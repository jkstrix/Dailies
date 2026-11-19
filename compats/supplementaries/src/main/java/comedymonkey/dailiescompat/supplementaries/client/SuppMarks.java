package comedymonkey.dailiescompat.supplementaries.client;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * What the compat's status-line mixin notes as the mods do it, for the status line's part to read at the end of a tick:
 * the last line Supplementaries or Amendments put above the hotbar on the player's own game. It refers to nothing of
 * Dailies or the mods, so the mixin can call it whatever is installed.
 */
public final class SuppMarks {

    /** The mods whose lines are kept, by the start of their classes' names */
    private static final String[] MODS = {"net.mehvahdjukaar.supplementaries.", "net.mehvahdjukaar.amendments."};
    /**
     * Supplementaries' handler of its own messages from the server (a speaker block's line, among others): those messages
     * are kept in the recording and handled again in playback, which puts the line up there too
     */
    private static final String FROM_SERVER = "net.mehvahdjukaar.supplementaries.common.network.ClientReceivers";
    /** As far down the stack as the Create compat looks for its own lines, from the same depth */
    private static final int DEPTH = 12;
    private static final StackWalker WALKER = StackWalker.getInstance();

    private static volatile @Nullable Component status = null;

    private SuppMarks() {
    }

    /**
     * The player's own game puts a line above the hotbar: kept when one of the mods asked for it on the player's own game
     * (a cannon's keys as it is aimed, an altimeter's height, a safe's owner); a line the server sent, in the game's
     * message or in one of Supplementaries' own, or of any other mod's, is left alone.
     */
    public static void statusShown(Component line) {
        boolean[] seen = new boolean[2];
        WALKER.walk(frames -> {
            frames.limit(DEPTH).forEach(frame -> {
                String name = frame.getClassName();
                if (name.startsWith(FROM_SERVER)) {
                    seen[1] = true;
                }
                for (String mod : MODS) {
                    if (name.startsWith(mod)) {
                        seen[0] = true;
                    }
                }
            });
            return null;
        });
        status = seen[0] && !seen[1] ? line : null;
    }

    /** The last line one of the mods put above the hotbar, or null when another has been put there since */
    static @Nullable Component status() {
        return status;
    }

}
