package comedymonkey.dailies.mixin.playback;

import comedymonkey.dailies.editor.StretchPreview;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No particles pile up from a stretch of recording that is being skipped over (specification PLAY-13), nor from one the
 * path preview plays at twenty times its speed while nothing of the world is drawn (EDIT-8)
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {

    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void dailies$noneWhileJumping(Particle particle, CallbackInfo ci) {
        if (Playback.skipping() || StretchPreview.running()) {
            ci.cancel();
        }
    }

}
