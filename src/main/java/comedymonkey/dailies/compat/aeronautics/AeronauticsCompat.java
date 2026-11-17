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
 * Nothing is compiled against Aeronautics: the one field is found by name, and a version that has no such field is
 * left to the plain fallback, with its one warning.
 */
public final class AeronauticsCompat {

    private static final Map<Class<?>, Optional<Field>> INFO_FIELDS = new ConcurrentHashMap<>();

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
            tag.putInt("Volume", ((Number) call(info, "clientBalloonVolume")).intValue());
            tag.putDouble("Filled", ((Number) call(info, "clientBalloonFilled")).doubleValue());
            tag.putDouble("Target", ((Number) call(info, "clientBalloonTarget")).doubleValue());
            tag.putDouble("Delta", ((Number) call(info, "clientBalloonChange")).doubleValue());
            tag.putDouble("Lift", ((Number) call(info, "clientBalloonLift")).doubleValue());
            if (call(info, "gasCenter") instanceof Vec3 centre) {
                tag.putDouble("CenterX", centre.x);
                tag.putDouble("CenterY", centre.y);
                tag.putDouble("CenterZ", centre.z);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object call(Object info, String accessor) throws Exception {
        Method method = info.getClass().getMethod(accessor);
        method.setAccessible(true);
        return method.invoke(info);
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
