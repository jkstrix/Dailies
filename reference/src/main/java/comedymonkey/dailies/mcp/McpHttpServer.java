package comedymonkey.dailies.mcp;

import comedymonkey.dailies.Host;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
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
 * <p>
 * Nobody can hold the server up for long: each request has one deadline to arrive in full, one address may hold only
 * some of the workers ({@link ClientShare}), and tools that run for minutes answer from threads of their own.
 */
public class McpHttpServer {

    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    /** How long one read may wait. A client that sends a byte now and then never meets it, hence the deadlines below */
    private static final int SOCKET_TIMEOUT_MILLIS = 15_000;
    private static final int WORKER_THREADS = 4;
    /** One address may hold this many of the workers at once, so there is always one left for another */
    static final int WORKERS_PER_ADDRESS = WORKER_THREADS - 1;
    /** And this many more of its connections may wait for their turn before the next is closed */
    static final int WAITING_PER_ADDRESS = 16;
    /** The threads that answer calls of long tools ({@link McpProtocol#LONG_TOOL_SECONDS}), and how many may wait for one */
    private static final int LONG_THREADS = 2;
    private static final int LONG_WAITING = 8;
    /** How long what a client still sends after the reply is read and thrown away, at most */
    private static final long DRAIN_MILLIS = 2_000;
    private static final String ENDPOINT = "/mcp";
    private static final String LIVE = "/live";
    private static final String PICTURES = "/pictures/";
    private static final long LIVE_KEEP_ALIVE_SECONDS = 15;

    /** Everything a running server was started with; a change to any of it means starting again */
    record Config(int port, String bind, boolean remote, String token, String allowedClients) {}

    /**
     * The deadlines a connection is held to: one for the whole request to arrive, the HTTPS greeting included, and one
     * for the reply to be taken. A connection still at it when its deadline passes is closed.
     */
    record Limits(int requestMillis, int replyMillis) {
        static final Limits NORMAL = new Limits(20_000, 30_000);
    }

    /** What answers the MCP endpoint: {@link McpProtocol} in the game, something smaller in the tests */
    public interface Endpoint {
        /** The reply to a request body, or null when there is nothing to reply with */
        @Nullable String handleMessage(String body);

        /** Whether answering the body may take minutes, so it is answered from a thread of its own */
        boolean callsLongTool(String body);
    }

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
            Path certificate = Host.get().dataDirectory().resolve("mcp").resolve("certificate.p12");
            // The key's password is kept in the game's config folder, away from Dailies' folder that can be copied or shared
            Path password = Host.get().configDirectory().resolve("dailies-mcp-key.secret");
            McpHttpServer server = new McpHttpServer(wanted, Limits.NORMAL, new McpProtocol(), certificate, password);
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
    private final Limits limits;
    private final Endpoint endpoint;
    private final Path certificateFile;
    private final Path passwordFile;
    private ServerSocket serverSocket;
    /** With remote access on a single address: the listener at 127.0.0.1 that this computer's own clients go on using */
    private @Nullable ServerSocket loopbackSocket = null;
    private @Nullable javax.net.ssl.SSLContext tls = null;
    private ExecutorService workers;
    private ClientShare share;
    private ExecutorService longWork;
    /** Closes connections whose deadline has passed */
    private ScheduledThreadPoolExecutor deadlines;
    private volatile boolean stopped = false;
    private HostRules hostRules = HostRules.local();
    private AddressRules addressRules = AddressRules.parse("");
    private @Nullable TokenGuard guard = null;
    private @Nullable ServerCertificate certificate = null;
    /** The connections of the live feed, by their plain socket: closing that frees a thread stuck writing over HTTPS too */
    private final Set<Socket> liveSockets = ConcurrentHashMap.newKeySet();
    private final Set<String> turnedAway = ConcurrentHashMap.newKeySet();
    private final AtomicInteger liveCount = new AtomicInteger();

    /**
     * @param certificateFile where the certificate for HTTPS is kept, used when the server listens beyond this computer
     * @param passwordFile    where the password that locks the certificate's key is kept, in another folder than the
     *                        certificate (see {@link KeyStorePassword})
     */
    McpHttpServer(Config config, Limits limits, Endpoint endpoint, Path certificateFile, Path passwordFile) {
        this.config = config;
        this.limits = limits;
        this.endpoint = endpoint;
        this.certificateFile = certificateFile;
        this.passwordFile = passwordFile;
    }

    /** The port listened on: the one asked for, unless that was 0 */
    int port() {
        return this.serverSocket.getLocalPort();
    }

