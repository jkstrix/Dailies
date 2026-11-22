package comedymonkey.dailies.mixin.effects;

import comedymonkey.dailies.effects.ParticleWatch;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Where particles are made, for the self test's count of potion swirls ({@link ParticleWatch}); nothing while it is not watching */
@Mixin(ParticleEngine.class)
public abstract class ParticleWatchMixin {

    @Inject(method = "createParticle", at = @At("HEAD"))
    private void dailies$watch(ParticleOptions options, double x, double y, double z, double xa, double ya, double za,
                               CallbackInfoReturnable<?> cir) {
        ParticleWatch.made(options, x, y, z);
    }
}
