package comedymonkey.dailies.mixin.playback;

import comedymonkey.dailies.playback.Viewer;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The field of view the hand is drawn with goes through the same NeoForge event as the world's. Before 26.1 the event
 * said which it was; now the viewer is told here, so playback's own field of view is given to the world only.
 */
@Mixin(Camera.class)
public abstract class HudFovMixin {

    @Inject(method = "calculateHudFov(F)F", at = @At("HEAD"))
    private void dailies$hudFovStarts(float partialTicks, CallbackInfoReturnable<Float> cir) {
        Viewer.hudFov = true;
    }

    @Inject(method = "calculateHudFov(F)F", at = @At("RETURN"))
    private void dailies$hudFovEnds(float partialTicks, CallbackInfoReturnable<Float> cir) {
        Viewer.hudFov = false;
    }

}
