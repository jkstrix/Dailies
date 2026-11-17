package comedymonkey.dailies.looks;

import comedymonkey.dailies.edit.Looks;
import comedymonkey.dailies.export.Export;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The guides drawn over the view while framing a shot (specification chapter V, "The frame"): dimmed bars that leave
 * a frame of the chosen shape clear, rule-of-thirds lines and a centre cross. They are an aid for the person editing
 * and are never drawn into an exported picture.
 */
final class FrameGuides {

    private static final int DIM = 0xB0000000;
    private static final int EDGE = 0x60FFFFFF;
    private static final int LINE = 0x80FFFFFF;
    private static final int CROSS_ARM = 6;

    private FrameGuides() {
    }

    static void draw(GuiGraphics graphics, DeltaTracker delta) {
        Looks looks = LooksRenderer.current();
        if (looks == null || Export.active()) {
            return;
        }
        try {
            double ratio = looks.aspectRatio();
            if (ratio <= 0.0 && !looks.thirdsGuide && !looks.centreGuide) {
                return;
            }
            int width = graphics.guiWidth();
            int height = graphics.guiHeight();
            int left = 0;
            int top = 0;
            int right = width;
            int bottom = height;
            if (ratio > 0.0) {
                Window window = Minecraft.getInstance().getWindow();
                double windowRatio = window.getHeight() > 0 ? (double) window.getWidth() / window.getHeight() : (double) width / Math.max(1, height);
                if (ratio < windowRatio) {
                    // Narrower than the window: bars down the sides
                    int inner = (int) Math.round(width * ratio / windowRatio);
                    left = (width - inner) / 2;
                    right = left + inner;
                    if (left > 0 || right < width) {
                        graphics.fill(0, 0, left, height, DIM);
                        graphics.fill(right, 0, width, height, DIM);
                        graphics.fill(left - 1, 0, left, height, EDGE);
                        graphics.fill(right, 0, right + 1, height, EDGE);
                    }
                } else if (ratio > windowRatio) {
                    // Wider than the window: bars above and below
                    int inner = (int) Math.round(height * windowRatio / ratio);
                    top = (height - inner) / 2;
                    bottom = top + inner;
                    if (top > 0 || bottom < height) {
                        graphics.fill(0, 0, width, top, DIM);
                        graphics.fill(0, bottom, width, height, DIM);
                        graphics.fill(0, top - 1, width, top, EDGE);
                        graphics.fill(0, bottom, width, bottom + 1, EDGE);
                    }
                }
            }
            int frameWidth = right - left;
            int frameHeight = bottom - top;
            if (looks.thirdsGuide) {
                for (int third = 1; third <= 2; third++) {
                    int x = left + frameWidth * third / 3;
                    int y = top + frameHeight * third / 3;
                    graphics.fill(x, top, x + 1, bottom, LINE);
                    graphics.fill(left, y, right, y + 1, LINE);
                }
            }
            if (looks.centreGuide) {
                int x = left + frameWidth / 2;
                int y = top + frameHeight / 2;
                graphics.fill(x - CROSS_ARM, y, x + CROSS_ARM + 1, y + 1, LINE);
                graphics.fill(x, y - CROSS_ARM, x + 1, y + CROSS_ARM + 1, LINE);
            }
        } catch (Throwable t) {
            LooksRenderer.failOnce("drawing the frame guides", t);
        }
    }

}
