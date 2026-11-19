package comedymonkey.dailiescompat.extras.client;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * What the compat's mixins note as the mods draw, for the parts to read: the lines a compass draws while its layer is
 * drawn, and the last line one of the mods put above the hotbar on the player's own game. It refers to nothing of
 * Dailies or the mods, so the mixins can call it whatever is installed.
 */
public final class ExtrasMarks {

    /** The two compasses, by the order of their layers */
    public static final int EXPLORERS = 0;
    public static final int NATURES = 1;

    /** The mods' classes whose lines above the hotbar are the player's own game's alone, never sent by a server as well */
    private static final String[] LINES_OF = {"net.p3pp3rf1y.sophisticatedbackpacks.client.", "de.maxhenkel.corpse.net.MessageOpenHistory"};
    /** As far down the stack as the other compats look for their own lines */
    private static final int DEPTH = 12;
    private static final StackWalker WALKER = StackWalker.getInstance();

    /** The compass whose layer is being drawn, or -1 */
    private static int drawing = -1;
    private static final List<Line> lines = new ArrayList<>();

    private static volatile @Nullable Component status = null;

    private ExtrasMarks() {
    }

    /** One string a compass drew, where it drew it */
    record Line(String text, int x, int y, int colour, boolean right) {
    }

    // --- The compasses ---

    static void layerStarts(int compass) {
        drawing = compass;
        lines.clear();
    }

    /** The lines drawn since the layer started, and the layer done with */
    static List<Line> layerEnds(int compass) {
        if (drawing != compass) {
            return List.of();
        }
        drawing = -1;
        List<Line> drawn = List.copyOf(lines);
        lines.clear();
        return drawn;
    }

    /** A compass's layer drew a string, from its left end ({@code right} false) or its right end at {@code x} */
    public static void compassLine(int compass, String text, int x, int y, int colour, boolean right) {
        if (drawing == compass && text != null) {
            lines.add(new Line(text, x, y, colour, right));
        }
    }

    // --- The line above the hotbar ---

    /**
     * The game puts a line above the hotbar: kept when one of the mods' own client code put it there (Sophisticated
     * Backpacks' tool swap refused, Corpse's empty death history), found by a walk of the nearest
     * frames of the stack. Lines a server sends, and those the mods set on both sides (whose copy from the server arrives
     * in a packet the recording holds), are left alone.
     */
    public static void statusShown(Component line) {
        boolean[] ours = new boolean[1];
        WALKER.walk(frames -> {
            frames.limit(DEPTH).forEach(frame -> {
                String name = frame.getClassName();
                for (String of : LINES_OF) {
                    if (name.startsWith(of)) {
                        ours[0] = true;
                    }
                }
            });
            return null;
        });
        status = ours[0] ? line : null;
    }

    /** The last line one of the mods put above the hotbar, or null when another has been put there since */
    static @Nullable Component status() {
        return status;
    }

}
