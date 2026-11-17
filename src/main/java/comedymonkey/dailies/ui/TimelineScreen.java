package comedymonkey.dailies.ui;

import comedymonkey.dailies.edit.EditHistory;
import comedymonkey.dailies.editor.EditorSession;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.Objects;

/**
 * The timeline with the mouse free (specification chapter IV): click or drag along the gauge to jump, with buttons
 * for play and pause, one tick forward, slower and faster, "follow the camera track" (EDIT-5), undo and redo.
 * The recording keeps playing behind it: the screen neither pauses nor dims nor blurs the world.
 */
public final class TimelineScreen extends Screen {

    private static final int GAUGE_HEIGHT = 10;
    private static final int BUTTONS = 8;

    private int panelTop;
    private int gaugeLeft;
    private int gaugeTop;
    private int gaugeWidth;

    private Button play;
    private Button camera;
    private Button undo;
    private Button redo;
    private @Nullable String undoShown = "";
    private @Nullable String redoShown = "";

    private boolean dragging = false;
    /** The tick the mouse last asked for, waiting for the jump before it to finish; -1 for none */
    private int wanted = -1;

    private boolean closedByViewer = false;

    public TimelineScreen() {
        this(false);
    }

    private TimelineScreen(boolean dragging) {
        super(Component.translatable("dailies.ui.timeline.title"));
        this.dragging = dragging;
    }

    @Override
    protected void init() {
        int columns = this.width >= 520 ? BUTTONS : BUTTONS / 2;
        int rows = BUTTONS / columns;
        int buttonWidth = Math.min(110, (this.width - 16 - (columns - 1) * 4) / columns);
        int rowWidth = columns * buttonWidth + (columns - 1) * 4;
        int rowLeft = (this.width - rowWidth) / 2;

        this.panelTop = this.height - (rows * 24 + 44);
        this.gaugeLeft = 8;
        this.gaugeWidth = this.width - 16;
        this.gaugeTop = this.panelTop + 22;
        int buttonsTop = this.panelTop + 40;

        this.play = Button.builder(Component.translatable("dailies.ui.timeline.play"), button -> Controls.togglePlay()).build();
        Button step = Button.builder(Component.translatable("dailies.ui.timeline.step"), button -> Controls.stepOneTick()).build();
        Button slower = Button.builder(Component.translatable("dailies.ui.timeline.slower"), button -> Controls.slower()).build();
        Button faster = Button.builder(Component.translatable("dailies.ui.timeline.faster"), button -> Controls.faster()).build();
        this.camera = Button.builder(Component.translatable("dailies.ui.timeline.camera_track"), button -> Controls.toggleFollowCamera())
                .tooltip(Tooltip.create(Component.translatable("dailies.ui.timeline.camera_help"))).build();
        this.undo = Button.builder(Component.translatable("dailies.ui.timeline.undo"), button -> Controls.undo()).build();
        this.redo = Button.builder(Component.translatable("dailies.ui.timeline.redo"), button -> Controls.redo()).build();
        Button close = Button.builder(Component.translatable("dailies.ui.timeline.close"), button -> this.onClose()).build();
        this.undoShown = "";
        this.redoShown = "";

        Button[] all = {this.play, step, slower, faster, this.camera, this.undo, this.redo, close};
        for (int i = 0; i < all.length; i++) {
            all[i].setX(rowLeft + (i % columns) * (buttonWidth + 4));
            all[i].setY(buttonsTop + (i / columns) * 24);
            all[i].setWidth(buttonWidth);
            this.addRenderableWidget(all[i]);
        }
        this.refresh();
    }

