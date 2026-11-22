package comedymonkey.dailies.mixin.looks;

import comedymonkey.dailies.looks.LooksRenderer;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The game asks for the field of view twice a frame, once for the world and once for the held item, through the same
 * event. Only the world's is fitted to a scene's frame ({@link LooksRenderer}): this says which of the two is being
 * worked out.
 */
@Mixin(Camera.class)
public abstract class CameraHudFovMixin {

    @Inject(method = "calculateHudFov", at = @At("HEAD"))
    private void dailies$heldItemFovStarts(float partialTicks, CallbackInfoReturnable<Float> cir) {
        LooksRenderer.heldItemFov(true);
    }

    @Inject(method = "calculateHudFov", at = @At("RETURN"))
    private void dailies$heldItemFovEnds(float partialTicks, CallbackInfoReturnable<Float> cir) {
        LooksRenderer.heldItemFov(false);
    }

}
