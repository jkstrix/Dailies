package comedymonkey.dailies.ui;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.export.Export;
import comedymonkey.dailies.export.PictureGui;
import comedymonkey.dailies.export.WindowPicture;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.Locale;

/**
 * What stands in place of the editor while a video is exported (specification EXP-6): a small plate at the foot of
 * the window with the frames done and to do, the speed, the time left, a bar, and a way to cancel.
 * <p>
 * Nothing of it may end up in the video. The export takes each picture after the game has drawn everything, screens
 * included, so as a screen this draws nothing at all. Where the game would then put the frame in the window, the
 * window is given only the plate instead ({@link #showInWindow}, from the export preview mixin), sized in the
 * window's own pixels. The screen is there to take the Escape key and the click on Cancel.
 */
public final class ExportProgress extends Screen {

    private static final int PLATE_WIDTH = 300;
    private static final int PLATE_HEIGHT = 82;

    /** The dark behind the panel, which fills the rest of the window */
    private static final int BACKGROUND = 0xFF17100A;

    private static boolean failureLogged = false;
    // Where Cancel was last painted, in pixels of the real window from its top left corner
    private static double cancelLeft;
    private static double cancelTop;
    private static double cancelRight;
    private static double cancelBottom;
    private static boolean cancelHovered = false;

    ExportProgress() {
        super(Component.translatable("dailies.editor.progress.title"));
    }

