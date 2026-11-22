package comedymonkey.dailies.mixin.looks;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import comedymonkey.dailies.looks.LooksRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A scene can switch the sky off (specification chapter V, "The world's look"): no sky, sun, moon or stars, and a
 * plain background colour in their place, for keying. The sky is the first thing drawn into a cleared picture, so
 * that is where the background colour goes in, as a step of its own in place of the sky's. Clouds go with the sky
 * through the cloud setting the game reads ({@link CloudsOptionMixin}).
 * <p>
 * Allowed to be missing, since other mods that draw the sky their own way may have replaced this method. The
 * priority puts it ahead of other mods' hooks at the same place.
 */
@Mixin(value = LevelRenderer.class, priority = 1500)
public abstract class SkyMixin {

    @Shadow
    @Final
    private LevelTargetBundle targets;

    @Inject(method = "addSkyPass", at = @At("HEAD"), cancellable = true, require = 0)
    private void dailies$noSky(FrameGraphBuilder frame, CameraRenderState cameraState, GpuBufferSlice skyFog, CallbackInfo ci) {
        if (LooksRenderer.skyOff()) {
            FramePass pass = frame.addPass("dailies_background");
            this.targets.main = pass.readsAndWrites(this.targets.main);
            pass.executes(LooksRenderer::replaceSky);
            ci.cancel();
        }
    }

}
