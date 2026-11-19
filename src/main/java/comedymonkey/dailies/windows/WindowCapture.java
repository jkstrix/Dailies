package comedymonkey.dailies.windows;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.GameCodec;
import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.capture.SnapshotExtensions;
import comedymonkey.dailies.mixin.access.ContainerMenuAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.HorseInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundHorseScreenOpenPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.payload.AdvancedOpenScreenPayload;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The recording half of the recorder's windows (specification REC-25). The server's packets for the player's own
 * windows are still left out of the recording (REC-11) and never reach playback's packet handling; what the recorder's
 * screen showed is written instead as {@code dailies:windows} records of Dailies' own, from the window the game holds
 * at the end of each recorded tick: which window, then only what changed in it. Only play screens are kept
 * ({@link WindowRules}). While one is open the pointer is written a few times a second when it has moved, and at once
 * when the slot under it or the stack on it changes, so a click lands where the item moves.
 */
final class WindowCapture {

    /** Ticks between writes of a pointer that only moves: five times a second */
    static final int POINTER_TICKS = 4;
    /** How far, in GUI units, the pointer must move to be written again */
    private static final float POINTER_STEP = 0.5F;

    /** The window opened by the newest open packet the recorder's game applied, or null */
    private static Opened opened = null;
    /** What the recording holds as the window now; null when it holds none */
    private static Window written = null;
    /** The window as the snapshot taken this tick described it; {@link #snapTaken} says whether one was */
    private static Window snap = null;
    private static boolean snapTaken = false;
    private static String recordingId = null;
    private static int ticks = 0;
    private static int pointerWritten = 0;
    private static WindowCodec codec = null;
    private static Object codecFor = null;
    private static final Set<String> SAID = new HashSet<>();

    private WindowCapture() {
    }

    static void init() {
        SnapshotExtensions.register(WindowState.ID, WindowCapture::snapshot);
        Recorder.everyRecordedTick(WindowCapture::tick);
    }

    /** What an open packet said, kept to go with the window when the game has built it */
    private record Opened(int containerId, String menuType, Component title, byte[] extra, int horseEntity, int horseColumns, boolean horse) {
    }

    /** The game is about to apply a packet. Client thread */
    static void seen(Packet<?> packet) {
        if (packet instanceof ClientboundBundlePacket bundle) {
            for (Packet<?> part : bundle.subPackets()) {
                seen(part);
            }
        } else if (packet instanceof ClientboundOpenScreenPacket open) {
            opened = new Opened(open.getContainerId(), key(open.getType()), open.getTitle(), null, -1, 0, false);
        } else if (packet instanceof ClientboundHorseScreenOpenPacket horse) {
            opened = new Opened(horse.getContainerId(), "", Component.empty(), null, horse.getEntityId(), horse.getInventoryColumns(), true);
        } else if (packet instanceof ClientboundCustomPayloadPacket custom && custom.payload() instanceof AdvancedOpenScreenPayload advanced) {
            opened = new Opened(advanced.windowId(), key(advanced.menuType()), advanced.name(), advanced.additionalData(), -1, 0, false);
        }
    }

    private static String key(net.minecraft.world.inventory.MenuType<?> type) {
        ResourceLocation key = type == null ? null : BuiltInRegistries.MENU.getKey(type);
        return key == null ? "" : key.toString();
    }

    private static String menuType(AbstractContainerMenu menu) {
        try {
            return key(menu.getType());
        } catch (UnsupportedOperationException none) {
            // The player's inventory, a horse's and the creative inventory have no menu type
            return null;
        }
    }

    /** The window on the recorder's screen now, if it is one a recording keeps; null otherwise */
    private static Window current(Minecraft minecraft, LocalPlayer player) {
        Screen screen = minecraft.screen;
        if (screen == null) {
            return null;
        }
        AbstractContainerMenu menu = screen instanceof MenuAccess<?> access ? access.getMenu() : null;
        WindowRules.Facts facts;
        if (menu == null) {
            facts = WindowRules.Facts.notAWindow();
        } else {
            String type = menuType(menu);
            Opened known = opened != null && opened.containerId() == menu.containerId ? opened : null;
            facts = new WindowRules.Facts(true, screen instanceof InventoryScreen && menu == player.inventoryMenu, screen instanceof HorseInventoryScreen,
                menu == player.containerMenu, menu.containerId, type, known != null && known.horse(), Compats.claims(screen));
        }
        WindowState.Source source = WindowRules.decide(facts);
        if (source == null) {
            if (facts.menuType() != null && WindowRules.denied(facts.menuType()) && SAID.add("denied " + facts.menuType())) {
                Host.LOGGER.info("Dailies: the window {} is on the list of those that do not draw right in playback, and is not recorded. Said once", facts.menuType());
            }
            return null;
        }
        Window window = new Window();
        window.source = source;
        window.containerId = menu.containerId;
        window.menuType = source == WindowState.Source.SERVER ? facts.menuType() : "";
        Opened known = opened != null && opened.containerId() == menu.containerId ? opened : null;
        window.title = known != null && !known.horse() ? known.title() : screen.getTitle();
        if (source == WindowState.Source.SERVER && known != null && known.extra() != null && known.menuType().equals(window.menuType)) {
            window.source = WindowState.Source.ADVANCED;
            window.extra = known.extra();
        }
        if (source == WindowState.Source.HORSE) {
            window.horseEntity = known.horseEntity();
            window.horseColumns = known.horseColumns();
        }
        List<Slot> slots = menu.slots;
        window.slots = new ItemStack[slots.size()];
        for (int i = 0; i < slots.size(); i++) {
            window.slots[i] = slots.get(i).getItem();
        }
        window.carried = menu.getCarried();
        List<DataSlot> data = ((ContainerMenuAccess) menu).dailies$dataSlots();
        window.data = new int[data.size()];
        for (int i = 0; i < data.size(); i++) {
            window.data[i] = data.get(i).get();
        }
        if (menu instanceof MerchantMenu merchant) {
            window.merchant = merchant;
            window.offersObject = merchant.getOffers();
            window.offersNumbers = new int[]{merchant.getTraderLevel(), merchant.getTraderXp(), merchant.showProgressBar() ? 1 : 0, merchant.canRestock() ? 1 : 0};
        }
        pointer(minecraft, screen, window);
        return window;
    }

