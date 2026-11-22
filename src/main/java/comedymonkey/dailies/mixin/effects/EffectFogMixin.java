package comedymonkey.dailies.mixin.effects;

import comedymonkey.dailies.effects.RecorderEyes;
import net.minecraft.client.renderer.fog.environment.MobEffectFogEnvironment;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.material.FogType;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The fog of darkness and blindness follows the camera's entity, which in playback is whoever the view rides with: it
 * is shown only through their eyes with Show HUD on, as the other screen effects are, never in third person
 */
@Mixin(MobEffectFogEnvironment.class)
public abstract class EffectFogMixin {

    @Inject(method = "isApplicable", at = @At("HEAD"), cancellable = true)
    private void dailies$onlyThroughTheirEyes(@Nullable FogType fogType, Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (RecorderEyes.inPlayback() && RecorderEyes.subject() != entity) {
            cir.setReturnValue(false);
        }
    }
}
