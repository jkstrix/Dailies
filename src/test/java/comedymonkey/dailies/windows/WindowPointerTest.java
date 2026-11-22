package comedymonkey.dailies.windows;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pointer over a window lands on the same slot whatever the screen it is drawn on and its GUI scale */
class WindowPointerTest {

    /** A chest's window, 176 by 166 GUI units */
    private static final int WIDTH = 176;
    private static final int HEIGHT = 166;
    /** Where the chest's slot 4 is drawn in its window, 16 units square */
    private static final int SLOT_X = 80;
    private static final int SLOT_Y = 18;

    /** The slot under a pointer at these GUI units on a screen of this size, or -1 */
    private static boolean overSlot(double guiX, double guiY, int guiWidth, int guiHeight) {
        int left = WindowPointer.corner(guiWidth, WIDTH);
        int top = WindowPointer.corner(guiHeight, HEIGHT);
        // As the game tests it: the slot's square, a unit wider each way (AbstractContainerScreen.isHovering)
        return guiX >= left + SLOT_X - 1 && guiX < left + SLOT_X + 17 && guiY >= top + SLOT_Y - 1 && guiY < top + SLOT_Y + 17;
    }

    @Test
    void theInterfaceSizeIsTheGamesOwn() {
        assertEquals(640, WindowPointer.guiSize(1920, 3));
        assertEquals(360, WindowPointer.guiSize(1080, 3));
        assertEquals(683, WindowPointer.guiSize(1366, 2));
        assertEquals(427, WindowPointer.guiSize(854, 2));
        assertEquals(1920, WindowPointer.guiSize(1920, 1));
        assertEquals(232, WindowPointer.corner(640, WIDTH));
    }

    @Test
    void aPointerOverASlotIsOverItOnAnyScreenAtAnyScale() {
        // Recorded at 1920 by 1080, GUI scale 3, the pointer in the middle of slot 4
        int[][] recordedAt = {{1920, 1080, 3}, {2560, 1440, 4}, {1280, 720, 2}, {854, 480, 1}};
        int[][] drawnAt = {{1920, 1080, 3}, {1920, 1080, 2}, {3840, 2160, 4}, {3840, 2160, 6}, {1280, 720, 1}, {1366, 768, 2}, {2560, 1080, 3}, {854, 480, 2}};
        for (int[] live : recordedAt) {
            int guiWidth = WindowPointer.guiSize(live[0], live[2]);
            int guiHeight = WindowPointer.guiSize(live[1], live[2]);
            int left = WindowPointer.corner(guiWidth, WIDTH);
            int top = WindowPointer.corner(guiHeight, HEIGHT);
            // The mouse handler gives the pointer in the screen's own pixels
            double rawX = (left + SLOT_X + 8.4) * live[0] / (double) guiWidth;
            double rawY = (top + SLOT_Y + 7.6) * live[1] / (double) guiHeight;
            double windowX = WindowPointer.toWindow(rawX, live[0], guiWidth, left);
            double windowY = WindowPointer.toWindow(rawY, live[1], guiHeight, top);
            assertEquals(SLOT_X + 8.4, windowX, 1e-6);
            assertEquals(SLOT_Y + 7.6, windowY, 1e-6);
            for (int[] playback : drawnAt) {
                int width = WindowPointer.guiSize(playback[0], playback[2]);
                int height = WindowPointer.guiSize(playback[1], playback[2]);
                double x = WindowPointer.toScreen(windowX, WindowPointer.corner(width, WIDTH));
                double y = WindowPointer.toScreen(windowY, WindowPointer.corner(height, HEIGHT));
                assertTrue(overSlot(x, y, width, height), "recorded at " + live[0] + "x" + live[1] + " scale " + live[2] + ", drawn at " + playback[0] + "x" + playback[1]
                    + " scale " + playback[2] + ": " + x + "," + y);
            }
        }
    }

    @Test
    void theWindowsCornerIsAtNoughtNought() {
        int guiWidth = WindowPointer.guiSize(1920, 3);
        int left = WindowPointer.corner(guiWidth, WIDTH);
        assertEquals(0.0, WindowPointer.toWindow(left * 3.0, 1920, guiWidth, left), 1e-9);
        assertEquals(-232.0, WindowPointer.toWindow(0.0, 1920, guiWidth, left), 1e-9);
    }

    @Test
    void aShareOfTheScreenIsTheSameShareOnAnother() {
        double share = WindowPointer.toShare(480, 1920);
        assertEquals(0.25, share, 1e-9);
        assertEquals(213.5, WindowPointer.fromShare(share, 854), 1e-9);
        assertEquals(0.0, WindowPointer.toShare(10, 0) * 0.0, 1e-9, "a screen of no size does not divide by nought");
    }

}
