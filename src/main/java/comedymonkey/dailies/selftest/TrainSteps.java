package comedymonkey.dailies.selftest;

import com.google.gson.JsonObject;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;

import static comedymonkey.dailies.selftest.BestiarySteps.begin;
import static comedymonkey.dailies.selftest.BestiarySteps.c;
import static comedymonkey.dailies.selftest.BestiarySteps.end;
import static comedymonkey.dailies.selftest.BestiarySteps.item;
import static comedymonkey.dailies.selftest.BestiarySteps.mark;
import static comedymonkey.dailies.selftest.BestiarySteps.present;
import static comedymonkey.dailies.selftest.BestiarySteps.problems;
import static comedymonkey.dailies.selftest.BestiarySteps.still;
import static comedymonkey.dailies.selftest.BestiarySteps.tick;
import static comedymonkey.dailies.selftest.BestiarySteps.waitTicks;
import static comedymonkey.dailies.selftest.BestiarySteps.where;
import static comedymonkey.dailies.selftest.SelfTest.args;

/**
 * Create's trains, in a recording of their own. A straight line of track with a station at each end; a train of two
 * carriages (each two bogeys under a row of railway casing, controls at both ends so that it can run either way) is
 * assembled at the west station before the recording starts, with the recorder sitting in a seat on it, so that the
 * first stored world has to carry the train and its track network. While recording, the train is given a schedule to the
 * east station and back: the recorder rides it east, gets off, and watches it come back from the ground. Playback is
 * compared with the live game by play, by jump and by jump back ({@link CensusPlayback}): the carriages where they were
 * (the census's tolerance for moving things), the recorder in the seat, the train standing at each station; and in
 * play, that the bogeys' wheels turn. The train has no doors. Differences are reported under TRAINS.
 */
final class TrainSteps {

    static final String WORLD = "Dailies Trains";
    private static final String OVERWORLD = BestiarySteps.OVERWORLD;
    private static final String CARRIAGE = "create:carriage_contraption";
    private static final int Y = -60;
    private static final int TRACK_Z = 30;
    private static final BlockPos WEST = new BlockPos(0, Y, TRACK_Z + 1);
    private static final BlockPos EAST = new BlockPos(60, Y, TRACK_Z + 1);
    private static final BlockPos SEAT = new BlockPos(3, Y + 3, TRACK_Z);
    private static final String DRIVER_BOT = "comedymonkeysaddons:helper_bot";
    private static final String DRIVER_BURNER = "create:blaze_burner";
    private static int movingTick = -1;

    private TrainSteps() {
    }

