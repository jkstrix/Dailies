package comedymonkey.dailiescompat.create.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import com.simibubi.create.content.equipment.blueprint.BlueprintOverlayRenderer;
import com.simibubi.create.content.equipment.toolbox.ToolboxHandler;
import com.simibubi.create.content.logistics.tableCloth.BlueprintOverlayShopContext;
import com.simibubi.create.content.trains.track.TrackPlacement;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.simibubi.create.foundation.utility.CreateLang;
import comedymonkey.dailiescompat.create.state.States;
import net.createmod.catnip.animation.LerpedFloat;
import net.createmod.catnip.data.Pair;
import net.createmod.catnip.ghostblock.GhostBlockParams;
import net.createmod.catnip.gui.element.GuiGameElement;
import net.createmod.catnip.ghostblock.GhostBlockRenderer;
import net.createmod.catnip.ghostblock.GhostBlocks;
import net.createmod.catnip.nbt.NBTHelper;
import net.createmod.catnip.placement.PlacementClient;
import net.createmod.catnip.theme.Color;
import net.createmod.ponder.enums.PonderConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Create's marks and rows over play that its own HUD layers draw from what the player's game works out, each recorded as
 * what was drawn and drawn again as Create draws it, with Create's own textures:
 * <ul>
 * <li>the toolbox's marks on the hotbar slots holding an item taken from a toolbox, lit while the toolbox is in reach
 * ({@link #TOOLBOX_MARKS});</li>
 * <li>the row above the hotbar for a crafting blueprint looked at, track or chain about to be placed, a table cloth's
 * shop or a shopping list ({@link #BLUEPRINT});</li>
 * <li>the hint while a curve of track is being placed, to hold the sprint key for a smooth one ({@link #TRACK_HINT});</li>
 * <li>a placement helper's arrow round the crosshair, and its ghost blocks, which the compat draws into the world
 * ({@link #PLACEMENT}, {@link WorldMarks}).</li>
 * </ul>
 */
final class PlayHud {

    static final String TOOLBOX_MARKS = "toolbox_marks";
    static final String BLUEPRINT = "blueprint_overlay";
    static final String TRACK_HINT = "track_hint";
    static final String PLACEMENT = "placement_helper";

    private PlayHud() {
    }

    private static boolean hidden(Minecraft minecraft) {
        return minecraft.options.hideGui || minecraft.player == null || minecraft.gameMode == null || minecraft.gameMode.getPlayerMode() == GameType.SPECTATOR;
    }

    // --- The toolbox's marks on the hotbar ---

    static byte @Nullable [] captureToolboxMarks() {
        Minecraft minecraft = Minecraft.getInstance();
        if (hidden(minecraft)) {
            return null;
        }
        // As ToolboxHandlerClient.renderOverlay reads them
        Player player = minecraft.player;
        CompoundTag compound = player.getPersistentData().getCompound("CreateToolboxData");
        if (compound.isEmpty()) {
            return null;
        }
        List<Integer> slots = new ArrayList<>();
        List<Boolean> inReach = new ArrayList<>();
        double max = ToolboxHandler.getMaxRange(player);
        for (int slot = 0; slot < 9; slot++) {
            String key = String.valueOf(slot);
            if (!compound.contains(key)) {
                continue;
            }
            BlockPos pos = NBTHelper.readBlockPos(compound.getCompound(key), "Pos");
            slots.add(slot);
            inReach.add(ToolboxHandler.distance(player.position(), pos) < max * max);
        }
        if (slots.isEmpty()) {
            return null;
        }
        return States.toolboxMarks(new States.ToolboxMarks(player.getInventory().selected, slots, inReach));
    }

    static void drawToolboxMarks(GuiGraphics graphics, byte[] state) {
        States.ToolboxMarks marks = States.readToolboxMarks(state);
        int x = graphics.guiWidth() / 2 - 90;
        int y = graphics.guiHeight() - 23;
        RenderSystem.enableDepthTest();
        for (int i = 0; i < marks.slots().size(); i++) {
            int slot = marks.slots().get(i);
            boolean selected = marks.selected() == slot;
            int offset = selected ? 1 : 0;
            boolean reach = i < marks.inReach().size() && marks.inReach().get(i);
            AllGuiTextures texture = reach ? selected ? AllGuiTextures.TOOLBELT_SELECTED_ON : AllGuiTextures.TOOLBELT_HOTBAR_ON
                : selected ? AllGuiTextures.TOOLBELT_SELECTED_OFF : AllGuiTextures.TOOLBELT_HOTBAR_OFF;
            texture.render(graphics, x + 20 * slot - offset, y + offset);
        }
    }

    // --- The blueprint overlay ---

    static byte @Nullable [] captureBlueprint() {
        Minecraft minecraft = Minecraft.getInstance();
        // As BlueprintOverlayRenderer.renderOverlay decides whether to draw
        if (minecraft.options.hideGui || minecraft.screen != null) {
            return null;
        }
        boolean active = Reflect.getStatic(BlueprintOverlayRenderer.class, "active");
        boolean empty = Reflect.getStatic(BlueprintOverlayRenderer.class, "empty");
        if (!active || empty) {
            return null;
        }
        List<Pair<ItemStack, Boolean>> ingredients = Reflect.getStatic(BlueprintOverlayRenderer.class, "ingredients");
        List<ItemStack> results = Reflect.getStatic(BlueprintOverlayRenderer.class, "results");
        BlueprintOverlayShopContext shop = Reflect.getStatic(BlueprintOverlayRenderer.class, "shopContext");
        List<States.Stack> had = new ArrayList<>();
        for (Pair<ItemStack, Boolean> pair : new ArrayList<>(ingredients)) {
            had.add(new States.Stack(Texts.item(pair.getFirst()), pair.getFirst().getCount(), pair.getSecond()));
        }
        List<States.Stack> made = new ArrayList<>();
        for (ItemStack result : new ArrayList<>(results)) {
            made.add(new States.Stack(Texts.item(result), result.getCount(), false));
        }
        return States.blueprint(new States.Blueprint(had, Reflect.getStatic(BlueprintOverlayRenderer.class, "noOutput"), made,
            Reflect.getStatic(BlueprintOverlayRenderer.class, "resultCraftable"), shop != null, shop != null && shop.checkout(),
            shop == null ? 0 : shop.stockLevel(), shop == null ? 0 : shop.purchases()));
    }

    private static ItemStack stack(States.Stack recorded) {
        ItemStack stack = Texts.stack(recorded.item());
        return stack.isEmpty() ? stack : stack.copyWithCount(Math.max(1, recorded.count()));
    }

    /** As BlueprintOverlayRenderer.renderOverlay draws it, from the recorded rows */
    static void drawBlueprint(GuiGraphics graphics, byte[] state) {
        States.Blueprint blueprint = States.readBlueprint(state);
        Minecraft minecraft = Minecraft.getInstance();
        List<States.Stack> ingredients = blueprint.ingredients();
        boolean shop = blueprint.shop();
        boolean invalidShop = shop && (ingredients.isEmpty() || ingredients.get(0).item().isEmpty() || blueprint.stock() == 0);

        int w = 21 * ingredients.size();
        if (!blueprint.noOutput()) {
            w += 21 * blueprint.results().size();
            w += 30;
        }
        int x = (graphics.guiWidth() - w) / 2;
        int y = graphics.guiHeight() - 100;

        if (shop) {
            TooltipRenderUtil.renderTooltipBackground(graphics, x - 2, y + 1, w + 4, 19, 0, 0x55_000000, 0x55_000000, 0, 0);
            AllGuiTextures.TRADE_OVERLAY.render(graphics, graphics.guiWidth() / 2 - 48, y - 19);
            if (blueprint.purchases() > 0) {
                graphics.renderItem(Texts.stack("create:shopping_list"), graphics.guiWidth() / 2 + 20, y - 20);
                graphics.drawString(minecraft.font, Component.literal("x" + blueprint.purchases()), graphics.guiWidth() / 2 + 20 + 16, y - 20 + 4, 0xff_eeeeee,
                    true);
            }
        }

        for (States.Stack ingredient : ingredients) {
            RenderSystem.enableBlend();
            (ingredient.had() ? AllGuiTextures.HOTSLOT_ACTIVE : AllGuiTextures.HOTSLOT).render(graphics, x, y);
            ItemStack itemStack = stack(ingredient);
            String count = shop && !blueprint.checkout() || ingredient.had() ? null : ChatFormatting.GOLD.toString() + itemStack.getCount();
            BlueprintOverlayRenderer.drawItemStack(graphics, minecraft, x, y, itemStack, count);
            x += 21;
        }
        if (blueprint.noOutput()) {
            return;
        }

        x += 5;
        RenderSystem.enableBlend();
        (invalidShop ? AllGuiTextures.HOTSLOT_ARROW_BAD : AllGuiTextures.HOTSLOT_ARROW).render(graphics, x, y + 4);
        x += 25;

        List<ItemStack> results = new ArrayList<>();
        for (States.Stack result : blueprint.results()) {
            results.add(stack(result));
        }
        if (results.isEmpty()) {
            AllGuiTextures.HOTSLOT.render(graphics, x, y);
            GuiGameElement.of(Items.BARRIER).at(x + 3, y + 3).render(graphics);
        } else {
            boolean craftable = blueprint.craftable();
            for (ItemStack result : results) {
                AllGuiTextures slot = craftable ? AllGuiTextures.HOTSLOT_SUPER_ACTIVE : AllGuiTextures.HOTSLOT;
                if (!invalidShop && shop && blueprint.stock() > blueprint.purchases()) {
                    slot = AllGuiTextures.HOTSLOT_ACTIVE;
                }
                slot.render(graphics, craftable ? x - 1 : x, craftable ? y - 1 : y);
                BlueprintOverlayRenderer.drawItemStack(graphics, minecraft, x, y, result, null);
                x += 21;
            }
        }

        if (shop && !blueprint.checkout() && minecraft.level != null) {
            // A result's tooltip, each in turn, as Create cycles them
            int cycle = 0;
            for (boolean count : new boolean[]{true, false}) {
                for (int i = 0; i < results.size(); i++) {
                    List<Component> lines = results.get(i).getTooltipLines(Item.TooltipContext.of(minecraft.level), minecraft.player, TooltipFlag.NORMAL);
                    if (lines.size() <= 1) {
                        continue;
                    }
                    if (count) {
                        cycle++;
                        continue;
                    }
                    if ((minecraft.gui.getGuiTicks() / 40) % cycle != i) {
                        continue;
                    }
                    graphics.renderComponentTooltip(minecraft.font, lines, graphics.guiWidth(), graphics.guiHeight());
                }
            }
        }
        RenderSystem.disableBlend();
    }

    // --- The track placement hint ---

    static byte @Nullable [] captureTrackHint() {
        Minecraft minecraft = Minecraft.getInstance();
        if (hidden(minecraft)) {
            return null;
        }
        // As TrackPlacementOverlay decides whether to draw
        BlockPos hovering = Reflect.getStatic(TrackPlacement.class, "hoveringPos");
        TrackPlacement.PlacementInfo cached = TrackPlacement.cached;
        if (hovering == null || cached == null || Reflect.get(cached, "curve") == null || !(boolean) Reflect.get(cached, "valid")) {
            return null;
        }
        int warmup = Reflect.getStatic(TrackPlacement.class, "extraTipWarmup");
        if (warmup < 4 || Reflect.getInt(minecraft.gui, "toolHighlightTimer") > 0) {
            return null;
        }
        // The key named as the recorder has it bound, not as whoever watches does
        boolean active = minecraft.options.keySprint.isDown();
        MutableComponent text = CreateLang.translateDirect("track.hold_for_smooth_curve",
            Component.literal(minecraft.options.keySprint.getTranslatedKeyMessage().getString()).withStyle(active ? ChatFormatting.WHITE : ChatFormatting.GRAY));
        return States.trackHint(new States.TrackHint(Texts.json(text), warmup));
    }

    static void drawTrackHint(GuiGraphics graphics, byte[] state) {
        States.TrackHint hint = States.readTrackHint(state);
        Minecraft minecraft = Minecraft.getInstance();
        Component text = Texts.read(hint.text());
        int x = (graphics.guiWidth() - minecraft.font.width(text)) / 2;
        int y = graphics.guiHeight() - 61;
        Color color = new Color(0x4ADB4A).setAlpha(hint.alpha());
        graphics.drawString(minecraft.font, text, x, y, color.getRGB(), false);
    }

    // --- Placement helpers ---

    static byte @Nullable [] capturePlacement() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.player == null) {
            return null;
        }
        int fade = Reflect.getStatic(PlacementClient.class, "animationTick");
        List<States.Ghost> ghosts = ghosts();
        if (fade <= 0 && ghosts.isEmpty()) {
            return null;
        }
        LerpedFloat angle = Reflect.getStatic(PlacementClient.class, "angle");
        float drawn = angle.getValue(0.0F);
        float snapped = (22.5F * Math.round(drawn / 22.5F)) % 360.0F;
        return States.placement(new States.Placement(fade, drawn, snapped, PonderConfig.client().placementIndicator.get().name(),
            PonderConfig.client().indicatorScale.get().floatValue(), ghosts));
    }

    /** The ghost blocks Catnip shows, as its GhostBlocks holds them */
    private static List<States.Ghost> ghosts() {
        Map<Object, Object> held = Reflect.get(GhostBlocks.getInstance(), "ghosts");
        List<States.Ghost> ghosts = new ArrayList<>();
        double breathing = GhostBlocks.getBreathingAlpha();
        for (Object entry : new ArrayList<>(held.values())) {
            GhostBlockRenderer renderer = Reflect.get(entry, "ghost");
            GhostBlockParams params = Reflect.get(entry, "params");
            BlockState state = Reflect.get(params, "state");
            BlockPos pos = Reflect.get(params, "pos");
            java.util.function.Supplier<Float> alpha = Reflect.get(params, "alphaSupplier");
            float now = alpha.get();
            ghosts.add(new States.Ghost(pos.asLong(), net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(state), now,
                Math.abs(now - breathing) < 1e-4, renderer == GhostBlockRenderer.transparent()));
        }
        return ghosts;
    }

    /** The arrow round the crosshair, as PlacementClient draws it; and the ghost blocks noted for the world's next drawing */
    static void drawPlacement(GuiGraphics graphics, byte[] state) {
        States.Placement placement = States.readPlacement(state);
        WorldMarks.noteGhosts(state, placement);
        if (placement.fade() <= 0) {
            return;
        }
        float centerX = graphics.guiWidth() / 2.0F;
        float centerY = graphics.guiHeight() / 2.0F;
        if (placement.mode().equals("TEXTURE")) {
            PlacementClient.textured(graphics.pose(), centerX, centerY, placement.alpha(), placement.snapped());
        } else if (placement.mode().equals("TRIANGLE")) {
            fadedArrow(graphics.pose(), centerX, centerY, placement.alpha(), placement.angle(), placement.scale());
        }
    }

    /** As PlacementClient.fadedArrow draws its arrow, which Catnip keeps to itself */
    private static void fadedArrow(PoseStack ms, float centerX, float centerY, float a, float angle, float scale) {
        float r = 0.8F;
        float g = 0.8F;
        float b = 0.8F;
        float length = 10;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        ms.pushPose();
        try {
            ms.translate(centerX, centerY, 5);
            ms.mulPose(Axis.ZP.rotationDegrees(angle));
            ms.scale(scale, scale, 1);
            BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
            Matrix4f mat = ms.last().pose();
            buffer.addVertex(mat, 0, -(10 + length), 0).setColor(r, g, b, a);
            buffer.addVertex(mat, -9, -3, 0).setColor(r, g, b, 0.0F);
            buffer.addVertex(mat, -6, -6, 0).setColor(r, g, b, 0.0F);
            buffer.addVertex(mat, -3, -8, 0).setColor(r, g, b, 0.0F);
            buffer.addVertex(mat, 0, -8.5F, 0).setColor(r, g, b, 0.0F);
            buffer.addVertex(mat, 3, -8, 0).setColor(r, g, b, 0.0F);
            buffer.addVertex(mat, 6, -6, 0).setColor(r, g, b, 0.0F);
            buffer.addVertex(mat, 9, -3, 0).setColor(r, g, b, 0.0F);
            BufferUploader.drawWithShader(buffer.buildOrThrow());
        } finally {
            RenderSystem.disableBlend();
            ms.popPose();
        }
    }

}
