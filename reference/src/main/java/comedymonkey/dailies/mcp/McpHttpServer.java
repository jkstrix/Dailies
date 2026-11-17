package comedymonkey.dailies.mcp;

import comedymonkey.dailies.Host;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal HTTP/1.1 server for the MCP "Streamable HTTP" transport, built on a plain {@link ServerSocket} because
 * com.sun.net.httpserver isn't guaranteed to exist in the launcher's Java runtime.
 *
 * Only binds to 127.0.0.1, answers every request with Content-Length + Connection: close and never streams (SSE),
 * which the transport allows for servers that only ever reply to requests.
 */
public class McpHttpServer {

    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final int SOCKET_TIMEOUT_MILLIS = 15_000;
    private static final int WORKER_THREADS = 4;
    private static final String ENDPOINT = "/mcp";

    private static McpHttpServer instance = null;

    /**
     * Start, stop or restart the server so that it matches the client config.
     * Called when the NeoForge client config is loaded or reloaded. Never throws.
     */
    public static synchronized void applyConfig() {
        try {
            if (!Host.get().settingsLoaded()) {
                return;
            }

            boolean enabled = Host.get().setting("mcpEnabled", true);
            int port = Host.get().setting("mcpPort", 25595);

            if (instance != null && (!enabled || instance.port != port)) {
                instance.stop();
                instance = null;
                Host.LOGGER.info("Dailies MCP server stopped");
            }

            if (enabled && instance == null) {
                McpHttpServer server = new McpHttpServer(port);
                try {
                    server.start();
                    instance = server;
                    Host.LOGGER.info("Dailies MCP server listening on http://127.0.0.1:{}/mcp", port);
                } catch (IOException e) {
                    Host.LOGGER.warn("Dailies MCP server could not listen on 127.0.0.1:{} ({}). Change mcpPort in the Dailies config to use another port", port, e.toString());
                }
            }
        } catch (Throwable t) {
            Host.LOGGER.warn("Unable to start Dailies MCP server", t);
        }
    }

    /** The port the server is currently listening on, or -1 if it isn't running */
    public static synchronized int getListeningPort() {
        return instance == null ? -1 : instance.port;
    }

    private final int port;
    private final McpProtocol protocol = new McpProtocol();
    private ServerSocket serverSocket;
    private ExecutorService workers;
    private volatile boolean stopped = false;

    private McpHttpServer(int port) {
        this.port = port;
    }

    private void start() throws IOException {
        this.serverSocket = new ServerSocket(this.port, 16, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));

        AtomicInteger threadCount = new AtomicInteger();
        // Bounded queue: if something floods the port we drop connections instead of piling up memory
        ThreadPoolExecutor executor = new ThreadPoolExecutor(WORKER_THREADS, WORKER_THREADS, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(64), runnable -> {
            Thread thread = new Thread(runnable, "Dailies-MCP-Worker-" + threadCount.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        executor.allowCoreThreadTimeOut(true);
        this.workers = executor;

        Thread acceptThread = new Thread(this::acceptLoop, "Dailies-MCP-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void stop() {
        this.stopped = true;
        try {
            this.serverSocket.close();
        } catch (IOException ignored) {}
        // Let requests that are already running finish (config_set can stop the server it was called through)
        this.workers.shutdown();
    }

    private void acceptLoop() {
        while (!this.stopped) {
            Socket socket;
            try {
                socket = this.serverSocket.accept();
            } catch (IOException e) {
                if (!this.stopped) {
                    Host.LOGGER.warn("Dailies MCP server stopped accepting connections", e);
                }
                return;
            }

            try {
                this.workers.execute(() -> this.handleConnection(socket));
            } catch (RuntimeException e) { // RejectedExecutionException: too busy or shutting down
                closeQuietly(socket);
            }
        }
    }

    private void handleConnection(Socket socket) {
        try (socket) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            socket.setTcpNoDelay(true);

            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            Response response;
            try {
                response = this.handleRequest(in);
            } catch (HttpError e) {
                response = Response.text(e.status, e.getMessage());
            } catch (SocketException | java.net.SocketTimeoutException e) {
                return;
            } catch (Throwable t) {
                Host.LOGGER.error("Error handling MCP request", t);
                response = Response.text(500, "Internal server error");
            }

            writeResponse(out, response);

            // Error replies can be sent before the body was read. Closing with unread data makes the OS reset the
            // connection, which can hide the reply from the client, so half-close and drain what is left first.
            socket.shutdownOutput();
            socket.setSoTimeout(1000);

            long drained = 0;
            byte[] scratch = new byte[4096];
            int read;
            while (drained < MAX_BODY_BYTES && (read = in.read(scratch)) >= 0) {
                drained += read;
            }
        } catch (IOException ignored) {
            // Client went away, nothing to do
        }
    }

    private Response handleRequest(InputStream in) throws IOException, HttpError {
        String head = readHead(in);
        String[] lines = head.split("\r\n");

        String[] requestLine = lines[0].split(" ");
        if (requestLine.length != 3 || !requestLine[2].startsWith("HTTP/1.")) {
            throw new HttpError(400, "Malformed request line");
        }
        String method = requestLine[0];
        String path = requestLine[1];
        int queryStart = path.indexOf('?');
        if (queryStart >= 0) {
            path = path.substring(0, queryStart);
        }

        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) {
                throw new HttpError(400, "Malformed header");
            }
            headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
        }

        // Browsers always send Origin on cross-site requests, so this stops web pages from driving the game.
        // Non-browser MCP clients don't send it at all.
        String origin = headers.get("origin");
        if (origin != null && !isLocalOrigin(origin)) {
            throw new HttpError(403, "Forbidden: requests from origin '" + origin + "' are not allowed");
        }
        // Protects against DNS rebinding, where a hostile hostname is pointed at 127.0.0.1
        String host = headers.get("host");
        if (host != null && !isLocalHost(stripPort(host))) {
            throw new HttpError(403, "Forbidden: unexpected Host header");
        }

        // The moments page: a plain static page that talks to the MCP endpoint below from the same origin
        if (method.equals("GET") && (path.equals("/") || path.equals("/moments") || path.equals("/moments/"))) {
            try (InputStream page = McpHttpServer.class.getResourceAsStream("/assets/dailies/web/moments.html")) {
                if (page == null) {
                    throw new HttpError(404, "The moments page is missing from this build");
                }
                return new Response(200, "text/html; charset=utf-8", page.readAllBytes());
            }
        }

        if (!path.equals(ENDPOINT) && !path.equals(ENDPOINT + "/")) {
            throw new HttpError(404, "Not found. The MCP endpoint is " + ENDPOINT);
        }

        if (!method.equals("POST")) {
            // GET would open a server-to-client SSE stream, which this server doesn't offer
            Response response = Response.text(405, "Method not allowed. Use POST " + ENDPOINT);
            response.allow = "POST";
            return response;
        }

        if (headers.containsKey("transfer-encoding")) {
            throw new HttpError(411, "Chunked request bodies are not supported, send Content-Length");
        }
        String contentLengthString = headers.get("content-length");
        if (contentLengthString == null) {
            throw new HttpError(411, "Content-Length required");
        }
        int contentLength;
        try {
            contentLength = Integer.parseInt(contentLengthString);
        } catch (NumberFormatException e) {
            throw new HttpError(400, "Invalid Content-Length");
        }
        if (contentLength < 0) {
            throw new HttpError(400, "Invalid Content-Length");
        }
        if (contentLength > MAX_BODY_BYTES) {
            throw new HttpError(413, "Request body too large (limit is " + MAX_BODY_BYTES + " bytes)");
        }

        byte[] body = in.readNBytes(contentLength);
        if (body.length != contentLength) {
            throw new HttpError(400, "Request body shorter than Content-Length");
        }

        String responseJson = this.protocol.handleMessage(new String(body, StandardCharsets.UTF_8));
        if (responseJson == null) {
            return new Response(202, null, null); // Only notifications/responses, nothing to reply with
        }
        return new Response(200, "application/json", responseJson.getBytes(StandardCharsets.UTF_8));
    }

