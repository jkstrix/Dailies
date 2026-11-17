package comedymonkey.dailies.selftest;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.compat.sable.SableCompat;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static comedymonkey.dailies.selftest.SelfTest.args;
import static comedymonkey.dailies.selftest.SelfTest.check;

/**
 * The hot air balloon's flight in playback (MOD-2 with a craft that really flies), and what the mod can say about
 * craft through {@link Host.Crafts}: the balloon and everyone in its basket where the live game had them at each
 * noted tick of the climb and the descent, the burner lit and out when it was, the same flight however a tick is
 * reached, and each craft known by an id that stays the same across jumps.
 */
final class BalloonSteps {

    private BalloonSteps() {
    }

    /** With the recording open in playback */
    static void run(SelfTest test) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        if (!CraftSteps.builtPlatform) {
            return;
        }
        crafts(test, minecraft);
        if (!CraftSteps.builtBalloon || CraftSteps.balloon == null) {
            return;
        }
        List<RecordingSteps.Note> notes = CraftSteps.flightNotes;
        check(notes.size() >= 10, "too few moments of the balloon's flight were noted: " + notes.size());

        // Everything where the live game had it, at each noted tick of the flight, reached by a jump
        double worstBalloon = 0.0;
        for (int index = 0; index < notes.size(); index++) {
            RecordingSteps.Note note = notes.get(index);
            PlaybackSteps.jump(test, note.tick());
            int at = index;
            worstBalloon = Math.max(worstBalloon, test.onClient(() -> compare(minecraft, notes, at)));
        }

        // The middle of the climb, however it is reached: by a jump forward, a jump back, and by play
        int lifted = CraftSteps.balloonAssembledTick;
        int climbed = CraftSteps.balloonClimbedTick;
        int burnerOff = CraftSteps.balloonBurnerOffTick;
        int middle = (lifted + climbed * 3) / 4;
        PlaybackSteps.jump(test, 0);
        PlaybackSteps.jump(test, middle);
        double[] forward = test.onClient(() -> flight(minecraft));
        PlaybackSteps.jump(test, Playback.total());
        PlaybackSteps.jump(test, middle);
        double[] back = test.onClient(() -> flight(minecraft));
        PlaybackSteps.jump(test, Math.max(0, middle - 70));
        test.onClientRun(() -> Playback.playTo(middle));
        test.waitFor(() -> Playback.paused() && Playback.position() == middle && !Playback.jumping(), 60, "play to reach tick " + middle + " of the climb");
        test.sleepTicks(3);
        double[] played = test.onClient(() -> flight(minecraft));
        String detail = test.onClient(() -> CraftSteps.burnerDetail(minecraft));
        check(forward != null && back != null && played != null, "the balloon or its burner is missing at tick " + middle + " of its climb" + detail);
        check(forward[0] == 15.0 && back[0] == 15.0 && played[0] == 15.0, "the burner is not lit in the middle of the climb: signal " + forward[0] + " by a jump forward, " + back[0]
            + " by a jump back, " + played[0] + " by play" + detail);
        check(forward[1] > 0.0 && forward[1] == back[1] && forward[1] == played[1],
            "the burner does not know its balloon's size the same each way: " + forward[1] + " by a jump forward, " + back[1] + " by a jump back, " + played[1] + " by play" + detail);
        check(forward[2] > 0.0 && Math.abs(forward[2] - back[2]) < 15.0 && Math.abs(forward[2] - played[2]) < 15.0,
            "the burner does not know how full its balloon is the same each way: " + forward[2] + ", " + back[2] + ", " + played[2] + detail);
        check(Math.abs(forward[3] - back[3]) < 0.3 && Math.abs(forward[3] - played[3]) < 0.3,
            "the balloon is not at the same height in the middle of its climb each way: " + forward[3] + " by a jump forward, " + back[3] + " by a jump back, " + played[3] + " by play");
        double ground = notes.get(0).positions().get("craft " + CraftSteps.balloon).y;
        check(forward[4] - ground > 2.0, "a jump into the middle of the climb did not land the balloon in the air: it is " + (forward[4] - ground) + " blocks above where it stood");

        // A still of it in the air, exported from playback, for a person to look at
        still(test, minecraft);

