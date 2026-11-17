package comedymonkey.dailies.compat.sable;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.GameCodec;
import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.capture.SnapshotExtensions;
import comedymonkey.dailies.capture.Snapshots;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.PlaybackExtensions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Sable's physics objects ("sub-levels": Create Aeronautics' aircraft), specification chapter VII. Sable keeps a
 * craft's blocks in a hidden region of the world, sends its motion over a side channel of its own, and smooths that
 * motion on the client. Each of those needs something here before a craft records and plays back.
 * <p>
 * Nothing is compiled against Sable. Every class and member is found by name, once; if any is missing (another
 * version of Sable) one error is logged and craft support stays off. Nothing here ever throws into the game.
 */
public final class SableCompat implements Host.Crafts {

    private static final String MOD = "sable";
    private static final String EXTENSION = "dailies:sable";
    /** A second kind of record, for people standing on craft. Readers that do not know it skip it */
    private static final String RIDERS = "dailies:sable_riders";

    private static SableCompat instance = null;

    // Looked up once
    private Method getContainer, inBounds, getAllSubLevels, getOrigin, getInterpolation, removeSubLevel;
    private Method getUniqueId, getName, getPlot, logicalPose, boundingBox, isRemoved, isFinalized, renderPose;
    private Method plotBounds, plotLightEngine, plotLoadedChunks, holderChunk;
    private Field plotPos, mostRecentTick, originX, originY;
    private Method transformPosition, transformPositionInverse, transformNormal;
    private Method helperProject, helperContainingEntity, helperContainingPosition, helperTrackingOrVehicle;
    private Object helper, removedReason;
    private Constructor<?> startTracking, finalizeSubLevel;
    private StreamCodec<RegistryFriendlyByteBuf, Object> startTrackingCodec, finalizeCodec;
    private CustomPacketPayload.Type<?> startTrackingType, finalizeType;
    private Class<?> clientSubLevel;
    private Method minX, minY, minZ, maxX, maxY, maxZ;
    private Field interpolatorBuffer, interpolatorRunning;
    private Method snapshotTick, snapshotPose, poseSet, sampleAt, interpolationDelay;
    private Method markTeleport;

    private SableCompat() {
    }

