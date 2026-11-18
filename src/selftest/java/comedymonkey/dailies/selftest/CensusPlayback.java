package comedymonkey.dailies.selftest;

import comedymonkey.dailies.export.Export;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.Viewer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;

import static comedymonkey.dailies.selftest.SelfTest.args;

/**
 * The bestiary's way of checking a recording, for the steps that stage a mod's things in a recording of their own
 * (the owner's addon, Create's trains): the recording is played through and compared with the live game tick by tick,
 * then jumped to at every marked tick and every hundred ticks, forwards and then backwards, where every landing starts
 * from a stored world; the stills the staging asked for are exported; and everything that differs is reported item by
 * item, failing the step at the end with the list. The staging fills BestiarySteps' items, marks and stills and the
 * live census, and sets BestiarySteps.test, as the bestiary does.
 */
final class CensusPlayback {

    private CensusPlayback() {
    }

    /** More checks of a step's own, made while the recording is open, after the jumps and before the stills */
    interface WhileOpen {
        void run(Census.Tally tally) throws Exception;
    }

    static void compare(SelfTest test, Path recording, Path folder, String heading, String step) throws Exception {
        compare(test, recording, folder, heading, step, null);
    }

    static void compare(SelfTest test, Path recording, Path folder, String heading, String step, WhileOpen more) throws Exception {
        int total = Census.live.lastKey();
        Census.Tally tally = new Census.Tally();
        Census.tally = tally;
        Census.playSounds.clear();
        Census.skippedSounds.clear();
        test.tool("replay_open", args("path", recording.toString()));
        test.waitFor(() -> Playback.loaded() || Playback.lastError() != null, 120, "the " + step + " recording to open");
        SelfTest.check(Playback.lastError() == null, "the " + step + " recording did not open: " + Playback.lastError());
        int length = Playback.total();
        try {
            // By play, comparing every tick
            test.onClientRun(() -> Census.aim(1));
            Census.start(Census.Mode.PLAY);
            test.tool("replay_set_speed", args("tick_rate", 40));
            test.onClientRun(() -> Playback.playTo(length));
            test.waitFor(() -> (Playback.paused() && Playback.position() >= length) || Playback.lastError() != null, 600, "the " + step + " recording to play through");
            SelfTest.check(Playback.lastError() == null, "playback failed while the " + step + " recording played: " + Playback.lastError());
            Census.stop();
            test.tool("replay_set_speed", args("tick_rate", 20));
            int played = tally.compared;
            BestiarySteps.noteCoverage(played, length);
            test.log(step + ": played through " + length + " ticks, " + played + " compared with the live game");

            // By jump, forwards, then backwards
            TreeSet<Integer> stops = new TreeSet<>(BestiarySteps.marks.keySet());
            for (int tick = BestiarySteps.EVERY; tick < total; tick += BestiarySteps.EVERY) {
                stops.add(tick);
            }
            stops.removeIf(tick -> !Census.live.containsKey(tick) || Census.live.get(tick).blocks == null || tick > length);
            PlaybackSteps.jump(test, 0);
            for (int stop : stops) {
                BestiarySteps.land(stop, "jump", tally);
            }
            for (int stop : stops.descendingSet()) {
                BestiarySteps.land(stop, "jump back", tally);
            }
            test.log(step + ": jumped to " + stops.size() + " ticks forwards and backwards");
            if (!Census.skippedSounds.isEmpty()) {
                tally.add("jump", "sounds", "were played from time that was skipped over", 0, Census.skippedSounds.size() + " sounds, first " + Census.skippedSounds.get(0), false);
            }

            if (more != null) {
                try {
                    more.run(tally);
                } catch (Throwable t) {
                    BestiarySteps.problems.add("the " + step + " step's own checks in playback stopped early: " + t);
                }
            }

            // Stills, for a person to look at
            for (BestiarySteps.Still still : BestiarySteps.stills) {
                try {
                    if (still.byPlay()) {
                        PlaybackSteps.jump(test, Math.max(0, still.tick() - 30));
                        test.onClientRun(() -> Viewer.set(still.x(), still.y(), still.z(), still.yaw(), still.pitch()));
                        test.onClientRun(() -> Playback.playTo(still.tick()));
                        test.waitFor(() -> Playback.paused() && Playback.position() == still.tick(), 60, "play to reach the still at tick " + still.tick());
                    } else {
                        PlaybackSteps.jump(test, still.tick());
                    }
                    test.onClientRun(() -> Viewer.set(still.x(), still.y(), still.z(), still.yaw(), still.pitch()));
                    test.waitTicks(3);
                    Path picture = folder.resolve(still.name() + ".png");
                    test.tool("export_screenshot", args("output_path", picture.toString(), "width", 1280, "height", 720, "no_gui", true, "overwrite", true));
                    test.waitFor(() -> !Export.active(), 60, "the still " + still.name());
                    test.waitFor(() -> !Playback.jumping(), 30, "playback to be put back after a still");
                    if (!Files.exists(picture)) {
                        BestiarySteps.problems.add("the still " + still.name() + " was not written");
                    }
                } catch (Throwable t) {
                    BestiarySteps.problems.add("the still " + still.name() + " at tick " + still.tick() + " could not be taken: " + t.getMessage());
                }
            }
            test.log(step + ": " + BestiarySteps.stills.size() + " stills written to " + folder);
            PlaybackSteps.close(test);
            BestiarySteps.report(heading, step, folder, tally, total, played, stops.size());
        } finally {
            Census.stop();
            if (Playback.loaded()) {
                PlaybackSteps.close(test);
            }
        }
    }

