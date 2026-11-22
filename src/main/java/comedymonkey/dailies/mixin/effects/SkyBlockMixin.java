package comedymonkey.dailies.mixin.effects;

import comedymonkey.dailies.effects.RecorderEyes;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Blindness and darkness take the sky away for the one who has them. In playback the camera's entity is whoever the
 * view rides with, so that is only through their eyes with Show HUD on, as the other screen effects; in third person or
 * with Show HUD off the sky stays.
 */
@Mixin(LevelRenderer.class)
public abstract class SkyBlockMixin {

    @Inject(method = "doesMobEffectBlockSky", at = @At("RETURN"), cancellable = true)
    private void dailies$skyOnlyThroughTheirEyes(Camera camera, CallbackInfoReturnable<Boolean> cir) {
        if (!RecorderEyes.inPlayback()) {
            return;
        }
        boolean blocked = cir.getReturnValueZ() && RecorderEyes.subject() == camera.getEntity();
        if (!blocked && cir.getReturnValueZ()) {
            cir.setReturnValue(false);
        }
        RecorderEyes.skyBlocked(blocked);
    }
}
