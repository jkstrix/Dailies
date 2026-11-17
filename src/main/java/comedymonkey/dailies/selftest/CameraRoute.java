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
    /**
     * The parts of a place that are each filmed from their own start, so every one is in a film: marked "place: part"
     * and "place: part end", and filmed as "place-part"
     */
    static final java.util.Map<String, List<String>> PARTS = java.util.Map.of(
        "flying", List.of("elytra", "creative"),
        "vehicles", List.of("boat", "minecart", "horse", "balloon"));
    /**
     * Goes up whenever the route is played differently, so that a fixture recorded before is recorded again by itself.
     * Kept in the fixture as a marker. 2: house doors walked through, the route kept out of water, parts marked
     */
    static final int VERSION = 2;
    private static final String VERSION_MARKER = "camera route version " + VERSION;
    /** How long each place is played for, in ticks: a little over the minute each camera films */
    private static final int STRETCH = 70 * 20;
    /** Each vehicle's part of the vehicles stretch */
    private static final int VEHICLE = 30 * 20;

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
            check(manifest.markers.stream().anyMatch(marker -> marker.description.equals(VERSION_MARKER)),
                "the route was recorded by an older version of its script (this one is " + VERSION + ")");
            List<Stretch> stretches = new ArrayList<>();
            for (String place : PLACES) {
                List<Stretch> parts = new ArrayList<>();
                for (String part : PARTS.getOrDefault(place, List.of())) {
                    Stretch found = marked(manifest, place + ": " + part, place + "-" + part);
                    if (found != null) {
                        parts.add(found);
                    }
                }
                Stretch whole = marked(manifest, place, place);
                if (!parts.isEmpty()) {
                    stretches.addAll(parts);
                } else if (whole != null) {
                    stretches.add(whole);
                }
            }
            check(!stretches.isEmpty(), "the route has none of its places marked");
            return new Route(file, manifest.totalTicks, stretches);
        }
    }

    /** The stretch between a marker and its end marker, or null */
    private static Stretch marked(RecordingManifest manifest, String marker, String name) {
        int start = -1;
        int end = -1;
        for (RecordingManifest.Marker one : manifest.markers) {
            if (one.description.equals(marker) && start < 0) {
                start = one.tick;
            } else if (one.description.equals(marker + " end") && start >= 0 && one.tick > start && end < 0) {
                end = one.tick;
            }
        }
        return start >= 0 && end > start ? new Stretch(name, start, end) : null;
    }

    /** Marks the start of a part of a place, and its end once it is over however it ends */
    private static void part(SelfTest test, String place, String part, Part play) throws Exception {
        test.tool("recording_add_marker", args("description", place + ": " + part));
        try {
            play.run();
        } finally {
            test.tool("recording_add_marker", args("description", place + ": " + part + " end"));
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
        test.tool("recording_add_marker", args("description", VERSION_MARKER));

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
     * turns away, and one who has got into water anyway heads for the nearest dry ground: every walk is on land.
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
                Float ashore = test.onClient(() -> ashore(minecraft));
                wanted = ashore != null ? ashore : heading.next(wanted);
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

    /**
     * Wanders where there is room ahead, drifting off to one side or the other every few seconds, and turning back
     * towards home when it strays further than the reach given, so a forest walk stays in the forest
     *
     * @param home null to wander anywhere
     */
    private static Heading wandering(SelfTest test, Vec3 home, double reach) {
        int[] drift = {0};
        return wanted -> {
            if (++drift[0] % 16 == 0) {
                wanted += (RANDOM.nextBoolean() ? 1 : -1) * (30 + RANDOM.nextInt(40));
            }
            float from = wanted;
            return test.onClient(() -> {
                Vec3 at = minecraft().player.position();
                float aim = from;
                if (home != null && Math.hypot(at.x - home.x, at.z - home.z) > reach) {
                    aim = RealWorldSteps.yawTowards(at, home.x, home.z);
                }
                return roomiest(minecraft(), aim);
            });
        };
    }

    private static Heading wandering(SelfTest test) {
        return wandering(test, null, 0.0);
    }

    /** Where the player is now, as the home of a wander */
    private static Vec3 here(SelfTest test) throws Exception {
        return RealWorldSteps.playerAt(test);
    }

    /** The way to the nearest dry place to stand when the player is in water; null when they are not. Client thread */
    private static Float ashore(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null || !player.isInWater()) {
            return null;
        }
        BlockPos from = player.blockPosition();
        BlockPos best = null;
        for (BlockPos pos : BlockPos.betweenClosed(from.offset(-16, -3, -16), from.offset(16, 4, 16))) {
            if (standable(minecraft.level, pos) && (best == null || pos.distSqr(from) < best.distSqr(from))) {
                best = pos.immutable();
            }
        }
        return best == null ? null : RealWorldSteps.yawTowards(player.position(), best.getX() + 0.5, best.getZ() + 0.5);
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
     * water, lava or a drop of five blocks or more. The route is meant to be on land wherever it walks.
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
                for (int down = -1; down <= drop + 1; down++) {
                    if (level.getFluidState(at.below(down)).is(FluidTags.WATER)) {
                        return step - 1;
                    }
                }
                y -= drop;
            } else if (!open(level, at) && open(level, at.above()) && open(level, at.above(2)) && !wet(level, at.above()) && !wet(level, at)) {
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

    private static boolean wet(Level level, BlockPos pos) {
        return level.getFluidState(pos).is(FluidTags.WATER);
    }

    /** Two blocks of dry air over solid ground that is not under water */
    private static boolean standable(Level level, BlockPos pos) {
        return open(level, pos) && open(level, pos.above()) && !open(level, pos.below()) && !wet(level, pos) && !wet(level, pos.above()) && !wet(level, pos.below());
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
        Heading heading = wandering(test, here(test), 40.0);
        while (now() < end) {
            walk(test, Math.min(end, now() + 200), heading, true, 50, null);
            walk(test, Math.min(end, now() + 160), heading, false, 0, null);
        }
    }

    // ------------------------------------------------------------------ A village

    /** The village's house doors, found when the player gets there: each as the door, a place outside it and one inside */
    private static List<BlockPos[]> houseDoors = List.of();
    private static Vec3 villageCentre = Vec3.ZERO;

    private static void buildingsSetup(SelfTest test, BlockPos village) throws Exception {
        RealWorldSteps.flying(test, true, 0.05F);
        int ground = RealWorldSteps.surface(village.getX(), village.getZ(), Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
        RealWorldSteps.goTo(test, village.getX() + 0.5, ground + 12, village.getZ() + 0.5, 0, 0);
        villageCentre = new Vec3(village.getX() + 0.5, ground, village.getZ() + 0.5);
        houseDoors = doors(test, new BlockPos(village.getX(), ground, village.getZ()));
        test.log("camera route: " + houseDoors.size() + " house doors found in the village");
        // In the street a few steps from the first house's door, facing it, so the stretch begins with the walk up to it
        BlockPos start = null;
        if (!houseDoors.isEmpty()) {
            BlockPos[] door = houseDoors.get(0);
            BlockPos outside = door[1];
            int dx = Integer.signum(outside.getX() - door[0].getX());
            int dz = Integer.signum(outside.getZ() - door[0].getZ());
            for (int far = 6; far >= 2 && start == null; far--) {
                BlockPos wanted = outside.offset(dx * far, 0, dz * far);
                start = test.onClient(() -> standable(minecraft().level, wanted)) ? wanted : RealWorldSteps.standNear(test, wanted, 2);
            }
        }
        if (start == null) {
            start = RealWorldSteps.standNear(test, new BlockPos(village.getX(), ground, village.getZ()), 6);
        }
        if (start != null) {
            Vec3 at = Vec3.atBottomCenterOf(start);
            float facing = houseDoors.isEmpty() ? 0.0F : RealWorldSteps.yawTowards(at, houseDoors.get(0)[1].getX() + 0.5, houseDoors.get(0)[1].getZ() + 0.5);
            RealWorldSteps.goTo(test, at.x, at.y, at.z, facing, 0);
        }
        RealWorldSteps.flying(test, false, 0.05F);
        test.command("item replace entity @s weapon.mainhand with minecraft:air");
        RealWorldSteps.ticks(test, 30);
    }

    /**
     * Wooden doors with a roof over one side and open sky over the other: the doors of houses. Looked for across the
     * whole village, several houses. Each comes as the door, a place to stand outside it and one inside, one or two
     * blocks off and a step up or down allowed, since village doors often have a step in front; the first is the door
     * nearest the middle of the village, then each the nearest to the one before, so the walk goes round the streets.
     */
    private static List<BlockPos[]> doors(SelfTest test, BlockPos centre) throws Exception {
        List<BlockPos[]> found = test.onClient(() -> {
            ClientLevel level = minecraft().level;
            List<BlockPos[]> doors = new ArrayList<>();
            for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-80, -12, -80), centre.offset(80, 14, 80))) {
                BlockState state = level.getBlockState(pos);
                if (!(state.getBlock() instanceof DoorBlock) || !state.is(BlockTags.WOODEN_DOORS) || state.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
                    continue;
                }
                Direction facing = state.getValue(DoorBlock.FACING);
                int oneCovered = covered(level, pos, facing);
                int otherCovered = covered(level, pos, facing.getOpposite());
                if (Math.abs(oneCovered - otherCovered) < 2) {
                    continue;
                }
                Direction out = oneCovered < otherCovered ? facing : facing.getOpposite();
                BlockPos outside = spotBeside(level, pos, out);
                BlockPos inside = spotBeside(level, pos, out.getOpposite());
                if (outside != null && inside != null) {
                    doors.add(new BlockPos[]{pos.immutable(), outside, inside});
                }
            }
            return doors;
        });
        List<BlockPos[]> ordered = new ArrayList<>();
        BlockPos from = centre;
        while (!found.isEmpty() && ordered.size() < 10) {
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

    /** How many of the three columns beyond a door on one side have something over them: a roof inside, sky outside */
    private static int covered(Level level, BlockPos door, Direction side) {
        int count = 0;
        for (int far = 1; far <= 3; far++) {
            if (!level.canSeeSky(door.relative(side, far).above(2))) {
                count++;
            }
        }
        return count;
    }

    /** A place to stand one or two blocks out from a door on one side, level with it or a step up or down; null when none */
    private static BlockPos spotBeside(Level level, BlockPos door, Direction side) {
        for (int far = 1; far <= 2; far++) {
            for (int up : new int[]{0, -1, 1}) {
                BlockPos spot = door.relative(side, far).above(up);
                if (standable(level, spot)) {
                    return spot.immutable();
                }
            }
        }
        return null;
    }

    private static boolean near(SelfTest test, Vec3 target, double within) throws Exception {
        Vec3 at = RealWorldSteps.playerAt(test);
        return Math.hypot(at.x - target.x, at.z - target.z) < within;
    }

    /**
     * Up to a house's door, opened with a use as a player opens it, in, a look round, then turned about and out again
     *
     * @return whether the player got in and out
     */
    private static boolean throughDoor(SelfTest test, BlockPos[] door, int end) throws Exception {
        Minecraft minecraft = minecraft();
        Vec3 outside = Vec3.atBottomCenterOf(door[1]);
        Vec3 inside = Vec3.atBottomCenterOf(door[2]);
        walkTo(test, outside, Math.min(end - now(), 15 * 20), 0.35, false);
        if (!near(test, outside, 0.9)) {
            test.log("camera route: could not get to the door at " + door[0].toShortString());
            return false;
        }
        turnTo(test, RealWorldSteps.yawTowards(RealWorldSteps.playerAt(test), door[0].getX() + 0.5, door[0].getZ() + 0.5));
        for (int attempt = 0; attempt < 2 && test.onClient(() -> !minecraft.level.getBlockState(door[0]).getValue(DoorBlock.OPEN)); attempt++) {
            // The empty hand used on the door, which is what a right click on it does
            RealWorldSteps.useOn(test, attempt == 0 ? door[0] : door[0].above(), Direction.UP);
            RealWorldSteps.ticks(test, 8);
        }
        if (test.onClient(() -> !minecraft.level.getBlockState(door[0]).getValue(DoorBlock.OPEN))) {
            test.log("camera route: the door at " + door[0].toShortString() + " would not open");
            return false;
        }
        walkTo(test, inside, Math.min(end - now(), 8 * 20), 0.35, false);
        boolean gotIn = near(test, inside, 0.9);
        RealWorldSteps.ticks(test, 25);
        turnTo(test, RealWorldSteps.yawTowards(RealWorldSteps.playerAt(test), outside.x, outside.z));
        walkTo(test, outside, Math.min(end - now(), 8 * 20), 0.35, false);
        // A few steps on into the street, clear of the doorway
        Vec3 away = outside.add(outside.subtract(inside).multiply(1.0, 0.0, 1.0).normalize().scale(3.0));
        walkTo(test, away, Math.min(end - now(), 4 * 20), 0.6, false);
        if (!gotIn) {
            test.log("camera route: the door at " + door[0].toShortString() + " opened but the player did not get inside");
        }
        return gotIn;
    }

    /** Along the streets from house to house, in through a door and out again at each of the first two, then on round the village */
    private static void buildings(SelfTest test, BlockPos village) throws Exception {
        int end = now() + STRETCH;
        int wentIn = 0;
        for (BlockPos[] door : houseDoors) {
            if (now() >= end - 10 * 20) {
                break;
            }
            if (wentIn < 2) {
                if (throughDoor(test, door, end)) {
                    wentIn++;
                    test.log("camera route: in and out through the door at " + door[0].toShortString());
                }
            } else {
                walkTo(test, Vec3.atBottomCenterOf(door[1]), Math.min(end - now(), 12 * 20), 1.5, false);
            }
        }
        if (wentIn == 0) {
            test.skip("camera route: no house door in the village could be walked through; the buildings stretch only walks the streets");
        }
        walk(test, end, wandering(test, villageCentre, 40.0), false, 0, null);
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
        Heading heading = wandering(test, here(test), 40.0);
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
        walk(test, end, wandering(test, here(test), 60.0), false, 0, null);
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
        float[] heading = new float[3];
        part(test, "flying", "elytra", () -> elytra(test, heading));
        part(test, "flying", "creative", () -> creativeFlight(test, heading));
        test.command("item replace entity @s weapon.mainhand with minecraft:air");
    }

    /**
     * On an elytra with rockets, turning and diving, for half the flying stretch
     *
     * @param heading the yaw, pitch and yaw wanted, handed on to the creative flight that follows
     */
    private static void elytra(SelfTest test, float[] heading) throws Exception {
        Minecraft minecraft = minecraft();
        int begin = now();
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
        RealWorldSteps.drive = null;
        heading[0] = yaw;
        heading[1] = pitch;
        heading[2] = wantedYaw;
        // The elytra off, for the creative flight after it
        test.command("item replace entity @s armor.chest with minecraft:air");
        RealWorldSteps.ticks(test, 3);
        RealWorldSteps.flying(test, true, 0.08F);
    }

    /** Creative flight, with fast turns, climbs and dives, for the other half of the flying stretch */
    private static void creativeFlight(SelfTest test, float[] heading) throws Exception {
        Minecraft minecraft = minecraft();
        float yaw = heading[0];
        float pitch = heading[1];
        float wantedYaw = heading[2];
        int turn = 0;
        int creativeBegin = now();
        int end = creativeBegin + STRETCH / 2;
        boolean shiftDown = false;
        int nextTurn = creativeBegin;
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
            part(test, "vehicles", "boat", () -> {
                int boatEnd = now() + VEHICLE;
                RealWorldSteps.drive = new Drive(Float.NaN, 0.0F, true, false, false, false, false);
                while (now() < boatEnd) {
                    boolean left = (now() / 20) % 6 >= 4;
                    test.onClientRun(() -> minecraft.options.keyLeft.setDown(left));
                    test.sleepTicks(5);
                }
                test.onClientRun(() -> minecraft.options.keyLeft.setDown(false));
                RealWorldSteps.drive = null;
            });
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
        if (!test.onClient(() -> minecraft.player.isPassenger())) {
            test.skip("camera route: the recorder could not get into the minecart");
        }
        part(test, "vehicles", "minecart", () -> {
            test.command("data merge entity @e[type=minecraft:minecart,limit=1,sort=nearest] {Motion:[0.6d,0.0d,0.0d]}");
            RealWorldSteps.ticks(test, VEHICLE);
        });
        test.command("ride @s dismount");
        test.command("kill @e[type=minecraft:minecart]");

        // The horse: tame, saddled and quick, ridden at a gallop over open ground
        BlockPos meadow = platform(test, plains, 40, 30, 2, 2);
        RealWorldSteps.goTo(test, meadow.getX() + 0.5, meadow.getY() + 1, meadow.getZ() + 0.5, 0, 0);
        RealWorldSteps.command(test, "summon minecraft:horse %.1f %d %.1f {Tame:1b,SaddleItem:{id:\"minecraft:saddle\",count:1},Variant:1,"
            + "attributes:[{id:\"minecraft:generic.movement_speed\",base:0.3375},{id:\"minecraft:generic.jump_strength\",base:0.9}]}", meadow.getX() + 0.5, meadow.getY(), meadow.getZ() + 0.5);
        RealWorldSteps.ticks(test, 5);
        test.command("ride @s mount @e[type=minecraft:horse,limit=1,sort=nearest]");
        RealWorldSteps.flying(test, false, 0.05F);
        RealWorldSteps.ticks(test, 5);
        if (!test.onClient(() -> minecraft.player.isPassenger())) {
            test.skip("camera route: the recorder could not get on the horse");
        }
        Vec3 field = here(test);
        part(test, "vehicles", "horse", () -> walk(test, now() + VEHICLE, wandering(test, field, 50.0), true, 0, null));
        test.command("ride @s dismount");
        test.command("kill @e[type=minecraft:horse]");

        // The balloon, climbing with the recorder in its basket
        if (Host.get().isModLoaded("sable") && SableCompat.available() && Host.get().isModLoaded("aeronautics")) {
            BlockPos ground = platform(test, plains, 80, 0, 18, 18);
            RealWorldSteps.command(test, "fill %d %d %d %d %d %d minecraft:air", ground.getX() - 1, ground.getY(), ground.getZ() - 1, ground.getX() + 18, ground.getY() + 30, ground.getZ() + 18);
            RealWorldSteps.goTo(test, ground.getX() - 1.5, ground.getY(), ground.getZ() - 1.5, -45, 10);
            RealWorldSteps.flying(test, false, 0.05F);
            part(test, "vehicles", "balloon", () -> {
                try {
                    CraftSteps.buildBalloon(test, minecraft, ground.offset(2, 0, 2), false);
                } catch (Throwable t) {
                    test.skip("camera route: the hot air balloon could not be flown: " + t);
                }
            });
        } else {
            test.skip("camera route: Sable or Create Aeronautics is not installed; the vehicles stretch has no hot air balloon");
        }
    }

}
