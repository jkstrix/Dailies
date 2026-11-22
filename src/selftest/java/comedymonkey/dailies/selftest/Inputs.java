package comedymonkey.dailies.selftest;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.sdl.SDLKeyboard;

/**
 * Input as the game hands it to a screen, so the scripted run presses keys and clicks the way a player does.
 */
final class Inputs {

    private Inputs() {
    }

    /** A key with no modifier held: where it is on the keyboard, and what it means there */
    static KeyEvent key(int key) {
        return key(key, 0);
    }

    /** A key with modifiers held, such as {@link InputConstants#MOD_SHIFT} */
    static KeyEvent key(int key, int modifiers) {
        return new KeyEvent(key, SDLKeyboard.SDL_GetKeyFromScancode(key, (short) 0, false), modifiers);
    }

    /** A typed character */
    static CharacterEvent character(int codepoint) {
        return new CharacterEvent(codepoint);
    }

    /** The left mouse button at a place on the screen */
    static MouseButtonEvent leftClick(double x, double y) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
    }
}
