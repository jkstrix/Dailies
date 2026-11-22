package comedymonkey.dailies.selftest;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.Viewer;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static comedymonkey.dailies.selftest.SelfTest.args;
import static comedymonkey.dailies.selftest.SelfTest.check;

/**
 * Supplementaries' and Amendments' placed items in a recording (the owner's ask, 19 November; specification TEST-8, in the
 * supplementaries group beside the compat's display test): things the player places that are not blocks in the game
 * itself, placed as a player places them, by using the item on a block, in survival. With Amendments: a pile of two
 * skulls (a skull used on a skull), a skull with two candles on it (candles used on a skull), a lantern on the side of a
 * block, a flower pot under one and a banner under one. With Supplementaries: a pile of three books, a stick, a blaze rod,
 * gunpowder and two pancakes. Each, its block state and its block entity's data (which skulls, which candles and how many,
 * which books), is noted in the live game at the middle of the recording and at its end, and a picture of them is taken
 * from where the player stands.
 * <p>
 * Then in playback, riding along with the recorder: after playing it from the start to its end without a jump, every
 * placed block must be there in the same place with the same block state and the same block entity data, and the picture
 * from the recorder's eyes must be near the live one (the mean difference of a pixel across the middle of the screen, the
 * way the bestiary compares its pictures); then after a jump back to the middle, the blocks placed by then must be there
 * as they stood then, and the later ones not yet. A placement the live game did not make (a mod's setting turned off) is
 * said in the log and left out; none at all made fails.
 */
final class SupplementariesPlacingSteps {

    /** Mean difference of a pixel, of 255, above which the playback picture is not the live one */
    private static final double MOST_APART = 20.0;
    /** The row the placements are made along, two blocks in front of where the player stands for each */
    private static final int ROW = 6;

    private SupplementariesPlacingSteps() {
    }

    /** One placement: the item and how many, where it is used, how many times, and where the mod's block then is */
    private record Placement(String label, String mod, String item, int count, BlockPos used, Direction face, int uses, BlockPos placed,
                             List<String> before) {
    }

    private static List<Placement> placements(boolean supplementaries, boolean amendments) {
        List<Placement> placements = new ArrayList<>();
        BlockPos below = new BlockPos(0, -61, ROW);
        if (amendments) {
            // A skull put down, then a second used on it: Amendments' skull pile
            placements.add(new Placement("a pile of two skulls", "amendments", "minecraft:skeleton_skull", 2, below.offset(-6, 0, 0), Direction.UP, 2,
                new BlockPos(-6, -60, ROW), List.of()));
            // A skull put down by command beforehand would not be the player's: this one is placed too, then candles used on it
            placements.add(new Placement("a skull with two candles", "amendments", "minecraft:white_candle", 2, new BlockPos(-4, -60, ROW), Direction.UP, 2,
                new BlockPos(-4, -60, ROW), List.of("minecraft:wither_skeleton_skull")));
        }
        if (supplementaries) {
            placements.add(new Placement("a pile of three books", "supplementaries", "minecraft:book", 3, below.offset(-2, 0, 0), Direction.UP, 3,
                new BlockPos(-2, -60, ROW), List.of()));
            placements.add(new Placement("a stick", "supplementaries", "minecraft:stick", 1, below, Direction.UP, 1, new BlockPos(0, -60, ROW), List.of()));
            placements.add(new Placement("a blaze rod", "supplementaries", "minecraft:blaze_rod", 1, below.offset(2, 0, 0), Direction.UP, 1,
                new BlockPos(2, -60, ROW), List.of()));
            placements.add(new Placement("gunpowder", "supplementaries", "minecraft:gunpowder", 1, below.offset(4, 0, 0), Direction.UP, 1,
                new BlockPos(4, -60, ROW), List.of()));
            placements.add(new Placement("two pancakes", "supplementaries", "supplementaries:pancake", 2, below.offset(6, 0, 0), Direction.UP, 2,
                new BlockPos(6, -60, ROW), List.of()));
        }
        if (amendments) {
            // On the north face of a block standing behind the row, under a block above it
            placements.add(new Placement("a lantern on a wall", "amendments", "minecraft:lantern", 1, new BlockPos(-8, -60, ROW + 1), Direction.NORTH, 1,
                new BlockPos(-8, -60, ROW), List.of()));
            placements.add(new Placement("a flower pot hung under a block", "amendments", "minecraft:flower_pot", 1, new BlockPos(8, -58, ROW), Direction.DOWN, 1,
                new BlockPos(8, -59, ROW), List.of()));
            placements.add(new Placement("a banner hung under a block", "amendments", "minecraft:white_banner", 1, new BlockPos(10, -58, ROW), Direction.DOWN, 1,
                new BlockPos(10, -59, ROW), List.of()));
        }
        return placements;
    }

