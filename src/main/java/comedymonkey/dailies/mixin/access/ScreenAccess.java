package comedymonkey.dailies.mixin.access;

import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Lays out a screen that playback draws itself and never shows as the game's screen: a recorded window. The game's own
 * way in (Screen.init with the game, width and height) also tells every mod a screen has opened, and some would add
 * their buttons and panels to it, which the recorder saw but the owner wants left out of videos; so the screen is laid
 * out directly. It has the game and its font from the moment it is made
 */
@Mixin(Screen.class)
public interface ScreenAccess {

    @Invoker("init")
    void dailies$init();

}
