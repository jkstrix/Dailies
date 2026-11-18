package comedymonkey.dailies.mcp;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rules that guard the MCP server once it can be reached from another machine (specification MCP-3, MCP-8) */
class RemoteAccessTest {

    private static InetAddress address(String literal) throws Exception {
        return InetAddress.getByAddress(AddressRules.literal(literal));
    }

    // --- Allowed clients ---

    @Test
    void theDefaultListIsTheLocalNetworksAndNothingElse() throws Exception {
        AddressRules rules = AddressRules.parse(AddressRules.LOCAL_NETWORKS);
        assertTrue(rules.rejected().isEmpty());
        for (String inside : List.of("10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.254", "192.168.1.50", "fd12:3456::1", "fe80::1")) {
            assertTrue(rules.allows(address(inside)), inside);
        }
        for (String outside : List.of("8.8.8.8", "172.15.255.255", "172.32.0.1", "192.169.0.1", "11.0.0.1", "2001:db8::1")) {
            assertFalse(rules.allows(address(outside)), outside);
        }
    }

    @Test
    void thisComputerIsAlwaysAllowed() throws Exception {
        AddressRules nobody = AddressRules.parse("");
        assertTrue(nobody.allows(address("127.0.0.1")));
        assertTrue(nobody.allows(address("127.0.0.2")));
        assertTrue(nobody.allows(address("::1")));
        assertFalse(nobody.allows(address("192.168.1.2")));
        assertFalse(nobody.allows(null));
    }

    @Test
    void singleAddressesAndRangesOfAnySize() throws Exception {
        AddressRules rules = AddressRules.parse("192.168.1.20, 192.168.2.0/24\n10.1.2.128/25  fd00::/8");
        assertEquals(4, rules.size());
        assertTrue(rules.allows(address("192.168.1.20")));
        assertFalse(rules.allows(address("192.168.1.21")));
        assertTrue(rules.allows(address("192.168.2.200")));
        assertFalse(rules.allows(address("192.168.3.1")));
        assertTrue(rules.allows(address("10.1.2.129")));
        assertFalse(rules.allows(address("10.1.2.127")));
        assertTrue(rules.allows(address("fd00:1::9")));
        assertFalse(rules.allows(address("fe80::1")));
    }

    @Test
    void anIpv4AddressWrittenTheIpv6WayMatchesIpv4Ranges() throws Exception {
        AddressRules rules = AddressRules.parse("192.168.0.0/16");
        assertTrue(rules.allows(address("::ffff:192.168.1.5")));
    }

    @Test
    void nonsenseIsLeftOutAndNamed() {
        AddressRules rules = AddressRules.parse("192.168.1.0/33, my-nas.local, 300.1.1.1, 10.0.0.0/8, bad.cafe, 1.2.3.4/x");
        assertEquals(1, rules.size());
        assertEquals(List.of("192.168.1.0/33", "my-nas.local", "300.1.1.1", "bad.cafe", "1.2.3.4/x"), rules.rejected());
    }

    @Test
    void everythingAllowedTakesSayingSo() throws Exception {
        AddressRules rules = AddressRules.parse("0.0.0.0/0");
        assertTrue(rules.allows(address("8.8.8.8")));
        assertFalse(rules.allows(address("2001:db8::1")), "an IPv4 range says nothing about IPv6");
    }

    // --- The token and the lockout ---

    @Test
    void theTokenMustBeSentAsABearerHeader() {
        TokenGuard guard = new TokenGuard("s3cret");
        assertEquals(TokenGuard.Verdict.OK, guard.check("10.0.0.5", "Bearer s3cret", 0));
        assertEquals(TokenGuard.Verdict.OK, guard.check("10.0.0.5", "bearer   s3cret ", 0));
        assertEquals(TokenGuard.Verdict.MISSING, guard.check("10.0.0.5", null, 0));
        assertEquals(TokenGuard.Verdict.MISSING, guard.check("10.0.0.5", "Basic s3cret", 0));
        assertEquals(TokenGuard.Verdict.MISSING, guard.check("10.0.0.5", "Bearer ", 0));
        assertEquals(TokenGuard.Verdict.WRONG, guard.check("10.0.0.5", "Bearer s3cre", 0));
        assertEquals(TokenGuard.Verdict.WRONG, guard.check("10.0.0.5", "Bearer s3cret2", 0));
    }

