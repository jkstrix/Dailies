package comedymonkey.dailies.windows;

import net.minecraft.network.protocol.Packet;
import net.neoforged.bus.api.IEventBus;

/**
 * Starts the recorder's windows: the play screens the recorder had open, recorded (specification REC-25) and drawn
 * again in playback and exports (PLAY-20, EXP-16).
 */
public final class Windows {

    private Windows() {
    }

    /** @param modBus the mod's own event bus, for the layer windows are drawn in */
    public static void init(IEventBus modBus) {
        WindowCapture.init();
        WindowLayer.init();
        modBus.addListener(WindowLayer::register);
    }

    /** The recorder's game is about to apply a packet: an open-screen packet says what the next window is. Client thread */
    public static void seen(Packet<?> packet) {
        WindowCapture.seen(packet);
    }

}
