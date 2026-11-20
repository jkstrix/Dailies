package comedymonkey.dailiescompat.extras.client;

import comedymonkey.dailies.api.compat.Claims;
import comedymonkey.dailiescompat.extras.state.ExtrasStates;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import top.theillusivec4.curios.client.gui.CuriosScreen;
import top.theillusivec4.curios.common.inventory.container.CuriosContainer;

import java.util.ArrayList;
import java.util.List;

/**
 * Extras for the Curios window, which the server opens and Dailies draws itself: which page of curio slots is shown, and
 * whether the cosmetic slots are shown in place of the others. The window's menu lays its slots out by page on the
 * player's game, and the server tells it a page in a message of Curios' own that Dailies' window recording does not keep,
 * so the page is recorded here and put back on the window Dailies builds in playback as Curios turns it, the items Dailies
 * filled it with kept in their places.
 */
final class CuriosExtras {

    static final String NAME = "curios";
    private static final String MENU = "curios:curios_container";

    private CuriosExtras() {
    }

    static void claim(Claims claims) {
        claims.windowExtras(NAME, MENU, Parts.extras(CuriosExtras::capture, CuriosExtras::apply, 1));
    }

    static byte @Nullable [] capture(AbstractContainerScreen<?> screen) {
        if (!(screen.getMenu() instanceof CuriosContainer menu)) {
            return null;
        }
        return ExtrasStates.curios(new ExtrasStates.Curios(menu.currentPage, menu.isViewingCosmetics));
    }

    static void apply(AbstractContainerScreen<?> screen, byte[] state) {
        if (!(screen.getMenu() instanceof CuriosContainer menu)) {
            return;
        }
        ExtrasStates.Curios recorded = ExtrasStates.readCurios(state);
        // A page this window does not have (fewer curio slots here) stays at the last it has
        int page = Math.max(0, Math.min(Math.max(0, menu.totalPages - 1), recorded.page()));
        if (menu.currentPage == page && menu.isViewingCosmetics == recorded.cosmetics()) {
            return;
        }
        // Turning the page makes the window's slots again: what Dailies filled them with goes back in the same places
        List<ItemStack> filled = new ArrayList<>(menu.slots.size());
        for (int i = 0; i < menu.slots.size(); i++) {
            filled.add(menu.getSlot(i).getItem().copy());
        }
        menu.isViewingCosmetics = recorded.cosmetics();
        // On the player's game the menu turns the page without a word to the server
        menu.setPage(page);
        for (int i = 0; i < menu.slots.size() && i < filled.size(); i++) {
            menu.getSlot(i).set(filled.get(i));
        }
        if (screen instanceof CuriosScreen curios) {
            curios.panelWidth = menu.panelWidth;
            curios.updateRenderButtons();
        }
    }

}
