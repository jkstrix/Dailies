package comedymonkey.dailies.windows;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.systems.RenderSystem;
import comedymonkey.dailies.Dailies;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.editor.EditorSession;
import comedymonkey.dailies.extras.HudLayer;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.mixin.access.ContainerMenuAccess;
import comedymonkey.dailies.mixin.access.ContainerScreenAccess;
import comedymonkey.dailies.mixin.access.ScreenAccess;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.PlaybackExtensions;
import comedymonkey.dailies.playback.Viewer;
import io.netty.buffer.Unpooled;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.BeaconScreen;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.client.gui.screens.inventory.HorseInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.SmithingScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.HorseInventoryMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.MerchantMenu;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The playback half of the recorder's windows (specification PLAY-20, EXP-16). While the viewer rides along with the
 * recorder in first person and the scene's "recorder's HUD" and "open windows" looks are on, the window the recorder had
 * open is built again from what the recording holds, with the game's own menu and screen classes (or the mod's), and
 * drawn over the world as the recorder's game drew it: the dimmed background, the window, the slot under the pointer,
 * the item on the pointer and its tooltip, then the pointer itself. It is never made the game's screen, takes no input
 * and has no connection to send on: its menu belongs to a stand-in player that is not in the world, so the viewer's own
 * inventory and windows are never touched, and playback's connection throws away anything sent. Drawn under Dailies' own
 * interface, which draws over it. A window that cannot be built or drawn this way is left out with a line in the log,
 * once, and listed by {@link #leftOut()} so a compat can take it over.
 */
public final class WindowLayer implements PlaybackExtensions.Extension {

    private static final WindowLayer INSTANCE = new WindowLayer();
    private static final GameProfile OWNER = new GameProfile(UUID.nameUUIDFromBytes("Dailies window".getBytes(StandardCharsets.UTF_8)), "Window");
    /** The game's windows that move by themselves, whose own tick is run once a recorded tick: the enchanting book, the beacon's buttons, the smithing table's icons */
    private static final Set<Class<?>> TICKED = Set.of(BeaconScreen.class, EnchantmentScreen.class, SmithingScreen.class);

    /**
     * The pointer, as the recorder's own system drew it over the game: an arrow, its tip at the point. Drawn one square
     * for each pixel of a 1080 line screen, so it is the size on the picture it was on the recorder's
     */
    private static final String[] ARROW = {
        "X",
        "XX",
        "X.X",
        "X..X",
        "X...X",
        "X....X",
        "X.....X",
        "X......X",
        "X.......X",
        "X........X",
        "X.........X",
        "X......XXXXX",
        "X...X..X",
        "X..XX..X",
        "X.X  X..X",
        "XX   X..X",
        "X     X..X",
        "      X..X",
        "       XX",
    };

    private final WindowState state = new WindowState();
    private @Nullable Built built = null;
    private boolean refill = true;
    private @Nullable WindowCodec codec = null;
    private @Nullable Object codecFor = null;
    private int tickedAt = Integer.MIN_VALUE;
    private boolean readFailed = false;
    /** Windows that could not be built or drawn, by name: left out from then on, for a compat to take over */
    private final Set<String> leftOut = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** The pointer moves from where it was shown to where it was written next, over the time between the two */
    private final PointerMotion pointer = new PointerMotion();

    // What was last drawn, for the scripted test and anyone checking a window reaches the picture
    private static volatile @Nullable String lastDrawn = null;
    private static volatile long lastDrawnFrame = -1;
    private static boolean drawing = false;
    private static @Nullable LivingEntity recorder = null;

    private WindowLayer() {
    }

    static void init() {
        PlaybackExtensions.register(WindowState.ID, INSTANCE);
        Playback.onClosing(INSTANCE::reset);
    }

    static void register(RegisterGuiLayersEvent event) {
        // Above every layer of the game's own, as the game draws its screen over them all, and under the layers that are
        // registered above everything (Dailies' own playback bar, notices and frame guides among them)
        event.registerAbove(VanillaGuiLayers.SAVING_INDICATOR, ResourceLocation.fromNamespaceAndPath(Dailies.ID, "recorder_windows"), INSTANCE::draw);
    }

    // --- The recording ---

    @Override
    public void play(byte[] data, int from, int length, boolean skipping) {
        int changed;
        try {
            changed = this.state.apply(data, from, length);
        } catch (IllegalArgumentException unreadable) {
            this.state.clear();
            changed = WindowState.CLOSED;
            if (!this.readFailed) {
                this.readFailed = true;
                Host.LOGGER.warn("Dailies: a window in this recording could not be read and is not shown. Said once", unreadable);
            }
        }
        if ((changed & (WindowState.OPENED | WindowState.CLOSED)) != 0) {
            this.built = null;
        }
        if ((changed & WindowState.CONTENTS) != 0) {
            this.refill = true;
        }
        if ((changed & WindowState.MOVED) != 0) {
            this.pointer.moved(this.state.pointerX, this.state.pointerY, skipping || (changed & WindowState.OPENED) != 0);
        }
    }

    /** The window open at this moment with everything in it, so the part after a cut starts with it */
    @Override
    public void describe(Consumer<byte[]> out) {
        if (this.state.open()) {
            out.accept(this.state.whole());
        }
    }

    @Override
    public void reset() {
        this.state.clear();
        this.built = null;
        this.refill = true;
        this.pointer.forget();
        this.tickedAt = Integer.MIN_VALUE;
    }

    // --- Drawing ---

    /** Whether a recorded window is shown now: the owner's rule, riding along with the recorder in first person, the HUD and windows looks on */
    static boolean shown(Minecraft minecraft) {
        return shown(minecraft, true);
    }

    /**
     * The owner's rule: riding along with the recorder in first person with the HUD look on, and for a window (not an
     * overlay, which is part of the HUD) the "open windows" look too
     */
    static boolean shown(Minecraft minecraft, boolean window) {
        if (minecraft.options.hideGui || !minecraft.options.getCameraType().isFirstPerson()) {
            return false;
        }
        RecordingManifest manifest = Playback.manifest();
        Entity riding = Viewer.riding();
        if (manifest == null || !(riding instanceof Player player) || !player.getUUID().toString().equalsIgnoreCase(manifest.recorderId)) {
            return false;
        }
        if (EditorSession.open()) {
            comedymonkey.dailies.edit.Looks looks = EditorSession.scene().looks();
            return looks.showHud && (!window || looks.showWindows);
        }
        return true;
    }

    /** What a window is called in the log and to the tests: its menu type, or what stands in for one */
    private static String name(WindowState state) {
        return switch (state.source) {
            case INVENTORY -> "inventory";
            case HORSE -> "horse";
            default -> state.menuType;
        };
    }

    private void draw(GuiGraphics graphics, DeltaTracker delta) {
        if (!this.state.open() || !Playback.loaded()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || !shown(minecraft)) {
            return;
        }
        String name = name(this.state);
        if (this.leftOut.contains(name)) {
            return;
        }
        Built window;
        try {
            window = this.built(minecraft, graphics.guiWidth(), graphics.guiHeight());
        } catch (NotYet waiting) {
            return;
        } catch (Throwable t) {
            this.leaveOut(name, "could not be built", t);
            return;
        }
        try {
            if (this.refill) {
                this.refill = false;
                this.fill(window);
            }
            if (window.screen instanceof AbstractContainerScreen<?> && TICKED.contains(window.screen.getClass()) && Playback.position() != this.tickedAt) {
                this.tickedAt = Playback.position();
                asViewer(minecraft, ((ContainerScreenAccess) window.screen)::dailies$containerTick);
            }
            // A compat's extras for this window: what its screen held that the server never sent (REC-26)
            Compats.applyExtras(this.state.menuType, window.screen);
            float[] pointer = this.pointer.at(Playback.frameTime());
            double x;
            double y;
            if (this.state.pointerShare || !(window.screen instanceof AbstractContainerScreen<?> container)) {
                x = WindowPointer.fromShare(pointer[0], graphics.guiWidth());
                y = WindowPointer.fromShare(pointer[1], graphics.guiHeight());
            } else {
                x = WindowPointer.toScreen(pointer[0], container.getGuiLeft());
                y = WindowPointer.toScreen(pointer[1], container.getGuiTop());
            }
            int mouseX = (int) Math.floor(x);
            int mouseY = (int) Math.floor(y);
            drawScreen(graphics, minecraft, window.screen, mouseX, mouseY, delta.getGameTimeDeltaPartialTick(false));
            if (this.state.pointer) {
                drawPointer(graphics, minecraft, x, y);
            }
            lastDrawn = name;
            lastDrawnFrame = comedymonkey.dailies.looks.Drawn.frames();
        } catch (Throwable t) {
            this.built = null;
            this.leaveOut(name, "could not be drawn", t);
        }
    }

    /**
     * Draws a screen built for playback as the game draws its screen: over everything drawn before, with nothing of the
     * layers below in the way, as the viewer with the recorder's numbers, the recorder standing in for the game's player
     * where the screen draws them. For the recorder's windows and a compat's screens
     */
    static void drawScreen(GuiGraphics graphics, Minecraft minecraft, Screen screen, int mouseX, int mouseY, float partial) {
        graphics.flush();
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        recorder = Viewer.riding() instanceof LivingEntity living ? living : null;
        drawing = true;
        try {
            asViewer(minecraft, () -> screen.renderWithTooltip(graphics, mouseX, mouseY, partial));
        } finally {
            drawing = false;
            recorder = null;
        }
        graphics.flush();
    }

    /**
     * Lays out a screen built for playback at this size without telling other mods it opened (ScreenAccess), so their
     * buttons and panels stay out of it
     */
    static void layOut(Minecraft minecraft, Screen screen, int width, int height) {
        ScreenAccess access = (ScreenAccess) screen;
        access.dailies$setMinecraft(minecraft);
        access.dailies$setFont(minecraft.font);
        screen.width = width;
        screen.height = height;
        asViewer(minecraft, access::dailies$init);
    }

    private void leaveOut(String name, String what, Throwable t) {
        if (this.leftOut.add(name)) {
            Host.LOGGER.warn("Dailies: the recorded window {} {} in playback and is left out; a compat can take it over. Said once", name, what, t);
        }
    }

    /** The window cannot be built this frame, but may be the next */
    private static final class NotYet extends RuntimeException {
        NotYet() {
            super(null, null, false, false);
        }
    }

    /** A window built for playback: its menu, filled from the recording, and the screen that draws it */
    private record Built(AbstractContainerMenu menu, Screen screen, int width, int height) {
    }

    private Built built(Minecraft minecraft, int width, int height) {
        Built window = this.built;
        if (window != null && window.width() == width && window.height() == height) {
            return window;
        }
        // Built afresh for another size too: a screen lays itself out once
        ClientPacketListener listener = minecraft.getConnection();
        if (listener == null) {
            throw new IllegalStateException("playback has no connection to read the window with");
        }
        if (this.codec == null || this.codecFor != listener) {
            if (this.codec != null) {
                this.codec.release();
            }
            this.codec = new WindowCodec(listener.registryAccess(), listener.getConnectionType());
            this.codecFor = listener;
        }
        window = build(minecraft, this.state, this.codec, listener, width, height);
        this.built = window;
        this.refill = true;
        return window;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Built build(Minecraft minecraft, WindowState state, WindowCodec codec, ClientPacketListener listener, int width, int height) {
        // The window's menu belongs to a stand-in player who is never in the world: what fills its slots fills the
        // stand-in's inventory, not the viewer's
        RemotePlayer owner = new RemotePlayer(minecraft.level, OWNER);
        if (Viewer.riding() instanceof Entity recorder) {
            // Where the recorder stands, turned as they are: a mod's window that looks for its block or entity near the
            // player opening it (a corpse, say) finds it near the stand-in too. The stand-in is still never in the world
            owner.moveTo(recorder.getX(), recorder.getY(), recorder.getZ(), recorder.getYRot(), recorder.getXRot());
        }
        Inventory inventory = owner.getInventory();
        Component title = codec.title(state.title);
        AbstractContainerMenu menu;
        Screen screen;
        switch (state.source) {
            case INVENTORY -> {
                if (minecraft.gameMode != null && minecraft.gameMode.hasInfiniteItems()) {
                    // The inventory screen swaps itself for the creative one as it is laid out when the game's own player
                    // is in creative, and would take the game's screen with it; the viewer is a spectator, so never here
                    throw new NotYet();
                }
                menu = owner.inventoryMenu;
                screen = new InventoryScreen(owner);
            }
            case HORSE -> {
                if (!(minecraft.level.getEntity(state.horseEntity) instanceof AbstractHorse horse)) {
                    // Not in the world for a moment (a jump under way): tried again next frame
                    throw new NotYet();
                }
                HorseInventoryMenu horseMenu = new HorseInventoryMenu(state.containerId, inventory, new SimpleContainer(AbstractHorse.getInventorySize(state.horseColumns)),
                    horse, state.horseColumns);
                menu = horseMenu;
                screen = new HorseInventoryScreen(horseMenu, inventory, horse, state.horseColumns);
            }
            default -> {
                ResourceLocation key = ResourceLocation.tryParse(state.menuType);
                MenuType type = key == null ? null : BuiltInRegistries.MENU.getOptional(key).orElse(null);
                if (type == null) {
                    throw new IllegalStateException("this game has no window of that type");
                }
                if (state.source == WindowState.Source.ADVANCED && state.extra != null) {
                    RegistryFriendlyByteBuf extra = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(state.extra), listener.registryAccess(), listener.getConnectionType());
                    try {
                        menu = (AbstractContainerMenu) type.create(state.containerId, inventory, extra);
                    } finally {
                        extra.release();
                    }
                } else {
                    menu = (AbstractContainerMenu) type.create(state.containerId, inventory);
                }
                Optional<MenuScreens.ScreenConstructor> factory = (Optional) MenuScreens.getScreenFactory(type);
                if (factory.isEmpty()) {
                    throw new IllegalStateException("no screen is registered for it");
                }
                screen = (Screen) factory.get().create(menu, inventory, title);
            }
        }
        layOut(minecraft, screen, width, height);
        return new Built(menu, screen, width, height);
    }

    private void fill(Built window) {
        AbstractContainerMenu menu = window.menu();
        WindowCodec codec = this.codec;
        // A horse's armour slot is the horse's own, which the world already shows it wearing
        int skipped = this.state.source == WindowState.Source.HORSE ? 1 : -1;
        for (int i = 0; i < menu.slots.size(); i++) {
            if (i != skipped) {
                menu.getSlot(i).set(codec.stack(this.state.slot(i)));
            }
        }
        int dataSlots = ((ContainerMenuAccess) menu).dailies$dataSlots().size();
        for (int i = 0; i < dataSlots; i++) {
            menu.setData(i, this.state.data(i));
        }
        menu.setCarried(codec.stack(this.state.carried));
        if (menu instanceof MerchantMenu merchant && this.state.offers != null) {
            codec.putOffers(this.state.offers, merchant);
        }
    }

    /**
     * Runs a screen's own code as the recorder's game would have: the game's player is the viewer, so a few things a
     * window reads from it are put back to the recorder's for the moment (the experience level the enchanting table
     * and anvil colour their costs by), and what a window sets on it (the recipe book points the player's open menu at
     * its own) is put back as it was afterwards
     */
    static void asViewer(Minecraft minecraft, Runnable action) {
        LocalPlayer viewer = minecraft.player;
        if (viewer == null) {
            action.run();
            return;
        }
        AbstractContainerMenu open = viewer.containerMenu;
        int level = viewer.experienceLevel;
        try {
            float[] recorded = HudLayer.recordedNumbers();
            if (recorded != null) {
                viewer.experienceLevel = (int) recorded[0];
            }
            action.run();
        } finally {
            viewer.containerMenu = open;
            viewer.experienceLevel = level;
        }
    }

    static void drawPointer(GuiGraphics graphics, Minecraft minecraft, double x, double y) {
        double scale = minecraft.getWindow().getGuiScale();
        double unit = Math.max(1L, Math.round(graphics.guiHeight() * scale / 1080.0)) / scale;
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(x, y, 700.0);
            graphics.pose().scale((float) unit, (float) unit, 1.0F);
            for (int row = 0; row < ARROW.length; row++) {
                String line = ARROW[row];
                int start = 0;
                while (start < line.length()) {
                    char kind = line.charAt(start);
                    int end = start;
                    while (end < line.length() && line.charAt(end) == kind) {
                        end++;
                    }
                    if (kind != ' ') {
                        graphics.fill(start, row, end, row + 1, kind == 'X' ? 0xFF000000 : 0xFFFFFFFF);
                    }
                    start = end;
                }
            }
        } finally {
            graphics.pose().popPose();
        }
        graphics.flush();
    }

    /** The inventory's drawing of the game's player draws the recorder while a recorded window is drawn (mixin.playback.WindowEntityMixin) */
    public static LivingEntity entityShown(LivingEntity entity) {
        LivingEntity shown = recorder;
        if (drawing && shown != null && entity == Minecraft.getInstance().player) {
            return shown;
        }
        return entity;
    }

    // --- For the scripted test, and anyone checking a window reaches the picture ---

    /** The window last drawn: its menu type id, "inventory" or "horse"; null when none has been */
    public static @Nullable String lastDrawn() {
        return lastDrawn;
    }

    /** The frame ({@link comedymonkey.dailies.looks.Drawn#frames()}) a window was last drawn in; -1 when none has been */
    public static long lastDrawnFrame() {
        return lastDrawnFrame;
    }

    /** The window the recording holds as open at this moment, named as {@link #lastDrawn()}; null when none is */
    public static @Nullable String held() {
        return INSTANCE.state.open() ? name(INSTANCE.state) : null;
    }

    /** The slot under the pointer as recorded at this moment; -1 for none */
    public static int heldHovered() {
        return INSTANCE.state.open() ? INSTANCE.state.hovered : -1;
    }

    /** Windows that could not be built or drawn in this game, by name, for a compat to take over */
    public static Set<String> leftOut() {
        return new TreeSet<>(INSTANCE.leftOut);
    }

}