    /** Reads up to and including the blank line that ends the headers */
    private static String readHead(InputStream in) throws IOException, HttpError {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(512);
        int matched = 0; // how much of \r\n\r\n has been seen
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new HttpError(400, "Incomplete request");
            }
            buffer.write(b);
            if (buffer.size() > MAX_HEADER_BYTES) {
                throw new HttpError(431, "Request headers too large");
            }

            if (b == (matched % 2 == 0 ? '\r' : '\n')) {
                matched += 1;
                if (matched == 4) {
                    break;
                }
            } else {
                matched = b == '\r' ? 1 : 0;
            }
        }
        String head = buffer.toString(StandardCharsets.ISO_8859_1);
        return head.substring(0, head.length() - 4);
    }

    private static void writeResponse(OutputStream out, Response response) throws IOException {
        byte[] body = response.body == null ? new byte[0] : response.body;

        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(response.status).append(' ').append(reasonPhrase(response.status)).append("\r\n");
        if (response.contentType != null) {
            head.append("Content-Type: ").append(response.contentType).append("\r\n");
        }
        if (response.allow != null) {
            head.append("Allow: ").append(response.allow).append("\r\n");
        }
        head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("Connection: close\r\n");
        head.append("\r\n");

        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static boolean isLocalOrigin(String origin) {
        try {
            URI uri = URI.create(origin);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
                return false;
            }
            return isLocalHost(uri.getHost());
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isLocalHost(@Nullable String host) {
        if (host == null) {
            return false;
        }
        host = host.toLowerCase(Locale.ROOT);
        return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1");
    }

    private static String stripPort(String host) {
        if (host.startsWith("[")) { // IPv6 literal
            int end = host.indexOf(']');
            return end < 0 ? host : host.substring(0, end + 1);
        }
        int colon = host.lastIndexOf(':');
        return colon < 0 ? host : host.substring(0, colon);
    }

    private static String reasonPhrase(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 202 -> "Accepted";
            case 400 -> "Bad Request";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 411 -> "Length Required";
            case 413 -> "Content Too Large";
            case 431 -> "Request Header Fields Too Large";
            default -> "Internal Server Error";
        };
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {}
    }

    private static class Response {
        final int status;
        final @Nullable String contentType;
        final byte @Nullable [] body;
        @Nullable String allow = null;

        Response(int status, @Nullable String contentType, byte @Nullable [] body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }

        static Response text(int status, String message) {
            return new Response(status, "text/plain; charset=utf-8", message.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static class HttpError extends Exception {
        final int status;

        HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

}
