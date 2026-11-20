package comedymonkey.dailiescompat.supplementaries.client;

import comedymonkey.dailiescompat.supplementaries.state.SuppStates;
import net.mehvahdjukaar.supplementaries.client.screens.CannonScreen;
import net.mehvahdjukaar.supplementaries.client.screens.PresentScreen;
import net.mehvahdjukaar.supplementaries.client.screens.RedMerchantScreen;
import net.mehvahdjukaar.supplementaries.client.screens.TrappedPresentScreen;
import net.mehvahdjukaar.supplementaries.client.screens.widgets.MultiLineEditBoxWidget;
import net.mehvahdjukaar.supplementaries.common.inventories.RedMerchantMenu;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.trading.MerchantOffers;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Extras for Supplementaries' windows that the server opens and Dailies draws itself (their slots, the item on the
 * pointer, the pointer): what each window's screen holds that the server never sent. A present's recipient and message as
 * typed and whether it was packed; whether a trapped present was primed; a cannon's pitch and yaw as typed and the power
 * picked; and the red merchant's offers, which come in a message of Supplementaries' own rather than the game's, with
 * its level, how far its list is scrolled and the offer picked. Put back on the window Dailies builds in playback straight
 * into the widgets, never through the screen's own buttons, which send what was set.
 * <p>
 * The other windows need nothing: the notice board (its book or map is drawn from the slot), the pulley, the sack, the
 * lunch basket and the safe show only their slots.
 */
final class SuppWindows {

    static final String PRESENT = "present";
    static final String PRESENT_MENU = "supplementaries:present";
    static final String TRAPPED_PRESENT = "trapped_present";
    static final String TRAPPED_PRESENT_MENU = "supplementaries:trapped_present";
    static final String CANNON = "cannon";
    static final String CANNON_MENU = "supplementaries:cannon";
    static final String MERCHANT = "red_merchant";
    static final String MERCHANT_MENU = "supplementaries:red_merchant";

    /** The offers last put on each playback window, so they are read again only when they change */
    private static final Map<AbstractContainerScreen<?>, byte[]> OFFERS_SHOWN = new WeakHashMap<>();

    private SuppWindows() {
    }

    // --- A present ---

    static byte @Nullable [] capturePresent(AbstractContainerScreen<?> screen) {
        if (!(screen instanceof PresentScreen)) {
            return null;
        }
        MultiLineEditBoxWidget recipient = Reflect.get(screen, "recipient");
        MultiLineEditBoxWidget description = Reflect.get(screen, "descriptionBox");
        return SuppStates.present(new SuppStates.Present(recipient == null ? "" : recipient.getText(), description == null ? "" : description.getText(),
            Reflect.get(screen, "packed")));
    }

    static void applyPresent(AbstractContainerScreen<?> screen, byte[] state) {
        if (!(screen instanceof PresentScreen)) {
            return;
        }
        SuppStates.Present recorded = SuppStates.readPresent(state);
        MultiLineEditBoxWidget recipient = Reflect.get(screen, "recipient");
        MultiLineEditBoxWidget description = Reflect.get(screen, "descriptionBox");
        if (recipient == null || description == null) {
            return;
        }
        if (!recipient.getText().equals(recorded.recipient())) {
            recipient.setText(recorded.recipient());
        }
        if (!description.getText().equals(recorded.description())) {
            description.setText(recorded.description());
        }
        Reflect.set(screen, "packed", recorded.packed());
        // As the screen sets its widgets' state, without the message it sends when packing changes
        boolean hasItem = screen.getMenu().getSlot(0).hasItem();
        recipient.setState(hasItem, recorded.packed());
        description.setState(hasItem, recorded.packed());
        Object pack = Reflect.get(screen, "packButton");
        if (pack != null) {
            Reflect.call(pack, "setState", new Class<?>[]{boolean.class, boolean.class}, hasItem, recorded.packed());
        }
    }

