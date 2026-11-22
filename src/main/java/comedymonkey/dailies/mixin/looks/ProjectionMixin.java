package comedymonkey.dailies.mixin.looks;

import comedymonkey.dailies.looks.LooksRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A scene held to a frame shape is shown in the editor as that frame, whole, in the part of the window the panels
 * leave clear (specification chapter V, "The frame"). The world is drawn as ever and then made smaller and moved
 * aside, which is one more step on the end of the projection the world is drawn with, taken where the camera hands
 * it over for the frame. Allowed to be missing: {@link LooksRenderer#viewShift} then says there is none, and the frame
 * stays in the middle of the window with the panels over its edges.
 */
@Mixin(Camera.class)
public abstract class ProjectionMixin {

    @Inject(method = "extractRenderState", at = @At("RETURN"), require = 0)
    private void dailies$frameInTheClear(CameraRenderState cameraState, DeltaTracker deltaTracker, CallbackInfo ci) {
        double[] shift = LooksRenderer.projectionShift();
        if (shift != null) {
            Matrix4f shifted = new Matrix4f().translate((float) shift[1], (float) shift[2], 0.0F).scale((float) shift[0], (float) shift[0], 1.0F)
                .mul(cameraState.projectionMatrix);
            cameraState.projectionMatrix.set(shifted);
        }
    }

}
