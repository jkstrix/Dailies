package comedymonkey.dailies.ui;

import com.mojang.blaze3d.platform.InputConstants;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.edit.EditHistory;
import comedymonkey.dailies.edit.Keyframe;
import comedymonkey.dailies.edit.KeyframeRef;
import comedymonkey.dailies.edit.Scene;
import comedymonkey.dailies.editor.EditorSession;
import comedymonkey.dailies.export.Export;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.Viewer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The editor (specification chapter V), opened with the timeline key while a recording is watched. Panels are docked
 * round the edges and the middle of the view is left clear: scenes and the export range along the top, the timeline
 * with its tracks along the bottom, the viewer's controls at the left with the players in the recording under them,
 * and at the right a panel with three tabs for the selected keyframe, the scene's looks and the export. Every panel
 * folds away. In the view itself a click on a keyframe's mark along the camera path selects it, and a click on a
 * marker's ring jumps to the marker.
 * <p>
 * The recording keeps playing behind it: the screen neither pauses nor dims nor blurs the world. Holding the right
 * mouse button over the view turns the camera and lets the movement keys fly it, so a shot can be framed without
 * closing the editor. Nothing is drawn while an export is under way.
 */
public final class EditorScreen extends Screen {

    private static final int TOP_ROW = 14;
    private static final int HEADER = 18;

    /**
     * Whether the editor should be on screen whenever it can be. The game takes screens away while a jump rebuilds
     * the world, and an export puts its progress display in the editor's place; afterwards the editor comes back.
     * Anything else that takes the editor off the screen closes it for good.
     */
    private static boolean wanted = false;
    /** Whether it is {@link #watch} itself that is changing the screen */
    private static boolean swapping = false;
    /** Whether an export has run since the editor last said how one ended */
    private static boolean exportSeen = false;
    private static int sceneSeen = -1;
    /** Where the mouse was when the game took the editor away, to put it back there; the game leaves it in the middle */
    private static boolean pointerKept = false;
    private static double pointerX;
    private static double pointerY;
    private static boolean failureLogged = false;

    private final TimelinePanel timeline = new TimelinePanel();
    private final Form keyframeForm = new Form();
    private final Form looksForm = new Form();
    private final Form exportForm = new Form();
    private final Form pickForm = new Form();
    private final Form viewerForm = new Form();
    private final Form playersForm = new Form();
    private final List<Gadget> top = new ArrayList<>();
    private final List<Gadget> tabs = new ArrayList<>();
    private Gadget.Push sideFold;
    private Gadget.Push viewerFold;
    private Gadget.Push playersFold;
    private @Nullable Gadget held;
    private int selectionSeen = 0;

    private int topBottom;
    private int timelineTop;
    private int sideLeft;
    private int sideBottom;
    private int viewerRight;
    private int viewerBottom;
    /** The players panel sits under the viewer's strip; top and bottom are the same when there is no room for it */
    private int playersTop;
    private int playersBottom;

    // Flying with the right mouse button held
    private boolean flying = false;
    /** Frames still to pass before the mouse is believed: hiding the pointer can make it jump once */
    private int turnSettling = 0;
    private double cursorX;
    private double cursorY;
    private double lastMouseX;
    private double lastMouseY;
    private Vec3 velocity = Vec3.ZERO;
    private long lastNanos = 0;

    private EditorScreen() {
        super(Component.translatable("dailies.editor.title"));
    }

