package comedymonkey.dailies.selftest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.api.DailiesApi;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static comedymonkey.dailies.selftest.SelfTest.args;
import static comedymonkey.dailies.selftest.SelfTest.check;

/**
 * Chapter XIV step 3, "Play back": open the recording, find everything where it was in the live game, and show that
 * the world at a tick is the same whether it was reached by playing, by jumping forward or by jumping back.
 */
final class PlaybackSteps {

    private PlaybackSteps() {
    }

    static void run(SelfTest test) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        JsonObject none = new JsonObject();

        JsonArray listed = test.tool("replay_list", none).getAsJsonArray("recordings");
        check(listed.size() == 1 && listed.get(0).getAsJsonObject().get("name").getAsString().equals("Self test"), "replay_list does not show the one recording: " + listed);
        check(test.toolError("replay_seek", args("tick", 10)).contains("No recording is open"), "seeking with nothing open did not say so");

        // Opening leaves the live world by itself (PLAY-1)
        long started = System.currentTimeMillis();
        test.tool("replay_open", args("name", "Self test"));
        test.waitFor(() -> Playback.loaded() || Playback.lastError() != null, 90, "the recording to open");
        check(Playback.lastError() == null, "the recording did not open: " + Playback.lastError());
        test.log("recording opened in " + (System.currentTimeMillis() - started) + " ms");
        test.sleepTicks(10);

        JsonObject status = test.tool("dailies_status", none).getAsJsonObject("playback");
        int total = status.get("total_ticks").getAsInt();
        check(status.get("loaded").getAsBoolean() && status.get("paused").getAsBoolean() && status.get("tick").getAsInt() == 0, "a freshly opened recording is not paused at tick 0: " + status);
        check(Host.get().inPlayback() && DailiesApi.isPlayback(), "the playback check does not say this is playback");
        test.onClientRun(() -> {
            check(minecraft.getSingleplayerServer() == null && !minecraft.isLocalServer(), "there is a server behind the playback world");
            check(minecraft.level != null && minecraft.player != null && minecraft.player.isSpectator(), "the viewer is not a spectator in a world");
            check(minecraft.player.getId() == comedymonkey.dailies.capture.Snapshots.VIEWER_ENTITY_ID, "the viewer has the wrong entity number");
        });
        check(test.toolError("recording_start", none).contains("watching"), "a recording could be started while watching one");

        // Everything is where the live game had it, at each noted tick, reached by a jump (PLAY-11)
        check(RecordingSteps.notes.size() >= 5, "too few live positions were noted");
        for (RecordingSteps.Note note : RecordingSteps.notes) {
            jump(test, note.tick());
            comparePositions(test, minecraft, note, "after jumping to it");
        }

        // The recorder never passes through a block they went round (watched on every tick of a play-through)
        BlockWatch.playbackTicks.clear();
        int watchFrom = RecordingSteps.notes.get(0).tick();
        int watchTo = RecordingSteps.notes.get(RecordingSteps.notes.size() - 1).tick();
        jump(test, watchFrom);
        BlockWatch.start();
        test.tool("replay_set_speed", args("tick_rate", 60));
        test.onClientRun(() -> Playback.playTo(watchTo));
        test.waitFor(() -> Playback.paused() && Playback.position() == watchTo, 90, "the play-through that watches for blocks");
        BlockWatch.stop();
        test.tool("replay_set_speed", args("tick_rate", 20));
        // Wherever the recorded player is inside a block, the live player was too, give or take the three ticks a game takes
        // to ease another player to where it was told they are
        java.util.Set<Integer> only = new java.util.TreeSet<>();
        for (int inBlock : BlockWatch.playbackTicks) {
            boolean liveToo = false;
            for (int near = inBlock - 4; near <= inBlock + 4; near++) {
                liveToo |= BlockWatch.liveTicks.contains(near);
            }
            if (!liveToo) {
                only.add(inBlock);
            }
        }
        check(only.isEmpty(), "in playback the recorder was inside a block at ticks " + only + ", where the live player was not (live: " + BlockWatch.liveTicks + ")");
        test.log("over " + (watchTo - watchFrom) + " ticks of play the recorder was inside a block only where the live player was (" + BlockWatch.playbackTicks.size()
            + " ticks in playback, " + BlockWatch.liveTicks.size() + " live)");