    /** Brings the buttons into line with what playback and the editor are doing now */
    private void refresh() {
        this.play.setMessage(Component.translatable(Playback.paused() ? "dailies.ui.timeline.play" : "dailies.ui.timeline.pause"));
        this.camera.setMessage(Component.translatable(EditorSession.followCamera() ? "dailies.ui.timeline.camera_track" : "dailies.ui.timeline.camera_free"));

        EditHistory history = EditorSession.open() ? EditorSession.history() : null;
        String undoNow = history == null ? null : history.undoDescription();
        String redoNow = history == null ? null : history.redoDescription();
        this.undo.active = undoNow != null;
        this.redo.active = redoNow != null;
        if (!Objects.equals(undoNow, this.undoShown)) {
            this.undoShown = undoNow;
            this.undo.setTooltip(undoNow == null ? null : Tooltip.create(Component.translatable("dailies.ui.timeline.undo_what", undoNow)));
        }
        if (!Objects.equals(redoNow, this.redoShown)) {
            this.redoShown = redoNow;
            this.redo.setTooltip(redoNow == null ? null : Tooltip.create(Component.translatable("dailies.ui.timeline.redo_what", redoNow)));
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** No blur and no dimming: only the panel the controls sit on */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, this.panelTop, this.width, this.height, Controls.WALNUT_GLASS);
        graphics.fill(0, this.panelTop, this.width, this.panelTop + 1, Controls.BRASS);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!Playback.loaded()) {
            return;
        }
        Controls.guarded(() -> {
            if (this.dragging && GLFW.glfwGetMouseButton(this.minecraft.getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
                this.dragging = false;
            }
            if (this.wanted >= 0 && !Playback.jumping()) {
                int tick = this.wanted;
                this.wanted = -1;
                Controls.jumpTo(tick);
            }
            this.refresh();
        });
        super.render(graphics, mouseX, mouseY, partialTick);
        Controls.guarded(() -> {
            Component nextUndo = this.undoShown == null ? null : Component.translatable("dailies.ui.timeline.undo_what", this.font.plainSubstrByWidth(this.undoShown, Math.max(20, this.width / 3)));
            PlaybackBar.drawStatus(graphics, this.font, this.gaugeLeft, this.panelTop + 5, this.gaugeWidth, nextUndo);
            PlaybackBar.drawGauge(graphics, this.gaugeLeft, this.gaugeTop, this.gaugeWidth, GAUGE_HEIGHT);
            if (this.dragging || this.overGauge(mouseX, mouseY)) {
                this.drawPointer(graphics, mouseX, mouseY);
            }
        });
    }

    /** A thin line where the mouse is on the gauge, and the time there, or the marker it is pointing at */
    private void drawPointer(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = Math.max(this.gaugeLeft, Math.min(this.gaugeLeft + this.gaugeWidth - 1, mouseX));
        graphics.fill(x, this.gaugeTop - 1, x + 1, this.gaugeTop + GAUGE_HEIGHT + 1, Controls.PARCHMENT);

        int tick = PlaybackBar.tickAt(mouseX, this.gaugeLeft, this.gaugeWidth);
        Component words = Component.translatable("dailies.ui.timeline.pointer", Controls.time(tick));
        RecordingManifest manifest = Playback.manifest();
        if (manifest != null && !this.dragging) {
            int total = Math.max(1, Playback.total());
            for (RecordingManifest.Marker marker : manifest.markers) {
                int flag = this.gaugeLeft + PlaybackBar.markerOffset(marker.tick, total, this.gaugeWidth);
                if (mouseX >= flag - 2 && mouseX <= flag + 4) {
                    words = marker.description == null || marker.description.isBlank()
                            ? Component.translatable("dailies.ui.timeline.marker", Controls.time(marker.tick))
                            : Component.translatable("dailies.ui.timeline.marker_described", marker.description, Controls.time(marker.tick));
                    break;
                }
            }
        }
        this.setTooltipForNextRenderPass(words);
    }

    private boolean overGauge(double mouseX, double mouseY) {
        return mouseY >= this.gaugeTop - 6 && mouseY <= this.gaugeTop + GAUGE_HEIGHT + 3 && mouseX >= this.gaugeLeft - 2 && mouseX <= this.gaugeLeft + this.gaugeWidth + 2;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && Playback.loaded() && this.overGauge(mouseX, mouseY)) {
            this.dragging = true;
            this.wanted = PlaybackBar.tickAt(mouseX, this.gaugeLeft, this.gaugeWidth);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.dragging && button == 0) {
            this.wanted = PlaybackBar.tickAt(mouseX, this.gaugeLeft, this.gaugeWidth);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            this.dragging = false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (Keys.isTimelineKey(keyCode, scanCode)) {
            this.onClose();
            return true;
        }
        if (Keys.playbackKeyPressed(keyCode, scanCode)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        this.closedByViewer = true;
        super.onClose();
    }

    /**
     * A jump backwards rebuilds the world, and the game puts its own "jumping" screen in this one's place.
     * The timeline comes back when the jump has landed, still dragging if the mouse was.
     */
    @Override
    public void removed() {
        if (this.closedByViewer || !Playback.loaded() || !Playback.jumping()) {
            return;
        }
        String recording = Playback.recordingId();
        boolean wasDragging = this.dragging;
        Minecraft game = Minecraft.getInstance();
        Playback.whenLoaded(() -> {
            if (Playback.loaded() && game.screen == null && Objects.equals(recording, Playback.recordingId())) {
                game.setScreen(new TimelineScreen(wasDragging));
            }
        });
    }

    @Override
    public void tick() {
        // The recording was closed, or failed, behind the screen
        if (!Playback.loaded() && this.minecraft != null && this.minecraft.screen == this) {
            this.onClose();
        }
    }

}
