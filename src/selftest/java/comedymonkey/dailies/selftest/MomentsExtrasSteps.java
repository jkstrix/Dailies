package comedymonkey.dailies.selftest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.mcp.McpHttpServer;
import comedymonkey.dailies.mcp.ServerCertificate;
import comedymonkey.dailies.moments.Moments;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static comedymonkey.dailies.selftest.SelfTest.args;
import static comedymonkey.dailies.selftest.SelfTest.check;
import static comedymonkey.dailies.selftest.SelfTestSupport.array;

/**
 * Plan 2.9 to 2.12 in a live world: voices left out (MOM-36, MOM-37), laughter and shouting (voice_burst), the
 * page's live feed and event pictures, and the MCP server reached the way another machine would reach it (MCP-8).
 * Runs before the recording steps; everything it changes in the settings is put back.
 */
final class MomentsExtrasSteps {

    private static final String PING = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";

    private MomentsExtrasSteps() {
    }

    static void run(SelfTest test) throws Exception {
        test.waitFor(Moments::isTracking, 30, "a moments session in the new world");
        // Let the world draw a few frames, so a picture is of something
        test.waitTicks(40);
        privacy(test);
        bursts(test);
        liveAndPictures(test);
        remote(test);
    }

    // --- MOM-36, MOM-37 ---

    private static void privacy(SelfTest test) throws Exception {
        UUID leftOut = UUID.randomUUID();
        UUID heard = UUID.randomUUID();

        // A run that failed half way may have left entries behind
        test.tool("moments_voice_excluded", args("action", "clear"));
        JsonObject added = test.tool("moments_voice_excluded", args("action", "add", "players", array(leftOut.toString(), "SomeoneShy")));
        check(added.getAsJsonArray("excluded").size() == 2 && added.toString().contains(leftOut.toString()) && added.toString().contains("SomeoneShy"),
            "adding to the excluded voices did not return the new list: " + added);
        JsonObject listed = test.tool("moments_voice_excluded", new JsonObject());
        check(listed.getAsJsonArray("excluded").size() == 2, "the excluded voices did not read back: " + listed);
        String setting = test.tool("config_get", args("key", "momentsVoiceExcluded")).get("value").getAsString();
        check(setting.contains(leftOut.toString()) && setting.contains("SomeoneShy"), "the excluded voices are not in the momentsVoiceExcluded setting: " + setting);
        String goesTo = listed.get("voice_goes_to").getAsString();
        check(goesTo.contains("AI is off") && goesTo.contains("nothing is sent anywhere"), "with AI off the tool does not say that nothing is sent anywhere: " + goesTo);
        JsonObject status = test.tool("moments_status", new JsonObject());
        check(status.has("privacy") && status.getAsJsonObject("privacy").getAsJsonArray("voice_excluded").size() == 2, "moments_status does not list the excluded voices");
        Reply page = http(test.port(), "GET", "/", null, null, null);
        check(page.body.contains("Whisper") && page.body.contains("nothing is sent anywhere"), "the page does not say where voice audio goes");

        // Both speak at once; only one of them may leave a trace
        speak(1000, 600.0, leftOut, heard);
        Thread.sleep(1300);
        String dialogue = test.tool("moments_dialogue_query", args("session", "current", "limit", 500)).toString();
        check(dialogue.contains(heard.toString()), "a speaker who is not left out left no voice segment");
        check(!dialogue.contains(leftOut.toString()), "an excluded speaker's voice reached Moments");
        String folder = sessionFolder(test);
        Moments.storage().flush();
        try (var files = Files.list(Path.of(folder, "audio"))) {
            check(files.noneMatch(file -> file.getFileName().toString().startsWith(leftOut.toString())), "an excluded speaker's voice was written to the audio folder");
        }

        // By name as well as by uuid, whatever the case
        check(comedymonkey.dailies.moments.MomentsConfig.isVoiceExcluded(null, "someoneSHY"), "an excluded player is not matched by name");

        JsonObject removed = test.tool("moments_voice_excluded", args("action", "remove", "players", array(leftOut.toString(), "someoneshy")));
        check(removed.getAsJsonArray("excluded").isEmpty(), "removing from the excluded voices did not empty the list: " + removed);
        speak(1000, 600.0, leftOut);
        Thread.sleep(1300);
        dialogue = test.tool("moments_dialogue_query", args("session", "current", "limit", 500)).toString();
        check(dialogue.contains(leftOut.toString()), "a speaker taken off the excluded list is still left out");
        test.log("privacy: an excluded speaker left no segment and no audio file; taken off the list, they were logged again");
    }

