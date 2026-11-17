package comedymonkey.dailies.mixin.capture;

import comedymonkey.dailies.capture.LightPasses;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The moment the game works out what its changed blocks do to the light: the end of each light pass, written down
 * while recording and held back in playback except where the recording says (capture.LightPasses)
 */
@Mixin(LevelLightEngine.class)
public abstract class LevelLightEngineMixin {

    @Inject(method = "runLightUpdates", at = @At("HEAD"), cancellable = true)
    private void dailies$lightPass(CallbackInfoReturnable<Integer> cir) {
        if (LightPasses.beforeRun((LevelLightEngine) (Object) this)) {
            cir.setReturnValue(0);
        }
    }

}