    /** Switches craft support on when Sable is installed and everything it needs is where it is expected */
    public static void init() {
        if (!Host.get().isModLoaded(MOD)) {
            return;
        }
        SableCompat compat = new SableCompat();
        try {
            compat.lookUp();
        } catch (Throwable t) {
            Host.LOGGER.error("Dailies: this version of Sable is not the one Dailies knows (2.0.6), so physics craft will be missing or still in recordings. Everything else works", t);
            return;
        }
        instance = compat;
        Host.installCrafts(compat);
        SnapshotExtensions.register(EXTENSION, compat::writeSnapshot);
        PlaybackExtensions.register(EXTENSION, new PlaybackExtensions.Extension() {
            @Override
            public void play(byte[] data, int from, int length, boolean skipping) {
                compat.readPoses(data, from, length);
            }

            @Override
            public boolean needsNewWorld() {
                return compat.anyCraft();
            }

            @Override
            public void reset() {
                compat.removeAll();
                compat.recordedPoses.clear();
                compat.riders.clear();
            }

            @Override
            public void arrived() {
                // Craft first: the people standing on them are placed from the poses just applied
                compat.placeCraft();
                compat.placeRiders();
            }
        });
        PlaybackExtensions.register(RIDERS, new PlaybackExtensions.Extension() {
            @Override
            public void play(byte[] data, int from, int length, boolean skipping) {
                compat.readRiders(data, from, length);
            }

            @Override
            public void reset() {
                compat.riders.clear();
            }
        });
        Snapshots.entityFollowUp = compat::afterEntitySpawn;
        // In this order, so a tick's poses come before the people placed by them
        Recorder.everyRecordedTick(compat::writePoses);
        Recorder.everyRecordedTick(compat::writeRiders);
        // Ahead of the viewer's own frame work, so a camera riding along with someone sees them where they are drawn
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, (RenderFrameEvent.Pre event) -> compat.ridersFrame(event.getPartialTick().getGameTimeDeltaPartialTick(true)));
        NeoForge.EVENT_BUS.addListener((EntityTickEvent.Pre event) -> compat.riderTick(event.getEntity()));
        Host.LOGGER.info("Dailies: Sable found, physics craft will be recorded");
    }

    @SuppressWarnings("unchecked")
    private void lookUp() throws Exception {
        String base = "dev.ryanhcode.sable.";
        Class<?> container = Class.forName(base + "api.sublevel.SubLevelContainer");
        Class<?> subLevel = Class.forName(base + "sublevel.SubLevel");
        this.clientSubLevel = Class.forName(base + "sublevel.ClientSubLevel");
        Class<?> plot = Class.forName(base + "sublevel.plot.LevelPlot");
        Class<?> holder = Class.forName(base + "sublevel.plot.PlotChunkHolder");
        Class<?> pose = Class.forName(base + "companion.math.Pose3dc");
        Class<?> mutablePose = Class.forName(base + "companion.math.Pose3d");
        Class<?> removal = Class.forName(base + "sublevel.storage.SubLevelRemovalReason");

        this.getContainer = container.getMethod("getContainer", Level.class);
        this.inBounds = container.getMethod("inBounds", ChunkPos.class);
        this.getAllSubLevels = container.getMethod("getAllSubLevels");
        this.getOrigin = container.getMethod("getOrigin");
        this.originX = this.getOrigin.getReturnType().getField("x");
        this.originY = this.getOrigin.getReturnType().getField("y");
        this.removeSubLevel = container.getMethod("removeSubLevel", subLevel, removal);
        this.getInterpolation = Class.forName(base + "api.sublevel.ClientSubLevelContainer").getMethod("getInterpolation");
        this.mostRecentTick = this.getInterpolation.getReturnType().getDeclaredField("mostRecentTick");
        this.mostRecentTick.setAccessible(true);
        for (Object constant : removal.getEnumConstants()) {
            if (((Enum<?>) constant).name().equals("REMOVED")) {
                this.removedReason = constant;
            }
        }
        if (this.removedReason == null) {
            throw new NoSuchFieldException("SubLevelRemovalReason.REMOVED");
        }

        this.getUniqueId = subLevel.getMethod("getUniqueId");
        this.getName = subLevel.getMethod("getName");
        this.getPlot = subLevel.getMethod("getPlot");
        this.logicalPose = subLevel.getMethod("logicalPose");
        this.boundingBox = subLevel.getMethod("boundingBox");
        this.isRemoved = subLevel.getMethod("isRemoved");
        this.isFinalized = this.clientSubLevel.getMethod("isFinalized");
        this.renderPose = this.clientSubLevel.getMethod("renderPose", float.class);
        Class<?> box = this.boundingBox.getReturnType();
        this.minX = box.getMethod("minX");
        this.minY = box.getMethod("minY");
        this.minZ = box.getMethod("minZ");
        this.maxX = box.getMethod("maxX");
        this.maxY = box.getMethod("maxY");
        this.maxZ = box.getMethod("maxZ");

        this.plotPos = plot.getField("plotPos");
        this.plotBounds = plot.getMethod("getBoundingBox");
        this.plotLightEngine = plot.getMethod("getLightEngine");
        this.plotLoadedChunks = plot.getMethod("getLoadedChunks");
        this.holderChunk = holder.getMethod("getChunk");

        this.transformPosition = pose.getMethod("transformPosition", Vec3.class);
        this.transformPositionInverse = pose.getMethod("transformPositionInverse", Vec3.class);
        this.transformNormal = pose.getMethod("transformNormal", Vec3.class);

        this.helper = Class.forName(base + "Sable").getField("HELPER").get(null);
        Class<?> helperClass = this.helper.getClass();
        this.helperProject = helperClass.getMethod("projectOutOfSubLevel", Level.class, Vec3.class);
        this.helperContainingEntity = helperClass.getMethod("getContaining", Entity.class);
        this.helperContainingPosition = helperClass.getMethod("getContaining", Level.class, Position.class);
        this.helperTrackingOrVehicle = helperClass.getMethod("getTrackingOrVehicleSubLevel", Entity.class);

        Class<?> start = Class.forName(base + "network.packets.tcp.ClientboundStartTrackingSubLevelPacket");
        Class<?> done = Class.forName(base + "network.packets.tcp.ClientboundFinalizeSubLevelPacket");
        this.startTracking = start.getConstructor(long.class, UUID.class, pose, mutablePose, this.plotBounds.getReturnType(), String.class, int.class);
        this.finalizeSubLevel = done.getConstructor(long.class);
        this.startTrackingCodec = (StreamCodec<RegistryFriendlyByteBuf, Object>) start.getField("CODEC").get(null);
        this.finalizeCodec = (StreamCodec<RegistryFriendlyByteBuf, Object>) done.getField("CODEC").get(null);
        this.startTrackingType = (CustomPacketPayload.Type<?>) start.getField("TYPE").get(null);
        this.finalizeType = (CustomPacketPayload.Type<?>) done.getField("TYPE").get(null);

        Class<?> interpolator = Class.forName(base + "network.client.SubLevelSnapshotInterpolator");
        Class<?> snapshot = Class.forName(base + "network.client.SubLevelSnapshotInterpolator$Snapshot");
        this.interpolatorBuffer = interpolator.getField("buffer");
        this.interpolatorRunning = interpolator.getDeclaredField("runningSnapshot");
        this.interpolatorRunning.setAccessible(true);
        this.snapshotTick = snapshot.getMethod("gameTick");
        this.snapshotPose = snapshot.getMethod("pose");
        this.poseSet = mutablePose.getMethod("set", pose);
        this.sampleAt = interpolator.getMethod("getSampleAt", double.class, mutablePose);
        this.interpolationDelay = this.getInterpolation.getReturnType().getMethod("getInterpolationDelay");

        // Sable adds this to the game's teleport packet: a marked teleport to hidden coordinates keeps an entity on its craft
        this.markTeleport = ClientboundTeleportEntityPacket.class.getMethod("sable$setActuallyInSubLevel", boolean.class);
    }

    // --- Recording motion (it never crosses the normal connection) ---

    private static final ThreadLocal<Boolean> ARRIVING_NORMALLY = ThreadLocal.withInitial(() -> false);

    /** Called around Sable's handling of a motion message that came over the normal connection, which is recorded there already */
    public static void arrivingNormally(boolean now) {
        ARRIVING_NORMALLY.set(now);
    }

    /** Called at the start of Sable's client handling of a motion message, whichever way it came */
    public static void motion(Object payload) {
        try {
            if (instance == null || ARRIVING_NORMALLY.get() || Playback.active() || !(payload instanceof CustomPacketPayload message)) {
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.isSameThread()) {
                Recorder.get().writePayload(message);
            } else {
                minecraft.execute(() -> Recorder.get().writePayload(message));
            }
        } catch (Throwable t) {
            failOnce("recording craft motion", t);
        }
    }

    // --- Jumps: the smoother throws away every pose older than a few ticks, which after a jump is all of them ---

    /** Called at the start of each tick of Sable's motion smoother for one craft */
    public static void beforeSmootherTick(Object smoother, double backTick) {
        SableCompat compat = instance;
        if (compat == null || !Playback.active()) {
            return;
        }
        try {
            List<?> buffer = (List<?>) compat.interpolatorBuffer.get(smoother);
            synchronized (buffer) {
                if (buffer.isEmpty()) {
                    return;
                }
                Object newest = buffer.get(buffer.size() - 1);
                int newestTick = (int) compat.snapshotTick.invoke(newest);
                if (newestTick < backTick - 6) {
                    // The whole buffer is about to be dropped. Hold the craft where live play would have drawn it at
                    // this moment: the same short way behind the newest pose that the smoother always keeps
                    Object running = compat.interpolatorRunning.get(smoother);
                    double delay = 0.0;
                    try {
                        Object container = compat.getContainer.invoke(null, Minecraft.getInstance().level);
                        delay = ((Number) compat.interpolationDelay.invoke(compat.getInterpolation.invoke(container))).doubleValue();
                    } catch (Throwable ignored) {
                        // Without the delay the craft is held at its newest pose, a tick or two ahead
                    }
                    if (delay > 0.0 && delay < 20.0 && buffer.size() > 1) {
                        compat.sampleAt.invoke(smoother, newestTick - delay, running);
                    } else {
                        compat.poseSet.invoke(running, compat.snapshotPose.invoke(newest));
                    }
                }
            }
        } catch (Throwable t) {
            failOnce("holding a craft's place after a jump", t);
        }
    }

    private boolean anyCraft() {
        try {
            ClientLevel level = Minecraft.getInstance().level;
            Object container = level == null ? null : this.getContainer.invoke(null, level);
            return container != null && !((Collection<?>) this.getAllSubLevels.invoke(container)).isEmpty();
        } catch (Throwable t) {
            return true;
        }
    }

    private void removeAll() {
        try {
            ClientLevel level = Minecraft.getInstance().level;
            Object container = level == null ? null : this.getContainer.invoke(null, level);
            if (container == null) {
                return;
            }
            for (Object subLevel : new ArrayList<>((Collection<?>) this.getAllSubLevels.invoke(container))) {
                this.removeSubLevel.invoke(container, subLevel, this.removedReason);
            }
            // The world may be kept across a jump. Sable's clock for smoothing motion is then put back as it is in a
            // new world, or it would carry on from the moment that was left
            Object clock = this.getInterpolation.invoke(container);
            Object fresh = clock.getClass().getDeclaredConstructor().newInstance();
            for (Field field : clock.getClass().getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (!java.lang.reflect.Modifier.isStatic(modifiers) && !java.lang.reflect.Modifier.isFinal(modifiers)) {
                    field.setAccessible(true);
                    field.set(clock, field.get(fresh));
                }
            }
        } catch (Throwable t) {
            failOnce("clearing craft before a jump", t);
        }
    }

    // --- Exactly where each craft was ---
    // Sable smooths motion against real time. After a jump there is no real time to smooth against, and the craft ends up
    // a few ticks ahead of where live play drew it. So the recording also keeps each craft's own pose on every tick it
    // changes, and a jump puts every craft exactly there.

    private static final int POSE_NUMBERS = 13;
    private final java.util.Map<UUID, double[]> recordedPoses = new java.util.HashMap<>();
    private final java.util.Map<UUID, double[]> lastWritten = new java.util.HashMap<>();
    private Field lastPoseField, renderPoseField;
    private Method forceUpdateBounds, getInterpolator;

    private static double[] numbers(Object pose) throws Exception {
        org.joml.Vector3dc position = (org.joml.Vector3dc) pose.getClass().getMethod("position").invoke(pose);
        org.joml.Quaterniondc turn = (org.joml.Quaterniondc) pose.getClass().getMethod("orientation").invoke(pose);
        org.joml.Vector3dc pivot = (org.joml.Vector3dc) pose.getClass().getMethod("rotationPoint").invoke(pose);
        org.joml.Vector3dc scale = (org.joml.Vector3dc) pose.getClass().getMethod("scale").invoke(pose);
        return new double[]{position.x(), position.y(), position.z(), turn.x(), turn.y(), turn.z(), turn.w(), pivot.x(), pivot.y(), pivot.z(), scale.x(), scale.y(), scale.z()};
    }

    private static void apply(Object pose, double[] n) throws Exception {
        ((org.joml.Vector3d) pose.getClass().getMethod("position").invoke(pose)).set(n[0], n[1], n[2]);
        ((org.joml.Quaterniond) pose.getClass().getMethod("orientation").invoke(pose)).set(n[3], n[4], n[5], n[6]);
        ((org.joml.Vector3d) pose.getClass().getMethod("rotationPoint").invoke(pose)).set(n[7], n[8], n[9]);
        ((org.joml.Vector3d) pose.getClass().getMethod("scale").invoke(pose)).set(n[10], n[11], n[12]);
    }

    /** At the end of every recorded tick */
    private void writePoses() {
        try {
            ClientLevel level = Minecraft.getInstance().level;
            Object container = level == null ? null : this.getContainer.invoke(null, level);
            if (container == null) {
                return;
            }
            java.nio.ByteBuffer out = null;
            Collection<?> subLevels = (Collection<?>) this.getAllSubLevels.invoke(container);
            for (Object subLevel : new ArrayList<>(subLevels)) {
                if ((boolean) this.isRemoved.invoke(subLevel)) {
                    continue;
                }
                UUID id = (UUID) this.getUniqueId.invoke(subLevel);
                double[] now = numbers(this.logicalPose.invoke(subLevel));
                if (java.util.Arrays.equals(now, this.lastWritten.get(id))) {
                    continue;
                }
                this.lastWritten.put(id, now);
                if (out == null) {
                    out = java.nio.ByteBuffer.allocate(subLevels.size() * (16 + POSE_NUMBERS * 8));
                }
                out.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
                for (double number : now) {
                    out.putDouble(number);
                }
            }
            if (out != null) {
                Recorder.get().writeExtension(EXTENSION, java.util.Arrays.copyOf(out.array(), out.position()));
            }
            if (this.lastWritten.size() > subLevels.size() + 64) {
                this.lastWritten.clear();
            }
        } catch (Throwable t) {
            failOnce("recording where craft are", t);
        }
    }

    private void readPoses(byte[] data, int from, int length) {
        java.nio.ByteBuffer in = java.nio.ByteBuffer.wrap(data, from, length);
        while (in.remaining() >= 16 + POSE_NUMBERS * 8) {
            UUID id = new UUID(in.getLong(), in.getLong());
            double[] pose = new double[POSE_NUMBERS];
            for (int i = 0; i < POSE_NUMBERS; i++) {
                pose[i] = in.getDouble();
            }
            this.recordedPoses.put(id, pose);
        }
    }

    /** A jump has arrived: every craft goes exactly where the recording says it was */
    private void placeCraft() {
        try {
            ClientLevel level = Minecraft.getInstance().level;
            Object container = level == null ? null : this.getContainer.invoke(null, level);
            if (container == null) {
                return;
            }
            if (this.lastPoseField == null) {
                this.lastPoseField = Class.forName("dev.ryanhcode.sable.sublevel.SubLevel").getDeclaredField("lastPose");
                this.lastPoseField.setAccessible(true);
                this.renderPoseField = this.clientSubLevel.getDeclaredField("renderPose");
                this.renderPoseField.setAccessible(true);
                this.forceUpdateBounds = this.clientSubLevel.getMethod("forceUpdateBounds");
                this.getInterpolator = this.clientSubLevel.getMethod("getInterpolator");
            }
            for (Object subLevel : new ArrayList<>((Collection<?>) this.getAllSubLevels.invoke(container))) {
                double[] pose = this.recordedPoses.get((UUID) this.getUniqueId.invoke(subLevel));
                if (pose == null || !this.clientSubLevel.isInstance(subLevel)) {
                    continue;
                }
                apply(this.logicalPose.invoke(subLevel), pose);
                apply(this.lastPoseField.get(subLevel), pose);
                apply(this.renderPoseField.get(subLevel), pose);
                Object smoother = this.getInterpolator.invoke(subLevel);
                List<?> buffer = (List<?>) this.interpolatorBuffer.get(smoother);
                synchronized (buffer) {
                    buffer.clear();
                    apply(this.interpolatorRunning.get(smoother), pose);
                }
                this.forceUpdateBounds.invoke(subLevel);
            }
        } catch (Throwable t) {
            failOnce("placing craft after a jump", t);
        }
    }

    // --- People standing on a craft (MOD-4) ---
    // In live play Sable's own client logic carries someone standing loose on a deck. In playback their recorded world
    // positions arrive as ordinary packets a fraction of a tick behind the craft, and they trail behind a fast climb.
    // So the recording also keeps where each of them stands in the craft's own coordinates, and playback places them
    // from the craft's pose: as drawn, on every frame, and tick by tick so that their walking is only what they walked.

    /** How many ticks someone may be off the deck (a jump, a bump) and still count as aboard */
    private static final int LOOSE_TICKS = 10;
    private static final int RIDER_LEFT = 0, RIDER_STANDING = 1;
    private static final int RIDER_STANDING_BYTES = 16 + 3 * 8 + 4;

    /** While recording: what was last written about a player */
    private static final class Standing {
        final UUID craft;
        final Vec3 local;
        final float yaw;
        final int loose;

        Standing(UUID craft, Vec3 local, float yaw, int loose) {
            this.craft = craft;
            this.local = local;
            this.yaw = yaw;
            this.loose = loose;
        }

        boolean same(UUID craft, Vec3 local, float yaw) {
            return this.craft.equals(craft) && this.local.distanceToSqr(local) < 1.0e-8 && Math.abs(Mth.wrapDegrees(this.yaw - yaw)) < 0.5F;
        }
    }

    /** In playback: the craft a player stands on and where, in the craft's own coordinates */
    private static final class Rider {
        final UUID craft;
        /** The newest recorded place */
        Vec3 local;
        /** Where the tick now being drawn started and ended */
        Vec3 from, to;
        /** Their body's turn against the craft's heading. Kept for readers; rotation is left to the recorded packets */
        float yaw;

        Rider(UUID craft, Vec3 local) {
            this.craft = craft;
            this.local = local;
            this.from = local;
            this.to = local;
        }
    }

    private final java.util.Map<UUID, Standing> standing = new java.util.HashMap<>();
    private final java.util.Map<UUID, Rider> riders = new java.util.concurrent.ConcurrentHashMap<>();
    /** Set when a snapshot is written: a snapshot clears the riders in playback, so everyone aboard is written again */
    private volatile boolean ridersStale = true;

    /** At the end of every recorded tick, after the poses */
    private void writeRiders() {
        try {
            ClientLevel level = Minecraft.getInstance().level;
            if (level == null) {
                return;
            }
            boolean everyone = this.ridersStale;
            this.ridersStale = false;
            Object container = this.getContainer.invoke(null, level);
            // Players and mobs alike: anything alive that stands loose on a deck is carried by the craft
            java.util.List<net.minecraft.world.entity.LivingEntity> living = new ArrayList<>();
            if (container != null) {
                for (Entity entity : level.entitiesForRendering()) {
                    if (entity instanceof net.minecraft.world.entity.LivingEntity alive) {
                        living.add(alive);
                    }
                }
            }
            java.nio.ByteBuffer out = java.nio.ByteBuffer.allocate((living.size() + this.standing.size()) * (17 + RIDER_STANDING_BYTES));
            java.util.Set<UUID> aboard = new java.util.HashSet<>();
            if (container != null) {
                for (net.minecraft.world.entity.LivingEntity player : living) {
                    UUID id = player.getUUID();
                    Standing last = this.standing.get(id);
                    if (aboard.contains(id) || player.isPassenger() || player.isSpectator() || player.isRemoved() || this.isInPlot(level, player.getX(), player.getZ())) {
                        // Seated people and anything inside the hidden region are part of the craft already
                        continue;
                    }
                    Object craft = this.standingOn(player);
                    int loose = 0;
                    if (craft == null && last != null && last.loose < LOOSE_TICKS) {
                        // In the air over the deck they were just on: still aboard for a moment
                        Object held = this.craftById(level, last.craft);
                        AABB bounds = held == null ? null : this.getSubLevelBounds(held);
                        if (bounds != null && bounds.inflate(2.0).contains(player.position())) {
                            craft = held;
                            loose = last.loose + 1;
                        }
                    }
                    if (craft == null) {
                        continue;
                    }
                    UUID craftId = (UUID) this.getUniqueId.invoke(craft);
                    Object pose = this.logicalPose.invoke(craft);
                    Vec3 local = (Vec3) this.transformPositionInverse.invoke(pose, player.position());
                    float yaw = Mth.wrapDegrees(player.yBodyRot - this.heading(pose));
                    aboard.add(id);
                    if (!everyone && last != null && last.same(craftId, local, yaw)) {
                        if (last.loose != loose) {
                            this.standing.put(id, new Standing(last.craft, last.local, last.yaw, loose));
                        }
                        continue;
                    }
                    this.standing.put(id, new Standing(craftId, local, yaw, loose));
                    out.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).put((byte) RIDER_STANDING);
                    out.putLong(craftId.getMostSignificantBits()).putLong(craftId.getLeastSignificantBits());
                    out.putDouble(local.x).putDouble(local.y).putDouble(local.z).putFloat(yaw);
                }
            }
            for (java.util.Iterator<UUID> left = this.standing.keySet().iterator(); left.hasNext(); ) {
                UUID id = left.next();
                if (!aboard.contains(id)) {
                    left.remove();
                    out.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).put((byte) RIDER_LEFT);
                }
            }
            if (out.position() > 0) {
                Recorder.get().writeExtension(RIDERS, java.util.Arrays.copyOf(out.array(), out.position()));
            }
        } catch (Throwable t) {
            failOnce("recording who stands on a craft", t);
        }
    }

    private void readRiders(byte[] data, int from, int length) {
        java.nio.ByteBuffer in = java.nio.ByteBuffer.wrap(data, from, length);
        while (in.remaining() >= 17) {
            UUID player = new UUID(in.getLong(), in.getLong());
            int kind = in.get();
            if (kind == RIDER_LEFT) {
                this.riders.remove(player);
            } else if (kind == RIDER_STANDING && in.remaining() >= RIDER_STANDING_BYTES) {
                UUID craft = new UUID(in.getLong(), in.getLong());
                Vec3 local = new Vec3(in.getDouble(), in.getDouble(), in.getDouble());
                float yaw = in.getFloat();
                Rider rider = this.riders.get(player);
                if (rider == null || !rider.craft.equals(craft)) {
                    rider = new Rider(craft, local);
                    this.riders.put(player, rider);
                }
                rider.local = local;
                rider.yaw = yaw;
            } else {
                // A kind of entry from a later version: nothing after it can be read
                return;
            }
        }
    }

    /** A craft's heading as a yaw in degrees: where its own south points in the world */
    private float heading(Object pose) throws Exception {
        Vec3 south = (Vec3) this.transformNormal.invoke(pose, new Vec3(0.0, 0.0, 1.0));
        return (float) Math.toDegrees(Math.atan2(-south.x, south.z));
    }

    private @Nullable Object craftById(Level level, UUID id) throws Exception {
        Object container = this.getContainer.invoke(null, level);
        if (container == null) {
            return null;
        }
        for (Object subLevel : new ArrayList<>((Collection<?>) this.getAllSubLevels.invoke(container))) {
            if (id.equals(this.getUniqueId.invoke(subLevel)) && !(boolean) this.isRemoved.invoke(subLevel)) {
                return subLevel;
            }
        }
        return null;
    }

    /** The player a rider record is about, when they are in the world and loose on their feet */
    private @Nullable net.minecraft.world.entity.LivingEntity riderEntity(ClientLevel level, UUID id) {
        Entity found = level.getEntities().get(id);
        return found instanceof net.minecraft.world.entity.LivingEntity player && player != Minecraft.getInstance().player && !player.isPassenger() && !player.isRemoved() ? player : null;
    }

    /** Puts an entity somewhere with nothing left of where it was, so the game's blending between ticks cannot pull it back */
    private static void hold(Entity entity, Vec3 place) {
        entity.setPos(place.x, place.y, place.z);
        entity.xo = entity.xOld = place.x;
        entity.yo = entity.yOld = place.y;
        entity.zo = entity.zOld = place.z;
    }

    /** Before every drawn frame of a recording: each rider goes where their craft is drawn this frame */
    private void ridersFrame(float partialTick) {
        if (this.riders.isEmpty() || !Playback.loaded()) {
            return;
        }
        try {
            ClientLevel level = Minecraft.getInstance().level;
            if (level == null) {
                return;
            }
            for (java.util.Iterator<java.util.Map.Entry<UUID, Rider>> each = this.riders.entrySet().iterator(); each.hasNext(); ) {
                java.util.Map.Entry<UUID, Rider> entry = each.next();
                Rider rider = entry.getValue();
                Object craft = this.craftById(level, rider.craft);
                if (craft == null) {
                    // The craft is gone; its riders are on their own again
                    each.remove();
                    continue;
                }
                net.minecraft.world.entity.LivingEntity player = this.riderEntity(level, entry.getKey());
                if (player != null) {
                    Vec3 local = rider.from.lerp(rider.to, Mth.clamp(partialTick, 0.0F, 1.0F));
                    hold(player, (Vec3) this.transformPosition.invoke(this.poseNow(craft, partialTick), local));
                }
            }
        } catch (Throwable t) {
            failOnce("keeping people on their craft's deck", t);
        }
    }

    /**
     * Before a rider's own tick in a recording. The game is about to move them a step towards their last recorded world
     * position, which trails the craft. Instead they start the tick where they stood on the craft and end it where they
     * stand now, so what the tick sees as their movement (and animates as walking) is only what they walked on the deck.
     */
    private void riderTick(Entity entity) {
        if (this.riders.isEmpty() || !entity.level().isClientSide || !(entity instanceof net.minecraft.world.entity.LivingEntity player) || !Playback.loaded()) {
            return;
        }
        try {
            Rider rider = this.riders.get(player.getUUID());
            if (rider == null || player == Minecraft.getInstance().player || player.isPassenger()) {
                return;
            }
            Object craft = this.craftById(player.level(), rider.craft);
            if (craft == null) {
                this.riders.remove(player.getUUID());
                return;
            }
            rider.from = rider.to;
            rider.to = rider.local;
            Object pose = this.logicalPose.invoke(craft);
            Vec3 end = (Vec3) this.transformPosition.invoke(pose, rider.to);
            hold(player, (Vec3) this.transformPosition.invoke(pose, rider.from));
            // One step, so the whole way is gone this tick. The turn it was already making is kept as its target
            player.lerpTo(end.x, end.y, end.z, player.lerpTargetYRot(), player.lerpTargetXRot(), 1);
        } catch (Throwable t) {
            failOnce("moving people with their craft", t);
        }
    }

    /** A jump has arrived and the craft have just been placed: their riders go with them */
    private void placeRiders() {
        if (this.riders.isEmpty()) {
            return;
        }
        try {
            ClientLevel level = Minecraft.getInstance().level;
            if (level == null) {
                return;
            }
            for (java.util.Map.Entry<UUID, Rider> entry : this.riders.entrySet()) {
                Rider rider = entry.getValue();
                rider.from = rider.local;
                rider.to = rider.local;
                Object craft = this.craftById(level, rider.craft);
                net.minecraft.world.entity.LivingEntity player = this.riderEntity(level, entry.getKey());
                if (craft != null && player != null) {
                    Vec3 place = (Vec3) this.transformPosition.invoke(this.logicalPose.invoke(craft), rider.local);
                    hold(player, place);
                    player.lerpTo(place.x, place.y, place.z, player.lerpTargetYRot(), player.lerpTargetXRot(), 1);
                }
            }
        } catch (Throwable t) {
            failOnce("placing people on their craft after a jump", t);
        }
    }

    // --- Snapshots ---

    private void writeSnapshot(Recorder.Out out, GameCodec codec) throws Exception {
        this.ridersStale = true;
        ClientLevel level = Minecraft.getInstance().level;
        Object container = level == null ? null : this.getContainer.invoke(null, level);
        if (container == null) {
            return;
        }
        Object origin = this.getOrigin.invoke(container);
        int tick = (int) Math.max(0, Math.floor(this.mostRecentTick.getDouble(this.getInterpolation.invoke(container))));
        for (Object subLevel : new ArrayList<>((Collection<?>) this.getAllSubLevels.invoke(container))) {
            if ((boolean) this.isRemoved.invoke(subLevel)) {
                continue;
            }
            try {
                // Gathered first and written only when whole, so a failure never leaves a craft that has no blocks
                Object plot = this.getPlot.invoke(subLevel);
                ChunkPos pos = (ChunkPos) this.plotPos.get(plot);
                long plotCoordinate = ChunkPos.asLong(pos.x - this.originX.getInt(origin), pos.z - this.originY.getInt(origin));
                Object pose = this.logicalPose.invoke(subLevel);
                Object start = this.startTracking.newInstance(plotCoordinate, this.getUniqueId.invoke(subLevel), pose, pose, this.plotBounds.invoke(plot),
                    this.getName.invoke(subLevel), tick);
                byte[] startFrame = codec.payloadFrame(this.startTrackingType.id(), buffer -> this.startTrackingCodec.encode(buffer, start));
                List<byte[]> chunks = new ArrayList<>();
                LevelLightEngine light = (LevelLightEngine) this.plotLightEngine.invoke(plot);
                for (Object holder : new ArrayList<>((Collection<?>) this.plotLoadedChunks.invoke(plot))) {
                    LevelChunk chunk = (LevelChunk) this.holderChunk.invoke(holder);
                    if (chunk != null) {
                        chunks.add(codec.encode(new ClientboundLevelChunkWithLightPacket(chunk, light, null, null)));
                    }
                }
                byte[] doneFrame = null;
                if (this.clientSubLevel.isInstance(subLevel) && (boolean) this.isFinalized.invoke(subLevel)) {
                    Object done = this.finalizeSubLevel.newInstance(plotCoordinate);
                    doneFrame = codec.payloadFrame(this.finalizeType.id(), buffer -> this.finalizeCodec.encode(buffer, done));
                }

                out.payloadFrame(this.startTrackingType.id().toString(), startFrame);
                chunks.forEach(out::sharedPacket);
                if (doneFrame != null) {
                    out.payloadFrame(this.finalizeType.id().toString(), doneFrame);
                }
            } catch (Throwable t) {
                failOnce("describing a craft for a snapshot", t);
            }
        }
    }

    /** Straight after a snapshot writes an entity: one that is part of a craft is told to stay there */
    private void afterEntitySpawn(Recorder.Out out, Entity entity) {
        try {
            if (!this.isInPlot(entity.level(), entity.getX(), entity.getZ())) {
                return;
            }
            ClientboundTeleportEntityPacket stay = new ClientboundTeleportEntityPacket(entity);
            this.markTeleport.invoke(stay, true);
            out.packet(stay);
        } catch (Throwable t) {
            failOnce("keeping an entity on its craft in a snapshot", t);
        }
    }

    // --- What the Smart Camera asks ---

    @Override
    public boolean isInPlot(Level level, double x, double z) {
        try {
            Object container = this.getContainer.invoke(null, level);
            return container != null && (boolean) this.inBounds.invoke(container, new ChunkPos(BlockPos.containing(x, 0.0, z)));
        } catch (Throwable t) {
            failOnce("asking whether a place is on a craft", t);
            return false;
        }
    }

    @Override
    public Vec3 projectOutOfSubLevel(Level level, Vec3 position) {
        try {
            Vec3 projected = (Vec3) this.helperProject.invoke(this.helper, level, position);
            return projected == null ? position : projected;
        } catch (Throwable t) {
            failOnce("placing a craft position in the world", t);
            return position;
        }
    }

    @Override
    public Vec3 projectOutOfSubLevel(Level level, Vec3 position, float partialTick) {
        try {
            Object subLevel = this.helperContainingPosition.invoke(this.helper, level, position);
            return subLevel == null ? position : (Vec3) this.transformPosition.invoke(this.poseNow(subLevel, partialTick), position);
        } catch (Throwable t) {
            failOnce("placing a craft position in the world", t);
            return position;
        }
    }

    @Override
    public Vec3 projectDirectionOutOfSubLevel(Level level, Vec3 position, Vec3 direction, float partialTick) {
        try {
            Object subLevel = this.helperContainingPosition.invoke(this.helper, level, position);
            return subLevel == null ? direction : (Vec3) this.transformNormal.invoke(this.poseNow(subLevel, partialTick), direction);
        } catch (Throwable t) {
            failOnce("turning a craft direction into the world", t);
            return direction;
        }
    }

    /** The pose as drawn this frame. The logical pose is a tick old and makes a camera judder on a moving craft */
    private Object poseNow(Object subLevel, float partialTick) throws Exception {
        return this.clientSubLevel.isInstance(subLevel) ? this.renderPose.invoke(subLevel, partialTick) : this.logicalPose.invoke(subLevel);
    }

    @Override
    public @Nullable Object getSubLevel(Entity entity) {
        try {
            Object subLevel = this.helperContainingEntity.invoke(this.helper, entity);
            if (subLevel == null) {
                subLevel = this.helperTrackingOrVehicle.invoke(this.helper, entity);
            }
            return subLevel != null ? subLevel : this.standingOn(entity);
        } catch (Throwable t) {
            failOnce("finding the craft an entity is on", t);
            return null;
        }
    }

    /** Sable only knows what a simulated entity stands on, and recorded players are not simulated: look at the blocks under their feet */
    private @Nullable Object standingOn(Entity entity) throws Exception {
        Object container = this.getContainer.invoke(null, entity.level());
        if (container == null) {
            return null;
        }
        Vec3 feet = entity.position();
        for (Object subLevel : new ArrayList<>((Collection<?>) this.getAllSubLevels.invoke(container))) {
            AABB bounds = this.getSubLevelBounds(subLevel);
            if (bounds == null || !bounds.inflate(1.0).contains(feet)) {
                continue;
            }
            Object pose = this.logicalPose.invoke(subLevel);
            for (double down : new double[]{0.1, 0.6, 1.1}) {
                Vec3 inPlot = (Vec3) this.transformPositionInverse.invoke(pose, feet.subtract(0.0, down, 0.0));
                BlockPos block = BlockPos.containing(inPlot);
                if (!entity.level().getBlockState(block).getCollisionShape(entity.level(), block).isEmpty()) {
                    return subLevel;
                }
            }
        }
        return null;
    }

    @Override
    public @Nullable Object getSubLevelAt(Level level, Vec3 position) {
        try {
            return this.helperContainingPosition.invoke(this.helper, level, position);
        } catch (Throwable t) {
            failOnce("finding the craft at a place", t);
            return null;
        }
    }

    @Override
    public boolean isNearSubLevel(Object subLevel, Vec3 position, double margin) {
        AABB bounds = this.getSubLevelBounds(subLevel);
        return bounds != null && bounds.inflate(margin).contains(position);
    }

    @Override
    public @Nullable AABB getSubLevelBounds(Object subLevel) {
        if (subLevel == null) {
            return null;
        }
        try {
            Object box = this.boundingBox.invoke(subLevel);
            double[] values = {number(this.minX.invoke(box)), number(this.minY.invoke(box)), number(this.minZ.invoke(box)),
                number(this.maxX.invoke(box)), number(this.maxY.invoke(box)), number(this.maxZ.invoke(box))};
            for (double value : values) {
                if (!Double.isFinite(value)) {
                    return null;
                }
            }
            if (values[3] < values[0] || values[4] < values[1] || values[5] < values[2]) {
                return null;
            }
            return new AABB(values[0], values[1], values[2], values[3], values[4], values[5]);
        } catch (Throwable t) {
            failOnce("measuring a craft", t);
            return null;
        }
    }

    private static double number(Object value) {
        return ((Number) value).doubleValue();
    }

    // --- For the scripted test: where every craft is, in world coordinates ---

    /** Each craft's id and the world position of its plot's centre, or an empty list without Sable */
    public static List<Object[]> craftPositions(Level level, float partialTick) {
        List<Object[]> found = new ArrayList<>();
        SableCompat compat = instance;
        if (compat == null) {
            return found;
        }
        try {
            Object container = compat.getContainer.invoke(null, level);
            if (container == null) {
                return found;
            }
            for (Object subLevel : new ArrayList<>((Collection<?>) compat.getAllSubLevels.invoke(container))) {
                if ((boolean) compat.isRemoved.invoke(subLevel)) {
                    continue;
                }
                AABB bounds = compat.getSubLevelBounds(subLevel);
                if (bounds != null) {
                    found.add(new Object[]{compat.getUniqueId.invoke(subLevel), bounds.getCenter(), bounds});
                }
            }
        } catch (Throwable t) {
            failOnce("listing craft", t);
        }
        return found;
    }

    /**
     * Where an entity stands on a craft, measured from the centre of the craft's bounds, or null when it is not standing
     * on one. Found the same way live and in playback, so the two can be compared
     */
    public static @Nullable Vec3 offsetOnCraft(Entity entity) {
        SableCompat compat = instance;
        if (compat == null) {
            return null;
        }
        try {
            if (entity.isPassenger() || compat.isInPlot(entity.level(), entity.getX(), entity.getZ())) {
                return null;
            }
            Object craft = compat.standingOn(entity);
            AABB bounds = craft == null ? null : compat.getSubLevelBounds(craft);
            return bounds == null ? null : entity.position().subtract(bounds.getCenter());
        } catch (Throwable t) {
            failOnce("measuring where someone stands on a craft", t);
            return null;
        }
    }

    public static boolean available() {
        return instance != null;
    }

    private static final java.util.Set<String> FAILED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void failOnce(String what, Throwable problem) {
        if (FAILED.add(what)) {
            Host.LOGGER.error("Dailies: {} failed, so physics craft may look wrong in recordings. Said once", what, problem);
        }
    }

}