    private static void pointer(Minecraft minecraft, Screen screen, Window window) {
        com.mojang.blaze3d.platform.Window gameWindow = minecraft.getWindow();
        double rawX = minecraft.mouseHandler.xpos();
        double rawY = minecraft.mouseHandler.ypos();
        if (screen instanceof AbstractContainerScreen<?> container) {
            window.share = false;
            window.x = (float) WindowPointer.toWindow(rawX, gameWindow.getScreenWidth(), gameWindow.getGuiScaledWidth(), container.getGuiLeft());
            window.y = (float) WindowPointer.toWindow(rawY, gameWindow.getScreenHeight(), gameWindow.getGuiScaledHeight(), container.getGuiTop());
            Slot hovered = container.getSlotUnderMouse();
            window.hovered = hovered == null ? -1 : hovered.index;
        } else {
            window.share = true;
            window.x = (float) WindowPointer.toShare(rawX, gameWindow.getScreenWidth());
            window.y = (float) WindowPointer.toShare(rawY, gameWindow.getScreenHeight());
            window.hovered = -1;
        }
    }

    private static WindowCodec codec(Object source, net.minecraft.core.RegistryAccess registries, net.neoforged.neoforge.network.connection.ConnectionType type) {
        if (codec == null || codecFor != source) {
            if (codec != null) {
                codec.release();
            }
            codec = new WindowCodec(registries, type);
            codecFor = source;
        }
        return codec;
    }

