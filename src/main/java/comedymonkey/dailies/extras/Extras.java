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

    /** The recorder's HUD at this moment of the open recording, as {@link HudLayer#numbers()} gives it; null when there is none */
    public static float[] recordedHud() {
        return HudLayer.numbers();
    }

    /** The same numbers for the live game's own player, as a recording would hold them now; null outside a world */
    public static float[] liveHud() {
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft.player == null || minecraft.gameMode == null) {
            return null;
        }
        return HudLayer.numbers(HudState.of(minecraft.player, minecraft.gameMode.getPlayerMode()));
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
