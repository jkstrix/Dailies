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
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal HTTP/1.1 server for the MCP "Streamable HTTP" transport, built on a plain {@link ServerSocket} because
 * com.sun.net.httpserver isn't guaranteed to exist in the launcher's Java runtime.
 *
 * Left alone it only binds to 127.0.0.1. The MCP endpoint answers every request with Content-Length + Connection:
 * close and never streams, which the transport allows for servers that only ever reply to requests. The moments page
 * has a small stream of its own at GET /live, which is not part of MCP.
 * <p>
 * With mcpBind set to anything but localhost (specification MCP-8) the same port also speaks HTTPS with a certificate
 * of its own, to the addresses of mcpAllowedClients only, and wants the token of mcpToken on every HTTPS request but
 * the page itself, which has to load before it can ask for one. This computer's own agents and page carry on as they
 * were, over plain HTTP at 127.0.0.1 without a token: {@link McpConnection} tells the two kinds of connection apart.
 */
public class McpHttpServer {

    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final int SOCKET_TIMEOUT_MILLIS = 15_000;
    private static final int WORKER_THREADS = 4;
    private static final String ENDPOINT = "/mcp";
    private static final String LIVE = "/live";
    private static final String PICTURES = "/pictures/";
    private static final long LIVE_KEEP_ALIVE_SECONDS = 15;

    /** Everything a running server was started with; a change to any of it means starting again */
    private record Config(int port, String bind, boolean remote, String token, String allowedClients) {}

    private static McpHttpServer instance = null;

    /**
     * Start, stop or restart the server so that it matches the client config.
     * Called when the NeoForge client config is loaded or reloaded. Never throws.
     */
    public static void applyConfig() {
        try {
            if (!Host.get().settingsLoaded()) {
                return;
            }
            // Settings are saved outside the lock: saving can announce a change, which comes straight back here
            boolean enabled = Host.get().setting("mcpEnabled", true);
            String bind = bindSetting();
            boolean remote = !HostRules.isLocalBind(bind);
            if (enabled && remote && Host.get().setting("mcpToken", "").isBlank()) {
                Host.get().saveSetting("mcpToken", TokenGuard.generate());
                Host.LOGGER.info("Dailies MCP server: remote access was switched on, so a token was made. It is the mcpToken setting");
            }

            String fingerprint = restart(enabled);
            if (fingerprint != null && !fingerprint.equals(Host.get().setting("mcpCertificateFingerprint", ""))) {
                Host.get().saveSetting("mcpCertificateFingerprint", fingerprint);
            }
        } catch (Throwable t) {
            Host.LOGGER.warn("Unable to start Dailies MCP server", t);
        }
    }

    private static String bindSetting() {
        String bind = Host.get().setting("mcpBind", "127.0.0.1").trim();
        return bind.isEmpty() ? "127.0.0.1" : bind;
    }

    /** @return the fingerprint of the certificate in use, or null when the server is off or on localhost */
    private static synchronized @Nullable String restart(boolean enabled) {
        String bind = bindSetting();
        boolean remote = !HostRules.isLocalBind(bind);
        Config wanted = new Config(Host.get().setting("mcpPort", 25595), bind, remote,
            remote ? Host.get().setting("mcpToken", "").trim() : "",
            remote ? Host.get().setting("mcpAllowedClients", AddressRules.LOCAL_NETWORKS) : "");

        if (instance != null && (!enabled || !instance.config.equals(wanted))) {
            instance.stop();
            instance = null;
            Host.LOGGER.info("Dailies MCP server stopped");
        }

        if (enabled && instance == null) {
            McpHttpServer server = new McpHttpServer(wanted);
            try {
                server.start();
                instance = server;
                if (remote) {
                    Host.LOGGER.info("Dailies MCP server listening on https://{}:{}/mcp for {} allowed address range(s), and still on http://127.0.0.1:{}/mcp for this computer. Certificate SHA-256 fingerprint: {}",
                        bind, wanted.port, server.addressRules.size(), wanted.port, server.certificate.fingerprint());
                } else {
                    Host.LOGGER.info("Dailies MCP server listening on http://127.0.0.1:{}/mcp", wanted.port);
                }
            } catch (Exception e) {
                Host.LOGGER.warn("Dailies MCP server could not listen on {}:{} ({}). Change mcpPort or mcpBind in the Dailies settings", bind, wanted.port, e.toString());
            }
        }
        return instance == null || instance.certificate == null ? null : instance.certificate.fingerprint();
    }

