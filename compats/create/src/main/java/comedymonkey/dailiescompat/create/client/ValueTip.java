package comedymonkey.dailiescompat.create.client;

import com.simibubi.create.CreateClient;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsClient;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsInputHandler;
import comedymonkey.dailiescompat.create.state.States;
import net.createmod.catnip.theme.Color;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The value box's tip: the lines Create shows under the crosshair while a value box is aimed at (what the slot sets, and
 * that holding use edits it), drawn over the world as a layer of the HUD. Recorded from Create's own handler, the lines
 * and its count of how far they have faded in, and drawn in playback as Create draws them.
 */
final class ValueTip {

    static final String NAME = "value_tip";

    private ValueTip() {
    }

    static byte @Nullable [] capture() {
        Minecraft minecraft = Minecraft.getInstance();
        ValueSettingsClient handler = CreateClient.VALUE_SETTINGS_HANDLER;
        // As ValueSettingsClient.render decides whether to draw
        if (minecraft.options.hideGui || !ValueSettingsInputHandler.canInteract(minecraft.player) || handler.hoverTicks == 0 || handler.lastHoverTip == null
            || handler.lastHoverTip.isEmpty()) {
            return null;
        }
        return States.tip(new States.Tip(Texts.jsons(handler.lastHoverTip), handler.hoverTicks));
    }

    static void draw(GuiGraphics graphics, byte[] state) {
        States.Tip tip = States.readTip(state);
        List<Component> lines = Texts.reads(tip.lines());
        Minecraft minecraft = Minecraft.getInstance();
        // As ValueSettingsClient.render draws it
        int x = graphics.guiWidth() / 2;
        int y = graphics.guiHeight() - 75 - lines.size() * 12;
        Color colour = new Color(0xffffff);
        Color titleColour = new Color(0xFBDC7D);
        colour.setAlpha(tip.alpha());
        titleColour.setAlpha(tip.alpha());
        for (int i = 0; i < lines.size(); i++) {
            Component line = lines.get(i);
            MutableComponent shown = line.copy();
            graphics.drawString(minecraft.font, shown, x - minecraft.font.width(shown) / 2, y, (i == 0 ? titleColour : colour).getRGB());
            y += 12;
        }
    }

}
