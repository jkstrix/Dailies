package comedymonkey.dailiescompat.supplementaries.client;

/**
 * Counts the frames the game draws, for the parts noted in one frame and drawn in the next. It refers to nothing of
 * Dailies or the mods.
 */
final class Frames {

    private static volatile long frames = 0;

    private Frames() {
    }

    static long now() {
        return frames;
    }

    static void next() {
        frames++;
    }

}
