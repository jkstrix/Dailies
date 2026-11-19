package comedymonkey.dailiescompat.template.client;

import comedymonkey.dailiescompat.template.TemplateMod;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * The example overlay: while the player holds the example note, where they would respawn, which only the server knows
 * (the server half sends it). Drawn live as a GUI layer; the compat records the line it shows and draws the same line
 * in playback.
 */
public final class HomeOverlay {

    /** Where the server says this player respawns; null until it has said, or on a server without the server half */
    static volatile @Nullable BlockPos home = null;

    private HomeOverlay() {
    }

    /** The line the overlay shows now in the live game, or null when it is not showing */
    static @Nullable String line(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.options.hideGui || !minecraft.player.getMainHandItem().is(TemplateMod.NOTE.get())) {
            return null;
        }
        BlockPos at = home;
        return at == null ? Component.translatable("overlay.dailies_template.home_unknown").getString()
            : Component.translatable("overlay.dailies_template.home", at.getX(), at.getY(), at.getZ()).getString();
    }

    /** The live layer. It stands down in playback, where Dailies draws what was recorded instead */
    static void drawLive(GuiGraphics graphics, DeltaTracker delta) {
        if (TemplateClient.inPlayback()) {
            return;
        }
        String line = line(Minecraft.getInstance());
        if (line != null) {
            draw(graphics, line);
        }
    }

    /** Draws the line, live or in playback */
    static void draw(GuiGraphics graphics, String line) {
        Minecraft minecraft = Minecraft.getInstance();
        int width = minecraft.font.width(line);
        int x = (graphics.guiWidth() - width) / 2;
        int y = graphics.guiHeight() - 72;
        graphics.fill(x - 3, y - 3, x + width + 3, y + 11, 0x90000000);
        graphics.drawString(minecraft.font, line, x, y, 0xA0E0FF, false);
    }

}