    /**
     * For each item that was not staged, what the live game last showed of what it owns, in the log: the block
     * entities of its kinds and the entities of its names, at the end of its stretch of the recording
     */
    static void explain(SelfTest test, String step) {
        for (BestiarySteps.Item item : BestiarySteps.items) {
            if (item.missing.isEmpty() || Census.live.isEmpty()) {
                continue;
            }
            java.util.Map.Entry<Integer, Census> last = Census.live.floorEntry(Math.min(item.to, Census.live.lastKey()));
            if (last == null) {
                continue;
            }
            StringBuilder seen = new StringBuilder();
            for (String label : item.labels) {
                if (label.startsWith("block entity ")) {
                    String kind = label.substring("block entity ".length());
                    last.getValue().blockEntities.forEach((place, text) -> {
                        if (text.startsWith(kind + " ")) {
                            seen.append(" | ").append(net.minecraft.core.BlockPos.of(place).toShortString()).append(' ').append(text, 0, Math.min(text.length(), 700));
                        }
                    });
                } else {
                    last.getValue().things.values().forEach(thing -> {
                        if (thing.label().equals(label)) {
                            seen.append(" | ").append(label).append(String.format(java.util.Locale.ROOT, " at %.1f %.1f %.1f ", thing.x(), thing.y(), thing.z()))
                                .append(thing.state(), 0, Math.min(thing.state().length(), 500)).append(' ').append(thing.clocks());
                        }
                    });
                }
            }
            test.log(step + ": not staged: " + item.name + " (" + item.missing + "); at tick " + last.getKey() + " the live game showed" + (seen.length() == 0 ? " none of it" : seen));
        }
    }

    /** A fresh start for a step's own staging: nothing left of the bestiary or another step */
    static void begin(SelfTest test) {
        BestiarySteps.test = test;
        BestiarySteps.minecraft = net.minecraft.client.Minecraft.getInstance();
        BestiarySteps.forget();
        BestiarySteps.pictureNotes.clear();
        Census.EXTRAS.clear();
        Census.EXTRA_SLACK.clear();
    }

    /** And nothing of it left for whatever runs next */
    static void end() {
        BestiarySteps.forget();
        BestiarySteps.pictureNotes.clear();
        Census.EXTRAS.clear();
        Census.EXTRA_SLACK.clear();
        Census.tally = null;
    }
}
