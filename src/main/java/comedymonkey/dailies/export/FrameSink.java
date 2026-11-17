package comedymonkey.dailies.export;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Where an export's frames go: a video encoder, or a folder of numbered pictures. Frames arrive in order on one
 * thread of the export's own, never on the game's.
 */
interface FrameSink {

    /** How the bytes of a frame are laid out */
    enum Layout {
        /** Four bytes a pixel, bottom row first, as the graphics card hands a picture over */
        RGBA_BOTTOM_UP,
        /** Planar YUV 4:2:0, top row first ({@link I420}), converted on the graphics card */
        I420
    }

    /** What the sink wants its frames as; asked once, before the first frame */
    default Layout layout() {
        return Layout.RGBA_BOTTOM_UP;
    }

    /**
     * @param pixels the picture, laid out as {@link #layout()} says, in the first {@code length} bytes. The array is
     *               used again for a later frame as soon as this returns
     */
    void frame(long number, byte[] pixels, int length, int width, int height) throws IOException;

    /**
     * @param cancelled the export was stopped early. What was written so far is kept and left usable
     * @return the file or folder that was written
     */
    Path finish(boolean cancelled) throws IOException;

    /** Something went wrong: release everything */
    void abort();

}