    void start() throws Exception {
        if (this.config.remote) {
            this.startRemote();
        } else {
            this.serverSocket = new ServerSocket(this.config.port, 16, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
        }

        // Bounded queue: if something floods the port we drop connections instead of piling up memory
        this.workers = pool("Dailies-MCP-Worker-", WORKER_THREADS, 64);
        this.share = new ClientShare(WORKERS_PER_ADDRESS, WAITING_PER_ADDRESS, this.workers);
        this.longWork = pool("Dailies-MCP-Long-", LONG_THREADS, LONG_WAITING);
        this.deadlines = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "Dailies-MCP-Deadlines");
            thread.setDaemon(true);
            return thread;
        });
        this.deadlines.setRemoveOnCancelPolicy(true);

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

    private static ThreadPoolExecutor pool(String name, int threads, int waiting) {
        AtomicInteger threadCount = new AtomicInteger();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(waiting), runnable -> {
            Thread thread = new Thread(runnable, name + threadCount.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        executor.allowCoreThreadTimeOut(true);
        return executor;
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

        this.certificate = ServerCertificate.loadOrCreate(this.certificateFile, this.passwordFile, names, own, wildcard ? null : address);
        this.tls = this.certificate.context();
        // A plain listener: each connection says by its first byte whether it is HTTPS or this computer's plain HTTP
        this.serverSocket = new ServerSocket(this.config.port, 16, address);
        if (!wildcard) {
            // Bound to one network address, the port is not open at 127.0.0.1 at all, and that is where local clients look
            try {
                this.loopbackSocket = new ServerSocket(this.serverSocket.getLocalPort(), 16, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
            } catch (IOException e) {
                this.serverSocket.close();
                throw e;
            }
        }
    }

    void stop() {
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
        this.longWork.shutdown();
        // Deadlines already set still close their connections when they come
        this.deadlines.shutdown();
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

            // Served when its address has a worker to spare; closed when that address has too many waiting already
            if (!this.share.submit(socket.getInetAddress(), () -> this.handleConnection(socket), () -> closeQuietly(socket))) {
                closeQuietly(socket);
            }
        }
    }

    /**
     * Closes a connection once a deadline passes, unless the returned handle is cancelled first. The plain socket is
     * the one closed: that never waits, and whatever is reading or writing over it, HTTPS included, gives up at once.
     */
    private @Nullable ScheduledFuture<?> deadline(Socket raw, int millis) {
        try {
            return this.deadlines.schedule(() -> closeQuietly(raw), millis, TimeUnit.MILLISECONDS);
        } catch (RuntimeException stopping) {
            // The server is stopping, and closes everything anyway
            return null;
        }
    }

    private static void cancel(@Nullable ScheduledFuture<?> deadline) {
        if (deadline != null) {
            deadline.cancel(false);
        }
    }

    private void handleConnection(Socket accepted) {
        boolean handedOver = false;
        Socket socket = accepted;
        // One deadline for the whole request, the HTTPS greeting included: the read timeout only counts each pause, so
        // a client sending a byte now and then would otherwise keep this worker for as long as it liked
        ScheduledFuture<?> deadline = this.deadline(accepted, this.limits.requestMillis());
        try {
            accepted.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            accepted.setTcpNoDelay(true);

            javax.net.ssl.SSLContext context = this.tls;
            McpConnection connection = context == null ? McpConnection.plain(accepted) : McpConnection.open(accepted, context);
            socket = connection.socket();
            InputStream in = new BufferedInputStream(connection.in());
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
                // The client went away, timed out, ran out of time, or never finished its side of the HTTPS greeting
                return;
            } catch (Throwable t) {
                Host.LOGGER.error("Error handling MCP request", t);
                response = Response.text(500, "Internal server error");
            }
            // The request is in; answering it is not held to the deadline for reading it
            cancel(deadline);

            if (response.live) {
                LiveHub.Listener listener = LiveHub.listen();
                if (listener != null) {
                    handedOver = true;
                    this.startLive(accepted, socket, out, listener);
                    return;
                }
                response = Response.text(503, "Too many pages are already listening");
            }

            if (response.call != null) {
                if (this.endpoint.callsLongTool(response.call)) {
                    // Minutes of work: a thread of its own answers, and this worker goes back to reading requests
                    if (this.answerLater(accepted, socket, in, out, response.call)) {
                        handedOver = true;
                        return;
                    }
                    response = Response.text(503, "Too many long tasks are running already. Try again when one has finished");
                } else {
                    response = this.answer(response.call);
                }
            }

            this.reply(accepted, socket, in, out, response);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Client went away, nothing to do
        } finally {
            cancel(deadline);
            if (!handedOver) {
                closeQuietly(socket);
            }
        }
    }

    /** The reply of the MCP endpoint to a request body */
    private Response answer(String call) {
        try {
            String responseJson = this.endpoint.handleMessage(call);
            if (responseJson == null) {
                return new Response(202, null, null); // Only notifications/responses, nothing to reply with
            }
            return new Response(200, "application/json", responseJson.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            Host.LOGGER.error("Error handling MCP request", t);
            return Response.text(500, "Internal server error");
        }
    }

    /** @return false when the threads for long work are all busy and enough calls wait for them already */
    private boolean answerLater(Socket raw, Socket socket, InputStream in, OutputStream out, String call) {
        try {
            this.longWork.execute(() -> {
                try {
                    this.reply(raw, socket, in, out, this.answer(call));
                } catch (IOException | UnsupportedOperationException ignored) {
                    // The client gave up waiting
                } finally {
                    closeQuietly(socket);
                }
            });
            return true;
        } catch (RuntimeException busy) { // RejectedExecutionException
            return false;
        }
    }

    /** Writes the reply, then takes what the client still sends, for a short while at most, before the connection closes */
    private void reply(Socket raw, Socket socket, InputStream in, OutputStream out, Response response) throws IOException {
        // A client that will not take the reply gets as long as this, and no longer
        ScheduledFuture<?> deadline = this.deadline(raw, this.limits.replyMillis());
        try {
            writeResponse(out, response);

            // Error replies can be sent before the body was read. Closing with unread data makes the OS reset the
            // connection, which can hide the reply from the client, so half-close and drain what is left first.
            socket.shutdownOutput();
            socket.setSoTimeout(1000);

            long until = System.currentTimeMillis() + DRAIN_MILLIS;
            long drained = 0;
            byte[] scratch = new byte[4096];
            int read;
            while (drained < MAX_BODY_BYTES && System.currentTimeMillis() < until && (read = in.read(scratch)) >= 0) {
                drained += read;
            }
        } finally {
            cancel(deadline);
        }
    }

    /** The page's live feed: the connection stays open on a thread of its own and gets a line whenever a moment changes */
    private void startLive(Socket raw, Socket socket, OutputStream out, LiveHub.Listener listener) {
        this.liveSockets.add(raw);
        // A page that stopped reading leaves this thread stuck in a write, which has no timeout: once the feed lets the
        // page go, the connection is closed, and the write gives up
        listener.whenDropped(() -> closeQuietly(raw));
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
                this.liveSockets.remove(raw);
                closeQuietly(raw);
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
            return page();
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

        // A web page may send text/plain to any address without asking first; JSON it may only send after a question
        // this server never answers yes to. MCP clients send JSON anyway
        if (!isJson(headers.get("content-type"))) {
            throw new HttpError(415, "Send the request body as JSON, with 'Content-Type: application/json'");
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
        return Response.call(new String(body, StandardCharsets.UTF_8));
    }

    /** Whether a Content-Type header says JSON: application/json, with or without a charset after it */
    static boolean isJson(@Nullable String contentType) {
        if (contentType == null) {
            return false;
        }
        int semicolon = contentType.indexOf(';');
        String type = (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
        return type.equals("application/json");
    }

    private static Response page() throws IOException, HttpError {
        String html;
        try (InputStream resource = McpHttpServer.class.getResourceAsStream("/assets/dailies/web/moments.html")) {
            if (resource == null) {
                throw new HttpError(404, "The moments page is missing from this build");
            }
            // Browsers read every line ending as \n before they check the script against its hash, so it is sent that way too
            html = new String(resource.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n');
        }
        Response response = new Response(200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
        response.headers.put("Cache-Control", "no-store");
        // The page drives the game, so no other page may show it in a frame and trick a click out of someone
        response.headers.put("Content-Security-Policy", pagePolicy(html));
        response.headers.put("X-Frame-Options", "DENY");
        response.headers.put("Referrer-Policy", "no-referrer");
        return response;
    }

    /**
     * The Content-Security-Policy of the moments page: only its own script runs (named by its hash), it talks only to
     * this server, shows only its own pictures, and nobody may put it in a frame. Its style is inline, and stays so.
     */
    static String pagePolicy(String html) {
        String script = "'none'";
        int open = html.indexOf("<script>");
        int close = open < 0 ? -1 : html.indexOf("</script>", open);
        if (close >= 0) {
            String code = html.substring(open + "<script>".length(), close);
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
                script = "'sha256-" + Base64.getEncoder().encodeToString(digest) + "'";
            } catch (Exception e) {
                throw new IllegalStateException("SHA-256 is missing from this Java", e);
            }
        }
        return "default-src 'self'; script-src " + script + "; style-src 'self' 'unsafe-inline'; img-src 'self' blob:; "
            + "connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'";
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
            head.append("X-Content-Type-Options: nosniff\r\n");
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
            case 415 -> "Unsupported Media Type";
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
        /** Not a reply yet: the body of a request to the MCP endpoint, answered once it is known how long that takes */
        @Nullable String call = null;

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

        static Response call(String body) {
            Response response = new Response(200, null, null);
            response.call = body;
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
