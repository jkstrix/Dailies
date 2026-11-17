package comedymonkey.dailies.selftest;

import com.google.gson.JsonObject;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.compat.sable.SableCompat;
import comedymonkey.dailies.file.RecordingFile;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.selftest.RealWorldSteps.Drive;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static comedymonkey.dailies.selftest.SelfTest.args;
import static comedymonkey.dailies.selftest.SelfTest.check;

/**
 * The camera route (specification FILM-1 and FILM-2): one recording of a player going through a forest, a village, a cave,
 * the Nether, the sky and a few vehicles in a generated world, driven by real movement keys as a person plays. It is
 * recorded once and kept as a fixture outside the recordings folder, so that every camera can be filmed against the
 * same play, run after run. Each place's stretch starts and ends with a marker named after it.
 * <p>
 * Recorded again only when asked for (dailies.test.recordRoute), when the fixture is missing, or when it no longer
 * opens (the format changed).
 */
final class CameraRoute {

    static final String WORLD = "Dailies Camera Route";
    private static final long SEED = 20261117L;
    static final List<String> PLACES = List.of("forest", "buildings", "cave", "nether", "flying", "vehicles");
    /** How long each place is played for, in ticks: a little over the minute each camera films */
    private static final int STRETCH = 70 * 20;
    /** Each vehicle's share of the vehicles stretch */
    private static final int VEHICLE = 18 * 20;

    /** One place's stretch of the route, in recording ticks */
    record Stretch(String place, int start, int end) {
        int ticks() {
            return this.end - this.start;
        }
    }

    record Route(Path file, int totalTicks, List<Stretch> stretches) {
    }

    private CameraRoute() {
    }

    static Path fixture() {
        return Host.get().dataDirectory().resolve("fixtures").resolve("camera-route" + RecordingFile.EXTENSION);
    }

    /** The route, recorded now only if it has to be */
    static Route obtain(SelfTest test) throws Exception {
        Path file = fixture();
        if (Boolean.getBoolean("dailies.test.recordRoute")) {
            test.log("camera route: recording it again, as asked (dailies.test.recordRoute)");
        } else if (!Files.exists(file)) {
            test.log("camera route: there is no fixture at " + file + " yet; recording it");
        } else {
            try {
                Route route = read(file);
                test.log("camera route: reusing the fixture at " + file);
                describe(test, route);
                return route;
            } catch (Exception e) {
                test.log("camera route: the fixture at " + file + " could not be used (" + e + "); recording it again");
            }
        }
        record(test, file);
        Route route = read(file);
        describe(test, route);
        return route;
    }

    private static void describe(SelfTest test, Route route) {
        StringBuilder marks = new StringBuilder();
        for (Stretch stretch : route.stretches()) {
            marks.append(String.format(Locale.ROOT, " %s %d to %d (%.1f s);", stretch.place(), stretch.start(), stretch.end(), stretch.ticks() / 20.0));
        }
        test.log(String.format(Locale.ROOT, "camera route: %d ticks (%.1f min), places:%s", route.totalTicks(), route.totalTicks() / 1200.0, marks));
    }

    /** Opens the fixture and finds its places; fails when it cannot be opened or holds none of them */
    static Route read(Path file) throws Exception {
        try (RecordingFile recording = RecordingFile.open(file)) {
            RecordingManifest manifest = recording.manifest();
            check(manifest.recorderId != null && !manifest.recorderId.isBlank(), "the route names no recorder");
            List<Stretch> stretches = new ArrayList<>();
            for (String place : PLACES) {
                int start = -1;
                int end = -1;
                for (RecordingManifest.Marker marker : manifest.markers) {
                    if (marker.description.equals(place) && start < 0) {
                        start = marker.tick;
                    } else if (marker.description.equals(place + " end") && start >= 0 && marker.tick > start) {
                        end = marker.tick;
                    }
                }
                if (start >= 0 && end > start) {
                    stretches.add(new Stretch(place, start, end));
                }
            }
            check(!stretches.isEmpty(), "the route has none of its places marked");
            return new Route(file, manifest.totalTicks, stretches);
        }
    }

    // ------------------------------------------------------------------ Recording it

    private interface Part {
        void run() throws Exception;
    }

    private static Minecraft minecraft() {
        return Minecraft.getInstance();
    }

    private static int now() {
        return Recorder.get().recordingTick();
    }

