package comedymonkey.dailies.selftest;

import com.google.gson.JsonObject;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static comedymonkey.dailies.selftest.SelfTest.args;
import static comedymonkey.dailies.selftest.SelfTest.check;

/**
 * Chapter XIV, the budgets (BUD-1 to BUD-8): measures each one, logs every measure against its target, writes them to
 * dailies/budgets.txt for whoever keeps the run's results, and fails only on a gross breach, more than twice the
 * target. The other side of a budget, a measure a little over, is logged as "over" and left for a person to judge:
 * other programs, and other test games on the same graphics card, make timings noisy.
 * <p>
 * Where another step already takes a measure it is used as well: the tour of generated terrain (RealWorldSteps, when
 * asked for) and the export speed (ExportSteps' H.264 export, BUD-6) report through {@link #measured}, and the jumps
 * and craft offsets of PlaybackSteps are read at the end. The rest is measured here, in a flat world of its own made
 * at the end of the run: recording's cost a frame, Moments' cost a tick, what a minute of recording fills, opening and
 * jumping.
 * <p>
 * BUD-8 (the Smart Camera) is the owner's to measure, with the Smart Camera's own steps, and is only listed here.
 */
final class BudgetSteps {

    static final String WORLD = "Dailies Budgets";

    /** One measure of a budget: what was measured, how much, and where */
    record Measure(String id, String what, double value, double target, boolean atMost, String unit, String where) {
        boolean gross() {
            return this.atMost ? this.value > this.target * 2 : this.value < this.target / 2;
        }

        boolean over() {
            return this.atMost ? this.value > this.target : this.value < this.target;
        }

        String line() {
            return String.format(Locale.ROOT, "%s | %s | %.3f %s | target %s %.3f %s | %s | %s", this.id, this.what, this.value, this.unit, this.atMost ? "at most" : "at least",
                this.target, this.unit, this.gross() ? "GROSS BREACH" : this.over() ? "over" : "within", this.where);
        }
    }

    private static final List<Measure> measures = new ArrayList<>();

    private static boolean hooked = false;
    private static volatile int frames = 0;
    private static volatile boolean walking = false;
    private static volatile float walkYaw = 0;
    private static volatile boolean timingTicks = false;
    private static volatile long tickStarted = 0;
    private static volatile double tickSum = 0;
    private static volatile int tickCount = 0;

    private BudgetSteps() {
    }

    /** A measure taken by another step, for the report at the end */
    static synchronized void measured(String id, String what, double value, double target, boolean atMost, String unit, String where) {
        measures.add(new Measure(id, what, value, target, atMost, unit, where));
    }

    static void run(SelfTest test) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        hook();
        if (Playback.active()) {
            test.tool("replay_close", new JsonObject());
            test.waitFor(() -> !Playback.active() && minecraft.level == null, 60, "the recording before the budgets to close");
        } else if (minecraft.level != null) {
            if (Recorder.get().recordingId() != null) {
                test.tool("recording_cancel", new JsonObject());
            }
            WorldSteps.leaveWorld(test);
        }
        WorldSteps.createWorld(test, WORLD);

        moments(test);
        Path recording = recording(test);
        WorldSteps.leaveWorld(test);
        playback(test, recording);
        test.tool("replay_close", new JsonObject());
        test.waitFor(() -> !Playback.active() && minecraft.level == null, 60, "the budgets' recording to close");

