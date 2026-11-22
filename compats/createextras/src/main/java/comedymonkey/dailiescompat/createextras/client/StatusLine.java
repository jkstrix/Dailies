package comedymonkey.dailiescompat.createextras.client;

import comedymonkey.dailiescompat.createextras.state.ExtrasStates;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

/**
 * The line the add-ons put above the hotbar on the player's own game, which no packet carries: Create Connected's (a
 * dashboard's readings), Create Deep Seas' (sneak to break a console, the console's own lines), Create: Tracks' (a
 * suspension key's setting), Steam 'n' Rails' (a buffer, a coupler, a whistle, a handcar, track placing) and Create:
 * Hypertubes' (a tube that cannot be placed), noted by the compat's mixin ({@link ExtrasMarks}). Recorded while the game
 * still shows that very line, with the ticks it has left, and drawn as the game draws its line above the hotbar. Lines
 * the servers send arrive in packets the recording keeps.
 */
final class StatusLine {

    static final String NAME = "status_line";

    private StatusLine() {
    }

    static byte @Nullable [] capture() {
        Minecraft minecraft = Minecraft.getInstance();
        Component noted = ExtrasMarks.status();
        if (noted == null || minecraft.options.hideGui) {
            return null;
        }
        Component shown = Reflect.get(minecraft.gui, "overlayMessageString");
        int ticksLeft = Reflect.get(minecraft.gui, "overlayMessageTime");
        // Shown while the game still holds the very line the mod put there, and it has not run out
        if (shown != noted || ticksLeft <= 0 || shown.getString().isEmpty()) {
            return null;
        }
        boolean animated = Reflect.get(minecraft.gui, "animateOverlayMessageColor");
        return ExtrasStates.status(new ExtrasStates.Status(Texts.json(shown), ticksLeft, animated));
    }

    /** As the game's Gui draws its line above the hotbar */
    static void draw(GuiGraphics graphics, byte[] state, float partialTick) {
        ExtrasStates.Status status = ExtrasStates.readStatus(state);
        int alpha = status.alpha(partialTick);
        if (alpha <= 8) {
            return;
        }
        Component text = Texts.read(status.text());
        Font font = Minecraft.getInstance().font;
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(graphics.guiWidth() / 2.0F, graphics.guiHeight() - 68.0F, 0.0F);
            float left = status.ticksLeft() - partialTick;
            int colour = status.animated() ? Mth.hsvToRgb(left / 50.0F, 0.7F, 0.6F) & 0xFF_FFFF : 0xFF_FFFF;
            int width = font.width(text);
            graphics.drawStringWithBackdrop(font, text, -width / 2, -4, width, colour | (alpha << 24 & 0xFF00_0000));
        } finally {
            graphics.pose().popPose();
        }
    }

}
