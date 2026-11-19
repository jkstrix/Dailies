package comedymonkey.dailies.capture;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.config.DailiesConfig;
import comedymonkey.dailies.file.PacketPieces;
import comedymonkey.dailies.file.RecordingFile;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.file.RecordingWriter;
import comedymonkey.dailies.file.Records;
import comedymonkey.dailies.moments.Moments;
import comedymonkey.dailies.ui.Notices;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.world.InteractionHand;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Makes recordings (specification chapter II). All of its state belongs to the client thread; the only other thread
 * involved is the {@link Spool}, which is handed finished bytes.
 */
public final class Recorder {

    public enum State {
        IDLE, RECORDING, PAUSED
    }

    /** What an unfinished recording is called on disk until it is finished properly */
    public static final String WORKING_EXTENSION = ".dailies.recording";
    /** The record that says an entity has just teleported and is to be shown at its new place at once */
    public static final String SNAP = "dailies:snap";

    /**
     * The specification asked for five minutes (REC-20). Going back in playback starts from the stored world before, so
     * at five minutes a step back replays up to five minutes; the owner asked for going back to be quick however long
     * the session, and five seconds is what the scripted tests have always run with. Since 18 November two: dragging the
     * playhead back replays up to the whole stretch since the stored world before, about a millisecond a tick, and at
     * five seconds the worst step back of a drag took near two hundred milliseconds. Unchanged chunks are stored once and
     * only named again, so a stored world costs little more than what has changed; what each costs is measured and logged.
     */
    private static final int DEFAULT_SEGMENT_TICKS = 2 * 20;
    private static final int PART_TICKS = 40;
    private static final int PART_BYTES = 1024 * 1024;
    private static final DateTimeFormatter NAME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss");

    private static final Recorder INSTANCE = new Recorder();
    private static final List<Runnable> TICK_HOOKS = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Something to run at the end of every recorded tick, on the client thread: for extensions that write state of their own */
    public static void everyRecordedTick(Runnable hook) {
        TICK_HOOKS.add(hook);
    }

    public static Recorder get() {
        return INSTANCE;
    }

    private volatile State state = State.IDLE;
    /** True while nothing may be written: before the first snapshot, between worlds, after a change of dimension */
    private volatile boolean waitingForSnapshot = true;
    /** True from the start of a recording until the game has applied everything that had arrived before it */
    private volatile boolean settling = false;
    private volatile String id = null;
    private volatile int tick = 0;

    private Spool spool;
    private Path workingFile;
    private RecordingManifest manifest;
    private final Records.Writer part = new Records.Writer();
    private int pendingTicks = 0;
    private int ticksSinceFlush = 0;
    private int segmentStartTick = 0;
    private boolean partBeginsSegment = false;
    private GameCodec codec;
    private final OwnPlayer own = new OwnPlayer();
    private final Set<String> sharedSent = new HashSet<>();
    private final Set<String> warnedPayloads = new HashSet<>();
    private boolean startWhenInWorld = false;
    private int segmentTicks = DEFAULT_SEGMENT_TICKS;
    private final comedymonkey.dailies.file.TimeIndex.Encoder timeEncoder = new comedymonkey.dailies.file.TimeIndex.Encoder();
    private long segmentReal = 0;
    private long segmentGame = 0;
    private long firstReal = 0;
    private long lastReal = 0;
    private final Set<String> players = new java.util.TreeSet<>();

    private Recorder() {
    }

