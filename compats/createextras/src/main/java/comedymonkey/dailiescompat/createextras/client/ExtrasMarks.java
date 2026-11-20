package comedymonkey.dailiescompat.createextras.client;

import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

/**
 * What the compat's status-line mixin notes as the mods do it, for the status line's part to read at the end of a tick:
 * the last line one of the add-ons put above the hotbar on the player's own game. It refers to nothing of Dailies or the
 * mods, so the mixin can call it whatever is installed.
 */
public final class ExtrasMarks {

    /** The add-ons whose lines are kept, by the start of their classes' names */
    private static final String[] MODS = {"com.maxenonyme.", "com.hlysine.create_connected.", "fr.iglee42.createcasing.", "dev.qwxon.tracks.",
        "com.railwayteam.railways.", "com.possible_triangle.", "com.pedrorok.hypertube."};
    /** The mods the Create Aeronautics compat keeps the lines of, as it names them */
    private static final String[] AERONAUTICS = {"dev.simulated_team.", "dev.eriksonn.aeronautics.", "dev.ryanhcode.offroad.", "com.raprek.gyro.",
        "dev.propulsionteam.", "dev.silvergold.simulatedcoasters."};
    /** As far down the stack as the Create compat looks for its own lines, from the same depth, so the compats agree */
    private static final int DEPTH = 12;
    private static final StackWalker WALKER = StackWalker.getInstance();

    private static volatile @Nullable Component status = null;
    private static volatile @Nullable Boolean createCompat = null;
    private static volatile @Nullable Boolean aeronauticsCompat = null;

    private ExtrasMarks() {
    }

    /**
     * The player's own game puts a line above the hotbar: kept when one of the add-ons asked for it. A line that went
     * through Create's code or Catnip's language helper (as a mixin of an add-on's into Create's own classes does) is the
     * Create compat's to record when that is installed, and one that went through Create Aeronautics' mods the Create
     * Aeronautics compat's, so it is left to them rather than drawn twice; a line of any other mod's, or the server's, is
     * left alone.
     */
    public static void statusShown(Component line) {
        boolean[] seen = new boolean[3];
        WALKER.walk(frames -> {
            frames.limit(DEPTH).forEach(frame -> {
                String name = frame.getClassName();
                if (name.startsWith("com.simibubi.create.") || name.startsWith("net.createmod.catnip.lang.")) {
                    seen[0] = true;
                }
                for (String mod : AERONAUTICS) {
                    if (name.startsWith(mod)) {
                        seen[1] = true;
                    }
                }
                for (String mod : MODS) {
                    if (name.startsWith(mod)) {
                        seen[2] = true;
                    }
                }
            });
            return null;
        });
        boolean othersOwn = seen[0] && loaded("dailies_create", true) || seen[1] && loaded("dailies_aeronautics", false);
        status = seen[2] && !othersOwn ? line : null;
    }

    private static boolean loaded(String mod, boolean create) {
        Boolean known = create ? createCompat : aeronauticsCompat;
        if (known == null) {
            known = ModList.get() != null && ModList.get().isLoaded(mod);
            if (create) {
                createCompat = known;
            } else {
                aeronauticsCompat = known;
            }
        }
        return known;
    }

    /** The last line one of the add-ons put above the hotbar, or null when another has been put there since */
    static @Nullable Component status() {
        return status;
    }

}
