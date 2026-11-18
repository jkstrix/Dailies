package comedymonkey.dailies.mcp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The MCP server's handling of requests, over real loopback sockets (specification MCP-3, MCP-8): what it refuses
 * before a tool is ever reached, and the deadlines that keep a slow client from holding it up.
 */
class McpHttpServerTest {

    private static final String PING = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
    private static final String REPLY = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}";

    /** Answers every call with the same reply; a body that mentions "long" counts as a long tool's */
    private static final McpHttpServer.Endpoint ENDPOINT = new McpHttpServer.Endpoint() {
        @Override
        public String handleMessage(String body) {
            return REPLY;
        }

        @Override
        public boolean callsLongTool(String body) {
            return body.contains("long");
        }
    };

    private final List<McpHttpServer> servers = new ArrayList<>();

    @AfterEach
    void stopServers() {
        this.servers.forEach(McpHttpServer::stop);
    }

    private McpHttpServer local(McpHttpServer.Limits limits) throws Exception {
        McpHttpServer server = new McpHttpServer(new McpHttpServer.Config(0, "127.0.0.1", false, "", ""), limits, ENDPOINT,
            Files.createTempDirectory("dailies-mcp").resolve("certificate.p12"), Files.createTempDirectory("dailies-config").resolve("key.secret"));
        server.start();
        this.servers.add(server);
        return server;
    }

    private McpHttpServer local() throws Exception {
        return this.local(McpHttpServer.Limits.NORMAL);
    }

    private static InetAddress loopback() throws Exception {
        return InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
    }

    private record Reply(int status, String head, String body) {
        String header(String name) {
            for (String line : this.head.split("\r\n")) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                    return line.substring(colon + 1).trim();
                }
            }
            return null;
        }
    }

    /** Sends a request as given and reads the reply until the server closes the connection */
    private static Reply exchange(Socket socket, String request) throws Exception {
        socket.setSoTimeout(10_000);
        OutputStream out = socket.getOutputStream();
        out.write(request.getBytes(StandardCharsets.UTF_8));
        out.flush();
        byte[] all = socket.getInputStream().readAllBytes();
        String text = new String(all, StandardCharsets.UTF_8);
        int end = text.indexOf("\r\n\r\n");
        String head = end < 0 ? text : text.substring(0, end);
        String body = end < 0 ? "" : text.substring(end + 4);
        String[] statusLine = head.split("\r\n")[0].split(" ");
        return new Reply(Integer.parseInt(statusLine[1]), head, body);
    }

    private static String post(String host, String extraHeaders, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return "POST /mcp HTTP/1.1\r\nHost: " + host + "\r\n" + extraHeaders + "Content-Length: " + bytes.length + "\r\n\r\n" + body;
    }

    private static Reply send(McpHttpServer server, String request) throws Exception {
        try (Socket socket = new Socket(loopback(), server.port())) {
            return exchange(socket, request);
        }
    }

    // --- What is refused before any tool is reached ---

    @Test
    void aPlainJsonCallFromThisComputerIsAnswered() throws Exception {
        McpHttpServer server = this.local();
        Reply reply = send(server, post("127.0.0.1:" + server.port(), "Content-Type: application/json\r\n", PING));
        assertEquals(200, reply.status());
        assertEquals(REPLY, reply.body());
        // And with a charset after the type
        assertEquals(200, send(server, post("localhost:" + server.port(), "Content-Type: application/json; charset=utf-8\r\n", PING)).status());
    }

    @Test
    void aStrangeHostOrOriginIsForbidden() throws Exception {
        McpHttpServer server = this.local();
        // DNS rebinding: a hostile name pointed at 127.0.0.1
        assertEquals(403, send(server, post("attacker.example:" + server.port(), "Content-Type: application/json\r\n", PING)).status());
        // A web page somewhere else
        assertEquals(403, send(server, post("127.0.0.1:" + server.port(), "Origin: http://attacker.example\r\nContent-Type: application/json\r\n", PING)).status());
        assertEquals(403, send(server, post("127.0.0.1:" + server.port(), "Origin: null\r\nContent-Type: application/json\r\n", PING)).status());
    }

    @Test
    void onlyJsonIsTakenSoNoPageCanPostWithoutAskingFirst() throws Exception {
        McpHttpServer server = this.local();
        String host = "127.0.0.1:" + server.port();
        // What a form or a fetch without a preflight can send from any page
        for (String type : List.of("text/plain", "application/x-www-form-urlencoded", "multipart/form-data; boundary=x", "application/jsonx")) {
            assertEquals(415, send(server, post(host, "Content-Type: " + type + "\r\n", PING)).status(), type);
        }
        assertEquals(415, send(server, post(host, "", PING)).status(), "no Content-Type at all");
        assertTrue(McpHttpServer.isJson("Application/JSON ; charset=UTF-8"));
        assertFalse(McpHttpServer.isJson(null));
        assertFalse(McpHttpServer.isJson("text/json"));
    }

    @Test
    void anOversizedBodyIsRefusedBeforeItIsRead() throws Exception {
        McpHttpServer server = this.local();
        String request = "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:" + server.port() + "\r\nContent-Type: application/json\r\nContent-Length: "
            + (2 * 1024 * 1024) + "\r\n\r\n";
        long started = System.nanoTime();
        Reply reply = send(server, request);
        assertEquals(413, reply.status());
        // The reply came without the server waiting for two megabytes that never arrive
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(8));
    }

    @Test
    void otherPathsAndMethodsAreRefused() throws Exception {
        McpHttpServer server = this.local();
        String host = "Host: 127.0.0.1:" + server.port() + "\r\n";
        assertEquals(404, send(server, "GET /nothing HTTP/1.1\r\n" + host + "\r\n").status());
        Reply get = send(server, "GET /mcp HTTP/1.1\r\n" + host + "\r\n");
        assertEquals(405, get.status());
        assertEquals("POST", get.header("Allow"));
        assertEquals(411, send(server, "POST /mcp HTTP/1.1\r\n" + host + "Content-Type: application/json\r\n\r\n").status());
        assertEquals(400, send(server, "NONSENSE\r\n" + host + "\r\n").status());
    }

    @Test
    void aLongToolIsAnsweredFromAThreadOfItsOwn() throws Exception {
        McpHttpServer server = this.local();
        Reply reply = send(server, post("127.0.0.1:" + server.port(), "Content-Type: application/json\r\n", "{\"long\":true}"));
        assertEquals(200, reply.status());
        assertEquals(REPLY, reply.body());
    }

    // --- The page ---

    @Test
    void thePageCannotBeFramedAndRunsOnlyItsOwnScript() throws Exception {
        McpHttpServer server = this.local();
        Reply reply = send(server, "GET / HTTP/1.1\r\nHost: 127.0.0.1:" + server.port() + "\r\n\r\n");
        assertEquals(200, reply.status());
        assertEquals("DENY", reply.header("X-Frame-Options"));
        String policy = reply.header("Content-Security-Policy");
        assertTrue(policy.contains("frame-ancestors 'none'"), policy);
        assertTrue(policy.contains("default-src 'self'"), policy);
        assertFalse(policy.contains("script-src 'unsafe-inline'"), policy);

        // The hash in the policy is the one of the script the page carries, exactly as served
        String body = reply.body();
        assertFalse(body.contains("\r"), "the page is served with plain \\n line endings, as browsers hash it");
        int open = body.indexOf("<script>");
        int close = body.indexOf("</script>");
        assertTrue(open >= 0 && close > open);
        assertEquals(open, body.lastIndexOf("<script"), "the page has a single script, inline, and nothing else to allow");
        String code = body.substring(open + "<script>".length(), close);
        String hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
        assertTrue(policy.contains("script-src 'sha256-" + hash + "'"), policy);
        // Handlers written into the markup would be blocked by the policy; the page adds its own from the script
        String markup = (body.substring(0, open) + body.substring(close)).toLowerCase(Locale.ROOT);
        assertFalse(markup.matches("(?s).*<[a-z]+[^>]*\\son[a-z]+\\s*=.*"), "an inline event handler in the page's markup");
    }

    @Test
    void aPolicyWithoutAScriptAllowsNone() {
        assertTrue(McpHttpServer.pagePolicy("<p>nothing</p>").contains("script-src 'none'"));
        String policy = McpHttpServer.pagePolicy("<script>\nlet a = 1;\n</script>");
        assertTrue(policy.contains("'sha256-"), policy);
    }

    // --- Deadlines ---

    @Test
    void aClientThatSendsItsHeadersSlowlyIsCutOffAtTheDeadline() throws Exception {
        // Each pause is well inside the 15 second read timeout; only the deadline for the whole request ends it
        McpHttpServer server = this.local(new McpHttpServer.Limits(1500, 5000));
        try (Socket socket = new Socket(loopback(), server.port())) {
            socket.setSoTimeout(12_000);
            OutputStream out = socket.getOutputStream();
            out.write("POST /mcp HTTP/1.1\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            AtomicBoolean writing = new AtomicBoolean(true);
            Thread drip = new Thread(() -> {
                try {
                    while (writing.get()) {
                        out.write('X');
                        out.flush();
                        Thread.sleep(200);
                    }
                } catch (Exception closed) {
                    // The server closed the connection, which is the point
                }
            }, "slow-client");
            drip.setDaemon(true);
            long started = System.nanoTime();
            drip.start();
            int read;
            try {
                read = socket.getInputStream().read();
            } catch (IOException reset) {
                read = -1;
            } finally {
                writing.set(false);
            }
            long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertEquals(-1, read, "the server answered a request it never got in full");
            assertTrue(took >= 1000 && took < 8000, "closed after " + took + " ms");
        }
    }

    @Test
    void aClientThatSaysNothingIsCutOffToo() throws Exception {
        McpHttpServer server = this.local(new McpHttpServer.Limits(1000, 5000));
        try (Socket socket = new Socket(loopback(), server.port())) {
            socket.setSoTimeout(12_000);
            long started = System.nanoTime();
            int read;
            try {
                read = socket.getInputStream().read();
            } catch (IOException reset) {
                read = -1;
            }
            assertEquals(-1, read);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 8000);
        }
    }

    @Test
    void slowClientsOfOneAddressLeaveAWorkerForTheNextRequest() throws Exception {
        McpHttpServer server = this.local(new McpHttpServer.Limits(10_000, 10_000));
        List<Socket> slow = new ArrayList<>();
        try {
            // More silent connections than there are workers, all from 127.0.0.1
            for (int i = 0; i < 6; i++) {
                slow.add(new Socket(loopback(), server.port()));
            }
            // Another address of this computer still gets its answer at once
            Socket other;
            try {
                other = new Socket();
                other.bind(new java.net.InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 2}), 0));
                other.connect(new java.net.InetSocketAddress(loopback(), server.port()), 5000);
            } catch (IOException e) {
                assumeTrue(false, "this computer cannot use 127.0.0.2 as a source address: " + e);
                return;
            }
            try (Socket socket = other) {
                long started = System.nanoTime();
                Reply reply = exchange(socket, post("127.0.0.1:" + server.port(), "Content-Type: application/json\r\n", PING));
                assertEquals(200, reply.status());
                assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5));
            }
        } finally {
            for (Socket socket : slow) {
                socket.close();
            }
        }
    }

    // --- The token, over HTTPS ---

    private static SSLContext trustAnything() throws Exception {
        TrustManager anything = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{anything}, null);
        return context;
    }

    @Test
    void overHttpsTheTokenIsNeeded() throws Exception {
        // 127.0.0.2 counts as another address, so remote access is on, while nothing is opened to the network
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[]{127, 0, 0, 2}))) {
            probe.getLocalPort();
        } catch (IOException e) {
            assumeTrue(false, "this computer cannot listen on 127.0.0.2: " + e);
        }
        String token = TokenGuard.generate();
        Path certificate = Files.createTempDirectory("dailies-mcp").resolve("certificate.p12");
        McpHttpServer server = new McpHttpServer(new McpHttpServer.Config(0, "127.0.0.2", true, token, ""), McpHttpServer.Limits.NORMAL, ENDPOINT, certificate,
            Files.createTempDirectory("dailies-config").resolve("key.secret"));
        server.start();
        this.servers.add(server);
        InetAddress bound = InetAddress.getByAddress(new byte[]{127, 0, 0, 2});
        String host = "127.0.0.2:" + server.port();
        SSLContext client = trustAnything();

        try (SSLSocket socket = (SSLSocket) client.getSocketFactory().createSocket(bound, server.port())) {
            Reply reply = exchange(socket, post(host, "Content-Type: application/json\r\n", PING));
            assertEquals(401, reply.status());
            assertEquals("Bearer", reply.header("WWW-Authenticate"));
        }
        try (SSLSocket socket = (SSLSocket) client.getSocketFactory().createSocket(bound, server.port())) {
            assertEquals(401, exchange(socket, post(host, "Authorization: Bearer wrong\r\nContent-Type: application/json\r\n", PING)).status());
        }
        try (SSLSocket socket = (SSLSocket) client.getSocketFactory().createSocket(bound, server.port())) {
            Reply reply = exchange(socket, post(host, "Authorization: Bearer " + token + "\r\nContent-Type: application/json\r\n", PING));
            assertEquals(200, reply.status());
            assertEquals(REPLY, reply.body());
        }
        // The page itself is served without the token: it is what asks for it
        try (SSLSocket socket = (SSLSocket) client.getSocketFactory().createSocket(bound, server.port())) {
            assertEquals(200, exchange(socket, "GET / HTTP/1.1\r\nHost: " + host + "\r\n\r\n").status());
        }
        // This computer's own plain HTTP needs no token, as before
        try (Socket socket = new Socket(loopback(), server.port())) {
            assertEquals(200, exchange(socket, post("127.0.0.1:" + server.port(), "Content-Type: application/json\r\n", PING)).status());
        }
    }

    // --- The live feed lets a page go by closing it ---

    @Test
    void aDroppedListenerHasItsConnectionClosed() throws Exception {
        LiveHub.Listener listener = LiveHub.listen();
        CountDownLatch closed = new CountDownLatch(1);
        listener.whenDropped(closed::countDown);
        for (int i = 0; i < 600 && !listener.dropped(); i++) {
            LiveHub.publish("event", "{}");
        }
        assertTrue(listener.dropped());
        assertTrue(closed.await(1, TimeUnit.SECONDS));

        // Asked for after the listener was let go: done at once
        CountDownLatch late = new CountDownLatch(1);
        listener.whenDropped(late::countDown);
        assertEquals(0, late.getCount());
    }

}