    public static void init() {
        PackCapture.init();
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            try {
                INSTANCE.onClientTick();
            } catch (Throwable t) {
                INSTANCE.fail(t);
            }
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> {
            if (INSTANCE.state == State.IDLE && !Host.get().inPlayback() && Host.get().setting("autoStart", false)) {
                INSTANCE.startWhenInWorld = true;
            }
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            INSTANCE.startWhenInWorld = false;
            if (INSTANCE.state != State.IDLE && !Host.get().inPlayback() && Host.get().setting("autoFinish", true)) {
                try {
                    INSTANCE.finish(null);
                } catch (Throwable t) {
                    INSTANCE.fail(t);
                }
            }
        });
    }

    public static Path folder() {
        return Host.get().dataDirectory().resolve("recordings");
    }

    // --- What others may ask ---

    public State state() {
        return this.state;
    }

    public @Nullable String recordingId() {
        return this.state == State.IDLE ? null : this.id;
    }

    public int recordingTick() {
        return this.state == State.IDLE ? -1 : this.tick;
    }

    /** Whether packets are being written right now. Read from the network thread */
    boolean capturing() {
        return this.state == State.RECORDING && !this.waitingForSnapshot;
    }

    // --- Starting and stopping (REC-1) ---

    public void start() throws IOException {
        Minecraft minecraft = Minecraft.getInstance();
        if (this.state != State.IDLE) {
            throw new IllegalStateException("A recording is already being made");
        }
        if (Host.get().inPlayback()) {
            throw new IllegalStateException("A recording cannot be made while watching one");
        }
        ClientPacketListener listener = minecraft.getConnection();
        if (minecraft.level == null || minecraft.player == null || listener == null) {
            throw new IllegalStateException("Join a world or a server first");
        }

        RecordingManifest manifest = new RecordingManifest();
        manifest.id = UUID.randomUUID().toString();
        manifest.name = LocalDateTime.now().format(NAME_FORMAT);
        manifest.created = System.currentTimeMillis();
        manifest.gameVersion = SharedConstants.getCurrentVersion().getName();
        manifest.dataVersion = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        manifest.protocolVersion = SharedConstants.getProtocolVersion();
        manifest.modVersion = Host.get().modVersion();
        manifest.recorderName = minecraft.player.getGameProfile().getName();
        manifest.recorderId = minecraft.player.getUUID().toString();
        manifest.singleplayer = minecraft.hasSingleplayerServer();
        if (minecraft.getSingleplayerServer() != null) {
            manifest.worldName = minecraft.getSingleplayerServer().getWorldData().getLevelName();
        } else {
            ServerData server = minecraft.getCurrentServer();
            manifest.worldName = server == null ? "" : server.name == null || server.name.isBlank() ? server.ip : server.name;
        }
        // The self test is a mod of the test games only: it is no part of what a recording needs to play back
        ModList.get().getMods().stream().filter(mod -> !mod.getModId().equals(comedymonkey.dailies.ScriptedRun.SELF_TEST_MOD))
            .forEach(mod -> manifest.mods.add(new RecordingManifest.ModEntry(mod.getModId(), mod.getVersion().toString())));

        Path file = folder().resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")) + WORKING_EXTENSION);
        this.spool = new Spool(RecordingWriter.create(file, manifest));
        this.workingFile = file;
        this.manifest = manifest;
        this.part.take();
        this.pendingTicks = 0;
        this.ticksSinceFlush = 0;
        this.segmentStartTick = 0;
        this.partBeginsSegment = false;
        this.sharedSent.clear();
        this.warnedPayloads.clear();
        this.players.clear();
        this.firstReal = 0;
        this.lastReal = 0;
        this.worldsStored = 0;
        this.storingNanos = 0L;
        this.longestStoreNanos = 0L;
        this.firstStoreNanos = 0L;
        java.util.Arrays.fill(this.storingParts, 0.0);
        this.chunksNamed = 0;
        this.chunksEncoded = 0;
        this.longestStore = null;
        this.segmentTicks =Math.max(20, Integer.getInteger("dailies.test.segmentTicks", Host.get().setting("snapshotSeconds", DEFAULT_SEGMENT_TICKS / 20) * 20));
        this.tick = 0;
        this.id = manifest.id;
        this.waitingForSnapshot = true;
        Snapshots.forgetAll();
        // Packets that reached the game before this moment and have not been applied yet are not seen by the recorder. They
        // are waiting in the game's queue; the first snapshot is taken once the queue has got as far as this, so that
        // every one of them is in it
        this.settling = true;
        minecraft.tell(() -> this.settling = false);
        this.state = State.RECORDING;
        Host.LOGGER.info("Dailies: recording {} started", manifest.id);
        Notices.show(Component.translatable("dailies.notice.started"));
    }

    public void pause() {
        if (this.state != State.RECORDING) {
            throw new IllegalStateException(this.state == State.PAUSED ? "The recording is already paused" : "No recording is being made");
        }
        this.flushPart();
        this.state = State.PAUSED;
        Notices.show(Component.translatable("dailies.notice.paused"));
    }

    public void resume() {
        if (this.state != State.PAUSED) {
            throw new IllegalStateException(this.state == State.RECORDING ? "The recording is not paused" : "No recording is being made");
        }
        // The world has moved on while nothing was captured, so what follows starts from a fresh snapshot (REC-3). Which
        // chunks changed meanwhile was still noted (see onPacket), so only those are described afresh
        this.waitingForSnapshot = true;
        this.state = State.RECORDING;
        Notices.show(Component.translatable("dailies.notice.resumed"));
    }

    // --- What storing the world cost the game, each time: it is done on the game's thread, between two frames ---
    private int worldsStored = 0;
    private long storingNanos = 0L;
    private long longestStoreNanos = 0L;

    /** Where storing the world spent its time, summed over the recording: light and the world's description, chunks, entities, mods' own */
    private final double[] storingParts = new double[4];
    private int chunksNamed = 0, chunksEncoded = 0;
    private Snapshots.Cost longestStore = null;

    private void storedWorld(long nanos) {
        this.worldsStored++;
        this.storingNanos += nanos;
        Snapshots.Cost cost = Snapshots.last;
        if (cost != null) {
            this.storingParts[0] += cost.world();
            this.storingParts[1] += cost.chunkMillis();
            this.storingParts[2] += cost.entityMillis();
            this.storingParts[3] += cost.extensions();
            this.chunksNamed += cost.chunks();
            this.chunksEncoded += cost.encoded();
        }
        if (this.worldsStored == 1) {
            // The first describes every chunk there is, once, as the recording starts: the pause that counts is the
            // one that comes back every couple of seconds while playing
            this.firstStoreNanos = nanos;
            return;
        }
        if (nanos > this.longestStoreNanos) {
            this.longestStore = cost;
        }
        this.longestStoreNanos = Math.max(this.longestStoreNanos, nanos);
    }

    private long firstStoreNanos = 0L;

    /** Where storing the world spent its time, for the log: what can be taken off the game's thread depends on it */
    private String storingBreakdown() {
        int stored = Math.max(1, this.worldsStored);
        String parts = String.format(java.util.Locale.ROOT, "on average: settling light and describing the world %.2f ms, chunks %.2f ms (%d named, %d encoded afresh a time), entities %.2f ms, mods' own %.2f ms",
            this.storingParts[0] / stored, this.storingParts[1] / stored, this.chunksNamed / stored, this.chunksEncoded / stored, this.storingParts[2] / stored, this.storingParts[3] / stored);
        Snapshots.Cost worst = this.longestStore;
        if (worst != null) {
            parts += String.format(java.util.Locale.ROOT, "; the longest: light and world %.2f ms, chunks %.2f ms (%d named, %d encoded), entities %.2f ms (%d), mods' own %.2f ms",
                worst.world(), worst.chunkMillis(), worst.chunks(), worst.encoded(), worst.entityMillis(), worst.entities(), worst.extensions());
        }
        return parts + "; chunks described afresh, by why: " + Snapshots.REASONS;
    }

    /** Worlds stored in the recording being made, their average and longest cost on the game's thread in milliseconds */
    public double[] storingCost() {
        return new double[]{this.worldsStored, this.worldsStored == 0 ? 0.0 : this.storingNanos / 1e6 / this.worldsStored, this.longestStoreNanos / 1e6};
    }

    /**
     * Ends the recording and saves it.
     *
     * @param name what to call it, or null for the default built from the date and time
     * @return the saved file, once the writer has finished with it
     */
    public CompletableFuture<Path> finish(@Nullable String name) {
        if (this.state == State.IDLE) {
            throw new IllegalStateException("No recording is being made");
        }
        if (this.state == State.RECORDING) {
            this.flushPart();
        }
        double[] storing = this.storingCost();
        Host.LOGGER.info(String.format(java.util.Locale.ROOT, "Dailies: the recording stored the world %d times, every %d ticks, taking the game %.2f ms each on average and %.2f ms at the longest after the first (the first, of every chunk, %.2f ms); %s",
            (int) storing[0], this.segmentTicks, storing[1], storing[2], this.firstStoreNanos / 1e6, this.storingBreakdown()));
        String wanted = name == null || name.isBlank() ? this.manifest.name : name.trim();
        Path working = this.workingFile;
        Spool spool = this.spool;
        List<String> everyone = new ArrayList<>(this.players);
        long realStart = this.firstReal;
        long realEnd = this.lastReal;
        this.state = State.IDLE;
        this.spool = null;
        this.id = null;

        CompletableFuture<Path> saved = new CompletableFuture<>();
        spool.add(new Spool.Job() {
            @Override
            public void run(RecordingWriter writer) throws Exception {
                try {
                    Throwable earlier = spool.failure();
                    if (earlier != null) {
                        throw new IOException("The recording could not be written", earlier);
                    }
                    writer.manifest().name = wanted;
                    writer.manifest().players = everyone;
                    writer.manifest().realStart = realStart;
                    writer.manifest().realEnd = realEnd;
                    writer.finish();
                    Path target = freeFile(working.getParent(), wanted);
                    Files.move(working, target);
                    comedymonkey.dailies.library.Library.changed(target);
                    saved.complete(target);
                } catch (Throwable t) {
                    try {
                        writer.close();
                    } catch (IOException ignored) {
                        // The file stays as it is and can be recovered
                    }
                    saved.completeExceptionally(t);
                    throw t;
                }
            }

            @Override
            public boolean last() {
                return true;
            }
        });
        saved.whenComplete((file, error) -> Minecraft.getInstance().execute(() -> {
            if (error == null) {
                Host.LOGGER.info("Dailies: recording saved as {}", file);
                Notices.show(Component.translatable("dailies.notice.finished", file.getFileName().toString()));
            } else {
                Notices.show(Component.translatable("dailies.notice.failed"));
            }
        }));
        return saved;
    }

    /** Ends the recording and deletes it */
    public void cancel() {
        if (this.state == State.IDLE) {
            throw new IllegalStateException("No recording is being made");
        }
        Spool spool = this.spool;
        this.state = State.IDLE;
        this.spool = null;
        this.id = null;
        this.part.take();
        spool.add(new Spool.Job() {
            @Override
            public void run(RecordingWriter writer) throws Exception {
                writer.discard();
            }

            @Override
            public boolean last() {
                return true;
            }
        });
        Notices.show(Component.translatable("dailies.notice.cancelled"));
    }

    /** Something went wrong: stop recording, keep what was written, leave the game running. Any thread */
    void fail(Throwable problem) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> this.fail(problem));
            return;
        }
        Host.LOGGER.error("Dailies: recording failed and has been stopped. What was written so far is kept and can be recovered", problem);
        if (this.state == State.IDLE) {
            return;
        }
        Spool spool = this.spool;
        this.state = State.IDLE;
        this.spool = null;
        this.id = null;
        this.part.take();
        spool.add(new Spool.Job() {
            @Override
            public void run(RecordingWriter writer) throws Exception {
                writer.close();
            }

            @Override
            public boolean last() {
                return true;
            }
        });
        Notices.show(Component.translatable("dailies.notice.failed"));
    }

    // --- Markers (REC-30, REC-31) ---

    /**
     * @param colour 0xRRGGBB
     */
    public void addMarker(int colour, @Nullable String description, boolean savePosition) {
        if (this.state == State.IDLE) {
            throw new IllegalStateException("No recording is being made");
        }
        RecordingManifest.Marker marker = new RecordingManifest.Marker();
        marker.tick = this.tick;
        marker.colour = colour & 0xFFFFFF;
        marker.description = description == null ? "" : description;
        LocalPlayer player = Minecraft.getInstance().player;
        if (savePosition && player != null) {
            marker.position = new double[]{player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()};
            marker.dimension = player.level().dimension().location().toString();
        }
        // The manifest belongs to the writer thread once recording has started
        this.spool.add(writer -> {
            writer.manifest().markers.add(marker);
            writer.writeManifest();
        });
        Moments.onMarker(description, marker.colour);
    }

    // --- The clock ---

    private void onClientTick() throws IOException {
        Minecraft minecraft = Minecraft.getInstance();
        if (this.startWhenInWorld && this.state == State.IDLE && minecraft.level != null && minecraft.player != null && minecraft.getConnection() != null) {
            this.startWhenInWorld = false;
            this.start();
        }
        if (this.state == State.IDLE) {
            return;
        }
        // Looked at while paused too: a write that failed then would otherwise go unnoticed until recording went on, and
        // the markers added meanwhile would be dropped without a word
        Throwable failure = this.spool.failure();
        if (failure != null) {
            this.fail(failure);
            return;
        }
        if (this.state != State.RECORDING) {
            return;
        }
        ClientPacketListener listener = minecraft.getConnection();
        if (minecraft.level == null || minecraft.player == null || listener == null) {
            // Between worlds. The recording stays open and carries on from a snapshot of the next one (REC-2)
            this.waitingForSnapshot = true;
            Snapshots.forget();
            return;
        }
        if (minecraft.isPaused() || this.settling) {
            return;
        }
        if (!this.spool.hasRoom()) {
            Host.LOGGER.warn("Dailies: the disk cannot keep up with the recording, so it has been paused");
            this.pause();
            Notices.show(Component.translatable("dailies.notice.disk_slow"));
            return;
        }
        if (this.waitingForSnapshot || this.tick - this.segmentStartTick >= this.segmentTicks) {
            this.beginSegment(minecraft, listener);
        }
        this.own.tick(minecraft.player, 20 / Math.max(1, Math.min(20, Host.get().setting("recorderUpdatesPerSecond", 20))), this::write);
        if (this.own.takeJumped()) {
            LocalPlayer player = minecraft.player;
            java.nio.ByteBuffer snap = java.nio.ByteBuffer.allocate(4 + 24 + 8);
            snap.putInt(player.getId()).putDouble(player.getX()).putDouble(player.getY()).putDouble(player.getZ()).putFloat(player.getYRot()).putFloat(player.getXRot());
            this.writeExtension(SNAP, snap.array());
        }

        this.tick++;
        this.pendingTicks++;
        this.writeTime(System.currentTimeMillis(), minecraft.level.getGameTime());
        // What the world looks like now that this tick is over belongs to the tick count just reached
        for (Runnable hook : TICK_HOOKS) {
            hook.run();
        }
        if (++this.ticksSinceFlush >= PART_TICKS || this.part.size() >= PART_BYTES) {
            this.flushPart();
        }
    }

    private void beginSegment(Minecraft minecraft, ClientPacketListener listener) {
        this.flushPart();
        this.segmentStartTick = this.tick;
        this.partBeginsSegment = true;
        // The segment's times are taken before the world is stored: storing chunks afresh hands what has been gathered to
        // the writer partway through (Out.sharedPackets), and the part that begins the segment carries these times into
        // the time index. Taken after, that part had the last segment's times, or the last recording's
        this.segmentReal = System.currentTimeMillis();
        this.segmentGame = minecraft.level.getGameTime();
        this.codec = new GameCodec(listener.registryAccess(), listener.getConnectionType());
        this.own.reset();
        this.part.number(Records.SNAPSHOT_BEGIN, this.tick);
        if (this.waitingForSnapshot) {
            this.part.record(Records.DISCONTINUITY);
            this.own.forgetShown();
        }
        Snapshots.ownShown = this.own.shown();
        long storing = System.nanoTime();
        Snapshots.write(this.live, minecraft, listener, this.codec);
        this.storedWorld(System.nanoTime() - storing);
        // Where Distant Horizons keeps this world's far terrain, so playback can show it from a copy of its own. Asked
        // here, written into the manifest by the writer, whose it is
        java.util.Map<String, String> farTerrain = comedymonkey.dailies.compat.distanthorizons.DistantHorizonsCompat.liveFolders();
        if (!farTerrain.isEmpty() && this.spool != null) {
            this.spool.add(new Spool.Job() {
                @Override
                public void run(RecordingWriter writer) {
                    writer.manifest().distantHorizonsFolders.putAll(farTerrain);
                }

                @Override
                public long bytes() {
                    return 0;
                }
            });
        }
        for (net.minecraft.client.multiplayer.PlayerInfo info : listener.getOnlinePlayers()) {
            this.players.add(info.getProfile().getName());
        }
        // What a server sends once the first chunks are on their way: it lets the game stop showing its loading screen
        this.write(new net.minecraft.network.protocol.game.ClientboundGameEventPacket(net.minecraft.network.protocol.game.ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START, 0.0F));
        this.part.record(Records.SNAPSHOT_END);
        // The times of the segment's first tick, in full: each segment's time index can be read without any other
        this.timeEncoder.reset();
        this.writeTime(this.segmentReal, this.segmentGame);
        this.waitingForSnapshot = false;
    }

    /** The real time and game time of the tick just reached (specification FILE-16) */
    private void writeTime(long real, long game) {
        this.flushTicks();
        this.part.record(Records.TIME, this.timeEncoder.next(real, game));
        if (this.firstReal == 0) {
            this.firstReal = real;
        }
        this.lastReal = real;
    }

    /** Hands what has been gathered to the writer */
    private void flushPart() {
        this.flushTicks();
        this.ticksSinceFlush = 0;
        if (this.part.size() == 0 && !this.partBeginsSegment) {
            return;
        }
        byte[] bytes = this.part.take();
        int endTick = this.tick;
        boolean begins = this.partBeginsSegment;
        int startTick = this.segmentStartTick;
        long startReal = this.segmentReal;
        long startGame = this.segmentGame;
        this.partBeginsSegment = false;
        this.spool.add(new Spool.Job() {
            @Override
            public void run(RecordingWriter writer) throws Exception {
                if (begins) {
                    writer.beginSegment(startTick);
                    writer.manifest().times.add(new RecordingManifest.SegmentTime(writer.segment(), startReal, startGame));
                }
                writer.appendToSegment(bytes, 0, bytes.length, endTick);
                writer.sync();
            }

            @Override
            public long bytes() {
                return bytes.length;
            }
        });
    }

    private void flushTicks() {
        if (this.pendingTicks > 0) {
            this.part.number(Records.TICK, this.pendingTicks);
            this.pendingTicks = 0;
        }
    }

    // --- What is written ---

    /**
     * How a snapshot, or anything else on the client thread, adds to a recording. The recording being made is one
     * place it can go; a snapshot taken from playback, for a trimmed recording, is another
     */
    public interface Out {
        void packet(Packet<? super ClientGamePacketListener> packet);

        /**
         * Names again a packet already stored ({@link Records#SHARED_PACKET}: stored once however often it is named), or
         * other shared data, by the name it was stored under
         */
        void sharedRef(int kind, byte[] hash);

        /**
         * Stores a packet that is likely to recur unchanged, such as a chunk, in the pieces its shape is cut into
         * ({@link ChunkPieces}), and names it here
         *
         * @return what to pass to {@link #sharedRef} to write the same packet again without its bytes
         */
        byte[] sharedPacket(byte[] encoded, ChunkPieces.Shape shape);

        /** A packet stored as one piece */
        default byte[] sharedPacket(byte[] encoded) {
            return this.sharedPacket(encoded, ChunkPieces.Shape.WHOLE);
        }

        /**
         * Packets stored once and named, as {@link #sharedPacket} does each, written here in this order; each name is set
         * once it is known. The recording being made does the encoding, cutting up, naming and storing on its writer's
         * thread, off the game's; this way of writing does them at once
         */
        default void sharedPackets(List<Packet<? super ClientGamePacketListener>> packets, List<java.util.concurrent.atomic.AtomicReference<byte[]>> names, GameCodec codec) {
            for (int i = 0; i < packets.size(); i++) {
                if (packets.get(i) == null) {
                    // Already stored: named again, in its place among the others
                    this.sharedRef(Records.SHARED_PACKET, names.get(i).get());
                } else {
                    names.get(i).set(this.sharedPacket(codec.encode(packets.get(i)), shapeOf(packets.get(i))));
                }
            }
        }

        /** How a packet is cut up to be stored: a chunk and light by section, anything else whole */
        static ChunkPieces.Shape shapeOf(Packet<?> packet) {
            return packet instanceof ClientboundLevelChunkWithLightPacket ? ChunkPieces.Shape.CHUNK
                : packet instanceof net.minecraft.network.protocol.game.ClientboundLightUpdatePacket ? ChunkPieces.Shape.LIGHT : ChunkPieces.Shape.WHOLE;
        }

        /** Data stored once and referred to by a record of the given kind */
        void sharedRecord(int kind, byte[] data);

        /** A whole packet exactly as it arrived */
        void frame(byte[] frame);

        void payloadFrame(String id, byte[] frame);

        void extension(String id, byte[] data);

        void bundleBegin();

        void bundleEnd();
    }

    private final LiveOut live = new LiveOut();
    /** Chunks the writer could not turn into bytes, in this game: only the first is logged. Writer thread */
    private static int chunkFailures = 0;

    private final class LiveOut implements Out {
        public void packet(Packet<? super ClientGamePacketListener> packet) {
            Recorder.this.write(packet);
        }

        /** A packet that is likely to recur unchanged, such as a chunk: it is stored once however often it is written */
        public void sharedRef(int kind, byte[] hash) {
            Recorder.this.flushTicks();
            Recorder.this.part.record(kind, hash);
        }

        /**
         * Keeps data in the recording without writing it into the ticks, once however often it is asked: registries for a
         * snapshot to name, say. Named here, and stored by the writer under that name without working it out again
         *
         * @return its name
         */
        byte[] stored(byte[] data) {
            byte[] hash = RecordingWriter.hashOf(data);
            if (Recorder.this.sharedSent.add(HexFormat.of().formatHex(hash))) {
                Recorder.this.spool.add(new Spool.Job() {
                    @Override
                    public void run(RecordingWriter writer) throws Exception {
                        writer.putShared(data, hash);
                    }

                    @Override
                    public long bytes() {
                        return data.length;
                    }
                });
            }
            return hash;
        }

        /**
         * Keeps a packet in the recording in its pieces, once however often it is asked: a chunk as it arrived, for the
         * snapshots that follow to name. Cutting it up and naming the pieces reads its bytes once, as naming it whole did;
         * the writer stores whichever pieces it does not hold yet, without copying them out of the packet first
         *
         * @return the name of its piece list
         */
        byte[] storedPacket(byte[] frame, ChunkPieces.Shape shape) {
            PacketPieces.Split split = ChunkPieces.split(frame, shape);
            if (Recorder.this.sharedSent.add(HexFormat.of().formatHex(split.name()))) {
                Recorder.this.spool.add(new Spool.Job() {
                    @Override
                    public void run(RecordingWriter writer) throws Exception {
                        store(writer, split);
                    }

                    @Override
                    public long bytes() {
                        return frame.length;
                    }
                });
            }
            return split.name();
        }

        /** @return what to pass to {@link #sharedRef} to write the same packet again without its bytes */
        public byte[] sharedPacket(byte[] encoded, ChunkPieces.Shape shape) {
            byte[] hash = this.storedPacket(encoded, shape);
            Recorder.this.flushTicks();
            Recorder.this.part.record(Records.SHARED_PACKET, hash);
            return hash;
        }

        /**
         * The chunks a snapshot describes afresh are the most of what storing the world cost the game: turning them into
         * bytes, naming them by their SHA-256 and storing them took about a third of it in new terrain. What has been
         * written so far goes to the writer first, then these, encoded, cut up, named and stored there, so the recording
         * keeps its order; what is written after goes in the next part
         */
        @Override
        public void sharedPackets(List<Packet<? super ClientGamePacketListener>> packets, List<java.util.concurrent.atomic.AtomicReference<byte[]>> names, GameCodec codec) {
            Recorder.this.flushPart();
            int endTick = Recorder.this.tick;
            List<Packet<? super ClientGamePacketListener>> made = new ArrayList<>(packets);
            List<java.util.concurrent.atomic.AtomicReference<byte[]>> naming = new ArrayList<>(names);
            Recorder.this.spool.add(new Spool.Job() {
                @Override
                public void run(RecordingWriter writer) throws Exception {
                    Records.Writer records = new Records.Writer();
                    for (int i = 0; i < made.size(); i++) {
                        if (made.get(i) == null) {
                            // Already stored (a chunk whose light alone is written afresh): named again, in its place
                            byte[] known = naming.get(i).get();
                            if (known != null) {
                                records.record(Records.SHARED_PACKET, known);
                            }
                            continue;
                        }
                        byte[] encoded;
                        try {
                            encoded = codec.encode(made.get(i));
                        } catch (Throwable t) {
                            // One chunk that cannot be turned into bytes (a mod's block entity changing as it was read, say)
                            // is left out of this stored world rather than stop the recording. It keeps no name, so the next
                            // snapshot describes it afresh
                            if (chunkFailures++ == 0) {
                                Host.LOGGER.warn("Dailies: a chunk could not be written into the recording and is left out of this stored world; the next has it. Said once", t);
                            }
                            continue;
                        }
                        // Cut up by section: whatever has not changed since it was last stored, as it arrived or by a
                        // snapshot, has the same name and is not stored again
                        PacketPieces.Split split = ChunkPieces.split(encoded, Out.shapeOf(made.get(i)));
                        store(writer, split);
                        records.record(Records.SHARED_PACKET, split.name());
                        naming.get(i).set(split.name());
                    }
                    byte[] bytes = records.take();
                    writer.appendToSegment(bytes, 0, bytes.length, endTick);
                }

                @Override
                public long bytes() {
                    // About what a chunk comes to, for the writer's queue to judge how much is waiting
                    return made.size() * 40_000L;
                }
            });
        }

        /** Data stored once and referred to by a record of the given kind */
        public void sharedRecord(int kind, byte[] data) {
            byte[] hash = this.stored(data);
            Recorder.this.flushTicks();
            Recorder.this.part.record(kind, hash);
        }

        /** A whole packet exactly as it arrived */
        public void frame(byte[] frame) {
            Recorder.this.flushTicks();
            Recorder.this.part.record(Records.PACKET, frame);
        }

        public void payloadFrame(String id, byte[] frame) {
            Recorder.this.flushTicks();
            Recorder.this.part.named(Records.PAYLOAD, id, frame, 0, frame.length);
        }

        public void extension(String id, byte[] data) {
            Recorder.this.flushTicks();
            Recorder.this.part.named(Records.EXTENSION, id, data, 0, data.length);
        }

        public void bundleBegin() {
            Recorder.this.flushTicks();
            Recorder.this.part.record(Records.BUNDLE_BEGIN);
        }

        public void bundleEnd() {
            Recorder.this.part.record(Records.BUNDLE_END);
        }
    }

    /**
     * Stores the pieces of a packet that the file does not hold yet, each list after the parts it names and the packet's
     * own list last, so a crash part way leaves nothing that names what is not there. Writer thread
     */
    private static void store(RecordingWriter writer, PacketPieces.Split split) throws IOException {
        for (PacketPieces.Blob blob : split.blobs()) {
            writer.putShared(blob.bytes(), blob.from(), blob.length(), blob.name());
        }
    }

    private void write(Packet<? super ClientGamePacketListener> packet) {
        byte[] encoded = this.codec.encode(packet);
        this.flushTicks();
        this.part.record(Records.PACKET, encoded);
    }

    /** Adds state from outside the vanilla world to the recording as it happens (specification chapter VII). Client thread */
    public void writeExtension(String id, byte[] data) {
        if (this.capturing()) {
            this.flushTicks();
            this.part.named(Records.EXTENSION, id, data, 0, data.length);
        }
    }

    /**
     * Records a mod message that reached the game some other way than the normal connection, as if it had arrived
     * there now. Does nothing unless a recording is capturing. Client thread
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void writePayload(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        if (!this.capturing()) {
            return;
        }
        String id = payload.type().id().toString();
        try {
            net.minecraft.network.codec.StreamCodec codec = net.neoforged.neoforge.network.registration.NetworkRegistry.getCodec(payload.type().id(),
                net.minecraft.network.ConnectionProtocol.PLAY, net.minecraft.network.protocol.PacketFlow.CLIENTBOUND);
            if (codec == null) {
                throw new IllegalStateException("no codec is registered for it");
            }
            byte[] frame = this.codec.payloadFrame(payload.type().id(), buffer -> codec.encode(buffer, payload));
            this.flushTicks();
            this.part.named(Records.PAYLOAD, id, frame, 0, frame.length);
        } catch (Throwable t) {
            if (this.warnedPayloads.add(id)) {
                Host.LOGGER.warn("Dailies: mod message {} cannot be recorded and is being left out. Said once per kind", id, t);
            }
        }
    }

    /** Hands the writer thread a piece of work of its own, done in turn with everything else that is written. Client thread */
    void spool(Spool.Job job) {
        Spool spool = this.spool;
        if (spool != null) {
            spool.add(job);
        }
    }

    void onOwnSwing(LocalPlayer player, InteractionHand hand) {
        if (this.capturing() && player == Minecraft.getInstance().player) {
            this.write(OwnPlayer.swing(player, hand));
        }
    }

    /** What the recorder's own act set off in the world (a block breaking up, say), in the form the server sends it to everyone else. Client thread */
    void onOwnEvent(net.minecraft.world.entity.player.Player player, int type, net.minecraft.core.BlockPos pos, int data) {
        Minecraft minecraft = Minecraft.getInstance();
        if (this.capturing() && minecraft.isSameThread() && player == minecraft.player) {
            this.write(new net.minecraft.network.protocol.game.ClientboundLevelEventPacket(type, pos, data, false));
        }
    }

    /**
     * A sound of the recorder's own making: their footsteps, a block they placed, a door they opened. The game plays it
     * for itself and the server sends it to everyone else, so it is written here in the form everyone else gets it.
     * {@code entity} is the entity the sound follows, or null for one at a place. Client thread
     */
    void onOwnSound(net.minecraft.world.entity.player.Player player, @Nullable net.minecraft.world.entity.Entity entity, double x, double y, double z,
                    net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent> sound, net.minecraft.sounds.SoundSource source, float volume, float pitch, long seed) {
        Minecraft minecraft = Minecraft.getInstance();
        if (this.capturing() && minecraft.isSameThread() && player == minecraft.player) {
            this.write(entity != null ? new net.minecraft.network.protocol.game.ClientboundSoundEntityPacket(sound, source, entity, volume, pitch, seed)
                : new net.minecraft.network.protocol.game.ClientboundSoundPacket(sound, source, x, y, z, volume, pitch, seed));
        }
    }

    /**
     * The cracks on a block someone is mining have spread or gone. A server tells every player but the miner, so the
     * recorder's own mining is written here in the form the server gives everyone else. Client thread
     */
    void onCracks(int breaker, net.minecraft.core.BlockPos pos, int stage) {
        Minecraft minecraft = Minecraft.getInstance();
        if (this.capturing() && minecraft.isSameThread() && minecraft.player != null && breaker == minecraft.player.getId() && this.own.cracked(pos, stage)) {
            this.write(new net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket(breaker, pos, stage));
        }
    }

    /** The game is about to apply a packet. Client thread */
    @SuppressWarnings({"unchecked", "rawtypes"})
    void onPacket(Packet<?> packet, List<byte[]> frames) {
        if (this.state == State.IDLE) {
            return;
        }
        if (!this.capturing()) {
            // Paused, or waiting for the snapshot of a new world: nothing is written, but what changes is still noted, so
            // that the snapshot to come describes afresh only the chunks that are no longer as they were stored
            if (packet instanceof ClientboundBundlePacket bundle) {
                int index = 1;
                for (Packet<?> part : bundle.subPackets()) {
                    this.noted(part, index < frames.size() - 1 ? frames.get(index) : null);
                    index++;
                }
            } else {
                this.noted(packet, frames.get(frames.size() - 1));
            }
            return;
        }
        if (packet instanceof ClientboundBundlePacket bundle) {
            List<Packet<?>> parts = new ArrayList<>();
            bundle.subPackets().forEach(parts::add);
            if (frames.size() != parts.size() + 2) {
                Host.LOGGER.warn("Dailies: a bundle of {} packets arrived as {} frames and was not recorded", parts.size(), frames.size());
                return;
            }
            this.flushTicks();
            this.part.record(Records.BUNDLE_BEGIN);
            for (int i = 0; i < parts.size(); i++) {
                this.onSinglePacket(parts.get(i), frames.get(i + 1));
            }
            this.part.record(Records.BUNDLE_END);
        } else {
            this.onSinglePacket(packet, frames.get(frames.size() - 1));
        }
    }

    private void noted(Packet<?> packet, @Nullable byte[] frame) {
        if (packet instanceof ClientboundLoginPacket || packet instanceof ClientboundRespawnPacket || packet instanceof ClientboundStartConfigurationPacket) {
            Snapshots.forget();
            return;
        }
        Snapshots.applied(packet);
        if (frame != null && this.state == State.RECORDING && packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
            // After a respawn or a change of dimension a whole new world of chunks arrives before the snapshot of it is taken.
            // Each is kept exactly as it came, so the snapshot names it; describing all of them afresh in that one tick held
            // the game for forty milliseconds. Not while paused: a long pause can pass any number of chunks that are never wanted
            Snapshots.arrived(chunk.getX(), chunk.getZ(), this.live.storedPacket(frame, ChunkPieces.Shape.CHUNK));
        }
    }

    private void onSinglePacket(Packet<?> packet, byte[] frame) {
        if (packet instanceof ClientboundLoginPacket || packet instanceof ClientboundRespawnPacket || packet instanceof ClientboundStartConfigurationPacket) {
            // A new world, or the same one rebuilt. The next tick describes it from scratch in a new segment (REC-17)
            this.flushPart();
            this.waitingForSnapshot = true;
            Snapshots.forget();
            if (packet instanceof ClientboundRespawnPacket respawn && Host.get().setting("markDimensionChanges", true)) {
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft.level != null && respawn.commonPlayerSpawnInfo().dimension() != minecraft.level.dimension()) {
                    this.addMarker(0xAA55FF, respawn.commonPlayerSpawnInfo().dimension().location().toString(), false);
                }
            }
            return;
        }
        if (this.waitingForSnapshot) {
            return;
        }
        Snapshots.applied(packet);
        if (PacketRules.dropped(packet)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        this.flushTicks();
        if (packet instanceof ClientboundCustomPayloadPacket payload) {
            // Kept as the bytes that arrived, with its id, without understanding it (REC-15)
            this.part.named(Records.PAYLOAD, payload.payload().type().id().toString(), frame, 0, frame.length);
        } else if (packet instanceof ClientboundPlayerChatPacket chat) {
            // The words are kept; the signature and the session that go with them are not (REC-11)
            Component content = chat.unsignedContent() != null ? chat.unsignedContent() : Component.literal(chat.body().content());
            this.write(new ClientboundDisguisedChatPacket(content, chat.chatType()));
        } else if (packet instanceof ClientboundPlayerInfoUpdatePacket info && info.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.INITIALIZE_CHAT)) {
            EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions = EnumSet.copyOf(info.actions());
            actions.remove(ClientboundPlayerInfoUpdatePacket.Action.INITIALIZE_CHAT);
            if (!actions.isEmpty()) {
                this.write(PlayerInfoPackets.build(this.codec, actions, info.entries()));
            }
        } else if (packet instanceof ClientboundGameEventPacket event && PacketRules.privateEvent(event)) {
            return;
        } else if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
            // A chunk is stored once however often it is sent, and the snapshots that follow name it for as long as nothing
            // changes it. Before, a chunk met while travelling was stored here and then again, described afresh, by the next
            // snapshot. It is stored by section, so a snapshot that does describe it afresh names the sections stored here again
            Snapshots.arrived(chunk.getX(), chunk.getZ(), this.live.sharedPacket(frame, ChunkPieces.Shape.CHUNK));
        } else if (packet instanceof ClientboundSetEntityMotionPacket motion && minecraft.player != null && motion.getId() == minecraft.player.getId()) {
            // A push on the recorder's own body. Where they actually went is recorded from the player itself
            return;
        } else {
            this.part.record(Records.PACKET, frame);
        }
    }

    // --- Recordings left unfinished by a crash (REC-6) ---

    public static List<Path> unfinished() {
        Path folder = folder();
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        Path beingWritten = INSTANCE.state == State.IDLE ? null : INSTANCE.workingFile;
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(file -> file.getFileName().toString().endsWith(WORKING_EXTENSION) && !file.equals(beingWritten)).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Repairs an unfinished recording and gives it a proper name. Reads and writes the file: not for the client thread */
    public static Path recover(Path working) throws IOException {
        RecordingManifest manifest = RecordingFile.recover(working);
        Path target = freeFile(working.getParent(), manifest.name.isBlank() ? "Recovered" : manifest.name + " (recovered)");
        Files.move(working, target);
        comedymonkey.dailies.library.Library.changed(target);
        return target;
    }

    /** A file in the folder for a recording of this name that is not taken yet */
    public static Path freeFile(Path folder, String name) {
        String safe = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        while (safe.endsWith(".")) {
            safe = safe.substring(0, safe.length() - 1);
        }
        if (safe.isEmpty()) {
            safe = "Recording";
        }
        Path file = folder.resolve(safe + RecordingFile.EXTENSION);
        for (int i = 2; Files.exists(file); i++) {
            file = folder.resolve(safe + " (" + i + ")" + RecordingFile.EXTENSION);
        }
        return file;
    }

}
