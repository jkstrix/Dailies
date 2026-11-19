package comedymonkey.dailiescompat.supplementaries.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import comedymonkey.dailies.api.compat.DailiesCompats;
import comedymonkey.dailiescompat.supplementaries.state.SuppStates;
import net.mehvahdjukaar.moonlight.api.entity.ITileEntityCarry;
import net.mehvahdjukaar.supplementaries.client.cannon.CannonController;
import net.mehvahdjukaar.supplementaries.client.hud.SelectableContainerItemHud;
import net.mehvahdjukaar.supplementaries.client.hud.SlimedOverlayHud;
import net.mehvahdjukaar.supplementaries.client.hud.platform.SelectableContainerItemHudImpl;
import net.mehvahdjukaar.supplementaries.common.block.cannon.BallisticTrajectory;
import net.mehvahdjukaar.supplementaries.common.block.cannon.ShootingMode;
import net.mehvahdjukaar.supplementaries.common.block.tiles.CannonBlockTile;
import net.mehvahdjukaar.supplementaries.common.entities.data.SlimedData;
import net.mehvahdjukaar.supplementaries.common.items.SelectableContainerItem;
import net.mehvahdjukaar.supplementaries.common.items.components.SelectableContainerContent;
import net.mehvahdjukaar.supplementaries.configs.ClientConfigs;
import net.mehvahdjukaar.supplementaries.reg.ClientRegistry;
import net.mehvahdjukaar.supplementaries.reg.ModData;
import net.mehvahdjukaar.supplementaries.reg.ModTextures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Supplementaries' overlays drawn over play: the slime over the screen of a player hit by a slimeball, the row of a quiver
 * or lunch basket being picked from, and the cannon's controls (the barrel's view while loaded in one, its hotbar,
 * charge, power, trajectory icons and crosshair while aimed, its charge while riding a cannon boat). Each is recorded as
 * what it showed on the recorder's game and drawn as Supplementaries draws it, with its own textures; none is worked out
 * again from the playback world, whose recorder holds neither the slime's ticks nor the cannon being aimed.
 */
final class SuppHud {

    static final String SLIMED = "slimed";
    static final String SELECTABLE = "selectable_row";
    static final String CANNON = "cannon_controls";

    private SuppHud() {
    }

    // --- The slime over the screen ---

