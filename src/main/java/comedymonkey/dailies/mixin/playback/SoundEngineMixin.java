package comedymonkey.dailies.mixin.playback;

import comedymonkey.dailies.playback.Playback;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Nothing from a stretch of recording that is being skipped over is heard (specification PLAY-13) */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {

    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void dailies$silentWhileJumping(SoundInstance sound, CallbackInfo ci) {
        if (Playback.skipping()) {
            ci.cancel();
        }
    }

    @Inject(method = "playDelayed", at = @At("HEAD"), cancellable = true)
    private void dailies$silentWhileJumpingDelayed(SoundInstance sound, int delay, CallbackInfo ci) {
        if (Playback.skipping()) {
            ci.cancel();
        }
    }

}