    /** The port the server is currently listening on, or -1 if it isn't running */
    public static synchronized int getListeningPort() {
        return instance == null ? -1 : instance.config.port;
    }

    /** Whether the running server listens beyond this computer, and so speaks HTTPS and wants the token from other machines */
    public static synchronized boolean isRemote() {
        return instance != null && instance.config.remote;
    }

    /** The SHA-256 fingerprint of the certificate the running server shows, or null when it is on localhost or off */
    public static synchronized @Nullable String certificateFingerprint() {
        return instance == null || instance.certificate == null ? null : instance.certificate.fingerprint();
    }

    private final Config config;
    private final McpProtocol protocol = new McpProtocol();
    private ServerSocket serverSocket;
    /** With remote access on a single address: the listener at 127.0.0.1 that this computer's own clients go on using */
    private @Nullable ServerSocket loopbackSocket = null;
    private @Nullable javax.net.ssl.SSLContext tls = null;
    private ExecutorService workers;
    private volatile boolean stopped = false;
    private HostRules hostRules = HostRules.local();
    private AddressRules addressRules = AddressRules.parse("");
    private @Nullable TokenGuard guard = null;
    private @Nullable ServerCertificate certificate = null;
    private final Set<Socket> liveSockets = ConcurrentHashMap.newKeySet();
    private final Set<String> turnedAway = ConcurrentHashMap.newKeySet();
    private final AtomicInteger liveCount = new AtomicInteger();

    private McpHttpServer(Config config) {
        this.config = config;
    }

