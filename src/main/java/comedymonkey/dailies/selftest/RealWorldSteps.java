package comedymonkey.dailies.selftest;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.datafixers.util.Pair;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.Audit;
import comedymonkey.dailies.capture.GameCodec;
import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.capture.Snapshots;
import comedymonkey.dailies.export.Export;
import comedymonkey.dailies.extras.Extras;
import comedymonkey.dailies.file.RecordingFile;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.selftest.WorldProbe.Region;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.LidBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static comedymonkey.dailies.selftest.SelfTest.args;
import static comedymonkey.dailies.selftest.SelfTest.check;

/**
 * Recording and playback in generated terrain, as people really play: a default world with its caves, oceans,
 * mountains, structures, mobs, weather and day, the Nether through a real portal, and a stretch of survival. Every
 * other scripted step runs on a flat creative world, which hides most of what is hard about a snapshot.
 * <p>
 * One recording is made of the whole tour. Along it the live game is noted at chosen ticks (see {@link WorldProbe}),
 * always at the very start of a game tick, when everything the recording holds for the tick before has been applied
 * and nothing of the next has; playback is then compared at each of those ticks reached three ways: by a jump
 * forwards, by a jump backwards, and by plain play. Nothing is at fixed coordinates: structures and biomes are looked
 * for when the run starts, so another seed gives another tour and the same checks.
 * <p>
 * A difference does not stop the run. Each is logged as it is found and they are all reported together at the end,
 * because one run of this costs many minutes and should tell everything it can.
 */
final class RealWorldSteps {

    static final String WORLD = "Dailies Real World";
    private static final long SEED = 20261117L;
    private static final int STILL_WIDTH = 960;
    private static final int STILL_HEIGHT = 540;

    private static final List<String> faults = new ArrayList<>();
    private static final List<WorldProbe> notes = new ArrayList<>();
    private static final List<Region> regions = new CopyOnWriteArrayList<>();
    private static final Map<String, Function<Minecraft, String>> askers = new ConcurrentHashMap<>();
    private static final Map<Integer, float[]> hudAtTick = new ConcurrentHashMap<>();
    private static final Queue<Runnable> atTickStart = new ConcurrentLinkedQueue<>();
    private static final List<String> table = new ArrayList<>();
    private static final Map<String, List<Snapshots.Cost>> snapshotCosts = new LinkedHashMap<>();
    /** By still name: the tick it was taken at */
    private static final Map<String, Integer> stills = new LinkedHashMap<>();
    private static final Map<String, long[]> bytesByPhase = new LinkedHashMap<>();

    private static Path folder;
    private static Path recording;
    private static boolean hooked = false;
    private static volatile boolean watching = false;
    private static volatile boolean noteHud = false;
    private static volatile String phase = "start";
    private static volatile Drive drive = null;
    private static boolean driven = false;
    private static Snapshots.Cost lastCost = null;
    private static volatile int frames = 0;
    private static volatile int lastSnapshotAt = 0;
    private static volatile int handChangedAt = -1;
    private static boolean handWasEmpty = true;

    private static Minecraft minecraft() {
        return Minecraft.getInstance();
    }

    /** Keys held down for the player, and where they look, until it is set to null */
    private record Drive(float yaw, float pitch, boolean forward, boolean sprint, boolean jump, boolean attack, boolean use) {
    }

    private RealWorldSteps() {
    }

    /** Fails the run if the tour found differences. For when the tour was run first and the rest of the test after it */
    static void verdict() {
        if (!faults.isEmpty()) {
            check(false, faults.size() + " things differ between the live game and playback in generated terrain; the first: " + faults.get(0));
        }
    }

    static void run(SelfTest test) throws Exception {
        run(test, false);
    }

    /**
     * @param later whether to leave the verdict until {@link #verdict} is asked for it
     */
    static void run(SelfTest test, boolean later) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        if (Playback.active()) {
            test.tool("replay_close", new JsonObject());
            test.waitFor(() -> !Playback.active() && minecraft.level == null, 60, "the recording before the generated world to close");
        } else if (minecraft.level != null) {
            WorldSteps.leaveWorld(test);
        }
        folder = Host.get().dataDirectory().resolve("realworld");
        Files.createDirectories(folder);
        try (Stream<Path> old = Files.list(folder)) {
            for (Path file : old.toList()) {
                Files.deleteIfExists(file);
            }
        }
        hook();
        long started = System.currentTimeMillis();
        WorldSteps.createRealWorld(test, WORLD, SEED);
        test.log("real world: created in " + (System.currentTimeMillis() - started) + " ms");
        watching = true;
        try {
            stage(test);
        } finally {
            watching = false;
            drive = null;
        }
        WorldSteps.leaveWorld(test);
        play(test);
        test.tool("replay_close", new JsonObject());
        test.waitFor(() -> !Playback.active() && minecraft.level == null, 60, "the generated world's recording to close");