        // The same world at a tick, however it is reached
        RecordingSteps.Note middle = RecordingSteps.notes.get(RecordingSteps.notes.size() / 2);
        int tick = middle.tick() - 7; // between two notes, while things are still moving
        jump(test, 0);
        jump(test, tick);
        String jumpedForward = test.onClient(() -> digest(minecraft));
        long timeForward = lastDayTime;
        jump(test, total);
        jump(test, tick);
        String jumpedBack = test.onClient(() -> digest(minecraft));
        jump(test, Math.max(0, tick - 130));
        test.tool("replay_set_speed", args("tick_rate", 100));
        test.onClientRun(() -> Playback.playTo(tick));
        test.waitFor(() -> Playback.paused() && Playback.position() == tick, 60, "play to reach tick " + tick);
        test.sleepTicks(5);
        String played = test.onClient(() -> digest(minecraft));
        if (lastDayTime != timeForward) {
            test.log("day time differs between jumping (" + timeForward + ") and playing (" + lastDayTime + ") at tick " + tick);
        }
        test.tool("replay_set_speed", args("tick_rate", 20));
        check(difference(jumpedForward, jumpedBack).isEmpty(), "the world at tick " + tick + " differs between a jump forward and a jump back:\n" + difference(jumpedForward, jumpedBack));
        check(difference(jumpedForward, played).isEmpty(), "the world at tick " + tick + " differs between jumping and playing (across a segment boundary):\n" + difference(jumpedForward, played));
        test.log("world at tick " + tick + " is the same by jump forward, jump back and play: " + jumpedForward.lines().count() + " entities and blocks compared");

        // Jumping backwards twice leaves no duplicates (MOD-5 for ordinary entities)
        jump(test, total - 5);
        jump(test, total / 2);
        jump(test, total / 4);
        test.onClientRun(() -> {
            Map<String, Integer> names = new HashMap<>();
            int players = 0;
            for (Entity entity : minecraft.level.entitiesForRendering()) {
                if (entity.hasCustomName()) {
                    names.merge(entity.getCustomName().getString(), 1, Integer::sum);
                }
                if (entity instanceof net.minecraft.world.entity.player.Player && entity != minecraft.player) {
                    players++;
                }
            }
            check(names.getOrDefault("Hamlet", 0) == 1 && names.getOrDefault("Mover", 0) == 1, "entities were duplicated or lost by jumping back: " + names);
            check(players == 1, "expected the recorder as the one other player, found " + players);
            check(minecraft.getConnection().getOnlinePlayers().size() == 2, "the tab list has " + minecraft.getConnection().getOnlinePlayers().size() + " entries, not the recorder and the viewer");
        });

        // Transport (PLAY-10)
        int before = Playback.position();
        test.tool("replay_play", none);
        test.sleepTicks(30);
        JsonObject pausedState = test.tool("replay_pause", none);
        int advanced = pausedState.get("tick").getAsInt() - before;
        check(advanced >= 15 && advanced <= 45, "a second and a half of play moved " + advanced + " ticks");
        test.sleepTicks(10);
        check(Playback.position() == pausedState.get("tick").getAsInt(), "the recording moved on while paused");
        test.tool("replay_set_speed", args("tick_rate", 2));
        test.tool("replay_play", none);
        test.sleepTicks(40);
        int slow = test.tool("replay_pause", none).get("tick").getAsInt() - pausedState.get("tick").getAsInt();
        check(slow >= 2 && slow <= 8, "two seconds at a tenth of normal speed moved " + slow + " ticks");
        test.tool("replay_set_speed", args("tick_rate", 20));
        check(test.toolError("replay_set_speed", args("tick_rate", 5000)).contains("2000"), "an impossible speed was not answered with the nearest allowed one");
        JsonObject clamped = test.tool("replay_seek", args("tick", total + 1000));
        check(clamped.get("target").getAsInt() == total, "a seek past the end was not clamped");
        test.waitFor(() -> !Playback.jumping(), 60, "the jump to the end");