    @Test
    void aServerWithoutATokenAcceptsNobody() {
        TokenGuard guard = new TokenGuard("");
        assertEquals(TokenGuard.Verdict.MISSING, guard.check("10.0.0.5", "Bearer ", 0));
        assertEquals(TokenGuard.Verdict.WRONG, guard.check("10.0.0.5", "Bearer anything", 0));
    }

    @Test
    void fiveWrongTokensLockAnAddressOutForAMinute() {
        TokenGuard guard = new TokenGuard("s3cret");
        for (int attempt = 0; attempt < 5; attempt++) {
            assertEquals(TokenGuard.Verdict.WRONG, guard.check("10.0.0.5", "Bearer guess" + attempt, 1000L * attempt));
        }
        // Locked: even the right token is turned away, and so is a request with none
        assertEquals(TokenGuard.Verdict.LOCKED, guard.check("10.0.0.5", "Bearer s3cret", 5000));
        assertEquals(TokenGuard.Verdict.LOCKED, guard.check("10.0.0.5", null, 30_000));
        assertEquals(34, guard.lockedSeconds("10.0.0.5", 30_000));
        // Another address is not affected
        assertEquals(TokenGuard.Verdict.OK, guard.check("10.0.0.6", "Bearer s3cret", 5000));
        // A minute after the fifth wrong token it is over
        assertEquals(TokenGuard.Verdict.LOCKED, guard.check("10.0.0.5", "Bearer s3cret", 63_999));
        assertEquals(TokenGuard.Verdict.OK, guard.check("10.0.0.5", "Bearer s3cret", 64_000));
        assertEquals(0, guard.lockedSeconds("10.0.0.5", 64_000));
    }

    @Test
    void wrongTokensFarApartNeverLock() {
        TokenGuard guard = new TokenGuard("s3cret");
        for (int attempt = 0; attempt < 20; attempt++) {
            assertEquals(TokenGuard.Verdict.WRONG, guard.check("10.0.0.5", "Bearer nope", 20_000L * attempt));
        }
    }

    @Test
    void theRightTokenForgivesEarlierMistakes() {
        TokenGuard guard = new TokenGuard("s3cret");
        for (int round = 0; round < 3; round++) {
            for (int attempt = 0; attempt < 4; attempt++) {
                assertEquals(TokenGuard.Verdict.WRONG, guard.check("10.0.0.5", "Bearer nope", round * 100 + attempt));
            }
            assertEquals(TokenGuard.Verdict.OK, guard.check("10.0.0.5", "Bearer s3cret", round * 100 + 50));
        }
    }

    @Test
    void generatedTokensAreLongAndDiffer() {
        String one = TokenGuard.generate();
        assertTrue(one.length() >= 43);
        assertTrue(one.matches("[A-Za-z0-9_-]+"));
        assertNotEquals(one, TokenGuard.generate());
    }

    // --- Host and Origin ---

    @Test
    void leftAloneOnlyLocalhostIsAnswered() {
        HostRules rules = HostRules.local();
        for (String host : List.of("127.0.0.1:25595", "localhost:25595", "LOCALHOST", "[::1]:25595", "127.0.0.1")) {
            assertTrue(rules.hostAllowed(host), host);
        }
        assertTrue(rules.hostAllowed(null), "clients that send no Host are not browsers");
        for (String host : List.of("example.com", "192.168.1.10:25595", "evil.localhost.example.com", "127.0.0.1.example.com:25595")) {
            assertFalse(rules.hostAllowed(host), host);
        }
        assertTrue(rules.originAllowed(null));
        assertTrue(rules.originAllowed("http://127.0.0.1:25595"));
        assertTrue(rules.originAllowed("http://localhost:3000"));
        assertTrue(rules.originAllowed("http://[::1]:25595"));
        for (String origin : List.of("http://example.com", "https://192.168.1.10:25595", "null", "file://", "ftp://localhost")) {
            assertFalse(rules.originAllowed(origin), origin);
        }
    }

    @Test
    void aBoundAddressWidensTheRulesByExactlyThatAddress() {
        HostRules rules = HostRules.forBind("192.168.1.10", List.of("192.168.1.99"));
        assertTrue(rules.hostAllowed("192.168.1.10:25595"));
        assertTrue(rules.hostAllowed("127.0.0.1:25595"));
        assertFalse(rules.hostAllowed("192.168.1.99:25595"), "own names only count when every interface is bound");
        assertFalse(rules.hostAllowed("example.com"));
        assertTrue(rules.originAllowed("https://192.168.1.10:25595"));
        assertFalse(rules.originAllowed("https://192.168.1.11:25595"));
        assertFalse(rules.originAllowed("https://example.com"));
    }