    private static void record(SelfTest test, Path file) throws Exception {
        Minecraft minecraft = minecraft();
        if (Playback.active()) {
            test.tool("replay_close", new JsonObject());
            test.waitFor(() -> !Playback.active() && minecraft.level == null, 60, "the recording before the camera route to close");
        } else if (minecraft.level != null) {
            WorldSteps.leaveWorld(test);
        }
        RealWorldSteps.hook();
        WorldSteps.createRealWorld(test, WORLD, SEED);
        test.command("gamerule doDaylightCycle false");
        test.command("gamerule doWeatherCycle false");
        test.command("time set 1000");
        test.command("weather clear");
        test.onClientRun(() -> minecraft.options.autoJump().set(true));
        RealWorldSteps.flying(test, true, 0.05F);
        RealWorldSteps.settle(test);

        // Where everything is, found before the recording so that the looking is not in it
        BlockPos spawn = RealWorldSteps.playerBlock(test);
        BlockPos forest = RealWorldSteps.biome(test, spawn, 4800, "dark or birch forest",
            found -> found.is(Biomes.DARK_FOREST) || found.is(Biomes.BIRCH_FOREST) || found.is(Biomes.OLD_GROWTH_BIRCH_FOREST));
        if (forest == null) {
            forest = RealWorldSteps.biome(test, spawn, 4800, "forest", found -> found.is(Biomes.FOREST));
        }
        BlockPos village = RealWorldSteps.structure(test, spawn, BuiltinStructures.VILLAGE_PLAINS, BuiltinStructures.VILLAGE_DESERT, BuiltinStructures.VILLAGE_SAVANNA,
            BuiltinStructures.VILLAGE_SNOWY, BuiltinStructures.VILLAGE_TAIGA);
        BlockPos mineshaft = RealWorldSteps.structure(test, spawn, BuiltinStructures.MINESHAFT, BuiltinStructures.MINESHAFT_MESA);
        BlockPos water = RealWorldSteps.biome(test, spawn, 4800, "river or ocean",
            found -> (found.is(BiomeTags.IS_RIVER) || found.is(BiomeTags.IS_OCEAN)) && !found.is(Biomes.FROZEN_RIVER) && !found.is(Biomes.FROZEN_OCEAN) && !found.is(Biomes.DEEP_FROZEN_OCEAN));
        BlockPos plainsFound = RealWorldSteps.biome(test, spawn, 4800, "open grassland",
            found -> found.is(Biomes.PLAINS) || found.is(Biomes.SUNFLOWER_PLAINS) || found.is(Biomes.SAVANNA) || found.is(Biomes.MEADOW));
        BlockPos plains = plainsFound != null ? plainsFound : spawn;

        JsonObject started = test.tool("recording_start", new JsonObject());
        check(started.get("active").getAsBoolean(), "the camera route's recording did not start");
        test.waitFor(() -> now() >= 5, 30, "the camera route's recording to get going");

        BlockPos forestAt = forest;
        if (forestAt != null) {
            place(test, "forest", () -> forestSetup(test, forestAt), () -> forest(test));
        } else {
            test.skip("camera route: no forest within reach of spawn; the forest stretch was not recorded");
        }
        if (village != null) {
            place(test, "buildings", () -> buildingsSetup(test, village), () -> buildings(test, village));
        } else {
            test.skip("camera route: no village within reach of spawn; the buildings stretch was not recorded");
        }
        BlockPos underground = mineshaft != null ? mineshaft : spawn;
        place(test, "cave", () -> caveSetup(test, underground), () -> cave(test));
        place(test, "nether", () -> netherSetup(test, plains), () -> nether(test));
        place(test, "flying", () -> flyingSetup(test, plains), () -> flying(test));
        place(test, "vehicles", () -> boatSetup(test, water, plains), () -> vehicles(test, water, plains));

        RealWorldSteps.ticks(test, 20);
        JsonObject finished = test.tool("recording_finish", args("name", "Camera route"));
        Path saved = Path.of(finished.get("path").getAsString());
        Files.createDirectories(file.getParent());
        // Out of the recordings folder: other steps count the recordings there, and the next run's world clears them
        Files.move(saved, file, StandardCopyOption.REPLACE_EXISTING);
        test.log("camera route: saved as " + file + ", " + Files.size(file) / 1048576 + " MB");
        WorldSteps.leaveWorld(test);
    }

    /**
     * One place: getting there (teleports allowed), then its stretch between two markers, played with the keys. A place
     * that cannot be staged is skipped and the rest still run.
     */
    private static void place(SelfTest test, String name, Part setup, Part play) throws Exception {
        Minecraft minecraft = minecraft();
        boolean marked = false;
        long began = System.currentTimeMillis();
        try {
            setup.run();
            test.tool("recording_add_marker", args("description", name));
            marked = true;
            play.run();
        } catch (Throwable t) {
            Host.LOGGER.error("Dailies self test: the '{}' stretch of the camera route stopped", name, t);
            test.skip("camera route: the " + name + " stretch could not be played to its end: " + t);
        } finally {
            RealWorldSteps.drive = null;
            test.onClientRun(() -> {
                minecraft.options.keyShift.setDown(false);
                minecraft.options.keyLeft.setDown(false);
                minecraft.options.keyRight.setDown(false);
            });
            if (marked) {
                test.tool("recording_add_marker", args("description", name + " end"));
            }
            test.sleepTicks(2);
            test.command("ride @s dismount");
            test.command("gamemode creative");
            test.onClientRun(() -> {
                if (minecraft.screen != null && minecraft.player != null) {
                    minecraft.player.closeContainer();
                    minecraft.setScreen(null);
                }
            });
        }
        test.log("camera route: " + name + " took " + (System.currentTimeMillis() - began) / 1000 + " s, up to tick " + now());
    }

    // ------------------------------------------------------------------ Walking about

