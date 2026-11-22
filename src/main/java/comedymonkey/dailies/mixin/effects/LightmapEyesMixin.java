package comedymonkey.dailies.mixin.effects;

import comedymonkey.dailies.effects.RecorderEyes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The darkness, night vision and underwater sight of the lightmap from the one whose eyes the view is through, not the
 * viewer's own: each thing the game asks of its own player as it works the lightmap out is asked of them instead, and
 * none show where nobody's should
 */
@Mixin(LightTexture.class)
public abstract class LightmapEyesMixin {

    @Shadow
    protected abstract float getDarknessGamma(float partialTicks);

    @Shadow
    protected abstract float calculateDarknessScale(LivingEntity camera, float darknessGamma, float partialTickTime);

    @Shadow
    private boolean updateLightTexture;

    @Unique
    private LivingEntity dailies$lastSubject = null;

    // The game works the lightmap out once a tick; when whose eyes the view is through changes, at once
    @Inject(method = "updateLightTexture", at = @At("HEAD"))
    private void dailies$newEyes(float partialTicks, CallbackInfo ci) {
        if (!RecorderEyes.inPlayback()) {
            this.dailies$lastSubject = null;
            return;
        }
        LivingEntity subject = RecorderEyes.subject();
        if (subject != this.dailies$lastSubject) {
            this.dailies$lastSubject = subject;
            this.updateLightTexture = true;
        }
    }

    @Redirect(method = "updateLightTexture", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LightTexture;getDarknessGamma(F)F"))
    private float dailies$theirDarkness(LightTexture self, float partialTicks) {
        if (!RecorderEyes.inPlayback()) {
            return this.getDarknessGamma(partialTicks);
        }
        try {
            LivingEntity subject = RecorderEyes.subject();
            if (subject == null) {
                RecorderEyes.shownLight(0.0F, 0.0F);
                return 0.0F;
            }
            float darkness = this.dailies$darknessOf(subject, partialTicks);
            // What the game goes on to work out from it, for the self test
            float darknessOption = Minecraft.getInstance().options.darknessEffectScale().get().floatValue();
            float scale = this.calculateDarknessScale(subject, darkness * darknessOption, partialTicks) * darknessOption;
            float waterVision = RecorderEyes.waterVision(subject);
            float nightVision = subject.hasEffect(MobEffects.NIGHT_VISION) ? GameRenderer.getNightVisionScale(subject, partialTicks)
                : waterVision > 0.0F && subject.hasEffect(MobEffects.CONDUIT_POWER) ? waterVision : 0.0F;
            RecorderEyes.shownLight(scale, nightVision);
            return darkness;
        } catch (RuntimeException | LinkageError e) {
            comedymonkey.dailies.effects.RecorderOverlays.failOnce("the lightmap", e);
            return 0.0F;
        }
    }

    @Unique
    private float dailies$darknessOf(LivingEntity living, float partialTicks) {
        MobEffectInstance darkness = living.getEffect(MobEffects.DARKNESS);
        return darkness != null ? darkness.getBlendFactor(living, partialTicks) : 0.0F;
    }

    @Redirect(method = "updateLightTexture", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LightTexture;calculateDarknessScale(Lnet/minecraft/world/entity/LivingEntity;FF)F"))
    private float dailies$theirDarknessScale(LightTexture self, LivingEntity camera, float darknessGamma, float partialTicks) {
        if (!RecorderEyes.inPlayback()) {
            return this.calculateDarknessScale(camera, darknessGamma, partialTicks);
        }
        LivingEntity subject = RecorderEyes.subject();
        return subject == null ? 0.0F : this.calculateDarknessScale(subject, darknessGamma, partialTicks);
    }

    @Redirect(method = "updateLightTexture", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getWaterVision()F"))
    private float dailies$theirWaterVision(LocalPlayer player) {
        if (!RecorderEyes.inPlayback()) {
            return player.getWaterVision();
        }
        LivingEntity subject = RecorderEyes.subject();
        return subject == null ? 0.0F : RecorderEyes.waterVision(subject);
    }

    @Redirect(method = "updateLightTexture", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;hasEffect(Lnet/minecraft/core/Holder;)Z"))
    private boolean dailies$theirEffect(LocalPlayer player, Holder<MobEffect> effect) {
        if (!RecorderEyes.inPlayback()) {
            return player.hasEffect(effect);
        }
        LivingEntity subject = RecorderEyes.subject();
        return subject != null && subject.hasEffect(effect);
    }

    @Redirect(method = "updateLightTexture", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;getNightVisionScale(Lnet/minecraft/world/entity/LivingEntity;F)F"))
    private float dailies$theirNightVision(LivingEntity camera, float partialTicks) {
        if (!RecorderEyes.inPlayback()) {
            return GameRenderer.getNightVisionScale(camera, partialTicks);
        }
        LivingEntity subject = RecorderEyes.subject();
        return subject == null || !subject.hasEffect(MobEffects.NIGHT_VISION) ? 0.0F : GameRenderer.getNightVisionScale(subject, partialTicks);
    }
}