    @Test
    void bindingEveryInterfaceAnswersToThisComputersOwnNames() {
        HostRules rules = HostRules.forBind("0.0.0.0", List.of("192.168.1.10", "gamingpc", "fe80:0:0:0:0:0:0:1%eth0"));
        assertTrue(rules.hostAllowed("192.168.1.10:25595"));
        assertTrue(rules.hostAllowed("GamingPC:25595"));
        assertTrue(rules.hostAllowed("[fe80::1]:25595"));
        assertFalse(rules.hostAllowed("0.0.0.0:25595"));
        assertFalse(rules.hostAllowed("attacker.example"));
        assertTrue(rules.originAllowed("https://gamingpc:25595"));
    }

    @Test
    void whichBindAddressesStayOnThisComputer() {
        assertTrue(HostRules.isLocalBind("127.0.0.1"));
        assertTrue(HostRules.isLocalBind(" localhost "));
        assertTrue(HostRules.isLocalBind("::1"));
        assertTrue(HostRules.isLocalBind("[::1]"));
        assertFalse(HostRules.isLocalBind("0.0.0.0"));
        assertFalse(HostRules.isLocalBind("192.168.1.10"));
        assertFalse(HostRules.isLocalBind("127.0.0.2"));
        assertTrue(HostRules.isWildcard("0.0.0.0"));
        assertTrue(HostRules.isWildcard("::"));
        assertFalse(HostRules.isWildcard("192.168.1.10"));
    }

    // --- The certificate ---

    @Test
    void aCertificateIsMadeKeptAndNamesItsAddresses() throws Exception {
        Path folder = Files.createTempDirectory("dailies-cert");
        Path file = folder.resolve("mcp").resolve("certificate.p12");
        Path password = folder.resolve("config").resolve("key.secret");
        InetAddress lan = address("192.168.1.10");
        List<InetAddress> addresses = List.of(address("127.0.0.1"), lan);

        ServerCertificate first = ServerCertificate.loadOrCreate(file, password, List.of("localhost"), addresses, lan);
        assertTrue(Files.isRegularFile(file));
        assertTrue(first.fingerprint().matches("([0-9A-F]{2}:){31}[0-9A-F]{2}"), first.fingerprint());
        assertTrue(first.names(lan));
        assertFalse(first.names(address("192.168.1.11")));
        first.certificate().checkValidity();
        assertEquals("CN=Dailies MCP", first.certificate().getSubjectX500Principal().getName());

        // Kept: the same fingerprint next time, so a client's pin goes on working
        ServerCertificate again = ServerCertificate.loadOrCreate(file, password, List.of("localhost"), addresses, lan);
        assertEquals(first.fingerprint(), again.fingerprint());

        // Bound to an address it does not name: a new one, with a new fingerprint
        InetAddress other = address("10.0.0.7");
        ServerCertificate moved = ServerCertificate.loadOrCreate(file, password, List.of("localhost"), List.of(address("127.0.0.1"), other), other);
        assertNotEquals(first.fingerprint(), moved.fingerprint());
        assertTrue(moved.names(other));

        // Every interface: whatever is kept will do
        assertEquals(moved.fingerprint(), ServerCertificate.loadOrCreate(file, password, List.of("localhost"), addresses, null).fingerprint());
    }