    /** Which way to head next, given the way wanted until now */
    private interface Heading {
        float next(float wanted) throws Exception;
    }

    private static final Random RANDOM = new Random(17);

    /**
     * Walks with the keys until the recording reaches a tick or the walk is done, turning smoothly, never more than
     * eight degrees a tick, towards the heading asked for. A player who has not got anywhere for a second and a half
     * turns away.
     */
    private static void walk(SelfTest test, int until, Heading heading, boolean sprint, int jumpEvery, java.util.function.BooleanSupplier done) throws Exception {
        Minecraft minecraft = minecraft();
        float yaw = test.onClient(() -> minecraft.player.getYRot());
        float wanted = yaw;
        int checked = -100;
        Vec3 lastAt = RealWorldSteps.playerAt(test);
        int lastAtTick = now();
        while (now() < until && (done == null || !done.getAsBoolean())) {
            int tick = now();
            if (tick - checked >= 5) {
                wanted = heading.next(wanted);
                checked = tick;
            }
            if (tick - lastAtTick >= 30) {
                Vec3 at = RealWorldSteps.playerAt(test);
                if (at.distanceTo(lastAt) < 0.8) {
                    wanted += 100 + RANDOM.nextInt(80);
                }
                lastAt = at;
                lastAtTick = tick;
            }
            yaw = approach(yaw, wanted, 8.0F);
            boolean jump = jumpEvery > 0 && tick % jumpEvery < 4;
            RealWorldSteps.drive = new Drive(yaw, 0.0F, true, sprint, jump, false, false);
            test.sleepTicks(1);
        }
        RealWorldSteps.drive = null;
    }

    private static float approach(float from, float to, float most) {
        return from + Mth.clamp(Mth.wrapDegrees(to - from), -most, most);
    }

    /** Wanders where there is room ahead, drifting off to one side or the other every few seconds */
    private static Heading wandering(SelfTest test) {
        int[] drift = {0};
        return wanted -> {
            if (++drift[0] % 16 == 0) {
                wanted += (RANDOM.nextBoolean() ? 1 : -1) * (30 + RANDOM.nextInt(40));
            }
            float from = wanted;
            return test.onClient(() -> roomiest(minecraft(), from));
        };
    }

    /** Heads for a place, round whatever is in the way */
    private static Heading towards(SelfTest test, Vec3 target) {
        return wanted -> test.onClient(() -> {
            Vec3 at = minecraft().player.position();
            float straight = RealWorldSteps.yawTowards(at, target.x, target.z);
            double far = Math.hypot(target.x - at.x, target.z - at.z);
            for (float off : new float[]{0, 20, -20, 45, -45, 70, -70}) {
                if (clearance(minecraft(), at, straight + off) >= Math.min(3, (int) Math.ceil(far))) {
                    return straight + off;
                }
            }
            return straight;
        });
    }

    private static void walkTo(SelfTest test, Vec3 target, int most, double near, boolean sprint) throws Exception {
        walk(test, now() + most, towards(test, target), sprint, 0, () -> {
            LocalPlayer player = minecraft().player;
            return player != null && Math.hypot(player.getX() - target.x, player.getZ() - target.z) < near;
        });
    }

    /** Turns on the spot to face a way */
    private static void turnTo(SelfTest test, float yaw) throws Exception {
        Minecraft minecraft = minecraft();
        float now = test.onClient(() -> minecraft.player.getYRot());
        while (Math.abs(Mth.wrapDegrees(yaw - now)) > 1.0F) {
            now = approach(now, yaw, 8.0F);
            RealWorldSteps.drive = new Drive(now, 0.0F, false, false, false, false, false);
            test.sleepTicks(1);
        }
        RealWorldSteps.drive = null;
    }

    /** The heading near the one wanted with the most room ahead; the wanted one when it has room enough */
    private static float roomiest(Minecraft minecraft, float wanted) {
        Vec3 at = minecraft.player.position();
        float best = wanted;
        int most = -1;
        for (float off : new float[]{0, 25, -25, 50, -50, 80, -80, 120, -120, 180}) {
            int room = clearance(minecraft, at, wanted + off);
            if (room >= 5) {
                return wanted + off;
            }
            if (room > most) {
                most = room;
                best = wanted + off;
            }
        }
        return best;
    }

    /**
     * How far a player on foot could go this way, up to eight blocks: a step up is fine, a wall is not, and neither is
     * lava or a drop of five blocks or more.
     */
    private static int clearance(Minecraft minecraft, Vec3 from, float yaw) {
        ClientLevel level = minecraft.level;
        Vec3 ahead = Vec3.directionFromRotation(0.0F, yaw);
        int y = Mth.floor(from.y + 0.01);
        for (int step = 1; step <= 8; step++) {
            BlockPos at = BlockPos.containing(from.x + ahead.x * step, y, from.z + ahead.z * step);
            if (open(level, at) && open(level, at.above())) {
                int drop = 0;
                while (drop < 5 && open(level, at.below(drop + 1))) {
                    drop++;
                }
                if (drop >= 5 || level.getFluidState(at.below(drop + 1)).is(FluidTags.LAVA)) {
                    return step - 1;
                }
                y -= drop;
            } else if (!open(level, at) && open(level, at.above()) && open(level, at.above(2))) {
                y++;
            } else {
                return step - 1;
            }
        }
        return 8;
    }

