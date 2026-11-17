package comedymonkey.dailies.mixin.looks;

import com.mojang.blaze3d.vertex.PoseStack;
import comedymonkey.dailies.looks.LooksRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A scene can switch the sky off (specification chapter V, "The world's look"): no sky, sun, moon, stars or clouds,
 * and a plain background colour in their place, for keying. The sky is the first thing drawn into a cleared
 * picture, so that is where the background colour goes in.
 * <p>
 * These two hooks are allowed to be missing, since other mods that draw the sky or clouds their own way may have
 * replaced these methods. The priority puts them ahead of other mods' hooks at the same place.
 */
@Mixin(value = LevelRenderer.class, priority = 1500)
public abstract class SkyMixin {

    @Inject(method = "renderSky", at = @At("HEAD"), cancellable = true, require = 0)
    private void dailies$noSky(Matrix4f frustumMatrix, Matrix4f projectionMatrix, float partialTick, Camera camera, boolean foggy, Runnable skyFog,
                               CallbackInfo ci) {
        if (LooksRenderer.replaceSky()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true, require = 0)
    private void dailies$noClouds(PoseStack poseStack, Matrix4f frustumMatrix, Matrix4f projectionMatrix, float partialTick, double x, double y, double z,
                                  CallbackInfo ci) {
        if (LooksRenderer.skyOff()) {
            ci.cancel();
        }
    }

}
