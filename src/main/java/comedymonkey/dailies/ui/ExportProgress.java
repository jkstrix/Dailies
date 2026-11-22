package comedymonkey.dailies.ui;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.export.Export;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.Locale;

/**
 * What stands in place of the editor while a video is exported (specification EXP-6): a small plate at the foot of
 * the window with the frames done and to do, the speed, the time left, a bar, and a way to cancel.
 * <p>
 * Nothing of it may end up in the video. The export takes each picture after the game has drawn everything, screens
 * included, so as a screen this draws nothing at all. Where the game would then put the frame in the window, the
 * window is given only the plate instead ({@link #showInWindow()}, from the export preview mixin), drawn in the
 * window's own pixels. The screen is there to take the Escape key and the click on Cancel.
 */
public final class ExportProgress extends Screen {

    private static final int PLATE_WIDTH = 300;
    private static final int PLATE_HEIGHT = 82;

    /** The dark behind the panel, which fills the rest of the window */
    private static final int BACKGROUND = 0xFF17100A;
    private static final float BACKGROUND_RED = ((BACKGROUND >> 16) & 0xFF) / 255.0F;
    private static final float BACKGROUND_GREEN = ((BACKGROUND >> 8) & 0xFF) / 255.0F;
    private static final float BACKGROUND_BLUE = (BACKGROUND & 0xFF) / 255.0F;

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
                if (minecraft.screen == null) {
                    minecraft.setScreen(new ExportProgress());
                    ours = true;
                }
            } else if (ours) {
                ours = false;
                if (minecraft.screen instanceof ExportProgress) {
                    minecraft.setScreen(null);
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
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            cancel();
        }
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && overCancel()) {
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
        long window = minecraft.getWindow().getWindow();
        int[] pixelsWide = new int[1];
        int[] pixelsHigh = new int[1];
        GLFW.glfwGetFramebufferSize(window, pixelsWide, pixelsHigh);
        int[] screenWide = new int[1];
        int[] screenHigh = new int[1];
        GLFW.glfwGetWindowSize(window, screenWide, screenHigh);
        // The mouse is reported in the desktop's units, which on a scaled display are not pixels
        double x = minecraft.mouseHandler.xpos() * pixelsWide[0] / Math.max(1, screenWide[0]);
        double y = minecraft.mouseHandler.ypos() * pixelsHigh[0] / Math.max(1, screenHigh[0]);
        return x >= cancelLeft && x < cancelRight && y >= cancelTop && y < cancelBottom;
    }

    /**
     * Shows the panel in the window in place of the frame, while a video is exported. Called where the game would put
     * the frame on the screen, after the export has taken its picture, so it is drawn in the real window's own pixels
     * whatever size the video is: centred and whole at any export size and any window size. Render thread
     */
    public static void showInWindow() {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            long window = minecraft.getWindow().getWindow();
            int[] pixelsWide = new int[1];
            int[] pixelsHigh = new int[1];
            GLFW.glfwGetFramebufferSize(window, pixelsWide, pixelsHigh);
            int wideInPixels = pixelsWide[0];
            int highInPixels = pixelsHigh[0];
            if (wideInPixels <= 0 || highInPixels <= 0) {
                // Minimised: there is nothing to draw on
                return;
            }
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            RenderSystem.viewport(0, 0, wideInPixels, highInPixels);
            RenderSystem.clearColor(BACKGROUND_RED, BACKGROUND_GREEN, BACKGROUND_BLUE, 1.0F);
            RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
            // As the game's own blit to the window does: what is drawn must not make the window see-through anywhere
            GlStateManager._colorMask(true, true, true, false);

            // One unit of the plate is this many pixels of the window, so that it reads the same on any display
            float unit = (float) Math.max(1.0, Math.min(3.0, Math.min(wideInPixels / 330.0, highInPixels / 120.0)));
            float wide = wideInPixels / unit;
            float high = highInPixels / unit;

            RenderSystem.backupProjectionMatrix();
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0.0F, wide, high, 0.0F, 1000.0F, 3000.0F), VertexSorting.ORTHOGRAPHIC_Z);
            Matrix4fStack stack = RenderSystem.getModelViewStack();
            stack.pushMatrix();
            stack.translation(0.0F, 0.0F, -2000.0F);
            RenderSystem.applyModelViewMatrix();
            try {
                GuiGraphics graphics = new GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource());
                cancelHovered = minecraft.screen instanceof ExportProgress && overCancel();
                plate(graphics, minecraft.font, (int) wide, (int) high, unit);
                graphics.flush();
            } finally {
                stack.popMatrix();
                RenderSystem.applyModelViewMatrix();
                RenderSystem.restoreProjectionMatrix();
                GlStateManager._colorMask(true, true, true, true);
            }
        } catch (RuntimeException | LinkageError e) {
            if (!failureLogged) {
                failureLogged = true;
                Host.LOGGER.error("Dailies: the export progress display could not be drawn", e);
            }
        }
    }

    private static void plate(GuiGraphics graphics, Font font, int wide, int high, float unit) {
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
        cancelLeft = buttonLeft * (double) unit;
        cancelTop = buttonTop * (double) unit;
        cancelRight = (buttonLeft + buttonWidth) * (double) unit;
        cancelBottom = (buttonTop + 16) * (double) unit;
    }

}