    static void hook() {
        // Whoever started the export (the editor, the queue, a tool, the moments page), the window shows the progress
        // panel while it runs, and is cleared again afterwards
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre event) -> standIn());
    }

    private static boolean ours = false;

    /** Puts the progress panel up while an export holds the clock with no screen of its own, and takes it down after */
    private static void standIn() {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            if (Export.holdsClock()) {
                if (minecraft.gui.screen() == null) {
                    minecraft.gui.setScreen(new ExportProgress());
                    ours = true;
                }
            } else if (ours) {
                ours = false;
                if (minecraft.gui.screen() instanceof ExportProgress) {
                    minecraft.gui.setScreen(null);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            if (!failureLogged) {
                failureLogged = true;
                Host.LOGGER.error("Dailies: the export progress display could not be put up", e);
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    /** Nothing: whatever a screen draws would be in the exported picture */
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_ESCAPE) {
            cancel();
        }
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && overCancel()) {
            cancel();
        }
        return true;
    }

    private static void cancel() {
        EditorState.guard(() -> {
            if (Export.active()) {
                Export.cancel();
            }
        });
    }

    /** Whether the real mouse is over the place Cancel was last painted at, both in pixels of the real window */
    private static boolean overCancel() {
        Minecraft minecraft = Minecraft.getInstance();
        Window window = minecraft.getWindow();
        Window.FramebufferSize pixels = window.queryFramebufferSize();
        // The mouse is reported in the desktop's units, which on a scaled display are not pixels
        double x = minecraft.mouseHandler.xpos() * pixels.width() / Math.max(1, window.getScreenWidth());
        double y = minecraft.mouseHandler.ypos() * pixels.height() / Math.max(1, window.getScreenHeight());
        return x >= cancelLeft && x < cancelRight && y >= cancelTop && y < cancelBottom;
    }

    /**
     * Shows the panel in the window in place of the frame, while a video is exported. Called where the game would put
     * the frame in the window, after the export has taken its picture: the panel is painted over the picture, measured
     * so that once the picture is scaled to fit the window a unit of the panel is the same number of the window's
     * pixels on any display, centred and whole at any export size and any window size. Returns whether the window was
     * given anything; if not, the caller hands it the picture as it is. Render thread
     */
    public static boolean showInWindow(GpuSurface surface, CommandEncoder encoder) {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            int[] window = WindowPicture.size(surface);
            RenderTarget main = minecraft.gameRenderer.mainRenderTarget();
            GpuTextureView picture = main.getColorTextureView();
            if (window == null || window[0] <= 0 || window[1] <= 0 || picture == null || minecraft.getWindow().isIconified()) {
                // Minimised, or nothing to draw on
                return false;
            }
            int wideInPixels = window[0];
            int highInPixels = window[1];
            int[] at = WindowPicture.fitted(main.width, main.height, wideInPixels, highInPixels);
            double fit = at[2] / (double) main.width;

            // One unit of the plate is this many pixels of the window, so that it reads the same on any display
            float unit = (float) Math.max(1.0, Math.min(3.0, Math.min(wideInPixels / 330.0, highInPixels / 120.0)));
            // ... and this many pixels of the picture, which the fit shrinks or grows on its way to the window
            float toPicture = (float) (unit / fit);
            float wide = at[2] / unit;
            float high = at[3] / unit;
            PictureGui.draw(graphics -> {
                graphics.pose().scale(toPicture, toPicture);
                cancelHovered = minecraft.gui.screen() instanceof ExportProgress && overCancel();
                plate(graphics, minecraft.font, (int) wide, (int) high, unit, at[0], at[1]);
            });
            WindowPicture.show(surface, encoder, picture, BACKGROUND & 0xFFFFFF);
            return true;
        } catch (RuntimeException | LinkageError e) {
            if (!failureLogged) {
                failureLogged = true;
                Host.LOGGER.error("Dailies: the export progress display could not be drawn", e);
            }
            return false;
        }
    }

    private static void plate(GuiGraphicsExtractor graphics, Font font, int wide, int high, float unit, int offsetX, int offsetY) {
        int width = Math.min(PLATE_WIDTH, Math.max(120, wide - 8));
        int left = (wide - width) / 2;
        // The picture being exported is not shown: nobody should have to sit through the video to get the video
        graphics.fill(0, 0, wide + 1, high + 1, BACKGROUND);
        int top = Math.max(4, (high - PLATE_HEIGHT) / 2);
        Kit.plate(graphics, left, top, left + width, top + PLATE_HEIGHT);

        long done = Export.frame();
        long total = Math.max(1, Export.totalFrames());
        double speed = Export.speed();
        int inner = width - 16;
        int x = left + 8;

        // The title with how far it has got, as every Dailies plate has its title: top left, a rule under it
        Kit.text(graphics, font, Kit.clip(font, Kit.t("progress.title").getString(), inner - 40), x, top + 8, Controls.BRASS_BRIGHT);
        String share = (int) Math.floor(100.0 * Math.min(total, done) / total) + "%";
        Kit.text(graphics, font, share, x + inner - font.width(share), top + 8, Controls.BRASS_BRIGHT);
        Kit.rule(graphics, x, top + 20, x + inner);

        // Every picture taken, the file is being finished: the parts joined, the sound joined to the picture
        boolean finishing = Export.finishing();
        String rate = finishing ? Kit.t("progress.finishing").getString()
            : speed > 0.0 ? Kit.t("progress.speed", String.format(Locale.ROOT, "%.1f", speed)).getString() : Kit.t("progress.starting").getString();
        int rateWidth = font.width(rate);
        Kit.text(graphics, font, Kit.clip(font, Kit.t("progress.frames", done, total).getString(), inner - rateWidth - 8), x, top + 27, Controls.PARCHMENT);
        Kit.text(graphics, font, rate, x + inner - rateWidth, top + 27, Controls.PARCHMENT_DIM);

        int barTop = top + 40;
        Kit.field(graphics, x, barTop, inner, 10, Controls.BRASS);
        int filled = (int) ((inner - 2) * Math.min(total, done) / total);
        if (filled > 0) {
            graphics.fill(x + 1, barTop + 1, x + 1 + filled, barTop + 9, Controls.BRASS);
            graphics.fill(x + 1, barTop + 1, x + 1 + filled, barTop + 2, Controls.BRASS_BRIGHT);
        }

        int buttonWidth = Math.max(70, font.width(Kit.t("progress.cancel")) + 12);
        int buttonLeft = left + width - 8 - buttonWidth;
        int buttonTop = top + PLATE_HEIGHT - 8 - 16;
        String left1 = !finishing && speed > 0.0 ? Kit.t("progress.left", Kit.seconds((total - done) / speed)).getString() : "";

        Kit.text(graphics, font, Kit.clip(font, left1, inner - buttonWidth - 8), x, buttonTop + 4, Controls.PARCHMENT);
        Kit.face(graphics, buttonLeft, buttonTop, buttonWidth, 16, cancelHovered, false, true);
        Kit.centred(graphics, font, Kit.t("progress.cancel").getString(), buttonLeft, buttonTop + 4, buttonWidth, Controls.PARCHMENT);
        cancelLeft = offsetX + buttonLeft * (double) unit;
        cancelTop = offsetY + buttonTop * (double) unit;
        cancelRight = offsetX + (buttonLeft + buttonWidth) * (double) unit;
        cancelBottom = offsetY + (buttonTop + 16) * (double) unit;
    }

}