    // --- voice_burst ---

    private static void bursts(SelfTest test) throws Exception {
        JsonObject labels = test.tool("config_get", args("key", "momentsBurstLabels"));
        check(labels.get("value").getAsBoolean(), "momentsBurstLabels is not on by default: " + labels);
        // The test tones are no one's laughter; with the classifier off the burst keeps the loudness-only subject and weight checked below.
        // The classifier also needs momentsAiEnabled, which is off here, but switching the labels off as well keeps this check true either way
        test.tool("config_set", args("key", "momentsBurstLabels", "value", false));
        try {
            loudBursts(test);
        } finally {
            test.tool("config_set", args("key", "momentsBurstLabels", "value", true));
        }
    }

    private static void loudBursts(SelfTest test) throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        // Ordinary talk first, so each voice has a baseline, then both about 23 dB louder for two seconds
        speak(2800, 600.0, first, second);
        check(!test.tool("moments_events_query", args("session", "current", "type", "voice_burst")).toString().contains(first.toString()),
            "ordinary talk was logged as a voice burst");
        speak(2200, 9000.0, first, second);

        JsonObject[] found = new JsonObject[1];
        test.waitFor(() -> {
            try {
                for (JsonElement element : test.tool("moments_events_query", args("session", "current", "type", "voice_burst")).getAsJsonArray("events")) {
                    JsonObject event = element.getAsJsonObject();
                    JsonArray speakers = event.getAsJsonObject("metadata").getAsJsonArray("speakers");
                    if (speakers.size() == 2 && event.toString().contains(first.toString()) && event.toString().contains(second.toString())) {
                        found[0] = event;
                        return true;
                    }
                }
                return false;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, 5, "a voice_burst event with both speakers");
        JsonObject burst = found[0];
        check(burst.get("importance").getAsDouble() == 40.0, "a burst of two speakers does not weigh 30 + 10: " + burst);
        check(burst.get("subject").getAsString().equals("unclassified"), "an unclassified burst has the subject " + burst.get("subject"));
        check(burst.getAsJsonObject("metadata").get("peak_db").getAsDouble() >= 12.0, "the burst's peak is under the threshold: " + burst);
        int count = test.tool("moments_events_query", args("session", "current", "type", "voice_burst")).getAsJsonArray("events").size();
        check(count == 1, "one burst of two speakers was logged as " + count + " events");
        test.log("voice burst: two speakers, weight " + burst.get("importance").getAsDouble() + ", peak " + burst.getAsJsonObject("metadata").get("peak_db") + " dB over baseline");
        Thread.sleep(1000);
    }

    /** Feeds sound from the given speakers at once, in real time, exactly as the voice chat capture hands it over */
    private static void speak(int millis, double amplitude, UUID... speakers) throws Exception {
        short[] frame = new short[960];
        long start = System.nanoTime();
        for (int sent = 0; sent * 20 < millis; sent++) {
            for (int i = 0; i < frame.length; i++) {
                frame[i] = (short) (Math.sin((sent * 960 + i) * 2.0 * Math.PI * 220.0 / 48000.0) * amplitude);
            }
            for (UUID speaker : speakers) {
                Moments.onVoiceChatAudio(speaker, frame.clone());
            }
            // Paced against the clock, not by counting sleeps, which run long on a busy machine
            long due = start + (sent + 1) * 20_000_000L;
            long wait = (due - System.nanoTime()) / 1_000_000L;
            if (wait > 0) {
                Thread.sleep(wait);
            }
        }
    }

    // --- GET /live and event pictures ---

    private static void liveAndPictures(SelfTest test) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpResponse<InputStream> stream = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + test.port() + "/live")).GET().build(),
            HttpResponse.BodyHandlers.ofInputStream());
        check(stream.statusCode() == 200, "GET /live answered " + stream.statusCode());
        check(stream.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"), "GET /live is not an event stream");
        LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(stream.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    lines.add(line);
                }
            } catch (Exception ignored) {
                // Closed below
            }
        }, "Dailies-Self-Test-Live");
        reader.setDaemon(true);
        reader.start();
        try {
            check(awaitLine(lines, ": connected", 5000) != null, "the live feed did not greet its listener");

            long before = System.nanoTime();
            JsonObject added = test.tool("moments_event_add", args("label", "Live test", "type", "live_test"));
            String eventId = added.getAsJsonObject("event").get("id").getAsString();
            String heard = awaitLine(lines, "\"id\":\"" + eventId + "\"", 3000);
            long millis = (System.nanoTime() - before) / 1_000_000L;
            check(heard != null, "the live feed did not announce event " + eventId);
            check(millis < 1000, "the live feed took " + millis + " ms to announce a new event");
            check(heard.startsWith("data: ") && heard.contains("\"change\":\"added\""), "the live feed's message is not as the page expects: " + heard);
            test.log("live feed: event " + eventId + " announced " + millis + " ms after it was asked for");

            // The same event scores 50, so a picture of the screen follows
            String folder = sessionFolder(test);
            Path picture = Path.of(folder, "pictures", eventId + ".jpg");
            JsonObject[] event = new JsonObject[1];
            test.waitFor(() -> {
                try {
                    for (JsonElement element : test.tool("moments_events_query", args("session", "current", "subject", "live_test")).getAsJsonArray("events")) {
                        if (element.getAsJsonObject().get("id").getAsString().equals(eventId) && element.getAsJsonObject().has("picture")) {
                            event[0] = element.getAsJsonObject();
                            return true;
                        }
                    }
                    return false;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }, 10, "the picture of event " + eventId);
            check(event[0].get("importance").getAsDouble() >= 50.0, "the pictured event scores under 50: " + event[0]);
            check(event[0].get("picture").getAsString().equals("pictures/" + eventId + ".jpg"), "the event names its picture " + event[0].get("picture"));
            Moments.storage().flush();
            check(Files.isRegularFile(picture), "there is no picture file at " + picture);
            byte[] bytes = Files.readAllBytes(picture);
            int[] size = jpegSize(bytes);
            check(size != null, "the picture is not a JPEG");
            check(size[0] >= 300 && size[0] <= 340, "the picture is " + size[0] + " wide, not about 320");
            check(size[1] >= 100 && size[1] <= 340, "the picture is " + size[1] + " high");
            check(bytes.length > 1500 && bytes.length < 80_000, "the picture is " + bytes.length + " bytes; a few kilobytes was the idea");
            check(awaitLine(lines, "\"change\":\"changed\"", 3000) != null, "the live feed did not say the event had gained its picture");

            RawReply served = httpBytes(test.port(), "/pictures/" + folderName(folder) + "/" + eventId + ".jpg");
            check(served.status == 200 && served.body.length == bytes.length, "the page cannot fetch the picture: HTTP " + served.status + ", " + served.body.length + " bytes");
            check(httpBytes(test.port(), "/pictures/" + folderName(folder) + "/..%2Fsession.json").status == 404, "the picture address reaches files that are not pictures");
            check(httpBytes(test.port(), "/pictures/../" + eventId + ".jpg").status == 404, "the picture address reaches outside the session");
            test.log("event picture: " + size[0] + "x" + size[1] + ", " + bytes.length + " bytes, beside the session and served to the page");

            // A small event gets none
            JsonObject small = test.tool("moments_events_query", args("session", "current", "type", "biome_discovered"));
            check(!small.toString().contains("\"picture\""), "an event scoring under 50 was given a picture");

            // And with the setting off, neither does a big one
            test.tool("config_set", args("key", "momentsEventPictures", "value", false));
            try {
                String quiet = test.tool("moments_event_add", args("label", "No picture", "type", "live_test")).getAsJsonObject("event").get("id").getAsString();
                test.waitTicks(30);
                Moments.storage().flush();
                check(!Files.exists(Path.of(folder, "pictures", quiet + ".jpg")), "a picture was taken with momentsEventPictures off");
            } finally {
                test.tool("config_set", args("key", "momentsEventPictures", "value", true));
            }
        } finally {
            stream.body().close();
        }
    }

    private static String awaitLine(LinkedBlockingQueue<String> lines, String holding, long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (true) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                return null;
            }
            String line = lines.poll(left, TimeUnit.MILLISECONDS);
            if (line != null && line.contains(holding)) {
                return line;
            }
        }
    }

    /** Width and height from a JPEG's frame header, or null when it is not one */
    private static int[] jpegSize(byte[] bytes) {
        if (bytes.length < 4 || (bytes[0] & 0xFF) != 0xFF || (bytes[1] & 0xFF) != 0xD8) {
            return null;
        }
        int at = 2;
        while (at + 9 < bytes.length) {
            if ((bytes[at] & 0xFF) != 0xFF) {
                return null;
            }
            int marker = bytes[at + 1] & 0xFF;
            int length = ((bytes[at + 2] & 0xFF) << 8) | (bytes[at + 3] & 0xFF);
            if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                return new int[]{((bytes[at + 7] & 0xFF) << 8) | (bytes[at + 8] & 0xFF), ((bytes[at + 5] & 0xFF) << 8) | (bytes[at + 6] & 0xFF)};
            }
            at += 2 + length;
        }
        return null;
    }

    private static String sessionFolder(SelfTest test) throws Exception {
        return test.tool("moments_status", args("session", "current")).getAsJsonObject("session").get("folder").getAsString();
    }

    private static String folderName(String folder) {
        return Path.of(folder).getFileName().toString();
    }

    // --- MCP-8 ---

    private static void remote(SelfTest test) throws Exception {
        int port = test.port();
        // Left alone, the server is as it always was: plain HTTP on this computer, deaf to any other name
        check(!McpHttpServer.isRemote() && McpHttpServer.certificateFingerprint() == null, "the MCP server is in remote mode without being asked");
        check(Host.get().setting("mcpBind", "").equals("127.0.0.1"), "mcpBind is not 127.0.0.1 by default");
        check(http(port, "POST", "/mcp", PING, "192.168.1.10:" + port, null).status == 403, "with mcpBind left alone, a request for a network address was not refused");
        check(http(port, "POST", "/mcp", PING, null, null).status == 200, "with mcpBind left alone, a plain local request was not answered");
        check(http(port, "POST", "/mcp", PING, null, "Bearer anything").status == 200, "with mcpBind left alone, a token is not needed and must not matter");

        String oldToken = Host.get().setting("mcpToken", "");
        String oldBind = Host.get().setting("mcpBind", "127.0.0.1");
        // 127.0.0.2 is "another address" to the server, so everything remote access does is switched on, while
        // nothing is in fact opened to the network and no firewall has anything to ask
        String bind = "127.0.0.2";
        try {
            Host.get().saveSetting("mcpToken", "");
            // The server's own settings are the settings screen's alone: a tool may not open the server to the network
            test.toolError("config_set", args("key", "mcpBind", "value", bind));
            check(Host.get().setting("mcpBind", "").equals(oldBind), "config_set changed mcpBind, which only the settings screen may");
            Host.get().saveSetting("mcpBind", bind);
            McpHttpServer.applyConfig();
            test.waitFor(() -> McpHttpServer.isRemote() && McpHttpServer.getListeningPort() == port, 20, "the MCP server to restart for remote access");

            String token = Host.get().setting("mcpToken", "");
            check(token.length() >= 32, "switching remote access on did not make a token");
            String fingerprint = McpHttpServer.certificateFingerprint();
            check(fingerprint != null && fingerprint.matches("([0-9A-F]{2}:){31}[0-9A-F]{2}"), "the server has no certificate fingerprint: " + fingerprint);
            check(fingerprint.equals(Host.get().setting("mcpCertificateFingerprint", "")), "the fingerprint is not shown in the settings");
            check(Files.isRegularFile(Host.get().dataDirectory().resolve("mcp").resolve("certificate.p12")), "the certificate was not kept");

            // The client trusts this one certificate, by its fingerprint, and nothing else
            SSLContext pinned = pinned(fingerprint);
            HttpClient client = HttpClient.newBuilder().sslContext(pinned).connectTimeout(Duration.ofSeconds(5)).build();
            URI mcp = URI.create("https://" + bind + ":" + port + "/mcp");

            HttpResponse<String> without = client.send(post(mcp, null), HttpResponse.BodyHandlers.ofString());
            check(without.statusCode() == 401, "a request without the token was answered " + without.statusCode());
            check(without.headers().firstValue("WWW-Authenticate").orElse("").startsWith("Bearer"), "the refusal does not say a Bearer token is wanted");
            HttpResponse<String> with = client.send(post(mcp, token), HttpResponse.BodyHandlers.ofString());
            check(with.statusCode() == 200 && with.body().contains("\"result\""), "a request with the token over HTTPS was answered " + with.statusCode() + " " + with.body());

            // The page itself loads without the token (it is what asks for it); nothing else does
            HttpResponse<String> page = client.send(HttpRequest.newBuilder(URI.create("https://" + bind + ":" + port + "/")).GET().build(), HttpResponse.BodyHandlers.ofString());
            check(page.statusCode() == 200 && page.body().toLowerCase().contains("<html"), "the page did not load over HTTPS: " + page.statusCode());
            HttpResponse<String> live = client.send(HttpRequest.newBuilder(URI.create("https://" + bind + ":" + port + "/live")).GET().build(), HttpResponse.BodyHandlers.ofString());
            check(live.statusCode() == 401, "the live feed was open without the token: " + live.statusCode());

            // Host and Origin still count, widened to the bound address and no further
            check(https(pinned, bind, port, bind + ":" + port, null, token).status == 200, "the bound address was not accepted as Host");
            check(https(pinned, bind, port, "example.com", null, token).status == 403, "a foreign Host was accepted in remote mode");
            check(https(pinned, bind, port, bind + ":" + port, "https://example.com", token).status == 403, "a foreign Origin was accepted in remote mode");
            check(https(pinned, bind, port, bind + ":" + port, "https://" + bind + ":" + port, token).status == 200, "the page's own Origin was refused in remote mode");

            // This computer's own agents carry on as they were: plain HTTP at 127.0.0.1, no token, localhost names only
            check(http(port, "POST", "/mcp", PING, null, null).status == 200, "with remote access on, a plain local request without a token was not answered");
            check(test.tool("dailies_status", new JsonObject()).has("version"), "with remote access on, the local tools stopped answering");
            check(http(port, "POST", "/mcp", PING, bind + ":" + port, null).status == 403, "a plain request was let in under the bound address's name");
            check(http(port, "POST", "/mcp", PING, null, null, "http://example.com").status == 403, "a plain request from a foreign origin was answered with remote access on");
            // The same plain request at the bound address is still this computer talking, so it is answered too
            check(httpAt(bind, port, "POST", "/mcp", PING, "127.0.0.1:" + port).status == 200, "a plain request from this computer to the bound address was not answered");

            // Five wrong tokens, and the address is shut out: even the right token is turned away
            for (int attempt = 1; attempt <= 5; attempt++) {
                int status = client.send(post(mcp, "wrong-token-" + attempt), HttpResponse.BodyHandlers.ofString()).statusCode();
                check(status == 401, "wrong token " + attempt + " was answered " + status);
            }
            HttpResponse<String> locked = client.send(post(mcp, token), HttpResponse.BodyHandlers.ofString());
            check(locked.statusCode() == 429, "after five wrong tokens the right one was answered " + locked.statusCode() + ", not 429");
            int retry = Integer.parseInt(locked.headers().firstValue("Retry-After").orElse("0"));
            check(retry >= 50 && retry <= 60, "the lockout is said to last " + retry + " seconds, not a minute");
            // The lockout is HTTPS's business; plain local requests never had a token to get wrong
            check(http(port, "POST", "/mcp", PING, null, null).status == 200, "the lockout shut out plain local requests as well");
            test.log("remote access: HTTPS with certificate " + fingerprint.substring(0, 23) + "..., 401 without the token, 200 with it, locked out after five wrong ones; plain local HTTP answered throughout");
        } finally {
            // Back to how it was; the restart also forgets the lockout this test earned itself
            Host.get().saveSetting("mcpBind", oldBind);
            Host.get().saveSetting("mcpToken", oldToken);
            McpHttpServer.applyConfig();
        }
        test.waitFor(() -> !McpHttpServer.isRemote() && McpHttpServer.getListeningPort() == port, 20, "the MCP server to go back to localhost");
        check(test.tool("dailies_status", new JsonObject()).has("version"), "the MCP server did not come back on localhost");
        check(http(port, "POST", "/mcp", PING, "192.168.1.10:" + port, null).status == 403, "back on localhost, a request for a network address was not refused");
    }

    private static HttpRequest post(URI uri, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(PING));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder.build();
    }

    private static SSLContext pinned(String fingerprint) throws Exception {
        TrustManager pin = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("not a client");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (chain.length == 0 || !fingerprint.equals(ServerCertificate.fingerprint(chain[0]))) {
                    throw new CertificateException("not the pinned certificate");
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{pin}, null);
        return context;
    }

    // --- Plain sockets, for the headers Java's HTTP client will not send ---

    private record Reply(int status, String body) {
    }

    private record RawReply(int status, byte[] body) {
    }

    private static Reply http(int port, String method, String path, String body, String host, String authorization) throws Exception {
        return http(port, method, path, body, host, authorization, null);
    }

    private static Reply http(int port, String method, String path, String body, String host, String authorization, String origin) throws Exception {
        try (Socket socket = new Socket(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), port)) {
            RawReply raw = exchange(socket, method, path, body, host == null ? "127.0.0.1:" + port : host, origin, authorization);
            return new Reply(raw.status, new String(raw.body, StandardCharsets.UTF_8));
        }
    }

    private static Reply httpAt(String address, int port, String method, String path, String body, String host) throws Exception {
        try (Socket socket = new Socket(InetAddress.getByName(address), port)) {
            RawReply raw = exchange(socket, method, path, body, host, null, null);
            return new Reply(raw.status, new String(raw.body, StandardCharsets.UTF_8));
        }
    }

    private static RawReply httpBytes(int port, String path) throws Exception {
        try (Socket socket = new Socket(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), port)) {
            return exchange(socket, "GET", path, null, "127.0.0.1:" + port, null, null);
        }
    }

    private static Reply https(SSLContext context, String address, int port, String host, String origin, String token) throws Exception {
        try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(InetAddress.getByName(address), port)) {
            RawReply raw = exchange(socket, "POST", "/mcp", PING, host, origin, "Bearer " + token);
            return new Reply(raw.status, new String(raw.body, StandardCharsets.UTF_8));
        }
    }

    private static RawReply exchange(Socket socket, String method, String path, String body, String host, String origin, String authorization) throws Exception {
        socket.setSoTimeout(30_000);
        byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder();
        head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        head.append("Host: ").append(host).append("\r\n");
        if (origin != null) {
            head.append("Origin: ").append(origin).append("\r\n");
        }
        if (authorization != null) {
            head.append("Authorization: ").append(authorization).append("\r\n");
        }
        if (body != null) {
            head.append("Content-Type: application/json\r\n");
            head.append("Content-Length: ").append(payload.length).append("\r\n");
        }
        head.append("Connection: close\r\n\r\n");
        OutputStream out = socket.getOutputStream();
        out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(payload);
        out.flush();

        ByteArrayOutputStream received = new ByteArrayOutputStream();
        InputStream in = socket.getInputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            received.write(buffer, 0, read);
        }
        byte[] all = received.toByteArray();
        String text = new String(all, StandardCharsets.ISO_8859_1);
        int headEnd = text.indexOf("\r\n\r\n");
        String[] parts = text.substring(0, Math.max(0, text.indexOf("\r\n"))).split(" ");
        check(parts.length >= 2, "the server sent no status line for " + method + " " + path);
        byte[] rest = headEnd < 0 ? new byte[0] : java.util.Arrays.copyOfRange(all, headEnd + 4, all.length);
        return new RawReply(Integer.parseInt(parts[1]), rest);
    }

}
