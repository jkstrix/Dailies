package comedymonkey.dailies.compat.aeronautics;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.Snapshots;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Create Aeronautics' hot air burner and steam vent in snapshots. Asked to describe itself for a client, each of them
 * reaches for the server's side of its balloon, which a game that is only a client does not have, and fails. What it
 * would have said is nonetheless all there: its own saved data (lit or not, signal strength, how much air it is set
 * to give), and the figures about its balloon that the server last sent, which the game keeps for the goggles and for
 * drawing. A snapshot is given both, under the names the block entity reads them back by, so a burner is as it was
 * after a jump to any part of a flight.
 * <p>
 * Nothing is compiled against Aeronautics: the one field and the figures' accessors are found by name, once for each
 * kind of block entity and of balloon figures, and a version that has no such field is left to the plain fallback, with
 * its one warning. Should reading the figures fail once, it is not tried again: every burner is then left to the
 * fallback, and that is said once.
 */
public final class AeronauticsCompat {

    private static final Map<Class<?>, Optional<Field>> INFO_FIELDS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Optional<Figures>> FIGURES = new ConcurrentHashMap<>();
    private static volatile boolean failed = false;

    /** The accessors of a balloon's figures, as the server last sent them */
    private record Figures(Method volume, Method filled, Method target, Method change, Method lift, Method centre) {
    }

    private AeronauticsCompat() {
    }

    public static void init() {
        if (!Host.get().isModLoaded("aeronautics")) {
            return;
        }
        Snapshots.helpBlockEntities(AeronauticsCompat::complete);
    }

    /** Adds what the server last told this game about a gas provider's balloon. False when this is not one */
    private static boolean complete(BlockEntity blockEntity, CompoundTag tag) {
        if (failed) {
            return false;
        }
        Optional<Field> field = INFO_FIELDS.computeIfAbsent(blockEntity.getClass(), AeronauticsCompat::find);
        if (field.isEmpty()) {
            return false;
        }
        try {
            Object info = field.get().get(blockEntity);
            if (info == null) {
                // Not part of a balloon yet: its own data is the whole of it
                return true;
            }
            Optional<Figures> figures = FIGURES.computeIfAbsent(info.getClass(), AeronauticsCompat::figures);
            if (figures.isEmpty()) {
                throw new NoSuchMethodException(info.getClass().getName() + " does not have the balloon figures this version of Dailies knows");
            }
            Figures known = figures.get();
            tag.putInt("Volume", ((Number) known.volume().invoke(info)).intValue());
            tag.putDouble("Filled", ((Number) known.filled().invoke(info)).doubleValue());
            tag.putDouble("Target", ((Number) known.target().invoke(info)).doubleValue());
            tag.putDouble("Delta", ((Number) known.change().invoke(info)).doubleValue());
            tag.putDouble("Lift", ((Number) known.lift().invoke(info)).doubleValue());
            if (known.centre().invoke(info) instanceof Vec3 centre) {
                tag.putDouble("CenterX", centre.x);
                tag.putDouble("CenterY", centre.y);
                tag.putDouble("CenterZ", centre.z);
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            Host.LOGGER.warn("Dailies: Create Aeronautics' balloon figures could not be read, so burners and vents are kept in snapshots by their own data only. Said once", t);
            return false;
        }
    }

    private static Optional<Figures> figures(Class<?> type) {
        try {
            return Optional.of(new Figures(accessor(type, "clientBalloonVolume"), accessor(type, "clientBalloonFilled"), accessor(type, "clientBalloonTarget"),
                accessor(type, "clientBalloonChange"), accessor(type, "clientBalloonLift"), accessor(type, "gasCenter")));
        } catch (ReflectiveOperationException | RuntimeException missing) {
            return Optional.empty();
        }
    }

    private static Method accessor(Class<?> type, String name) throws NoSuchMethodException {
        Method method = type.getMethod(name);
        method.setAccessible(true);
        return method;
    }

    private static Optional<Field> find(Class<?> type) {
        for (Class<?> at = type; at != null && at != BlockEntity.class; at = at.getSuperclass()) {
            try {
                Field field = at.getDeclaredField("clientBalloonInfo");
                field.setAccessible(true);
                return Optional.of(field);
            } catch (NoSuchFieldException notHere) {
                // Look further up
            }
        }
        return Optional.empty();
    }

}
