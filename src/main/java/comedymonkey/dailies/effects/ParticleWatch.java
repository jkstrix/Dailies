package comedymonkey.dailies.effects;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.Vec3;

/**
 * Notes where particles of the kinds asked for are made, while watching is switched on: for the self test, which counts
 * the swirls of potion effects around each entity. Off, it costs one volatile read a particle
 */
public final class ParticleWatch {

    /** One particle made: its kind (the registry name) and where */
    public record Seen(String type, Vec3 at) {
    }

    private static volatile boolean watching = false;
    private static final List<Seen> SEEN = new ArrayList<>();

    private ParticleWatch() {
    }

    public static void start() {
        synchronized (SEEN) {
            SEEN.clear();
        }
        watching = true;
    }

    public static List<Seen> stop() {
        watching = false;
        synchronized (SEEN) {
            List<Seen> seen = List.copyOf(SEEN);
            SEEN.clear();
            return seen;
        }
    }

    /** What has been seen so far, watching on */
    public static List<Seen> seen() {
        synchronized (SEEN) {
            return List.copyOf(SEEN);
        }
    }

    public static void made(ParticleOptions options, double x, double y, double z) {
        if (!watching) {
            return;
        }
        var key = BuiltInRegistries.PARTICLE_TYPE.getKey(options.getType());
        synchronized (SEEN) {
            if (SEEN.size() < 100_000) {
                SEEN.add(new Seen(key == null ? "?" : key.toString(), new Vec3(x, y, z)));
            }
        }
    }
}
