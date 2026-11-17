package comedymonkey.dailies.extras;

import net.neoforged.bus.api.IEventBus;

/**
 * Starts three smaller parts of Dailies: the recorder's HUD (specification REC-13, PLAY-3), skins and capes that
 * show with no network (REC-14), and the notice and list of mods that differ from those a recording was made with
 * (PLAY-5).
 */
public final class Extras {

    private Extras() {
    }

    /**
     * @param modBus the mod's own event bus, for the key binding and the HUD layer
     */
    public static void init(IEventBus modBus) {
        HudCapture.init();
        HudLayer.init();
        EntityClocks.init();
        SkinCapture.init();
        RecordedSkins.init();
        ModDifferences.init();
        modBus.addListener(HudLayer::register);
        modBus.addListener(ModDifferences::registerKey);
    }

}
