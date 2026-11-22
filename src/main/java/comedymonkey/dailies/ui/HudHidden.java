package comedymonkey.dailies.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Hud;

/**
 * Whether the game's HUD is hidden (F1). Minecraft keeps it in the HUD itself, which can only be toggled; this reads it
 * and sets it to a given value. Render thread
 */
public final class HudHidden {

    private HudHidden() {
    }

    public static boolean get() {
        return Minecraft.getInstance().gui.hud.isHidden();
    }

    public static void set(boolean hidden) {
        Hud hud = Minecraft.getInstance().gui.hud;
        if (hud.isHidden() != hidden) {
            hud.toggle();
        }
    }
}
