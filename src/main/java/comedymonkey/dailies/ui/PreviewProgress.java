package comedymonkey.dailies.ui;

import com.mojang.blaze3d.platform.InputConstants;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.editor.StretchPreview;
import comedymonkey.dailies.editor.StretchRun;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * What stands in place of the editor while its "Preview path" button plays a Smart Camera stretch at up to twenty
 * times its speed (specification EDIT-8): a small plate with how far it has got, how fast it goes, a bar, and a way to
 * cancel, in the colours of the export's progress plate. The world is not drawn behind it (see the playback mixin
 * that asks {@link #drawWorld}): that is where the time for the extra ticks comes from. Once the playhead is back
 * where it was the editor returns, with the fresh path in the view.
 */
public final class PreviewProgress extends Screen {

    private static final int PLATE_WIDTH = 280;
    private static final int PLATE_HEIGHT = 82;
    /** The dark behind the plate, the same as behind the export's */
    private static final int BACKGROUND = 0xFF17100A;

    private static boolean failureLogged = false;

    // Where Cancel was last drawn
    private int cancelLeft;
    private int cancelTop;
    private int cancelWidth;
    private int cancelHeight;

    PreviewProgress() {
        super(Kit.t("preview.title"));
    }

    private static volatile long framesWithoutWorld = 0;

    /**
     * Whether the world is drawn this frame: not while a run is under way and this plate is what is on screen. Asked
     * once a frame by the playback mixin, with what the game meant to do. Render thread
     */
    public static boolean drawWorld(boolean wanted) {
        if (wanted && StretchPreview.running() && Minecraft.getInstance().gui.screen() instanceof PreviewProgress) {
            framesWithoutWorld++;
            return false;
        }
        return wanted;
    }

    /** How many frames have left the world out for a run, since the game started. For the scripted test */
    public static long framesWithoutWorld() {
        return framesWithoutWorld;
    }

    // ------------------------------------------------------------------ The editor's button

    /** The stretch the button would play now, or null when there is none */
    static StretchRun.@Nullable Stretch stretchNow() {
        return StretchPreview.stretchNow(EditorState.selection);
    }

    /** Whether the scene has a Smart Camera at all: the button is only there when it has */
    static boolean wanted() {
        if (!comedymonkey.dailies.editor.EditorSession.open()) {
            return false;
        }
        for (comedymonkey.dailies.edit.Track track : EditorState.scene().tracks()) {
            if (track.kind() == comedymonkey.dailies.edit.KeyframeKind.SMART_CAMERA && track.enabled() && !track.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** What the button does, in words: which stretch it would play, or how to give it one */
    static Component tip() {
        StretchRun.Stretch stretch = stretchNow();
        return stretch == null ? Kit.t("transport.preview.none") : Kit.t("transport.preview.tip", Controls.time(stretch.first()), Controls.time(stretch.last()));
    }

    /** The button: plays the stretch, with this plate in the editor's place until the playhead is back */
    static void begin() {
        StretchPreview.start(EditorState.selection);
        Entry.blur();
        Menu.close();
        Gadget.focus = null;
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

    @Override
    public void renderBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == InputConstants.KEY_ESCAPE) {
            cancel();
        }
        // Every other key is kept from the game, whose keys would otherwise reach the paused recording behind
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && this.overCancel(mouseX, mouseY)) {
            Kit.click();
            cancel();
        }
        return true;
    }

    private static void cancel() {
        EditorState.guard(StretchPreview::cancel);
    }

    private boolean overCancel(double mouseX, double mouseY) {
        return mouseX >= this.cancelLeft && mouseX < this.cancelLeft + this.cancelWidth && mouseY >= this.cancelTop && mouseY < this.cancelTop + this.cancelHeight;
    }

    @Override
    public void tick() {
        // The recording was closed behind the plate
        if (!Playback.active() && this.minecraft != null && this.minecraft.gui.screen() == this) {
            this.minecraft.gui.setScreen(null);
        }
    }

    // ------------------------------------------------------------------ Drawing

    @Override
    public void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        try {
            graphics.fill(0, 0, this.width, this.height, BACKGROUND);
            StretchRun run = StretchPreview.current();
            if (run == null) {
                // Over, and the editor is on its way back
                return;
            }
            this.plate(graphics, run, mouseX, mouseY);
        } catch (RuntimeException | LinkageError e) {
            if (!failureLogged) {
                failureLogged = true;
                Host.LOGGER.error("Dailies: the path preview's progress could not be drawn", e);
            }
        }
    }

    private void plate(GuiGraphicsExtractor graphics, StretchRun run, int mouseX, int mouseY) {
        int width = Math.min(PLATE_WIDTH, Math.max(140, this.width - 16));
        int left = (this.width - width) / 2;
        int top = Math.max(4, (this.height - PLATE_HEIGHT) / 2);
        Kit.plate(graphics, left, top, left + width, top + PLATE_HEIGHT);
        int inner = width - 16;
        int x = left + 8;
        StretchRun.Stretch stretch = run.stretch();
        double share = run.share();

        // The title with how far it has got, as every Dailies plate has its title: top left, a rule under it
        String percent = (int) Math.floor(100.0 * share) + "%";
        Kit.text(graphics, this.font, Kit.clip(this.font, Kit.t("preview.title").getString(), inner - this.font.width(percent) - 8), x, top + 8, Controls.BRASS_BRIGHT);
        Kit.text(graphics, this.font, percent, x + inner - this.font.width(percent), top + 8, Controls.BRASS_BRIGHT);
        Kit.rule(graphics, x, top + 20, x + inner);

        // Which stretch, and how fast it is going, or where to
        double speed = run.speed();
        String state = switch (run.phase()) {
            case GOING -> Kit.t("preview.going").getString();
            case RETURNING, DONE -> Kit.t("preview.back").getString();
            case RUNNING -> speed > 0.0 ? Kit.t("preview.speed", String.format(Locale.ROOT, "%.1f", speed)).getString() : Kit.t("preview.starting").getString();
        };
        int stateWidth = this.font.width(state);
        String span = Kit.t("preview.span", Controls.time(stretch.first()), Controls.time(stretch.last())).getString();
        Kit.text(graphics, this.font, Kit.clip(this.font, span, inner - stateWidth - 8), x, top + 27, Controls.PARCHMENT);
        Kit.text(graphics, this.font, state, x + inner - stateWidth, top + 27, Controls.PARCHMENT_DIM);

        int barTop = top + 40;
        Kit.field(graphics, x, barTop, inner, 10, Controls.BRASS);
        int filled = (int) Math.round((inner - 2) * share);
        if (filled > 0) {
            graphics.fill(x + 1, barTop + 1, x + 1 + filled, barTop + 9, Controls.BRASS);
            graphics.fill(x + 1, barTop + 1, x + 1 + filled, barTop + 2, Controls.BRASS_BRIGHT);
        }

        String cancelWords = Kit.t("preview.cancel").getString();
        this.cancelWidth = Math.max(70, this.font.width(cancelWords) + 12);
        this.cancelHeight = 16;
        this.cancelLeft = left + width - 8 - this.cancelWidth;
        this.cancelTop = top + PLATE_HEIGHT - 8 - this.cancelHeight;
        String left1 = "";
        if (run.phase() == StretchRun.Phase.RUNNING && speed > 0.0) {
            double ticksLeft = Math.max(0.0, (1.0 - share) * stretch.length());
            left1 = Kit.t("preview.left", Kit.seconds(ticksLeft / (speed * 20.0))).getString();
        }
        Kit.text(graphics, this.font, Kit.clip(this.font, left1, inner - this.cancelWidth - 8), x, this.cancelTop + 4, Controls.PARCHMENT);
        boolean usable = run.phase() == StretchRun.Phase.GOING || run.phase() == StretchRun.Phase.RUNNING;
        boolean hovered = this.overCancel(mouseX, mouseY);
        Kit.face(graphics, this.cancelLeft, this.cancelTop, this.cancelWidth, this.cancelHeight, hovered, false, usable);
        Kit.centred(graphics, this.font, cancelWords, this.cancelLeft, this.cancelTop + 4, this.cancelWidth, usable ? Controls.PARCHMENT : Controls.PARCHMENT_FAINT);
    }

}
