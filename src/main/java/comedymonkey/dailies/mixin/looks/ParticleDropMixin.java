package comedymonkey.dailies.mixin.looks;

import comedymonkey.dailies.looks.LooksRenderer;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Particles a scene hides (all of them, or chosen kinds) are turned away where they would be added, so they are
 * never made, moved or drawn (specification chapter V, "What is drawn").
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleDropMixin {

    @Inject(method = "createParticle", at = @At("HEAD"), cancellable = true)
    private void dailies$dropHiddenKinds(ParticleOptions particle, double x, double y, double z, double xSpeed, double ySpeed, double zSpeed,
                                         CallbackInfoReturnable<Particle> cir) {
        if (LooksRenderer.dropsParticle(particle)) {
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void dailies$dropAll(Particle particle, CallbackInfo ci) {
        if (LooksRenderer.dropsParticles()) {
            ci.cancel();
        }
    }

}
