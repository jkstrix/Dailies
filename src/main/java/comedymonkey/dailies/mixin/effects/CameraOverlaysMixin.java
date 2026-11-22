package comedymonkey.dailies.mixin.effects;

import comedymonkey.dailies.effects.RecorderEyes;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The overlays the game draws over the picture from its own player, drawn in playback from the one whose eyes the view
 * is through ({@link RecorderEyes}): the spyglass, what is worn over the eyes (the carved pumpkin), frost, the portal
 * and nausea, and the sleep fade. The vignette stays as the game draws it, from the camera. Where nobody's should
 * show, none are drawn.
 */
@Mixin(Hud.class)
public abstract class CameraOverlaysMixin {

    private static final Identifier DAILIES$FROST = Identifier.withDefaultNamespace("textures/misc/powder_snow_outline.png");

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    private float scopeScale;

    @Shadow
    protected abstract void extractSpyglassOverlay(GuiGraphicsExtractor graphics, float scale);

    @Shadow
    protected abstract void extractTextureOverlay(GuiGraphicsExtractor graphics, Identifier texture, float alpha);

    @Shadow
    protected abstract void extractVignette(GuiGraphicsExtractor graphics, Entity camera);

    @Shadow
    protected abstract void extractPortalOverlay(GuiGraphicsExtractor graphics, float alpha);

    @Shadow
    protected abstract void extractConfusionOverlay(GuiGraphicsExtractor graphics, float strength);

    @Inject(method = "extractCameraOverlays", at = @At("HEAD"), cancellable = true)
    private void dailies$throughTheirEyes(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (!RecorderEyes.inPlayback()) {
            return;
        }
        ci.cancel();
        try {
            if (this.minecraft.options.vignette().get()) {
                this.extractVignette(graphics, this.minecraft.getCameraEntity());
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
                this.extractSpyglassOverlay(graphics, this.scopeScale);
            } else {
                this.scopeScale = 0.5F;
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    ItemStack item = subject.getItemBySlot(slot);
                    Equippable equippable = item.get(DataComponents.EQUIPPABLE);
                    if (equippable != null && equippable.slot() == slot) {
                        if (!comedymonkey.dailies.looks.LooksRenderer.showsPumpkinOverlay()) {
                            continue;
                        }
                        if (equippable.cameraOverlay().isPresent()) {
                            this.extractTextureOverlay(graphics, equippable.cameraOverlay().get().withPath(p -> "textures/" + p + ".png"), 1.0F);
                            worn++;
                        }
                        if (subject instanceof Player player) {
                            net.neoforged.neoforge.client.extensions.common.IClientItemExtensions.of(item).renderFirstPersonOverlay(item, slot, player, graphics, deltaTracker);
                        }
                    }
                }
            }
            float frost = subject.getTicksFrozen() > 0 ? subject.getPercentFrozen() : 0.0F;
            if (frost > 0.0F) {
                this.extractTextureOverlay(graphics, DAILIES$FROST, frost);
            }
            float partialTicks = deltaTracker.getGameTimeDeltaPartialTick(false);
            float portal = comedymonkey.dailies.effects.RecorderOverlays.portal(partialTicks);
            float nausea = subject.getEffectBlendFactor(MobEffects.NAUSEA, partialTicks);
            if (portal > 0.0F) {
                this.extractPortalOverlay(graphics, portal);
            } else if (nausea > 0.0F) {
                float screenEffectScale = this.minecraft.options.screenEffectScale().get().floatValue();
                if (screenEffectScale < 1.0F) {
                    this.extractConfusionOverlay(graphics, nausea * (1.0F - screenEffectScale));
                }
            }
            RecorderEyes.shownOverlays(true, frost, portal, spyglass, worn);
        } catch (RuntimeException | LinkageError e) {
            comedymonkey.dailies.effects.RecorderOverlays.failOnce("the camera overlays", e);
        }
    }

    // The potion effect icons at the top of the screen are the recorder's, through their eyes with the HUD shown
    @Redirect(method = "extractEffects", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getActiveEffects()Ljava/util/Collection;"))
    private java.util.Collection<net.minecraft.world.effect.MobEffectInstance> dailies$theirEffects(LocalPlayer player) {
        if (!RecorderEyes.inPlayback()) {
            return player.getActiveEffects();
        }
        LivingEntity subject = RecorderEyes.subject();
        java.util.Collection<net.minecraft.world.effect.MobEffectInstance> effects = subject == null ? java.util.List.of() : subject.getActiveEffects();
        RecorderEyes.iconsShown(effects.size());
        return effects;
    }

    // The spyglass's scope is drawn from the item the recorder looks through (a mod's scope has a texture of its own)
    @Redirect(method = "extractSpyglassOverlay", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getUseItem()Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack dailies$theirScope(LocalPlayer player) {
        if (RecorderEyes.inPlayback() && RecorderEyes.subject() instanceof LivingEntity subject) {
            return subject.getUseItem();
        }
        return player.getUseItem();
    }

    // The sleep fade, from whoever's eyes the view is through
    @Redirect(method = "extractSleepOverlay", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getSleepTimer()I"))
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
