package comedymonkey.dailies.ui;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.export.Export;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/**
 * What stands in place of the editor while a video is exported (specification EXP-6): a small plate at the foot of
 * the window with the frames done and to do, the speed, the time left, a bar, and a way to cancel.
 * <p>
 * Nothing of it may end up in the video. The export takes each picture after the game has drawn everything, screens
 * included, so as a screen this draws nothing at all; the plate is painted afterwards, once the picture has been
 * taken and before it is shown in the window. It is there to take the Escape key and the click on Cancel.
 */
public final class ExportProgress extends Screen {

    private static final int PLATE_WIDTH = 300;
    private static final int PLATE_HEIGHT = 82;

    private static boolean failureLogged = false;
    // Where Cancel was last painted, in pixels of the picture, and how the picture sits in the window
    private static double cancelLeft;
    private static double cancelTop;
    private static double cancelRight;
    private static double cancelBottom;
    private static double windowPerPicture = 1.0;
    private static int pictureHeight = 0;
    private static boolean cancelHovered = false;

    ExportProgress() {
        super(Component.translatable("dailies.editor.progress.title"));
    }

    static void hook() {
        // After the export has taken its picture of the frame
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (RenderFrameEvent.Post event) -> paint());
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

    /** Whether the real mouse is over the place Cancel was last painted at */
    private static boolean overCancel() {
        Minecraft minecraft = Minecraft.getInstance();
        long window = minecraft.getWindow().getWindow();
        int[] width = new int[1];
        int[] height = new int[1];
        GLFW.glfwGetFramebufferSize(window, width, height);
        int screenWidth = Math.max(1, minecraft.getWindow().getScreenWidth());
        int screenHeight = Math.max(1, minecraft.getWindow().getScreenHeight());
        // The mouse in pixels of the real window, measured from its top left corner
        double x = minecraft.mouseHandler.xpos() * width[0] / screenWidth;
        double y = minecraft.mouseHandler.ypos() * height[0] / screenHeight;
        // The picture is shown scaled to fit, in the window's bottom left corner
        double shownHeight = pictureHeight * windowPerPicture;
        double pictureX = x / windowPerPicture;
        double pictureY = (y - (height[0] - shownHeight)) / windowPerPicture;
        return pictureX >= cancelLeft && pictureX < cancelRight && pictureY >= cancelTop && pictureY < cancelBottom;
    }

    /** Paints the plate over the frame that has just been captured, so that it is seen and not exported */
    private static void paint() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!Export.holdsClock() || !(minecraft.screen instanceof ExportProgress)) {
            return;
        }
        try {
            RenderTarget target = minecraft.getMainRenderTarget();
            if (target.width <= 0 || target.height <= 0) {
                return;
            }
            int[] shown = Export.previewSize(target.width, target.height);
            windowPerPicture = Math.max(1.0e-6, (double) shown[0] / target.width);
            pictureHeight = target.height;
            // One unit of the plate is this many pixels of the window, so that it reads the same whatever is exported
            double unitInWindow = Math.max(1.0, Math.min(3.0, Math.min(shown[0] / 330.0, shown[1] / 120.0)));
            float unit = (float) (unitInWindow / windowPerPicture);
            float wide = target.width / unit;
            float high = target.height / unit;

            target.bindWrite(true);
            RenderSystem.clear(256, Minecraft.ON_OSX);
            RenderSystem.backupProjectionMatrix();
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0.0F, wide, high, 0.0F, 1000.0F, 3000.0F), VertexSorting.ORTHOGRAPHIC_Z);
            Matrix4fStack stack = RenderSystem.getModelViewStack();
            stack.pushMatrix();
            stack.translation(0.0F, 0.0F, -2000.0F);
            RenderSystem.applyModelViewMatrix();
            try {
                GuiGraphics graphics = new GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource());
                cancelHovered = overCancel();
                plate(graphics, minecraft.font, (int) wide, (int) high, unit);
                graphics.flush();
            } finally {
                stack.popMatrix();
                RenderSystem.applyModelViewMatrix();
                RenderSystem.restoreProjectionMatrix();
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
        graphics.fill(0, 0, wide + 1, high + 1, 0xFF17100A);
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

        String rate = speed > 0.0 ? Kit.t("progress.speed", String.format(Locale.ROOT, "%.1f", speed)).getString() : Kit.t("progress.starting").getString();
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
        String left1 = speed > 0.0 ? Kit.t("progress.left", Kit.seconds((total - done) / speed)).getString() : "";
        Kit.text(graphics, font, Kit.clip(font, left1, inner - buttonWidth - 8), x, buttonTop + 4, Controls.PARCHMENT);
        Kit.face(graphics, buttonLeft, buttonTop, buttonWidth, 16, cancelHovered, false, true);
        Kit.centred(graphics, font, Kit.t("progress.cancel").getString(), buttonLeft, buttonTop + 4, buttonWidth, Controls.PARCHMENT);
        cancelLeft = buttonLeft * (double) unit;
        cancelTop = buttonTop * (double) unit;
        cancelRight = (buttonLeft + buttonWidth) * (double) unit;
        cancelBottom = (buttonTop + 16) * (double) unit;
    }

}