        // And out once it was turned down
        PlaybackSteps.jump(test, burnerOff + 60);
        double[] down = test.onClient(() -> flight(minecraft));
        check(down != null && down[0] == 0.0, "the burner is still lit sixty ticks after it was turned down: " + (down == null ? "missing" : "signal " + down[0]));
        test.log("balloon flight in playback: " + notes.size() + " noted ticks match the live flight, the balloon at most " + String.format("%.2f", worstBalloon)
            + " blocks from its live place; at tick " + middle + " the burner is lit, its balloon " + (int) forward[1] + " cubic metres and " + String.format("%.0f", forward[2])
            + " full, " + String.format("%.1f", forward[4] - ground) + " blocks up, by jump forward, jump back and play alike; the burner is out at tick " + (burnerOff + 60));
    }

    /** The burner's signal, its balloon's size and filling, the height of the balloon's underside and of its middle */
    private static double[] flight(Minecraft minecraft) {
        double[] burner = CraftSteps.burnerState(minecraft);
        double base = CraftSteps.balloonBase(minecraft);
        if (burner == null || Double.isNaN(base)) {
            return null;
        }
        Object craft = Host.crafts().craftById(minecraft.level, CraftSteps.balloon);
        return new double[]{burner[0], burner[1], burner[2], base, Host.crafts().getSubLevelBounds(craft).getCenter().y};
    }

    /**
     * One noted tick of the flight against playback, as the other craft are checked: a third of a block, plus one
     * tick of the thing's own travel.
     *
     * @return how far the balloon is from its live place
     */
    private static double compare(Minecraft minecraft, List<RecordingSteps.Note> notes, int index) {
        RecordingSteps.Note note = notes.get(index);
        Map<String, Vec3> now = CraftSteps.flightPositions(minecraft, Playback.manifest().recorderId);
        double balloonOff = 0.0;
        for (Map.Entry<String, Vec3> live : note.positions().entrySet()) {
            String name = live.getKey();
            Vec3 played = now.get(name);
            check(played != null, name + " is missing at tick " + note.tick() + " of the balloon's flight" + (name.startsWith("balloon") ? CraftSteps.burnerDetail(minecraft) : ""));
            if (name.endsWith(CraftSteps.SEATED)) {
                check(played.x == live.getValue().x, name.substring(0, name.length() - CraftSteps.SEATED.length()) + " is " + (played.x > 0 ? "riding" : "loose")
                    + " in playback at tick " + note.tick() + " of the balloon's flight, but was " + (live.getValue().x > 0 ? "riding" : "loose") + " in the live game");
                continue;
            }
            double speed = 0.0;
            for (int other : new int[]{index - 1, index + 1}) {
                if (other >= 0 && other < notes.size() && notes.get(other).positions().containsKey(name) && notes.get(other).tick() != note.tick()) {
                    speed = Math.max(speed, notes.get(other).positions().get(name).distanceTo(live.getValue()) / Math.abs(notes.get(other).tick() - note.tick()));
                }
            }
            double off = played.distanceTo(live.getValue());
            double allowed = 0.34 + Math.min(1.2, speed);
            if (name.equals("craft " + CraftSteps.balloon)) {
                balloonOff = off;
            }
            check(off <= allowed, name + " is " + String.format("%.2f", off) + " from where the live game had it at tick " + note.tick() + " of the balloon's flight (live "
                + live.getValue() + ", playback " + played + ")" + (name.startsWith("balloon") ? CraftSteps.burnerDetail(minecraft) : ""));
        }
        return balloonOff;
    }

    // ------------------------------------------------------------------ What Host.Crafts says

    private static void crafts(SelfTest test, Minecraft minecraft) throws Exception {
        // A tick at which every craft that was built is in the world
        int tick = CraftSteps.builtBalloon && CraftSteps.balloonClimbedTick > 0 ? (CraftSteps.balloonAssembledTick + CraftSteps.balloonClimbedTick) / 2
            : RecordingSteps.notes.get(RecordingSteps.notes.size() - 1).tick();
        PlaybackSteps.jump(test, tick);
        Map<UUID, String> first = test.onClient(() -> describe(minecraft));
        int expected = 2 + (CraftSteps.builtBalloon ? 1 : 0);
        // At least: a part that turns on a bearing of a craft (the balloon's propeller) is a craft of its own to Sable
        check(first.size() >= expected, "Host.Crafts lists " + first.size() + " craft at tick " + tick + ", fewer than the " + expected + " that were built: " + first);
        if (CraftSteps.balloon != null) {
            check(first.containsKey(CraftSteps.balloon), "the balloon's id in playback is not the one it had in the live game: " + CraftSteps.balloon + " is not among " + first.keySet());
        }
        // The same craft by the same ids after a jump away and back, which builds the world afresh
        PlaybackSteps.jump(test, Math.max(0, tick - 150));
        PlaybackSteps.jump(test, tick);
        Map<UUID, String> again = test.onClient(() -> describe(minecraft));
        check(again.keySet().equals(first.keySet()), "the ids of the craft changed across a jump: " + first.keySet() + " then " + again.keySet());
        test.onClientRun(() -> {
            List<Object[]> placed = SableCompat.craftPositions(minecraft.level, 1.0F);
            Map<UUID, Vec3> centres = new HashMap<>();
            for (Object[] craft : placed) {
                centres.put((UUID) craft[0], (Vec3) craft[1]);
            }
            for (Host.CraftInfo info : Host.crafts().crafts(minecraft.level)) {
                Object found = Host.crafts().craftById(minecraft.level, info.uniqueId());
                check(found == info.craft(), "craftById does not give the craft that crafts lists under " + info.uniqueId());
                check(info.uniqueId().equals(Host.crafts().craftId(found)), "craftId does not give back the id a craft is listed under: " + info.uniqueId());
                check(info.name() != null, "a craft has no name, not even an empty one");
                AABB bounds = info.bounds();
                check(centres.containsKey(info.uniqueId()) && bounds.inflate(0.01).contains(centres.get(info.uniqueId())), "the bounds of craft " + info.uniqueId() + " do not hold its middle: " + bounds);
                check(bounds.getXsize() > 0.5 && bounds.getXsize() <= 16.0 && bounds.getYsize() > 0.5 && bounds.getYsize() <= 16.0 && bounds.getZsize() > 0.5 && bounds.getZsize() <= 16.0,
                    "the bounds of craft " + info.uniqueId() + " are not the size of anything that was built: " + bounds);
                boolean platform = Math.abs(bounds.getXsize() - 4.0) < 1.0 && Math.abs(bounds.getZsize() - 4.0) < 1.0 && bounds.getYsize() < 3.5;
                boolean balloon = info.uniqueId().equals(CraftSteps.balloon);
                if (balloon) {
                    check(bounds.getXsize() >= 5.0 && bounds.getYsize() >= 7.0 && bounds.getZsize() >= 5.0, "the balloon's bounds do not hold the whole balloon, basket to envelope: " + bounds);
                }
                Vec3 nose = Host.crafts().craftForward(found, 1.0F);
                check(nose != null && Math.abs(nose.length() - 1.0) < 1.0e-3, "craftForward does not give a direction for craft " + info.uniqueId() + ": " + nose);
                // Nothing in this test turns a craft far round: each still points close to the way it was built, towards positive Z
                check(!(platform || balloon) || nose.z > 0.7, "craft " + info.uniqueId() + " points " + nose + ", though it was built pointing towards positive Z and has not been turned");
            }
            check(Host.crafts().craftById(minecraft.level, UUID.randomUUID()) == null, "craftById gave a craft for an id no craft has");
            check(Host.crafts().craftId("not a craft") == null && Host.crafts().craftForward("not a craft", 1.0F) == null, "craftId or craftForward answered for something that is not a craft");
        });
        test.log("Host.Crafts: " + first.size() + " craft, the same ids across a jump, each found again by its id, its bounds round its middle and its nose towards +Z: " + first.values());
    }

    private static Map<UUID, String> describe(Minecraft minecraft) {
        Map<UUID, String> crafts = new java.util.TreeMap<>();
        for (Host.CraftInfo info : Host.crafts().crafts(minecraft.level)) {
            AABB b = info.bounds();
            crafts.put(info.uniqueId(), info.uniqueId().toString().substring(0, 8) + " \"" + info.name() + "\" " + String.format("%.1f x %.1f x %.1f", b.getXsize(), b.getYsize(), b.getZsize()));
        }
        return crafts;
    }

    // ------------------------------------------------------------------ A picture

    private static void still(SelfTest test, Minecraft minecraft) throws Exception {
        Path folder = Host.get().dataDirectory().resolve("balloon");
        Files.createDirectories(folder);
        Path file = folder.resolve("balloon-in-the-air.png");
        Files.deleteIfExists(file);
        AABB bounds = test.onClient(() -> Host.crafts().getSubLevelBounds(Host.crafts().craftById(minecraft.level, CraftSteps.balloon)));
        Vec3 centre = bounds.getCenter();
        // From the south-east and a little below its middle, far enough back to have the ground in the picture too
        Vec3 from = centre.add(11.0, -1.0, 13.0);
        Vec3 look = centre.subtract(from.add(0.0, 1.62, 0.0));
        double yaw = Math.toDegrees(Math.atan2(-look.x, look.z));
        double pitch = Math.toDegrees(-Math.atan2(look.y, Math.sqrt(look.x * look.x + look.z * look.z)));
        test.tool("camera_set", args("x", from.x, "y", from.y, "z", from.z, "yaw", yaw, "pitch", pitch));
        test.sleepTicks(10);
        test.tool("export_screenshot", args("output_path", file.toString(), "width", 1280, "height", 720, "no_gui", true, "overwrite", true));
        test.waitFor(() -> !comedymonkey.dailies.export.Export.active() && Files.exists(file), 60, "the still of the balloon to be written");
        test.waitFor(() -> !Playback.jumping(), 30, "playback to settle after the still");
        check(Files.size(file) > 10_000, "the still of the balloon is nearly empty: " + Files.size(file) + " bytes");
        test.log("a still of the balloon in the air, exported from playback: " + file);
    }

}
