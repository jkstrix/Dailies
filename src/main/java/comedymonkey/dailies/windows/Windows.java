package comedymonkey.dailies.windows;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.protocol.Packet;
import net.neoforged.bus.api.IEventBus;

/**
 * Starts the recorder's windows: the play screens the recorder had open, recorded (specification REC-25) and drawn
 * again in playback and exports (PLAY-20, EXP-16); the screens and overlays of other mods' compats (REC-26, PLAY-21,
 * EXP-17), which register through the compat API (comedymonkey.dailies.api.compat); and the game's own books, lectern and
 * signs, a compat of Dailies' own through the same API (REC-29, PLAY-24, EXP-18).
 */
public final class Windows {

    private Windows() {
    }

    /** @param modBus the mod's own event bus, for the layer windows are drawn in */
    public static void init(IEventBus modBus) {
        WindowCapture.init();
        WindowLayer.init();
        modBus.addListener(WindowLayer::register);
        // Compats' screens and overlays (REC-26, PLAY-21); their layers are placed beside the windows', so registered after it
        CompatCapture.init();
        CompatLayer.init();
        Compats.init();
        modBus.addListener(CompatLayer::register);
        // The game's own books, lectern and signs, a compat of Dailies' own (REC-29); registered first, so a mod's claim on
        // one of these screens is the one left out
        comedymonkey.dailies.windows.vanilla.VanillaCompat.register();
    }

    /** The recorder's game is about to apply a packet: an open-screen packet says what the next window is. Client thread */
    public static void seen(Packet<?> packet) {
        WindowCapture.seen(packet);
    }

    /**
     * Whether the screen open now is a play screen the recording keeps: one a compat records whole, Dailies' own books
     * and signs among them, or a window Dailies records itself. While one pauses the game the recording keeps time
     * (REC-36). Client thread
     */
    public static boolean recordsScreen(Minecraft minecraft) {
        Screen screen = minecraft.gui.screen();
        if (screen == null || minecraft.player == null) {
            return false;
        }
        try {
            return CompatCapture.records(screen) || WindowCapture.records(minecraft.player, screen);
        } catch (Throwable t) {
            return false;
        }
    }

}
