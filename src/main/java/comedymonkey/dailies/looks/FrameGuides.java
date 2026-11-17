package comedymonkey.dailies.looks;

import comedymonkey.dailies.edit.Looks;
import comedymonkey.dailies.export.Export;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * What is drawn over the view while framing a shot (specification chapter V, "The frame"): bars that leave a frame of
 * the chosen shape clear, rule-of-thirds lines and a centre cross. The bars are dimmed when the shape is only a guide,
 * and solid black when the scene holds its picture to that shape, in which case what shows between them is the
 * picture an export will hold. None of it is ever drawn into an exported picture: an export of a framed scene is
 * that shape already.
 */
final class FrameGuides {

    private static final int DIM = 0xB0000000;
    private static final int SOLID = 0xFF000000;
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
                double[] clear = Looks.clearPart(ratio, windowRatio);
                left = (int) Math.round(width * clear[0]);
                right = (int) Math.round(width * clear[2]);
                top = (int) Math.round(height * clear[1]);
                bottom = (int) Math.round(height * clear[3]);
                boolean solid = looks.letterbox;
                int bar = solid ? SOLID : DIM;
                if (left > 0 || right < width) {
                    // Narrower than the window: bars down the sides
                    graphics.fill(0, 0, left, height, bar);
                    graphics.fill(right, 0, width, height, bar);
                    if (!solid) {
                        graphics.fill(left - 1, 0, left, height, EDGE);
                        graphics.fill(right, 0, right + 1, height, EDGE);
                    }
                } else if (top > 0 || bottom < height) {
                    // Wider than the window: bars above and below
                    graphics.fill(0, 0, width, top, bar);
                    graphics.fill(0, bottom, width, height, bar);
                    if (!solid) {
                        graphics.fill(0, top - 1, width, top, EDGE);
                        graphics.fill(0, bottom, width, bottom + 1, EDGE);
                    }
                }
                Drawn.bars(left, top, right, bottom, solid);
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