    /** What stood at one place: the block state as a command writes it, and its block entity's data (empty for none) */
    private record Stood(String block, String data) {
    }

    static void run(SelfTest test) throws Exception {
        boolean supplementaries = Host.get().isModLoaded("supplementaries");
        boolean amendments = Host.get().isModLoaded("amendments");
        if (!supplementaries && !amendments) {
            test.skip("Supplementaries and Amendments are not installed: their placed items in recordings were not tried");
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Path folder = Host.get().dataDirectory().resolve("supplementaries");
        Files.createDirectories(folder);
        if (Playback.active()) {
            PlaybackSteps.close(test);
        }
        if (minecraft.level != null) {
            WorldSteps.leaveWorld(test);
        }
        WorldSteps.createWorld(test, "Dailies Supplementaries Placing");
        test.onClientRun(() -> minecraft.options.setCameraType(CameraType.FIRST_PERSON));
        test.command("gamerule sendCommandFeedback false");
        test.command("gamemode survival");
        test.command("effect give @s minecraft:resistance 600 4 true");
        test.command("time set noon");
        test.command("weather clear");
        test.command("item replace entity @s weapon.mainhand with minecraft:air");
        // What the lantern, the pot and the banner hang from
        stage(test, "setblock -8 -60 " + (ROW + 1) + " minecraft:stone");
        stage(test, "setblock 8 -58 " + ROW + " minecraft:stone");
        stage(test, "setblock 10 -58 " + ROW + " minecraft:stone");
        test.command("tp @s 0.5 -60 0.5 0 0");
        test.waitTicks(20);

        List<Placement> placements = placements(supplementaries, amendments);
        int middleAfter = Math.max(1, placements.size() / 2);
        List<Placement> made = new ArrayList<>();
        Map<BlockPos, Stood> atMiddle = new LinkedHashMap<>();
        Map<BlockPos, Stood> atEnd = new LinkedHashMap<>();
        int middle = -1;

        test.tool("recording_start", new JsonObject());
        test.waitTicks(30);
        for (int i = 0; i < placements.size(); i++) {
            Placement placement = placements.get(i);
            place(test, minecraft, placement);
            Stood now = test.onClient(() -> stood(minecraft.level, placement.placed()));
            String namespace = now.block().contains(":") ? now.block().substring(0, now.block().indexOf(':')) : "";
            if (!namespace.equals(placement.mod())) {
                test.log("Supplementaries placing: " + placement.label() + " was not made in the live game (" + now.block() + " at " + placement.placed().toShortString()
                    + "), so it is left out; a setting of " + placement.mod() + "'s may have it off");
            } else {
                made.add(placement);
            }
            if (i + 1 == middleAfter) {
                test.waitTicks(10);
                middle = test.onClient(() -> Recorder.get().recordingTick());
                note(test, minecraft, placements, atMiddle);
            }
        }
        check(!made.isEmpty(), "Supplementaries placing: none of the " + placements.size() + " placements was made in the live game");

        // The picture: from where the player stands, looking down at the row, with nothing in the hand and no HUD
        test.command("item replace entity @s weapon.mainhand with minecraft:air");
        test.command("tp @s 0.5 -60 0.5 0 35");
        test.onClientRun(() -> comedymonkey.dailies.ui.HudHidden.set(true));
        test.waitTicks(20);
        WindowSteps.frames(test, 10);
        Path live = folder.resolve("placed-live.png");
        picture(test, minecraft, live);
        int end = test.onClient(() -> Recorder.get().recordingTick());
        note(test, minecraft, placements, atEnd);
        test.waitTicks(20);
        test.onClientRun(() -> comedymonkey.dailies.ui.HudHidden.set(false));
        JsonObject finished = test.tool("recording_finish", args("name", "Supplementaries placing"));
        Path recording = Path.of(finished.get("path").getAsString());
        WorldSteps.leaveWorld(test);

        test.tool("replay_open", args("path", recording.toString()));
        test.waitFor(() -> Playback.loaded() || Playback.lastError() != null, 120, "the Supplementaries placing recording to open");
        check(Playback.lastError() == null, "the Supplementaries placing recording did not open: " + Playback.lastError());
        List<String> problems = new ArrayList<>();
        double apart;
        try {
            // Played from the start to the end, without a jump, riding along with the recorder
            PlaybackSteps.jump(test, 0);
            ride(test, minecraft);
            int to = end;
            test.onClientRun(() -> Playback.playTo(to));
            test.waitFor(() -> Playback.paused() && Playback.position() == to, 120, "play to reach the end of the placing at tick " + to);
            ride(test, minecraft);
            compare(test, minecraft, made, atEnd, "after playing to the end", problems);
            test.onClientRun(() -> comedymonkey.dailies.ui.HudHidden.set(true));
            WindowSteps.frames(test, 20);
            Path played = folder.resolve("placed-playback.png");
            picture(test, minecraft, played);
            test.onClientRun(() -> comedymonkey.dailies.ui.HudHidden.set(false));
            apart = apart(live, played);
            test.log(String.format("Supplementaries placing: the playback picture is %.2f apart from the live one (mean difference of a pixel, of 255; %s and %s)",
                apart, live.getFileName(), played.getFileName()));
            if (apart < 0 || apart > MOST_APART) {
                problems.add(String.format("the picture of the placed blocks after playing is %.2f apart from the live one, more than %.0f (see %s and %s)", apart,
                    MOST_APART, live, played));
            }

            // Jumped back to the middle: the first half placed, the rest not yet
            WindowSteps.ride(test, minecraft, middle);
            List<Placement> byMiddle = new ArrayList<>();
            for (Placement placement : made) {
                if (placements.indexOf(placement) < middleAfter) {
                    byMiddle.add(placement);
                }
            }
            compare(test, minecraft, byMiddle, atMiddle, "after a jump to the middle", problems);
            for (Placement placement : made) {
                if (placements.indexOf(placement) >= middleAfter) {
                    Stood shown = test.onClient(() -> stood(minecraft.level, placement.placed()));
                    if (!shown.equals(atMiddle.get(placement.placed()))) {
                        problems.add(placement.label() + " after a jump to the middle: before it was placed the live game had " + atMiddle.get(placement.placed())
                            + ", playback shows " + shown);
                    }
                }
            }
        } finally {
            test.onClientRun(() -> {
                comedymonkey.dailies.ui.HudHidden.set(false);
                Viewer.ride(null);
            });
            PlaybackSteps.close(test);
        }
        check(problems.isEmpty(), "Supplementaries placing: " + String.join("; ", problems));
        test.log("Supplementaries placing: " + made.size() + " placed items played back in place, with their block states and data, after playing and after a jump");
    }

    private static void stage(SelfTest test, String command) throws Exception {
        if (!test.commandRan(command)) {
            test.log("Supplementaries placing: a staging command did not run: " + command);
        }
    }

    /** Uses the item as the player does: from two blocks back, turned towards the place by yaw and pitch, with the item in the hand */
    private static void place(SelfTest test, Minecraft minecraft, Placement placement) throws Exception {
        BlockPos at = placement.placed();
        test.command("tp @s " + (at.getX() + 0.5) + " -60 " + (at.getZ() - 1.5) + " 0 40");
        test.waitTicks(3);
        // What has to be there first, put down by the player in the same way
        for (String first : placement.before()) {
            test.command("item replace entity @s weapon.mainhand with " + first);
            test.waitTicks(2);
            use(test, minecraft, at.below(), Direction.UP);
        }
        test.command("item replace entity @s weapon.mainhand with " + placement.item() + " " + placement.count());
        test.waitTicks(2);
        for (int use = 0; use < placement.uses(); use++) {
            // The first use on the block it goes on; the later ones on what was placed, as the player adds to a pile
            BlockPos on = use == 0 ? placement.used() : placement.placed();
            Direction face = use == 0 ? placement.face() : Direction.UP;
            use(test, minecraft, on, face);
        }
        test.waitTicks(5);
    }

    private static void use(SelfTest test, Minecraft minecraft, BlockPos on, Direction face) throws Exception {
        Vec3 hit = Vec3.atCenterOf(on).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        test.onClientRun(() -> minecraft.gameMode.useItemOn(minecraft.player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, on, false)));
        test.waitTicks(6);
    }