    @Test
    void theCertificateIsAServersOwnAndCanSignNoOther() throws Exception {
        X509Certificate made = ServerCertificate.create(List.of("localhost"), List.of(address("127.0.0.1")), Instant.now()).certificate();
        // Not a certificate authority: someone who trusts it trusts this server and nothing it might sign
        assertEquals(-1, made.getBasicConstraints());
        assertTrue(made.getCriticalExtensionOIDs().contains("2.5.29.19"), "basicConstraints is critical");
        assertTrue(made.getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.1"), "for a TLS server");
        boolean[] usage = made.getKeyUsage();
        assertTrue(usage[0], "digitalSignature");
        assertFalse(usage[5], "keyCertSign");
        assertFalse(usage[6], "cRLSign");
    }

    @Test
    void aCertificateLastsTenYears() throws Exception {
        Instant now = Instant.parse("2026-11-17T12:00:00Z");
        ServerCertificate made = ServerCertificate.create(List.of("localhost"), List.of(address("127.0.0.1")), now);
        assertEquals(Instant.parse("2026-11-16T12:00:00Z"), made.certificate().getNotBefore().toInstant());
        assertEquals(Instant.parse("2036-11-17T12:00:00Z"), made.certificate().getNotAfter().toInstant());
        // Past 2049 the date is written another way; it must still read back
        ServerCertificate late = ServerCertificate.create(List.of("localhost"), List.of(), Instant.parse("2045-01-01T00:00:00Z"));
        assertEquals(Instant.parse("2055-01-01T00:00:00Z"), late.certificate().getNotAfter().toInstant());
    }

    @Test
    void aClientThatPinsTheFingerprintCanTalkToIt() throws Exception {
        ServerCertificate made = ServerCertificate.create(List.of("localhost"), List.of(address("127.0.0.1")), Instant.now());
        String[] seen = new String[1];
        TrustManager pin = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("not a client");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                seen[0] = ServerCertificate.fingerprint(chain[0]);
                if (!made.fingerprint().equals(seen[0])) {
                    throw new CertificateException("not the pinned certificate");
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext client = SSLContext.getInstance("TLS");
        client.init(null, new TrustManager[]{pin}, null);

        try (SSLServerSocket server = (SSLServerSocket) made.context().getServerSocketFactory().createServerSocket(0, 1, address("127.0.0.1"))) {
            CompletableFuture<String> heard = CompletableFuture.supplyAsync(() -> {
                try (var socket = server.accept()) {
                    byte[] bytes = socket.getInputStream().readNBytes(5);
                    socket.getOutputStream().write("world".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    return new String(bytes, StandardCharsets.US_ASCII);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            try (SSLSocket socket = (SSLSocket) client.getSocketFactory().createSocket(address("127.0.0.1"), server.getLocalPort())) {
                socket.setSoTimeout(10_000);
                socket.getOutputStream().write("hello".getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                assertEquals("world", new String(socket.getInputStream().readNBytes(5), StandardCharsets.US_ASCII));
            }
            assertEquals("hello", heard.get(10, TimeUnit.SECONDS));
            assertEquals(made.fingerprint(), seen[0]);
        }
    }

    // --- One port, plain for this computer and HTTPS for the rest ---

    @Test
    void onlyATlsGreetingLooksLikeTls() {
        assertTrue(McpConnection.looksLikeTls(0x16));
        for (int first : new int[]{'G', 'P', 'O', 'H', 'D', ' ', '\r', 0x00, 0x15, 0x17, 0x80, 0xFF}) {
            assertFalse(McpConnection.looksLikeTls(first), "byte " + first);
        }
    }

    @Test
    void plainHttpIsForThisComputerAlone() throws Exception {
        assertTrue(McpConnection.plainAllowedFrom(address("127.0.0.1")));
        assertTrue(McpConnection.plainAllowedFrom(address("127.0.0.2")));
        assertTrue(McpConnection.plainAllowedFrom(address("::1")));
        // Being an allowed client is not enough: other machines get HTTPS or nothing
        assertFalse(McpConnection.plainAllowedFrom(address("192.168.1.20")));
        assertFalse(McpConnection.plainAllowedFrom(address("10.0.0.1")));
        assertFalse(McpConnection.plainAllowedFrom(address("fe80::1")));
        assertFalse(McpConnection.plainAllowedFrom(null));
    }

    /** A listener that answers each connection with what kind it was and the first line it sent */
    private static java.net.ServerSocket echoKind(ServerCertificate certificate, int connections) throws Exception {
        SSLContext context = certificate.context();
        java.net.ServerSocket listener = new java.net.ServerSocket(0, 4, address("127.0.0.1"));
        Thread thread = new Thread(() -> {
            for (int i = 0; i < connections; i++) {
                try (java.net.Socket raw = listener.accept()) {
                    raw.setSoTimeout(10_000);
                    try (McpConnection connection = McpConnection.open(raw, context)) {
                        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(connection.in(), StandardCharsets.US_ASCII));
                        String line = reader.readLine();
                        String reply = (connection.secure() ? "https" : "plain") + "|" + connection.plainAllowed() + "|" + line + "\n";
                        connection.out().write(reply.getBytes(StandardCharsets.US_ASCII));
                        connection.out().flush();
                    }
                } catch (Exception e) {
                    // The test's own assertions notice a connection that went wrong
                }
            }
        }, "sniff-test");
        thread.setDaemon(true);
        thread.start();
        return listener;
    }

    private static String firstLine(java.net.Socket socket, String request) throws Exception {
        socket.setSoTimeout(10_000);
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        return new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
    }

    @Test
    void theSamePortAnswersPlainHttpAndHttps() throws Exception {
        ServerCertificate made = ServerCertificate.create(List.of("localhost"), List.of(address("127.0.0.1")), Instant.now());
        TrustManager pin = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("not a client");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (!made.fingerprint().equals(ServerCertificate.fingerprint(chain[0]))) {
                    throw new CertificateException("not the pinned certificate");
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext client = SSLContext.getInstance("TLS");
        client.init(null, new TrustManager[]{pin}, null);

        try (java.net.ServerSocket listener = echoKind(made, 4)) {
            int port = listener.getLocalPort();
            // Plain, as a local agent sends it: nothing of the request is lost to the look at its first byte
            try (java.net.Socket socket = new java.net.Socket(address("127.0.0.1"), port)) {
                assertEquals("plain|true|POST /mcp HTTP/1.1", firstLine(socket, "POST /mcp HTTP/1.1\r\n\r\n"));
            }
            // HTTPS, as another machine sends it: the greeting's first byte is handed on and the handshake completes
            try (SSLSocket socket = (SSLSocket) client.getSocketFactory().createSocket(address("127.0.0.1"), port)) {
                assertEquals("https|false|GET /live HTTP/1.1", firstLine(socket, "GET /live HTTP/1.1\r\n\r\n"));
            }
            // And turn about, so neither leaves the listener in a state
            try (java.net.Socket socket = new java.net.Socket(address("127.0.0.1"), port)) {
                assertEquals("plain|true|GET / HTTP/1.1", firstLine(socket, "GET / HTTP/1.1\r\n\r\n"));
            }
            try (SSLSocket socket = (SSLSocket) client.getSocketFactory().createSocket(address("127.0.0.1"), port)) {
                assertEquals("https|false|POST /mcp HTTP/1.1", firstLine(socket, "POST /mcp HTTP/1.1\r\n\r\n"));
            }
        }
    }

    @Test
    void aConnectionThatSaysNothingIsAnError() throws Exception {
        ServerCertificate made = ServerCertificate.create(List.of("localhost"), List.of(address("127.0.0.1")), Instant.now());
        try (java.net.ServerSocket listener = new java.net.ServerSocket(0, 1, address("127.0.0.1"))) {
            new java.net.Socket(address("127.0.0.1"), listener.getLocalPort()).close();
            try (java.net.Socket raw = listener.accept()) {
                raw.setSoTimeout(5000);
                org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> McpConnection.open(raw, made.context()));
            }
        }
    }

    // --- The live feed ---

    @Test
    void aListenerHearsWhatIsPublishedAndOnlyWhileListening() throws Exception {
        LiveHub.publish("event", "{\"id\":\"e0\"}"); // nobody is listening: nothing happens
        LiveHub.Listener listener = LiveHub.listen();
        try {
            assertNull(listener.next(10, TimeUnit.MILLISECONDS));
            LiveHub.publish("event", "{\"id\":\"e1\"}");
            assertEquals("event: event\ndata: {\"id\":\"e1\"}\n\n", listener.next(1, TimeUnit.SECONDS));
            // A line break would end the message early, so there never is one
            LiveHub.publish("clips", "{\n}");
            assertEquals("event: clips\ndata: { }\n\n", listener.next(1, TimeUnit.SECONDS));
        } finally {
            LiveHub.leave(listener);
        }
        LiveHub.publish("event", "{\"id\":\"e2\"}");
        assertNull(listener.next(10, TimeUnit.MILLISECONDS));
    }

    @Test
    void aListenerThatStopsReadingIsLetGoAndThereIsALimit() {
        LiveHub.Listener slow = LiveHub.listen();
        for (int i = 0; i < 600 && !slow.dropped(); i++) {
            LiveHub.publish("event", "{}");
        }
        assertTrue(slow.dropped());
        assertEquals(0, LiveHub.listeners());

        List<LiveHub.Listener> many = new java.util.ArrayList<>();
        for (int i = 0; i < LiveHub.MAX_LISTENERS; i++) {
            many.add(LiveHub.listen());
        }
        assertNull(LiveHub.listen());
        many.forEach(LiveHub::leave);
        assertEquals(0, LiveHub.listeners());
    }

}