    private static void snapshot(Recorder.Out out, GameCodec gameCodec) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        Window now = current(minecraft, minecraft.player);
        snapTaken = true;
        snap = now == null ? null : now.keep(null);
        if (now != null) {
            WindowCodec writing = codec(gameCodec, gameCodec.registries(), gameCodec.connectionType());
            WindowState.Parts parts = new WindowState.Parts();
            whole(now, parts, writing);
            out.extension(WindowState.ID, parts.bytes());
        }
    }

    /** Everything about a window, as a record that opens it */
    private static void whole(Window now, WindowState.Parts parts, WindowCodec writing) {
        parts.open(now.source, now.containerId, now.menuType, writing.title(now.title), now.extra, now.horseEntity, now.horseColumns);
        for (int i = 0; i < now.slots.length; i++) {
            if (!now.slots[i].isEmpty()) {
                parts.slot(i, writing.stack(now.slots[i]));
            }
        }
        for (int i = 0; i < now.data.length; i++) {
            if (now.data[i] != 0) {
                parts.data(i, now.data[i]);
            }
        }
        if (now.merchant != null) {
            parts.offers(writing.offers(now.merchant));
        }
        if (!now.carried.isEmpty()) {
            parts.carried(writing.stack(now.carried));
        }
        parts.pointer(now.share, now.x, now.y, now.hovered);
    }

    private static void tick() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            LocalPlayer player = minecraft.player;
            ClientPacketListener listener = minecraft.getConnection();
            if (player == null || listener == null) {
                return;
            }
            String id = Recorder.get().recordingId();
            if (id != null && !id.equals(recordingId)) {
                // A new recording holds nothing yet: its first snapshot says what is open
                recordingId = id;
                written = null;
            }
            ticks++;
            Window now = current(minecraft, player);
            WindowState.Parts parts = new WindowState.Parts();
            if (now == null) {
                if (written != null || snapTaken && snap != null) {
                    parts.close();
                }
                written = null;
            } else {
                WindowCodec writing = codec(listener, listener.registryAccess(), listener.getConnectionType());
                Window snapped = snapTaken ? snap : null;
                boolean fresh = written == null || !written.sameWindow(now) || snapTaken && (snap == null || !snap.sameWindow(now));
                if (fresh) {
                    whole(now, parts, writing);
                    pointerWritten = ticks;
                    written = now.keep(null);
                } else {
                    changes(now, snapped, parts, writing);
                }
            }
            snapTaken = false;
            snap = null;
            if (!parts.isEmpty()) {
                Recorder.get().writeExtension(WindowState.ID, parts.bytes());
            }
        } catch (Throwable t) {
            if (SAID.add("failed")) {
                Host.LOGGER.warn("Dailies: the recorder's window could not be recorded. The rest of the recording is unharmed. Said once", t);
            }
        }
    }

    /**
     * What changed since the recording last said, and what differs from what a snapshot taken this tick described: a
     * jump that lands on the snapshot reads these too, so it arrives where plain play does
     */
    private static void changes(Window now, Window snapped, WindowState.Parts parts, WindowCodec writing) {
        Window was = written;
        if (was.slots.length != now.slots.length) {
            was.slots = Arrays.copyOf(was.slots, now.slots.length);
        }
        for (int i = 0; i < now.slots.length; i++) {
            ItemStack stack = now.slots[i];
            ItemStack before = was.slots[i] == null ? ItemStack.EMPTY : was.slots[i];
            if (!ItemStack.matches(stack, before) || snapped != null && !ItemStack.matches(stack, snapped.slot(i))) {
                parts.slot(i, writing.stack(stack));
                was.slots[i] = stack.copy();
            }
        }
        if (was.data.length != now.data.length) {
            was.data = Arrays.copyOf(was.data, now.data.length);
        }
        for (int i = 0; i < now.data.length; i++) {
            if (now.data[i] != was.data[i] || snapped != null && now.data[i] != snapped.data(i)) {
                parts.data(i, now.data[i]);
                was.data[i] = now.data[i];
            }
        }
        if (now.merchant != null && (now.offersObject != was.offersObject || !Arrays.equals(now.offersNumbers, was.offersNumbers)
            || snapped != null && (now.offersObject != snapped.offersObject || !Arrays.equals(now.offersNumbers, snapped.offersNumbers)))) {
            parts.offers(writing.offers(now.merchant));
            was.offersObject = now.offersObject;
            was.offersNumbers = now.offersNumbers;
        }
        boolean carriedChanged = !ItemStack.matches(now.carried, was.carried);
        if (carriedChanged || snapped != null && !ItemStack.matches(now.carried, snapped.carried)) {
            parts.carried(writing.stack(now.carried));
            was.carried = now.carried.copy();
        }
        boolean moved = now.share != was.share || Math.abs(now.x - was.x) * (now.share ? 400 : 1) >= POINTER_STEP
            || Math.abs(now.y - was.y) * (now.share ? 300 : 1) >= POINTER_STEP;
        boolean due = moved && ticks - pointerWritten >= POINTER_TICKS;
        boolean differsFromSnap = snapped != null && (snapped.x != now.x || snapped.y != now.y || snapped.hovered != now.hovered || snapped.share != now.share);
        if (now.hovered != was.hovered || carriedChanged || due || differsFromSnap) {
            parts.pointer(now.share, now.x, now.y, now.hovered);
            was.share = now.share;
            was.x = now.x;
            was.y = now.y;
            was.hovered = now.hovered;
            pointerWritten = ticks;
        }
    }

    /** A window as the recorder's game held it: the stacks are the game's own until {@link #keep} copies them */
    private static final class Window {
        WindowState.Source source;
        int containerId;
        String menuType = "";
        Component title = Component.empty();
        byte[] extra = null;
        int horseEntity = -1;
        int horseColumns = 0;
        ItemStack[] slots = new ItemStack[0];
        ItemStack carried = ItemStack.EMPTY;
        int[] data = new int[0];
        MerchantMenu merchant = null;
        Object offersObject = null;
        int[] offersNumbers = null;
        boolean share;
        float x;
        float y;
        int hovered = -1;

        boolean sameWindow(Window other) {
            return this.source == other.source && this.containerId == other.containerId && this.menuType.equals(other.menuType)
                && this.horseEntity == other.horseEntity;
        }

        ItemStack slot(int index) {
            return index < this.slots.length && this.slots[index] != null ? this.slots[index] : ItemStack.EMPTY;
        }

        int data(int index) {
            return index < this.data.length ? this.data[index] : 0;
        }

        /** A copy that later changes to the game's stacks leave alone */
        Window keep(Window into) {
            Window copy = into == null ? new Window() : into;
            copy.source = this.source;
            copy.containerId = this.containerId;
            copy.menuType = this.menuType;
            copy.title = this.title;
            copy.extra = this.extra;
            copy.horseEntity = this.horseEntity;
            copy.horseColumns = this.horseColumns;
            copy.slots = new ItemStack[this.slots.length];
            for (int i = 0; i < this.slots.length; i++) {
                copy.slots[i] = this.slots[i].copy();
            }
            copy.carried = this.carried.copy();
            copy.data = this.data.clone();
            copy.merchant = this.merchant;
            copy.offersObject = this.offersObject;
            copy.offersNumbers = this.offersNumbers;
            copy.share = this.share;
            copy.x = this.x;
            copy.y = this.y;
            copy.hovered = this.hovered;
            return copy;
        }
    }

}
