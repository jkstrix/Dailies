package comedymonkey.dailies.mixin.effects;

import comedymonkey.dailies.effects.RecorderEyes;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Blindness and darkness take the sky away for the one who has them. In playback the camera's entity is whoever the
 * view rides with, so that is only through their eyes with Show HUD on, as the other screen effects; in third person or
 * with Show HUD off the sky stays.
 */
@Mixin(Camera.class)
public abstract class SkyBlockMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void dailies$skyOnlyThroughTheirEyes(CameraRenderState cameraState, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (RecorderEyes.inPlayback() && cameraState.entityRenderState.doesMobEffectBlockSky
            && RecorderEyes.subject() != ((Camera) (Object) this).entity()) {
            cameraState.entityRenderState.doesMobEffectBlockSky = false;
        }
        if (RecorderEyes.inPlayback()) {
            RecorderEyes.skyBlocked(cameraState.entityRenderState.doesMobEffectBlockSky);
        }
    }
}