    static void run(SelfTest test) throws Exception {
        if (!Host.get().isModLoaded("create")) {
            test.skip("Create is not installed: trains were not tried");
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (Playback.active()) {
            PlaybackSteps.close(test);
        }
        if (minecraft.level != null) {
            WorldSteps.leaveWorld(test);
        }
        Path folder = Host.get().dataDirectory().resolve("trains");
        Files.createDirectories(folder);
        try (java.util.stream.Stream<Path> old = Files.list(folder)) {
            for (Path file : old.toList()) {
                Files.deleteIfExists(file);
            }
        }
        CensusPlayback.begin(test);
        movingTick = -1;
        try {
            Census.EXTRAS.add(TrainSteps::describe);
            Path recording = folder.resolve("Trains.dailies");
            if (!record(test, minecraft, recording)) {
                return;
            }
            test.skip("the train has no sliding doors: doors in playback were not tried");
            CensusPlayback.compare(test, recording, folder, "TRAINS", "trains", tally -> wheels(test, minecraft, tally));
        } finally {
            CensusPlayback.end();
        }
    }

    // --- Staging ---

    private static boolean record(SelfTest test, Minecraft minecraft, Path recording) throws Exception {
        WorldSteps.createWorld(test, WORLD);
        Census.watch(OVERWORLD, 0, -12, -61, TRACK_Z - 4, 80, -55, TRACK_Z + 7);
        c("gamerule commandModificationBlockLimit 4000000");
        c("gamerule sendCommandFeedback false");
        c("fill -14 -61 " + (TRACK_Z - 5) + " 82 -61 " + (TRACK_Z + 8) + " minecraft:smooth_stone");
        c("fill -12 " + Y + " " + TRACK_Z + " 80 " + Y + " " + TRACK_Z + " create:track[shape=xo]");
        test.sleepTicks(20);
        // Each station looks at the track beside it. A station's direction is the way the train's body lies from it, the
        // train standing there with its front at the station: the west station points east (the train assembles east of
        // it and arrives travelling west), the east station west (the train arrives travelling east, its body behind it).
        // Both pointing east, the train could reach the east station only by passing it and reversing, which Create does
        // not plan: "No suitable path to the next Scheduled destination could be found"
        c("setblock " + WEST.getX() + " " + WEST.getY() + " " + WEST.getZ() + " create:track_station{TargetTrack:[I;0,0,-1],TargetDirection:1b}");
        c("setblock " + EAST.getX() + " " + EAST.getY() + " " + EAST.getZ() + " create:track_station{TargetTrack:[I;0,0,-1],TargetDirection:0b}");
        test.sleepTicks(40);
        test.log("trains: the stations as the server holds them: " + describeStation(minecraft, WEST) + " || " + describeStation(minecraft, EAST));
        name(WEST, "Dailies West");
        name(EAST, "Dailies East");
        // Two carriages, each two bogeys under a row of casing; controls at both ends of the train, each with a driver
        // directly behind it: Create drives a train on a schedule in a direction only when a conductor has controls next
        // to it that way, facing back at it ("Driver has gone missing" otherwise). The drivers are the owner's Helper
        // Bots, who conduct as a Blaze Burner does (no seat: a block behind the controls) and have animations of their
        // own; without the addon, Create's Blaze Burners. The west driver takes it west, the east driver east
        carriage(1, 5);
        carriage(8, 12);
        String driver = Host.get().isModLoaded("comedymonkeysaddons") ? DRIVER_BOT : DRIVER_BURNER;
        c("setblock 2 " + (Y + 3) + " " + TRACK_Z + " " + driver + "[facing=west]");
        c("setblock 11 " + (Y + 3) + " " + TRACK_Z + " " + driver + "[facing=east]");
        // Placed after the drivers: controls set down beside a Helper Bot, facing him, turn him to face them
        c("setblock 1 " + (Y + 3) + " " + TRACK_Z + " create:controls[facing=east]");
        c("setblock 12 " + (Y + 3) + " " + TRACK_Z + " create:controls[facing=west]");
        c("setblock " + SEAT.getX() + " " + SEAT.getY() + " " + SEAT.getZ() + " create:white_seat");
        c("create glue 1 " + (Y + 1) + " " + TRACK_Z + " 5 " + (Y + 3) + " " + TRACK_Z);
        c("create glue 8 " + (Y + 1) + " " + TRACK_Z + " 12 " + (Y + 3) + " " + TRACK_Z);
        // The recorder sits down before the train is assembled, and so is carried aboard
        c("tp @s " + (SEAT.getX() + 0.5) + " " + SEAT.getY() + " " + (SEAT.getZ() + 2.5) + " 180 50");
        test.sleepTicks(10);
        boolean seated = CraftSteps.sit(test, minecraft, SEAT);
        if (!seated) {
            problems.add("the recorder could not take the seat on the train before it was assembled");
        }
        int trains = trainCount(minecraft);
        BestiarySteps.server("the train's assembly mode", (level, player) -> call(level.getBlockEntity(WEST), "enterAssemblyMode", new Class<?>[]{net.minecraft.server.level.ServerPlayer.class}, (Object) null));
        test.sleepTicks(10);
        BestiarySteps.server("the train's assembly", (level, player) -> call(level.getBlockEntity(WEST), "assemble", new Class<?>[]{UUID.class}, player.getUUID()));
        test.sleepTicks(20);
        if (trainCount(minecraft) <= trains) {
            String why = minecraft.getSingleplayerServer().submit(() -> {
                try {
                    return String.valueOf(secret(minecraft.getSingleplayerServer().overworld().getBlockEntity(WEST), "lastException"));
                } catch (Throwable t) {
                    return "unknown (" + t + ")";
                }
            }).get();
            problems.add("the train did not assemble at the west station: " + why + "; the station: " + describeStation(minecraft, WEST));
            BestiarySteps.report("TRAINS", "trains", Host.get().dataDirectory().resolve("trains"), new Census.Tally(), 0, 0, 0);
            return false;
        }
        // Out of assembly mode, as closing the station's screen does: a station still assembling is no destination, and
        // the train could not have been sent home ("No suitable path to the next Scheduled destination could be found")
        BestiarySteps.server("the west station out of assembly mode", (level, player) -> call(level.getBlockEntity(WEST), "exitAssemblyMode", new Class<?>[0]));
        test.sleepTicks(40);
        String drivers = conductors(minecraft);
        test.log("trains: the train's drivers as Create found them: " + drivers);
        if (!drivers.contains("forward=true") || !drivers.contains("backward=true")) {
            problems.add("Create did not find a driver each way on the assembled train (" + drivers + ")");
        }

        // The census compares the recorded HUD as well, so the recording keeps it
        test.tool("config_set", args("key", "recordHud", "value", true));
        test.tool("recording_start", new JsonObject());
        Census.start(Census.Mode.LIVE);
        try {
            test.sleepTicks(20);
            stage(test, "the ride east", () -> rideEast(test, minecraft, seated));
            stage(test, "the train watched from the ground", () -> watchWest(test, minecraft));
            test.sleepTicks(10);
        } finally {
            Census.stop();
            test.tool("config_set", args("key", "recordHud", "value", false));
        }
        JsonObject finished = test.tool("recording_finish", args("name", "Trains"));
        Files.move(Path.of(finished.get("path").getAsString()), recording, StandardCopyOption.REPLACE_EXISTING);
        for (BestiarySteps.Item item : BestiarySteps.items) {
            for (java.util.function.Function<BestiarySteps.Item, String> need : item.needs) {
                String seen;
                try {
                    seen = need.apply(item);
                } catch (Throwable t) {
                    seen = null;
                }
                (seen == null ? item.missing : item.evidence).add(seen == null ? "need " + (item.needs.indexOf(need) + 1) : seen);
            }
        }
        CensusPlayback.explain(test, "trains");
        test.log("trains: recorded " + Census.live.lastKey() + " ticks; " + problems.size() + " staging problems");
        return true;
    }

    private interface Stage {
        void run() throws Exception;
    }

    private static void stage(SelfTest test, String name, Stage stage) throws Exception {
        test.log("trains: staging " + name + " at tick " + tick());
        begin();
        try {
            stage.run();
        } catch (Throwable t) {
            problems.add("the stage '" + name + "' stopped early: " + t);
            Host.LOGGER.warn("Dailies self test: train stage {} stopped early", name, t);
        }
        end();
    }

    /** A station's saved data and whether it has found its place on a track network, for the log */
    private static String describeStation(Minecraft minecraft, BlockPos pos) {
        try {
            return minecraft.getSingleplayerServer().submit(() -> {
                BlockEntity entity = minecraft.getSingleplayerServer().overworld().getBlockEntity(pos);
                if (entity == null) {
                    return pos.toShortString() + " has no station";
                }
                Object station;
                try {
                    station = entity.getClass().getMethod("getStation").invoke(entity);
                } catch (Throwable t) {
                    station = "unreadable " + t;
                }
                String text = String.valueOf(entity.saveWithoutMetadata(minecraft.getSingleplayerServer().registryAccess()));
                return pos.toShortString() + " " + minecraft.getSingleplayerServer().overworld().getBlockState(pos) + " on a network: " + (station != null) + " " + text.substring(0, Math.min(500, text.length()));
            }).get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return "unreadable (" + e + ")";
        }
    }

    /** Whether Create found a driver each way on the train it assembled, for the log and the staging problems */
    private static String conductors(Minecraft minecraft) throws Exception {
        return minecraft.getSingleplayerServer().submit(() -> {
            try {
                Object train = serverTrain(minecraft);
                if (train == null) {
                    return "no train";
                }
                Object forward = train.getClass().getMethod("hasForwardConductor").invoke(train);
                Object backward = train.getClass().getMethod("hasBackwardConductor").invoke(train);
                return "forward=" + forward + " backward=" + backward + " doubleEnded=" + secret(train, "doubleEnded");
            } catch (Throwable t) {
                return "unreadable (" + t + ")";
            }
        }).get(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static void carriage(int from, int to) throws Exception {
        c("setblock " + from + " " + (Y + 1) + " " + TRACK_Z + " create:small_bogey[axis=x]");
        c("setblock " + to + " " + (Y + 1) + " " + TRACK_Z + " create:small_bogey[axis=x]");
        c("fill " + from + " " + (Y + 2) + " " + TRACK_Z + " " + to + " " + (Y + 2) + " " + TRACK_Z + " create:railway_casing");
    }

    /** Names a station, trying again for a few seconds while it is still finding its place on the track */
    private static void name(BlockPos station, String name) throws Exception {
        for (int attempt = 0; attempt < 4; attempt++) {
            int before = problems.size();
            boolean named = BestiarySteps.server("the name of a station", (level, player) -> {
                BlockEntity entity = level.getBlockEntity(station);
                Object done = call(entity, "updateName", new Class<?>[]{String.class}, name);
                if (Boolean.FALSE.equals(done)) {
                    throw new IllegalStateException("the station at " + station.toShortString() + " would not take the name " + name);
                }
            });
            if (named) {
                return;
            }
            if (attempt < 3) {
                problems.remove(before);
                BestiarySteps.test.sleepTicks(20);
            }
        }
    }

    private static void rideEast(SelfTest test, Minecraft minecraft, boolean seated) throws Exception {
        item("The train runs east with the recorder aboard and stops at the station").owns(CARRIAGE, "recorder", "blocks", "block entity create:track_station", "block entity create:track",
                "block entity " + DRIVER_BOT, "block entity " + DRIVER_BURNER)
            .need(present(CARRIAGE, 2))
            .need(where("recorder", "seated on the train", thing -> thing.state().contains("vehicle=")))
            .need(where(CARRIAGE, "standing by the east station", thing -> Math.abs(thing.x() - EAST.getX()) < 10))
            .note("the train was assembled before the recording began: the first stored world carries it, its track network and its stations");
        if (!seated) {
            problems.add("the recorder rides the train standing, not seated");
        }
        schedule(minecraft);
        int at = mark("the train sets off east");
        still("trains-departing", at + 10, 8.5, -56.0, 37.5, 6.5, -58.0, 30.5, false);
        boolean arrived = false;
        for (int part = 0; part < 30 && !arrived; part++) {
            waitTicks(20);
            int now = mark("the train on its way east");
            if (movingTick < 0 && speed(minecraft) > 0.05) {
                movingTick = now;
            }
            arrived = at(minecraft, "Dailies East");
        }
        if (!arrived) {
            problems.add("the train did not reach the east station within thirty seconds");
        }
        waitTicks(10);
        at = mark("the train standing at the east station");
        still("trains-at-east", at, 56.5, -56.0, 37.5, 60.5, -58.0, 30.5, false);
    }

    private static void watchWest(SelfTest test, Minecraft minecraft) throws Exception {
        item("The train runs back west, watched from the ground").owns(CARRIAGE, "recorder", "blocks", "block entity create:track_station", "block entity create:track",
                "block entity " + DRIVER_BOT, "block entity " + DRIVER_BURNER)
            .need(where(CARRIAGE, "having been away east", thing -> Math.abs(thing.x() - EAST.getX()) < 14))
            .need(where(CARRIAGE, "standing by the west station", thing -> Math.abs(thing.x() - WEST.getX()) < 14))
            .need(where("recorder", "on foot", thing -> !thing.state().contains("vehicle=")));
        c("ride @s dismount");
        waitTicks(4);
        c("tp @s 30.5 -60 37.5 180 10");
        boolean arrived = false;
        for (int part = 0; part < 40 && !arrived; part++) {
            waitTicks(20);
            int now = mark("the train on its way west");
            if (part == 5) {
                still("trains-passing", now, 30.5, -58.4, 37.5, 30.5, -58.0, 30.5, true);
            }
            arrived = part > 4 && at(minecraft, "Dailies West");
        }
        if (!arrived) {
            problems.add("the train did not come back to the west station within forty seconds");
        }
        waitTicks(10);
        int at = mark("the train standing at the west station");
        still("trains-at-west", at, 8.5, -56.0, 37.5, 4.5, -58.0, 30.5, false);
    }

    // --- The train, on the server ---

    private static Object serverTrain(Minecraft minecraft) throws Exception {
        Object railways = Class.forName("com.simibubi.create.Create").getField("RAILWAYS").get(null);
        Map<?, ?> trains = (Map<?, ?>) secret(railways, "trains");
        return trains.isEmpty() ? null : trains.values().iterator().next();
    }

    private static int trainCount(Minecraft minecraft) throws Exception {
        return minecraft.getSingleplayerServer().submit(() -> {
            try {
                Object railways = Class.forName("com.simibubi.create.Create").getField("RAILWAYS").get(null);
                return ((Map<?, ?>) secret(railways, "trains")).size();
            } catch (Throwable t) {
                return 0;
            }
        }).get();
    }

    /** To the east station, three seconds there, back to the west station, three seconds there, and round again */
    private static void schedule(Minecraft minecraft) throws Exception {
        String stop = "Conditions:[[{Id:\"create:delay\",Data:{Value:3,TimeUnit:1}}]]";
        CompoundTag tag = TagParser.parseTag("{Cyclic:1b,Entries:[{Instruction:{Id:\"create:destination\",Data:{Text:\"Dailies East\"}}," + stop + "},"
            + "{Instruction:{Id:\"create:destination\",Data:{Text:\"Dailies West\"}}," + stop + "}]}");
        BestiarySteps.server("the train's schedule", (level, player) -> {
            Object train = serverTrain(minecraft);
            if (train == null) {
                throw new IllegalStateException("there is no train");
            }
            Class<?> scheduleClass = Class.forName("com.simibubi.create.content.trains.schedule.Schedule");
            Object schedule = scheduleClass.getMethod("fromTag", net.minecraft.core.HolderLookup.Provider.class, CompoundTag.class).invoke(null, level.registryAccess(), tag);
            Object runtime = secret(train, "runtime");
            runtime.getClass().getMethod("setSchedule", scheduleClass, boolean.class).invoke(runtime, schedule, false);
        });
    }

    private static double speed(Minecraft minecraft) throws Exception {
        return minecraft.getSingleplayerServer().submit(() -> {
            try {
                Object train = serverTrain(minecraft);
                return train == null ? 0.0 : Math.abs(((Number) secret(train, "speed")).doubleValue());
            } catch (Throwable t) {
                return 0.0;
            }
        }).get();
    }

    /** Whether the train is standing at the station of this name */
    private static boolean at(Minecraft minecraft, String station) throws Exception {
        return minecraft.getSingleplayerServer().submit(() -> {
            try {
                Object train = serverTrain(minecraft);
                Object current = train == null ? null : train.getClass().getMethod("getCurrentStation").invoke(train);
                return current != null && station.equals(secret(current, "name"));
            } catch (Throwable t) {
                return false;
            }
        }).get();
    }

    private static Object secret(Object owner, String name) throws ReflectiveOperationException {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException notHere) {
                // Look further up
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object call(Object owner, String name, Class<?>[] types, Object... values) throws ReflectiveOperationException {
        if (owner == null) {
            throw new IllegalStateException("there is no block entity to call " + name + " on");
        }
        Method method = owner.getClass().getMethod(name, types);
        try {
            return method.invoke(owner, values);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // What went wrong inside Create, not the wrapper reflection puts round it
            throw new IllegalStateException(name + " failed: " + e.getCause(), e.getCause());
        }
    }

    // --- In playback ---

    /** How far the first bogey's wheels of the first carriage have turned, as this game shows them; NaN when there is no carriage */
    private static float wheelAngle(Minecraft minecraft) {
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (!CARRIAGE.equals(String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())))) {
                continue;
            }
            try {
                Object carriage = secret(entity, "carriage");
                if (carriage == null) {
                    continue;
                }
                Object bogeys = secret(carriage, "bogeys");
                Object first = bogeys.getClass().getMethod("getFirst").invoke(bogeys);
                Object angle = secret(first, "wheelAngle");
                return ((Number) angle.getClass().getMethod("getValue").invoke(angle)).floatValue();
            } catch (Throwable t) {
                return Float.NaN;
            }
        }
        return Float.NaN;
    }

    /** That the wheels turn in play, after a jump forwards and after a jump back */
    private static void wheels(SelfTest test, Minecraft minecraft, Census.Tally tally) throws Exception {
        if (movingTick < 0) {
            problems.add("the train was never seen moving, so its wheels were not looked at");
            return;
        }
        for (String mode : new String[]{"play after a jump", "play after a jump back"}) {
            PlaybackSteps.jump(test, mode.endsWith("back") ? Math.min(Playback.total(), movingTick + 200) : 0);
            PlaybackSteps.jump(test, Math.max(0, movingTick - 10));
            float before = test.onClient(() -> wheelAngle(minecraft));
            test.onClientRun(() -> Playback.playTo(movingTick + 10));
            test.waitFor(() -> Playback.paused() && Playback.position() >= movingTick + 10, 60, "play to carry the train on");
            float after = test.onClient(() -> wheelAngle(minecraft));
            if (Float.isNaN(before) || Float.isNaN(after)) {
                tally.add(mode, CARRIAGE, "has no bogey wheels to look at", movingTick, "before " + before + ", after " + after, false);
            } else if (Math.abs(net.minecraft.util.Mth.wrapDegrees(after - before)) < 1.0F) {
                tally.add(mode, CARRIAGE, "wheels did not turn while it moved", movingTick, "wheel angle " + before + " then " + after, false);
            } else {
                test.log("trains: in " + mode + " the wheels turned from " + before + " to " + after);
            }
        }
    }

    // --- What the census writes down of a carriage ---

    private static void describe(Entity entity, StringBuilder state, StringBuilder clocks) {
        if (!CARRIAGE.equals(String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())))) {
            return;
        }
        try {
            // Whether this game has found the carriage's train: without it a carriage is not placed on the track at all
            Object carriage = secret(entity, "carriage");
            state.append("train=").append(carriage == null ? "not found" : "found").append(';');
            state.append("pitch=").append(String.format(java.util.Locale.ROOT, "%.0f", entity.getXRot())).append(';');
        } catch (Throwable t) {
            state.append("train=unreadable;");
        }
    }

}
