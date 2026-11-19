package comedymonkey.dailies.api.compat;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.jetbrains.annotations.Nullable;

/**
 * What a server window's screen holds that the server never sent, for a window Dailies records and draws itself: see
 * {@link Claims#windowExtras}.
 */
public interface WindowExtras {

    /**
     * What the screen holds besides its slots, while recording: called at the end of each recorded tick while Dailies
     * records the window. Client thread.
     *
     * @return the whole state, or null for nothing
     */
    @Nullable byte[] capture(AbstractContainerScreen<?> screen);

    /**
     * Puts a state back on the screen Dailies built for the window in playback, before each frame it is drawn, so keep
     * it quick; called again with the same state frame after frame. As with every screen drawn in playback, nothing may
     * be sent to the server. Client thread.
     *
     * @param version the compat's version that wrote the state
     */
    void apply(AbstractContainerScreen<?> screen, byte[] state, int version);

    /** The fewest ticks between two writes of a changed state, as for {@link ScreenPart#ticksBetweenWrites} */
    default int ticksBetweenWrites() {
        return 1;
    }

}
