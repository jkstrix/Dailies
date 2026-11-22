package comedymonkey.dailiescompat.create.client;

import com.simibubi.create.content.logistics.filter.AttributeFilterWhitelistMode;
import comedymonkey.dailiescompat.create.state.States;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.Arrays;
import java.util.List;

/**
 * Create's filter windows, and its logistics windows with an address, are opened by the server, so Dailies records and
 * draws them itself (their slots, the item on the pointer, the pointer). What the player changes in them besides the
 * slots is the player's game's own until the window closes: the list filter's allow or deny and whether it matches
 * data, the attribute filter's mode and the attributes added, and the address typed into a package filter, a package
 * port or a redstone requester. These extras keep those, so playback shows the buttons and the address as the player
 * set them rather than as the window was when opened.
 */
final class FilterExtras {

    static final String FILTER = "filter";
    static final String ATTRIBUTE_FILTER = "attribute_filter";
    static final String PACKAGE_FILTER = "package_filter";
    static final String PACKAGE_PORT = "package_port";
    static final String REDSTONE_REQUESTER = "redstone_requester";

    private FilterExtras() {
    }

    // --- The list filter ---

    static byte[] captureFilter(AbstractContainerScreen<?> screen) {
        AbstractContainerMenu menu = screen.getMenu();
        boolean respectData = Reflect.get(menu, "respectNBT");
        boolean deny = Reflect.get(menu, "blacklist");
        return States.filter(new States.Filter(new int[]{respectData ? 1 : 0, deny ? 1 : 0}, List.of(), ""));
    }

    static void applyFilter(AbstractContainerScreen<?> screen, byte[] state) {
        States.Filter recorded = States.readFilter(state);
        if (recorded.modes().length < 2) {
            return;
        }
        AbstractContainerMenu menu = screen.getMenu();
        Reflect.setBoolean(menu, "respectNBT", recorded.modes()[0] == 1);
        Reflect.setBoolean(menu, "blacklist", recorded.modes()[1] == 1);
    }

    // --- The attribute filter ---

    static byte[] captureAttributes(AbstractContainerScreen<?> screen) {
        AttributeFilterWhitelistMode mode = Reflect.get(screen.getMenu(), "whitelistMode");
        List<Component> listed = Reflect.get(screen, "selectedAttributes");
        return States.filter(new States.Filter(new int[]{mode == null ? 0 : mode.ordinal()}, Texts.jsons(listed), ""));
    }

    static void applyAttributes(AbstractContainerScreen<?> screen, byte[] state) {
        States.Filter recorded = States.readFilter(state);
        AttributeFilterWhitelistMode[] modes = AttributeFilterWhitelistMode.values();
        if (recorded.modes().length >= 1 && recorded.modes()[0] >= 0 && recorded.modes()[0] < modes.length) {
            Reflect.set(screen.getMenu(), "whitelistMode", modes[recorded.modes()[0]]);
        }
        List<Component> listed = Reflect.get(screen, "selectedAttributes");
        List<Component> wanted = Texts.reads(recorded.lines());
        if (!wanted.isEmpty() && !Arrays.equals(listed.stream().map(Component::getString).toArray(), wanted.stream().map(Component::getString).toArray())) {
            listed.clear();
            listed.addAll(wanted);
        }
    }

    // --- An address: the package filter's, a package port's, a redstone requester's ---

    static byte[] captureAddress(AbstractContainerScreen<?> screen) {
        EditBox address = Reflect.get(screen, "addressBox");
        return States.filter(new States.Filter(new int[0], List.of(), address == null ? "" : address.getValue()));
    }

    static void applyAddress(AbstractContainerScreen<?> screen, byte[] state) {
        EditBox address = Reflect.get(screen, "addressBox");
        String wanted = States.readFilter(state).address();
        if (address != null && !address.getValue().equals(wanted)) {
            // The box tells the server each change: in playback it is told nothing, and keeps telling nothing
            address.setResponder(changed -> {
            });
            address.setValue(wanted);
        }
    }

}
