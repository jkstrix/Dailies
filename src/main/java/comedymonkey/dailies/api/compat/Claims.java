package comedymonkey.dailies.api.compat;

import net.minecraft.client.gui.screens.Screen;

/**
 * What a compat claims, said from {@link DailiesCompat#claim}. Each claim has a name, unique within the compat, made of
 * lower-case letters, digits and {@code _ - . /}: its records go under it, and the display test and the log call it
 * {@code <compat id>/<name>}, such as {@code postrider:screens/letter_read}. Keep names once recordings carry them.
 * <p>
 * A screen class or menu type claimed by one compat is not claimed again by another: the second claim is left out with
 * a line in the log.
 */
public interface Claims {

    /**
     * A screen by its class: while the recorder's game shows a screen of this class (or of a class extending it, when
     * nothing claims that one more closely), the part records it, and Dailies' own window recording leaves it alone.
     * For screens only the player's game knows, and for a server window whose screen the compat records whole.
     */
    void screen(String name, Class<? extends Screen> screenClass, ScreenPart part);

    /**
     * A server window by its menu type id, such as {@code mymod:press}, recorded and drawn whole by the part instead of
     * by Dailies' own window recording, whatever screen class shows it.
     */
    void menu(String name, String menuType, ScreenPart part);

    /**
     * Extras for a server window that Dailies records and draws itself, by its menu type id: Dailies keeps recording its
     * slots, the item on the pointer and the pointer, and the part records what the screen holds besides (a tab, a
     * selection, text being typed) and puts it back on the screen Dailies builds in playback.
     */
    void windowExtras(String name, String menuType, WindowExtras extras);

    /** An overlay drawn over play, recorded while it shows and drawn in playback with the recorder's HUD */
    void overlay(String name, OverlayPart part);

    /**
     * The client half of a server half: what to do with the data a server plugin of this id sends this player through
     * the Dailies Companion. Dailies keeps it in the recording and hands it over again in playback.
     */
    void serverHalf(String pluginId, ServerHalf half);

}
