package comedymonkey.dailiescompat.aeronautics.client;

import comedymonkey.dailiescompat.aeronautics.state.AeroStates;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

/**
 * What the compat's handle mixin notes as Simulated Coasters draws a track's handles into the world: the handles of the
 * last frame they were drawn in, as Simulated Coasters lined them up to draw, each kind in a list of records of its own
 * (an arm, a tangent, an open end, a lift and a tilt handle), whose points, texture, colour and flag are read through
 * the records' own parts. It refers to nothing of Dailies or the mods, so the mixin can call it whatever is installed.
 */
public final class HandleMarks {

    private static final List<AeroStates.Handle> FRAME = new ArrayList<>();
    private static List<AeroStates.Handle> last = List.of();
    private static long lastFrame = Long.MIN_VALUE;
    /** Set once the compat has registered with Dailies: without it, nothing is noted */
    static volatile boolean on = false;

    private HandleMarks() {
    }

    /**
     * Simulated Coasters draws a list of one kind of handle. The arms come first in each frame it draws them, so they
     * start the frame's handles afresh
     */
    public static void drawn(int kind, List<?> pending) {
        if (!on) {
            return;
        }
        synchronized (FRAME) {
            if (kind == AeroStates.ARM) {
                FRAME.clear();
            }
            for (Object each : pending) {
                AeroStates.Handle handle = handle(kind, each);
                if (handle != null) {
                    FRAME.add(handle);
                }
            }
            if (kind == AeroStates.TILT) {
                last = List.copyOf(FRAME);
                lastFrame = Frames.now();
            }
        }
    }

    /** One of Simulated Coasters' records, read part by part: its points, its texture, its colour and alpha, and a flag */
    private static AeroStates.Handle handle(int kind, Object pending) {
        RecordComponent[] parts = pending.getClass().getRecordComponents();
        if (parts == null) {
            return null;
        }
        List<Double> coords = new ArrayList<>();
        String texture = "";
        float[] colour = new float[4];
        int colours = 0;
        boolean flag = false;
        try {
            for (RecordComponent part : parts) {
                var accessor = part.getAccessor();
                accessor.setAccessible(true);
                Object value = accessor.invoke(pending);
                if (value instanceof Vec3 point) {
                    coords.add(point.x);
                    coords.add(point.y);
                    coords.add(point.z);
                } else if (value instanceof ResourceLocation id) {
                    texture = id.toString();
                } else if (value instanceof Float number && colours < 4) {
                    colour[colours++] = number;
                } else if (value instanceof Boolean set) {
                    flag = set;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            return null;
        }
        double[] points = new double[coords.size()];
        for (int i = 0; i < points.length; i++) {
            points[i] = coords.get(i);
        }
        return new AeroStates.Handle(kind, points, texture, colour[0], colour[1], colour[2], colour[3], flag);
    }

    /** The handles of the last frame they were drawn in, when that was no more than a few frames ago; otherwise none */
    static List<AeroStates.Handle> recent() {
        synchronized (FRAME) {
            return lastFrame >= Frames.now() - 3 ? last : List.of();
        }
    }

}