    // --- A trapped present ---

    static byte @Nullable [] captureTrappedPresent(AbstractContainerScreen<?> screen) {
        if (!(screen instanceof TrappedPresentScreen)) {
            return null;
        }
        return SuppStates.present(new SuppStates.Present("", "", Reflect.get(screen, "primed")));
    }

    static void applyTrappedPresent(AbstractContainerScreen<?> screen, byte[] state) {
        if (!(screen instanceof TrappedPresentScreen)) {
            return;
        }
        boolean primed = SuppStates.readPresent(state).packed();
        Reflect.set(screen, "primed", primed);
        Object pack = Reflect.get(screen, "packButton");
        if (pack != null) {
            Reflect.call(pack, "setState", new Class<?>[]{boolean.class, boolean.class}, screen.getMenu().getSlot(0).hasItem(), primed);
        }
    }

    // --- A cannon ---

    static byte @Nullable [] captureCannon(AbstractContainerScreen<?> screen) {
        if (!(screen instanceof CannonScreen)) {
            return null;
        }
        EditBox pitch = Reflect.get(screen, "pitchSelector");
        EditBox yaw = Reflect.get(screen, "yawSelector");
        Object power = Reflect.get(screen, "powerSelector");
        return SuppStates.cannon(new SuppStates.Cannon(pitch == null ? "" : pitch.getValue(), yaw == null ? "" : yaw.getValue(),
            power == null ? 0 : (byte) Reflect.get(power, "power")));
    }

    static void applyCannon(AbstractContainerScreen<?> screen, byte[] state) {
        if (!(screen instanceof CannonScreen)) {
            return;
        }
        SuppStates.Cannon recorded = SuppStates.readCannon(state);
        EditBox pitch = Reflect.get(screen, "pitchSelector");
        EditBox yaw = Reflect.get(screen, "yawSelector");
        Object power = Reflect.get(screen, "powerSelector");
        if (pitch != null && !pitch.getValue().equals(recorded.pitch())) {
            pitch.setValue(recorded.pitch());
        }
        if (yaw != null && !yaw.getValue().equals(recorded.yaw())) {
            yaw.setValue(recorded.yaw());
        }
        if (power != null) {
            Reflect.set(power, "power", recorded.power());
        }
    }

    // --- The red merchant ---

    static byte @Nullable [] captureMerchant(AbstractContainerScreen<?> screen) {
        if (!(screen instanceof RedMerchantScreen) || !(screen.getMenu() instanceof RedMerchantMenu menu)) {
            return null;
        }
        return SuppStates.merchant(new SuppStates.Merchant(Texts.encode(MerchantOffers.STREAM_CODEC, menu.getOffers()), menu.getTraderLevel(), menu.getTraderXp(),
            menu.showProgressBar(), menu.canRestock(), Reflect.get(screen, "scrollOff"), Reflect.get(screen, "shopItem")));
    }

    static void applyMerchant(AbstractContainerScreen<?> screen, byte[] state) {
        if (!(screen instanceof RedMerchantScreen) || !(screen.getMenu() instanceof RedMerchantMenu menu)) {
            return;
        }
        SuppStates.Merchant recorded = SuppStates.readMerchant(state);
        byte[] offers = recorded.offers();
        if (!Arrays.equals(OFFERS_SHOWN.get(screen), offers)) {
            MerchantOffers read = Texts.decode(MerchantOffers.STREAM_CODEC, offers);
            if (read != null) {
                menu.setOffers(read);
            }
            OFFERS_SHOWN.put(screen, offers);
        }
        menu.setMerchantLevel(recorded.level());
        menu.setXp(recorded.xp());
        menu.setShowProgressBar(recorded.progress());
        menu.setCanRestock(recorded.restock());
        Reflect.set(screen, "scrollOff", recorded.scroll());
        Reflect.set(screen, "shopItem", recorded.picked());
    }

}