    private static boolean open(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty() && !state.getFluidState().is(FluidTags.LAVA);
    }

    private static boolean standable(Level level, BlockPos pos) {
        return open(level, pos) && open(level, pos.above()) && !open(level, pos.below());
    }

    // ------------------------------------------------------------------ The forest

    private static void forestSetup(SelfTest test, BlockPos forest) throws Exception {
        RealWorldSteps.flying(test, true, 0.05F);
        int ground = RealWorldSteps.surface(forest.getX(), forest.getZ(), Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
        RealWorldSteps.goTo(test, forest.getX() + 0.5, ground + 1, forest.getZ() + 0.5, 0, 0);
        // Somewhere to stand under the trees, not on a trunk
        BlockPos stand = RealWorldSteps.standNear(test, new BlockPos(forest.getX(), ground, forest.getZ()), 6);
        if (stand != null) {
            RealWorldSteps.goTo(test, stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0, 0);
        }
        RealWorldSteps.flying(test, false, 0.05F);
        RealWorldSteps.ticks(test, 30);
    }

    /** Walking and jumping between the trunks, now sprinting, now not */
    private static void forest(SelfTest test) throws Exception {
        int end = now() + STRETCH;
        Heading heading = wandering(test);
        while (now() < end) {
            walk(test, Math.min(end, now() + 200), heading, true, 50, null);
            walk(test, Math.min(end, now() + 160), heading, false, 0, null);
        }
    }

    // ------------------------------------------------------------------ A village

    private static void buildingsSetup(SelfTest test, BlockPos village) throws Exception {
        RealWorldSteps.flying(test, true, 0.05F);
        int ground = RealWorldSteps.surface(village.getX(), village.getZ(), Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
        RealWorldSteps.goTo(test, village.getX() + 0.5, ground + 1, village.getZ() + 0.5, 0, 0);
        BlockPos stand = RealWorldSteps.standNear(test, new BlockPos(village.getX(), ground, village.getZ()), 6);
        if (stand != null) {
            RealWorldSteps.goTo(test, stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0, 0);
        }
        RealWorldSteps.flying(test, false, 0.05F);
        test.command("item replace entity @s weapon.mainhand with minecraft:air");
        RealWorldSteps.ticks(test, 30);
    }

    /**
     * Wooden doors with open sky on one side and a roof on the other: the doors of houses. Each as the door, a place to
     * stand outside it and one inside, nearest first and then each nearest the one before, so the walk goes round the
     * streets.
     */
    private static List<BlockPos[]> doors(SelfTest test, BlockPos centre) throws Exception {
        List<BlockPos[]> found = test.onClient(() -> {
            ClientLevel level = minecraft().level;
            List<BlockPos[]> doors = new ArrayList<>();
            for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-48, -8, -48), centre.offset(48, 8, 48))) {
                BlockState state = level.getBlockState(pos);
                if (!(state.getBlock() instanceof DoorBlock) || !state.is(BlockTags.WOODEN_DOORS) || state.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
                    continue;
                }
                Direction facing = state.getValue(DoorBlock.FACING);
                BlockPos one = pos.relative(facing, 2);
                BlockPos other = pos.relative(facing.getOpposite(), 2);
                boolean oneSky = level.canSeeSky(one.above(2));
                boolean otherSky = level.canSeeSky(other.above(2));
                if (oneSky != otherSky && standable(level, one) && standable(level, other)) {
                    doors.add(new BlockPos[]{pos.immutable(), oneSky ? one.immutable() : other.immutable(), oneSky ? other.immutable() : one.immutable()});
                }
            }
            return doors;
        });
        List<BlockPos[]> ordered = new ArrayList<>();
        BlockPos from = centre;
        while (!found.isEmpty() && ordered.size() < 8) {
            BlockPos[] nearest = null;
            for (BlockPos[] door : found) {
                if (nearest == null || door[1].distSqr(from) < nearest[1].distSqr(from)) {
                    nearest = door;
                }
            }
            found.remove(nearest);
            ordered.add(nearest);
            from = nearest[1];
        }
        return ordered;
    }

    /** Along the streets from door to door, in at the first door and out again, then round the corners to the others */
    private static void buildings(SelfTest test, BlockPos village) throws Exception {
        Minecraft minecraft = minecraft();
        int end = now() + STRETCH;
        List<BlockPos[]> doors = doors(test, RealWorldSteps.playerBlock(test));
        test.log("camera route: " + doors.size() + " house doors found in the village");
        boolean wentIn = false;
        for (BlockPos[] door : doors) {
            if (now() >= end) {
                break;
            }
            Vec3 outside = Vec3.atBottomCenterOf(door[1]);
            Vec3 inside = Vec3.atBottomCenterOf(door[2]);
            walkTo(test, outside, Math.min(end - now(), 20 * 20), wentIn ? 1.5 : 0.4, false);
            if (wentIn || now() >= end || Math.hypot(RealWorldSteps.playerAt(test).x - outside.x, RealWorldSteps.playerAt(test).z - outside.z) > 1.0) {
                continue;
            }
            // In through the door and out again, as anyone looking round a village does
            turnTo(test, RealWorldSteps.yawTowards(outside, inside.x, inside.z));
            boolean shut = test.onClient(() -> !minecraft.level.getBlockState(door[0]).getValue(DoorBlock.OPEN));
            if (shut) {
                RealWorldSteps.useOn(test, door[0], Direction.UP);
                RealWorldSteps.ticks(test, 6);
            }
            walkTo(test, inside, Math.min(end - now(), 6 * 20), 0.5, false);
            RealWorldSteps.ticks(test, 20);
            turnTo(test, RealWorldSteps.yawTowards(inside, outside.x, outside.z));
            walkTo(test, outside, Math.min(end - now(), 6 * 20), 0.5, false);
            wentIn = true;
        }
        if (!wentIn) {
            test.skip("camera route: no house door in the village could be walked through; the buildings stretch only walks the streets");
        }
        walk(test, end, wandering(test), false, 0, null);
    }

    // ------------------------------------------------------------------ A cave

    private static void caveSetup(SelfTest test, BlockPos near) throws Exception {
        Minecraft minecraft = minecraft();
        test.command("execute in minecraft:overworld run tp @s " + near.getX() + " 200 " + near.getZ());
        RealWorldSteps.flying(test, true, 0.05F);
        int x = near.getX();
        int z = near.getZ();
        RealWorldSteps.goTo(test, x + 0.5, RealWorldSteps.surface(x, z, Heightmap.Types.MOTION_BLOCKING) + 20, z + 0.5, 0, 60);
        // A tunnel: dark, under rock, with the most room along it
        BlockPos spot = test.onClient(() -> {
            ClientLevel level = minecraft.level;
            BlockPos best = null;
            int bestScore = Integer.MIN_VALUE;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int dx = -40; dx <= 40; dx += 2) {
                for (int dz = -40; dz <= 40; dz += 2) {
                    for (int y = -50; y <= 30; y++) {
                        pos.set(x + dx, y, z + dz);
                        if (!standable(level, pos) || level.getBrightness(LightLayer.SKY, pos) > 0) {
                            continue;
                        }
                        int room = 0;
                        for (int turn = 0; turn < 8; turn++) {
                            room += clearance(minecraft, Vec3.atBottomCenterOf(pos), turn * 45.0F);
                        }
                        int score = room * 4 - Math.abs(y + 10);
                        if (room >= 18 && score > bestScore) {
                            bestScore = score;
                            best = pos.immutable();
                        }
                    }
                }
            }
            return best;
        });
        check(spot != null, "no tunnel was found under " + near.toShortString());
        int top = RealWorldSteps.surface(spot.getX(), spot.getZ(), Heightmap.Types.MOTION_BLOCKING);
        BlockState surfaceBlock = RealWorldSteps.blockAt(test, new BlockPos(spot.getX(), top - 1, spot.getZ()));
        test.command("item replace entity @s weapon.mainhand with minecraft:torch 64");
        if (top - spot.getY() >= 8 && top - spot.getY() <= 70 && surfaceBlock.getFluidState().isEmpty()) {
            // A shaft down from the surface, as if dug, for the player to drop down into the cave
            RealWorldSteps.command(test, "fill %d %d %d %d %d %d minecraft:air", spot.getX(), spot.getY(), spot.getZ(), spot.getX() + 1, top + 2, spot.getZ() + 1);
            RealWorldSteps.command(test, "fill %d %d %d %d %d %d minecraft:stone replace minecraft:air", spot.getX() - 2, top - 1, spot.getZ() - 1, spot.getX() - 1, top - 1, spot.getZ() + 2);
            RealWorldSteps.goTo(test, spot.getX() - 1.0, top, spot.getZ() + 1.0, -90, 30);
        } else {
            test.log("camera route: the cave at " + spot.toShortString() + " has no dry ground over it to dig down from; the stretch starts in it");
            RealWorldSteps.goTo(test, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 0, 0);
        }
        RealWorldSteps.flying(test, false, 0.05F);
        RealWorldSteps.ticks(test, 20);
        test.log("camera route: the cave is at " + spot.toShortString() + ", " + (top - spot.getY()) + " blocks under the surface");
    }

    /** Down the shaft, then along the tunnels, lighting the way with a torch every few seconds */
    private static void cave(SelfTest test) throws Exception {
        Minecraft minecraft = minecraft();
        int end = now() + STRETCH;
        // Off the edge and down
        walk(test, now() + 60, wanted -> test.onClient(() -> minecraft.player.getYRot()), false, 0,
            () -> minecraft.player != null && !minecraft.player.onGround());
        test.waitFor(() -> minecraft.player.onGround() || now() >= end, 20, "the drop into the cave");
        Heading heading = wandering(test);
        while (now() < end) {
            walk(test, Math.min(end, now() + 100), heading, false, 0, null);
            BlockPos floor = test.onClient(() -> {
                Vec3 behind = minecraft.player.position().subtract(Vec3.directionFromRotation(0.0F, minecraft.player.getYRot()));
                BlockPos below = BlockPos.containing(behind).below();
                return !open(minecraft.level, below) && minecraft.level.getBlockState(below.above()).isAir() ? below : null;
            });
            if (floor != null) {
                RealWorldSteps.useOn(test, floor, Direction.UP);
            }
        }
    }

    // ------------------------------------------------------------------ The Nether

    private static BlockPos platform(SelfTest test, BlockPos near, int dx, int dz, int width, int depth) throws Exception {
        int x = near.getX() + dx;
        int z = near.getZ() + dz;
        int ground = RealWorldSteps.surface(x, z, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
        RealWorldSteps.command(test, "fill %d %d %d %d %d %d minecraft:air", x - 1, ground, z - 1, x + width, ground + 6, z + depth);
        RealWorldSteps.command(test, "fill %d %d %d %d %d %d minecraft:stone", x - 1, ground - 1, z - 1, x + width, ground - 1, z + depth);
        return new BlockPos(x, ground, z);
    }

    private static void netherSetup(SelfTest test, BlockPos plains) throws Exception {
        Minecraft minecraft = minecraft();
        test.command("execute in minecraft:overworld run tp @s " + plains.getX() + " 200 " + plains.getZ());
        RealWorldSteps.flying(test, true, 0.05F);
        RealWorldSteps.settle(test);
        BlockPos base = platform(test, plains, 0, 0, 10, 12);
        int x = base.getX() + 3;
        int y = base.getY();
        int z = base.getZ() + 2;
        // A frame four wide and five high, its foot in the floor so the way in is level
        RealWorldSteps.command(test, "fill %d %d %d %d %d %d minecraft:obsidian", x, y - 1, z, x + 3, y + 3, z);
        RealWorldSteps.command(test, "fill %d %d %d %d %d %d minecraft:air", x + 1, y, z, x + 2, y + 2, z);
        test.command("item replace entity @s weapon.mainhand with minecraft:flint_and_steel");
        RealWorldSteps.goTo(test, x + 2.0, y, z + 7.5, 180, 20);
        RealWorldSteps.flying(test, false, 0.05F);
        RealWorldSteps.useOn(test, new BlockPos(x + 1, y - 1, z), Direction.UP);
        RealWorldSteps.ticks(test, 10);
        if (!RealWorldSteps.blockAt(test, new BlockPos(x + 1, y, z)).is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL)) {
            RealWorldSteps.command(test, "setblock %d %d %d minecraft:fire", x + 1, y, z);
            RealWorldSteps.ticks(test, 10);
        }
        check(RealWorldSteps.blockAt(test, new BlockPos(x + 1, y, z)).is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL), "the portal did not light");
        test.command("item replace entity @s weapon.mainhand with minecraft:air");
        RealWorldSteps.ticks(test, 20);
    }

    /** Through the portal on foot, then off across the Nether */
    private static void nether(SelfTest test) throws Exception {
        Minecraft minecraft = minecraft();
        int end = now() + STRETCH;
        RealWorldSteps.drive = new Drive(180.0F, 0.0F, true, false, false, false, false);
        test.waitFor(() -> minecraft.level != null && minecraft.level.dimension() == Level.NETHER, 40, "the walk through the portal into the Nether");
        RealWorldSteps.drive = null;
        test.waitFor(() -> minecraft.screen == null && minecraft.player != null, 60, "the Nether to appear");
        RealWorldSteps.flying(test, false, 0.05F);
        RealWorldSteps.ticks(test, 20);
        walk(test, end, wandering(test), false, 0, null);
    }

    // ------------------------------------------------------------------ Flying

    private static void flyingSetup(SelfTest test, BlockPos plains) throws Exception {
        Minecraft minecraft = minecraft();
        int x = plains.getX() + 200;
        int z = plains.getZ() + 200;
        test.command("execute in minecraft:overworld run tp @s " + x + " 220 " + z);
        test.waitFor(() -> minecraft.level != null && minecraft.level.dimension() == Level.OVERWORLD && minecraft.screen == null, 60, "the overworld again");
        RealWorldSteps.flying(test, true, 0.05F);
        int ground = RealWorldSteps.surface(x, z, Heightmap.Types.MOTION_BLOCKING);
        RealWorldSteps.goTo(test, x + 0.5, Math.max(ground, 63) + 60, z + 0.5, 0, 10);
        RealWorldSteps.flying(test, true, 0.05F);
        test.command("item replace entity @s armor.chest with minecraft:elytra");
        test.command("item replace entity @s weapon.mainhand with minecraft:firework_rocket 64");
        RealWorldSteps.ticks(test, 20);
    }

    private static double heightAboveGround(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        int ground = minecraft.level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(player.getX()), Mth.floor(player.getZ()));
        return player.getY() - Math.max(ground, 63);
    }

    /** Half the stretch on an elytra with rockets, turning and diving, and half in creative flight */
    private static void flying(SelfTest test) throws Exception {
        Minecraft minecraft = minecraft();
        int begin = now();
        int end = begin + STRETCH;
        RealWorldSteps.flying(test, false, 0.05F);
        RealWorldSteps.ticks(test, 6);
        boolean gliding = test.onClient(() -> {
            LocalPlayer player = minecraft.player;
            if (player.tryToStartFallFlying()) {
                player.connection.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
                return true;
            }
            return false;
        });
        if (!gliding) {
            test.skip("camera route: the elytra would not open; the flying stretch is creative flight only");
        }
        float yaw = test.onClient(() -> minecraft.player.getYRot());
        float pitch = 10.0F;
        float wantedYaw = yaw;
        int lastRocket = -1000;
        int turn = 0;
        int nextTurn = begin;
        int glideEnd = begin + STRETCH / 2;
        while (gliding && now() < glideEnd) {
            int tick = now();
            int since = tick - begin;
            double height = test.onClient(() -> heightAboveGround(minecraft));
            if (tick >= nextTurn) {
                nextTurn = tick + 100;
                wantedYaw += (turn++ % 2 == 0 ? 75.0F : -60.0F);
            }
            float wantedPitch = 5.0F;
            if (since % 200 >= 110 && since % 200 < 150 && height > 30) {
                // A dive
                wantedPitch = 40.0F;
            } else if (height < 20) {
                wantedPitch = -25.0F;
            }
            yaw = approach(yaw, wantedYaw, 4.0F);
            pitch += Mth.clamp(wantedPitch - pitch, -3.0F, 3.0F);
            RealWorldSteps.drive = new Drive(yaw, pitch, false, false, false, false, false);
            if ((wantedPitch < 10.0F && tick - lastRocket > 60) || (height < 20 && tick - lastRocket > 25)) {
                lastRocket = tick;
                test.onClientRun(() -> minecraft.gameMode.useItem(minecraft.player, InteractionHand.MAIN_HAND));
            }
            test.sleepTicks(1);
        }
        // Then creative flight: the elytra off, fast turns, climbs and dives
        test.command("item replace entity @s armor.chest with minecraft:air");
        RealWorldSteps.ticks(test, 3);
        RealWorldSteps.flying(test, true, 0.08F);
        int creativeBegin = now();
        boolean shiftDown = false;
        nextTurn = creativeBegin;
        while (now() < end) {
            int since = now() - creativeBegin;
            double height = test.onClient(() -> heightAboveGround(minecraft));
            if (now() >= nextTurn) {
                nextTurn = now() + 80;
                wantedYaw += (turn++ % 2 == 0 ? 90.0F : -80.0F);
            }
            boolean climb = (since % 160 >= 40 && since % 160 < 70) || height < 8;
            boolean dive = !climb && since % 160 >= 110 && since % 160 < 140 && height > 15;
            if (dive != shiftDown) {
                shiftDown = dive;
                boolean down = dive;
                test.onClientRun(() -> minecraft.options.keyShift.setDown(down));
            }
            yaw = approach(yaw, wantedYaw, 6.0F);
            pitch += Mth.clamp((dive ? 25.0F : climb ? -20.0F : 0.0F) - pitch, -3.0F, 3.0F);
            RealWorldSteps.drive = new Drive(yaw, pitch, true, true, climb, false, false);
            test.sleepTicks(1);
        }
        test.onClientRun(() -> minecraft.options.keyShift.setDown(false));
        RealWorldSteps.drive = null;
        test.command("item replace entity @s weapon.mainhand with minecraft:air");
    }

    // ------------------------------------------------------------------ Vehicles

    private static void boatSetup(SelfTest test, BlockPos water, BlockPos plains) throws Exception {
        if (water == null) {
            test.skip("camera route: no river or sea within reach; the vehicles stretch has no boat");
            return;
        }
        Minecraft minecraft = minecraft();
        test.command("execute in minecraft:overworld run tp @s " + water.getX() + " 200 " + water.getZ());
        RealWorldSteps.flying(test, true, 0.05F);
        int top = RealWorldSteps.surface(water.getX(), water.getZ(), Heightmap.Types.MOTION_BLOCKING);
        RealWorldSteps.goTo(test, water.getX() + 0.5, top + 1, water.getZ() + 0.5, 0, 10);
        RealWorldSteps.command(test, "summon minecraft:boat %.1f %d %.1f {Type:\"oak\"}", water.getX() + 0.5, top, water.getZ() + 0.5);
        RealWorldSteps.ticks(test, 5);
        test.command("ride @s mount @e[type=minecraft:boat,limit=1,sort=nearest]");
        RealWorldSteps.ticks(test, 10);
        if (!test.onClient(() -> minecraft.player.isPassenger())) {
            test.skip("camera route: the recorder could not get into the boat");
        }
        RealWorldSteps.ticks(test, 20);
    }

    /** A boat, a minecart round a loop of track, a horse at a gallop, and with Create Aeronautics a hot air balloon */
    private static void vehicles(SelfTest test, BlockPos water, BlockPos plains) throws Exception {
        Minecraft minecraft = minecraft();
        // The boat: rowing on, with a turn to the left every few seconds
        if (water != null) {
            int boatEnd = now() + VEHICLE;
            RealWorldSteps.drive = new Drive(Float.NaN, 0.0F, true, false, false, false, false);
            while (now() < boatEnd) {
                boolean left = (now() / 20) % 6 >= 4;
                test.onClientRun(() -> minecraft.options.keyLeft.setDown(left));
                test.sleepTicks(5);
            }
            test.onClientRun(() -> minecraft.options.keyLeft.setDown(false));
            RealWorldSteps.drive = null;
            test.command("ride @s dismount");
            test.command("kill @e[type=minecraft:boat]");
        }

        // The minecart: a loop of rails laid one after another as a player lays them, so each joins the last and the corners turn
        BlockPos base = platform(test, plains, 40, 0, 22, 14);
        int x = base.getX();
        int y = base.getY();
        int z = base.getZ();
        RealWorldSteps.flying(test, true, 0.05F);
        RealWorldSteps.goTo(test, x + 2.5, y + 1, z + 3.5, 0, 30);
        List<int[]> loop = new ArrayList<>();
        for (int i = 0; i <= 19; i++) {
            loop.add(new int[]{x + i, z});
        }
        for (int i = 1; i <= 11; i++) {
            loop.add(new int[]{x + 19, z + i});
        }
        for (int i = 18; i >= 0; i--) {
            loop.add(new int[]{x + i, z + 11});
        }
        for (int i = 10; i >= 1; i--) {
            loop.add(new int[]{x, z + i});
        }
        for (int[] at : loop) {
            boolean straightSide = (at[1] == z || at[1] == z + 11) && (at[0] - x) % 5 == 4 && at[0] > x && at[0] < x + 19;
            if (straightSide) {
                RealWorldSteps.command(test, "setblock %d %d %d minecraft:redstone_block", at[0], y - 1, at[1]);
                RealWorldSteps.command(test, "setblock %d %d %d minecraft:powered_rail", at[0], y, at[1]);
            } else {
                RealWorldSteps.command(test, "setblock %d %d %d minecraft:rail", at[0], y, at[1]);
            }
        }
        RealWorldSteps.command(test, "summon minecraft:minecart %.1f %.1f %.1f", x + 2.5, y + 0.1, z + 0.5);
        RealWorldSteps.ticks(test, 5);
        RealWorldSteps.goTo(test, x + 2.5, y + 0.5, z + 0.5, -90, 10);
        test.command("ride @s mount @e[type=minecraft:minecart,limit=1,sort=nearest]");
        RealWorldSteps.ticks(test, 5);
        test.command("data merge entity @e[type=minecraft:minecart,limit=1,sort=nearest] {Motion:[0.6d,0.0d,0.0d]}");
        if (!test.onClient(() -> minecraft.player.isPassenger())) {
            test.skip("camera route: the recorder could not get into the minecart");
        }
        RealWorldSteps.ticks(test, VEHICLE);
        test.command("ride @s dismount");
        test.command("kill @e[type=minecraft:minecart]");

        // The horse: tame, saddled and quick, ridden at a gallop over open ground
        BlockPos field = platform(test, plains, 40, 30, 2, 2);
        RealWorldSteps.goTo(test, field.getX() + 0.5, field.getY() + 1, field.getZ() + 0.5, 0, 0);
        RealWorldSteps.command(test, "summon minecraft:horse %.1f %d %.1f {Tame:1b,SaddleItem:{id:\"minecraft:saddle\",count:1},Variant:1,"
            + "attributes:[{id:\"minecraft:generic.movement_speed\",base:0.3375},{id:\"minecraft:generic.jump_strength\",base:0.9}]}", field.getX() + 0.5, field.getY(), field.getZ() + 0.5);
        RealWorldSteps.ticks(test, 5);
        test.command("ride @s mount @e[type=minecraft:horse,limit=1,sort=nearest]");
        RealWorldSteps.flying(test, false, 0.05F);
        RealWorldSteps.ticks(test, 5);
        if (!test.onClient(() -> minecraft.player.isPassenger())) {
            test.skip("camera route: the recorder could not get on the horse");
        }
        walk(test, now() + VEHICLE, wandering(test), true, 0, null);
        test.command("ride @s dismount");
        test.command("kill @e[type=minecraft:horse]");

        // The balloon, climbing with the recorder in its basket
        if (Host.get().isModLoaded("sable") && SableCompat.available() && Host.get().isModLoaded("aeronautics")) {
            BlockPos ground = platform(test, plains, 80, 0, 18, 18);
            RealWorldSteps.command(test, "fill %d %d %d %d %d %d minecraft:air", ground.getX() - 1, ground.getY(), ground.getZ() - 1, ground.getX() + 18, ground.getY() + 30, ground.getZ() + 18);
            RealWorldSteps.goTo(test, ground.getX() - 1.5, ground.getY(), ground.getZ() - 1.5, -45, 10);
            RealWorldSteps.flying(test, false, 0.05F);
            try {
                CraftSteps.buildBalloon(test, minecraft, ground.offset(2, 0, 2), false);
            } catch (Throwable t) {
                test.skip("camera route: the hot air balloon could not be flown: " + t);
            }
        } else {
            test.skip("camera route: Sable or Create Aeronautics is not installed; the vehicles stretch has no hot air balloon");
        }
    }

}
