package comedymonkey.dailies.api.compat;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jetbrains.annotations.Nullable;

/** An overlay drawn over play: see {@link Claims#overlay} */
public interface OverlayPart {

    /**
     * What the overlay shows, while recording: called at the end of each recorded tick. Client thread.
     *
     * @return the whole state, or null while the overlay is not showing on the recorder's screen
     */
    @Nullable byte[] capture();

    /**
     * Draws a state in playback, over the world and under any window, while the viewer rides along with the recorder in
     * first person with the recorder's HUD on. Client thread.
     *
     * @param version the compat's version that wrote the state
     */
    void draw(GuiGraphicsExtractor graphics, byte[] state, int version, float partialTick);

    /** The fewest ticks between two writes of a changed state, as for {@link ScreenPart#ticksBetweenWrites} */
    default int ticksBetweenWrites() {
        return 1;
    }

}
