package comedymonkey.dailies.compat.create;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.playback.PlaybackExtensions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Create's turning contraptions (the ones a bearing carries), specification MOD-1. A game eases a contraption towards
 * the angle its bearing reports, so straight after a jump it can be several ticks of rotation away from where it was,
 * and everything riding it with it. The recording therefore also keeps each turning contraption's real angle on every
 * tick it changes, and a jump sets every one of them to exactly that.
 * <p>
 * Nothing is compiled against Create: its classes and fields are found by name, once. If any is missing, one line is
 * logged and contraptions simply settle by themselves as before.
 */
public final class CreateCompat {

    private static final String EXTENSION = "dailies:create";
    /** The angle of the bearing that turns each contraption, by the contraption: a bearing runs a tick ahead of what it carries */
    private static final String BEARINGS = "dailies:create_bearings";
    private static final int ENTRY_BYTES = 16 + 4;

    private Class<?> controlled, bearing;
    private Field angle, prevAngle, controllerPos, bearingAngle, bearingPrevAngle, bearingDiff;
    private final Map<UUID, Float> lastWritten = new HashMap<>();
    private final Map<UUID, Float> recorded = new HashMap<>();
    private final Map<UUID, Float> bearingsWritten = new HashMap<>();
    private final Map<UUID, Float> bearingsRecorded = new HashMap<>();
    private boolean failed = false;

    private CreateCompat() {
    }

