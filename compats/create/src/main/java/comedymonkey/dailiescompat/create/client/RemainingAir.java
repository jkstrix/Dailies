package comedymonkey.dailiescompat.create.client;

import com.simibubi.create.content.equipment.armor.RemainingAirOverlay;
import comedymonkey.dailiescompat.create.state.States;
import net.createmod.catnip.gui.element.GuiGameElement;
import net.createmod.catnip.theme.Color;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringUtil;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import org.jetbrains.annotations.Nullable;

/**
 * The air left in a backtank, which Create shows beside the hotbar while the player is under water or in lava wearing
 * one: the backtank and the time left. Recorded as the seconds left (which the server sends the player's game) and the
 * backtank, and drawn in playback as Create draws it.
 */
final class RemainingAir {

    static final String NAME = "remaining_air";

    private RemainingAir() {
    }

    static byte @Nullable [] capture() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        // As RemainingAirOverlay.render decides whether to draw
        if (player == null || minecraft.options.hideGui || minecraft.gameMode == null || minecraft.gameMode.getPlayerMode() == GameType.SPECTATOR
            || player.isCreative() || !player.getPersistentData().contains("VisualBacktankAir")) {
            return null;
        }
        boolean isAir = player.getEyeInFluidType().isAir()
            || player.level().getBlockState(BlockPos.containing(player.getX(), player.getEyeY(), player.getZ())).is(Blocks.BUBBLE_COLUMN);
        boolean canBreathe = !player.canDrownInFluidType(player.getEyeInFluidType()) || MobEffectUtil.hasWaterBreathing(player)
            || player.getAbilities().invulnerable;
        if ((isAir || canBreathe) && !player.isInLava()) {
            return null;
        }
        ItemStack backtank = RemainingAirOverlay.getDisplayedBacktank(player);
        return States.air(new States.Air(player.getPersistentData().getInt("VisualBacktankAir"), Texts.item(backtank),
            backtank.has(DataComponents.FIRE_RESISTANT)));
    }

    static void draw(GuiGraphics graphics, byte[] state) {
        States.Air air = States.readAir(state);
        Minecraft minecraft = Minecraft.getInstance();
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(graphics.guiWidth() / 2 + 90, graphics.guiHeight() - 53 + (air.fireResistant() ? 9 : 0), 0);
            float tickRate = minecraft.level == null ? 20.0F : minecraft.level.tickRateManager().tickrate();
            Component text = Component.literal(StringUtil.formatTickDuration(Math.max(0, air.timeLeft() - 1) * 20, tickRate));
            ItemStack backtank = Texts.stack(air.backtank());
            if (!backtank.isEmpty()) {
                GuiGameElement.of(backtank).<GuiGameElement.GuiRenderBuilder>at(0, 0).render(graphics);
            }
            int colour = 0xFF_FFFFFF;
            if (air.flashing()) {
                colour = Color.mixColors(0xFF_FF0000, colour, Math.max(air.timeLeft() / 60f, .25f));
            }
            graphics.drawString(minecraft.font, text, 16, 5, colour);
        } finally {
            graphics.pose().popPose();
        }
    }

}
