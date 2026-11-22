package comedymonkey.dailiescompat.jade.client;

import comedymonkey.dailies.api.compat.Claims;
import comedymonkey.dailies.api.compat.DailiesCompat;
import comedymonkey.dailies.api.compat.DailiesCompats;
import comedymonkey.dailies.api.compat.DisplayTest;
import comedymonkey.dailies.api.compat.OverlayPart;
import comedymonkey.dailiescompat.jade.JadeCompat;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jetbrains.annotations.Nullable;

/**
 * The Jade compat itself (specification CPT-8): the class that registers with Dailies, loaded only when both Dailies and
 * Jade are (see {@link JadeCompat}). It claims one overlay, Jade's box over play ({@link TooltipBox}), recorded as Jade
 * drew it and drawn again with the recorder's HUD, not under the recorder's windows' look. Jade's other drawing during
 * play is the line of a block being broken along the box's foot, which goes with the box; the rest of what Jade puts on
 * the screen is its settings, its plugin list and its key bindings, which are not play, the mod's name it adds to an
 * item's tooltip, which a window Dailies draws gets again from this game's Jade, and two lines in the chat when its keys
 * turn the box or the narrator off, which are the chat's. A box Jade draws for the Create compat's machines sits beside
 * the goggles' readout the Create compat records; each records its own.
 */
public final class JadeDailies implements DailiesCompat {

    public static final String ID = JadeCompat.ID + ":jade";
    /**
     * Raise when the state in JadeStates changes shape; the part must still read every version before it. 2: Jade 26's
     * elements, on Minecraft 26.3. Version 1 held Jade 15's (Minecraft 1.21.1), which this Jade has no elements for: a box
     * in such a recording is left out
     */
    static final int VERSION = 2;
    /** The box changes as a furnace burns or a block is broken: at most ten times a second */
    private static final int BOX_TICKS = 2;

    private static volatile boolean registered = false;

    private JadeDailies() {
    }

    /** Registers the compat. Only call it when Dailies and Jade are both loaded */
    public static void register() {
        DailiesCompats.register(new JadeDailies());
        registered = true;
    }

    /** Whether the compat is registered with Dailies, which Jade's callbacks ask before they do anything */
    static boolean registered() {
        return registered;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public void claim(Claims claims) {
        claims.overlay(TooltipBox.NAME, new OverlayPart() {
            @Override
            public byte @Nullable [] capture() {
                return TooltipBox.capture();
            }

            @Override
            public void draw(GuiGraphicsExtractor graphics, byte[] state, int version, float partialTick) {
                if (version >= 2) {
                    TooltipBox.draw(graphics, state, partialTick);
                }
            }

            @Override
            public int ticksBetweenWrites() {
                return BOX_TICKS;
            }
        });
    }

    @Override
    public DisplayTest displayTest() {
        return new JadeDisplayTest();
    }

}