    private void start() throws Exception {
        if (this.config.remote) {
            this.startRemote();
        } else {
            this.serverSocket = new ServerSocket(this.config.port, 16, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
        }

        AtomicInteger threadCount = new AtomicInteger();
        // Bounded queue: if something floods the port we drop connections instead of piling up memory
        ThreadPoolExecutor executor = new ThreadPoolExecutor(WORKER_THREADS, WORKER_THREADS, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(64), runnable -> {
            Thread thread = new Thread(runnable, "Dailies-MCP-Worker-" + threadCount.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        executor.allowCoreThreadTimeOut(true);
        this.workers = executor;

        Thread acceptThread = new Thread(() -> this.acceptLoop(this.serverSocket), "Dailies-MCP-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        ServerSocket loopback = this.loopbackSocket;
        if (loopback != null) {
            Thread localThread = new Thread(() -> this.acceptLoop(loopback), "Dailies-MCP-Accept-Local");
            localThread.setDaemon(true);
            localThread.start();
        }
    }

    /** Beyond this computer: HTTPS, the allowed addresses, the token, and Host and Origin widened to the bound address */
    private void startRemote() throws Exception {
        InetAddress address = InetAddress.getByName(this.config.bind);
        boolean wildcard = address.isAnyLocalAddress();

        List<InetAddress> own = new ArrayList<>();
        own.add(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
        List<String> names = new ArrayList<>(List.of("localhost"));
        if (wildcard) {
            try {
                for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (network.isUp()) {
                        own.addAll(Collections.list(network.getInetAddresses()));
                    }
                }
                String hostName = InetAddress.getLocalHost().getHostName();
                if (hostName != null && hostName.matches("[A-Za-z0-9.-]+")) {
                    names.add(hostName);
                }
            } catch (Exception e) {
                Host.LOGGER.warn("Dailies MCP server: this computer's own addresses could not be listed ({}); only localhost will be answered to", e.toString());
            }
        } else if (!own.contains(address)) {
            own.add(address);
        }

        List<String> ownNames = new ArrayList<>(names);
        for (InetAddress each : own) {
            ownNames.add(each.getHostAddress());
        }
        this.hostRules = HostRules.forBind(this.config.bind, ownNames);
        this.addressRules = AddressRules.parse(this.config.allowedClients);
        if (!this.addressRules.rejected().isEmpty()) {
            Host.LOGGER.warn("Dailies MCP server: these entries of mcpAllowedClients are not addresses or ranges and were left out: {}", this.addressRules.rejected());
        }
        this.guard = new TokenGuard(this.config.token);

        Path file = Host.get().dataDirectory().resolve("mcp").resolve("certificate.p12");
        this.certificate = ServerCertificate.loadOrCreate(file, names, own, wildcard ? null : address);
        this.tls = this.certificate.context();
        // A plain listener: each connection says by its first byte whether it is HTTPS or this computer's plain HTTP
        this.serverSocket = new ServerSocket(this.config.port, 16, address);
        if (!wildcard) {
            // Bound to one network address, the port is not open at 127.0.0.1 at all, and that is where local clients look
            try {
                this.loopbackSocket = new ServerSocket(this.config.port, 16, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
            } catch (IOException e) {
                this.serverSocket.close();
                throw e;
            }
        }
    }

    private void stop() {
        this.stopped = true;
        try {
            this.serverSocket.close();
        } catch (IOException ignored) {}
        if (this.loopbackSocket != null) {
            try {
                this.loopbackSocket.close();
            } catch (IOException ignored) {}
        }
        for (Socket live : this.liveSockets) {
            closeQuietly(live);
        }
        // Let requests that are already running finish (config_set can stop the server it was called through)
        this.workers.shutdown();
    }

    private void acceptLoop(ServerSocket listener) {
        while (!this.stopped) {
            Socket socket;
            try {
                socket = listener.accept();
            } catch (IOException e) {
                if (!this.stopped) {
                    Host.LOGGER.warn("Dailies MCP server stopped accepting connections", e);
                }
                return;
            }

            if (this.config.remote && !this.addressRules.allows(socket.getInetAddress())) {
                // Turned away before a single byte is read. Said once per address, so a stranger cannot fill the log
                String client = socket.getInetAddress().getHostAddress();
                if (this.turnedAway.size() < 64 && this.turnedAway.add(client)) {
                    Host.LOGGER.warn("Dailies MCP server: turned away {}, which is not in mcpAllowedClients", client);
                }
                closeQuietly(socket);
                continue;
            }

            try {
                this.workers.execute(() -> this.handleConnection(socket));
            } catch (RuntimeException e) { // RejectedExecutionException: too busy or shutting down
                closeQuietly(socket);
            }
        }
    }

    private void handleConnection(Socket accepted) {
        boolean handedOver = false;
        Socket socket = accepted;
        try {
            accepted.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            accepted.setTcpNoDelay(true);

            javax.net.ssl.SSLContext context = this.tls;
            McpConnection connection = context == null ? McpConnection.plain(accepted) : McpConnection.open(accepted, context);
            socket = connection.socket();
            InputStream in = connection.in();
            OutputStream out = connection.out();

            Response response;
            try {
                if (context != null && !connection.secure() && !connection.plainAllowed()) {
                    // Plain HTTP is for this computer alone; nothing of the request is looked at
                    throw new HttpError(400, McpConnection.USE_HTTPS);
                }
                response = this.handleRequest(in, socket, connection.secure());
            } catch (HttpError e) {
                response = Response.text(e.status, e.getMessage());
                response.headers.putAll(e.headers);
            } catch (IOException e) {
                // The client went away, timed out, or never finished its side of the HTTPS greeting
                return;
            } catch (Throwable t) {
                Host.LOGGER.error("Error handling MCP request", t);
                response = Response.text(500, "Internal server error");
            }

            if (response.live) {
                LiveHub.Listener listener = LiveHub.listen();
                if (listener != null) {
                    handedOver = true;
                    this.startLive(socket, out, listener);
                    return;
                }
                response = Response.text(503, "Too many pages are already listening");
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
        } catch (IOException | UnsupportedOperationException ignored) {
            // Client went away, nothing to do
        } finally {
            if (!handedOver) {
                closeQuietly(socket);
            }
        }
    }

    /** The page's live feed: the connection stays open on a thread of its own and gets a line whenever a moment changes */
    private void startLive(Socket socket, OutputStream out, LiveHub.Listener listener) {
        this.liveSockets.add(socket);
        Thread thread = new Thread(() -> {
            try {
                socket.setSoTimeout(0);
                String head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
                out.write(head.getBytes(StandardCharsets.ISO_8859_1));
                out.write("retry: 3000\n: connected\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                while (!this.stopped && !listener.dropped()) {
                    String message = listener.next(LIVE_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS);
                    // A comment line now and then is how a page that has gone away gets noticed
                    out.write((message == null ? ": keep-alive\n\n" : message).getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (IOException | InterruptedException ignored) {
                // The page was closed
            } finally {
                LiveHub.leave(listener);
                this.liveSockets.remove(socket);
                closeQuietly(socket);
            }
        }, "Dailies-MCP-Live-" + this.liveCount.incrementAndGet());
        thread.setDaemon(true);
        thread.start();
    }

    /** @param secure whether the request came over HTTPS, and so from (or as if from) another machine */
    private Response handleRequest(InputStream in, Socket socket, boolean secure) throws IOException, HttpError {
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
        // A plain request is this computer's own, and is held to localhost names exactly as it always was
        HostRules hostRules = secure ? this.hostRules : HostRules.local();
        if (!hostRules.originAllowed(origin)) {
            throw new HttpError(403, "Forbidden: requests from origin '" + origin + "' are not allowed");
        }
        // Protects against DNS rebinding, where a hostile hostname is pointed at this server's address
        if (!hostRules.hostAllowed(headers.get("host"))) {
            throw new HttpError(403, "Forbidden: unexpected Host header");
        }

        boolean page = method.equals("GET") && (path.equals("/") || path.equals("/moments") || path.equals("/moments/"));
        TokenGuard guard = secure ? this.guard : null;
        // Over HTTPS the page is the one thing served without the token: it holds no data, and it is what asks for the token
        if (guard != null && !page) {
            String client = socket.getInetAddress().getHostAddress();
            long now = System.currentTimeMillis();
            switch (guard.check(client, headers.get("authorization"), now)) {
                case OK -> {}
                case LOCKED -> {
                    HttpError error = new HttpError(429, "Too many wrong tokens from this address. Try again in a minute");
                    error.headers.put("Retry-After", String.valueOf(Math.max(1, guard.lockedSeconds(client, now))));
                    throw error;
                }
                case MISSING, WRONG -> {
                    HttpError error = new HttpError(401, "This server is reachable from other machines, so it needs its token: send 'Authorization: Bearer <mcpToken>'");
                    error.headers.put("WWW-Authenticate", "Bearer");
                    throw error;
                }
            }
        }

        // The moments page: a plain static page that talks to the MCP endpoint below from the same origin
        if (page) {
            try (InputStream resource = McpHttpServer.class.getResourceAsStream("/assets/dailies/web/moments.html")) {
                if (resource == null) {
                    throw new HttpError(404, "The moments page is missing from this build");
                }
                Response response = new Response(200, "text/html; charset=utf-8", resource.readAllBytes());
                response.headers.put("Cache-Control", "no-store");
                return response;
            }
        }

        if (method.equals("GET") && path.equals(LIVE)) {
            return Response.goLive();
        }

        if (method.equals("GET") && path.startsWith(PICTURES)) {
            return picture(path.substring(PICTURES.length()));
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

    /** An event's picture: /pictures/<session id>/<event id>.jpg, from the session's own folder and nowhere else */
    private static Response picture(String rest) throws IOException, HttpError {
        int slash = rest.indexOf('/');
        String name = slash < 0 ? "" : rest.substring(slash + 1);
        comedymonkey.dailies.moments.storage.MomentsSession session = slash <= 0 || !name.matches("[A-Za-z0-9_-]{1,64}\\.jpg") ? null
            : comedymonkey.dailies.moments.Moments.storage().getSession(rest.substring(0, slash));
        Path file = session == null ? null : session.dir.resolve("pictures").resolve(name);
        if (file == null || !Files.isRegularFile(file)) {
            throw new HttpError(404, "There is no such picture");
        }
        Response response = new Response(200, "image/jpeg", Files.readAllBytes(file));
        // A picture never changes once written
        response.headers.put("Cache-Control", "private, max-age=86400");
        return response;
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
        for (Map.Entry<String, String> header : response.headers.entrySet()) {
            head.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        }
        head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("Connection: close\r\n");
        head.append("\r\n");

        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static String reasonPhrase(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 202 -> "Accepted";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 429 -> "Too Many Requests";
            case 503 -> "Service Unavailable";
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
        final Map<String, String> headers = new LinkedHashMap<>();
        boolean live = false;

        Response(int status, @Nullable String contentType, byte @Nullable [] body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }

        /** Not a reply at all: the connection becomes a listener of the live feed */
        static Response goLive() {
            Response response = new Response(200, null, null);
            response.live = true;
            return response;
        }

        static Response text(int status, String message) {
            return new Response(status, "text/plain; charset=utf-8", message.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static class HttpError extends Exception {
        final int status;
        final Map<String, String> headers = new LinkedHashMap<>();

        HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

}