    public static void init() {
        if (!Host.get().isModLoaded("create")) {
            return;
        }
        CreateCompat compat = new CreateCompat();
        try {
            compat.controlled = Class.forName("com.simibubi.create.content.contraptions.ControlledContraptionEntity");
            compat.bearing = Class.forName("com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity");
            compat.angle = field(compat.controlled, "angle");
            compat.prevAngle = field(compat.controlled, "prevAngle");
            compat.controllerPos = field(compat.controlled, "controllerPos");
            compat.bearingAngle = field(compat.bearing, "angle");
            compat.bearingPrevAngle = field(compat.bearing, "prevAngle");
            compat.bearingDiff = field(compat.bearing, "clientAngleDiff");
        } catch (Throwable t) {
            Host.LOGGER.error("Dailies: this version of Create is not the one Dailies knows (6.0.10), so turning contraptions may be a little out of place just after a jump. Everything else works", t);
            return;
        }
        // Written at the end of each tick before the recorder counts it, so that the record lands in the tick it
        // describes: written after (as the recorder's own end-of-tick hooks are), it would be read a tick late, and a
        // jump would put each contraption and bearing a tick of turning behind where the live game had it
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGH,
            (net.neoforged.neoforge.client.event.ClientTickEvent.Post event) -> {
                if (Recorder.get().state() == Recorder.State.RECORDING && !Minecraft.getInstance().isPaused()) {
                    compat.write();
                }
            });
        PlaybackExtensions.register(EXTENSION, new PlaybackExtensions.Extension() {
            @Override
            public void play(byte[] data, int from, int length, boolean skipping) {
                ByteBuffer in = ByteBuffer.wrap(data, from, length);
                while (in.remaining() >= ENTRY_BYTES) {
                    compat.recorded.put(new UUID(in.getLong(), in.getLong()), in.getFloat());
                }
            }

            @Override
            public void reset() {
                compat.recorded.clear();
            }

            @Override
            public void describe(java.util.function.Consumer<byte[]> out) {
                if (compat.recorded.isEmpty()) {
                    return;
                }
                // Every angle known now, in one record of the kind a recorded tick writes
                ByteBuffer all = ByteBuffer.allocate(compat.recorded.size() * ENTRY_BYTES);
                compat.recorded.forEach((id, angle) -> all.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).putFloat(angle));
                out.accept(all.array());
            }

            @Override
            public void arrived() {
                compat.place();
            }
        });
        PlaybackExtensions.register(BEARINGS, new PlaybackExtensions.Extension() {
            @Override
            public void play(byte[] data, int from, int length, boolean skipping) {
                ByteBuffer in = ByteBuffer.wrap(data, from, length);
                while (in.remaining() >= ENTRY_BYTES) {
                    compat.bearingsRecorded.put(new UUID(in.getLong(), in.getLong()), in.getFloat());
                }
            }

            @Override
            public void reset() {
                compat.bearingsRecorded.clear();
            }

            @Override
            public void describe(java.util.function.Consumer<byte[]> out) {
                if (compat.bearingsRecorded.isEmpty()) {
                    return;
                }
                ByteBuffer all = ByteBuffer.allocate(compat.bearingsRecorded.size() * ENTRY_BYTES);
                compat.bearingsRecorded.forEach((id, angle) -> all.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).putFloat(angle));
                out.accept(all.array());
            }
        });
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    /** At the end of every recorded tick: the angle of each turning contraption whose angle has changed */
    private void write() {
        if (this.failed) {
            return;
        }
        try {
            ClientLevel level = Minecraft.getInstance().level;
            if (level == null) {
                return;
            }
            ByteBuffer out = null;
            for (Entity entity : level.entitiesForRendering()) {
                if (!this.controlled.isInstance(entity)) {
                    continue;
                }
                float now = this.angle.getFloat(entity);
                Float before = this.lastWritten.get(entity.getUUID());
                if (before != null && before == now) {
                    continue;
                }
                this.lastWritten.put(entity.getUUID(), now);
                if (out == null) {
                    out = ByteBuffer.allocate(64 * ENTRY_BYTES);
                }
                if (out.remaining() >= ENTRY_BYTES) {
                    out.putLong(entity.getUUID().getMostSignificantBits()).putLong(entity.getUUID().getLeastSignificantBits()).putFloat(now);
                }
            }
            if (out != null) {
                Recorder.get().writeExtension(EXTENSION, java.util.Arrays.copyOf(out.array(), out.position()));
            }
            // And the bearing's own angle, which the game moves on before the contraption it turns
            ByteBuffer bearings = null;
            for (Entity entity : level.entitiesForRendering()) {
                if (!this.controlled.isInstance(entity)) {
                    continue;
                }
                BlockPos controller = (BlockPos) this.controllerPos.get(entity);
                BlockEntity blockEntity = controller == null ? null : level.getBlockEntity(controller);
                if (!this.bearing.isInstance(blockEntity)) {
                    continue;
                }
                float now = this.bearingAngle.getFloat(blockEntity);
                Float before = this.bearingsWritten.get(entity.getUUID());
                if (before != null && before == now) {
                    continue;
                }
                this.bearingsWritten.put(entity.getUUID(), now);
                if (bearings == null) {
                    bearings = ByteBuffer.allocate(64 * ENTRY_BYTES);
                }
                if (bearings.remaining() >= ENTRY_BYTES) {
                    bearings.putLong(entity.getUUID().getMostSignificantBits()).putLong(entity.getUUID().getLeastSignificantBits()).putFloat(now);
                }
            }
            if (bearings != null) {
                Recorder.get().writeExtension(BEARINGS, java.util.Arrays.copyOf(bearings.array(), bearings.position()));
            }
            if (this.bearingsWritten.size() > 4096) {
                this.bearingsWritten.clear();
            }
            if (this.lastWritten.size() > 4096) {
                this.lastWritten.clear();
            }
        } catch (Throwable t) {
            this.fail("recording the angle of turning contraptions", t);
        }
    }

    /** A jump has arrived: every turning contraption, and the bearing that carries it, goes to the angle it really had */
    private void place() {
        if (this.failed || this.recorded.isEmpty()) {
            return;
        }
        try {
            ClientLevel level = Minecraft.getInstance().level;
            if (level == null) {
                return;
            }
            for (Entity entity : level.entitiesForRendering()) {
                Float wanted = this.controlled.isInstance(entity) ? this.recorded.get(entity.getUUID()) : null;
                if (wanted == null) {
                    continue;
                }
                this.angle.setFloat(entity, wanted);
                this.prevAngle.setFloat(entity, wanted);
                BlockPos controller = (BlockPos) this.controllerPos.get(entity);
                BlockEntity blockEntity = controller == null ? null : level.getBlockEntity(controller);
                if (this.bearing.isInstance(blockEntity)) {
                    // The bearing would otherwise ease the contraption back towards its own idea of the angle
                    // At its own recorded angle where the recording has it (older recordings do not), else at the contraption's
                    Float own = this.bearingsRecorded.get(entity.getUUID());
                    float bearingWanted = own != null ? own : wanted;
                    this.bearingAngle.setFloat(blockEntity, bearingWanted);
                    this.bearingPrevAngle.setFloat(blockEntity, bearingWanted);
                    this.bearingDiff.setFloat(blockEntity, 0.0F);
                }
                // Whoever rides it is moved with it now, not on the next tick, which may be a long time coming while paused
                for (Entity passenger : entity.getPassengers()) {
                    entity.positionRider(passenger);
                    passenger.xo = passenger.xOld = passenger.getX();
                    passenger.yo = passenger.yOld = passenger.getY();
                    passenger.zo = passenger.zOld = passenger.getZ();
                }
            }
        } catch (Throwable t) {
            this.fail("placing turning contraptions after a jump", t);
        }
    }

    private void fail(String what, Throwable problem) {
        this.failed = true;
        Host.LOGGER.error("Dailies: {} failed, so turning contraptions may be a little out of place just after a jump. Said once", what, problem);
    }

}
