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
import com.google.gson.JsonObject;
import comedymonkey.dailies.compat.iris.IrisShaders;
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
 * The editor (specification chapter V), opened with the timeline key while a recording is watched. The view is the
 * point of it, so what is round it is kept narrow: one row along the top for the scene, the export range, undo and
 * redo, the moments and the way out, with the rarer things in a "..." menu; the timeline with its tracks along the
 * bottom; at the right a panel with three tabs for the selected keyframe, the scene's looks and the export, which
 * folds to a slim edge; and two small see-through cards over the top left of the view for the viewer's controls and
 * the players in the recording, which fold to their titles. Where each goes is worked out by {@link EditorLayout}.
 * In the view itself a click on a keyframe's mark along the camera path selects it, and a click on a marker's ring
 * jumps to the marker.
 * <p>
 * The recording keeps playing behind it: the screen neither pauses nor dims nor blurs the world. Holding the right
 * mouse button over the view turns the camera and lets the movement keys fly it, so a shot can be framed without
 * closing the editor. Nothing is drawn while an export is under way.
 * <p>
 * The Tab key puts a parchment ring on one control after another, bar first, then the cards, the panel and the
 * timeline's buttons; Enter or the space bar then presses it. Until Tab is pressed no control has the ring, and every
 * key does what it always did.
 */
public final class EditorScreen extends Screen {

    /** Shows a line of words in the editor's own place for them, or as an ordinary notice while the editor is closed */
    public static void say(net.minecraft.network.chat.Component words) {
        EditorState.say(words);
    }

    private static final int ROW = EditorLayout.ROW;
    private static final int GAP = EditorLayout.GAP;
    private static final int PAD = EditorLayout.PAD;
    private static final int HEADER = EditorLayout.HEADER;
    /** The room between two groups of controls in the bar, where a thin line stands */
    private static final int GROUP_GAP = 9;

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
    private final Form keyframeForm = new Form().fitNames();
    private final Form looksForm = new Form().fitNames();
    private final Form exportForm = new Form().fitNames();
    private final Form pickForm = new Form();
    private final Form viewerForm = new Form().fitNames();
    private final Form playersForm = new Form();
    /** Every control of the bar, left to right */
    private final List<Gadget> top = new ArrayList<>();
    /** The bar's controls in their groups: those that start at the left, and those that end at the right */
    private final List<List<Gadget>> topLeft = new ArrayList<>();
    private final List<List<Gadget>> topRight = new ArrayList<>();
    private final List<Gadget> tabs = new ArrayList<>();
    /** The tabs again, standing on end along the panel's edge while it is folded */
    private final List<Gadget> edgeTabs = new ArrayList<>();
    private Gadget.Push more;
    private Gadget.Push sideFold;
    private Gadget.Push viewerFold;
    private Gadget.Push playersFold;
    /** How the mouse and the keys reach the controls, as in every other Dailies screen */
    private final InputRouter input = new InputRouter();
    /** The revision of the selection the keyframe tab last saw */
    private long selectionSeen = -1;
    /** The things near the viewer the pick panel lists, looked for again every 0.8 s, and the word for them */
    private List<Entity> pickNear = List.of();
    private long pickNearAt = 0;
    private int pickNearFor = 0;
    private String pickShape = "";

