package comedymonkey.dailies.capture;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.config.DailiesConfig;
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
     * the session, and five seconds is what the scripted tests have always run with.
     */
    private static final int DEFAULT_SEGMENT_TICKS = 5 * 20;
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

    private Recorder() {
    }

    public static void init() {
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
        ModList.get().getMods().forEach(mod -> manifest.mods.add(new RecordingManifest.ModEntry(mod.getModId(), mod.getVersion().toString())));

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
        this.segmentTicks = Math.max(20, Integer.getInteger("dailies.test.segmentTicks", Host.get().setting("snapshotSeconds", DEFAULT_SEGMENT_TICKS / 20) * 20));
        this.tick = 0;
        this.id = manifest.id;
        this.waitingForSnapshot = true;
        Snapshots.forget();
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
        // The world has moved on while nothing was captured, so what follows starts from a fresh snapshot (REC-3)
        this.waitingForSnapshot = true;
        Snapshots.forget();
        this.state = State.RECORDING;
        Notices.show(Component.translatable("dailies.notice.resumed"));
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
        String wanted = name == null || name.isBlank() ? this.manifest.name : name.trim();
        Path working = this.workingFile;
        Spool spool = this.spool;
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
                    writer.finish();
                    Path target = freeFile(working.getParent(), wanted);
                    Files.move(working, target);
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
        if (this.state != State.RECORDING) {
            return;
        }
        Throwable failure = this.spool.failure();
        if (failure != null) {
            this.fail(failure);
            return;
        }
        ClientPacketListener listener = minecraft.getConnection();
        if (minecraft.level == null || minecraft.player == null || listener == null) {
            // Between worlds. The recording stays open and carries on from a snapshot of the next one (REC-2)
            this.waitingForSnapshot = true;
            Snapshots.forget();
            return;
        }
        if (minecraft.isPaused()) {
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
        this.codec = new GameCodec(listener.registryAccess(), listener.getConnectionType());
        this.own.reset();
        this.part.number(Records.SNAPSHOT_BEGIN, this.tick);
        if (this.waitingForSnapshot) {
            this.part.record(Records.DISCONTINUITY);
            this.own.forgetShown();
        }
        Snapshots.ownShown = this.own.shown();
        Snapshots.write(new Out(), minecraft, listener, this.codec);
        // What a server sends once the first chunks are on their way: it lets the game stop showing its loading screen
        this.write(new net.minecraft.network.protocol.game.ClientboundGameEventPacket(net.minecraft.network.protocol.game.ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START, 0.0F));
        this.part.record(Records.SNAPSHOT_END);
        this.waitingForSnapshot = false;
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
        this.partBeginsSegment = false;
        this.spool.add(new Spool.Job() {
            @Override
            public void run(RecordingWriter writer) throws Exception {
                if (begins) {
                    writer.beginSegment(startTick);
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

    /** How a snapshot, or anything else on the client thread, adds to the recording */
    public final class Out {
        public void packet(Packet<? super ClientGamePacketListener> packet) {
            Recorder.this.write(packet);
        }

        /** A packet that is likely to recur unchanged, such as a chunk: it is stored once however often it is written */
        public void sharedRef(int kind, byte[] hash) {
            Recorder.this.flushTicks();
            Recorder.this.part.record(kind, hash);
        }

        /** @return what to pass to {@link #sharedRef} to write the same packet again without its bytes */
        public byte[] sharedPacket(byte[] encoded) {
            byte[] hash = sha256(encoded);
            String name = HexFormat.of().formatHex(hash);
            if (Recorder.this.sharedSent.add(name)) {
                Recorder.this.spool.add(new Spool.Job() {
                    @Override
                    public void run(RecordingWriter writer) throws Exception {
                        writer.putShared(encoded);
                    }

                    @Override
                    public long bytes() {
                        return encoded.length;
                    }
                });
            }
            Recorder.this.flushTicks();
            Recorder.this.part.record(Records.SHARED_PACKET, hash);
            return hash;
        }

        /** Data stored once and referred to by a record of the given kind */
        public void sharedRecord(int kind, byte[] data) {
            byte[] hash = sha256(data);
            if (Recorder.this.sharedSent.add(HexFormat.of().formatHex(hash))) {
                Recorder.this.spool.add(writer -> writer.putShared(data));
            }
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

    private static byte[] sha256(byte[] data) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(data);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
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

    void onOwnSwing(LocalPlayer player, InteractionHand hand) {
        if (this.capturing() && player == Minecraft.getInstance().player) {
            this.write(OwnPlayer.swing(player, hand));
        }
    }

    /** The game is about to apply a packet. Client thread */
    @SuppressWarnings({"unchecked", "rawtypes"})
    void onPacket(Packet<?> packet, List<byte[]> frames) {
        if (!this.capturing()) {
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
        return target;
    }

    private static Path freeFile(Path folder, String name) {
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
