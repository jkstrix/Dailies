package comedymonkey.dailiescompat.template;

import comedymonkey.dailiescompat.template.server.TemplateServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The template compat's common half (specification CPT-5). In a real compat this half is usually empty: the mod being
 * made a compat for has its own items and screens, and only the server half below goes here. The template brings an
 * item of its own, the example note, so it has a screen and an overlay to record.
 * <p>
 * The server half is registered only when the Dailies Companion is loaded, through a class of its own that nothing
 * else touches, so the mod runs on a server without the companion.
 */
@Mod(TemplateMod.ID)
public final class TemplateMod {

    public static final String ID = "dailies_template";

    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ID);
    /** Using it opens the example screen; holding it shows the example overlay */
    public static final DeferredItem<ExampleNoteItem> NOTE = ITEMS.registerItem("example_note", ExampleNoteItem::new, properties -> properties.stacksTo(1));

    public TemplateMod(IEventBus modBus) {
        ITEMS.register(modBus);
        if (ModList.get().isLoaded("dailies_companion")) {
            TemplateServer.register();
        }
    }

}