    private EditorLayout.Layout layout = EditorLayout.of(new EditorLayout.Input(1920, 1080, 2, false, false, false, false, 0, 0, 0, 0, 40, 40));
    /** Where the thin lines between the bar's groups stand */
    private final List<Integer> dividers = new ArrayList<>();
    /** The part of the bar no control takes, where notices are shown when they fit */
    private int barFreeLeft;
    private int barFreeRight;

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
        int scale = EditorLayout.scaleFor(window.getWidth(), window.getHeight(), usualScale(this.minecraft));
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
            Minecraft.getInstance().setScreen(opening());
        }
    }

    /** The editor is not to come back by itself, even if the game took it away for a jump */
    static void letGo() {
        wanted = false;
    }

    /** A new editor, wanted on screen from now on, for whoever is about to put it there: {@link #open}, or a screen being swapped for it */
    static EditorScreen opening() {
        wanted = true;
        pointerKept = false;
        return new EditorScreen();
    }

    /**
     * Once a frame. Puts the editor back after the game has taken it away for a jump, puts the progress display in
     * its place while a video is exported or the path preview plays a Smart Camera stretch, and says how the export went
     * when it is over.
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
            if (comedymonkey.dailies.editor.StretchPreview.running()) {
                // The path preview plays a Smart Camera stretch with the world undrawn: its plate stands in the editor's place
                if (screen == null || screen instanceof EditorScreen) {
                    show(minecraft, new PreviewProgress());
                }
                return;
            }
            if ((screen == null || screen instanceof ExportProgress || screen instanceof PreviewProgress) && !Playback.jumping()) {
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
        this.input.begin();
        EditorState.attach();
        this.top.clear();
        this.topLeft.clear();
        this.topRight.clear();
        this.tabs.clear();
        this.edgeTabs.clear();
        this.buildTop();

        String[] names = {"tab.keyframe", "tab.looks", "tab.export"};
        int[] which = {EditorState.TAB_KEYFRAME, EditorState.TAB_LOOKS, EditorState.TAB_EXPORT};
        for (int i = 0; i < names.length; i++) {
            int tab = which[i];
            this.tabs.add(new Gadget.Push(Kit.t(names[i]), () -> this.showTab(tab))
                    .lit(() -> EditorState.pick == null && EditorState.tab == tab).tip(Kit.t(names[i] + ".tip")));
            this.edgeTabs.add(new EdgeTab(Kit.t(names[i]), () -> this.showTab(tab), () -> EditorState.tab == tab).tip(Kit.t(names[i] + ".tip")));
        }
        this.sideFold = Gadget.Push.icon(() -> EditorState.sideFolded ? Kit.Icon.FOLD_LEFT : Kit.Icon.FOLD_RIGHT, Kit.t("side.fold"),
                () -> EditorState.sideFolded = !EditorState.sideFolded);
        this.viewerFold = Gadget.Push.icon(() -> EditorState.viewerFolded ? Kit.Icon.FOLD_DOWN : Kit.Icon.FOLD_UP, Kit.t("viewer.fold"),
                () -> EditorState.viewerFolded = !EditorState.viewerFolded);
        this.playersFold = Gadget.Push.icon(() -> EditorState.playersFolded ? Kit.Icon.FOLD_DOWN : Kit.Icon.FOLD_UP, Kit.t("players.fold"),
                () -> EditorState.playersFolded = !EditorState.playersFolded);
        // Square, to stand in a card's title row
        this.sideFold.wide(ROW);
        this.viewerFold.wide(ROW);
        this.playersFold.wide(ROW);

        this.keyframeForm.builtFor = "";
        this.looksForm.builtFor = "";
        this.exportForm.builtFor = "";
        this.pickForm.builtFor = "";
        this.playersForm.builtFor = "";
        ViewerForms.build(this.viewerForm);
        if (this.usable()) {
            // So that a click before the first frame lands where the controls will be; each frame arranges it again
            this.arrange();
        }
    }

    private void showTab(int tab) {
        EditorState.pick = null;
        EditorState.tab = tab;
        EditorState.sideFolded = false;
    }

    /** The bar: the scene, the export range, undo and redo, the moments, the rarer things and the way out */
    private void buildTop() {
        // The scene, as a dropdown of every scene of the recording
        Gadget.Dropdown<Integer> scenes = new Gadget.Dropdown<>(() -> {
            List<Integer> all = new ArrayList<>();
            for (int index = 0; index < EditorState.history().state().scenes().size(); index++) {
                all.add(index);
            }
            return all;
        }, index -> Component.literal(EditorState.history().state().scene(index).name()), () -> EditorState.history().state().currentSceneIndex(), chosen -> {
            if (chosen != EditorState.history().state().currentSceneIndex()) {
                EditorState.selection.clear();
                EditorState.history().selectScene(chosen);
            }
        });
        scenes.wide(128);
        scenes.tip(Kit.t("scene.current.tip"));
        this.group(this.topLeft, scenes);

        List<Gadget> range = new ArrayList<>();
        range.add(new Gadget.Words(() -> Kit.t("range.label"), Controls.PARCHMENT_DIM).tip(Kit.t("range.tip")));
        range.add(Entry.number(() -> EditorState.scene().hasExportRange() ? Double.valueOf(EditorState.scene().exportStart()) : null,
                0, Keyframe.MAX_TICK, true, value -> {
                    Scene scene = EditorState.scene();
                    int start = (int) Math.rint(value);
                    if (start > Playback.total()) {
                        return Kit.t("error.range", 0, Playback.total());
                    }
                    EditorState.history().setExportRange(start, Math.max(start, scene.hasExportRange() ? scene.exportEnd() : Playback.total()));
                    return null;
                }).errorsAsNotice().hint(Kit.t("range.start.hint")).wide(48).tip(Kit.t("range.start.tip")));
        range.add(Entry.number(() -> EditorState.scene().hasExportRange() ? Double.valueOf(EditorState.scene().exportEnd()) : null,
                0, Keyframe.MAX_TICK, true, value -> {
                    Scene scene = EditorState.scene();
                    int end = Math.min(Playback.total(), (int) Math.rint(value));
                    EditorState.history().setExportRange(scene.hasExportRange() ? scene.exportStart() : 0, end);
                    return null;
                }).errorsAsNotice().hint(Kit.t("range.end.hint")).wide(48).tip(Kit.t("range.end.tip")));
        range.add(new Gadget.Push(Kit.t("range.start_here"), () -> {
            Scene scene = EditorState.scene();
            int start = Playback.position();
            EditorState.history().setExportRange(start, scene.hasExportRange() ? Math.max(start, scene.exportEnd()) : Math.max(start, Playback.total()));
        }).tip(Kit.t("range.start_here.tip")));
        range.add(new Gadget.Push(Kit.t("range.end_here"), () -> {
            Scene scene = EditorState.scene();
            int end = Playback.position();
            EditorState.history().setExportRange(scene.hasExportRange() ? Math.min(end, scene.exportStart()) : 0, end);
        }).tip(Kit.t("range.end_here.tip")));
        // Trimming starts from the export range: what is kept is what would be exported (FILE-10)
        range.add(new Gadget.Push(Kit.t("range.trim"), () -> Minecraft.getInstance().setScreen(TrimScreen.fromEditor(this)))
                .tip(Kit.t("range.trim.tip")));
        this.group(this.topLeft, range.toArray(new Gadget[0]));

        Gadget undo = Gadget.Push.icon(Kit.Icon.UNDO, Component.translatable("dailies.ui.timeline.undo"), EditorState::undo).liveTip(() -> {
            String what = EditorState.history().undoDescription();
            return what == null ? Component.translatable("dailies.ui.notice.nothing_to_undo") : Kit.t("undo.tip", what);
        }).when(() -> EditorState.history().canUndo());
        Gadget redo = Gadget.Push.icon(Kit.Icon.REDO, Component.translatable("dailies.ui.timeline.redo"), EditorState::redo).liveTip(() -> {
            String what = EditorState.history().redoDescription();
            return what == null ? Component.translatable("dailies.ui.notice.nothing_to_redo") : Kit.t("redo.tip", what);
        }).when(() -> EditorState.history().canRedo());
        this.group(this.topRight, undo, redo);

        // Iris's shaders on or off at a click: the scene's shaders look, so it can be undone and an export does the same.
        // Only there when Iris is
        if (IrisShaders.available()) {
            this.group(this.topRight, new Gadget.Push(() -> Kit.t(shadersShown() ? "shaders.on" : "shaders.off"), EditorScreen::switchShaders)
                .lit(EditorScreen::shadersShown)
                .tip(Kit.t("shaders.tip")));
        }

        this.more =Gadget.Push.icon(Kit.Icon.DOTS, Kit.t("more.tip"), this::openMoreMenu);
        this.more.lit(() -> Menu.openFor(this.more));
        this.group(this.topRight,
                new Gadget.Push(Component.translatable("dailies.ui.pause.moments"), Controls::openMomentsPage).tip(Component.translatable("dailies.ui.moments.tip")),
                this.more,
                new Gadget.Push(Kit.t("close"), this::onClose).tip(Kit.t("close.tip")));
        for (List<Gadget> group : this.topLeft) {
            this.top.addAll(group);
        }
        for (List<Gadget> group : this.topRight) {
            this.top.addAll(group);
        }
    }

    /** Whether the scene shows shaders: as its look says, or as Iris has them when the look leaves it to the game */
    private static boolean shadersShown() {
        return switch (EditorState.scene().looks().shaders) {
            case ON -> true;
            case OFF -> false;
            case AS_GAME -> Boolean.TRUE.equals(IrisShaders.enabledNow());
        };
    }

    /** The shaders button: the scene's shaders look set to the other of on and off, as one step to undo */
    private static void switchShaders() {
        JsonObject values = new JsonObject();
        values.addProperty("shaders", shadersShown() ? "off" : "on");
        try {
            EditorState.history().setLooks(values);
        } catch (IllegalArgumentException | IllegalStateException refused) {
            EditorState.say(Component.literal(String.valueOf(refused.getMessage())));
        }
    }

    private void group(List<List<Gadget>> side, Gadget... gadgets) {
        side.add(List.of(gadgets));
    }

    /** The rarer things, hanging from the "..." button: what is done to scenes, and forgetting the export range */
    private void openMoreMenu() {
        Menu.drop(this.moreItems(), this.more.x, this.more.y, this.more.width, ROW, this.more);
    }

    private List<Menu.Item> moreItems() {
        List<Menu.Item> items = new ArrayList<>();
        items.add(new Menu.Item(Kit.t("more.scene_add"), () -> {
            EditorState.selection.clear();
            EditorState.history().addScene(null);
        }));
        items.add(new Menu.Item(Kit.t("more.scene_rename"), this::renameScene));
        items.add(new Menu.Item(Kit.t("more.scene_duplicate"), () -> {
            EditorState.selection.clear();
            EditorState.history().duplicateScene(EditorState.history().state().currentSceneIndex());
        }));
        items.add(new Menu.Item(Kit.t("more.scene_remove"), () -> {
            EditorState.selection.clear();
            int doomed = EditorState.history().state().currentSceneIndex();
            // Into the bin, from where it can be put back (REC-9)
            comedymonkey.dailies.library.Library.binScene(EditorState.history().state().scene(doomed));
            EditorState.history().removeScene(doomed);
        }, EditorState.history().state().scenes().size() > 1, false));
        items.add(new Menu.Item(Kit.t("more.range_clear"), () -> EditorState.history().clearExportRange(), EditorState.scene().hasExportRange(), false));
        return items;
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

    /** For the picture-taking scripts: opens the "..." menu as a click on it would */
    boolean openMore() {
        if (this.more == null) {
            return false;
        }
        this.arrange();
        this.openMoreMenu();
        return Menu.open != null;
    }

    /** For the scripted test: the share of the window the view has as the editor is laid out now */
    double viewShare() {
        return this.layout.viewShare();
    }

    /** For the scripted test: whatever is wrong with the layout as it stands, in words; empty when nothing is */
    List<String> layoutProblems() {
        this.arrange();
        List<String> problems = new ArrayList<>();
        EditorLayout.Layout at = this.layout;
        List<EditorLayout.Rect> all = new ArrayList<>(at.docked());
        all.addAll(at.cards());
        for (EditorLayout.Rect rect : all) {
            if (!rect.within(this.width, this.height)) {
                problems.add(rect + " is off a screen of " + this.width + "x" + this.height);
            }
        }
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                if (all.get(i).overlaps(all.get(j))) {
                    problems.add(all.get(i) + " overlaps " + all.get(j));
                }
            }
        }
        for (Gadget gadget : this.top) {
            if (gadget.height <= 0 || gadget.x < 0 || gadget.x + gadget.width > this.width) {
                problems.add("a control of the bar had no room: " + gadget.getClass().getSimpleName() + " " + gadget.tip);
            }
        }
        if (at.width() != this.width || at.height() != this.height) {
            problems.add("the layout was worked out for " + at.width() + "x" + at.height() + " but the screen is " + this.width + "x" + this.height);
        }
        return problems;
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
        Gadget.focus = null;
        EditorState.pick = null;
        this.stopFlying();
        super.onClose();
    }

    /** The screen was taken away. After a jump {@link #watch} puts it back; otherwise it stays away */
    @Override
    public void removed() {
        this.input.end();
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
            long selected = EditorState.selection.revision();
            if (selected != this.selectionSeen) {
                this.selectionSeen = selected;
                this.keyframeForm.forget();
            }
            this.arrange();
            // A scene held to a frame shape shows its picture whole in what the panels leave clear
            EditorLayout.Rect clear = this.layout.frameClear();
            comedymonkey.dailies.looks.LooksRenderer.clearOfPanels((double) clear.left() / this.width, (double) clear.top() / this.height,
                    (double) clear.right() / this.width, (double) clear.bottom() / this.height);
            this.timeline.frame(mouseX, GLFW.glfwGetMouseButton(this.minecraft.getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS);

            boolean busy = Menu.open != null || this.flying;
            int pointerX = busy ? Integer.MIN_VALUE / 2 : mouseX;
            int pointerY = busy ? Integer.MIN_VALUE / 2 : mouseY;
            this.drawPanels(graphics, pointerX, pointerY);
            this.drawNotice(graphics);
            Menu menu = Menu.open;
            if (menu != null) {
                menu.draw(graphics, this.font, mouseX, mouseY, this.width, this.height);
            } else if (!this.flying && this.input.held() == null && !this.timeline.dragging()) {
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
            shape = this.pickShape();
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

    /**
     * The entities near the viewer come and go, so they are looked for again every 0.8 s. The panel is built afresh
     * only when which of them it lists has changed: building it takes the keys' ring away.
     */
    private String pickShape() {
        EditorState.Pick pick = EditorState.pick;
        int which = System.identityHashCode(pick);
        long now = net.minecraft.Util.getMillis();
        if (which != this.pickNearFor || now - this.pickNearAt >= 800 || this.pickShape.isEmpty()) {
            this.pickNearFor = which;
            this.pickNearAt = now;
            this.pickNear = pick == null || pick.block() ? List.of() : ViewPick.nearby(pick.playersOnly(), 40);
            int[] ids = new int[this.pickNear.size()];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = this.pickNear.get(i).getId();
            }
            this.pickShape = PickShape.of(which, ids);
        }
        return this.pickShape;
    }

    private void buildPick() {
        EditorState.Pick pick = EditorState.pick;
        this.pickForm.clear();
        if (pick == null) {
            return;
        }
        this.pickForm.add(new Gadget.Heading(Kit.t("pick.heading")));
        this.pickForm.add(new Gadget.Note(() -> pick.prompt(), Controls.PARCHMENT));
        this.pickForm.add(new Gadget.Push(Kit.t("button.cancel"), () -> EditorState.pick = null).tip(Kit.t("pick.cancel.tip")));
        for (Menu.Item extra : pick.extras()) {
            this.pickForm.add(new Gadget.Push(extra.label(), extra.action()).tip(extra.label()));
        }
        if (pick.block()) {
            return;
        }
        List<Entity> near = this.pickNear;
        if (near.isEmpty()) {
            this.pickForm.add(new Gadget.Note(Kit.t(pick.playersOnly() ? "pick.no_players" : "pick.no_entities")));
            return;
        }
        this.pickForm.add(new Gadget.Words(() -> Kit.t("pick.nearby"), Controls.PARCHMENT_DIM));
        for (Entity entity : near) {
            // The distance in its name is kept up to date as things move, without building the panel again
            this.pickForm.add(new Gadget.Push(() -> Component.literal(ViewPick.describe(entity)), () -> {
                if (entity.isRemoved()) {
                    EditorState.say(Kit.t("pick.gone"));
                    return;
                }
                EditorState.pick = null;
                pick.entity().accept(entity);
            }).liveTip(() -> Kit.t("pick.choose.tip", ViewPick.describe(entity))));
        }
    }

    /** Works out where everything goes on a screen of this size */
    private void arrange() {
        Font font = this.font;
        if (font == null || this.minecraft == null) {
            return;
        }
        // How high the cards' contents are, measured at the width they will have
        int cardInner = Math.max(40, EditorLayout.cardWidth(this.width) - 2);
        String shape = PlayerList.shape();
        if (!shape.equals(this.playersForm.builtFor)) {
            // Who is there changes as the recording plays and with every jump
            this.playersForm.builtFor = shape;
            PlayerList.build(this.playersForm);
        }
        com.mojang.blaze3d.platform.Window window = this.minecraft.getWindow();
        boolean sideFolded = EditorState.sideFolded && EditorState.pick == null;
        this.layout = EditorLayout.of(new EditorLayout.Input(window.getWidth(), window.getHeight(), Math.max(1, (int) window.getGuiScale()),
                EditorState.viewerFolded, EditorState.playersFolded, sideFolded, EditorState.timelineFolded,
                this.viewerForm.measure(font, cardInner) + 1, this.playersForm.measure(font, cardInner) + 1,
                EditorSession.open() ? EditorState.scene().tracks().size() : 0, MomentsLane.height(),
                font.width(Kit.t("viewer.title")), font.width(Kit.t("players.title"))));
        EditorLayout.Layout at = this.layout;

        this.arrangeBar(font, at.bar());
        EditorLayout.Rect timeline = at.timeline();
        this.timeline.place(font, timeline.left(), timeline.top(), timeline.right(), timeline.bottom());

        // The panel at the right: its tabs and a form, or folded to an edge with the tabs standing on end
        EditorLayout.Rect side = at.side();
        if (sideFolded) {
            this.sideFold.clip(Integer.MIN_VALUE, Integer.MAX_VALUE);
            this.sideFold.place(font, side.left() + GAP, side.top() + GAP, ROW, ROW);
            int y = side.top() + HEADER + GAP;
            for (Gadget tab : this.edgeTabs) {
                int tall = ((EdgeTab) tab).length(font);
                tab.clip(Integer.MIN_VALUE, Integer.MAX_VALUE);
                // A tab there is no room for is left out rather than cut short
                tab.place(font, side.left() + GAP, y, ROW, y + tall <= side.bottom() - GAP ? tall : 0);
                y += tall + GAP;
            }
        } else {
            int tabRoom = side.width() - 2 * PAD - ROW - GAP - GAP * (this.tabs.size() - 1) - GAP;
            int tabWidth = tabRoom / Math.max(1, this.tabs.size());
            int x = side.left() + PAD;
            for (Gadget tab : this.tabs) {
                tab.clip(Integer.MIN_VALUE, Integer.MAX_VALUE);
                tab.place(font, x, side.top() + GAP, tabWidth, ROW);
                x += tabWidth + GAP;
            }
            this.sideFold.clip(Integer.MIN_VALUE, Integer.MAX_VALUE);
            this.sideFold.place(font, side.right() - PAD - ROW, side.top() + GAP, ROW, ROW);
            Form form = this.sideForm();
            this.refresh(form);
            form.place(font, side.left() + 1, side.top() + HEADER, side.width() - 2, Math.max(0, side.height() - HEADER - 1));
        }

        // The two cards over the view
        this.arrangeCard(font, at.viewer(), this.viewerFold, this.viewerForm, EditorState.viewerFolded);
        this.arrangeCard(font, at.players(), this.playersFold, this.playersForm, EditorState.playersFolded);
    }

    private void arrangeCard(Font font, EditorLayout.Rect card, Gadget.Push fold, Form form, boolean folded) {
        fold.clip(Integer.MIN_VALUE, Integer.MAX_VALUE);
        if (card.empty()) {
            fold.place(font, -100, -100, 0, 0);
            form.place(font, 0, 0, 0, 0);
            return;
        }
        fold.place(font, card.right() - GAP - ROW, card.top() + GAP, ROW, ROW);
        if (folded) {
            form.place(font, card.left() + 1, card.top() + HEADER, 0, 0);
        } else {
            form.place(font, card.left() + 1, card.top() + HEADER, card.width() - 2, Math.max(0, card.height() - HEADER - 1));
        }
    }

    /** Sets the bar's groups out in one row, and finds the room left in the middle for notices */
    private void arrangeBar(Font font, EditorLayout.Rect bar) {
        int[][] left = widths(font, this.topLeft);
        int[][] right = widths(font, this.topRight);
        int[] xs = EditorLayout.row(bar.left() + PAD, bar.right() - PAD, GROUP_GAP, left, right);
        this.dividers.clear();
        int index = 0;
        int leftEnd = bar.left() + PAD;
        int rightStart = bar.right() - PAD;
        boolean firstRight = true;
        for (int side = 0; side < 2; side++) {
            List<List<Gadget>> groups = side == 0 ? this.topLeft : this.topRight;
            for (int g = 0; g < groups.size(); g++) {
                List<Gadget> group = groups.get(g);
                int groupLeft = Integer.MAX_VALUE;
                for (Gadget gadget : group) {
                    int x = xs[index++];
                    gadget.clip(Integer.MIN_VALUE, Integer.MAX_VALUE);
                    int wide = gadget.wantedWidth(font);
                    // One that has no room is given none, rather than lie over its neighbour
                    gadget.place(font, Math.max(0, x), bar.top() + GAP, x < 0 ? 0 : wide, x < 0 ? 0 : ROW);
                    if (x >= 0) {
                        groupLeft = Math.min(groupLeft, x);
                        if (side == 0) {
                            leftEnd = Math.max(leftEnd, x + wide);
                        } else if (firstRight) {
                            rightStart = x;
                            firstRight = false;
                        }
                    }
                }
                if ((side == 1 || g > 0) && groupLeft != Integer.MAX_VALUE && groupLeft > bar.left() + PAD) {
                    this.dividers.add(groupLeft - (GROUP_GAP + 1) / 2);
                }
            }
        }
        this.barFreeLeft = leftEnd + GROUP_GAP;
        this.barFreeRight = rightStart - GROUP_GAP;
    }

    private static int[][] widths(Font font, List<List<Gadget>> groups) {
        int[][] widths = new int[groups.size()][];
        for (int g = 0; g < groups.size(); g++) {
            List<Gadget> group = groups.get(g);
            widths[g] = new int[group.size()];
            for (int i = 0; i < group.size(); i++) {
                widths[g][i] = group.get(i).wantedWidth(font);
            }
        }
        return widths;
    }

    private boolean sideOpen() {
        return !EditorState.sideFolded || EditorState.pick != null;
    }

    private void drawPanels(GuiGraphics graphics, int mouseX, int mouseY) {
        Font font = this.font;
        EditorLayout.Layout at = this.layout;
        EditorLayout.Rect bar = at.bar();
        Kit.panel(graphics, bar.left(), bar.top(), bar.right(), bar.bottom());
        for (int x : this.dividers) {
            Kit.divider(graphics, x, bar.top(), bar.bottom());
        }
        for (Gadget gadget : this.top) {
            if (gadget.height > 0) {
                gadget.draw(graphics, font, mouseX, mouseY);
            }
        }

        this.drawCard(graphics, font, at.viewer(), Kit.t("viewer.title"), this.viewerFold, this.viewerForm, EditorState.viewerFolded, mouseX, mouseY);
        this.drawCard(graphics, font, at.players(), Kit.t("players.title"), this.playersFold, this.playersForm, EditorState.playersFolded, mouseX, mouseY);

        EditorLayout.Rect side = at.side();
        Kit.panel(graphics, side.left(), side.top(), side.right(), side.bottom());
        this.sideFold.draw(graphics, font, mouseX, mouseY);
        if (this.sideOpen()) {
            for (Gadget tab : this.tabs) {
                tab.draw(graphics, font, mouseX, mouseY);
            }
            Kit.rule(graphics, side.left() + PAD, side.top() + HEADER - 1, side.right() - PAD);
            this.sideForm().draw(graphics, font, mouseX, mouseY);
        } else {
            for (Gadget tab : this.edgeTabs) {
                if (tab.height > 0) {
                    tab.draw(graphics, font, mouseX, mouseY);
                }
            }
        }

        this.timeline.draw(graphics, font, mouseX, mouseY);
    }

    private void drawCard(GuiGraphics graphics, Font font, EditorLayout.Rect card, Component title, Gadget.Push fold, Form form, boolean folded,
                          int mouseX, int mouseY) {
        if (card.empty()) {
            return;
        }
        Kit.card(graphics, card.left(), card.top(), card.right(), card.bottom());
        Kit.text(graphics, font, Kit.clip(font, title.getString(), card.width() - 2 * PAD - ROW - GAP), card.left() + PAD, card.top() + 5, Controls.BRASS_BRIGHT);
        fold.draw(graphics, font, mouseX, mouseY);
        if (!folded) {
            Kit.rule(graphics, card.left() + PAD, card.top() + HEADER - 1, card.right() - PAD);
            form.draw(graphics, font, mouseX, mouseY);
        }
    }

    /**
     * What a click in the view is wanted for, or the latest notice: in the middle of the bar when it fits there, so
     * that it covers none of the view, else on a strip of its own just under the bar.
     */
    private void drawNotice(GuiGraphics graphics) {
        EditorState.Pick pick = EditorState.pick;
        Component words = pick != null ? Kit.t("pick.banner", pick.prompt()) : EditorState.notice();
        if (words == null) {
            return;
        }
        String full = words.getString();
        int accent = pick != null ? Controls.BRASS_BRIGHT : Controls.BRASS;
        int room = this.barFreeRight - this.barFreeLeft;
        if (this.font.width(full) + 12 <= room) {
            int width = this.font.width(full) + 12;
            int x = this.barFreeLeft + (room - width) / 2;
            int y = this.layout.bar().top() + GAP;
            graphics.fill(x, y, x + width, y + ROW, Controls.FIELD);
            graphics.fill(x, y + ROW - 1, x + width, y + ROW, accent);
            Kit.text(graphics, this.font, full, x + 6, y + 3, Controls.PARCHMENT);
            return;
        }
        EditorLayout.Rect view = this.layout.view();
        int left = this.layout.frameClear().left() + PAD;
        int right = view.right() - PAD;
        if (right - left < 60) {
            left = view.left() + PAD;
        }
        String shown = Kit.clip(this.font, full, right - left - 12);
        int width = this.font.width(shown) + 12;
        int x = left + (right - left - width) / 2;
        int y = view.top() + PAD;
        graphics.fill(x, y, x + width, y + ROW, Controls.WALNUT_GLASS);
        graphics.fill(x, y + ROW - 1, x + width, y + ROW, accent);
        Kit.text(graphics, this.font, shown, x + 6, y + 3, Controls.PARCHMENT);
    }

    private @Nullable Component tipAt(double mouseX, double mouseY) {
        Component barWords = InputRouter.tipAmong(this.top, mouseX, mouseY);
        if (barWords != null) {
            return barWords;
        }
        EditorLayout.Layout at = this.layout;
        Component words = null;
        if (at.side().contains(mouseX, mouseY)) {
            words = this.sideFold.tipAt(mouseX, mouseY);
            if (words == null) {
                words = InputRouter.tipAmong(this.sideOpen() ? this.tabs : this.edgeTabs, mouseX, mouseY);
            }
            if (words == null && this.sideOpen()) {
                words = this.sideForm().tipAt(mouseX, mouseY);
            }
            return words;
        }
        if (at.viewer().contains(mouseX, mouseY)) {
            words = this.viewerFold.tipAt(mouseX, mouseY);
            return words != null || EditorState.viewerFolded ? words : this.viewerForm.tipAt(mouseX, mouseY);
        }
        if (at.players().contains(mouseX, mouseY)) {
            words = this.playersFold.tipAt(mouseX, mouseY);
            return words != null || EditorState.playersFolded ? words : this.playersForm.tipAt(mouseX, mouseY);
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
        EditorLayout.Layout at = this.layout;
        return at.bar().contains(mouseX, mouseY) || this.timeline.over(mouseX, mouseY) || at.side().contains(mouseX, mouseY)
                || at.viewer().contains(mouseX, mouseY) || at.players().contains(mouseX, mouseY);
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
        if (InputRouter.beforeClick(mouseX, mouseY)) {
            return;
        }
        if (this.overPanels(mouseX, mouseY)) {
            this.input.hold(this.pressPanels(mouseX, mouseY, button));
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
        EditorLayout.Layout at = this.layout;
        if (at.bar().contains(mouseX, mouseY)) {
            return InputRouter.pressAmong(this.top, mouseX, mouseY, button);
        }
        if (at.side().contains(mouseX, mouseY)) {
            Gadget hit = this.sideFold.press(mouseX, mouseY, button);
            if (hit == null) {
                hit = InputRouter.pressAmong(this.sideOpen() ? this.tabs : this.edgeTabs, mouseX, mouseY, button);
            }
            if (hit != null) {
                return hit;
            }
            return this.sideOpen() ? this.sideForm().press(mouseX, mouseY, button) : null;
        }
        if (at.viewer().contains(mouseX, mouseY)) {
            Gadget hit = this.viewerFold.press(mouseX, mouseY, button);
            return hit != null || EditorState.viewerFolded ? hit : this.viewerForm.press(mouseX, mouseY, button);
        }
        if (at.players().contains(mouseX, mouseY)) {
            Gadget hit = this.playersFold.press(mouseX, mouseY, button);
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
        Gadget was = this.input.letGo();
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
                this.input.drag(mouseX, mouseY);
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
            EditorLayout.Layout at = this.layout;
            if (at.side().contains(mouseX, mouseY)) {
                if (this.sideOpen()) {
                    this.sideForm().wheel(mouseX, mouseY, scrollY);
                }
            } else if (at.viewer().contains(mouseX, mouseY)) {
                if (!EditorState.viewerFolded) {
                    this.viewerForm.wheel(mouseX, mouseY, scrollY);
                }
            } else if (at.players().contains(mouseX, mouseY)) {
                if (!EditorState.playersFolded) {
                    this.playersForm.wheel(mouseX, mouseY, scrollY);
                }
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
        boolean back = InputRouter.back(modifiers);
        Entry typing = Entry.focused;
        if (typing != null) {
            // A real box, and not the stand-in that hands the keys to an open menu
            boolean box = Entry.typing();
            boolean handedOver = InputRouter.typeKey(typing, keyCode, scanCode, modifiers);
            if (box && handedOver && Menu.open == null && !this.hidden()) {
                // Tab hands over what was typed and moves on, as it does in every other Dailies screen
                this.moveFocus(typing, back);
            }
            return;
        }
        if (Gadget.focus != null && !this.flying && !this.hidden()) {
            if (!this.order().contains(Gadget.focus)) {
                // Its panel was folded or built afresh
                Gadget.focus = null;
            } else if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                // The ring goes first; the next Escape means what it always meant
                Gadget.focus = null;
                return;
            } else if (keyCode != GLFW.GLFW_KEY_TAB && Gadget.focus.key(keyCode, modifiers)) {
                return;
            }
        }
        if (keyCode == GLFW.GLFW_KEY_TAB && !this.flying && !this.hidden() && Menu.open == null && !Screen.hasControlDown()) {
            this.moveFocus(Gadget.focus, back);
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
                // Dailies' own menu, as Escape gives anywhere a recording is watched; it brings the editor back
                this.minecraft.setScreen(new PlaybackMenu(true));
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
            // The key that opens the editor does nothing while it is open; the editor's Close button and the menu
            // Escape brings up are the ways out
            return;
        } else {
            Keys.playbackKeyPressed(keyCode, scanCode);
        }
    }

    // ------------------------------------------------------------------ Moving the keys from control to control

    /** The controls in the order the Tab key visits them: along the bar, down the cards, the panel, then the timeline */
    private List<Gadget> order() {
        List<Gadget> order = new ArrayList<>();
        for (Gadget gadget : this.top) {
            if (gadget.height > 0) {
                gadget.collect(order);
            }
        }
        EditorLayout.Layout at = this.layout;
        if (!at.viewer().empty()) {
            this.viewerFold.collect(order);
            if (!EditorState.viewerFolded) {
                this.viewerForm.collect(order);
            }
        }
        if (!at.players().empty()) {
            this.playersFold.collect(order);
            if (!EditorState.playersFolded) {
                this.playersForm.collect(order);
            }
        }
        if (this.sideOpen()) {
            this.tabs.forEach(tab -> tab.collect(order));
            this.sideFold.collect(order);
            this.sideForm().collect(order);
        } else {
            this.sideFold.collect(order);
            this.edgeTabs.forEach(tab -> tab.collect(order));
        }
        this.timeline.collect(order);
        return order;
    }

    private void moveFocus(@Nullable Gadget from, boolean back) {
        // A control further down a panel than it shows is scrolled into sight
        InputRouter.moveFocus(this.order(), from, back, List.of(this.viewerForm, this.playersForm, this.sideForm()));
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
        // While the keys act on a control (after Tab) the space bar presses it, and must not also lift the camera
        boolean keys = this.flying || (Entry.focused == null && Menu.open == null && Gadget.focus == null && !Screen.hasControlDown() && !Screen.hasAltDown()
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

    // ------------------------------------------------------------------ A tab standing on end

    /**
     * One of the panel's tabs while the panel is folded to its edge: a narrow button with its name written down it.
     * A click opens the panel on that tab.
     */
    private static final class EdgeTab extends Gadget {
        private final Component label;
        private final Runnable action;
        private final java.util.function.BooleanSupplier lit;

        EdgeTab(Component label, Runnable action, java.util.function.BooleanSupplier lit) {
            this.label = label;
            this.action = action;
            this.lit = lit;
        }

        /** How tall it is: its name and a little room at each end */
        int length(Font font) {
            return font.width(this.label) + 10;
        }

        @Override
        boolean focusable() {
            return true;
        }

        @Override
        boolean key(int keyCode, int modifiers) {
            if (!activates(keyCode)) {
                return false;
            }
            Kit.click();
            EditorState.guard(this.action);
            return true;
        }

        @Override
        void draw(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
            boolean hovered = this.over(mouseX, mouseY);
            Kit.face(graphics, this.x, this.y, this.width, this.height, hovered, this.lit.getAsBoolean(), true, hovered && Kit.mouseDown(), focus == this);
            // Turned a quarter of the way round, so the name reads from the top down
            graphics.pose().pushPose();
            try {
                graphics.pose().translate(this.x + this.width / 2.0F + 4.0F, this.y + this.height / 2.0F, 0.0F);
                graphics.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(90.0F));
                String words = this.label.getString();
                graphics.drawString(font, words, -font.width(words) / 2, 0, Controls.PARCHMENT, false);
            } finally {
                graphics.pose().popPose();
            }
        }

        @Override
        @Nullable Gadget press(double mouseX, double mouseY, int button) {
            if (button != 0 || !this.over(mouseX, mouseY)) {
                return null;
            }
            Kit.click();
            EditorState.guard(this.action);
            return this;
        }
    }

}
