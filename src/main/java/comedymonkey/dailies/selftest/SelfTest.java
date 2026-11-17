package comedymonkey.dailies.selftest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.mcp.McpHttpServer;
import net.minecraft.client.Minecraft;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The scripted run of specification chapter XIV. Switched on by the system property dailies.test.script, it waits
 * for the game to finish loading, works through its steps, writes PASSED or FAILED: reason to the log and to
 * dailies/test-result.txt, and closes the game. Steps are added here as each part of the mod is built.
 */
public final class SelfTest {

    public static final String PROPERTY = "dailies.test.script";

    private static final int LOAD_TIMEOUT_SECONDS = 300;

    private final List<String> skipped = new ArrayList<>();
    private int port;

    private SelfTest() {
    }

    public static boolean asked() {
        return System.getProperty(PROPERTY) != null;
    }

    public static void startIfAsked() {
        if (!asked()) {
            return;
        }
        Thread thread = new Thread(() -> new SelfTest().run(), "Dailies-Self-Test");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        String result;
        try {
            this.waitForGame();
            if ("manual".equals(System.getProperty(PROPERTY))) {
                ManualShots.run(this);
                Host.LOGGER.info("DAILIES MANUAL PICTURES DONE");
                Minecraft.getInstance().execute(Minecraft.getInstance()::stop);
                return;
            }
            if ("kit".equals(System.getProperty(PROPERTY))) {
                KitShots.run(this);
                Host.LOGGER.info("DAILIES KIT PICTURES DONE");
                Minecraft.getInstance().execute(Minecraft.getInstance()::stop);
                return;
            }
            if ("hands-on".equals(System.getProperty(PROPERTY))) {
                try {
                    this.handsOn();
                } catch (Throwable t) {
                    // A hands-on run never closes the game on the person at the controls
                    Host.LOGGER.warn("Dailies hands-on: the script stopped early; the game is yours", t);
                }
                return;
            }
            if (Boolean.getBoolean("dailies.test.realWorldOnly")) {
                // Only the tour of generated terrain, for working on it: gradlew runTestClient -PdailiesFlags=dailies.test.realWorldOnly
                RealWorldSteps.run(this);
                this.finish("PASSED");
                return;
            }
            // The tour first and everything else after it, with the tour's verdict at the end: one long run that tells both
            // what the tour found and whether the rest still passes. -PdailiesFlags=dailies.test.realWorldFirst
            boolean tourFirst = Boolean.getBoolean("dailies.test.realWorldFirst");
            if (tourFirst) {
                RealWorldSteps.run(this, true);
            }
            this.checkMcp();
            if (Boolean.getBoolean("dailies.test.bestiaryOnly")) {
                // For working on the bestiary by itself: gradlew runTestClient -PdailiesFlags=dailies.test.bestiaryOnly
                BestiarySteps.run(this);
                throw new Failure("only the bestiary was run, and it passed");
            }
            this.step("SettingsSteps.run", () -> SettingsSteps.run(this, this.port));
            this.step("WorldSteps.createWorld", () -> WorldSteps.createWorld(this));
            // First, while nothing else has been recorded: it makes a recording of its own and puts it aside for later
            this.step("WatchingSteps.record", () -> WatchingSteps.record(this));
            this.step("MomentsExtrasSteps.run", () -> MomentsExtrasSteps.run(this));
            this.step("PointOfViewSteps.beforeCraft", () -> PointOfViewSteps.beforeCraft(this));
            this.step("RecordingSteps.run", () -> RecordingSteps.run(this));
            this.step("PointOfViewSteps.craftMoments", () -> PointOfViewSteps.craftMoments(this));
            // A switch for running everything else while a fault in the pack step is being looked into
            if (!Boolean.getBoolean("dailies.test.skipPacks")) {
                this.step("ResourcePackSteps.record", () -> ResourcePackSteps.record(this));
            } else {
                this.skip("the resource pack steps were switched off for this run (dailies.test.skipPacks)");
            }
            this.step("PlaybackSteps.run", () -> PlaybackSteps.run(this));
            this.step("BalloonSteps.run", () -> BalloonSteps.run(this));
            this.step("ExportSteps.run", () -> ExportSteps.run(this));
            this.step("SettingsSteps.editorDropdown", () -> SettingsSteps.editorDropdown(this));
            this.step("EditorDesignSteps.run", () -> EditorDesignSteps.run(this));
            this.step("ExportPresetSteps.run", () -> ExportPresetSteps.run(this));
            // With the recording that has the machines in it still open: Flywheel's contraption, Iris's clock, Distant Horizons
            this.step("RendererSteps.run", () -> RendererSteps.run(this));
            this.step("QueueSteps.run", () -> QueueSteps.run(this));
            this.step("PlaybackSteps.close", () -> PlaybackSteps.close(this));
            // While the recording with the machines in it is still there: the library steps take it apart
            this.step("WatchingSteps.machines", () -> WatchingSteps.machines(this));
            this.step("OwnerFeatureSteps.run", () -> OwnerFeatureSteps.run(this));
            this.step("TimelineSteps.original", () -> TimelineSteps.original(this));
            this.step("LibrarySteps.run", () -> LibrarySteps.run(this));
            if (!Boolean.getBoolean("dailies.test.skipPacks")) {
                this.step("ResourcePackSteps.play", () -> ResourcePackSteps.play(this));
            }
            this.step("WatchingSteps.watch", () -> WatchingSteps.watch(this));
            // The library's screens, the bin and storage, with small recordings of their own (REC-5, REC-8, REC-9)
            this.step("LibraryScreenSteps.run", () -> LibraryScreenSteps.run(this));
            this.step("PointOfViewSteps.run", () -> PointOfViewSteps.run(this));
            if (Boolean.getBoolean("dailies.test.bestiary")) {
                // About ten minutes by itself: gradlew runTestClient -PdailiesFlags=dailies.test.bestiary
                this.step("BestiarySteps.run", () -> BestiarySteps.run(this));
            } else {
                this.skip("the bestiary was not asked for (dailies.test.bestiary)");
            }
            // In a world and with a recording of its own: everything above is in a flat world, this is in generated terrain. It
            // takes about seventeen minutes, so it runs only when asked for: -PdailiesFlags=dailies.test.realWorld
            if (tourFirst) {
                RealWorldSteps.verdict();
            } else if (Boolean.getBoolean("dailies.test.realWorld")) {
                this.step("RealWorldSteps.run", () -> RealWorldSteps.run(this));
            } else {
                this.skip("the tour of generated terrain was not asked for (dailies.test.realWorld)");
            }
            // Last, so the tour's measures are there to be reported with its own. About three minutes, in a world of its own
            if (!Boolean.getBoolean("dailies.test.skipBudgets")) {
                this.step("BudgetSteps.run", () -> BudgetSteps.run(this));
            } else {
                this.skip("the budgets were switched off for this run (dailies.test.skipBudgets)");
            }
            result = this.failures.isEmpty() ? "PASSED" : "FAILED: " + this.failures.size() + " step(s): " + String.join(" || ", this.failures);
        } catch (Throwable t) {
            Host.LOGGER.error("Dailies self test: a step failed", t);
            result = "FAILED: " + (t instanceof Failure ? t.getMessage() : t.toString());
        }
        this.finish(result);
    }

