package comedymonkey.dailies.mixin.renderers;

import comedymonkey.dailies.playback.Playback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Iris puts its frame counter and frame timer back to zero whenever it makes a pipeline for a world, and it makes one
 * after every change of the game's level. Playback changes the level on its own account, on a jump back or the start of
 * an export, though to anyone watching (and to a shader pack) it is one world going on. Left alone, the counter that
 * shader packs build their picture up by would start again in the middle of an export, the thrown-away frames of
 * {@code throwawayFrames} with it (specification REN-5), and the timer that animates water and wind would jump back to
 * nought (REN-4). While a recording is open both carry on instead. Outside playback Iris does as it always has.
 * Skipped without complaint when Iris (or Oculus, under its older names) is absent or has changed.
 */
@Pseudo
@Mixin(targets = {
    "net.irisshaders.iris.uniforms.SystemTimeUniforms$FrameCounter", "net.irisshaders.iris.uniforms.SystemTimeUniforms$Timer",
    "net.coderbot.iris.uniforms.SystemTimeUniforms$FrameCounter", "net.coderbot.iris.uniforms.SystemTimeUniforms$Timer"}, remap = false)
public abstract class IrisResetMixin {

    @Inject(method = "reset()V", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void dailies$keepCountingInPlayback(CallbackInfo ci) {
        if (Playback.active()) {
            ci.cancel();
        }
    }

}
