package comedymonkey.dailies.mixin.effects;

import comedymonkey.dailies.effects.RecorderEyes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The darkness, night vision and underwater sight of the lightmap from the one whose eyes the view is through, not the
 * viewer's own: put in just as the game finishes working the lightmap out
 */
@Mixin(LightmapRenderStateExtractor.class)
public abstract class LightmapEyesMixin {

    @Shadow
    protected abstract float calculateDarknessScale(LivingEntity camera, float darknessGamma, float partialTickTime);

    @Inject(method = "extract", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/LightmapRenderStateExtractor;needsUpdate:Z", opcode = Opcodes.PUTFIELD))
    private void dailies$throughTheirEyes(LightmapRenderState renderState, float partialTicks, CallbackInfo ci) {
        if (!RecorderEyes.inPlayback()) {
            return;
        }
        try {
            LivingEntity subject = RecorderEyes.subject();
            if (subject == null) {
                RecorderEyes.shownLight(0.0F, 0.0F);
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            float brightness = minecraft.options.gamma().get().floatValue();
            float darknessOption = minecraft.options.darknessEffectScale().get().floatValue();
            float darkness = subject.getEffectBlendFactor(MobEffects.DARKNESS, partialTicks) * darknessOption;
            renderState.brightness = Math.max(0.0F, brightness - darkness);
            renderState.darknessEffectScale = this.calculateDarknessScale(subject, darkness, partialTicks) * darknessOption;
            float waterVision = RecorderEyes.waterVision(subject);
            if (subject.hasEffect(MobEffects.NIGHT_VISION)) {
                renderState.nightVisionEffectIntensity = GameRenderer.nightVisionScale(subject, partialTicks);
            } else if (waterVision > 0.0F && subject.hasEffect(MobEffects.CONDUIT_POWER)) {
                renderState.nightVisionEffectIntensity = waterVision;
            } else {
                renderState.nightVisionEffectIntensity = 0.0F;
            }
            RecorderEyes.shownLight(renderState.darknessEffectScale, renderState.nightVisionEffectIntensity);
        } catch (RuntimeException | LinkageError e) {
            comedymonkey.dailies.effects.RecorderOverlays.failOnce("the lightmap", e);
        }
    }
}
