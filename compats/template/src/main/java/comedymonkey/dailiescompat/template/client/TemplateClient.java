package comedymonkey.dailiescompat.template.client;

import comedymonkey.dailiescompat.template.ExampleNoteItem;
import comedymonkey.dailiescompat.template.TemplateMod;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * The template compat's client half, as a mod of its own on the client. Everything that refers to Dailies is in
 * {@link TemplateDailies}, which is only touched when Dailies is loaded: that is the whole of the soft dependency.
 */
@Mod(value = TemplateMod.ID, dist = Dist.CLIENT)
public final class TemplateClient {

    private static final boolean DAILIES = ModList.get().isLoaded("dailies");

    public TemplateClient(IEventBus modBus) {
        ExampleNoteItem.opener = () -> Minecraft.getInstance().gui.setScreen(new ExampleScreen(0, false));
        modBus.addListener((RegisterGuiLayersEvent event) -> event.registerAbove(VanillaGuiLayers.HOTBAR,
            Identifier.fromNamespaceAndPath(TemplateMod.ID, "home"), HomeOverlay::drawLive));
        if (DAILIES) {
            TemplateDailies.register();
        }
    }

    /** Whether the game is showing a Dailies recording rather than a live world */
    static boolean inPlayback() {
        return DAILIES && TemplateDailies.inPlayback();
    }

}