        fromOtherSteps();
        report(test);
    }

    private static synchronized void hook() {
        if (hooked) {
            return;
        }
        hooked = true;
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Post event) -> frames++);
        // The whole of a client tick, from before every other listener to after every other: Moments' share is the
        // difference between ticks with it on and with it off
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, (ClientTickEvent.Pre event) -> {
            if (timingTicks) {
                tickStarted = System.nanoTime();
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (walking && minecraft.player != null && !Playback.active()) {
                minecraft.options.keyUp.setDown(true);
                minecraft.player.setYRot(walkYaw);
            }
        });
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (ClientTickEvent.Post event) -> {
            if (timingTicks && tickStarted != 0) {
                tickSum += (System.nanoTime() - tickStarted) / 1.0e6;
                tickCount++;
                tickStarted = 0;
            }
        });
    }

    // --- BUD-5: Moments' cost a tick, in live play

    private static void moments(SelfTest test) throws Exception {
        double on = 0, off = 0;
        // Three turns each, so the machine waking or warming up does not pass for a cost
        for (int turn = 0; turn < 3; turn++) {
            test.tool("config_set", args("key", "momentsEnabled", "value", true));
            test.waitTicks(20);
            on += tickTime(test, 200);
            test.tool("config_set", args("key", "momentsEnabled", "value", false));
            test.waitTicks(20);
            off += tickTime(test, 200);
        }
        test.tool("config_set", args("key", "momentsEnabled", "value", true));
        double cost = Math.max(0, (on - off) / 3);
        measured("BUD-5", "Moments' tracking, a client tick", cost, 0.2, true, "ms", String.format(Locale.ROOT, "flat world, live, ticks averaged %.3f ms with Moments on and %.3f ms off over 600 ticks each", on / 3, off / 3));
        if (Host.get().setting("momentsAiEnabled", false)) {
            test.skip("BUD-5's second half, transcription keeping up in real time, is judged from the transcription steps' own timings, not here");
        } else {
            test.skip("AI is off: BUD-5's second half, transcription keeping up in real time, was not measured");
        }
    }

    private static double tickTime(SelfTest test, int ticks) throws Exception {
        tickSum = 0;
        tickCount = 0;
        tickStarted = 0;
        timingTicks = true;
        test.waitTicks(ticks);
        timingTicks = false;
        return tickCount == 0 ? 0 : tickSum / tickCount;
    }

    // --- BUD-1 and BUD-2: what recording costs a frame, and what it fills

    private static Path recording(SelfTest test) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        JsonObject none = new JsonObject();
        test.tool("recording_start", none);
        test.waitTicks(40);
        boolean vsync = minecraft.options.enableVsync().get();
        int limit = minecraft.options.framerateLimit().get();
        double idleSum = 0, recordingSum = 0;
        int idleFrames = 0, recordingFrames = 0;
        try {
            // As fast as the game will draw, so the frame's own cost is what is measured and not the monitor's pace
            test.onClientRun(() -> {
                minecraft.getWindow().updateVsync(false);
                minecraft.getWindow().setFramerateLimit(260);
            });
            for (int turn = 0; turn < 4; turn++) {
                test.tool("recording_pause", none);
                test.waitTicks(10);
                FrameTimes.Result idle = FrameTimes.measure(test, 70);
                test.tool("recording_resume", none);
                test.waitTicks(10);
                FrameTimes.Result recorded = FrameTimes.measure(test, 70);
                idleSum += idle.average() * idle.frames();
                idleFrames += idle.frames();
                recordingSum += recorded.average() * recorded.frames();
                recordingFrames += recorded.frames();
            }
        } finally {
            test.onClientRun(() -> {
                minecraft.getWindow().updateVsync(vsync);
                minecraft.getWindow().setFramerateLimit(limit);
            });
        }
        double idle = idleSum / Math.max(1, idleFrames);
        double recorded = recordingSum / Math.max(1, recordingFrames);
        double added = Math.max(0, recorded - idle);
        String where = String.format(Locale.ROOT, "the flat test world at %dx%d, standing still, uncapped: %.3f ms a frame paused, %.3f ms recording, over %d and %d frames",
            minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight(), idle, recorded, idleFrames, recordingFrames);
        measured("BUD-1", "recording's cost, added a frame", added, 1.0, true, "ms", where);
        // Against a frame of a game running at 144 frames a second: three per cent of an uncapped frame is too small to tell from noise
        measured("BUD-1", "recording's cost, of a frame at 144 a second", added / (1000.0 / 144) * 100, 3.0, true, "%", where);

        // Walking about for half a minute, turning every three seconds: what a minute of recording fills
        long size = workingSize();
        int from = Recorder.get().recordingTick();
        float[] headings = {0, 90, 180, 270, 45, 225, 135, 315, 0, 180};
        walking = true;
        try {
            for (float heading : headings) {
                walkYaw = heading;
                test.waitTicks(60);
            }
        } finally {
            walking = false;
            test.onClientRun(() -> minecraft.options.keyUp.setDown(false));
        }
        test.waitTicks(45);
        long bytes = workingSize() - size;
        int ticks = Recorder.get().recordingTick() - from;
        double hourly = bytes / 1048576.0 / (Math.max(1, ticks) / 72000.0);
        measured("BUD-2", "recording size, an hour", hourly, 150, true, "MB", String.format(Locale.ROOT, "the flat test world, walking about for %d ticks (%.2f MB); a flat world fills less than ordinary play, so the tour of generated terrain is the truer measure", ticks, bytes / 1048576.0));

        // Storing the whole world afresh is done on the game's thread between two frames, every couple of seconds: the longest
        // time it took is a hitch the player may see, so it is held to a frame at 120 frames a second
        double[] storing = Recorder.get().storingCost();
        measured("BUD-1", "storing the world, the longest pause after the first", storing[2], 1000.0 / 120, true, "ms", String.format(Locale.ROOT,
            "the flat test world, stored %d times (every %d seconds by default), %.2f ms on average; the tour of generated terrain and the other recordings log their own",
            (int) storing[0], Host.get().setting("snapshotSeconds", 2), storing[1]));

        JsonObject finished = test.tool("recording_finish", args("name", "Budgets"));
        Path saved = Path.of(finished.get("path").getAsString());
        Path folder = Host.get().dataDirectory().resolve("budgets");
        Files.createDirectories(folder);
        Path moved = folder.resolve(saved.getFileName());
        Files.move(saved, moved, StandardCopyOption.REPLACE_EXISTING);
        return moved;
    }

    private static long workingSize() throws Exception {
        try (Stream<Path> files = Files.list(Recorder.folder())) {
            long size = 0;
            for (Path file : files.toList()) {
                if (file.getFileName().toString().endsWith(Recorder.WORKING_EXTENSION)) {
                    size += Files.size(file);
                }
            }
            return size;
        }
    }

    // --- BUD-4 and BUD-3: opening and jumping. BUD-6, the speed of an export, is measured by ExportSteps on its one
    // H.264 export rather than by an export of its own here

    private static void playback(SelfTest test, Path recording) throws Exception {
        int before = frames;
        long started = System.nanoTime();
        test.tool("replay_open", args("path", recording.toString()));
        int[] loadedAt = {-1};
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline && Playback.lastError() == null) {
            if (Playback.loaded() && loadedAt[0] < 0) {
                loadedAt[0] = frames;
            }
            if (loadedAt[0] >= 0 && frames > loadedAt[0]) {
                break;
            }
            Thread.sleep(2);
        }
        long opened = (System.nanoTime() - started) / 1_000_000;
        check(Playback.lastError() == null && Playback.loaded(), "the budgets' recording did not open: " + Playback.lastError());
        RecordingManifest manifest = Playback.manifest();
        measured("BUD-4", "opening a recording, to its first frame", opened, 5000, true, "ms",
            String.format(Locale.ROOT, "the budgets' recording, %d ticks (the budget is for two hours: a short one only shows a gross breach), %d frames drawn meanwhile", manifest.totalTicks, frames - before));
        test.waitTicks(10);

        // Jumps to anywhere: the end, the start, the middle, and across snapshots both ways
        int total = manifest.totalTicks;
        List<Integer> starts = manifest.segments.stream().map(segment -> segment.startTick).toList();
        long longest = 0;
        for (int tick : new int[]{total, 0, total / 2, total / 4, total * 3 / 4, 5}) {
            longest = Math.max(longest, jump(test, tick));
        }
        // Short hops that stay between two snapshots
        long longestWithin = 0;
        for (int i = 1; i < starts.size(); i++) {
            int start = starts.get(i - 1), end = starts.get(i);
            if (end - start < 30) {
                continue;
            }
            jump(test, start + 2);
            longestWithin = Math.max(longestWithin, jump(test, end - 3));
            longestWithin = Math.max(longestWithin, jump(test, start + 5));
        }
        measured("BUD-3", "a jump to any tick", longest, 2000, true, "ms", "the budgets' recording, " + total + " ticks in " + starts.size() + " segments");
        if (longestWithin > 0) {
            measured("BUD-3", "a jump inside one segment", longestWithin, 500, true, "ms", "the budgets' recording, hops between the ends of each segment");
        }
    }

    /** Jumps, and says how long until the recording stood at exactly that tick; then a tick for the picture to settle */
    private static long jump(SelfTest test, int tick) throws Exception {
        long took = SelfTestSupport.timedJump(test, tick);
        test.waitTicks(1);
        return took;
    }

    // --- Measures other steps have already taken

    private static void fromOtherSteps() {
        if (PlaybackSteps.longestJumpMillis > 0) {
            measured("BUD-3", "a jump to any tick", PlaybackSteps.longestJumpMillis, 2000, true, "ms", "the scripted recording, the longest of PlaybackSteps' jumps");
        }
        if (PlaybackSteps.worstCraftBeyondSampling >= 0) {
            measured("BUD-7", "craft from their live position", PlaybackSteps.worstCraftBeyondSampling, 0.3, true, "blocks", "PlaybackSteps' comparisons, less the one tick of travel they allow for sampling");
        }
        if (PlaybackSteps.worstDeckBeyondSampling >= 0) {
            measured("BUD-7", "a standing passenger from the deck", PlaybackSteps.worstDeckBeyondSampling, 0.25, true, "blocks", "PlaybackSteps' comparisons (MOD-4), less the one tick of travel they allow for sampling");
        }
    }

    // --- The report

    private static void report(SelfTest test) throws Exception {
        List<String> lines = new ArrayList<>();
        List<String> gross = new ArrayList<>();
        List<Measure> all;
        synchronized (BudgetSteps.class) {
            all = List.copyOf(measures);
        }
        for (String id : List.of("BUD-1", "BUD-2", "BUD-3", "BUD-4", "BUD-5", "BUD-6", "BUD-7", "BUD-8")) {
            List<Measure> forId = all.stream().filter(measure -> measure.id().equals(id)).toList();
            if (forId.isEmpty()) {
                String why = switch (id) {
                    case "BUD-7" -> "no craft were built and compared (needs Create, Sable and Create Aeronautics)";
                    case "BUD-8" -> "the Smart Camera's budget is the owner's to measure, with the Smart Camera's own steps";
                    default -> "not measured on this run";
                };
                lines.add(id + " | not measured | " + why);
                test.skip(id + " was not measured: " + why);
                continue;
            }
            for (Measure measure : forId) {
                lines.add(measure.line());
                if (measure.gross()) {
                    gross.add(measure.line());
                }
            }
        }
        for (String line : lines) {
            test.log("budget: " + line);
        }
        try {
            Files.write(Host.get().dataDirectory().resolve("budgets.txt"), lines, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Host.LOGGER.warn("Dailies self test: the budgets could not be written to budgets.txt", e);
        }
        check(gross.isEmpty(), gross.size() + " budget" + (gross.size() == 1 ? " is" : "s are") + " more than twice over; the first: " + (gross.isEmpty() ? "" : gross.get(0)));
    }

}