    /** Once, when the game starts: the hooks the editor and what belongs to it need */
    static void hook() {
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre event) -> watch());
        ViewPick.init();
        ExportProgress.hook();
        // The editor is the way a recording is watched and worked on, so it is there as soon as one opens. The scripted
        // test runs drive the game through its tools and put the editor up themselves when they want it
        Playback.onOpened(() -> {
            String script = System.getProperty("dailies.test.script");
            if (script == null || script.equals("hands-on")) {
                open();
            }
        });
    }

    /**
     * The editor has a lot on it, and at the size the game draws its own menus on a large window there is no room left
     * for the view. So it is drawn at a size of its own: the largest that still leaves it about 900 by 500 to work in,
     * and never larger than the game's.
     */
    private void fitScale() {
        com.mojang.blaze3d.platform.Window window = this.minecraft.getWindow();
        int scale = Math.min(usualScale(this.minecraft), Math.max(1, Math.min(window.getWidth() / 900, window.getHeight() / 500)));
        if (scale != (int) window.getGuiScale()) {
            window.setGuiScale(scale);
        }
        this.width = window.getGuiScaledWidth();
        this.height = window.getGuiScaledHeight();
    }

    private static int usualScale(Minecraft minecraft) {
        return minecraft.getWindow().calculateScale(minecraft.options.guiScale().get(), minecraft.isEnforceUnicode());
    }

    /** Puts the editor on screen */
    static void open() {
        if (Playback.loaded()) {
            wanted = true;
            pointerKept = false;
            Minecraft.getInstance().setScreen(new EditorScreen());
        }
    }

    /**
     * Once a frame. Puts the editor back after the game has taken it away for a jump, puts the progress display in
     * its place while a video is exported, and says how the export went when it is over.
     */
    private static void watch() {
        if (!wanted) {
            return;
        }
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (!Playback.active()) {
                wanted = false;
                return;
            }
            if (!Playback.loaded()) {
                return;
            }
            Screen screen = minecraft.screen;
            if (Export.active()) {
                exportSeen = true;
                if (Export.holdsClock() && (screen == null || screen instanceof EditorScreen)) {
                    show(minecraft, new ExportProgress());
                    // The pointer stays wherever the person takes it during the export
                    pointerKept = false;
                }
                return;
            }
            if ((screen == null || screen instanceof ExportProgress) && !Playback.jumping()) {
                show(minecraft, new EditorScreen());
                screen = minecraft.screen;
                if (pointerKept) {
                    pointerKept = false;
                    InputConstants.grabOrReleaseMouse(minecraft.getWindow().getWindow(), GLFW.GLFW_CURSOR_NORMAL, pointerX, pointerY);
                    // The game hears of the move a moment later; until then the playhead must not follow a stale pointer
                    EditorState.pointerSettling = 3;
                }
            }
            if (exportSeen && screen instanceof EditorScreen) {
                exportSeen = false;
                EditorState.tab = EditorState.TAB_EXPORT;
                EditorState.sideFolded = false;
                EditorState.say(ExportForms.ended());
            }
        } catch (RuntimeException | LinkageError e) {
            wanted = false;
            Host.LOGGER.error("Dailies: the editor could not be put back on screen", e);
        }
    }

    private static void show(Minecraft minecraft, Screen screen) {
        swapping = true;
        try {
            minecraft.setScreen(screen);
        } finally {
            swapping = false;
        }
    }

    // ------------------------------------------------------------------ Building

    @Override
    protected void init() {
        try {
            this.fitScale();
            this.build();
        } catch (RuntimeException | LinkageError e) {
            Host.LOGGER.error("Dailies: the editor could not be built", e);
            Notices.show(Component.translatable("dailies.ui.notice.went_wrong"));
        }
    }

    private void build() {
        Entry.drop();
        Menu.close();
        this.held = null;
        EditorState.attach();
        this.top.clear();
        this.tabs.clear();
        this.buildTop();

        this.tabs.add(new Gadget.Push(Kit.t("tab.keyframe"), () -> this.showTab(EditorState.TAB_KEYFRAME))
                .lit(() -> EditorState.pick == null && EditorState.tab == EditorState.TAB_KEYFRAME).tip(Kit.t("tab.keyframe.tip")));
        this.tabs.add(new Gadget.Push(Kit.t("tab.looks"), () -> this.showTab(EditorState.TAB_LOOKS))
                .lit(() -> EditorState.pick == null && EditorState.tab == EditorState.TAB_LOOKS).tip(Kit.t("tab.looks.tip")));
        this.tabs.add(new Gadget.Push(Kit.t("tab.export"), () -> this.showTab(EditorState.TAB_EXPORT))
                .lit(() -> EditorState.pick == null && EditorState.tab == EditorState.TAB_EXPORT).tip(Kit.t("tab.export.tip")));
        this.sideFold = Gadget.Push.icon(() -> EditorState.sideFolded ? Kit.Icon.FOLD_LEFT : Kit.Icon.FOLD_RIGHT, Kit.t("side.fold"),
                () -> EditorState.sideFolded = !EditorState.sideFolded);
        this.viewerFold = Gadget.Push.icon(() -> EditorState.viewerFolded ? Kit.Icon.FOLD_DOWN : Kit.Icon.FOLD_UP, Kit.t("viewer.fold"),
                () -> EditorState.viewerFolded = !EditorState.viewerFolded);
        this.playersFold = Gadget.Push.icon(() -> EditorState.playersFolded ? Kit.Icon.FOLD_DOWN : Kit.Icon.FOLD_UP, Kit.t("players.fold"),
                () -> EditorState.playersFolded = !EditorState.playersFolded);

        this.keyframeForm.builtFor = "";
        this.looksForm.builtFor = "";
        this.exportForm.builtFor = "";
        this.pickForm.builtFor = "";
        this.playersForm.builtFor = "";
        ViewerForms.build(this.viewerForm);
    }

    private void showTab(int tab) {
        EditorState.pick = null;
        EditorState.tab = tab;
        EditorState.sideFolded = false;
    }

    private void buildTop() {
        this.top.add(new Gadget.Push(() -> {
            EditHistory history = EditorState.history();
            return Kit.t("scene.current", history.scene().name(), history.state().currentSceneIndex() + 1, history.state().scenes().size());
        }, this::openSceneMenu).wide(128).tip(Kit.t("scene.current.tip")));
        this.top.add(new Gadget.Push(Kit.t("scene.add"), () -> {
            EditorState.selection.clear();
            EditorState.history().addScene(null);
        }).tip(Kit.t("scene.add.tip")));
        this.top.add(new Gadget.Push(Kit.t("scene.rename"), this::renameScene).tip(Kit.t("scene.rename.tip")));
        this.top.add(new Gadget.Push(Kit.t("scene.duplicate"), () -> {
            EditorState.selection.clear();
            EditorState.history().duplicateScene(EditorState.history().state().currentSceneIndex());
        }).tip(Kit.t("scene.duplicate.tip")));
        this.top.add(new Gadget.Push(Kit.t("scene.remove"), () -> {
            EditorState.selection.clear();
            EditorState.history().removeScene(EditorState.history().state().currentSceneIndex());
        }).when(() -> EditorState.history().state().scenes().size() > 1).tip(Kit.t("scene.remove.tip")));

        this.top.add(new Gadget.Words(() -> Kit.t("range.label"), Controls.PARCHMENT_DIM).tip(Kit.t("range.tip")));
        this.top.add(Entry.number(() -> EditorState.scene().hasExportRange() ? Double.valueOf(EditorState.scene().exportStart()) : null,
                0, Keyframe.MAX_TICK, true, value -> {
                    Scene scene = EditorState.scene();
                    int start = (int) Math.rint(value);
                    if (start > Playback.total()) {
                        return Kit.t("error.range", 0, Playback.total());
                    }
                    EditorState.history().setExportRange(start, Math.max(start, scene.hasExportRange() ? scene.exportEnd() : Playback.total()));
                    return null;
                }).errorsAsNotice().hint(Kit.t("range.start.hint")).wide(48).tip(Kit.t("range.start.tip")));
        this.top.add(Entry.number(() -> EditorState.scene().hasExportRange() ? Double.valueOf(EditorState.scene().exportEnd()) : null,
                0, Keyframe.MAX_TICK, true, value -> {
                    Scene scene = EditorState.scene();
                    int end = Math.min(Playback.total(), (int) Math.rint(value));
                    EditorState.history().setExportRange(scene.hasExportRange() ? scene.exportStart() : 0, end);
                    return null;
                }).errorsAsNotice().hint(Kit.t("range.end.hint")).wide(48).tip(Kit.t("range.end.tip")));
        this.top.add(new Gadget.Push(Kit.t("range.start_here"), () -> {
            Scene scene = EditorState.scene();
            int start = Playback.position();
            EditorState.history().setExportRange(start, scene.hasExportRange() ? Math.max(start, scene.exportEnd()) : Math.max(start, Playback.total()));
        }).tip(Kit.t("range.start_here.tip")));
        this.top.add(new Gadget.Push(Kit.t("range.end_here"), () -> {
            Scene scene = EditorState.scene();
            int end = Playback.position();
            EditorState.history().setExportRange(scene.hasExportRange() ? Math.min(end, scene.exportStart()) : 0, end);
        }).tip(Kit.t("range.end_here.tip")));
        this.top.add(new Gadget.Push(Kit.t("range.clear"), () -> EditorState.history().clearExportRange())
                .when(() -> EditorState.scene().hasExportRange()).tip(Kit.t("range.clear.tip")));

        this.top.add(new Gadget.Push(Component.translatable("dailies.ui.timeline.undo"), EditorState::undo).liveTip(() -> {
            String what = EditorState.history().undoDescription();
            return what == null ? Component.translatable("dailies.ui.notice.nothing_to_undo") : Kit.t("undo.tip", what);
        }).when(() -> EditorState.history().canUndo()));
        this.top.add(new Gadget.Push(Component.translatable("dailies.ui.timeline.redo"), EditorState::redo).liveTip(() -> {
            String what = EditorState.history().redoDescription();
            return what == null ? Component.translatable("dailies.ui.notice.nothing_to_redo") : Kit.t("redo.tip", what);
        }).when(() -> EditorState.history().canRedo()));
        this.top.add(new Gadget.Push(Kit.t("close"), this::onClose).tip(Kit.t("close.tip")));
    }

    private void openSceneMenu() {
        EditHistory history = EditorState.history();
        List<Menu.Item> items = new ArrayList<>();
        for (int index = 0; index < history.state().scenes().size(); index++) {
            int chosen = index;
            items.add(new Menu.Item(Component.literal(history.state().scene(index).name()), () -> {
                EditorState.selection.clear();
                EditorState.history().selectScene(chosen);
            }, true, index == history.state().currentSceneIndex()));
        }
        Menu.show(items, this.mouseX(), this.mouseY());
    }

    private void renameScene() {
        EditHistory history = EditorState.history();
        int index = history.state().currentSceneIndex();
        Minecraft.getInstance().setScreen(new NameScreen(
                Kit.t("scene.rename.title"),
                Kit.t("scene.rename.explanation"),
                history.scene().name(),
                Kit.t("button.rename"),
                Kit.t("button.cancel"),
                name -> {
                    if (!name.isBlank()) {
                        EditorState.guard(() -> EditorState.history().renameScene(index, name));
                    }
                    open();
                },
                EditorScreen::open));
    }

    private double mouseX() {
        return this.minecraft.mouseHandler.xpos() * this.width / Math.max(1, this.minecraft.getWindow().getScreenWidth());
    }

    private double mouseY() {
        return this.minecraft.mouseHandler.ypos() * this.height / Math.max(1, this.minecraft.getWindow().getScreenHeight());
    }

    // ------------------------------------------------------------------ The screen as the game sees it

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    /** No blur and no dimming: only the panels */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void onClose() {
        wanted = false;
        Entry.blur();
        Menu.close();
        EditorState.pick = null;
        this.stopFlying();
        super.onClose();
    }

    /** The screen was taken away. After a jump {@link #watch} puts it back; otherwise it stays away */
    @Override
    public void removed() {
        EditorState.guard(Entry::blur);
        Menu.close();
        this.held = null;
        this.stopFlying();
        if (this.minecraft != null) {
            // Whatever comes next is drawn at the game's own size
            int usual = usualScale(this.minecraft);
            if (usual != (int) this.minecraft.getWindow().getGuiScale()) {
                this.minecraft.getWindow().setGuiScale(usual);
            }
        }
        if (!wanted || swapping) {
            return;
        }
        if (Playback.loaded() && (Playback.jumping() || Playback.skipping()) && this.minecraft != null) {
            // Taken away by the game while the world is rebuilt: it comes back, with the pointer where it was
            pointerKept = true;
            pointerX = this.minecraft.mouseHandler.xpos();
            pointerY = this.minecraft.mouseHandler.ypos();
        } else {
            // Another screen took its place, or something closed it
            wanted = false;
        }
    }

    @Override
    public void tick() {
        // The recording was closed, or failed, behind the screen
        if (!Playback.loaded() && !Playback.jumping() && this.minecraft != null && this.minecraft.screen == this) {
            this.onClose();
        }
    }

    private boolean hidden() {
        return this.minecraft.options.hideGui || Notices.hidden();
    }

    private boolean usable() {
        return Playback.loaded() && EditorSession.open() && !Export.active();
    }

    // ------------------------------------------------------------------ Drawing

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Nothing of the editor may be in an exported picture
        if (!this.usable()) {
            return;
        }
        try {
            this.fly();
            if (this.hidden()) {
                return;
            }
            EditorState.attach();
            EditorState.validate();
            int scene = EditorState.history().state().currentSceneIndex();
            if (scene != sceneSeen) {
                // Keyframes are known by track and tick, which mean something else in another scene
                if (sceneSeen >= 0) {
                    EditorState.selection.clear();
                }
                sceneSeen = scene;
            }
            int selected = EditorState.selection.hashCode();
            if (selected != this.selectionSeen) {
                this.selectionSeen = selected;
                this.keyframeForm.forget();
            }
            this.arrange();
            this.timeline.frame(mouseX, GLFW.glfwGetMouseButton(this.minecraft.getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS);

            boolean busy = Menu.open != null || this.flying;
            int pointerX = busy ? Integer.MIN_VALUE / 2 : mouseX;
            int pointerY = busy ? Integer.MIN_VALUE / 2 : mouseY;
            this.drawPanels(graphics, pointerX, pointerY);
            this.drawNotice(graphics);
            Menu menu = Menu.open;
            if (menu != null) {
                menu.draw(graphics, this.font, mouseX, mouseY, this.width, this.height);
            } else if (!this.flying && this.held == null && !this.timeline.dragging()) {
                Component words = this.tipAt(mouseX, mouseY);
                if (words != null) {
                    this.setTooltipForNextRenderPass(words);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            if (!failureLogged) {
                failureLogged = true;
                Host.LOGGER.error("Dailies: the editor could not be drawn", e);
            }
        }
    }

    private Form sideForm() {
        if (EditorState.pick != null) {
            return this.pickForm;
        }
        return switch (EditorState.tab) {
            case EditorState.TAB_LOOKS -> this.looksForm;
            case EditorState.TAB_EXPORT -> this.exportForm;
            default -> this.keyframeForm;
        };
    }

    /** Builds the tab on show afresh when what it should hold has changed */
    private void refresh(Form form) {
        String shape;
        if (form == this.pickForm) {
            // The entities near the viewer come and go
            shape = System.identityHashCode(EditorState.pick) + ":" + System.currentTimeMillis() / 800;
        } else if (form == this.looksForm) {
            shape = LooksForms.shape();
        } else if (form == this.exportForm) {
            shape = ExportForms.shape();
        } else {
            shape = KeyframeForms.shape();
        }
        if (shape.equals(form.builtFor)) {
            return;
        }
        form.builtFor = shape;
        if (form == this.pickForm) {
            this.buildPick();
        } else if (form == this.looksForm) {
            LooksForms.build(form);
        } else if (form == this.exportForm) {
            ExportForms.build(form);
        } else {
            KeyframeForms.build(form);
        }
    }

    private void buildPick() {
        EditorState.Pick pick = EditorState.pick;
        this.pickForm.clear();
        if (pick == null) {
            return;
        }
        this.pickForm.add(new Gadget.Heading(Kit.t("pick.heading")));
        this.pickForm.add(new Gadget.Note(() -> pick.prompt(), Controls.PARCHMENT));
        this.pickForm.add(new Gadget.Push(Kit.t("button.cancel"), () -> EditorState.pick = null));
        for (Menu.Item extra : pick.extras()) {
            this.pickForm.add(new Gadget.Push(extra.label(), extra.action()));
        }
        if (pick.block()) {
            return;
        }
        List<Entity> near = ViewPick.nearby(pick.playersOnly(), 40);
        if (near.isEmpty()) {
            this.pickForm.add(new Gadget.Note(Kit.t(pick.playersOnly() ? "pick.no_players" : "pick.no_entities")));
            return;
        }
        this.pickForm.add(new Gadget.Words(() -> Kit.t("pick.nearby"), Controls.PARCHMENT_DIM));
        for (Entity entity : near) {
            this.pickForm.add(new Gadget.Push(Component.literal(ViewPick.describe(entity)), () -> {
                if (entity.isRemoved()) {
                    EditorState.say(Kit.t("pick.gone"));
                    return;
                }
                EditorState.pick = null;
                pick.entity().accept(entity);
            }));
        }
    }

    /** Works out where everything goes on a screen of this size */
    private void arrange() {
        Font font = this.font;
        this.topBottom = Kit.flow(font, this.top, 3, 2, this.width - 3, TOP_ROW) + 3;

        int timelineHeight = Math.min(TimelinePanel.wantedHeight(this.height), Math.max(37, this.height - this.topBottom - 24));
        this.timelineTop = Math.max(this.topBottom, this.height - timelineHeight);
        this.timeline.place(font, 0, this.timelineTop, this.width, this.height);

        int room = Math.max(HEADER, this.timelineTop - this.topBottom);

        // The side panel, at the right
        int sideWidth = Math.min(Math.max(150, Math.min(250, this.width * 30 / 100)), Math.max(60, this.width - 20));
        if (EditorState.sideFolded && EditorState.pick == null) {
            this.sideLeft = this.width - 20;
            this.sideBottom = this.topBottom + Math.min(room, HEADER);
            this.sideFold.clip(Integer.MIN_VALUE, Integer.MAX_VALUE);
            this.sideFold.place(font, this.sideLeft + 2, this.topBottom + 2, 16, TOP_ROW);
        } else {
            this.sideLeft = this.width - sideWidth;
            this.sideBottom = this.topBottom + room;
            int tabWidth = (sideWidth - 4 - 18 - 2 * this.tabs.size()) / Math.max(1, this.tabs.size());
            int x = this.sideLeft + 2;
            for (Gadget tab : this.tabs) {
                tab.clip(Integer.MIN_VALUE, Integer.MAX_VALUE);
                tab.place(font, x, this.topBottom + 2, tabWidth, TOP_ROW);
                x += tabWidth + 2;
            }
            this.sideFold.clip(Integer.MIN_VALUE, Integer.MAX_VALUE);
            this.sideFold.place(font, this.width - 18, this.topBottom + 2, 16, TOP_ROW);
            Form form = this.sideForm();
            this.refresh(form);
            form.place(font, this.sideLeft + 1, this.topBottom + HEADER, sideWidth - 2, Math.max(0, this.sideBottom - this.topBottom - HEADER - 1));
        }

        // The viewer's strip, at the left
        int viewerWidth = Math.min(Math.max(140, Math.min(190, this.width * 22 / 100)), Math.max(40, this.sideLeft - 4));
        this.viewerRight = viewerWidth;
        this.viewerFold.clip(Integer.MIN_VALUE, Integer.MAX_VALUE);
        this.viewerFold.place(font, viewerWidth - 18, this.topBottom + 2, 16, TOP_ROW);
        if (EditorState.viewerFolded) {
            this.viewerBottom = this.topBottom + Math.min(room, HEADER);
        } else {
            int formRoom = Math.max(0, room - HEADER - 1);
            this.viewerForm.place(font, 1, this.topBottom + HEADER, viewerWidth - 2, formRoom);
            int needed = Math.min(formRoom, this.viewerForm.contentHeight());
            this.viewerForm.place(font, 1, this.topBottom + HEADER, viewerWidth - 2, needed);
            this.viewerBottom = this.topBottom + HEADER + needed + 1;
        }

        // The players, under it
        this.playersTop = this.viewerBottom;
        int left = this.timelineTop - this.playersTop;
        if (left < HEADER) {
            this.playersBottom = this.playersTop;
            return;
        }
        this.playersFold.clip(Integer.MIN_VALUE, Integer.MAX_VALUE);
        this.playersFold.place(font, viewerWidth - 18, this.playersTop + 2, 16, TOP_ROW);
        if (EditorState.playersFolded) {
            this.playersBottom = this.playersTop + HEADER;
        } else {
            // Who is there changes as the recording plays and with every jump
            String shape = PlayerList.shape();
            if (!shape.equals(this.playersForm.builtFor)) {
                this.playersForm.builtFor = shape;
                PlayerList.build(this.playersForm);
            }
            int formRoom = Math.max(0, left - HEADER - 1);
            this.playersForm.place(font, 1, this.playersTop + HEADER, viewerWidth - 2, formRoom);
            int needed = Math.min(formRoom, this.playersForm.contentHeight());
            this.playersForm.place(font, 1, this.playersTop + HEADER, viewerWidth - 2, needed);
            this.playersBottom = this.playersTop + HEADER + needed + 1;
        }
    }

    private void drawPanels(GuiGraphics graphics, int mouseX, int mouseY) {
        Font font = this.font;
        Kit.panel(graphics, 0, 0, this.width, this.topBottom);
        for (Gadget gadget : this.top) {
            gadget.draw(graphics, font, mouseX, mouseY);
        }

        Kit.panel(graphics, 0, this.topBottom, this.viewerRight, this.viewerBottom);
        Kit.text(graphics, font, Kit.clip(font, Kit.t("viewer.title").getString(), this.viewerRight - 26), 4, this.topBottom + 5, Controls.BRASS_BRIGHT);
        this.viewerFold.draw(graphics, font, mouseX, mouseY);
        if (!EditorState.viewerFolded) {
            this.viewerForm.draw(graphics, font, mouseX, mouseY);
        }

        if (this.playersBottom > this.playersTop) {
            Kit.panel(graphics, 0, this.playersTop, this.viewerRight, this.playersBottom);
            Kit.text(graphics, font, Kit.clip(font, Kit.t("players.title").getString(), this.viewerRight - 26), 4, this.playersTop + 5, Controls.BRASS_BRIGHT);
            this.playersFold.draw(graphics, font, mouseX, mouseY);
            if (!EditorState.playersFolded) {
                this.playersForm.draw(graphics, font, mouseX, mouseY);
            }
        }

        Kit.panel(graphics, this.sideLeft, this.topBottom, this.width, this.sideBottom);
        this.sideFold.draw(graphics, font, mouseX, mouseY);
        if (!EditorState.sideFolded || EditorState.pick != null) {
            for (Gadget tab : this.tabs) {
                tab.draw(graphics, font, mouseX, mouseY);
            }
            this.sideForm().draw(graphics, font, mouseX, mouseY);
        }

        this.timeline.draw(graphics, font, mouseX, mouseY);
    }

    /** A line of words under the top bar: what a click in the view is wanted for, or the latest notice */
    private void drawNotice(GuiGraphics graphics) {
        EditorState.Pick pick = EditorState.pick;
        Component words = pick != null ? Kit.t("pick.banner", pick.prompt()) : EditorState.notice();
        if (words == null) {
            return;
        }
        int left = this.viewerRight + 4;
        int right = this.sideLeft - 4;
        if (right - left < 60) {
            left = 4;
            right = this.width - 4;
        }
        String shown = Kit.clip(this.font, words.getString(), right - left - 12);
        int width = this.font.width(shown) + 12;
        int x = left + (right - left - width) / 2;
        int y = this.topBottom + 3;
        graphics.fill(x, y, x + width, y + 14, Controls.WALNUT_GLASS);
        graphics.fill(x, y + 13, x + width, y + 14, pick != null ? Controls.BRASS_BRIGHT : Controls.BRASS);
        Kit.text(graphics, this.font, shown, x + 6, y + 3, Controls.PARCHMENT);
    }

    private @Nullable Component tipAt(double mouseX, double mouseY) {
        for (Gadget gadget : this.top) {
            Component words = gadget.tipAt(mouseX, mouseY);
            if (words != null) {
                return words;
            }
        }
        Component words = this.sideFold.tipAt(mouseX, mouseY);
        if (words == null) {
            words = this.viewerFold.tipAt(mouseX, mouseY);
        }
        if (words == null && (!EditorState.sideFolded || EditorState.pick != null)) {
            for (Gadget tab : this.tabs) {
                if (words == null) {
                    words = tab.tipAt(mouseX, mouseY);
                }
            }
            if (words == null) {
                words = this.sideForm().tipAt(mouseX, mouseY);
            }
        }
        if (words == null && !EditorState.viewerFolded) {
            words = this.viewerForm.tipAt(mouseX, mouseY);
        }
        if (words == null && this.playersBottom > this.playersTop) {
            words = this.playersFold.tipAt(mouseX, mouseY);
            if (words == null && !EditorState.playersFolded) {
                words = this.playersForm.tipAt(mouseX, mouseY);
            }
        }
        if (words == null) {
            words = this.timeline.tipAt(mouseX, mouseY);
        }
        if (words == null && EditorState.pick != null && this.overView(mouseX, mouseY)) {
            // What a click here would choose
            EditorState.Pick pick = EditorState.pick;
            ViewPick.Ray ray = ViewPick.through(mouseX, mouseY, this.width, this.height);
            if (pick.block()) {
                BlockHitResult hit = ViewPick.block(ray);
                if (hit != null && this.minecraft.level != null) {
                    words = Component.literal(this.minecraft.level.getBlockState(hit.getBlockPos()).getBlock().getName().getString() + "  " + hit.getBlockPos().toShortString());
                }
            } else {
                Entity entity = ViewPick.entity(ray, pick.playersOnly());
                if (entity != null) {
                    words = Component.literal(ViewPick.describe(entity));
                }
            }
        }
        if (words == null && EditorState.pick == null && this.overView(mouseX, mouseY)) {
            // What is drawn in the world itself: a keyframe of the camera path, a marker's ring
            ViewPick.Ray ray = ViewPick.through(mouseX, mouseY, this.width, this.height);
            PathPreview.Mark mark = PathPreview.at(ray);
            MarkerRings.Ring ring = mark == null ? MarkerRings.at(ray) : null;
            words = mark != null ? PathPreview.describe(mark) : ring != null ? MarkerRings.describe(ring) : null;
        }
        return words;
    }

    private boolean overPanels(double mouseX, double mouseY) {
        if (mouseY < this.topBottom || this.timeline.over(mouseX, mouseY)) {
            return true;
        }
        if (mouseX >= this.sideLeft && mouseY < this.sideBottom) {
            return true;
        }
        return mouseX < this.viewerRight && mouseY < Math.max(this.viewerBottom, this.playersBottom);
    }

    private boolean overView(double mouseX, double mouseY) {
        return !this.overPanels(mouseX, mouseY);
    }

    // ------------------------------------------------------------------ The mouse

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!this.usable()) {
            return true;
        }
        EditorState.guard(() -> this.pressed(mouseX, mouseY, button));
        return true;
    }

    private void pressed(double mouseX, double mouseY, int button) {
        if (this.flying) {
            // The pointer is hidden: a click cannot be meant for anything under it
            return;
        }
        if (this.hidden()) {
            if (button == 1) {
                this.startFlying();
            }
            return;
        }
        Menu menu = Menu.open;
        if (menu != null) {
            menu.press(mouseX, mouseY);
            return;
        }
        Entry typing = Entry.focused;
        if (typing != null && !typing.over(mouseX, mouseY)) {
            // What was typed is handed over before the click does anything, so the click acts on the new value
            Entry.blur();
        }
        if (this.overPanels(mouseX, mouseY)) {
            this.held = this.pressPanels(mouseX, mouseY, button);
            return;
        }
        if (button == 1) {
            this.startFlying();
        } else if (button == 0 && EditorState.pick != null) {
            this.pickAt(mouseX, mouseY);
        } else if (button == 0) {
            this.clickWorld(mouseX, mouseY);
        }
    }

    /** A click in the view on something Dailies draws in the world: a keyframe's mark is selected, a marker's ring is jumped to */
    private void clickWorld(double mouseX, double mouseY) {
        ViewPick.Ray ray = ViewPick.through(mouseX, mouseY, this.width, this.height);
        PathPreview.Mark mark = PathPreview.at(ray);
        if (mark != null) {
            if (Screen.hasShiftDown() || Screen.hasControlDown()) {
                EditorState.selection.add(new KeyframeRef(mark.track(), mark.tick()));
            } else {
                EditorState.select(mark.track(), mark.tick());
            }
            Kit.click();
            return;
        }
        MarkerRings.Ring ring = MarkerRings.at(ray);
        if (ring != null) {
            Kit.click();
            Controls.jumpTo(ring.tick());
        }
    }

    private @Nullable Gadget pressPanels(double mouseX, double mouseY, int button) {
        for (Gadget gadget : this.top) {
            Gadget hit = gadget.press(mouseX, mouseY, button);
            if (hit != null) {
                return hit;
            }
        }
        Gadget hit = this.sideFold.press(mouseX, mouseY, button);
        if (hit != null) {
            return hit;
        }
        hit = this.viewerFold.press(mouseX, mouseY, button);
        if (hit != null) {
            return hit;
        }
        if (mouseX >= this.sideLeft && mouseY < this.sideBottom && (!EditorState.sideFolded || EditorState.pick != null)) {
            for (Gadget tab : this.tabs) {
                hit = tab.press(mouseX, mouseY, button);
                if (hit != null) {
                    return hit;
                }
            }
            return this.sideForm().press(mouseX, mouseY, button);
        }
        if (mouseX < this.viewerRight && mouseY < this.viewerBottom && mouseY >= this.topBottom) {
            return EditorState.viewerFolded ? null : this.viewerForm.press(mouseX, mouseY, button);
        }
        if (mouseX < this.viewerRight && mouseY >= this.playersTop && mouseY < this.playersBottom) {
            hit = this.playersFold.press(mouseX, mouseY, button);
            return hit != null || EditorState.playersFolded ? hit : this.playersForm.press(mouseX, mouseY, button);
        }
        if (this.timeline.over(mouseX, mouseY)) {
            return this.timeline.press(mouseX, mouseY, button);
        }
        return null;
    }

    private void pickAt(double mouseX, double mouseY) {
        EditorState.Pick pick = EditorState.pick;
        if (pick == null) {
            return;
        }
        ViewPick.Ray ray = ViewPick.through(mouseX, mouseY, this.width, this.height);
        if (pick.block()) {
            BlockHitResult hit = ViewPick.block(ray);
            if (hit == null) {
                EditorState.say(Kit.t("notice.no_block"));
                return;
            }
            EditorState.pick = null;
            pick.blockAt().accept(hit.getBlockPos());
        } else {
            Entity entity = ViewPick.entity(ray, pick.playersOnly());
            if (entity == null) {
                EditorState.say(Kit.t(pick.playersOnly() ? "notice.no_player" : "notice.no_entity"));
                return;
            }
            EditorState.pick = null;
            pick.entity().accept(entity);
        }
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 1) {
            this.stopFlying();
        }
        Gadget was = this.held;
        this.held = null;
        if (this.usable()) {
            EditorState.guard(() -> {
                if (was != null) {
                    was.release(mouseX, mouseY);
                }
                if (button == 0) {
                    this.timeline.release(mouseX, mouseY);
                }
            });
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.flying) {
            return true;
        }
        if (button == 0 && this.usable() && !this.hidden()) {
            EditorState.guard(() -> {
                if (this.held != null) {
                    this.held.drag(mouseX, mouseY);
                }
                this.timeline.dragTo(mouseX, mouseY);
            });
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!this.usable()) {
            return true;
        }
        if (this.flying) {
            // The wheel sets how fast the camera flies
            Viewer.setBlocksPerSecond(Viewer.blocksPerSecond() * Math.pow(1.2, scrollY));
            EditorState.say(Kit.t("viewer.speed.value", Kit.number(Math.rint(Viewer.blocksPerSecond() * 100.0) / 100.0)));
            return true;
        }
        if (this.hidden()) {
            return true;
        }
        Menu menu = Menu.open;
        if (menu != null) {
            menu.wheel(scrollY);
            return true;
        }
        EditorState.guard(() -> {
            if (mouseX >= this.sideLeft && mouseY < this.sideBottom && (!EditorState.sideFolded || EditorState.pick != null)) {
                this.sideForm().wheel(mouseX, mouseY, scrollY);
            } else if (!EditorState.viewerFolded && this.viewerForm.wheel(mouseX, mouseY, scrollY)) {
                return;
            } else if (!EditorState.playersFolded && this.playersBottom > this.playersTop && this.playersForm.wheel(mouseX, mouseY, scrollY)) {
                return;
            } else {
                this.timeline.wheel(mouseX, mouseY, scrollX, scrollY);
            }
        });
        return true;
    }

    // ------------------------------------------------------------------ The keys

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!this.usable()) {
            return keyCode != GLFW.GLFW_KEY_ESCAPE || this.escape();
        }
        EditorState.guard(() -> this.key(keyCode, scanCode, modifiers));
        return true;
    }

    private boolean escape() {
        this.onClose();
        return true;
    }

    private void key(int keyCode, int scanCode, int modifiers) {
        Entry typing = Entry.focused;
        if (typing != null) {
            typing.key(keyCode, scanCode, modifiers);
            return;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (Menu.open != null) {
                Menu.close();
            } else if (EditorState.pick != null) {
                EditorState.pick = null;
            } else if (this.hidden()) {
                this.minecraft.options.hideGui = false;
                Notices.setHidden(false);
            } else {
                this.onClose();
            }
            return;
        }
        if (keyCode == GLFW.GLFW_KEY_F1) {
            // The game's own key for hiding its interface hides the editor with it
            this.minecraft.options.hideGui = !this.minecraft.options.hideGui;
            Menu.close();
            return;
        }
        if (this.flying || this.hidden()) {
            return;
        }
        boolean control = Screen.hasControlDown();
        if (control && keyCode == GLFW.GLFW_KEY_Z) {
            if (Screen.hasShiftDown()) {
                EditorState.redo();
            } else {
                EditorState.undo();
            }
        } else if (control && keyCode == GLFW.GLFW_KEY_Y) {
            EditorState.redo();
        } else if (control && keyCode == GLFW.GLFW_KEY_C) {
            EditorState.copySelected();
        } else if (control && keyCode == GLFW.GLFW_KEY_V) {
            EditorState.pasteAtPlayhead();
        } else if (control && keyCode == GLFW.GLFW_KEY_A) {
            EditorState.selection.clear();
            Scene scene = EditorState.scene();
            for (int index = 0; index < scene.tracks().size(); index++) {
                for (Keyframe keyframe : scene.track(index).keyframes()) {
                    EditorState.selection.add(new KeyframeRef(index, keyframe.tick()));
                }
            }
        } else if (keyCode == GLFW.GLFW_KEY_DELETE || keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            EditorState.removeSelected();
        } else if (keyCode == GLFW.GLFW_KEY_HOME) {
            Controls.jumpTo(0);
        } else if (keyCode == GLFW.GLFW_KEY_END) {
            Controls.jumpTo(Playback.total());
        } else if (control) {
            // Not ours, and not to be taken for a playback key
            return;
        } else if (Keys.isTimelineKey(keyCode, scanCode)) {
            this.onClose();
        } else {
            Keys.playbackKeyPressed(keyCode, scanCode);
        }
    }

    @Override
    public boolean charTyped(char character, int modifiers) {
        Entry typing = Entry.focused;
        if (typing != null && this.usable()) {
            EditorState.guard(() -> typing.typed(character, modifiers));
        }
        return true;
    }

    // ------------------------------------------------------------------ Flying with the right button held

    private void startFlying() {
        if (this.flying) {
            return;
        }
        if (Viewer.riding() != null) {
            EditorState.say(Kit.t("notice.riding"));
            return;
        }
        Entry.blur();
        Menu.close();
        this.flying = true;
        this.turnSettling = 2;
        this.velocity = Vec3.ZERO;
        this.lastNanos = 0;
        this.cursorX = this.minecraft.mouseHandler.xpos();
        this.cursorY = this.minecraft.mouseHandler.ypos();
        // The pointer is hidden and held while the button is down, as it is in play, and put back where it was after
        InputConstants.grabOrReleaseMouse(this.minecraft.getWindow().getWindow(), GLFW.GLFW_CURSOR_DISABLED, this.cursorX, this.cursorY);
    }

    private void stopFlying() {
        if (!this.flying) {
            return;
        }
        this.flying = false;
        this.velocity = Vec3.ZERO;
        Minecraft game = Minecraft.getInstance();
        InputConstants.grabOrReleaseMouse(game.getWindow().getWindow(), GLFW.GLFW_CURSOR_NORMAL, this.cursorX, this.cursorY);
    }

    private boolean down(KeyMapping key) {
        InputConstants.Key bound = key.getKey();
        return bound.getType() == InputConstants.Type.KEYSYM && bound.getValue() > 0
                && InputConstants.isKeyDown(this.minecraft.getWindow().getWindow(), bound.getValue());
    }

    /**
     * Once a frame. The movement keys fly the camera whenever nothing is being typed; holding the right button over the
     * view also turns it by what the mouse moves.
     */
    private void fly() {
        long window = this.minecraft.getWindow().getWindow();
        if (this.flying && (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) != GLFW.GLFW_PRESS || !this.minecraft.isWindowActive() || Viewer.riding() != null)) {
            this.stopFlying();
        }
        boolean keys = this.flying || (Entry.focused == null && Menu.open == null && !Screen.hasControlDown() && !Screen.hasAltDown()
            && Viewer.riding() == null && this.minecraft.isWindowActive());
        if (!keys) {
            this.velocity = Vec3.ZERO;
            this.lastNanos = 0;
            return;
        }
        long now = System.nanoTime();
        double seconds = this.lastNanos == 0 ? 0.0 : Math.min(0.25, (now - this.lastNanos) / 1.0e9);
        this.lastNanos = now;

        Options options = this.minecraft.options;
        double forward = (this.down(options.keyUp) ? 1 : 0) - (this.down(options.keyDown) ? 1 : 0);
        double strafe = (this.down(options.keyLeft) ? 1 : 0) - (this.down(options.keyRight) ? 1 : 0);
        // Shift is also held to choose several things with the mouse, which must not sink the camera
        boolean choosing = !this.flying && GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        double lift = (this.down(options.keyJump) ? 1 : 0) - (this.down(options.keyShift) && !choosing ? 1 : 0);
        boolean moving = forward != 0.0 || strafe != 0.0 || lift != 0.0;
        // How far the hidden pointer has moved since the last frame, in the mouse's own measure
        double mouseX = this.minecraft.mouseHandler.xpos();
        double mouseY = this.minecraft.mouseHandler.ypos();
        double turnX = 0.0;
        double turnY = 0.0;
        if (!this.flying) {
            // The pointer is free and in use: only the keys move the camera
        } else if (this.turnSettling > 0) {
            this.turnSettling--;
        } else {
            turnX = Mth.clamp(mouseX - this.lastMouseX, -400.0, 400.0);
            turnY = Mth.clamp(mouseY - this.lastMouseY, -400.0, 400.0);
        }
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        boolean turning = turnX != 0.0 || turnY != 0.0;
        if ((moving || turning) && EditorSession.followCamera() && !Playback.paused()) {
            // Flying off by hand takes the camera away from its track until following is switched back on (EDIT-5)
            EditorSession.setFollowCamera(false);
            EditorState.say(Component.translatable("dailies.ui.notice.camera_free"));
        }
        if (!moving && !turning && this.velocity.lengthSqr() < 1.0e-6) {
            this.velocity = Vec3.ZERO;
            return;
        }

        // The mouse turns the camera as it turns the player in play
        double sensitivity = options.sensitivity().get() * 0.6 + 0.2;
        double perPixel = sensitivity * sensitivity * sensitivity * 8.0 * 0.15;
        float yaw = Viewer.yaw() + (float) (turnX * perPixel);
        float pitch = Mth.clamp(Viewer.pitch() + (float) (turnY * perPixel * (options.invertYMouse().get() ? -1.0 : 1.0)), -90.0F, 90.0F);

        boolean lookRelative = EditorState.setting("flightLookRelative", false);
        Vec3 ahead = Vec3.directionFromRotation(lookRelative ? pitch : 0.0F, yaw);
        Vec3 side = Vec3.directionFromRotation(0.0F, yaw - 90.0F);
        Vec3 wantedSpeed = ahead.scale(forward).add(side.scale(strafe)).add(0.0, lift, 0.0);
        if (wantedSpeed.lengthSqr() > 1.0) {
            wantedSpeed = wantedSpeed.normalize();
        }
        wantedSpeed = wantedSpeed.scale(Viewer.blocksPerSecond() * (this.down(options.keySprint) ? 3.0 : 1.0));
        if (EditorState.setting("flightMomentum", true)) {
            // Glide towards the wanted speed, as the viewer does outside the editor
            double blend = 1.0 - Math.exp(-seconds / 0.08);
            this.velocity = this.velocity.add(wantedSpeed.subtract(this.velocity).scale(blend));
        } else {
            this.velocity = wantedSpeed;
        }
        Vec3 at = Viewer.position().add(this.velocity.scale(seconds));
        Viewer.set(at.x, at.y, at.z, yaw, pitch);
    }

}
