package comedymonkey.dailies.mixin.effects;

import comedymonkey.dailies.effects.RecorderEyes;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The overlays the game draws over the picture from its own player, drawn in playback from the one whose eyes the view
 * is through ({@link RecorderEyes}): the spyglass, what is worn over the eyes (the carved pumpkin), frost, the portal,
 * and the sleep fade. The vignette stays as the game draws it, from the camera. Where nobody's should show, none are
 * drawn. Nausea's own overlay is drawn by the game's renderer ({@link WarpMixin}).
 */
@Mixin(Gui.class)
public abstract class CameraOverlaysMixin {

    private static final ResourceLocation DAILIES$PUMPKIN = ResourceLocation.withDefaultNamespace("textures/misc/pumpkinblur.png");
    private static final ResourceLocation DAILIES$FROST = ResourceLocation.withDefaultNamespace("textures/misc/powder_snow_outline.png");

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    private float scopeScale;

    @Shadow
    protected abstract void renderSpyglassOverlay(GuiGraphics graphics, float scale);

    @Shadow
    protected abstract void renderTextureOverlay(GuiGraphics graphics, ResourceLocation texture, float alpha);

    @Shadow
    protected abstract void renderVignette(GuiGraphics graphics, Entity camera);

    @Shadow
    protected abstract void renderPortalOverlay(GuiGraphics graphics, float alpha);

    // Each frame starts with nothing shown: with the HUD hidden the game skips the camera overlays altogether
    @Inject(method = "render", at = @At("HEAD"))
    private void dailies$frameStarts(GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (RecorderEyes.inPlayback()) {
            RecorderEyes.shownOverlays(false, 0.0F, 0.0F, false, 0);
            RecorderEyes.shownSleep(0);
        }
    }

    @Inject(method = "renderCameraOverlays", at = @At("HEAD"), cancellable = true)
    private void dailies$throughTheirEyes(GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (!RecorderEyes.inPlayback()) {
            return;
        }
        ci.cancel();
        try {
            if (Minecraft.useFancyGraphics()) {
                this.renderVignette(graphics, this.minecraft.getCameraEntity());
            }
            this.scopeScale = Mth.lerp(0.5F * deltaTracker.getGameTimeDeltaTicks(), this.scopeScale, 1.125F);
            LivingEntity subject = RecorderEyes.subject();
            if (subject == null) {
                RecorderEyes.shownOverlays(false, 0.0F, 0.0F, false, 0);
                return;
            }
            boolean spyglass = subject instanceof Player player && player.isScoping();
            int worn = 0;
            if (spyglass) {
                this.renderSpyglassOverlay(graphics, this.scopeScale);
            } else {
                this.scopeScale = 0.5F;
                ItemStack head = subject.getItemBySlot(EquipmentSlot.HEAD);
                if (comedymonkey.dailies.looks.LooksRenderer.showsPumpkinOverlay()) {
                    if (head.is(Blocks.CARVED_PUMPKIN.asItem())) {
                        this.renderTextureOverlay(graphics, DAILIES$PUMPKIN, 1.0F);
                        worn++;
                    } else if (subject instanceof Player player) {
                        net.neoforged.neoforge.client.extensions.common.IClientItemExtensions.of(head).renderHelmetOverlay(head, player, graphics, deltaTracker);
                    }
                }
            }
            float frost = subject.getTicksFrozen() > 0 ? subject.getPercentFrozen() : 0.0F;
            if (frost > 0.0F) {
                this.renderTextureOverlay(graphics, DAILIES$FROST, frost);
            }
            // The portal's purple, from the warp when nausea is not what draws it, as the game does
            float partialTicks = deltaTracker.getGameTimeDeltaPartialTick(false);
            float portal = 0.0F;
            float warp = comedymonkey.dailies.effects.RecorderOverlays.portal(partialTicks);
            if (warp > 0.0F && !subject.hasEffect(MobEffects.CONFUSION)) {
                portal = warp;
                this.renderPortalOverlay(graphics, portal);
            }
            RecorderEyes.shownOverlays(true, frost, portal, spyglass, worn);
        } catch (RuntimeException | LinkageError e) {
            comedymonkey.dailies.effects.RecorderOverlays.failOnce("the camera overlays", e);
        }
    }

    // The spyglass's own scope, from what the one looking through it holds
    @Redirect(method = "renderSpyglassOverlay", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getUseItem()Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack dailies$theirSpyglass(LocalPlayer player) {
        if (RecorderEyes.inPlayback() && RecorderEyes.subject() instanceof LivingEntity subject) {
            return subject.getUseItem();
        }
        return player.getUseItem();
    }

    // The sleep fade, from whoever's eyes the view is through
    @Redirect(method = "renderSleepOverlay", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getSleepTimer()I"))
    private int dailies$theirSleep(LocalPlayer player) {
        if (!RecorderEyes.inPlayback()) {
            return player.getSleepTimer();
        }
        LivingEntity subject = RecorderEyes.subject();
        int sleep = subject instanceof Player sleeper ? sleeper.getSleepTimer() : 0;
        RecorderEyes.shownSleep(sleep);
        return sleep;
    }
}