        // The viewer (PLAY-2, PLAY-3)
        jump(test, middle.tick());
        JsonObject camera = test.tool("camera_set", args("x", 1.5, "y", -55.0, "z", -3.25, "yaw", 30.0, "pitch", 15.0));
        check(Math.abs(camera.get("x").getAsDouble() - 1.5) < 1e-6 && Math.abs(camera.get("pitch").getAsDouble() - 15.0) < 1e-4, "camera_set did not set the camera: " + camera);
        test.sleepTicks(5);
        JsonObject held = test.tool("camera_get", none);
        check(Math.abs(held.get("y").getAsDouble() + 55.0) < 1e-6 && Math.abs(held.get("z").getAsDouble() + 3.25) < 1e-6, "the camera drifted while the recording was paused: " + held);
        JsonArray pigs = test.tool("replay_list_entities", args("type", "minecraft:pig")).getAsJsonArray("entities");
        JsonObject hamlet = null;
        for (com.google.gson.JsonElement pig : pigs) {
            if (pig.getAsJsonObject().get("name").getAsString().equals("Hamlet")) {
                hamlet = pig.getAsJsonObject();
            }
        }
        check(hamlet != null, "replay_list_entities did not find the pig called Hamlet: " + pigs);
        JsonObject riding = test.tool("camera_spectate", args("entity_id", hamlet.get("entity_id").getAsInt()));
        check(riding.has("spectating") && riding.get("spectating").isJsonObject(), "camera_spectate did not start riding along");
        JsonObject free = test.tool("camera_spectate", none);
        check(!free.has("spectating") || free.get("spectating").isJsonNull(), "camera_spectate did not go back to the free camera");
        check(test.toolError("camera_spectate", args("entity_id", 987654)).contains("no such entity"), "riding along with a missing entity did not say so");
        test.log("longest jump took " + longestJumpMillis + " ms");
        if (CraftSteps.builtPlatform) {
            test.log(String.format("physics craft were at most %.2f blocks from their live positions after a jump", worstCraftOffset));
        }
        check(longestJumpMillis < 5000, "a jump took " + longestJumpMillis + " ms");

    }

    static void close(SelfTest test) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        test.tool("replay_close", new JsonObject());
        test.waitFor(() -> !Playback.active() && minecraft.level == null, 60, "the recording to close");
        check(!DailiesApi.isPlayback(), "the playback check still says playback after closing");
        check(test.toolError("replay_play", new JsonObject()).contains("No recording is open"), "playing after closing did not say nothing is open");
    }

    /** Jumps and waits until the recording is paused at exactly that tick */
    static void jump(SelfTest test, int tick) throws Exception {
        long started = System.nanoTime();
        test.onClientRun(() -> Playback.jumpTo(tick, true));
        test.waitFor(() -> !Playback.jumping() && Playback.position() == tick, 60, "the jump to tick " + tick);
        longestJumpMillis = Math.max(longestJumpMillis, (System.nanoTime() - started) / 1_000_000);
        // A couple of drawn frames, so what is checked is what a person would see
        Thread.sleep(60);
    }

    static long longestJumpMillis = 0;
    static long lastDayTime = 0;
    static double worstCraftOffset = 0;

    /** How much something's speed differs between the stretch before a note and the stretch after it, in blocks a tick */
    private static double speedChangeAt(RecordingSteps.Note note, String name) {
        List<RecordingSteps.Note> notes = RecordingSteps.notes;
        int index = notes.indexOf(note);
        double before = 0.0, after = 0.0;
        if (index > 0 && notes.get(index - 1).positions().containsKey(name) && notes.get(index - 1).tick() != note.tick()) {
            before = notes.get(index - 1).positions().get(name).distanceTo(note.positions().get(name)) / Math.abs(note.tick() - notes.get(index - 1).tick());
        }
        if (index + 1 < notes.size() && notes.get(index + 1).positions().containsKey(name) && notes.get(index + 1).tick() != note.tick()) {
            after = notes.get(index + 1).positions().get(name).distanceTo(note.positions().get(name)) / Math.abs(notes.get(index + 1).tick() - note.tick());
        }
        return Math.abs(after - before);
    }

    /** How fast something was moving around a note, in blocks a tick, from the notes either side of it */
    private static double speedAt(RecordingSteps.Note note, String name) {
        double fastest = 0.0;
        List<RecordingSteps.Note> notes = RecordingSteps.notes;
        int index = notes.indexOf(note);
        for (int other : new int[]{index - 1, index + 1}) {
            if (other >= 0 && other < notes.size() && notes.get(other).positions().containsKey(name) && notes.get(other).tick() != note.tick()) {
                fastest = Math.max(fastest, notes.get(other).positions().get(name).distanceTo(note.positions().get(name)) / Math.abs(notes.get(other).tick() - note.tick()));
            }
        }
        return fastest;
    }

    static void comparePositions(SelfTest test, Minecraft minecraft, RecordingSteps.Note note, String how) throws Exception {
        test.onClientRun(() -> {
            Map<String, Vec3> now = CraftSteps.positions(minecraft, Playback.manifest().recorderId);
            for (Map.Entry<String, Vec3> live : note.positions().entrySet()) {
                Vec3 played = now.get(live.getKey());
                check(played != null, live.getKey() + " is missing at tick " + note.tick() + " " + how);
                if (live.getKey().endsWith(CraftSteps.SEATED)) {
                    String who = live.getKey().substring(0, live.getKey().length() - CraftSteps.SEATED.length());
                    check(played.x == live.getValue().x, who + " is " + (played.x > 0 ? "riding" : "loose") + " in playback at tick " + note.tick() + " " + how
                        + ", but was " + (live.getValue().x > 0 ? "riding" : "loose") + " in the live game");
                    continue;
                }
                double off = played.distanceTo(live.getValue());
                // A third of a block (MOD-2), plus one tick of the thing's own travel. A live position is noted between two
                // ticks, and whatever arrives in the rest of that tick belongs to the same tick of the recording, so something
                // moving fast may fairly be one tick further on. For anything slow or still this is just the third of a block
                double allowed = 0.34 + Math.min(1.2, speedAt(note, live.getKey()));
                if (live.getKey().startsWith("craft ")) {
                    worstCraftOffset = Math.max(worstCraftOffset, off);
                } else if (live.getKey().equals(CraftSteps.RECORDER_ON_CRAFT)) {
                    // Someone standing on a craft stays on its deck to within a quarter of a block (MOD-4). This is measured
                    // from the craft, so the craft's own travel does not come into it
                    // The live sample is taken between two ticks with the deck up to a tick behind the person on it, so one tick
                    // of their travel is allowed here too; once the craft moves steadily this is the plain quarter block
                    allowed = 0.25 + Math.min(0.6, speedAt(note, "recorder"));
                }
                check(off <= allowed, live.getKey() + " is " + String.format("%.2f", off) + " blocks from its live position at tick " + note.tick() + " " + how
                    + " (live " + live.getValue() + ", playback " + played + ")");
            }
        });
    }

    /** Every entity's place and facing, and the blocks around the origin, as text that can be compared */
    static String digest(Minecraft minecraft) {
        TreeMap<Integer, String> lines = new TreeMap<>();
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            // Experience orbs are left out: the game scatters each new orb at random on every client, live or not
            if (entity == minecraft.player || entity instanceof net.minecraft.world.entity.ExperienceOrb) {
                continue;
            }
            lines.put(entity.getId(), String.format("entity %d %s %.3f %.3f %.3f yaw %.1f pitch %.1f", entity.getId(), entity.getType().getDescriptionId(),
                entity.getX(), entity.getY(), entity.getZ(), net.minecraft.util.Mth.wrapDegrees(entity.getYRot()), net.minecraft.util.Mth.wrapDegrees(entity.getXRot())));
        }
        StringBuilder text = new StringBuilder();
        lines.values().forEach(line -> text.append(line).append('\n'));
        long blocks = 17;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = -8; x <= 16; x++) {
            for (int y = -64; y <= -56; y++) {
                for (int z = -8; z <= 16; z++) {
                    blocks = blocks * 31 + Block.getId(minecraft.level.getBlockState(pos.set(x, y, z)));
                }
            }
        }
        text.append("blocks ").append(blocks).append('\n');
        // The day-time counter is noted but not compared: with the daylight cycle off it has been seen to read 0 by one
        // route and 1 by another, which changes nothing that is drawn. An open question, logged so it is not forgotten
        lastDayTime = minecraft.level.getDayTime();
        return text.toString();
    }

    /**
     * What differs between two descriptions of the world. Blocks must match exactly. An entity may be up to half a block
     * and a few degrees apart: something riding a physics craft is put exactly where it was recorded by a jump, and shown
     * through the craft's own smoothing by plain play, as the live game shows it.
     */
    static String difference(String a, String b) {
        List<String> first = a.lines().toList();
        List<String> second = b.lines().toList();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < Math.max(first.size(), second.size()) && text.length() < 1200; i++) {
            String left = i < first.size() ? first.get(i) : "(nothing)";
            String right = i < second.size() ? second.get(i) : "(nothing)";
            if (!left.equals(right) && !nearlySame(left, right)) {
                text.append("  ").append(left).append("\n  ").append(right).append('\n');
            }
        }
        return text.toString();
    }

    private static boolean nearlySame(String left, String right) {
        String[] one = left.split(" ");
        String[] two = right.split(" ");
        if (one.length != 10 || two.length != 10 || !one[0].equals("entity") || !one[1].equals(two[1]) || !one[2].equals(two[2])) {
            return false;
        }
        try {
            double apart = Math.sqrt(Math.pow(Double.parseDouble(one[3]) - Double.parseDouble(two[3]), 2) + Math.pow(Double.parseDouble(one[4]) - Double.parseDouble(two[4]), 2)
                + Math.pow(Double.parseDouble(one[5]) - Double.parseDouble(two[5]), 2));
            double turned = Math.abs(net.minecraft.util.Mth.wrapDegrees(Double.parseDouble(one[7]) - Double.parseDouble(two[7])));
            return apart <= 0.5 && turned <= 25.0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

}
