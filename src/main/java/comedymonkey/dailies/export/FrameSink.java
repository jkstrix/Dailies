package comedymonkey.dailies.export;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Where an export's frames go: a video encoder, or a folder of numbered pictures. Frames arrive in order on one
 * thread of the export's own, never on the game's.
 */
interface FrameSink {

    /**
     * @param rgba the picture, four bytes a pixel, bottom row first (as the graphics card hands it over)
     */
    void frame(long number, byte[] rgba, int width, int height) throws IOException;

    /**
     * @param cancelled the export was stopped early. What was written so far is kept and left usable
     * @return the file or folder that was written
     */
    Path finish(boolean cancelled) throws IOException;

    /** Something went wrong: release everything */
    void abort();

}
