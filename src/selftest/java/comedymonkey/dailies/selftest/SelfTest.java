package comedymonkey.dailies.selftest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.export.Export;
import comedymonkey.dailies.mcp.McpHttpServer;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The scripted run of specification chapter XIV. Switched on by the system property dailies.test.script, it waits
 * for the game to finish loading, works through its steps, writes PASSED or FAILED: reason to the log and to
 * dailies/test-result.txt, with the number of checks skipped, and closes the game. Steps are added here as each part
 * of the mod is built.
 */
public final class SelfTest {

    public static final String PROPERTY = "dailies.test.script";

    /**
     * The mod ids the game must have loaded, comma-separated, set by the modded run (TEST-1): a mods folder that has
     * lost one of them would otherwise only show as skips, and the run as passed
     */
    static final String EXPECTED_MODS = "dailies.test.expectMods";

    private static final int LOAD_TIMEOUT_SECONDS = 300;

    /** Game ticks the client has run since the self test began, counted at the end of each: what waitTicks counts */
    private static final AtomicLong CLIENT_TICKS = new AtomicLong();

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
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> CLIENT_TICKS.incrementAndGet());
        Thread thread = new Thread(() -> new SelfTest().run(), "Dailies-Self-Test");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        String result;
        try {
            this.waitForGame();
            this.checkExpectedMods();
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
            // Only some groups of steps, with the groups they need, for a short run while one thing is being fixed:
            // gradlew runTestClient -PdailiesOnly=trains,library, or -PdailiesOnly=failed for the groups that failed last time
            Set<String> chosen = chosenGroups();
            boolean tourFirst = chosen == null && Boolean.getBoolean("dailies.test.realWorldFirst");
            if (tourFirst) {
                // The tour first and everything else after it, with the tour's verdict at the end: one long run that tells
                // both what the tour found and whether the rest still passes. -PdailiesFlags=dailies.test.realWorldFirst
                RealWorldSteps.run(this, true);
            }
            this.checkMcp();
            if (chosen != null) {
                this.log("running only " + chosen + " (asked for " + System.getProperty(ONLY) + ")");
                // As the whole run starts: no recordings from an earlier run to pass or fail a check
                SelfTestSupport.deleteTree(comedymonkey.dailies.capture.Recorder.folder());
            }
            for (Entry entry : this.plan(tourFirst)) {
                if (chosen != null ? !chosen.contains(entry.group()) : !entry.inWholeRun()) {
                    if (chosen == null && entry.skipped() != null) {
                        this.skip(entry.skipped());
                    }
                    continue;
                }
                int before = this.failures.size();
                this.step(entry.label(), entry.body());
                if (this.failures.size() > before) {
                    this.failedGroups.add(entry.group());
                }
            }
            if (tourFirst) {
                RealWorldSteps.verdict();
            }
            this.writeFailedGroups();
            if (chosen != null) {
                // Its verdict names what was run, so a pass of some groups is never taken for a pass of the whole run
                this.finish(this.verdict(), "only " + String.join(", ", chosen));
                return;
            }
            result = this.verdict();
        } catch (Throwable t) {
            Host.LOGGER.error("Dailies self test: a step failed", t);
            result = "FAILED: " + (t instanceof Failure ? t.getMessage() : t.toString());
        }
        this.finish(result, null);
    }

    /** PASSED, or FAILED with every step that failed and why */
    private String verdict() {
        return this.failures.isEmpty() ? "PASSED" : "FAILED: " + this.failures.size() + " step(s): " + String.join(" || ", this.failures);
    }

    /**
     * The modded run says which mods it must have (dailies.test.expectMods, from build.gradle). One that is missing
     * fails the run at once: the steps for it would only skip, and a broken mods folder would pass
     */
    private void checkExpectedMods() {
        String expected = System.getProperty(EXPECTED_MODS, "").trim();
        if (expected.isEmpty()) {
            return;
        }
        List<String> missing = new ArrayList<>();
        for (String mod : expected.split(",")) {
            String id = mod.trim();
            if (!id.isEmpty() && !ModList.get().isLoaded(id)) {
                missing.add(id);
            }
        }
        check(missing.isEmpty(), "the game is missing mods the run is meant to have: " + String.join(", ", missing)
            + " (" + EXPECTED_MODS + "; the jars come from dailies.modsFolder, by the names in dailies.testMods)");
        this.log("every mod the run expects is loaded: " + expected);
    }

    /** The system property naming the groups to run by themselves (from -PdailiesOnly) */
    private static final String ONLY = "dailies.test.only";
    /** Where a run writes the groups that failed, for -PdailiesOnly=failed; in the game folder, apart from the recordings */
    private static final String FAILED_FILE = "selftest-failed.txt";

    /**
     * One top-level step of the scripted run.
     *
     * @param group      what -PdailiesOnly names it by; several steps can share a group, which then runs them all
     * @param label      its name in the log and the verdict
     * @param inWholeRun whether the whole run includes it (some long parts only when asked for by a flag)
     * @param skipped    what the whole run says when it leaves the step out, or null
     */
    private record Entry(String group, String label, Step body, boolean inWholeRun, @Nullable String skipped) {
    }

    private static Entry always(String group, String label, Step body) {
        return new Entry(group, label, body, true, null);
    }

    private static Entry when(boolean asked, @Nullable String skipped, String group, String label, Step body) {
        return new Entry(group, label, body, asked, skipped);
    }

    /** Every step of the whole run, in the order they run */
    private List<Entry> plan(boolean tourFirst) {
        boolean packs = !Boolean.getBoolean("dailies.test.skipPacks");
        boolean quickFilms = Boolean.getBoolean("dailies.test.cameraFilmsQuick");
        return List.of(
            always("settings", "SettingsSteps.run", () -> SettingsSteps.run(this, this.port)),
            always("world", "WorldSteps.createWorld", () -> WorldSteps.createWorld(this)),
            // First, while nothing else has been recorded: it makes a recording of its own and puts it aside for later
            always("watching", "WatchingSteps.record", () -> WatchingSteps.record(this)),
            always("moments", "MomentsExtrasSteps.run", () -> MomentsExtrasSteps.run(this)),
            always("recording", "PointOfViewSteps.beforeCraft", () -> PointOfViewSteps.beforeCraft(this)),
            always("recording", "RecordingSteps.run", () -> RecordingSteps.run(this)),
            always("recording", "PointOfViewSteps.craftMoments", () -> PointOfViewSteps.craftMoments(this)),
            // A switch for running everything else while a fault in the pack step is being looked into
            when(packs, "the resource pack steps were switched off for this run (dailies.test.skipPacks)", "packs", "ResourcePackSteps.record", () -> ResourcePackSteps.record(this)),
            always("playback", "PlaybackSteps.run", () -> PlaybackSteps.run(this)),
            always("balloon", "BalloonSteps.run", () -> BalloonSteps.run(this)),
            always("export", "ExportSteps.run", () -> ExportSteps.run(this)),
            always("editor", "SettingsSteps.editorDropdown", () -> SettingsSteps.editorDropdown(this)),
            always("editor", "EditorDesignSteps.run", () -> EditorDesignSteps.run(this)),
            always("export", "ExportPresetSteps.run", () -> ExportPresetSteps.run(this)),
            // With the recording that has the machines in it still open: Flywheel's contraption, Iris's clock, Distant Horizons
            always("renderer", "RendererSteps.run", () -> RendererSteps.run(this)),
            always("export", "QueueSteps.run", () -> QueueSteps.run(this)),
            always("playback", "PlaybackSteps.close", () -> PlaybackSteps.close(this)),
            // While the recording with the machines in it is still there: the library steps take it apart
            always("watching", "WatchingSteps.machines", () -> WatchingSteps.machines(this)),
            always("moments", "OwnerFeatureSteps.run", () -> OwnerFeatureSteps.run(this)),
            always("moments", "TimelineSteps.original", () -> TimelineSteps.original(this)),
            always("library", "LibrarySteps.run", () -> LibrarySteps.run(this)),
            when(packs, null, "packs", "ResourcePackSteps.play", () -> ResourcePackSteps.play(this)),
            always("watching", "WatchingSteps.watch", () -> WatchingSteps.watch(this)),
            // The library's screens, the bin and storage, with small recordings of their own (REC-5, REC-8, REC-9)
            always("library-screens", "LibraryScreenSteps.run", () -> LibraryScreenSteps.run(this)),
            always("points-of-view", "PointOfViewSteps.run", () -> PointOfViewSteps.run(this)),
            // The owner's own addon and Create's trains, each in a world and a recording of its own; skipped with a line when
            // their mods are not installed (the plain game)
            always("addon", "AddonSteps.run", () -> AddonSteps.run(this)),
            always("trains", "TrainSteps.run", () -> TrainSteps.run(this)),
            // Powder snow, snowballs and the void, in a recording of their own
            always("edges", "EdgeSteps.run", () -> EdgeSteps.run(this)),
            // A recording made on a server that numbers its sounds differently from this game, simulated by numbering them so
            // before a world opens, plays each sound as itself (REC-37, PLAY-25); two short recordings in a world of their own
            always("registries", "RegistrySteps.run", () -> RegistrySteps.run(this)),
            // The recorder's windows (a chest, a furnace, a villager's trades, the inventory) drawn in playback, in a world and a
            // short recording of their own; each later compat adds a display step of the same shape
            always("windows", "WindowSteps.run", () -> WindowSteps.run(this)),
            // The game's books, lectern and signs, through Dailies' own compat (REC-29): its display test, in a world and a
            // recording of its own
            always("windows", "CompatDisplaySteps.vanilla", () -> CompatDisplaySteps.vanilla(this)),
            // Post Rider's mailbox, a letter read and a letter written, through the compat in Post Rider itself (REC-26, PLAY-21):
            // the compat's own display test, in a world and a recording of its own; skipped with a line without Post Rider
            always("postrider", "CompatDisplaySteps.postRider", () -> CompatDisplaySteps.postRider(this)),
            // Create's value settings board and value box, the goggles, a sequenced gearshift and a clipboard, through the
            // Create compat (compats/create, CPT-6): in a world and a recording of its own; skipped with a line without Create
            // or the compat
            always("create", "CompatDisplaySteps.create", () -> CompatDisplaySteps.create(this)),
            // Jade's box over play, through the Jade compat (compats/jade, CPT-8): in a world and a recording of its own; skipped
            // with a line without Jade or the compat
            always("jade", "CompatDisplaySteps.jade", () -> CompatDisplaySteps.jade(this)),
            // Create Aeronautics and its add-ons, through the Create Aeronautics compat (compats/aeronautics, CPT-9): in a world and
            // a recording of their own; skipped with a line without the mods or the compat
            always("aeronautics", "CompatDisplaySteps.aeronautics", () -> CompatDisplaySteps.aeronautics(this)),
            // Supplementaries and Amendments, through the Supplementaries compat (compats/supplementaries, CPT-10): in a world and a
            // recording of their own; then their placed items (skull piles, skull candles, book piles and the rest) played back
            // in place, in another; each skipped with a line without the mods or the compat
            always("supplementaries", "CompatDisplaySteps.supplementaries", () -> CompatDisplaySteps.supplementaries(this)),
            always("supplementaries", "SupplementariesPlacingSteps.run", () -> SupplementariesPlacingSteps.run(this)),
            // Create's other add-ons, through the compat for them (compats/createextras, CPT-11): in a world and a recording of
            // their own; skipped with a line without the add-ons or the compat
            always("createextras", "CompatDisplaySteps.createExtras", () -> CompatDisplaySteps.createExtras(this)),
            // The rest of the pack (the compasses, Sophisticated Backpacks, Curios, Corpse, VanillaBackport),
            // through the extras compat (compats/extras, CPT-12): in a world and a recording of their own; skipped with a line
            // without the mods or the compat
            always("extras", "CompatDisplaySteps.extras", () -> CompatDisplaySteps.extras(this)),
            // The owner's own mods, each through the compat in the mod itself, in a world and a recording of its own; skipped
            // with a line without the mod, or a version of it without the compat
            always("createaddons", "CompatDisplaySteps.createAddons", () -> CompatDisplaySteps.createAddons(this)),
            always("mcpcraft", "CompatDisplaySteps.mcpCraft", () -> CompatDisplaySteps.mcpCraft(this)),
            // About ten minutes when it records, about six when its fixture is reused: -PdailiesFlags=dailies.test.bestiary.
            // The recording is kept in dailies/fixtures; dailies.test.recordTours records it (and the tour below) again
            when(Boolean.getBoolean("dailies.test.bestiary"), "the bestiary was not asked for (dailies.test.bestiary)", "bestiary", "BestiarySteps.run", () -> BestiarySteps.run(this)),
            // In a world and with a recording of its own: everything above is in a flat world, this is in generated terrain. It
            // takes about twelve minutes when it records and three when its fixture is reused, so it runs only when asked for:
            // -PdailiesFlags=dailies.test.realWorld (with realWorldFirst it has already run, first)
            when(!tourFirst && Boolean.getBoolean("dailies.test.realWorld"), tourFirst ? null : "the tour of generated terrain was not asked for (dailies.test.realWorld)",
                "tour", "RealWorldSteps.run", () -> RealWorldSteps.run(this)),
            // Every tracking camera filmed in every place of a recorded route and measured (FILM-1 to FILM-9). About an hour and
            // a quarter: -PdailiesFlags=dailies.test.cameraFilms; ten seconds a film, to check the harness: dailies.test.cameraFilmsQuick.
            // The route is recorded once and kept; dailies.test.recordRoute records it again
            when(Boolean.getBoolean("dailies.test.cameraFilms") || quickFilms, "the camera films were not asked for (dailies.test.cameraFilms)",
                "camera-films", "CameraFilms.run", () -> CameraFilms.run(this, quickFilms)),
            // Last, so the tour's measures are there to be reported with its own. About three minutes, in a world of its own
            when(!Boolean.getBoolean("dailies.test.skipBudgets"), "the budgets were switched off for this run (dailies.test.skipBudgets)", "budgets", "BudgetSteps.run", () -> BudgetSteps.run(this)));
    }

    /**
     * The groups -PdailiesOnly asks for, with every group they need, or null for the whole run. "failed" stands for the
     * groups that failed in the last run in this game folder. A name that is no group fails the run at once, with the list
     */
    private @Nullable Set<String> chosenGroups() throws java.io.IOException {
        String asked = System.getProperty(ONLY, "").trim();
        if (asked.isEmpty()) {
            return null;
        }
        Set<String> known = new java.util.LinkedHashSet<>();
        for (Entry entry : this.plan(false)) {
            known.add(entry.group());
        }
        List<String> names = new ArrayList<>();
        for (String name : asked.split(",")) {
            String group = name.trim();
            if (group.equals("failed")) {
                Path file = Host.get().dataDirectory().resolve(FAILED_FILE);
                check(Files.exists(file), "-PdailiesOnly=failed: no run in this game folder has written down what failed (" + file + ")");
                List<String> failed = Files.readAllLines(file).stream().map(String::trim).filter(line -> !line.isEmpty()).toList();
                check(!failed.isEmpty(), "-PdailiesOnly=failed: nothing failed in the last run");
                names.addAll(failed);
            } else if (!group.isEmpty()) {
                names.add(group);
            }
        }
        List<String> unknown = names.stream().filter(name -> !known.contains(name)).toList();
        check(unknown.isEmpty(), "-PdailiesOnly names no group " + unknown + "; the groups are " + known + ", and failed");
        return StepGroups.withNeeds(names);
    }

    /** Groups that failed in this run, for the file -PdailiesOnly=failed reads */
    private final Set<String> failedGroups = new java.util.LinkedHashSet<>();

    /** Writes down which groups failed (none: an empty file), so the next run can be just those */
    private void writeFailedGroups() {
        try {
            Path file = Host.get().dataDirectory().resolve(FAILED_FILE);
            Files.createDirectories(file.getParent());
            Files.write(file, this.failedGroups);
            if (!this.failedGroups.isEmpty()) {
                this.log("groups that failed, for -PdailiesOnly=failed: " + String.join(",", this.failedGroups));
            }
        } catch (java.io.IOException e) {
            Host.LOGGER.warn("Dailies self test: could not write down which groups failed", e);
        }
    }

    /** Steps that failed in this run, each with its reason; the run carries on past them so that one run finds them all */
    private final List<String> failures = new ArrayList<>();

    /** The step running now, with the part of it when it has parts: what a fault is written down under */
    private String currentStep;

    interface Step {
        void run() throws Exception;
    }

    /** One named part of a long step, for {@link #parts} */
    record Part(String name, Step body) {
    }

    static Part part(String name, Step body) {
        return new Part(name, body);
    }

    /** Runs one group of steps; a failure is written down and the run goes on with the next group */
    void step(String name, Step body) {
        String outer = this.currentStep;
        String full = outer == null ? name : outer + " > " + name;
        this.currentStep = full;
        try {
            body.run();
        } catch (Throwable t) {
            Host.LOGGER.error("Dailies self test: {} failed; carrying on with the next steps", full, t);
            this.failures.add(full + ": " + (t instanceof Failure ? t.getMessage() : t.toString()));
            Host.LOGGER.info("DAILIES TEST STEP FAILED: {}: {}", full, t instanceof Failure ? t.getMessage() : t.toString());
            try {
                this.onClientRun(() -> {
                    Minecraft minecraft = Minecraft.getInstance();
                    if (minecraft.gui.screen() != null && minecraft.level != null) {
                        minecraft.gui.setScreen(null);
                    }
                });
            } catch (Throwable ignored) {
                // The next step finds the game as it is
            }
        } finally {
            this.currentStep = outer;
        }
    }

    /**
     * The parts of one long step, in order, each run through {@link #step} so that a failure names the part it was in.
     * Each part works on what the ones before it made, so after a failed part the rest are not run: they are named in
     * the log instead, and the failure already written down stands for them
     */
    void parts(Part... parts) {
        int failuresBefore = this.failures.size();
        for (int i = 0; i < parts.length; i++) {
            this.step(parts[i].name(), parts[i].body());
            if (this.failures.size() > failuresBefore) {
                for (int j = i + 1; j < parts.length; j++) {
                    this.log("not run, as '" + parts[i].name() + "' failed: " + parts[j].name());
                }
                return;
            }
        }
    }

    /**
     * A check that failed where the rest of the step is still worth running, such as a mob that would not take a Create
     * seat with Create installed: written down as a failure of the run, and the step goes on. A skip is only for what is
     * not there to try (TEST-4); something that is there and does not work is a failure
     */
    void fault(String what) {
        String where = this.currentStep == null ? "" : this.currentStep + ": ";
        this.failures.add(where + what);
        Host.LOGGER.info("DAILIES TEST STEP FAILED: {}{}", where, what);
    }

    /**
     * Writes the verdict, with how many checks were skipped and, for a run of only some parts, which: for example
     * "PASSED (bestiary only, 3 skipped)". CI reads the line for PASSED and shows it whole
     */
    private void finish(String result, String only) {
        for (String line : this.skipped) {
            Host.LOGGER.info("DAILIES TEST skipped: {}", line);
        }
        String line = result + " (" + (only == null ? "" : only + ", ") + this.skipped.size() + " skipped)";
        Host.LOGGER.info("DAILIES TEST {}", line);
        try {
            Files.writeString(Host.get().dataDirectory().resolve("test-result.txt"), line + System.lineSeparator(), StandardCharsets.UTF_8);
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
        long deadline = System.nanoTime() + LOAD_TIMEOUT_SECONDS * 1_000_000_000L;
        boolean focusSet = false;
        while (System.nanoTime() - deadline < 0) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null && minecraft.options != null && !focusSet) {
                // The owner watches runs and types elsewhere, so losing focus must not pause the game
                minecraft.options.pauseOnLostFocus = false;
                focusSet = true;
            }
            this.port = McpHttpServer.getListeningPort();
            if (focusSet && this.port > 0 && minecraft.gui.overlay() == null && minecraft.isGameLoadFinished()) {
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
        } catch (java.util.concurrent.TimeoutException e) {
            logThreads("60 seconds waiting for the client thread to run a step");
            throw e;
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

    /** Waits until the condition holds, by the monotonic clock, which a change of the computer's time does not move */
    void waitFor(java.util.function.BooleanSupplier condition, int seconds, String what) throws Exception {
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) {
                logThreads(seconds + " seconds waiting for " + what);
                throw new Failure("gave up after " + seconds + " seconds waiting for " + what);
            }
            Thread.sleep(10);
        }
    }

    /**
     * Writes where every thread of the game stands to the log, the render thread first, in one entry: called once as a
     * wait gives up, before its failure is raised, so a step that hangs (a jump that never finishes, say) shows where the
     * game was stuck. A stack trace is a snapshot, taken thread by thread, so two threads may be seen a moment apart
     */
    static void logThreads(String why) {
        try {
            Map<Thread, StackTraceElement[]> traces = Thread.getAllStackTraces();
            List<Thread> threads = new ArrayList<>(traces.keySet());
            threads.sort(java.util.Comparator.comparing((Thread thread) -> !"Render thread".equals(thread.getName())).thenComparing(Thread::getName));
            StringBuilder dump = new StringBuilder("Dailies self test: gave up after ").append(why).append("; every thread, the render thread first:");
            for (Thread thread : threads) {
                dump.append("\n\n\"").append(thread.getName()).append("\"").append(thread.isDaemon() ? " daemon" : "").append(" ").append(thread.getState());
                for (StackTraceElement frame : traces.get(thread)) {
                    dump.append("\n\tat ").append(frame);
                }
            }
            Host.LOGGER.warn(dump.toString());
        } catch (Throwable t) {
            Host.LOGGER.warn("Dailies self test: could not write down the threads after {}", why, t);
        }
    }

    /**
     * Lets this many game ticks go by, counted as the client runs them, not guessed from the clock: on a busy machine
     * fewer ticks pass in a second than twenty, and a check made after a fixed sleep would come too soon. While an
     * export holds the game's clock the client runs no ticks of its own, so real time, fifty milliseconds a tick, stands
     * in for them then. On the game's own thread no tick could pass while it waited, so there it can only sleep
     */
    void waitTicks(int ticks) throws Exception {
        if (ticks <= 0) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.isSameThread()) {
            Thread.sleep(ticks * 50L);
            return;
        }
        long seen = CLIENT_TICKS.get();
        double passed = 0.0;
        long last = System.nanoTime();
        // Generous: a tick under load can take many times its fifty milliseconds, and a world loading takes a while
        int seconds = ticks / 5 + 60;
        long deadline = last + seconds * 1_000_000_000L;
        while (passed < ticks) {
            Thread.sleep(5);
            long now = System.nanoTime();
            long counted = CLIENT_TICKS.get();
            if (counted > seen) {
                passed += counted - seen;
                seen = counted;
            } else if (Export.holdsClock()) {
                passed += (now - last) / 50_000_000.0;
            }
            last = now;
            if (now - deadline > 0) {
                logThreads(seconds + " seconds waiting for " + ticks + " game ticks to pass");
                throw new Failure("gave up after " + seconds + " seconds waiting for " + ticks + " game ticks to pass (" + (int) passed + " did)");
            }
        }
    }

    /** Runs a command as the player, on the built-in server, and waits until it has run */
    void command(String command) throws Exception {
        net.minecraft.client.server.IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        check(server != null, "there is no built-in server to run '" + command + "' on");
        server.submit(() -> {
            net.minecraft.server.level.ServerPlayer player = server.getPlayerList().getPlayers().get(0);
            server.getCommands().performPrefixedCommand(player.createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER).withSuppressedOutput(), command);
        }).get(30, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * Runs a command as {@link #command} does, and says whether it ran: one the game refuses (a typo, a block state it
     * does not have) gives false, where {@link #command} lets it pass unseen, its output being hidden
     */
    boolean commandRan(String command) throws Exception {
        net.minecraft.client.server.IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        check(server != null, "there is no built-in server to run '" + command + "' on");
        java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean(false);
        server.submit(() -> {
            net.minecraft.server.level.ServerPlayer player = server.getPlayerList().getPlayers().get(0);
            server.getCommands().performPrefixedCommand(player.createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER).withSuppressedOutput()
                .withCallback((success, result) -> {
                    if (success) {
                        ran.set(true);
                    }
                }), command);
        }).get(30, java.util.concurrent.TimeUnit.SECONDS);
        return ran.get();
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

    /**
     * A check that needs an optional mod, service or part of the run, which is not there or not asked for (TEST-4), so it
     * could not be tried. Not for something that is there and did not work: that is a {@link #fault} or a failed check
     */
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