    /** Steps that failed in this run, each with its reason; the run carries on past them so that one run finds them all */
    private final List<String> failures = new ArrayList<>();

    private interface Step {
        void run() throws Exception;
    }

    /** Runs one group of steps; a failure is written down and the run goes on with the next group */
    private void step(String name, Step body) {
        try {
            body.run();
        } catch (Throwable t) {
            Host.LOGGER.error("Dailies self test: {} failed; carrying on with the next steps", name, t);
            this.failures.add(name + ": " + (t instanceof Failure ? t.getMessage() : t.toString()));
            Host.LOGGER.info("DAILIES TEST STEP FAILED: {}: {}", name, t instanceof Failure ? t.getMessage() : t.toString());
            try {
                this.onClientRun(() -> {
                    Minecraft minecraft = Minecraft.getInstance();
                    if (minecraft.screen != null && minecraft.level != null) {
                        minecraft.setScreen(null);
                    }
                });
            } catch (Throwable ignored) {
                // The next step finds the game as it is
            }
        }
    }

    private void finish(String result) {
        for (String line : this.skipped) {
            Host.LOGGER.info("DAILIES TEST skipped: {}", line);
        }
        Host.LOGGER.info("DAILIES TEST {}", result);
        try {
            Files.writeString(Host.get().dataDirectory().resolve("test-result.txt"), result + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            Host.LOGGER.error("Dailies self test: unable to write the result file", t);
        }
        boolean passed = result.equals("PASSED");
        Minecraft minecraft = Minecraft.getInstance();
        if (passed && minecraft != null) {
            minecraft.execute(minecraft::stop);
        } else {
            System.exit(1);
        }
    }

    /** TEST-3: place the test world, give the person sixty seconds at the controls while recording, and leave the recording open */
    private void handsOn() throws Exception {
        WorldSteps.createWorld(this);
        this.tool("recording_start", new JsonObject());
        this.log("recording: you have sixty seconds at the controls");
        for (int left = 60; left > 0; left -= 10) {
            int seconds = left;
            Minecraft.getInstance().execute(() -> comedymonkey.dailies.ui.Notices.show(net.minecraft.network.chat.Component.literal(seconds + " seconds of recording left")));
            Thread.sleep(10_000);
            if (comedymonkey.dailies.capture.Recorder.get().recordingId() == null) {
                // The person finished the recording, or left the world, themselves; the game is theirs from here
                this.log("the recording was ended by hand; the game is yours");
                return;
            }
        }
        JsonObject saved = this.tool("recording_finish", args("name", "Hands on"));
        this.tool("replay_open", args("path", saved.get("path").getAsString()));
        this.log("the recording is open in playback; the game is yours");
    }

    /** The game has finished its loading screen and the MCP server is answering */
    private void waitForGame() throws Exception {
        long deadline = System.currentTimeMillis() + LOAD_TIMEOUT_SECONDS * 1000L;
        boolean focusSet = false;
        while (System.currentTimeMillis() < deadline) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null && minecraft.options != null && !focusSet) {
                // The owner watches runs and types elsewhere, so losing focus must not pause the game
                minecraft.options.pauseOnLostFocus = false;
                focusSet = true;
            }
            this.port = McpHttpServer.getListeningPort();
            if (focusSet && this.port > 0 && minecraft.getOverlay() == null && minecraft.isGameLoadFinished()) {
                return;
            }
            Thread.sleep(250);
        }
        throw new Failure("the game did not finish loading with the MCP server running within " + LOAD_TIMEOUT_SECONDS + " seconds");
    }

    // --- Step: MCP (MCP-1, MCP-3, MCP-5) ---

    private void checkMcp() throws Exception {
        JsonObject listed = this.rpc("tools/list", new JsonObject()).getAsJsonObject("result");
        Set<String> names = new HashSet<>();
        for (JsonElement tool : listed.getAsJsonArray("tools")) {
            names.add(tool.getAsJsonObject().get("name").getAsString());
        }
        for (String expected : List.of("dailies_status", "config_get", "config_set", "moments_status", "moments_cue", "moments_ignored_events")) {
            check(names.contains(expected), "tools/list does not include " + expected);
        }
        Host.LOGGER.info("Dailies self test: {} tools listed", names.size());

        check(this.http("GET", "/mcp", null, null, null).status == 405, "GET /mcp did not answer 405");
        String ping = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
        check(this.http("POST", "/mcp", ping, "http://example.com", null).status == 403, "a request from a foreign origin was not refused");
        check(this.http("POST", "/mcp", ping, null, "example.com").status == 403, "a request for a foreign host was not refused");

        Reply page = this.http("GET", "/", null, null, null);
        check(page.status == 200 && page.body.toLowerCase().contains("<html"), "GET / did not return the moments page");

        String batch = "[" + call(1, "dailies_status", new JsonObject()) + "," + call(2, "moments_status", new JsonObject()) + "]";
        JsonElement batchReply = JsonParser.parseString(this.http("POST", "/mcp", batch, null, null).body);
        check(batchReply.isJsonArray() && batchReply.getAsJsonArray().size() == 2, "a batch of two calls did not return two results");

        JsonObject status = this.tool("dailies_status", new JsonObject());
        check(status.has("version") && status.has("recording") && status.has("playback") && status.has("export"), "dailies_status is missing a part");

        JsonObject unknownTool = JsonParser.parseString(this.http("POST", "/mcp", call(3, "no_such_tool", new JsonObject()), null, null).body).getAsJsonObject();
        check(unknownTool.has("error") || isToolError(unknownTool), "an unknown tool was not reported as an error");

        JsonObject wrongArgument = new JsonObject();
        wrongArgument.addProperty("colour", "red");
        String message = this.toolError("dailies_status", wrongArgument);
        check(message.contains("colour"), "an unknown argument was not named in the error: " + message);

        JsonObject outOfRange = new JsonObject();
        outOfRange.addProperty("key", "recorderUpdatesPerSecond");
        outOfRange.addProperty("value", 99);
        message = this.toolError("config_set", outOfRange);
        check(message.contains("20"), "an out-of-range number was not answered with the nearest allowed value: " + message);

        JsonObject one = new JsonObject();
        one.addProperty("key", "momentsAiEnabled");
        check(!this.tool("config_get", one).get("value").getAsBoolean(), "momentsAiEnabled is not off by default");
    }

    // --- Talking to the MCP server ---

    private static String call(int id, String tool, JsonObject arguments) {
        JsonObject params = new JsonObject();
        params.addProperty("name", tool);
        params.add("arguments", arguments);
        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", id);
        request.addProperty("method", "tools/call");
        request.add("params", params);
        return request.toString();
    }

    private JsonObject rpc(String method, JsonObject params) throws Exception {
        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", 1);
        request.addProperty("method", method);
        request.add("params", params);
        Reply reply = this.http("POST", "/mcp", request.toString(), null, null);
        check(reply.status == 200, method + " answered HTTP " + reply.status);
        return JsonParser.parseString(reply.body).getAsJsonObject();
    }

    private static boolean isToolError(JsonObject response) {
        JsonObject result = response.getAsJsonObject("result");
        return result != null && result.has("isError") && result.get("isError").getAsBoolean();
    }

    private static String text(JsonObject response) {
        JsonArray content = response.getAsJsonObject("result").getAsJsonArray("content");
        return content.get(0).getAsJsonObject().get("text").getAsString();
    }

    /** Call a tool that must succeed and return its result object */
    JsonObject tool(String name, JsonObject arguments) throws Exception {
        JsonObject response = JsonParser.parseString(this.http("POST", "/mcp", call(1, name, arguments), null, null).body).getAsJsonObject();
        check(response.has("result"), name + " answered with a protocol error: " + response);
        check(!isToolError(response), name + " failed: " + text(response));
        return JsonParser.parseString(text(response)).getAsJsonObject();
    }

    /** Call a tool that must fail and return its message */
    String toolError(String name, JsonObject arguments) throws Exception {
        JsonObject response = JsonParser.parseString(this.http("POST", "/mcp", call(1, name, arguments), null, null).body).getAsJsonObject();
        check(response.has("result") && isToolError(response), name + " was expected to fail and did not: " + response);
        return text(response);
    }

    private record Reply(int status, String body) {
    }

    // A plain socket, because the tests need to send Host and Origin headers that Java's HTTP client will not
    private Reply http(String method, String path, String body, String origin, String host) throws Exception {
        try (Socket socket = new Socket(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), this.port)) {
            socket.setSoTimeout(30_000);
            byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            StringBuilder head = new StringBuilder();
            head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            head.append("Host: ").append(host == null ? "127.0.0.1:" + this.port : host).append("\r\n");
            if (origin != null) {
                head.append("Origin: ").append(origin).append("\r\n");
            }
            if (body != null) {
                head.append("Content-Type: application/json\r\n");
                head.append("Accept: application/json, text/event-stream\r\n");
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
            String reply = received.toString(StandardCharsets.UTF_8);
            int headEnd = reply.indexOf("\r\n\r\n");
            String statusLine = reply.substring(0, Math.max(0, reply.indexOf("\r\n")));
            String[] parts = statusLine.split(" ");
            check(parts.length >= 2, "the server sent no status line for " + method + " " + path);
            return new Reply(Integer.parseInt(parts[1]), headEnd < 0 ? "" : reply.substring(headEnd + 4));
        }
    }

    // --- Helpers for the steps ---

    /** The port the MCP server was found on when the run began */
    int port() {
        return this.port;
    }

    /** Runs something on the client thread and waits for it */
    <T> T onClient(java.util.concurrent.Callable<T> action) throws Exception {
        java.util.concurrent.CompletableFuture<T> done = new java.util.concurrent.CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            try {
                done.complete(action.call());
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        try {
            return done.get(60, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    void onClientRun(Runnable action) throws Exception {
        this.onClient(() -> {
            action.run();
            return null;
        });
    }

    void waitFor(java.util.function.BooleanSupplier condition, int seconds, String what) throws Exception {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new Failure("gave up after " + seconds + " seconds waiting for " + what);
            }
            Thread.sleep(50);
        }
    }

    void sleepTicks(int ticks) throws InterruptedException {
        Thread.sleep(ticks * 50L);
    }

    /** Runs a command as the player, on the built-in server, and waits until it has run */
    void command(String command) throws Exception {
        net.minecraft.client.server.IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        check(server != null, "there is no built-in server to run '" + command + "' on");
        server.submit(() -> {
            net.minecraft.server.level.ServerPlayer player = server.getPlayerList().getPlayers().get(0);
            server.getCommands().performPrefixedCommand(player.createCommandSourceStack().withPermission(4).withSuppressedOutput(), command);
        }).get(30, java.util.concurrent.TimeUnit.SECONDS);
    }

    void log(String message) {
        Host.LOGGER.info("Dailies self test: {}", message);
    }

    static JsonObject args(Object... pairs) {
        JsonObject json = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            if (value instanceof Boolean flag) {
                json.addProperty((String) pairs[i], flag);
            } else if (value instanceof Number number) {
                json.addProperty((String) pairs[i], number);
            } else if (value instanceof JsonElement element) {
                json.add((String) pairs[i], element);
            } else {
                json.addProperty((String) pairs[i], String.valueOf(value));
            }
        }
        return json;
    }

    // --- Outcomes ---

    /** A check that needs an optional mod or service, which is not there (TEST-4) */
    void skip(String what) {
        this.skipped.add(what);
    }

    static void check(boolean condition, String failure) {
        if (!condition) {
            throw new Failure(failure);
        }
    }

    private static final class Failure extends RuntimeException {
        Failure(String message) {
            super(message);
        }
    }

}
