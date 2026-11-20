package comedymonkey.dailiescompat.extras.client;

import comedymonkey.dailies.api.compat.Claims;
import comedymonkey.dailiescompat.extras.state.ExtrasStates;
import de.maxhenkel.corpse.gui.CorpseAdditionalScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.jetbrains.annotations.Nullable;

/**
 * Extras for a corpse's window of further items, which the server opens and Dailies draws itself: the page the screen
 * says it shows ("Page 2 of 3"), which it counts on the player's game as its buttons are clicked; the items of that page
 * come in the window's own slots. The page is read and set by the field's name, as Corpse 1.1.22 names it. A corpse's
 * main window needs nothing here: Dailies draws it whole.
 */
final class CorpseExtras {

    static final String NAME = "corpse_items";
    private static final String MENU = "corpse:corpse_additional_items";

    private CorpseExtras() {
    }

    static void claim(Claims claims) {
        claims.windowExtras(NAME, MENU, Parts.extras(CorpseExtras::capture, CorpseExtras::apply, 1));
    }

    static byte @Nullable [] capture(AbstractContainerScreen<?> screen) {
        if (!(screen instanceof CorpseAdditionalScreen)) {
            return null;
        }
        int page = Reflect.get(screen, "page");
        return ExtrasStates.page(new ExtrasStates.Page(page));
    }

    static void apply(AbstractContainerScreen<?> screen, byte[] state) {
        if (screen instanceof CorpseAdditionalScreen) {
            Reflect.set(screen, "page", ExtrasStates.readPage(state).page());
        }
    }

}