    static byte @Nullable [] captureSlimed() {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.options.hideGui || !ClientConfigs.Tweaks.SLIME_OVERLAY.get()) {
            return null;
        }
        SlimedData data = (SlimedData) ModData.SLIMED_DATA.getOrCreate(player);
        int ticks = data.getSlimedTicks();
        return ticks > 0 ? SuppStates.slimed(new SuppStates.Slimed(ticks)) : null;
    }

    /** As SlimedOverlayHud draws it: the slime over the whole screen, sliding down as it fades */
    static void drawSlimed(GuiGraphics graphics, byte[] state, float partialTick) {
        float alpha = SuppStates.slimeAlpha(SuppStates.readSlimed(state).ticks(), partialTick);
        if (alpha <= 0.0F) {
            return;
        }
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        graphics.setColor(1.0F, 1.0F, 1.0F, alpha);
        try {
            int height = graphics.guiHeight();
            int width = graphics.guiWidth();
            int slide = (int) (height / 3.0F * (1.0F - alpha));
            SlimedOverlayHud.INSTANCE.blit(graphics, ModTextures.SLIME_GUI_OVERLAY, 0, 0, -90, 0.0F, 0.0F, width, height + slide, width, height + slide);
        } finally {
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.disableBlend();
            graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    // --- A quiver's or lunch basket's row ---

    static byte @Nullable [] captureSelectable() {
        Minecraft minecraft = Minecraft.getInstance();
        SelectableContainerItemHud hud = SelectableContainerItemHud.getInstance();
        if (minecraft.player == null || minecraft.options.hideGui || !hud.isActive() || minecraft.getCameraEntity() != minecraft.player) {
            return null;
        }
        // As the row closes itself when its key is let go
        if (hud.isUsingKey() && !ClientRegistry.QUIVER_KEYBIND.isUnbound() && !ClientRegistry.QUIVER_KEYBIND.isDown()) {
            return null;
        }
        ItemStack stack = Reflect.call(hud, "getItemUsed", new Class<?>[0]);
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof SelectableContainerItem<?, ?> selectable)) {
            return null;
        }
        if (!(stack.get(selectable.getComponentType()) instanceof SelectableContainerContent<?> content)) {
            return null;
        }
        List<byte[]> items = new ArrayList<>();
        for (ItemStack each : content.getContentUnsafe()) {
            items.add(Texts.item(each));
        }
        if (items.isEmpty()) {
            return null;
        }
        return SuppStates.selectable(new SuppStates.Selectable(items, content.getSelectedSlot(), ClientConfigs.Items.QUIVER_GUI_X.get(),
            ClientConfigs.Items.QUIVER_GUI_Y.get()));
    }

    /** As SelectableContainerItemHud draws it: the row above the crosshair, the place picked lit, its item's name over it */
    static void drawSelectable(GuiGraphics graphics, byte[] state) {
        SuppStates.Selectable recorded = SuppStates.readSelectable(state);
        Minecraft minecraft = Minecraft.getInstance();
        List<ItemStack> items = new ArrayList<>();
        for (byte[] item : recorded.items()) {
            items.add(Texts.stack(item));
        }
        int slots = items.size();
        int picked = Math.clamp(recorded.picked(), 0, slots - 1);
        graphics.pose().pushPose();
        try {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            int centre = graphics.guiWidth() / 2;
            int width = slots * 20 + 2;
            int px = width / 2 + recorded.offsetX();
            int py = graphics.guiHeight() / 2 - 40 + recorded.offsetY();
            graphics.pose().pushPose();
            graphics.pose().translate(0.0F, 0.0F, -90.0F);
            graphics.blitSprite(ModTextures.SELECTABLE_ITEM_BAR, 182, 22, 0, 0, centre - px, py, width - 1, 22);
            graphics.blitSprite(ModTextures.SELECTABLE_ITEM_BAR, 182, 22, 181, 0, centre + px - 1, py, 1, 22);
            graphics.blitSprite(ModTextures.SELECTABLE_ITEM_OVERLAY, centre - px - 1 + picked * 20, py - 1, 24, 24);
            graphics.pose().popPose();
            Font font = minecraft.font;
            for (int i = 0; i < slots; i++) {
                ItemStack item = items.get(i);
                if (!item.isEmpty()) {
                    int x = centre - px + 3 + i * 20;
                    graphics.renderItem(item, x, py + 3, i + 1);
                    RenderSystem.setShader(GameRenderer::getPositionColorShader);
                    graphics.renderItemDecorations(font, item, x, py + 3);
                }
            }
            RenderSystem.disableBlend();
            ItemStack chosen = items.get(picked);
            if (!chosen.isEmpty() && SelectableContainerItemHud.getInstance() instanceof SelectableContainerItemHudImpl hud) {
                hud.drawHighlight(graphics, graphics.guiWidth(), py, chosen);
            }
        } finally {
            graphics.pose().popPose();
        }
    }

    // --- The cannon's controls ---

    static byte @Nullable [] captureCannon() {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.options.hideGui) {
            return null;
        }
        boolean barrel = CannonBlockTile.riddenBy(player) != null;
        CannonBlockTile cannon = null;
        int kind = SuppStates.CANNON_NONE;
        boolean icons = false;
        int shooting = 0;
        boolean trajectory = false;
        int crosshair = SuppStates.CROSSHAIR_NONE;
        if (CannonController.isActive()) {
            cannon = Reflect.getStatic(CannonController.class, "cannon");
            kind = SuppStates.CANNON_AIMING;
            icons = !CannonController.isFirstPersonAiming();
            ShootingMode mode = CannonController.getShootingMode();
            shooting = mode.ordinal();
            trajectory = Reflect.getStatic(CannonController.class, "showsTrajectory");
            BallisticTrajectory path = Reflect.getStatic(CannonController.class, "trajectory");
            crosshair = mode == ShootingMode.STRAIGHT ? SuppStates.CROSSHAIR_AIM : path != null && !path.miss() ? SuppStates.CROSSHAIR_HIT : SuppStates.CROSSHAIR_MISS;
        } else if (player.getVehicle() instanceof ITileEntityCarry carry && carry.getCarriedTileEntity() instanceof CannonBlockTile carried) {
            cannon = carried;
            kind = SuppStates.CANNON_BOAT;
        } else if (!barrel) {
            return null;
        }
        byte[] projectile = cannon == null ? new byte[0] : Texts.item(cannon.getProjectile());
        byte[] fuel = cannon == null ? new byte[0] : Texts.item(cannon.getFuel());
        Player rider = cannon == null ? null : cannon.getRider();
        return SuppStates.cannonHud(new SuppStates.CannonHud(barrel, kind, icons, shooting, trajectory, crosshair, projectile, fuel,
            rider == null ? "" : rider.getUUID().toString(), cannon == null ? 0.0F : cannon.getCooldownAnimation(0.0F),
            cannon == null ? 0.0F : cannon.getCooldownAnimation(1.0F), cannon == null ? 0.0F : cannon.getFiringAnimation(0.0F),
            cannon == null ? 0.0F : cannon.getFiringAnimation(1.0F), cannon == null ? 0 : cannon.getPowerLevel()));
    }

    /** As CannonManeuverHud draws it, with the crosshair Supplementaries draws in the game's place while a cannon is aimed */
    static void drawCannon(GuiGraphics graphics, byte[] state, float partialTick) {
        SuppStates.CannonHud recorded = SuppStates.readCannonHud(state);
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        if (recorded.barrel()) {
            drawBarrel(graphics);
        }
        if (recorded.kind() == SuppStates.CANNON_NONE) {
            return;
        }
        setUp();
        try {
            if (recorded.kind() == SuppStates.CANNON_AIMING) {
                drawHotbar(graphics, width, height, recorded);
            }
            drawCharge(graphics, width, height, recorded, partialTick);
            if (recorded.kind() == SuppStates.CANNON_AIMING && recorded.icons()) {
                drawIcons(graphics, width, height, recorded);
            }
        } finally {
            RenderSystem.enableDepthTest();
        }
        if (recorded.kind() == SuppStates.CANNON_AIMING) {
            drawCrosshair(graphics, recorded.crosshair());
        }
    }

    private static void setUp() {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
    }

    private static void drawBarrel(GuiGraphics graphics) {
        int w = graphics.guiWidth();
        int h = graphics.guiHeight();
        int size = Math.min(w, h);
        int left = (w - size) / 2;
        int top = (h - size) / 2;
        int right = left + size;
        int bottom = top + size;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);
        int border = -14869219;
        graphics.blit(ModTextures.CANNON_OVERLAY_TEXTURE, left, top, -90, 0.0F, 0.0F, size, size, size, size);
        graphics.fill(RenderType.guiOverlay(), 0, 0, w, top, -90, border);
        graphics.fill(RenderType.guiOverlay(), 0, bottom, w, h, -90, border);
        graphics.fill(RenderType.guiOverlay(), 0, top, left, bottom, -90, border);
        graphics.fill(RenderType.guiOverlay(), right, top, w, bottom, -90, border);
        RenderSystem.depthMask(true);
    }

    private static void drawHotbar(GuiGraphics graphics, int width, int height, SuppStates.CannonHud recorded) {
        Minecraft minecraft = Minecraft.getInstance();
        int left = width / 2 - 91;
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, -90.0F);
        graphics.blitSprite(ModTextures.CANNON_HOTBAR_SPRITE, left, height - 22, 182, 22);
        graphics.pose().popPose();
        int y = height - 16 - 3;
        AbstractClientPlayer rider = rider(recorded.rider());
        if (rider != null) {
            ResourceLocation skin = rider.getSkin().texture();
            RenderSystem.enableBlend();
            graphics.blit(skin, left + 50, y, 16, 16, 8.0F, 8.0F, 8, 8, 64, 64);
            graphics.blit(skin, left + 50, y, 16, 16, 40.0F, 8.0F, 8, 8, 64, 64);
            RenderSystem.disableBlend();
        } else {
            slot(graphics, minecraft, left + 50, y, Texts.stack(recorded.projectile()));
        }
        slot(graphics, minecraft, left + 116, y, Texts.stack(recorded.fuel()));
    }

    /** The player loaded in the cannon, as the playback world holds them; null when none was, or they are not there */
    private static @Nullable AbstractClientPlayer rider(String id) {
        Minecraft minecraft = Minecraft.getInstance();
        if (id.isEmpty() || minecraft.level == null) {
            return null;
        }
        try {
            return minecraft.level.getPlayerByUUID(UUID.fromString(id)) instanceof AbstractClientPlayer player ? player : null;
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }

    private static void slot(GuiGraphics graphics, Minecraft minecraft, int x, int y, ItemStack item) {
        if (!item.isEmpty()) {
            Player holder = DailiesCompats.recorder();
            if (holder != null) {
                graphics.renderItem(holder, item, x, y, 1);
            } else {
                graphics.renderItem(item, x, y, 1);
            }
            graphics.renderItemDecorations(minecraft.font, item, x, y);
        }
    }

    private static void drawCharge(GuiGraphics graphics, int width, int height, SuppStates.CannonHud recorded, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        int left = width / 2 - 91;
        int filled = (int) ((1.0F - recorded.cooldown(partialTick)) * 183.0F);
        int top = height - 32 + 3;
        graphics.blitSprite(ModTextures.CANNON_CHARGE_BACKGROUND_SPRITE, left, top, 182, 5);
        float firing = recorded.firing(partialTick);
        float min = 0.7F;
        if (firing > 0.0F) {
            firing = 1.0F - firing;
            RenderSystem.setShaderColor(Math.min(firing * 0.4F + min, 1.0F), Math.min(min - firing * 0.4F * min, 1.0F), min - firing * min, 1.0F);
        } else {
            RenderSystem.setShaderColor(min, min, min, 1.0F);
        }
        graphics.blitSprite(ModTextures.CANNON_CHARGE_PROGRESS_SPRITE, 183, 5, 0, 0, left, top, filled, 5);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        int colour = switch (recorded.power()) {
            case 2 -> 16755200;
            case 3 -> 16746496;
            case 4 -> 16737792;
            default -> 16763904;
        };
        String power = String.valueOf((int) recorded.power());
        int x = (width - minecraft.font.width(power)) / 2;
        int y = height - 31 - 4;
        graphics.drawString(minecraft.font, power, x + 1, y, 0, false);
        graphics.drawString(minecraft.font, power, x - 1, y, 0, false);
        graphics.drawString(minecraft.font, power, x, y + 1, 0, false);
        graphics.drawString(minecraft.font, power, x, y - 1, 0, false);
        graphics.drawString(minecraft.font, power, x, y, colour, false);
    }

    private static void drawIcons(GuiGraphics graphics, int width, int height, SuppStates.CannonHud recorded) {
        int top = height - 22;
        ShootingMode[] modes = ShootingMode.values();
        ShootingMode mode = recorded.shooting() >= 0 && recorded.shooting() < modes.length ? modes[recorded.shooting()] : ShootingMode.STRAIGHT;
        ResourceLocation shape = switch (mode) {
            case DOWN -> ModTextures.CANNON_TRAJECTORY_0_SPRITE;
            case UP -> ModTextures.CANNON_TRAJECTORY_1_SPRITE;
            default -> ModTextures.CANNON_TRAJECTORY_2_SPRITE;
        };
        graphics.blitSprite(shape, width / 2 + 96, top, 14, 14);
        graphics.blitSprite(recorded.trajectory() ? ModTextures.CANNON_TRAJECTORY_SHOWN_SPRITE : ModTextures.CANNON_TRAJECTORY_HIDDEN_SPRITE, width / 2 - 110, top,
            14, 14);
    }

    private static void drawCrosshair(GuiGraphics graphics, int crosshair) {
        ResourceLocation sprite = switch (crosshair) {
            case SuppStates.CROSSHAIR_AIM -> ModTextures.CANNON_CROSSHAIR_AIM_SPRITE;
            case SuppStates.CROSSHAIR_HIT -> ModTextures.CANNON_CROSSHAIR_HIT_SPRITE;
            case SuppStates.CROSSHAIR_MISS -> ModTextures.CANNON_CROSSHAIR_MISS_SPRITE;
            default -> null;
        };
        if (sprite == null) {
            return;
        }
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE_MINUS_DST_COLOR, GlStateManager.DestFactor.ONE_MINUS_SRC_COLOR,
            GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        graphics.blitSprite(sprite, (graphics.guiWidth() - 9) / 2, (graphics.guiHeight() - 9) / 2, 9, 9);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

}