        test.log("real world: measurements");
        for (String line : table) {
            test.log("real world: | " + line);
        }
        test.log("real world: " + notes.size() + " live notes compared three ways, " + faults.size() + " differences; in " + leftoverNotes
            + " comparisons playback lacked chunks the live game had kept after the server stopped sending them, which is let off (see WorldProbe)");
        Map<String, Integer> kinds = new java.util.TreeMap<>();
        for (String fault : faults) {
            int at = fault.indexOf(": ", Math.max(0, fault.indexOf(") ")));
            String rest = at < 0 ? fault : fault.substring(at + 2);
            int end = rest.indexOf(':');
            kinds.merge(end < 0 || end > 40 ? "other" : rest.substring(0, end).replaceAll("'[^']*'", "a named entity"), 1, Integer::sum);
        }
        test.log("real world: differences by kind: " + kinds);
        if (!later) {
            verdict();
        }
    }

    private static synchronized void hook() {
        if (hooked) {
            return;
        }
        hooked = true;
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Pre event) -> tickStart());
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Post event) -> frames++);
        Recorder.everyRecordedTick(() -> {
            if (!watching) {
                return;
            }
            float[] hud = Extras.liveHud();
            if (hud != null) {
                hudAtTick.put(Recorder.get().recordingTick(), hud);
            }
            if (minecraft().player != null && minecraft().player.getMainHandItem().isEmpty() != handWasEmpty) {
                handWasEmpty = !handWasEmpty;
                // Seen by the recorder at the end of the tick before this hook's
                handChangedAt = Recorder.get().recordingTick() - 1;
            }
            Snapshots.Cost cost = Snapshots.last;
            if (cost != null && cost != lastCost) {
                lastSnapshotAt = Recorder.get().recordingTick() - 1;
                lastCost = cost;
                snapshotCosts.computeIfAbsent(phase, key -> new ArrayList<>()).add(cost);
            }
        });
    }

    /** The start of every game tick: hold the keys, then do whatever has been left to be done at exactly this moment */
    private static void tickStart() {
        Minecraft minecraft = Minecraft.getInstance();
        Drive held = drive;
        if (minecraft.player != null && !Playback.active()) {
            Options options = minecraft.options;
            if (held != null) {
                options.keyUp.setDown(held.forward);
                options.keyJump.setDown(held.jump);
                options.keyAttack.setDown(held.attack);
                options.keyUse.setDown(held.use);
                minecraft.player.setSprinting(held.sprint && held.forward);
                if (!Float.isNaN(held.yaw)) {
                    minecraft.player.setYRot(held.yaw);
                    minecraft.player.setXRot(held.pitch);
                }
                if (held.attack) {
                    // The game only mines while it believes it has the mouse, which it does not when its window is in the background
                    grabMouse(minecraft, true);
                }
                driven = true;
            } else if (driven) {
                driven = false;
                options.keyUp.setDown(false);
                options.keyJump.setDown(false);
                options.keyAttack.setDown(false);
                options.keyUse.setDown(false);
                minecraft.player.setSprinting(false);
                grabMouse(minecraft, minecraft.isWindowActive() && minecraft.screen == null);
            }
        }
        Runnable task;
        while ((task = atTickStart.poll()) != null) {
            task.run();
        }
    }

    private static void grabMouse(Minecraft minecraft, boolean grabbed) {
        try {
            java.lang.reflect.Field field = minecraft.mouseHandler.getClass().getDeclaredField("mouseGrabbed");
            field.setAccessible(true);
            field.setBoolean(minecraft.mouseHandler, grabbed);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Mining by the keys will not work; the step that needs it says so
        }
    }

    // ------------------------------------------------------------------ Small tools

    private static void fault(SelfTest test, String what) {
        test.log("real world: FAULT " + what);
        if (faults.size() < 5000) {
            faults.add(what);
        }
    }

    private interface Step {
        void run() throws Exception;
    }

    /** One part of the tour. If it cannot be staged the rest still runs: each part goes to a place of its own */
    private static void part(SelfTest test, String name, Step step) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        long size = workingSize();
        int from = Recorder.get().recordingTick();
        phase = name;
        long began = System.currentTimeMillis();
        try {
            step.run();
        } catch (Throwable t) {
            Host.LOGGER.error("Dailies self test: the '{}' part of the generated-world tour stopped", name, t);
            fault(test, "the '" + name + "' part could not be staged to its end: " + t);
        }
        drive = null;
        regions.clear();
        askers.clear();
        noteHud = false;
        test.sleepTicks(2);
        test.onClientRun(() -> {
            if (minecraft.screen != null && minecraft.player != null) {
                if (minecraft.screen instanceof DeathScreen) {
                    minecraft.player.respawn();
                }
                minecraft.player.closeContainer();
                minecraft.setScreen(null);
            }
            minecraft.options.hideGui = false;
        });
        if (minecraft.getSingleplayerServer() != null && minecraft.player != null) {
            test.command("gamemode creative");
            test.command("gamerule randomTickSpeed 3");
        }
        if (Recorder.get().state() == Recorder.State.PAUSED) {
            test.tool("recording_resume", new JsonObject());
        }
        bytesByPhase.put(name, new long[]{workingSize() - size, Recorder.get().recordingTick() - from});
        test.log("real world: part '" + name + "' took " + (System.currentTimeMillis() - began) / 1000 + " s, ticks " + from + " to " + Recorder.get().recordingTick());
    }

    private static long workingSize() {
        try (Stream<Path> files = Files.list(Recorder.folder())) {
            long size = 0;
            for (Path file : files.toList()) {
                if (file.getFileName().toString().endsWith(Recorder.WORKING_EXTENSION)) {
                    size += Files.size(file);
                }
            }
            return size;
        } catch (Exception e) {
            return 0;
        }
    }

    private static void command(SelfTest test, String format, Object... values) throws Exception {
        test.command(String.format(Locale.ROOT, format, values));
    }

    private static <T> T onServer(Callable<T> action) throws Exception {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        check(server != null, "the generated world has no built-in server");
        CompletableFuture<T> done = new CompletableFuture<>();
        server.execute(() -> {
            try {
                done.complete(action.call());
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        return done.get(180, TimeUnit.SECONDS);
    }

    private static ServerLevel serverLevel(IntegratedServer server) {
        return server.getPlayerList().getPlayers().get(0).serverLevel();
    }

    /** The nearest of some structures to a place, in the dimension the player is in; null when none is within reach */
    @SafeVarargs
    private static BlockPos structure(SelfTest test, BlockPos from, ResourceKey<Structure>... kinds) throws Exception {
        long started = System.currentTimeMillis();
        BlockPos found = onServer(() -> {
            ServerLevel level = serverLevel(Minecraft.getInstance().getSingleplayerServer());
            Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
            List<Holder<Structure>> holders = new ArrayList<>();
            for (ResourceKey<Structure> kind : kinds) {
                registry.getHolder(kind).ifPresent(holders::add);
            }
            Pair<BlockPos, Holder<Structure>> nearest = level.getChunkSource().getGenerator().findNearestMapStructure(level, HolderSet.direct(holders), from, 100, false);
            return nearest == null ? null : nearest.getFirst();
        });
        test.log("real world: nearest " + kinds[0].location().getPath() + " to " + from.toShortString() + ": "
            + (found == null ? "none" : found.toShortString() + ", " + (int) Math.sqrt(found.distSqr(new BlockPos(from.getX(), found.getY(), from.getZ()))) + " blocks away")
            + " (looked for " + (System.currentTimeMillis() - started) + " ms)");
        return found;
    }

    private static BlockPos biome(SelfTest test, BlockPos from, int reach, String name, Predicate<Holder<Biome>> wanted) throws Exception {
        long started = System.currentTimeMillis();
        BlockPos found = onServer(() -> {
            ServerLevel level = serverLevel(Minecraft.getInstance().getSingleplayerServer());
            Pair<BlockPos, Holder<Biome>> nearest = level.findClosestBiome3d(wanted, from, reach, 32, 64);
            return nearest == null ? null : nearest.getFirst();
        });
        test.log("real world: nearest " + name + " to " + from.toShortString() + ": "
            + (found == null ? "none" : found.toShortString() + ", " + (int) Math.sqrt(found.distSqr(new BlockPos(from.getX(), found.getY(), from.getZ()))) + " blocks away")
            + " (looked for " + (System.currentTimeMillis() - started) + " ms)");
        return found;
    }

    /** The first free height above the ground at a column, generating the chunk if it has to */
    private static int surface(int x, int z, Heightmap.Types kind) throws Exception {
        return onServer(() -> {
            ServerLevel level = serverLevel(Minecraft.getInstance().getSingleplayerServer());
            level.getChunk(x >> 4, z >> 4);
            return level.getHeight(kind, x, z);
        });
    }

    private static void flying(SelfTest test, boolean on, float speed) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        test.onClientRun(() -> {
            minecraft.player.getAbilities().flying = on && minecraft.player.getAbilities().mayfly;
            minecraft.player.getAbilities().setFlyingSpeed(speed);
            minecraft.player.onUpdateAbilities();
        });
    }

    /** Waits until the chunks have stopped arriving and the one the player is in is there */
    private static void settle(SelfTest test) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        long deadline = System.currentTimeMillis() + 90_000;
        int last = -1;
        int steady = 0;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(250);
            int[] state = test.onClient(() -> {
                if (minecraft.level == null || minecraft.player == null) {
                    return new int[]{0, 0};
                }
                boolean here = minecraft.level.getChunkSource().getChunk(minecraft.player.chunkPosition().x, minecraft.player.chunkPosition().z, ChunkStatus.FULL, false) != null;
                boolean clear = minecraft.screen == null && minecraft.level.isLightUpdateQueueEmpty();
                return new int[]{minecraft.level.getChunkSource().getLoadedChunksCount(), here && clear ? 1 : 0};
            });
            if (state[1] == 1 && state[0] == last && state[0] > 20) {
                // A second and a half without a new chunk when most are there; longer when few are, since generating comes in bursts
                if (++steady >= (state[0] >= 400 ? 6 : 14)) {
                    return;
                }
            } else {
                steady = 0;
            }
            last = state[0];
        }
        test.log("real world: the chunks never stopped arriving; carrying on with " + last);
    }

    private static void goTo(SelfTest test, double x, double y, double z, float yaw, float pitch) throws Exception {
        command(test, "tp @s %.2f %.2f %.2f %.1f %.1f", x, y, z, yaw, pitch);
        test.sleepTicks(10);
        settle(test);
    }

    /** Lets this many ticks of the recording go by */
    private static void ticks(SelfTest test, int count) throws Exception {
        if (Recorder.get().state() != Recorder.State.RECORDING) {
            test.sleepTicks(count);
            return;
        }
        int until = Recorder.get().recordingTick() + count;
        test.waitFor(() -> Recorder.get().recordingTick() >= until || Recorder.get().state() != Recorder.State.RECORDING, count / 20 + 60, count + " ticks of recording to pass");
    }

    private static <T> T atTickStart(Callable<T> action) throws Exception {
        CompletableFuture<T> done = new CompletableFuture<>();
        atTickStart.add(() -> {
            try {
                done.complete(action.call());
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        return done.get(60, TimeUnit.SECONDS);
    }

    /** Notes the live world at the start of the next game tick */
    private static WorldProbe note(SelfTest test, String label) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        WorldProbe probe = atTickStart(() -> {
            int tick = Recorder.get().recordingTick();
            WorldProbe taken = WorldProbe.take(minecraft, null, minecraft.player, tick, phase + ": " + label, new ArrayList<>(regions), new LinkedHashMap<>(askers));
            if (noteHud) {
                taken.hud = hudAtTick.get(tick);
            }
            return taken;
        });
        check(probe.tick >= 0, "a note was taken with no recording running");
        notes.add(probe);
        test.log("real world: noted tick " + probe.tick + " (" + probe.label + ") in " + probe.dimension + ": " + probe.summary() + ", recorder at "
            + String.format(Locale.ROOT, "%.1f %.1f %.1f", probe.recorder.at().x, probe.recorder.at().y, probe.recorder.at().z) + " " + probe.recorder.state()
            + (probe.facts.isEmpty() ? "" : ", " + probe.facts) + " (" + probe.nanos / 1_000_000 + " ms to look)");
        return probe;
    }

    /** A note with a picture of what the live game was showing, to set beside playback at the same tick */
    private static WorldProbe noteWithStill(SelfTest test, String label, String name) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        test.onClientRun(() -> minecraft.options.hideGui = true);
        int before = frames;
        test.waitFor(() -> frames >= before + 3, 10, "frames without the HUD");
        WorldProbe probe = note(test, label);
        int[][] picture = new int[1][];
        int[] size = new int[2];
        test.onClientRun(() -> {
            try (NativeImage image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
                picture[0] = image.makePixelArray();
                size[0] = image.getWidth();
                size[1] = image.getHeight();
            }
            minecraft.options.hideGui = false;
        });
        BufferedImage full = new BufferedImage(size[0], size[1], BufferedImage.TYPE_INT_RGB);
        full.setRGB(0, 0, size[0], size[1], picture[0], 0, size[0]);
        BufferedImage small = new BufferedImage(STILL_WIDTH, STILL_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = small.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(full, 0, 0, STILL_WIDTH, STILL_HEIGHT, null);
        graphics.dispose();
        ImageIO.write(small, "png", folder.resolve(name + "-live.png").toFile());
        stills.put(name, probe.tick);
        return probe;
    }

    private static BlockPos playerBlock(SelfTest test) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        return test.onClient(() -> minecraft.player.blockPosition());
    }

    private static Vec3 playerAt(SelfTest test) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        return test.onClient(() -> minecraft.player.position());
    }

    private static BlockState blockAt(SelfTest test, BlockPos pos) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        return test.onClient(() -> minecraft.level.getBlockState(pos));
    }

    /** Uses the held item on a block's face as a click would */
    private static void useOn(SelfTest test, BlockPos pos, Direction face) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        test.onClientRun(() -> {
            Vec3 middle = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            minecraft.gameMode.useItemOn(minecraft.player, InteractionHand.MAIN_HAND, new BlockHitResult(middle, face, pos, false));
            minecraft.player.swing(InteractionHand.MAIN_HAND);
        });
    }

    /** The nearest block entity of some kinds that the game holds, within a few chunks of a place */
    private static BlockPos nearestBlockEntity(SelfTest test, BlockPos near, int chunks, String... kinds) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        return test.onClient(() -> {
            BlockPos best = null;
            for (int x = (near.getX() >> 4) - chunks; x <= (near.getX() >> 4) + chunks; x++) {
                for (int z = (near.getZ() >> 4) - chunks; z <= (near.getZ() >> 4) + chunks; z++) {
                    LevelChunk chunk = minecraft.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                    if (chunk == null) {
                        continue;
                    }
                    for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                        String kind = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()).getPath();
                        if (List.of(kinds).contains(kind) && (best == null || blockEntity.getBlockPos().distSqr(near) < best.distSqr(near))) {
                            best = blockEntity.getBlockPos();
                        }
                    }
                }
            }
            return best;
        });
    }

    /** Somewhere to stand near a place: two blocks of air over something solid. Null when there is nowhere within reach */
    private static BlockPos standNear(SelfTest test, BlockPos target, int reach) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        return test.onClient(() -> {
            BlockPos best = null;
            for (BlockPos pos : BlockPos.betweenClosed(target.offset(-reach, -reach, -reach), target.offset(reach, reach, reach))) {
                ClientLevel level = minecraft.level;
                if (level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir() && level.getBlockState(pos.below()).isSolidRender(level, pos.below())
                    && (best == null || pos.distSqr(target) < best.distSqr(target)) && pos.distSqr(target) >= 2) {
                    best = pos.immutable();
                }
            }
            return best;
        });
    }

    private static float yawTowards(Vec3 from, double x, double z) {
        return (float) Math.toDegrees(Math.atan2(-(x - from.x), z - from.z));
    }

    private static void uncapped(SelfTest test, boolean on, boolean[] before) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        test.onClientRun(() -> {
            if (on) {
                before[0] = minecraft.options.enableVsync().get();
                minecraft.options.enableVsync().set(false);
                minecraft.getWindow().updateVsync(false);
                minecraft.getWindow().setFramerateLimit(260);
            } else {
                minecraft.options.enableVsync().set(before[0]);
                minecraft.getWindow().updateVsync(before[0]);
                minecraft.getWindow().setFramerateLimit(minecraft.options.framerateLimit().get());
            }
        });
    }

    // ------------------------------------------------------------------ The tour

    private static void stage(SelfTest test) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        JsonObject none = new JsonObject();
        test.command("time set 1000");
        test.command("weather clear");
        flying(test, true, 0.05F);
        settle(test);

        // What there is to visit, looked for before the recording so that the looking is not in it
        BlockPos spawn = playerBlock(test);
        BlockPos village = structure(test, spawn, BuiltinStructures.VILLAGE_PLAINS, BuiltinStructures.VILLAGE_DESERT, BuiltinStructures.VILLAGE_SAVANNA,
            BuiltinStructures.VILLAGE_SNOWY, BuiltinStructures.VILLAGE_TAIGA);
        BlockPos hub = village != null ? village : spawn;
        BlockPos trial = structure(test, hub, BuiltinStructures.TRIAL_CHAMBERS);
        BlockPos ruin = structure(test, hub, BuiltinStructures.RUINED_PORTAL_STANDARD, BuiltinStructures.RUINED_PORTAL_DESERT, BuiltinStructures.RUINED_PORTAL_JUNGLE,
            BuiltinStructures.RUINED_PORTAL_SWAMP, BuiltinStructures.RUINED_PORTAL_MOUNTAIN, BuiltinStructures.RUINED_PORTAL_OCEAN);
        BlockPos mineshaft = structure(test, hub, BuiltinStructures.MINESHAFT, BuiltinStructures.MINESHAFT_MESA);
        BlockPos ocean = biome(test, hub, 4800, "ocean", found -> found.is(BiomeTags.IS_OCEAN) && !found.is(Biomes.FROZEN_OCEAN) && !found.is(Biomes.DEEP_FROZEN_OCEAN));
        BlockPos wreck = ocean == null ? null : structure(test, ocean, BuiltinStructures.SHIPWRECK);
        BlockPos peak = biome(test, hub, 6400, "snowy peak", found -> found.is(Biomes.JAGGED_PEAKS) || found.is(Biomes.FROZEN_PEAKS));
        BlockPos cold = peak != null ? peak : biome(test, hub, 6400, "snowy ground",
            found -> found.is(Biomes.SNOWY_SLOPES) || found.is(Biomes.GROVE) || found.is(Biomes.SNOWY_PLAINS) || found.is(Biomes.SNOWY_TAIGA) || found.is(Biomes.ICE_SPIKES));

        boolean[] vsync = new boolean[1];
        test.tool("config_set", args("key", "recordHud", "value", true));
        JsonObject started = test.tool("recording_start", none);
        check(started.get("active").getAsBoolean(), "the recording in the generated world did not start");
        test.waitFor(() -> Recorder.get().recordingTick() >= 5, 30, "the generated world's recording to get going");

        // What recording costs a frame, standing still: four turns each of paused and recording, a few seconds at a time,
        // so that the machine warming up or another program waking does not pass for a cost
        uncapped(test, true, vsync);
        test.sleepTicks(20);
        double idleSum = 0, recordingSum = 0;
        int idleFrames = 0, recordingFrames = 0;
        double idleWorst = 0, recordingWorst = 0;
        for (int turn = 0; turn < 4; turn++) {
            test.tool("recording_pause", none);
            test.sleepTicks(10);
            FrameTimes.Result idle = FrameTimes.measure(test, 70);
            test.tool("recording_resume", none);
            test.sleepTicks(10);
            FrameTimes.Result recording = FrameTimes.measure(test, 70);
            idleSum += idle.average() * idle.frames();
            idleFrames += idle.frames();
            idleWorst = Math.max(idleWorst, idle.worst());
            recordingSum += recording.average() * recording.frames();
            recordingFrames += recording.frames();
            recordingWorst = Math.max(recordingWorst, recording.worst());
        }
        uncapped(test, false, vsync);
        FrameTimes.Result idleStill = new FrameTimes.Result(idleSum / Math.max(1, idleFrames), idleWorst, 0, idleFrames);
        FrameTimes.Result recordingStill = new FrameTimes.Result(recordingSum / Math.max(1, recordingFrames), recordingWorst, 0, recordingFrames);
        table.add(String.format(Locale.ROOT, "frames, standing still at spawn, four turns each: idle (recording paused) %.3f ms average, %.1f ms worst over %d frames; recording %.3f ms average, %.1f ms worst over %d frames",
            idleStill.average(), idleStill.worst(), idleFrames, recordingStill.average(), recordingStill.worst(), recordingFrames));
        overhead(test, "standing still", idleStill, recordingStill);

        part(test, "travel", () -> travel(test, spawn, hub, vsync));
        if (village != null) {
            part(test, "village", () -> village(test, village, vsync));
            part(test, "night", () -> night(test, village));
        } else {
            test.skip("no village within reach of spawn in the generated world: villagers, beds, bells and the night there were not staged");
        }
        BlockPos labGround = village != null ? village.offset(56, 0, 56) : spawn.offset(56, 0, 56);
        BlockPos lab = new BlockPos(labGround.getX(), surface(labGround.getX(), labGround.getZ(), Heightmap.Types.WORLD_SURFACE) + 30, labGround.getZ());
        part(test, "fluids and falling things", () -> lab(test, lab));
        part(test, "survival", () -> survival(test, lab));
        if (mineshaft != null) {
            part(test, "cave", () -> cave(test, mineshaft));
        } else {
            test.skip("no mineshaft within reach in the generated world: the deep cave and its torch were not staged");
        }
        if (trial != null) {
            part(test, "trial chamber", () -> trialChamber(test, trial));
        } else {
            test.skip("no trial chamber within reach in the generated world: spawners and vaults were not staged");
        }
        if (ruin != null) {
            part(test, "ruined portal", () -> ruinedPortal(test, ruin));
        } else {
            test.skip("no ruined portal within reach in the generated world");
        }
        if (ocean != null) {
            part(test, "ocean", () -> ocean(test, ocean, wreck));
        } else {
            test.skip("no ocean within reach in the generated world: under water was not staged");
        }
        if (cold != null) {
            part(test, "mountain", () -> mountain(test, cold, peak != null));
        } else {
            test.skip("no snowy biome within reach in the generated world: the mountain top and settling snow were not staged");
        }
        part(test, "nether", () -> nether(test, lab));

        phase = "end";
        ticks(test, 20);
        test.tool("config_set", args("key", "recordHud", "value", false));
        JsonObject finished = test.tool("recording_finish", args("name", "Real world"));
        Path saved = Path.of(finished.get("path").getAsString());
        // Out of the recordings folder: other steps count the recordings there
        recording = folder.resolve("Real world" + RecordingFile.EXTENSION);
        Files.move(saved, recording, StandardCopyOption.REPLACE_EXISTING);

        // What the file holds
        ClientPacketListener listener = minecraft.getConnection();
        GameCodec codec = new GameCodec(listener.registryAccess(), listener.getConnectionType());
        try (RecordingFile file = RecordingFile.open(recording)) {
            RecordingManifest manifest = file.manifest();
            Audit audit = Audit.of(file, codec, false);
            if (!audit.problems.isEmpty()) {
                fault(test, "the recording has problems: " + audit.problems);
            }
            long size = Files.size(recording);
            double minutes = manifest.totalTicks / 1200.0;
            table.add(String.format(Locale.ROOT, "recording: %d ticks (%.1f min) in %d segments, %.1f MB, %.2f MB a minute over the whole tour (%.0f MB an hour); %d chunk uses stored as %d pieces",
                manifest.totalTicks, minutes, manifest.segments.size(), size / 1048576.0, size / 1048576.0 / minutes, size / 1048576.0 / minutes * 60, audit.sharedPackets, file.sharedCount()));
            whatItHolds(test, file, codec, minecraft.player.getId());
            if (manifest.markers.stream().noneMatch(marker -> marker.description.contains("the_nether"))) {
                fault(test, "no marker was dropped on the change to the Nether: " + manifest.markers.stream().map(marker -> marker.description).toList());
            }
        }
        bytesByPhase.forEach((name, counted) -> {
            if (counted[1] > 0) {
                table.add(String.format(Locale.ROOT, "bytes, %s: %.2f MB over %d ticks, %.2f MB a minute (%.0f MB an hour)", name, counted[0] / 1048576.0, counted[1],
                    counted[0] / 1048576.0 / (counted[1] / 1200.0), counted[0] / 1048576.0 / (counted[1] / 1200.0) * 60));
            }
        });
        double worst = 0;
        for (Map.Entry<String, List<Snapshots.Cost>> entry : snapshotCosts.entrySet()) {
            List<Snapshots.Cost> costs = entry.getValue();
            double longest = costs.stream().mapToDouble(Snapshots.Cost::millis).max().orElse(0);
            worst = Math.max(worst, longest);
            Snapshots.Cost slowest = costs.stream().filter(cost -> cost.millis() == longest).findFirst().orElseThrow();
            table.add(String.format(Locale.ROOT, "snapshots, %s: %d taken, %.1f ms average, %.1f ms longest (that one: %d chunks named, %d written afresh, %d entities; world %.1f ms, chunks %.1f ms, entities %.1f ms, the rest %.1f ms); %.0f chunks written afresh on average",
                entry.getKey(), costs.size(), costs.stream().mapToDouble(Snapshots.Cost::millis).average().orElse(0), longest, slowest.chunks(), slowest.encoded(), slowest.entities(),
                slowest.world(), slowest.chunkMillis(), slowest.entityMillis(), slowest.extensions(), costs.stream().mapToInt(Snapshots.Cost::encoded).average().orElse(0)));
        }
        // A snapshot holds the game for its whole length. One frame at 20 frames a second is 50 ms; five of those is a gross breach
        if (worst > 250) {
            fault(test, String.format(Locale.ROOT, "a snapshot held the game for %.0f ms", worst));
        }
    }

    /**
     * Reads the whole recording once: where its bytes go, and whether it holds what a server never tells a player about
     * themselves (their footsteps, the cracks on the block they mine, the block breaking up).
     */
    private static int ordinaryFrom = -1;
    private static int ordinaryTo = -1;

    private static void whatItHolds(SelfTest test, RecordingFile file, GameCodec codec, int ownId) throws Exception {
        Map<String, long[]> byKind = new java.util.HashMap<>();
        Map<String, long[]> ordinary = new java.util.HashMap<>();
        java.util.Set<String> ordinaryPieces = new java.util.HashSet<>();
        int ordinaryNames = 0;
        java.util.Set<String> pieces = new java.util.HashSet<>();
        int namedInSnapshots = 0, namedInTicks = 0;
        int ownSounds = 0, cracks = 0, breaks = 0;
        long other = 0;
        for (RecordingManifest.Segment segment : file.segments()) {
            boolean inSnapshot = false;
            int tick = segment.startTick;
            try (comedymonkey.dailies.file.Records.Reader reader = new comedymonkey.dailies.file.Records.Reader(file.openSegment(segment.number))) {
                while (reader.next()) {
                    int kind = reader.kind();
                    boolean inOrdinary = tick >= ordinaryFrom && tick < ordinaryTo;
                    if (kind == comedymonkey.dailies.file.Records.TICK) {
                        tick += reader.number();
                    } else if (kind == comedymonkey.dailies.file.Records.SNAPSHOT_BEGIN) {
                        inSnapshot = true;
                    } else if (kind == comedymonkey.dailies.file.Records.SNAPSHOT_END) {
                        inSnapshot = false;
                    } else if (kind == comedymonkey.dailies.file.Records.SHARED_PACKET) {
                        String piece = java.util.HexFormat.of().formatHex(reader.payload(), 0, reader.length());
                        if (pieces.add(piece) && inOrdinary) {
                            ordinaryPieces.add(piece);
                        }
                        if (inOrdinary) {
                            ordinaryNames++;
                        }
                        if (inSnapshot) {
                            namedInSnapshots++;
                        } else {
                            namedInTicks++;
                        }
                        other += reader.length();
                    } else if (kind == comedymonkey.dailies.file.Records.PACKET) {
                        net.minecraft.network.protocol.Packet<?> packet;
                        try {
                            packet = codec.decode(reader.payload(), 0, reader.length());
                        } catch (RuntimeException unreadable) {
                            continue;
                        }
                        String name = (inSnapshot ? "snapshot " : "") + packet.getClass().getSimpleName().replace("Clientbound", "").replace("Packet", "");
                        long[] sum = byKind.computeIfAbsent(name, key -> new long[2]);
                        sum[0] += reader.length();
                        sum[1]++;
                        if (inOrdinary) {
                            long[] here = ordinary.computeIfAbsent(name, key -> new long[2]);
                            here[0] += reader.length();
                            here[1]++;
                        }
                        if (packet instanceof net.minecraft.network.protocol.game.ClientboundSoundPacket sound && sound.getSource() == net.minecraft.sounds.SoundSource.PLAYERS) {
                            ownSounds++;
                        } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket cracking && cracking.getId() == ownId
                            && cracking.getPos().equals(minedBlock) && cracking.getProgress() >= 0) {
                            cracks++;
                        } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundLevelEventPacket event && event.getType() == 2001 && event.getPos().equals(minedBlock)) {
                            breaks++;
                        }
                    } else {
                        other += reader.length();
                    }
                }
            }
        }
        long chunkBytes = 0;
        for (String piece : pieces) {
            chunkBytes += file.shared(piece).length;
        }
        long packetBytes = byKind.values().stream().mapToLong(sum -> sum[0]).sum();
        StringBuilder biggest = new StringBuilder();
        byKind.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0])).limit(9).forEach(entry -> biggest.append(String.format(Locale.ROOT, " %s %.1f MB (%d);",
            entry.getKey(), entry.getValue()[0] / 1048576.0, entry.getValue()[1])));
        table.add(String.format(Locale.ROOT, "what the recording holds, before packing: %d stored chunks %.1f MB, named %d times by snapshots and %d times as they arrived; packets %.1f MB; the rest %.1f MB. The largest kinds of packet:%s",
            pieces.size(), chunkBytes / 1048576.0, namedInSnapshots, namedInTicks, packetBytes / 1048576.0, other / 1048576.0, biggest));

        if (ordinaryTo > ordinaryFrom) {
            long fresh = 0;
            for (String piece : ordinaryPieces) {
                fresh += file.shared(piece).length;
            }
            StringBuilder most = new StringBuilder();
            ordinary.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0])).limit(7).forEach(entry -> most.append(String.format(Locale.ROOT, " %s %.0f KB (%d);",
                entry.getKey(), entry.getValue()[0] / 1024.0, entry.getValue()[1])));
            table.add(String.format(Locale.ROOT, "where ordinary play's bytes go (the walk round the village, ticks %d to %d, before packing): %d chunks stored that were not stored before, %.1f MB; chunks named %d times, %.0f KB; packets %.0f KB, the largest kinds:%s",
                ordinaryFrom, ordinaryTo, ordinaryPieces.size(), fresh / 1048576.0, ordinaryNames, ordinaryNames * 33 / 1024.0,
                ordinary.values().stream().mapToLong(sum -> sum[0]).sum() / 1024.0, most));
        }
        test.log("real world: the recording holds " + ownSounds + " sounds of players' own bodies (footsteps and the like)");
        if (ownSounds < 10) {
            fault(test, "the recorder walked across country and through a village and the recording holds " + ownSounds + " footsteps: in playback they walk in silence");
        }
        if (minedBlock != null) {
            test.log("real world: the recording holds " + cracks + " stages of cracks on the log the recorder mined, and " + breaks + " breaking of it");
            if (minedByHand && cracks < 3) {
                fault(test, "the recorder mined a log by hand and the recording holds " + cracks + " stages of its cracks: playback shows the block whole until it vanishes");
            }
            if (minedByHand && breaks == 0) {
                fault(test, "the recorder mined a log and the recording does not hold its breaking: playback shows no fragments and plays no sound when it goes");
            }
        }
    }

    /** Recording must not cost more than half as much again plus 2 ms a frame: a gross breach of the 3 % and 1 ms the plan asks for, which are logged */
    private static void overhead(SelfTest test, String what, FrameTimes.Result idle, FrameTimes.Result recording) {
        double added = recording.average() - idle.average();
        // The game ran uncapped for this, at a thousand frames a second or so; three per cent of such a frame is a few
        // hundredths of a millisecond, less than two runs of the same thing differ by. So the cost is also given against
        // the frame of a game running at 60 and at 144 frames a second, which is what a person would lose
        table.add(String.format(Locale.ROOT, "recording overhead, %s: %.3f ms a frame added to %.2f ms (%.1f %% at this uncapped rate of %.0f frames a second; %.2f %% of a frame at 144 a second, %.2f %% at 60); the plan asks for under 1 ms and under 3 %%%s",
            what, added, idle.average(), idle.average() > 0 ? added / idle.average() * 100 : 0, 1000.0 / Math.max(0.01, idle.average()), added / (1000.0 / 144) * 100, added / (1000.0 / 60) * 100,
            added > 1.0 || added / (1000.0 / 144) > 0.03 ? " -- OVER" : ""));
        if (recording.average() > idle.average() * 1.5 + 2.0) {
            fault(test, "recording slowed the game while " + what + ": frames averaged " + recording.average() + " ms against " + idle.average() + " ms idle");
        }
    }

    // --- 1. Travel: chunks arriving ahead and forgotten behind

    private static void travel(SelfTest test, BlockPos spawn, BlockPos towards, boolean[] vsync) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        Vec3 from = playerAt(test);
        float yaw = towards.equals(spawn) ? 0.0F : yawTowards(from, towards.getX() + 0.5, towards.getZ() + 0.5);

        // On foot first, over whatever the ground is
        int ground = surface(spawn.getX(), spawn.getZ(), Heightmap.Types.MOTION_BLOCKING);
        flying(test, false, 0.05F);
        goTo(test, spawn.getX() + 0.5, ground, spawn.getZ() + 0.5, yaw, 0);
        test.onClientRun(() -> minecraft.options.autoJump().set(true));
        note(test, "before setting off");
        drive = new Drive(yaw, 0, true, true, false, false, false);
        for (int i = 0; i < 4; i++) {
            ticks(test, 45);
            note(test, "walking " + (i + 1));
        }
        drive = null;
        Vec3 walkedTo = playerAt(test);
        test.log(String.format(Locale.ROOT, "real world: walked %.0f blocks", walkedTo.distanceTo(from)));
        if (walkedTo.distanceTo(from) < 10) {
            test.skip("the walk across generated terrain covered only " + (int) walkedTo.distanceTo(from) + " blocks (water or a wall in the way); the flight covers the travel checks");
        }

        // Then in the air, at about the speed of an elytra with rockets: half as fast again as creative flight
        int height = Math.max(190, surface((int) walkedTo.x, (int) walkedTo.z, Heightmap.Types.MOTION_BLOCKING) + 60);
        flying(test, true, 0.075F);
        goTo(test, walkedTo.x, height, walkedTo.z, yaw, 10);
        flying(test, true, 0.075F);
        Vec3 flightStart = playerAt(test);
        note(test, "before the flight");

        // What flying through fresh terrain costs a frame while recording, and then with the recording paused
        uncapped(test, true, vsync);
        drive = new Drive(yaw, 10, true, true, false, false, false);
        ticks(test, 20);
        FrameTimes.begin();
        ticks(test, 160);
        FrameTimes.Result recordingFlight = FrameTimes.end();
        test.tool("recording_pause", new JsonObject());
        test.sleepTicks(20);
        FrameTimes.Result idleFlight = FrameTimes.measure(test, 160);
        test.tool("recording_resume", new JsonObject());
        uncapped(test, false, vsync);
        table.add("frames, flying through fresh terrain: idle (recording paused) " + idleFlight + "; recording " + recordingFlight);
        overhead(test, "flying through fresh terrain", idleFlight, recordingFlight);

        // On, with the world noted every two and a half seconds: twice in every stretch between snapshots
        for (int i = 0; i < 6; i++) {
            ticks(test, 50);
            note(test, "flying " + (i + 1));
        }
        drive = null;
        Vec3 flightEnd = playerAt(test);
        double flown = flightEnd.distanceTo(flightStart);
        test.log(String.format(Locale.ROOT, "real world: flew %.0f blocks", flown));
        if (flown < 300) {
            fault(test, "the flight covered only " + (int) flown + " blocks, too few to prove chunks are forgotten behind the recorder");
        }
        settle(test);
        note(test, "at the end of the flight");

        // Back to where the walk began, in one step: every chunk held is replaced
        goTo(test, from.x, height, from.z, yaw + 180, 30);
        note(test, "teleported back to the start");
        ticks(test, 130);
        note(test, "a while after the teleport");
    }

    // --- 3. A village: block entities, villagers, a chest opened, dusk and rain

    private static void village(SelfTest test, BlockPos village, boolean[] vsync) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        int x = village.getX();
        int z = village.getZ();
        int ground = surface(x, z, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
        flying(test, true, 0.05F);
        goTo(test, x + 0.5, ground + 12, z + 0.5, 0, 40);
        test.command("time set 6000");

        // The people of the place, some given names so they can be followed
        command(test, "execute unless entity @e[type=minecraft:villager,distance=..64] run summon minecraft:villager %d %d %d", x + 2, ground + 1, z + 2);
        command(test, "execute unless entity @e[type=minecraft:iron_golem,distance=..64] run summon minecraft:iron_golem %d %d %d", x - 3, ground + 1, z + 2);
        test.sleepTicks(10);
        test.command("data merge entity @e[type=minecraft:villager,limit=1,sort=nearest] {CustomName:'\"Mayor\"'}");
        test.command("data merge entity @e[type=minecraft:iron_golem,limit=1,sort=nearest] {CustomName:'\"Guard\"'}");
        command(test, "summon minecraft:cat %d %d %d {CustomName:'\"Tom\"',PersistenceRequired:1b}", x + 3, ground + 1, z - 2);

        // A chest whose loot has not been made yet, as the village's own are until someone looks
        BlockPos chest = new BlockPos(x + 1, surface(x + 1, z - 4, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES), z - 4);
        command(test, "fill %d %d %d %d %d %d minecraft:stone_bricks", chest.getX() - 1, chest.getY() - 1, chest.getZ() - 1, chest.getX() + 1, chest.getY() - 1, chest.getZ() + 3);
        command(test, "fill %d %d %d %d %d %d minecraft:air", chest.getX() - 1, chest.getY(), chest.getZ() - 1, chest.getX() + 1, chest.getY() + 2, chest.getZ() + 3);
        command(test, "setblock %d %d %d minecraft:chest{LootTable:\"minecraft:chests/village/village_plains_house\"}", chest.getX(), chest.getY(), chest.getZ());
        regions.add(Region.around("village centre", new BlockPos(x, ground, z), 12, 6, 10, true));
        askers.put("chest lid", game -> {
            BlockEntity blockEntity = game.level.getBlockEntity(chest);
            return blockEntity instanceof LidBlockEntity lid ? (lid.getOpenNess(1.0F) > 0.01F ? "open" : "shut") : "no chest";
        });
        ticks(test, 30);
        note(test, "the village at noon");
        int beds = test.onClient(() -> {
            int count = 0;
            for (int cx = (x >> 4) - 4; cx <= (x >> 4) + 4; cx++) {
                for (int cz = (z >> 4) - 4; cz <= (z >> 4) + 4; cz++) {
                    LevelChunk chunk = minecraft.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                    count += chunk == null ? 0 : chunk.getBlockEntities().size();
                }
            }
            return count;
        });
        test.log("real world: the village has " + beds + " block entities in the nine-by-nine chunks around its middle");

        // Down beside the chest, and open it as a player does
        flying(test, false, 0.05F);
        goTo(test, chest.getX() + 0.5, chest.getY(), chest.getZ() + 1.8, 180, 45);
        ticks(test, 10);
        note(test, "before the chest is opened");
        useOn(test, chest, Direction.UP);
        ticks(test, 14);
        WorldProbe opening = note(test, "the chest lid going up");
        if (!"open".equals(opening.facts.get("chest lid")) || !opening.screen.contains("ContainerScreen")) {
            test.skip("the chest in the village could not be opened by the script (" + opening.facts + ", screen " + opening.screen + "): its lid in playback was not checked");
        }
        // Dusk and rain come on while it stands open, and a snapshot or two goes by
        test.command("time set 12600");
        test.command("weather rain");
        ticks(test, 215);
        note(test, "the chest still open, two snapshots later");
        test.onClientRun(() -> minecraft.player.closeContainer());
        ticks(test, 16);
        note(test, "the chest shut again");

        flying(test, true, 0.05F);
        goTo(test, x + 14.5, ground + 9, z + 14.5, 135, 25);
        ticks(test, 40);
        noteWithStill(test, "the village at dusk in the rain", "village-dusk-rain");

        // An ordinary minute: wandering about a village on foot, measured with the recording paused and then running
        flying(test, false, 0.05F);
        goTo(test, x + 6.5, surface(x + 6, z + 6, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES), z + 6.5, 0, 0);
        uncapped(test, true, vsync);
        test.tool("recording_pause", new JsonObject());
        FrameTimes.begin();
        wander(test, 300);
        FrameTimes.Result idle = FrameTimes.end();
        test.tool("recording_resume", new JsonObject());
        ticks(test, 10);
        long size = workingSize();
        int from = Recorder.get().recordingTick();
        ordinaryFrom = from;
        FrameTimes.begin();
        wander(test, 600);
        FrameTimes.Result recorded = FrameTimes.end();
        uncapped(test, false, vsync);
        ticks(test, 45);
        long bytes = workingSize() - size;
        int length = Recorder.get().recordingTick() - from;
        ordinaryTo = Recorder.get().recordingTick();
        table.add("frames, on foot in a village in the rain: idle (recording paused) " + idle + "; recording " + recorded);
        overhead(test, "on foot in a village", idle, recorded);
        double hourly = bytes / 1048576.0 / (length / 1200.0) * 60;
        table.add(String.format(Locale.ROOT, "bytes, ordinary play (on foot in a village, %d ticks): %.2f MB, %.0f MB an hour; the plan asks for under 150%s", length, bytes / 1048576.0,
            hourly, hourly > 150 ? " -- OVER" : ""));
        if (hourly > 450) {
            fault(test, "ordinary play in a village filled the recording at " + (int) hourly + " MB an hour, three times what the plan allows");
        }
        note(test, "after a walk round the village");
    }

    /** Walks about for a while, turning every few seconds, as someone looking round does */
    private static void wander(SelfTest test, int ticks) throws Exception {
        float[] headings = {0, 90, 180, 270, 45, 225};
        for (int done = 0, turn = 0; done < ticks; done += 60, turn++) {
            drive = new Drive(headings[turn % headings.length], 5, true, false, false, false, false);
            test.sleepTicks(Math.min(60, ticks - done));
        }
        drive = null;
    }

    // --- 4. Night, its mobs, thunder, and the dawn that burns them

    private static void night(SelfTest test, BlockPos village) throws Exception {
        int x = village.getX() - 20;
        int z = village.getZ() - 20;
        int ground = surface(x, z, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
        flying(test, true, 0.05F);
        goTo(test, x + 0.5, ground + 7, z + 0.5, -45, 22);
        test.command("time set 12800");
        test.command("weather thunder");

        // Some of everything the night brings, named so each can be followed; the rest spawn by themselves
        String[][] cast = {
            {"zombie", "Burny", ""}, {"skeleton", "Bones", ""}, {"creeper", "Hiss", ""}, {"spider", "Climber", ""}, {"iron_golem", "Tin", ""},
            {"enderman", "Carrier", ",carriedBlockState:{Name:\"minecraft:grass_block\"}"}, {"bat", "Flap", ""},
            {"cow", "Daisy", ",InLove:600"}, {"cow", "Buttercup", ",InLove:600"}, {"bee", "Buzz", ""},
            {"wandering_trader", "Trader", ",UUID:[I;77,78,79,80],DespawnDelay:24000"},
            {"trader_llama", "Larry", ",leash:{UUID:[I;77,78,79,80]}"}, {"trader_llama", "Lola", ",leash:{UUID:[I;77,78,79,80]}"}
        };
        for (int i = 0; i < cast.length; i++) {
            int cx = x + 4 + (i % 4) * 3;
            int cz = z + 4 + (i / 4) * 3;
            int cy = surface(cx, cz, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES) + (cast[i][0].equals("bat") ? 4 : 0);
            command(test, "summon minecraft:%s %d %d %d {CustomName:'\"%s\"',PersistenceRequired:1b%s}", cast[i][0], cx, cy, cz, cast[i][1], cast[i][2]);
        }
        command(test, "setblock %d %d %d minecraft:bee_nest", x + 2, surface(x + 2, z + 2, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES), z + 2);
        ticks(test, 40);
        WorldProbe first = noteWithStill(test, "nightfall with thunder coming on", "night-thunder");
        for (String name : new String[]{"Burny", "Bones", "Hiss", "Climber", "Carrier", "Daisy", "Trader", "Larry"}) {
            if (!first.named.containsKey(name)) {
                test.log("real world: " + name + " is not there to be followed (it may have wandered off or been killed)");
            }
        }
        if (first.named.containsKey("Larry") && !first.named.get("Larry").state().contains("leashed")) {
            test.skip("the wandering trader's llama was not on its lead in the live game, so leads in playback were not checked");
        }
        test.command("time set 13800");
        ticks(test, 100);
        note(test, "night, thunder");

        // The night passed quickly: three jumps of the clock, the world noted between them
        for (int i = 0; i < 3; i++) {
            test.command("time add 2500");
            ticks(test, 70);
            note(test, "the night, part " + (i + 1));
        }
        test.command("weather clear");
        test.command("time set 23300");
        ticks(test, 120);
        note(test, "first light");
        test.command("time set 200");
        ticks(test, 60);
        WorldProbe dawn = note(test, "morning: what the sun finds");
        long burning = dawn.named.values().stream().filter(tracked -> tracked.state().contains("burning")).count();
        test.log("real world: " + burning + " of the named mobs are burning in the morning sun; " + dawn.entities.size() + " entities in all");
        ticks(test, 130);
        note(test, "later that morning");
    }

    // --- 6. Fluids and falling things, on a stone floor in the air where nothing else interferes

    private static void lab(SelfTest test, BlockPos lab) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        int x = lab.getX();
        int y = lab.getY();
        int z = lab.getZ();
        test.command("time set 3000");
        test.command("weather clear");
        flying(test, true, 0.05F);
        goTo(test, x + 0.5, y + 12, z + 0.5, 0, 60);
        build(test, lab);
        goTo(test, x + 0.5, y + 14, z + 0.5, 0, 75);
        regions.add(new Region("laboratory", lab.offset(-17, -2, -17), lab.offset(17, 12, 17), true));
        ticks(test, 20);
        note(test, "the laboratory, built");

        // Water at the top of one flight of steps, lava at the top of another facing it; they meet on the floor between
        command(test, "setblock %d %d %d minecraft:water", x - 14, y + 6, z - 13);
        command(test, "setblock %d %d %d minecraft:lava", x + 11, y + 3, z - 13);
        // Three troughs: lava running into water (cobblestone), water reaching a lava spring (obsidian), lava falling on water (stone)
        command(test, "setblock %d %d %d minecraft:lava", x + 3, y, z + 6);
        command(test, "setblock %d %d %d minecraft:lava", x + 1, y, z + 10);
        command(test, "setblock %d %d %d minecraft:water", x - 1, y, z + 10);
        command(test, "setblock %d %d %d minecraft:water", x + 8, y, z + 10);
        ticks(test, 25);
        note(test, "fluids setting off");
        command(test, "setblock %d %d %d minecraft:lava", x + 8, y + 2, z + 10);
        ticks(test, 45);
        note(test, "water down the steps");
        command(test, "setblock %d %d %d minecraft:water", x - 3, y, z + 6);
        ticks(test, 40);
        note(test, "water meets lava in the troughs");

        // Sand and gravel on pillars, with the pillars taken away
        command(test, "fill %d %d %d %d %d %d minecraft:air", x + 12, y, z + 2, x + 13, y + 3, z + 2);
        ticks(test, 5);
        note(test, "sand and gravel falling");
        ticks(test, 40);
        WorldProbe landed = note(test, "sand and gravel landed");

        // Bone meal on young wheat, by hand
        command(test, "tp @s %.2f %d %.2f 180 50", x - 10.5, y, z + 0.5);
        flying(test, false, 0.05F);
        test.command("item replace entity @s weapon.mainhand with minecraft:bone_meal 16");
        ticks(test, 10);
        for (int i = 0; i < 3; i++) {
            useOn(test, new BlockPos(x - 12 + i, y, z - 2), Direction.UP);
            ticks(test, 6);
            useOn(test, new BlockPos(x - 12 + i, y, z - 2), Direction.UP);
            ticks(test, 6);
        }
        note(test, "wheat after bone meal");
        ticks(test, 100);
        WorldProbe met = note(test, "lava down its steps");

        // Fire on wool, a tree with its trunk cut out, ice beside a torch: all three are slow, so the world is hurried along
        flying(test, true, 0.05F);
        goTo(test, x + 0.5, y + 14, z + 0.5, 0, 75);
        command(test, "fill %d %d %d %d %d %d minecraft:fire", x + 11, y + 2, z + 10, x + 13, y + 2, z + 12);
        command(test, "fill %d %d %d %d %d %d minecraft:air replace #minecraft:logs", x - 15, y, z + 3, x - 9, y + 12, z + 9);
        test.command("gamerule randomTickSpeed 300");
        ticks(test, 12);
        note(test, "leaves starting to go, fire catching");
        ticks(test, 60);
        note(test, "leaves going, ice melting");
        test.command("tick sprint 900");
        IntegratedServer server = minecraft.getSingleplayerServer();
        test.waitFor(() -> !server.tickRateManager().isSprinting(), 180, "the hurried ticks in the laboratory");
        test.command("gamerule randomTickSpeed 3");
        ticks(test, 40);
        WorldProbe after = note(test, "after the fire");

        // What came of it all, so the report can say what the block checks were looking at
        Region region = regions.get(0);
        test.log("real world: laboratory blocks: " + census(landed, region) + " when the sand had landed; " + census(met, region) + " with the fluids met; "
            + census(after, region) + " at the end");
        String end = census(after, region);
        for (String made : new String[]{"cobblestone", "obsidian"}) {
            if (!end.contains(made)) {
                test.skip("no " + made + " formed where the fluids met in the laboratory, so that meeting was not among the blocks compared");
            }
        }
        // Left dry for the survival part
        command(test, "fill %d %d %d %d %d %d minecraft:air replace minecraft:water", x - 16, y, z - 16, x + 16, y + 8, z + 16);
        command(test, "fill %d %d %d %d %d %d minecraft:air replace minecraft:lava", x - 16, y, z - 16, x + 16, y + 8, z + 16);
        command(test, "fill %d %d %d %d %d %d minecraft:air replace minecraft:fire", x - 16, y, z - 16, x + 16, y + 8, z + 16);
        test.command("kill @e[type=minecraft:item,distance=..40]");
        ticks(test, 20);
        note(test, "the laboratory dried out");
    }

    /** How many of each block of interest a watched box held */
    private static String census(WorldProbe probe, Region region) {
        Map<String, Integer> counts = new java.util.TreeMap<>();
        for (int id : probe.regionBlocks.get(region)) {
            String name = BuiltInRegistries.BLOCK.getKey(net.minecraft.world.level.block.Block.stateById(id).getBlock()).getPath();
            if (List.of("water", "lava", "cobblestone", "obsidian", "sand", "gravel", "oak_leaves", "oak_log", "wheat", "fire", "ice", "white_wool", "farmland").contains(name)) {
                counts.merge(name, 1, Integer::sum);
            }
        }
        return counts.toString();
    }

    private static void build(SelfTest test, BlockPos lab) throws Exception {
        int x = lab.getX();
        int y = lab.getY();
        int z = lab.getZ();
        // The floor, two thick, with a wall round it to keep what flows from running off
        command(test, "fill %d %d %d %d %d %d minecraft:air", x - 16, y, z - 16, x + 16, y + 12, z + 16);
        command(test, "fill %d %d %d %d %d %d minecraft:stone", x - 16, y - 2, z - 16, x + 16, y - 1, z + 16);
        command(test, "fill %d %d %d %d %d %d minecraft:stone_bricks", x - 16, y, z - 16, x + 16, y, z - 16);
        command(test, "fill %d %d %d %d %d %d minecraft:stone_bricks", x - 16, y, z + 16, x + 16, y, z + 16);
        command(test, "fill %d %d %d %d %d %d minecraft:stone_bricks", x - 16, y, z - 16, x - 16, y, z + 16);
        command(test, "fill %d %d %d %d %d %d minecraft:stone_bricks", x + 16, y, z - 16, x + 16, y, z + 16);
        // Steps down towards the east for water, six of them
        for (int i = 0; i < 6; i++) {
            command(test, "fill %d %d %d %d %d %d minecraft:stone", x - 14 + i * 2, y, z - 14, x - 13 + i * 2, y + 5 - i, z - 12);
        }
        // Steps down towards the west for lava, three of them
        for (int i = 0; i < 3; i++) {
            command(test, "fill %d %d %d %d %d %d minecraft:stone", x + 10 - i * 2, y, z - 14, x + 11 - i * 2, y + 2 - i, z - 12);
        }
        // Trough one: seven long. Trough two: three long. A well with a shaft over it
        command(test, "fill %d %d %d %d %d %d minecraft:stone", x - 4, y, z + 5, x + 4, y + 1, z + 7);
        command(test, "fill %d %d %d %d %d %d minecraft:air", x - 3, y, z + 6, x + 3, y + 1, z + 6);
        command(test, "fill %d %d %d %d %d %d minecraft:stone", x - 2, y, z + 9, x + 2, y + 1, z + 11);
        command(test, "fill %d %d %d %d %d %d minecraft:air", x - 1, y, z + 10, x + 1, y + 1, z + 10);
        command(test, "fill %d %d %d %d %d %d minecraft:stone", x + 7, y, z + 9, x + 9, y + 3, z + 11);
        command(test, "fill %d %d %d %d %d %d minecraft:air", x + 8, y, z + 10, x + 8, y + 3, z + 10);
        // Sand and gravel, three of each, on pillars four high
        command(test, "fill %d %d %d %d %d %d minecraft:stone", x + 12, y, z + 2, x + 13, y + 3, z + 2);
        command(test, "fill %d %d %d %d %d %d minecraft:sand", x + 12, y + 4, z + 2, x + 12, y + 6, z + 2);
        command(test, "fill %d %d %d %d %d %d minecraft:gravel", x + 13, y + 4, z + 2, x + 13, y + 6, z + 2);
        // A tree, grown by the game's own rules where it will; built by hand where it will not
        command(test, "setblock %d %d %d minecraft:dirt", x - 12, y - 1, z + 6);
        command(test, "place feature minecraft:oak %d %d %d", x - 12, y, z + 6);
        test.sleepTicks(5);
        if (!blockAt(test, new BlockPos(x - 12, y, z + 6)).is(Blocks.OAK_LOG)) {
            command(test, "fill %d %d %d %d %d %d minecraft:oak_leaves", x - 14, y + 3, z + 4, x - 10, y + 5, z + 8);
            command(test, "fill %d %d %d %d %d %d minecraft:oak_log", x - 12, y, z + 6, x - 12, y + 4, z + 6);
        }
        // Wheat on wet farmland
        command(test, "fill %d %d %d %d %d %d minecraft:farmland[moisture=7]", x - 12, y - 1, z - 2, x - 10, y - 1, z - 2);
        command(test, "fill %d %d %d %d %d %d minecraft:wheat[age=0]", x - 12, y, z - 2, x - 10, y, z - 2);
        // Ice with a torch beside it
        command(test, "setblock %d %d %d minecraft:ice", x - 14, y, z + 13);
        command(test, "setblock %d %d %d minecraft:torch", x - 14, y, z + 14);
        // Wool to burn
        command(test, "fill %d %d %d %d %d %d minecraft:white_wool", x + 11, y, z + 10, x + 13, y + 1, z + 12);
    }

    // --- 7. The player's own play in survival

    private static void survival(SelfTest test, BlockPos lab) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        IntegratedServer server = minecraft.getSingleplayerServer();
        int x = lab.getX();
        int y = lab.getY();
        int z = lab.getZ();
        // A clear yard on the laboratory floor
        command(test, "fill %d %d %d %d %d %d minecraft:air", x - 2, y, z - 4, x + 9, y + 6, z + 3);
        test.command("time set 4000");
        test.command("weather clear");
        test.command("kill @e[type=!minecraft:player,distance=..48]");
        test.command("clear @s");
        flying(test, false, 0.05F);
        goTo(test, x + 3.5, y, z + 0.5, 180, 0);
        command(test, "spawnpoint @s %d %d %d", x + 3, y, z);
        regions.add(new Region("yard", lab.offset(-3, -2, -5), lab.offset(10, 6, 4), false));
        noteHud = true;
        test.command("gamemode survival");
        test.command("give @s minecraft:cooked_beef 8");
        test.command("give @s minecraft:oak_planks 16");
        ticks(test, 20);
        note(test, "standing in the yard");

        // Mining a block with the bare hand: the cracks spread over it for a second and a half
        BlockPos target = new BlockPos(x + 3, y + 1, z - 2);
        command(test, "fill %d %d %d %d %d %d minecraft:oak_log", target.getX(), y, target.getZ(), target.getX(), y + 1, target.getZ());
        // Whether there are any, not how far they have spread: the stage moves on a tick that live and playback count a tick apart
        askers.put("cracks in the block being mined", game -> cracks(game, target) >= 0 ? "cracked" : "whole");
        ticks(test, 10);
        note(test, "a log to mine, untouched");
        Vec3 eyes = test.onClient(() -> minecraft.player.getEyePosition());
        Vec3 aim = Vec3.atCenterOf(target).subtract(eyes);
        float yaw = (float) Math.toDegrees(Math.atan2(-aim.x, aim.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(aim.y, Math.sqrt(aim.x * aim.x + aim.z * aim.z)));
        drive = new Drive(yaw, pitch, false, false, false, true, false);
        ticks(test, 30);
        WorldProbe mining = note(test, "half way through a log");
        int stage = test.onClient(() -> cracks(minecraft, target));
        int swingsBefore = Recorder.get().recordingTick();
        try {
            test.waitFor(() -> {
                try {
                    return blockAt(test, target).isAir();
                } catch (Exception e) {
                    return false;
                }
            }, 20, "the log to be mined");
        } catch (Throwable stuck) {
            command(test, "setblock %d %d %d minecraft:air destroy", target.getX(), target.getY(), target.getZ());
        }
        drive = null;
        ticks(test, 12);
        note(test, "the log mined");
        askers.remove("cracks in the block being mined");
        minedBlock = target;
        minedByHand = stage >= 0;
        if ("whole".equals(mining.facts.get("cracks in the block being mined"))) {
            test.skip("the script could not mine by holding the attack key (the game window may be in the background): cracks on the recorder's own block were not checked");
        }
        test.log("real world: mining took " + (Recorder.get().recordingTick() - swingsBefore) + " more ticks after the note; the cracks were then at stage " + stage + " of 9");

        // Placing blocks
        test.command("item replace entity @s weapon.mainhand with minecraft:oak_planks 16");
        ticks(test, 5);
        for (int i = 0; i < 3; i++) {
            useOn(test, new BlockPos(x + 5 + i, y - 1, z - 2), Direction.UP);
            ticks(test, 5);
        }
        ticks(test, 8);
        note(test, "three planks placed");

        // Hunger, then a meal
        onServer(() -> {
            ServerPlayer player = server.getPlayerList().getPlayers().get(0);
            player.getFoodData().setFoodLevel(8);
            player.getFoodData().setSaturation(0);
            return null;
        });
        test.command("item replace entity @s weapon.mainhand with minecraft:cooked_beef 4");
        ticks(test, 10);
        note(test, "hungry");
        drive = new Drive(180, 0, false, false, false, false, true);
        ticks(test, 16);
        WorldProbe eating = note(test, "eating");
        ticks(test, 30);
        drive = null;
        ticks(test, 8);
        WorldProbe fed = note(test, "fed");
        if (!eating.recorder.state().contains("using")) {
            test.skip("the script could not eat by holding the use key: the recorder's eating in playback was not checked");
        }
        test.log("real world: hunger went " + (eating.hud == null ? "?" : eating.hud[3]) + " to " + (fed.hud == null ? "?" : fed.hud[3]));

        // What is in the hand, and the HUD, changing on the very tick a snapshot is taken. A snapshot holds the change, but
        // plain play never reads a snapshot it runs past, so the change has to be in the ticks as well
        int hits = 0;
        for (int attempt = 0; attempt < 5; attempt++) {
            test.command("item replace entity @s weapon.mainhand with minecraft:stick");
            ticks(test, 12);
            int boundary = lastSnapshotAt + 100;
            if (boundary - Recorder.get().recordingTick() < 8) {
                boundary += 100;
            }
            // A command sent while the recording stands at one tick reaches the game during that same tick, or the next
            int sendAt = boundary;
            long deadline = System.currentTimeMillis() + 15_000;
            while (Recorder.get().recordingTick() < sendAt && System.currentTimeMillis() < deadline) {
                Thread.sleep(1);
            }
            test.command("item replace entity @s weapon.mainhand with minecraft:air");
            test.command("experience add @s 1 levels");
            ticks(test, 30);
            boolean hit = handChangedAt == lastSnapshotAt;
            hits += hit ? 1 : 0;
            note(test, "the hand emptied and a level gained " + (hit ? "on the very tick of a snapshot" : "near a snapshot") + " (hand at tick " + handChangedAt + ", snapshot at "
                + lastSnapshotAt + ")");
        }
        if (hits == 0) {
            test.skip("in five tries no change of the recorder's hand landed on the very tick of a snapshot, so that such a change survives plain play was not shown this run");
        }
        test.command("item replace entity @s weapon.mainhand with minecraft:cooked_beef 2");
        ticks(test, 10);

        // A fall
        command(test, "tp @s %.2f %d %.2f", x + 3.5, y + 9, z + 0.5);
        ticks(test, 6);
        note(test, "falling");
        ticks(test, 30);
        WorldProbe fallen = note(test, "landed, hurt");
        test.log("real world: health after the fall " + (fallen.hud == null ? "?" : fallen.hud[0]));

        // Running out of air at the bottom of a well
        command(test, "fill %d %d %d %d %d %d minecraft:glass", x - 1, y, z - 1, x + 1, y + 3, z + 1);
        command(test, "fill %d %d %d %d %d %d minecraft:water", x, y, z, x, y + 2, z);
        command(test, "tp @s %.2f %d %.2f", x + 0.5, y, z + 0.5);
        ticks(test, 10);
        onServer(() -> {
            server.getPlayerList().getPlayers().get(0).setAirSupply(25);
            return null;
        });
        ticks(test, 12);
        note(test, "the last bubbles");
        ticks(test, 50);
        WorldProbe drowning = note(test, "drowning");
        test.log("real world: air " + (drowning.hud == null ? "?" : drowning.hud[4]) + ", health " + (drowning.hud == null ? "?" : drowning.hud[0]) + " while drowning");
        command(test, "tp @s %.2f %d %.2f 180 0", x + 3.5, y, z + 0.5);
        command(test, "fill %d %d %d %d %d %d minecraft:air", x - 1, y, z - 1, x + 1, y + 3, z + 1);

        // A zombie that can stand the sun, close enough to hit
        test.command("effect give @s minecraft:instant_health 1 3");
        command(test, "summon minecraft:zombie %d %d %d {CustomName:'\"Biter\"',PersistenceRequired:1b,ArmorItems:[{},{},{},{id:\"minecraft:leather_helmet\",count:1}]}", x + 2, y, z - 2);
        float[] healthBefore = {20};
        test.onClientRun(() -> healthBefore[0] = minecraft.player.getHealth());
        try {
            test.waitFor(() -> minecraft.player != null && minecraft.player.getHealth() < healthBefore[0] - 0.5F, 15, "the zombie to land a blow");
        } catch (Throwable late) {
            test.skip("the zombie did not hit the recorder within fifteen seconds: a mob's blow was not staged");
        }
        ticks(test, 3);
        note(test, "bitten");
        test.command("kill @e[type=minecraft:zombie,distance=..24]");
        ticks(test, 30);

        // Experience
        test.command("experience add @s 37 points");
        ticks(test, 10);
        WorldProbe wiser = note(test, "with experience");
        test.log("real world: experience level " + (wiser.hud == null ? "?" : wiser.hud[6]) + " progress " + (wiser.hud == null ? "?" : wiser.hud[7]));

        // A furnace lit and a crafting table looked at: their screens are the recorder's own business, the blocks are not
        BlockPos furnace = new BlockPos(x + 6, y, z + 2);
        command(test, "setblock %d %d %d minecraft:furnace[facing=north]", furnace.getX(), y, furnace.getZ());
        command(test, "setblock %d %d %d minecraft:crafting_table", furnace.getX() + 1, y, furnace.getZ());
        test.command("item replace entity @s weapon.mainhand with minecraft:air");
        command(test, "tp @s %.2f %d %.2f 0 30", furnace.getX() + 0.5, y, furnace.getZ() - 1.5);
        ticks(test, 10);
        useOn(test, furnace.east(), Direction.NORTH);
        ticks(test, 15);
        WorldProbe crafting = note(test, "at the crafting table");
        test.onClientRun(() -> minecraft.player.closeContainer());
        ticks(test, 5);
        useOn(test, furnace, Direction.NORTH);
        ticks(test, 10);
        command(test, "item replace block %d %d %d container.0 with minecraft:raw_iron 4", furnace.getX(), y, furnace.getZ());
        command(test, "item replace block %d %d %d container.1 with minecraft:coal 2", furnace.getX(), y, furnace.getZ());
        ticks(test, 20);
        WorldProbe smelting = note(test, "the furnace lit, its screen open");
        test.onClientRun(() -> minecraft.player.closeContainer());
        ticks(test, 10);
        note(test, "the furnace burning by itself");
        test.log("real world: live screens were '" + crafting.screen + "' at the crafting table and '" + smelting.screen + "' at the furnace");

        // A bed, and the night slept through
        BlockPos bed = new BlockPos(x + 1, y, z + 2);
        command(test, "setblock %d %d %d minecraft:red_bed[facing=east,part=foot]", bed.getX(), y, bed.getZ());
        command(test, "setblock %d %d %d minecraft:red_bed[facing=east,part=head]", bed.getX() + 1, y, bed.getZ());
        command(test, "tp @s %.2f %d %.2f 0 40", bed.getX() + 0.5, y, bed.getZ() - 1.2);
        test.command("time set 13000");
        ticks(test, 20);
        note(test, "bedtime");
        useOn(test, bed, Direction.UP);
        ticks(test, 40);
        WorldProbe asleep = note(test, "asleep");
        if (!asleep.recorder.state().contains("asleep")) {
            test.skip("the recorder could not get to sleep in the live game (" + asleep.recorder.state() + "): sleeping in playback was not checked");
        }
        try {
            test.waitFor(() -> minecraft.player != null && !minecraft.player.isSleeping(), 20, "the night to be slept through");
        } catch (Throwable late) {
            test.onClientRun(() -> minecraft.player.stopSleeping());
        }
        ticks(test, 20);
        WorldProbe woken = note(test, "woken in the morning");
        test.log("real world: time of day asleep " + asleep.dayTime % 24000 + ", on waking " + woken.dayTime % 24000);

        // Death, the things dropped, and the respawn
        test.command("give @s minecraft:diamond 3");
        ticks(test, 10);
        test.command("damage @s 1000 minecraft:generic");
        test.waitFor(() -> minecraft.screen instanceof DeathScreen, 15, "the death screen");
        ticks(test, 15);
        WorldProbe dead = note(test, "dead");
        long dropped = dead.entities.values().stream().filter(kind -> kind.equals("item")).count();
        test.log("real world: " + dropped + " dropped items lying about, live screen '" + dead.screen + "', recorder " + dead.recorder.state());
        ticks(test, 25);
        test.onClientRun(() -> {
            minecraft.player.respawn();
            minecraft.setScreen(null);
        });
        test.waitFor(() -> minecraft.player != null && minecraft.player.isAlive() && minecraft.screen == null, 30, "the respawn");
        ticks(test, 10);
        settle(test);
        ticks(test, 30);
        note(test, "respawned");
        ticks(test, 110);
        note(test, "a while after respawning");
        test.command("gamemode creative");
        noteHud = false;
        ticks(test, 10);
    }

    private static BlockPos minedBlock = null;
    private static boolean minedByHand = false;

    /** How far the cracks have spread over a block, 0 to 9, or -1 when it has none */
    private static int cracks(Minecraft minecraft, BlockPos pos) {
        try {
            java.lang.reflect.Field field = minecraft.levelRenderer.getClass().getDeclaredField("destroyingBlocks");
            field.setAccessible(true);
            int most = -1;
            for (Object progress : ((Map<?, ?>) field.get(minecraft.levelRenderer)).values()) {
                if (progress instanceof BlockDestructionProgress cracking && cracking.getPos().equals(pos)) {
                    most = Math.max(most, cracking.getProgress());
                }
            }
            return most;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- 2. Depth: a cave down in the deepslate, lit by a torch and dark again

    private static void cave(SelfTest test, BlockPos mineshaft) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        test.command("time set 6000");
        flying(test, true, 0.05F);
        int x = mineshaft.getX();
        int z = mineshaft.getZ();
        goTo(test, x + 0.5, surface(x, z, Heightmap.Types.MOTION_BLOCKING) + 20, z + 0.5, 0, 60);

        // Somewhere to stand, as deep as there is, beside lava when there is any
        BlockPos spot = test.onClient(() -> {
            ClientLevel level = minecraft.level;
            BlockPos best = null;
            int bestScore = Integer.MIN_VALUE;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int dx = -44; dx <= 44; dx++) {
                for (int dz = -44; dz <= 44; dz++) {
                    for (int py = -58; py <= 20; py++) {
                        pos.set(x + dx, py, z + dz);
                        if (!level.getBlockState(pos).isAir() || !level.getBlockState(pos.above()).isAir() || !level.getBlockState(pos.below()).isSolidRender(level, pos.below())) {
                            continue;
                        }
                        if (level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos) > 6) {
                            // Dark enough that a torch makes a difference
                            continue;
                        }
                        int score = -py * 2;
                        for (Direction side : Direction.Plane.HORIZONTAL) {
                            for (int far = 1; far <= 14; far++) {
                                BlockState there = level.getBlockState(pos.relative(side, far).below());
                                if (there.getFluidState().is(Fluids.LAVA) || level.getBlockState(pos.relative(side, far)).getFluidState().is(Fluids.LAVA)) {
                                    score += 60;
                                    break;
                                }
                                if (!level.getBlockState(pos.relative(side, far)).isAir()) {
                                    break;
                                }
                            }
                        }
                        if (score > bestScore) {
                            bestScore = score;
                            best = pos.immutable();
                        }
                    }
                }
            }
            return best;
        });
        if (spot == null) {
            test.skip("no cave to stand in was found under the mineshaft at " + mineshaft.toShortString() + ": the deep cave and its torch were not staged");
            return;
        }
        flying(test, false, 0.05F);
        goTo(test, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 0, 10);
        goTo(test, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, openYaw(test), 10);
        Region region = Region.around("cave", spot, 9, 5, 6, true);
        regions.add(region);
        ticks(test, 30);
        WorldProbe dark = note(test, "the cave, dark");
        test.log("real world: the cave is at " + spot.toShortString() + " in " + blockAt(test, spot.below()).getBlock() + "; lava and the rest nearby: " + nearby(dark, region));

        BlockPos torch = spot.east();
        command(test, "setblock %d %d %d minecraft:stone", torch.getX(), torch.getY() - 1, torch.getZ());
        command(test, "setblock %d %d %d minecraft:torch", torch.getX(), torch.getY(), torch.getZ());
        ticks(test, 12);
        WorldProbe lit = noteWithStill(test, "the cave by torchlight", "cave-torchlight");
        int at = region.index(spot);
        test.log("real world: block light where the recorder stands went from " + (dark.regionLight.get(region)[at] & 15) + " to " + (lit.regionLight.get(region)[at] & 15));
        ticks(test, 215);
        note(test, "the torch still burning, two snapshots later");
        command(test, "setblock %d %d %d minecraft:air", torch.getX(), torch.getY(), torch.getZ());
        ticks(test, 12);
        note(test, "the torch taken away");
        ticks(test, 130);
        note(test, "dark again, a snapshot later");
    }

    private static String nearby(WorldProbe probe, Region region) {
        Map<String, Integer> counts = new java.util.TreeMap<>();
        for (int id : probe.regionBlocks.get(region)) {
            String name = BuiltInRegistries.BLOCK.getKey(net.minecraft.world.level.block.Block.stateById(id).getBlock()).getPath();
            if (!name.equals("air") && !name.equals("cave_air")) {
                counts.merge(name, 1, Integer::sum);
            }
        }
        return counts.toString();
    }

    // --- 3. More structures

    private static void trialChamber(SelfTest test, BlockPos trial) throws Exception {
        test.command("time set 6000");
        flying(test, true, 0.05F);
        goTo(test, trial.getX() + 0.5, surface(trial.getX(), trial.getZ(), Heightmap.Types.MOTION_BLOCKING) + 20, trial.getZ() + 0.5, 0, 60);
        BlockPos spawner = nearestBlockEntity(test, new BlockPos(trial.getX(), -20, trial.getZ()), 6, "trial_spawner");
        BlockPos vault = nearestBlockEntity(test, new BlockPos(trial.getX(), -20, trial.getZ()), 6, "vault");
        if (spawner == null && vault == null) {
            test.skip("the trial chamber at " + trial.toShortString() + " showed no spawner or vault to the game: it was not staged");
            return;
        }
        BlockPos target = spawner != null ? spawner : vault;
        BlockPos stand = standNear(test, target, 5);
        if (stand == null) {
            stand = target.above();
        }
        goTo(test, stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, yawTowards(Vec3.atBottomCenterOf(stand), target.getX() + 0.5, target.getZ() + 0.5), 10);
        regions.add(Region.around("trial chamber", stand, 10, 5, 8, true));
        ticks(test, 40);
        noteWithStill(test, "in the trial chamber, by a " + (spawner != null ? "spawner" : "vault"), "trial-chamber");
        if (vault != null && spawner != null) {
            BlockPos beside = standNear(test, vault, 5);
            if (beside != null) {
                goTo(test, beside.getX() + 0.5, beside.getY(), beside.getZ() + 0.5, yawTowards(Vec3.atBottomCenterOf(beside), vault.getX() + 0.5, vault.getZ() + 0.5), 10);
                ticks(test, 40);
                note(test, "by a vault");
            }
        }
        ticks(test, 110);
        note(test, "the trial chamber, a snapshot later");
    }

    private static void ruinedPortal(SelfTest test, BlockPos ruin) throws Exception {
        flying(test, true, 0.05F);
        int ground = surface(ruin.getX(), ruin.getZ(), Heightmap.Types.MOTION_BLOCKING);
        goTo(test, ruin.getX() + 8.5, ground + 8, ruin.getZ() + 8.5, 135, 35);
        BlockPos chest = nearestBlockEntity(test, new BlockPos(ruin.getX(), ground, ruin.getZ()), 3, "chest");
        regions.add(Region.around("ruined portal", new BlockPos(ruin.getX() + 4, ground - 4, ruin.getZ() + 4), 14, 10, 14, false));
        ticks(test, 40);
        note(test, "the ruined portal" + (chest == null ? "" : ", its chest at " + chest.toShortString()));
        ticks(test, 110);
        note(test, "the ruined portal, a snapshot later");
    }

    // --- 2. Under water in an ocean

    private static void ocean(SelfTest test, BlockPos ocean, BlockPos wreck) throws Exception {
        test.command("time set 6000");
        test.command("weather clear");
        flying(test, true, 0.05F);
        int x = ocean.getX();
        int z = ocean.getZ();
        int floor = surface(x, z, Heightmap.Types.OCEAN_FLOOR);
        int top = surface(x, z, Heightmap.Types.WORLD_SURFACE);
        if (top - floor < 5) {
            test.skip("the ocean found at " + ocean.toShortString() + " is only " + (top - floor) + " deep there: under water was not staged");
            return;
        }
        int y = Math.min(top - 4, floor + 3);
        goTo(test, x + 0.5, y, z + 0.5, 0, 10);
        flying(test, true, 0.05F);
        // What grows and bubbles there, where the sea has not already put it
        command(test, "setblock %d %d %d minecraft:soul_sand", x + 2, floor - 1, z + 3);
        command(test, "setblock %d %d %d minecraft:magma_block", x - 2, floor - 1, z + 3);
        command(test, "fill %d %d %d %d %d %d minecraft:kelp_plant replace minecraft:water", x, floor, z + 4, x, Math.min(top - 3, floor + 4), z + 4);
        command(test, "setblock %d %d %d minecraft:kelp", x, Math.min(top - 2, floor + 5), z + 4);
        command(test, "summon minecraft:tropical_fish %d %d %d {CustomName:'\"Nemo\"',PersistenceRequired:1b}", x + 1, y, z + 3);
        command(test, "summon minecraft:drowned %d %d %d {CustomName:'\"Davy\"',PersistenceRequired:1b}", x - 3, floor, z + 5);
        for (int i = 0; i < 5; i++) {
            command(test, "summon minecraft:cod %d %d %d", x - 1 + i, y + 1, z + 5);
        }
        regions.add(Region.around("sea bed", new BlockPos(x, floor + 3, z + 2), 8, 5, Math.max(4, top - floor), true));
        ticks(test, 30);
        note(test, "under water, bubbles rising");
        ticks(test, 60);
        WorldProbe under = noteWithStill(test, "under water", "under-water");
        long fish = under.entities.values().stream().filter(kind -> kind.equals("cod") || kind.equals("tropical_fish") || kind.equals("salmon")).count();
        test.log("real world: " + fish + " fish, " + under.entities.values().stream().filter(kind -> kind.equals("drowned")).count() + " drowned, water " + (top - floor) + " deep; "
            + nearby(under, regions.get(0)));
        ticks(test, 110);
        note(test, "under water, a snapshot later");
        regions.clear();

        if (wreck != null && Math.sqrt(wreck.distSqr(new BlockPos(x, wreck.getY(), z))) < 1600) {
            int wreckTop = surface(wreck.getX(), wreck.getZ(), Heightmap.Types.WORLD_SURFACE);
            int wreckFloor = surface(wreck.getX(), wreck.getZ(), Heightmap.Types.OCEAN_FLOOR);
            goTo(test, wreck.getX() + 4.5, Math.min(wreckTop + 2, wreckFloor + 12), wreck.getZ() + 4.5, 135, 40);
            BlockPos chest = nearestBlockEntity(test, new BlockPos(wreck.getX(), wreckFloor, wreck.getZ()), 3, "chest");
            regions.add(Region.around("shipwreck", new BlockPos(wreck.getX() + 4, wreckFloor, wreck.getZ() + 4), 12, 8, 14, false));
            ticks(test, 40);
            note(test, "the shipwreck" + (chest == null ? ", no chest seen" : ", a chest at " + chest.toShortString()));
            ticks(test, 110);
            note(test, "the shipwreck, a snapshot later");
        } else {
            test.skip("no shipwreck within reach of the ocean in the generated world");
        }
    }

    // --- 2 and 4. A mountain top, the top of the world, and snow settling

    private static void mountain(SelfTest test, BlockPos cold, boolean peak) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        test.command("time set 6000");
        test.command("weather clear");
        flying(test, true, 0.05F);
        int x = cold.getX();
        int z = cold.getZ();
        int ground = surface(x, z, Heightmap.Types.MOTION_BLOCKING);
        goTo(test, x + 0.5, ground + 3, z + 0.5, 0, 15);
        ticks(test, 40);
        noteWithStill(test, peak ? "on the mountain top" : "on snowy ground", "mountain-top");

        // The very top of the world: a block in the last layer, and the light above it
        command(test, "tp @s %.2f 316 %.2f 0 60", x + 0.5, z + 0.5);
        command(test, "fill %d 318 %d %d 319 %d minecraft:glowstone", x - 1, z + 2, x + 1, z + 2);
        regions.add(new Region("the top of the world", new BlockPos(x - 4, 312, z - 2), new BlockPos(x + 4, 319, z + 6), true));
        ticks(test, 40);
        note(test, "at the build limit");
        regions.clear();

        // Snow settling on bare stone and ice forming round a pool, hurried along
        int y = ground + 8;
        command(test, "fill %d %d %d %d %d %d minecraft:stone", x - 7, y - 2, z - 7, x + 7, y - 1, z + 7);
        command(test, "fill %d %d %d %d %d %d minecraft:water", x - 2, y - 1, z - 2, x + 2, y - 1, z + 2);
        command(test, "tp @s %.2f %d %.2f 0 60", x + 0.5, y + 8, z + 0.5);
        Region slab = new Region("snow slab", new BlockPos(x - 8, y - 2, z - 8), new BlockPos(x + 8, y + 1, z + 8), true);
        regions.add(slab);
        test.command("weather rain");
        ticks(test, 60);
        WorldProbe bare = note(test, "bare stone under falling snow");
        test.command("tick sprint 1500");
        IntegratedServer server = minecraft.getSingleplayerServer();
        test.waitFor(() -> !server.tickRateManager().isSprinting(), 180, "the hurried snowfall");
        ticks(test, 30);
        WorldProbe snowed = noteWithStill(test, "snow settled, ice formed", "snow-settled");
        int snow = count(snowed, slab, "snow");
        int ice = count(snowed, slab, "ice");
        test.log("real world: on the slab " + snow + " snow layers and " + ice + " ice formed (before: " + count(bare, slab, "snow") + " and " + count(bare, slab, "ice") + "); rain "
            + snowed.rain);
        if (snow == 0 && ice == 0) {
            test.skip("no snow settled and no ice formed on the slab at " + cold.toShortString() + " within the recording: only its falling was recorded");
        }
        ticks(test, 110);
        note(test, "snow, a snapshot later");
        test.command("weather clear");
    }

    private static int count(WorldProbe probe, Region region, String block) {
        int count = 0;
        for (int id : probe.regionBlocks.get(region)) {
            if (BuiltInRegistries.BLOCK.getKey(net.minecraft.world.level.block.Block.stateById(id).getBlock()).getPath().equals(block)) {
                count++;
            }
        }
        return count;
    }

    // --- 5. The Nether and back, through a portal built and lit by hand

    private static void nether(SelfTest test, BlockPos lab) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        int x = lab.getX();
        int y = lab.getY();
        int z = lab.getZ();
        test.command("time set 6000");
        test.command("weather clear");
        flying(test, false, 0.05F);
        command(test, "execute in minecraft:overworld run tp @s %.2f %d %.2f 0 0", x - 2.0, y, z + 12.5);
        test.sleepTicks(10);
        settle(test);
        // The frame: four wide, five high, its foot in the floor so the way in is level
        command(test, "fill %d %d %d %d %d %d minecraft:air", x - 5, y, z + 12, x, y + 4, z + 15);
        command(test, "fill %d %d %d %d %d %d minecraft:obsidian", x - 4, y - 1, z + 14, x - 1, y + 3, z + 14);
        command(test, "fill %d %d %d %d %d %d minecraft:air", x - 3, y, z + 14, x - 2, y + 2, z + 14);
        Region frame = new Region("portal", new BlockPos(x - 6, y - 2, z + 10), new BlockPos(x + 1, y + 5, z + 16), true);
        regions.add(frame);
        test.command("item replace entity @s weapon.mainhand with minecraft:flint_and_steel");
        ticks(test, 20);
        note(test, "the frame built, not yet lit");
        useOn(test, new BlockPos(x - 3, y - 1, z + 14), Direction.UP);
        ticks(test, 10);
        if (!blockAt(test, new BlockPos(x - 3, y, z + 14)).is(Blocks.NETHER_PORTAL)) {
            test.log("real world: the flint and steel did not light the portal; lighting it by command");
            command(test, "setblock %d %d %d minecraft:fire", x - 3, y, z + 14);
            ticks(test, 10);
        }
        check(blockAt(test, new BlockPos(x - 3, y, z + 14)).is(Blocks.NETHER_PORTAL), "the portal did not light");
        ticks(test, 30);
        WorldProbe lit = note(test, "the portal lit");
        test.log("real world: " + count(lit, frame, "nether_portal") + " portal blocks in the frame");
        Vec3 before = playerAt(test);

        // Through it on foot
        int leftAt = Recorder.get().recordingTick();
        drive = new Drive(0, 0, true, false, false, false, false);
        test.waitFor(() -> minecraft.level != null && minecraft.level.dimension() == Level.NETHER, 30, "the walk through the portal into the Nether");
        drive = null;
        test.waitFor(() -> minecraft.screen == null && minecraft.player != null, 60, "the Nether to appear");
        test.sleepTicks(20);
        settle(test);
        Vec3 arrived = playerAt(test);
        regions.clear();
        Region far = Region.around("the far side of the portal", BlockPos.containing(arrived), 8, 4, 8, true);
        regions.add(far);
        ticks(test, 30);
        WorldProbe there = note(test, "just through the portal");
        test.log("real world: walked through at about tick " + leftAt + " from " + String.format(Locale.ROOT, "%.1f %.1f %.1f", before.x, before.y, before.z) + " to "
            + String.format(Locale.ROOT, "%.1f %.1f %.1f", arrived.x, arrived.y, arrived.z) + " in the Nether; " + count(there, far, "nether_portal") + " portal blocks on the far side");

        // A little way through the Nether on the wing
        flying(test, true, 0.05F);
        command(test, "summon minecraft:ghast %.1f %.1f %.1f {CustomName:'\"Casper\"',PersistenceRequired:1b}", arrived.x + 12, arrived.y + 10, arrived.z + 12);
        command(test, "summon minecraft:piglin %.1f %.1f %.1f {CustomName:'\"Porky\"',PersistenceRequired:1b,IsImmuneToZombification:1b}", arrived.x + 2, arrived.y, arrived.z + 1);
        for (int i = 0; i < 3; i++) {
            // The Nether is mostly rock: each leg starts from the nearest open place thirty blocks on, facing the way that is clearest
            BlockPos from = openSpot(test, BlockPos.containing(arrived.x + 30 * (i + 1), arrived.y, arrived.z), 14);
            if (from != null) {
                command(test, "tp @s %.2f %.2f %.2f", from.getX() + 0.5, from.getY() + 1.0, from.getZ() + 0.5);
                test.sleepTicks(5);
            }
            flying(test, true, 0.05F);
            drive = new Drive(openYaw(test), 0, true, true, false, false, false);
            ticks(test, 50);
            note(test, "flying through the Nether " + (i + 1));
        }
        drive = null;
        Vec3 flewTo = playerAt(test);
        test.log(String.format(Locale.ROOT, "real world: flew %.0f blocks through the Nether", flewTo.distanceTo(arrived)));
        regions.clear();

        BlockPos here = BlockPos.containing(flewTo);
        BlockPos valley = biome(test, here, 1200, "soul sand valley", found -> found.is(Biomes.SOUL_SAND_VALLEY));
        if (valley != null) {
            visitNether(test, valley, "the soul sand valley", "nether");
        } else {
            test.skip("no soul sand valley within reach of the portal in the Nether");
            noteWithStill(test, "the Nether", "nether");
        }
        BlockPos fortress = structure(test, here, BuiltinStructures.FORTRESS);
        if (fortress != null && Math.sqrt(fortress.distSqr(new BlockPos(here.getX(), fortress.getY(), here.getZ()))) < 1200) {
            visitNether(test, new BlockPos(fortress.getX() + 8, 64, fortress.getZ() + 8), "the fortress", null);
        } else {
            test.skip("no Nether fortress within reach of the portal");
        }

        // And home, by stepping back into the portal on that side
        flying(test, true, 0.05F);
        command(test, "tp @s %.2f %.2f %.2f", arrived.x + 3, arrived.y, arrived.z);
        test.sleepTicks(10);
        settle(test);
        ticks(test, 30);
        note(test, "back at the portal in the Nether");
        command(test, "tp @s %.2f %.2f %.2f", arrived.x, arrived.y, arrived.z);
        test.waitFor(() -> minecraft.level != null && minecraft.level.dimension() == Level.OVERWORLD, 40, "the step back through the portal");
        test.waitFor(() -> minecraft.screen == null && minecraft.player != null, 60, "the Overworld to appear again");
        test.sleepTicks(20);
        settle(test);
        Vec3 home = playerAt(test);
        regions.add(frame);
        ticks(test, 30);
        note(test, "home again");
        test.log(String.format(Locale.ROOT, "real world: came out at %.1f %.1f %.1f, %.1f blocks from where the walk in began", home.x, home.y, home.z, home.distanceTo(before)));
        ticks(test, 130);
        note(test, "home, a snapshot later");
    }

    /** The nearest place in the Nether with a floor, three blocks of air over it and some room around; null when there is none within reach */
    private static BlockPos openSpot(SelfTest test, BlockPos near, int reach) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        return test.onClient(() -> {
            ClientLevel level = minecraft.level;
            BlockPos best = null;
            double bestScore = Double.MAX_VALUE;
            for (BlockPos pos : BlockPos.betweenClosed(near.offset(-reach, -40, -reach), near.offset(reach, 40, reach))) {
                if (pos.getY() < 33 || pos.getY() > 116 || !level.getBlockState(pos).isAir() || !level.getBlockState(pos.above()).isAir() || !level.getBlockState(pos.above(2)).isAir()
                    || !level.getBlockState(pos.below()).isSolidRender(level, pos.below())) {
                    continue;
                }
                int room = 0;
                for (Direction side : Direction.Plane.HORIZONTAL) {
                    for (int far = 1; far <= 8 && level.getBlockState(pos.above().relative(side, far)).isAir(); far++) {
                        room++;
                    }
                }
                // Near, but a roomy place a little further off is better than a crack in the rock close by
                double score = Math.sqrt(pos.distSqr(near)) - room * 1.5;
                if (score < bestScore) {
                    bestScore = score;
                    best = pos.immutable();
                }
            }
            return best;
        });
    }

    /** The way to face, of eight, that looks furthest before meeting anything solid */
    private static float openYaw(SelfTest test) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        return test.onClient(() -> {
            Vec3 eyes = minecraft.player.getEyePosition();
            float best = 0;
            int furthest = -1;
            for (int turn = 0; turn < 8; turn++) {
                float yaw = turn * 45.0F;
                Vec3 ahead = Vec3.directionFromRotation(0, yaw);
                int clear = 0;
                while (clear < 64 && !minecraft.level.getBlockState(BlockPos.containing(eyes.add(ahead.scale(clear + 1)))).isSolidRender(minecraft.level, BlockPos.containing(eyes.add(ahead.scale(clear + 1))))) {
                    clear++;
                }
                if (clear > furthest) {
                    furthest = clear;
                    best = yaw;
                }
            }
            return best;
        });
    }

    private static void visitNether(SelfTest test, BlockPos where, String what, String still) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        flying(test, true, 0.05F);
        goTo(test, where.getX() + 0.5, Math.max(40, Math.min(110, where.getY())), where.getZ() + 0.5, 0, 20);
        // Somewhere open to look from, if the place the search gave is inside the rock
        BlockPos open = openSpot(test, playerBlock(test), 20);
        if (open != null) {
            goTo(test, open.getX() + 0.5, open.getY() + 1, open.getZ() + 0.5, 0, 15);
            goTo(test, open.getX() + 0.5, open.getY() + 1, open.getZ() + 0.5, openYaw(test), 15);
        }
        regions.clear();
        regions.add(Region.around(what, playerBlock(test), 8, 5, 6, true));
        ticks(test, 40);
        WorldProbe seen = still != null ? noteWithStill(test, what, still) : note(test, what);
        test.log("real world: " + what + ": " + nearby(seen, regions.get(0)));
        ticks(test, 110);
        note(test, what + ", a snapshot later");
        regions.clear();
    }

    // ------------------------------------------------------------------ Playback

    private static int recorderEntity = -1;
    private static int leftoverNotes = 0;

    private static void play(SelfTest test) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        JsonObject none = new JsonObject();
        check(!notes.isEmpty(), "nothing was noted in the generated world");

        // Opening, to the first frame of the recording's world
        int framesBefore = frames;
        long started = System.nanoTime();
        test.tool("replay_open", args("path", recording.toString()));
        long deadline = System.currentTimeMillis() + 120_000;
        int[] frameWhenLoaded = {-1};
        while (System.currentTimeMillis() < deadline && Playback.lastError() == null) {
            if (Playback.loaded() && frameWhenLoaded[0] < 0) {
                frameWhenLoaded[0] = frames;
            }
            if (frameWhenLoaded[0] >= 0 && frames > frameWhenLoaded[0]) {
                break;
            }
            Thread.sleep(2);
        }
        long opened = (System.nanoTime() - started) / 1_000_000;
        check(Playback.lastError() == null && Playback.loaded(), "the generated world's recording did not open: " + Playback.lastError());
        table.add("opening the recording to its first frame: " + opened + " ms (" + (frames - framesBefore) + " frames drawn meanwhile); the plan asks for under 5000" + (opened > 5000 ? " -- OVER" : ""));
        if (opened > 15_000) {
            fault(test, "opening the recording took " + opened + " ms");
        }
        test.sleepTicks(10);
        RecordingManifest manifest = Playback.manifest();
        int total = manifest.totalTicks;
        List<Integer> segmentStarts = manifest.segments.stream().map(segment -> segment.startTick).toList();

        // 1. Every noted tick by a jump forwards from the one before
        Timings forwards = new Timings();
        int at = 0;
        String dimension = dimensionNow(test, minecraft);
        for (WorldProbe live : notes) {
            long took = jump(test, live.tick);
            forwards.add(took, at, live.tick, segmentStarts, live.dimension.equals(dimension));
            dimension = live.dimension;
            at = live.tick;
            compare(test, live, "after a jump forwards");
            for (Map.Entry<String, Integer> entry : stills.entrySet()) {
                if (entry.getValue() == live.tick) {
                    playbackStill(test, entry.getKey());
                }
            }
        }
        // 2. Every noted tick by a jump backwards from the one after
        Timings backwards = new Timings();
        long toEnd = jump(test, total);
        at = total;
        dimension = dimensionNow(test, minecraft);
        for (int i = notes.size() - 1; i >= 0; i--) {
            WorldProbe live = notes.get(i);
            long took = jump(test, live.tick);
            backwards.add(took, at, live.tick, segmentStarts, live.dimension.equals(dimension));
            dimension = live.dimension;
            at = live.tick;
            compare(test, live, "after a jump backwards");
        }
        // Short hops inside one stretch between snapshots, both ways, at a few places
        Timings hops = new Timings();
        for (int i = 2; i < notes.size(); i += Math.max(1, notes.size() / 8)) {
            int tick = notes.get(i).tick;
            jump(test, tick);
            long back = jump(test, Math.max(0, tick - 25));
            hops.add(back, tick, Math.max(0, tick - 25), segmentStarts, true);
            long on = jump(test, tick - 5);
            hops.add(on, Math.max(0, tick - 25), tick - 5, segmentStarts, true);
        }
        table.add("jumps forwards between noted ticks: " + forwards);
        table.add("jumps backwards between noted ticks: " + backwards);
        table.add("short hops of 20 to 25 ticks: " + hops);
        table.add("jump from the last noted tick to the end of the recording: " + toEnd + " ms");
        if (rebuilds > 0) {
            table.add(String.format(Locale.ROOT, "jumps that read a snapshot: %d, the reading %.0f ms on average; of the snapshots' chunks %.0f %% were left as they were and %.1f a jump were built again; the slowest jump %s",
                rebuilds, rebuildMillis / rebuilds, rebuildKept * 100.0 / Math.max(1, rebuildKept + rebuildBuilt), rebuildBuilt / (double) rebuilds, slowestJumpWas));
        }
        long longest = Math.max(Math.max(forwards.longest, backwards.longest), hops.longest);
        table.add("longest jump of all: " + longest + " ms; the plan asks for under 2000, and under 500 inside one stretch" + (longest > 2000 || hops.longestWithin > 500 ? " -- OVER" : ""));
        if (longest > 6000) {
            fault(test, "a jump took " + longest + " ms, three times what the plan allows");
        }

        // 3. Plain play from the start to the end, stopping at every noted tick
        jump(test, 0);
        test.tool("replay_set_speed", args("tick_rate", 100));
        long playStarted = System.currentTimeMillis();
        for (WorldProbe live : notes) {
            if (live.tick <= Playback.position()) {
                if (live.tick == Playback.position()) {
                    compare(test, live, "reached by play");
                }
                continue;
            }
            int target = live.tick;
            test.onClientRun(() -> Playback.playTo(target));
            test.waitFor(() -> (Playback.paused() && Playback.position() == target && !Playback.jumping()) || Playback.lastError() != null, 240, "play to reach tick " + target);
            check(Playback.lastError() == null, "playback stopped while playing to tick " + target + ": " + Playback.lastError());
            test.sleepTicks(3);
            compare(test, live, "reached by play");
        }
        test.tool("replay_set_speed", args("tick_rate", 20));
        table.add("playing the whole recording through at five times speed, with a stop at each noted tick: " + (System.currentTimeMillis() - playStarted) / 1000 + " s");
    }

    private static String dimensionNow(SelfTest test, Minecraft minecraft) throws Exception {
        return test.onClient(() -> minecraft.level == null ? "" : minecraft.level.dimension().location().toString());
    }

    /** How long some jumps took, by whether they stayed inside one stretch between snapshots */
    private static final class Timings {
        private final List<Long> within = new ArrayList<>();
        private final List<Long> across = new ArrayList<>();
        private final List<Long> betweenDimensions = new ArrayList<>();
        long longest = 0;
        long longestWithin = 0;

        void add(long millis, int from, int to, List<Integer> segmentStarts, boolean sameDimension) {
            int fromSegment = 0, toSegment = 0;
            for (int i = 0; i < segmentStarts.size(); i++) {
                if (segmentStarts.get(i) <= from) {
                    fromSegment = i;
                }
                if (segmentStarts.get(i) <= to) {
                    toSegment = i;
                }
            }
            this.longest = Math.max(this.longest, millis);
            if (!sameDimension) {
                this.betweenDimensions.add(millis);
            } else if (fromSegment == toSegment) {
                this.within.add(millis);
                this.longestWithin = Math.max(this.longestWithin, millis);
            } else {
                this.across.add(millis);
            }
        }

        private static String of(List<Long> times) {
            if (times.isEmpty()) {
                return "none";
            }
            List<Long> sorted = times.stream().sorted().toList();
            return times.size() + " (median " + sorted.get(sorted.size() / 2) + " ms, longest " + sorted.get(sorted.size() - 1) + " ms)";
        }

        @Override
        public String toString() {
            return "inside one stretch " + of(this.within) + "; across snapshots " + of(this.across) + "; between dimensions " + of(this.betweenDimensions);
        }
    }

    private static double[] lastRebuild = null;
    private static int rebuilds = 0;
    private static double rebuildMillis = 0;
    private static long rebuildKept = 0;
    private static long rebuildBuilt = 0;
    private static long slowestJump = 0;
    private static String slowestJumpWas = "";

    /** Jumps, waits until the recording stands at exactly that tick, and says how long that took */
    private static long jump(SelfTest test, int tick) throws Exception {
        long started = System.nanoTime();
        test.onClientRun(() -> Playback.jumpTo(tick, true));
        long deadline = System.currentTimeMillis() + 120_000;
        while (Playback.jumping() || Playback.position() != tick) {
            check(System.currentTimeMillis() < deadline, "gave up waiting for the jump to tick " + tick);
            check(Playback.lastError() == null, "playback stopped during the jump to tick " + tick + ": " + Playback.lastError());
            Thread.sleep(2);
        }
        long took = (System.nanoTime() - started) / 1_000_000;
        double[] rebuild = Playback.lastRebuild();
        if (rebuild != null && rebuild != lastRebuild) {
            lastRebuild = rebuild;
            rebuilds++;
            rebuildMillis += rebuild[0];
            rebuildKept += (long) rebuild[1];
            rebuildBuilt += (long) rebuild[2];
            if (took > slowestJump) {
                slowestJump = took;
                slowestJumpWas = String.format(Locale.ROOT, "to tick %d: %d ms, of which reading the snapshot %.0f ms (%d chunks left as they were, %d built)", tick, took, rebuild[0],
                    (int) rebuild[1], (int) rebuild[2]);
            }
        }
        // A few drawn frames, so what is compared is what a person would see
        int before = frames;
        long until = System.currentTimeMillis() + 2000;
        while (frames < before + 3 && System.currentTimeMillis() < until) {
            Thread.sleep(5);
        }
        return took;
    }

    private static void compare(SelfTest test, WorldProbe live, String how) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        WorldProbe played = test.onClient(() -> {
            Entity recorder = WorldProbe.recorderIn(minecraft, Playback.manifest().recorderId);
            if (recorder != null) {
                recorderEntity = recorder.getId();
            }
            WorldProbe taken = WorldProbe.take(minecraft, live, recorder, Playback.position(), live.label, new ArrayList<>(live.regionBlocks.keySet()), live.askers);
            if (live.hud != null) {
                taken.hud = Extras.recordedHud();
            }
            return taken;
        });
        List<String> differences = WorldProbe.differences(live, played);
        // Nothing of the recorder's own screens may open for the person watching (PLAY-4)
        if (played.screen.startsWith("net.minecraft.")) {
            differences.add("a screen is open in playback: " + played.screen);
        }
        for (String difference : differences) {
            if (difference.startsWith(WorldProbe.LET_OFF)) {
                leftoverNotes++;
                test.log("real world: tick " + live.tick + " (" + live.label + ") " + how + ": " + difference);
            } else {
                fault(test, "tick " + live.tick + " (" + live.label + ") " + how + ": " + difference);
                if (difference.contains("in playback that the live game did not hold")) {
                    // Which they are and what playback knows of them, for whoever has to find out why
                    double[] rebuild = Playback.lastRebuild();
                    StringBuilder which = new StringBuilder();
                    int listed = 0, all = 0;
                    for (long key : played.blocks.keySet()) {
                        if (!live.blocks.containsKey(key)) {
                            all++;
                            boolean lists = Playback.listsChunk(net.minecraft.world.level.ChunkPos.getX(key), net.minecraft.world.level.ChunkPos.getZ(key));
                            listed += lists ? 1 : 0;
                            if (all <= 6) {
                                which.append(' ').append(net.minecraft.world.level.ChunkPos.getX(key)).append(',').append(net.minecraft.world.level.ChunkPos.getZ(key)).append(lists ? " listed;" : " not listed;");
                            }
                        }
                    }
                    test.log("real world: of those " + all + " chunks playback lists " + listed + ":" + which + " the last snapshot read was of tick "
                        + (rebuild == null ? "none" : (int) rebuild[4] + ", with " + (int) rebuild[1] + " chunks left as they were, " + (int) rebuild[2] + " built and " + (int) rebuild[3] + " let go"));
                }
            }
        }
    }

    /** Playback's picture at the tick it stands at, through the recorder's eyes, beside the live one of the same name */
    private static void playbackStill(SelfTest test, String name) throws Exception {
        Path file = folder.resolve(name + "-playback.png");
        Files.deleteIfExists(file);
        try {
            test.tool("camera_spectate", args("entity_id", recorderEntity));
            // The ground is built over many frames once the view has moved somewhere new: wait until some is drawn and no more is coming
            long until = System.currentTimeMillis() + 20_000;
            int steady = 0;
            int last = -1;
            while (System.currentTimeMillis() < until && steady < 12) {
                int before = frames;
                test.waitFor(() -> frames >= before + 2, 10, "frames through the recorder's eyes");
                int[] drawn = test.onClient(() -> new int[]{minecraft().levelRenderer.countRenderedSections(), minecraft().levelRenderer.hasRenderedAllSections() ? 1 : 0});
                steady = drawn[0] > 0 && drawn[1] == 1 && drawn[0] == last ? steady + 1 : 0;
                last = drawn[0];
            }
            test.tool("export_screenshot", args("output_path", file.toString(), "width", STILL_WIDTH, "height", STILL_HEIGHT, "overwrite", true, "no_gui", true));
            test.waitFor(() -> !Export.active(), 60, "the still " + name);
            test.tool("camera_spectate", new JsonObject());
            if (!Files.exists(file)) {
                fault(test, "playback's still '" + name + "' was not written");
                return;
            }
            BufferedImage live = ImageIO.read(folder.resolve(name + "-live.png").toFile());
            BufferedImage played = ImageIO.read(file.toFile());
            long sum = 0;
            int far = 0;
            for (int y = 0; y < STILL_HEIGHT; y++) {
                for (int x = 0; x < STILL_WIDTH; x++) {
                    int p = live.getRGB(x, y);
                    int q = played.getRGB(x, y);
                    int off = Math.abs((p >> 16 & 0xFF) - (q >> 16 & 0xFF)) + Math.abs((p >> 8 & 0xFF) - (q >> 8 & 0xFF)) + Math.abs((p & 0xFF) - (q & 0xFF));
                    sum += off;
                    if (off > 96) {
                        far++;
                    }
                }
            }
            table.add(String.format(Locale.ROOT, "still '%s' at tick %d: live and playback differ by %.1f of 255 a channel on average, %.1f %% of the picture by a lot (%s and %s)", name,
                stills.get(name), sum / 3.0 / (STILL_WIDTH * STILL_HEIGHT), far * 100.0 / (STILL_WIDTH * STILL_HEIGHT), name + "-live.png", name + "-playback.png"));
        } catch (Throwable t) {
            fault(test, "playback's still '" + name + "' could not be taken: " + t);
        }
    }

}
