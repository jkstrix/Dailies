package comedymonkey.dailiescompat.extras.client;

import com.mojang.authlib.GameProfile;
import comedymonkey.dailies.api.compat.Claims;
import comedymonkey.dailies.api.compat.ScreenPart;
import comedymonkey.dailiescompat.extras.state.ExtrasStates;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.client.gui.BackpackScreen;
import net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer;
import net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContext;
import net.p3pp3rf1y.sophisticatedcore.client.gui.controls.TextBox;
import net.p3pp3rf1y.sophisticatedcore.upgrades.UpgradeHandler;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Sophisticated Backpacks' backpack window. The server opens it, but Dailies' own window recording cannot draw it: the
 * window's menu finds its backpack in the hand or on the back of the player it is opened for, and Dailies' stand-in player
 * for a window holds none, so Sophisticated would give it an empty backpack of no slots; and the backpack's upgrades and
 * their settings' slots sit past the window's own slots, where Dailies does not fill. So the window is claimed whole
 * ({@link #BACKPACK}): recorded as the backpack itself as the recorder's game held it (its size, colours and upgrades'
 * settings are on it), every slot of the window (storage, the player's inventory, the upgrades and their settings'
 * slots), the item on the pointer, the upgrade tab open, how far a tall backpack's storage was scrolled and the search as
 * typed; and drawn with Sophisticated's own window, built for a stand-in of the compat's own holding that backpack, its
 * upgrades put in before the window is made (so its tabs are made as they were), its slots filled as the server fills
 * them, and the tab, scroll and search put back as the screen is laid out.
 * <p>
 * The backpack's settings window ({@link #SETTINGS}) is settings, not play, and is left out: claimed so that Dailies'
 * own recording leaves it alone, and never recorded.
 */
final class Backpacks {

    static final String BACKPACK = "backpack";
    static final String SETTINGS = "backpack_settings";
    private static final String BACKPACK_MENU = "sophisticatedbackpacks:backpack";
    private static final String SETTINGS_MENU = "sophisticatedbackpacks:settings";

    private static final String THE_BACKPACK = "backpack";
    private static final String INVENTORY = "inventory";
    private static final String UPGRADES = "upgrades";
    /** The window's slots and the pointer change with every click: written at most ten times a second */
    private static final int ITEMS_TICKS = 2;
    private static final GameProfile OWNER = new GameProfile(UUID.nameUUIDFromBytes("Dailies backpack".getBytes(StandardCharsets.UTF_8)), "Backpack");

    private Backpacks() {
    }

    static void claim(Claims claims) {
        claims.menu(BACKPACK, BACKPACK_MENU, Parts.screen(Backpacks::capture, Backpacks::build, ITEMS_TICKS));
        claims.menu(SETTINGS, SETTINGS_MENU, new ScreenPart() {
            @Override
            public byte @Nullable [] capture(Screen screen) {
                // Settings, not play: never recorded
                return null;
            }

            @Override
            public @Nullable Screen build(byte[] state, int version) {
                return null;
            }
        });
    }

    static byte @Nullable [] capture(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> window) || !(window.getMenu() instanceof BackpackContainer menu)) {
            return null;
        }
        IBackpackWrapper wrapper = menu.getStorageWrapper();
        CompoundTag items = new CompoundTag();
        items.put(THE_BACKPACK, Stacks.save(wrapper.getBackpack()));
        List<ItemStack> slots = new ArrayList<>(menu.getTotalSlotsNumber());
        for (int i = 0; i < menu.getTotalSlotsNumber(); i++) {
            slots.add(menu.getSlot(i).getItem());
        }
        items.put(Stacks.SLOTS, Stacks.saveAll(slots));
        items.put(Stacks.CARRIED, Stacks.save(menu.getCarried()));
        items.putInt(INVENTORY, menu.getInventorySlotsSize());
        items.putInt(UPGRADES, menu.getNumberOfUpgradeSlots());
        Object scrollPanel = Reflect.get(screen, "inventoryScrollPanel");
        float scroll = scrollPanel == null ? 0.0F : Reflect.<Float>get(scrollPanel, "scrollDistance");
        TextBox search = Reflect.get(screen, "searchBox");
        return ExtrasStates.backpack(new ExtrasStates.Backpack(Texts.json(screen.getTitle()), new ExtrasStates.Items(Stacks.bytes(items)),
            wrapper.getOpenTabId().orElse(-1), scroll, search == null ? "" : search.getValue()));
    }

    static @Nullable Screen build(byte[] data) {
        ExtrasStates.Backpack recorded = ExtrasStates.readBackpack(data);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return null;
        }
        CompoundTag items = Stacks.tag(recorded.items().saved());
        ItemStack backpack = Stacks.load(items.get(THE_BACKPACK));
        if (backpack.isEmpty()) {
            return null;
        }
        List<ItemStack> slots = Stacks.loadAll(items, Stacks.SLOTS);
        int inventory = items.getIntOr(INVENTORY, 0);
        int upgrades = items.getIntOr(UPGRADES, 0);
        // A stand-in of the compat's own, never in the world, holding the backpack in its first slot as a hand holds one
        RemotePlayer owner = new RemotePlayer(minecraft.level, OWNER);
        owner.getInventory().setSelectedSlot(0);
        owner.getInventory().setItem(0, backpack);
        BackpackContext context = new BackpackContext.Item("main", 0);
        IBackpackWrapper wrapper = context.getBackpackWrapper(owner);
        if (wrapper == IBackpackWrapper.Noop.INSTANCE) {
            return null;
        }
        // The upgrades first, so the window makes their tabs as the recorder's did; the tab open is on the backpack itself
        UpgradeHandler handler = wrapper.getUpgradeHandler();
        for (int i = 0; i < upgrades && i < handler.size() && inventory + i < slots.size(); i++) {
            handler.setStackInSlot(i, slots.get(inventory + i).copy());
        }
        if (recorded.openTab() >= 0) {
            wrapper.setOpenTabId(recorded.openTab());
        } else {
            wrapper.removeOpenTabId();
        }
        BackpackContainer menu = new BackpackContainer(0, owner, context);
        List<ItemStack> filled = new ArrayList<>(slots.size());
        for (ItemStack stack : slots) {
            filled.add(stack.copy());
        }
        // As the server's first message fills a window: every slot, the upgrades' among them, and the pointer
        menu.initializeContents(0, filled, Stacks.load(items.get(Stacks.CARRIED)));
        return new RecordedBackpack(menu, owner.getInventory(), Texts.read(recorded.title()), recorded);
    }

    /** Sophisticated's backpack screen with the recorded scroll and search put back as it is laid out; it sends nothing as it closes */
    static final class RecordedBackpack extends BackpackScreen {
        private final ExtrasStates.Backpack recorded;

        RecordedBackpack(BackpackContainer menu, Inventory inventory, net.minecraft.network.chat.Component title, ExtrasStates.Backpack recorded) {
            super(menu, inventory, title);
            this.recorded = recorded;
        }

        @Override
        protected void init() {
            super.init();
            TextBox search = Reflect.get(this, "searchBox");
            if (search != null && !search.getValue().equals(this.recorded.search())) {
                search.setValue(this.recorded.search());
            }
            Object scrollPanel = Reflect.get(this, "inventoryScrollPanel");
            if (scrollPanel != null) {
                Reflect.set(scrollPanel, "scrollDistance", this.recorded.scroll());
                Reflect.call(scrollPanel, "updateSlotsPosition", new Class<?>[0]);
            }
        }

        @Override
        protected void containerTick() {
        }

        @Override
        public void removed() {
        }

        @Override
        public void onClose() {
        }
    }

}
