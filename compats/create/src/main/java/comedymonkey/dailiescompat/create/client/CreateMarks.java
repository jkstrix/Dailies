package comedymonkey.dailiescompat.create.client;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * What the compat's mixins note as Create does it, for the parts to read at the end of a tick: the blocks each cluster
 * outline was made from (Catnip keeps only the faces it works out from them), and the last line Create put above the
 * hotbar on the player's own game. It refers to nothing of Dailies or Create, so the mixins can call it whatever is
 * installed.
 */
public final class CreateMarks {

    /** The most blocks kept for one cluster: a glue selection is far smaller */
    private static final int MOST_BLOCKS = 1 << 14;
    private static final StackWalker WALKER = StackWalker.getInstance();

    private static final Map<Object, long[]> CLUSTERS = Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile @Nullable Component status = null;

    private CreateMarks() {
    }

    /** A cluster outline was made from these blocks */
    public static void clusterMade(Object outline, @Nullable Iterable<?> blocks) {
        if (blocks == null) {
            return;
        }
        long[] packed = new long[16];
        int count = 0;
        for (Object block : blocks) {
            if (block instanceof BlockPos pos && count < MOST_BLOCKS) {
                if (count == packed.length) {
                    packed = Arrays.copyOf(packed, count * 2);
                }
                packed[count++] = pos.asLong();
            }
        }
        long[] kept = Arrays.copyOf(packed, count);
        Arrays.sort(kept);
        CLUSTERS.put(outline, kept);
    }

    /** The blocks a cluster outline was made from, sorted, or null when it was made before the compat was looking */
    static long @Nullable [] cluster(Object outline) {
        return CLUSTERS.get(outline);
    }

    /**
     * The player's own game puts a line above the hotbar: kept when Create (or the Catnip library its lines go through)
     * asked for it, so a line of another mod's, or the server's, is left alone
     */
    public static void statusShown(Component line) {
        boolean create = WALKER.walk(frames -> frames.limit(12).anyMatch(frame -> {
            String name = frame.getClassName();
            return name.startsWith("com.simibubi.create.") || name.startsWith("net.createmod.catnip.lang.");
        }));
        status = create ? line : null;
    }

    /** The last line Create put above the hotbar, or null when another has been put there since */
    static @Nullable Component status() {
        return status;
    }

}
