package comedymonkey.dailiescompat.create.client;

import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.stockTicker.StockKeeperRequestScreen;
import comedymonkey.dailiescompat.create.state.States;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The stock keeper's request window is opened by the server, so Dailies records and draws it itself. What the player
 * does in it besides its slots stays on the player's game until an order is sent: the search typed, the address, the
 * order being built, and how far the list is scrolled. These extras keep those, and put them back on the window Dailies
 * builds in playback, which sends nothing.
 */
final class StockKeeperExtras {

    static final String NAME = "stock_keeper_request";
    static final String MENU = "create:stock_keeper_request";

    private StockKeeperExtras() {
    }

    static byte[] capture(AbstractContainerScreen<?> screen) {
        StockKeeperRequestScreen keeper = (StockKeeperRequestScreen) screen;
        EditBox search = keeper.searchBox;
        EditBox address = keeper.addressBox;
        List<States.Stack> order = new ArrayList<>();
        for (BigItemStack wanted : new ArrayList<>(keeper.itemsToOrder)) {
            order.add(new States.Stack(Texts.item(wanted.stack), wanted.count, false));
        }
        return States.stockKeeper(new States.StockKeeper(search == null ? "" : search.getValue(), search != null && search.isFocused(),
            search == null ? 0 : search.getCursorPosition(), address == null ? "" : address.getValue(), order, keeper.itemScroll.getValue()));
    }

    static void apply(AbstractContainerScreen<?> screen, byte[] state) {
        if (!(screen instanceof StockKeeperRequestScreen keeper)) {
            return;
        }
        States.StockKeeper recorded = States.readStockKeeper(state);
        EditBox search = keeper.searchBox;
        if (search != null) {
            if (!search.getValue().equals(recorded.search())) {
                search.setValue(recorded.search());
                // The list filtered by the search, as the window does on its next tick once the player types (playback
                // does not tick it); only once the window holds a list of stock
                if (keeper.currentItemSource != null) {
                    Reflect.call(keeper, "refreshSearchResults", boolean.class, false);
                }
            }
            search.setFocused(recorded.searching());
            if (recorded.searching() && search.getCursorPosition() != recorded.cursor()) {
                search.setCursorPosition(recorded.cursor());
                search.setHighlightPos(search.getCursorPosition());
            }
        }
        EditBox address = keeper.addressBox;
        if (address != null && !address.getValue().equals(recorded.address())) {
            address.setResponder(changed -> {
            });
            address.setValue(recorded.address());
        }
        if (!sameOrder(keeper.itemsToOrder, recorded.order())) {
            keeper.itemsToOrder.clear();
            for (States.Stack wanted : recorded.order()) {
                ItemStack stack = Texts.stack(wanted.item());
                if (!stack.isEmpty()) {
                    keeper.itemsToOrder.add(new BigItemStack(stack, wanted.count()));
                }
            }
        }
        if (keeper.itemScroll.getValue() != recorded.scroll() || keeper.itemScroll.getChaseTarget() != recorded.scroll()) {
            keeper.itemScroll.startWithValue(recorded.scroll());
        }
    }

    private static boolean sameOrder(List<BigItemStack> held, List<States.Stack> recorded) {
        if (held.size() != recorded.size()) {
            return false;
        }
        for (int i = 0; i < held.size(); i++) {
            if (!Texts.item(held.get(i).stack).equals(recorded.get(i).item()) || held.get(i).count != recorded.get(i).count()) {
                return false;
            }
        }
        return true;
    }

}
