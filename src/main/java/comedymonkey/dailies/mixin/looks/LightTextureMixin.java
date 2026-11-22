package comedymonkey.dailies.mixin.looks;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import comedymonkey.dailies.looks.LooksRenderer;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A scene's night vision (specification chapter V, "The world's look"): the table the game looks brightness up in is
 * filled with full brightness, so everything is lit evenly with no potion and none of its tint. When the look is
 * switched off the game works the table out again as it always does.
 */
@Mixin(Lightmap.class)
public abstract class LightTextureMixin {

    @Shadow
    @Final
    private GpuTexture texture;

    @Unique
    private boolean dailies$fullBright = false;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void dailies$nightVision(LightmapRenderState state, CallbackInfo ci) {
        if (LooksRenderer.nightVision()) {
            if (!this.dailies$fullBright) {
                RenderSystem.getDevice().createCommandEncoder().clearColorTexture(this.texture, new Vector4f(1.0F, 1.0F, 1.0F, 1.0F));
                this.dailies$fullBright = true;
            }
            ci.cancel();
        } else if (this.dailies$fullBright) {
            this.dailies$fullBright = false;
            state.needsUpdate = true;
        }
    }

}
