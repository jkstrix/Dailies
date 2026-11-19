package comedymonkey.dailies.api.compat;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.jetbrains.annotations.Nullable;

/** A screen a compat records and draws whole: see {@link Claims#screen} and {@link Claims#menu} */
public interface ScreenPart {

    /**
     * What the screen shows, while recording: called at the end of each recorded tick while the recorder's game shows
     * the claimed screen. Client thread.
     *
     * @param screen the screen as the recorder's game holds it; only read it
     * @return the whole state, the least that draws the screen again, or null to leave this screen out of the recording
     */
    @Nullable byte[] capture(Screen screen);

    /**
     * A new screen showing a state, in playback. Dailies gives it the game and its size, lays it out without telling
     * other mods (so their buttons stay out), draws it as the recorder's game drew it with the recorded pointer, and
     * builds it again when the state or the size changes. The screen must take no part in play: never send anything to
     * the server, change the world or open another screen. Client thread.
     *
     * @param version the compat's version that wrote the state
     * @return the screen, or null when this state cannot be drawn here (it is then left out)
     */
    @Nullable Screen build(byte[] state, int version);

    /**
     * Where the screen's window starts, in GUI units from the screen's top left corner, as {x, y}; the pointer is kept
     * from there, so it lands on the same spot whatever the screen size or GUI scale. Asked of the recorder's screen
     * while recording and of the built screen in playback. Null keeps the pointer as a share of the screen instead.
     */
    default @Nullable int[] corner(Screen screen) {
        return screen instanceof AbstractContainerScreen<?> container ? new int[]{container.getGuiLeft(), container.getGuiTop()} : null;
    }

    /**
     * The fewest ticks between two writes of a changed state: 1 writes every change; 4 writes at most five times a
     * second, enough for text being typed. The state on opening is always written at once.
     */
    default int ticksBetweenWrites() {
        return 1;
    }

}
