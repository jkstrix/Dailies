package comedymonkey.dailies.windows;

/**
 * Where the pointer is over a window, in the window's own units (specification REC-25, PLAY-20). The game measures its
 * interface in GUI units, the screen's pixels divided by the GUI scale, and centres a window in them; so a pointer
 * kept as GUI units from the window's top left corner lands on the same slot whatever the size of the screen it is
 * drawn on and whatever its GUI scale. A screen that has no window of a known size keeps it as a share of the screen.
 * No game in here, so it is unit tested.
 */
final class WindowPointer {

    private WindowPointer() {
    }

    /** The game's interface size along one side, in GUI units, for a side of this many pixels (Window.calculateScale) */
    static int guiSize(int pixels, int scale) {
        int size = pixels / scale;
        return pixels / scale * scale == pixels ? size : size + 1;
    }

    /** Where the game puts a window of this size along one side: centred, rounded down (AbstractContainerScreen.init) */
    static int corner(int guiSize, int windowSize) {
        return (guiSize - windowSize) / 2;
    }

    /**
     * @param raw     the pointer along one side in the screen's own pixels, as the game's mouse handler has it
     * @param pixels  the screen's size along that side in pixels
     * @param guiSize the interface's size along that side in GUI units
     * @param corner  where the window starts along that side, in GUI units
     * @return the pointer in GUI units from the window's corner
     */
    static double toWindow(double raw, int pixels, int guiSize, int corner) {
        return raw * guiSize / Math.max(1, pixels) - corner;
    }

    /** The other way: from GUI units from the window's corner to GUI units on the screen it is drawn on */
    static double toScreen(double window, int corner) {
        return corner + window;
    }

    /** The pointer as a share of the screen, for a screen with no window of a known size */
    static double toShare(double raw, int pixels) {
        return raw / Math.max(1, pixels);
    }

    static double fromShare(double share, int guiSize) {
        return share * guiSize;
    }

}
