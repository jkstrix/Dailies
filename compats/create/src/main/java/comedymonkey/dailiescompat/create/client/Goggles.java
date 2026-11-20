package comedymonkey.dailiescompat.create.client;

import com.simibubi.create.api.equipment.goggles.IHaveCustomOverlayIcon;
import com.simibubi.create.content.equipment.goggles.GoggleOverlayRenderer;
import com.simibubi.create.foundation.gui.RemovedGuiUtils;
import com.simibubi.create.infrastructure.config.AllConfigs;
import com.simibubi.create.infrastructure.config.CClient;
import com.mojang.datafixers.util.Either;
import comedymonkey.dailiescompat.create.state.States;
import net.createmod.catnip.gui.element.BoxElement;
import net.createmod.catnip.gui.element.GuiGameElement;
import net.createmod.catnip.theme.Color;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The engineer's goggles' readout, and the hover information Create shows the same way without goggles (a contraption's
 * assembly problem, a train's relocation), over the world beside the crosshair. Create works its lines out while it
 * draws, so the lines recorded are the ones it drew: while Create's goggles layer is drawn, the lines it hands the
 * game's tooltip machinery are kept, with the item drawn beside them. In playback they are drawn as Create draws them,
 * with its own tooltip and the player's own settings for where and in what colours.
 */
final class Goggles {

    static final String NAME = "goggles";

    private static final ResourceLocation LAYER = ResourceLocation.fromNamespaceAndPath("create", "goggle_info");
    private static final ResourceLocation GOGGLES = ResourceLocation.fromNamespaceAndPath("create", "goggles");

    /** While Create's goggles layer is being drawn */
    private static boolean inLayer = false;
    private static @Nullable List<Component> drawing = null;
    /** What the layer drew last, and whether it has drawn since the last tick was recorded */
    private static @Nullable List<Component> lastLines = null;
    private static String lastIcon = "";
    private static boolean drawnSinceTick = false;
    private static int ticksUndrawn = 0;

    private Goggles() {
    }

    // --- Recording ---

    static void layerStarts(RenderGuiLayerEvent.Pre event) {
        if (LAYER.equals(event.getName())) {
            inLayer = true;
            drawing = null;
        }
    }

    static void layerEnds(RenderGuiLayerEvent.Post event) {
        if (!LAYER.equals(event.getName()) || !inLayer) {
            return;
        }
        inLayer = false;
        lastLines = drawing;
        lastIcon = drawing == null ? "" : icon();
        drawing = null;
        drawnSinceTick = true;
    }

    /** The whole HUD is drawn: a goggles layer cut short by a fault leaves nothing behind for the screens drawn after it */
    static void hudEnds() {
        inLayer = false;
        drawing = null;
    }

    /** The lines Create's goggles layer hands the game's tooltips, before anything else changes them */
    static void tooltip(RenderTooltipEvent.GatherComponents event) {
        if (!inLayer) {
            return;
        }
        List<Component> lines = new ArrayList<>();
        for (Either<FormattedText, TooltipComponent> element : event.getTooltipElements()) {
            element.left().ifPresent(text -> lines.add(text instanceof Component component ? component : Component.literal(text.getString())));
        }
        drawing = lines.isEmpty() ? null : lines;
    }

    /** The item Create draws beside the readout, as GoggleOverlayRenderer picks it */
    private static String icon() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && minecraft.player != null && minecraft.hitResult instanceof BlockHitResult hit) {
            BlockEntity be = minecraft.level.getBlockEntity(GoggleOverlayRenderer.proxiedOverlayPosition(minecraft.level, hit.getBlockPos()));
            if (be instanceof IHaveCustomOverlayIcon custom) {
                return Texts.item(custom.getIcon(minecraft.player.isShiftKeyDown()));
            }
        }
        return GOGGLES.toString();
    }

    static byte @Nullable [] capture() {
        // A layer that stops being drawn (the HUD hidden, a world left) stops showing: a frame or two may pass between ticks
        if (drawnSinceTick) {
            drawnSinceTick = false;
            ticksUndrawn = 0;
        } else if (++ticksUndrawn > 2) {
            lastLines = null;
        }
        List<Component> lines = lastLines;
        return lines == null ? null : States.goggles(new States.Goggles(Texts.jsons(lines), lastIcon));
    }

    // --- Playback ---

    static void draw(GuiGraphics graphics, byte[] state) {
        States.Goggles goggles = States.readGoggles(state);
        List<Component> tooltip = Texts.reads(goggles.lines());
        if (tooltip.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        // As GoggleOverlayRenderer.renderOverlay lays it out, fully faded in
        int tooltipTextWidth = 0;
        for (FormattedText line : tooltip) {
            tooltipTextWidth = Math.max(tooltipTextWidth, minecraft.font.width(line));
        }
        int tooltipHeight = 8;
        if (tooltip.size() > 1) {
            tooltipHeight += 2 + (tooltip.size() - 1) * 10;
        }
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        CClient config = AllConfigs.client();
        int x = Math.min(width / 2 + config.overlayOffsetX.get(), width - tooltipTextWidth - 20);
        int y = Math.min(height / 2 + config.overlayOffsetY.get(), height - tooltipHeight - 20);
        boolean custom = config.overlayCustomColor.get();
        Color background = custom ? new Color(config.overlayBackgroundColor.get()) : BoxElement.COLOR_VANILLA_BACKGROUND.scaleAlpha(.75f);
        Color borderTop = custom ? new Color(config.overlayBorderColorTop.get()) : BoxElement.COLOR_VANILLA_BORDER.getFirst().copy();
        Color borderBottom = custom ? new Color(config.overlayBorderColorBot.get()) : BoxElement.COLOR_VANILLA_BORDER.getSecond().copy();
        graphics.pose().pushPose();
        try {
            ItemStack icon = Texts.stack(goggles.icon());
            if (!icon.isEmpty()) {
                GuiGameElement.of(icon).<GuiGameElement.GuiRenderBuilder>at(x + 10, y - 16, 450).render(graphics);
            }
            RemovedGuiUtils.drawHoveringText(graphics, tooltip, x, y, width, height, -1, background.getRGB(), borderTop.getRGB(), borderBottom.getRGB(),
                minecraft.font);
        } finally {
            graphics.pose().popPose();
        }
    }

}