    /** Notes what stands at every place a placement goes, made or not */
    private static void note(SelfTest test, Minecraft minecraft, List<Placement> placements, Map<BlockPos, Stood> into) throws Exception {
        for (Placement placement : placements) {
            into.put(placement.placed(), test.onClient(() -> stood(minecraft.level, placement.placed())));
        }
    }

    private static Stood stood(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        BlockEntity entity = level.getBlockEntity(pos);
        String data = "";
        if (entity != null) {
            CompoundTag tag = entity.saveWithoutMetadata(level.registryAccess());
            data = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()) + " " + tag;
        }
        return new Stood(BlockStateParser.serialize(state), data);
    }

    private static void ride(SelfTest test, Minecraft minecraft) throws Exception {
        test.onClientRun(() -> Viewer.ride(minecraft.level.getPlayerByUUID(UUID.fromString(Playback.manifest().recorderId))));
        test.waitFor(() -> Viewer.riding() != null, 10, "the view to ride along with the recorder");
    }

    /** Each placement as playback shows it against what the live game noted */
    private static void compare(SelfTest test, Minecraft minecraft, List<Placement> placements, Map<BlockPos, Stood> noted, String when, List<String> problems)
        throws Exception {
        for (Placement placement : placements) {
            Stood live = noted.get(placement.placed());
            Stood shown = test.onClient(() -> stood(minecraft.level, placement.placed()));
            if (!live.block().equals(shown.block())) {
                problems.add(placement.label() + " " + when + ": the live game had " + live.block() + " at " + placement.placed().toShortString() + ", playback shows "
                    + shown.block());
            } else if (!live.data().equals(shown.data())) {
                problems.add(placement.label() + " " + when + ": its block entity in the live game held " + (live.data().isEmpty() ? "nothing" : live.data())
                    + ", in playback " + (shown.data().isEmpty() ? "nothing" : shown.data()));
            }
        }
    }

    /** A picture of the screen as last drawn, kept for a person to look at too */
    private static void picture(SelfTest test, Minecraft minecraft, Path file) throws Exception {
        test.onClientRun(() -> {
            try (NativeImage image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
                image.writeToFile(file);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    /** The mean difference of a pixel between two pictures, of 255, across the middle of the screen, as the bestiary compares */
    private static double apart(Path one, Path two) throws Exception {
        java.awt.image.BufferedImage a = javax.imageio.ImageIO.read(one.toFile());
        java.awt.image.BufferedImage b = javax.imageio.ImageIO.read(two.toFile());
        if (a == null || b == null) {
            return -1.0;
        }
        long sum = 0;
        int count = 0;
        for (int row = 60; row < 360; row++) {
            for (int column = 0; column < 854; column++) {
                int p = a.getRGB(column * a.getWidth() / 854, row * a.getHeight() / 480);
                int q = b.getRGB(column * b.getWidth() / 854, row * b.getHeight() / 480);
                sum += Math.abs((p >> 16 & 255) - (q >> 16 & 255)) + Math.abs((p >> 8 & 255) - (q >> 8 & 255)) + Math.abs((p & 255) - (q & 255));
                count += 3;
            }
        }
        return (double) sum / count;
    }

}
