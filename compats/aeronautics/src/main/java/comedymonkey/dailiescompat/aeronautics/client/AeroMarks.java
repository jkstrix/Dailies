package comedymonkey.dailiescompat.aeronautics.client;

import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

/**
 * What the compat's status-line mixin notes as the mods do it, for the status line's part to read at the end of a tick:
 * the last line one of the mods put above the hotbar on the player's own game. It refers to nothing of Dailies or the
 * mods, so the mixin can call it whatever is installed.
 */
public final class AeroMarks {

    /** The mods whose lines are kept, by the start of their classes' names */
    private static final String[] MODS = {"dev.simulated_team.", "dev.eriksonn.aeronautics.", "dev.ryanhcode.offroad.", "com.raprek.gyro.",
        "dev.propulsionteam.", "dev.silvergold.simulatedcoasters."};
    /** As far down the stack as the Create compat looks for its own lines, from the same depth, so the two agree */
    private static final int DEPTH = 12;
    private static final StackWalker WALKER = StackWalker.getInstance();

    private static volatile @Nullable Component status = null;
    private static volatile @Nullable Boolean createCompat = null;

    private AeroMarks() {
    }

    /**
     * The player's own game puts a line above the hotbar: kept when one of the mods asked for it. A line that went through
     * Create's code or Catnip's language helper (as Simulated's own wording often does) is the Create compat's to record
     * when that is installed, so it is left to it rather than drawn twice; a line of any other mod's, or the server's, is
     * left alone.
     */
    public static void statusShown(Component line) {
        boolean[] seen = new boolean[2];
        WALKER.walk(frames -> {
            frames.limit(DEPTH).forEach(frame -> {
                String name = frame.getClassName();
                if (name.startsWith("com.simibubi.create.") || name.startsWith("net.createmod.catnip.lang.")) {
                    seen[0] = true;
                }
                for (String mod : MODS) {
                    if (name.startsWith(mod)) {
                        seen[1] = true;
                    }
                }
            });
            return null;
        });
        boolean createsOwn = seen[0] && createCompat();
        status = seen[1] && !createsOwn ? line : null;
    }

    private static boolean createCompat() {
        Boolean loaded = createCompat;
        if (loaded == null) {
            loaded = ModList.get() != null && ModList.get().isLoaded("dailies_create");
            createCompat = loaded;
        }
        return loaded;
    }

    /** The last line one of the mods put above the hotbar, or null when another has been put there since */
    static @Nullable Component status() {
        return status;
    }

}
