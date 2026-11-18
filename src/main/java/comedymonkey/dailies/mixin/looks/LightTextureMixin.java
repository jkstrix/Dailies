package comedymonkey.dailies.mixin.looks;

import com.mojang.blaze3d.platform.NativeImage;
import comedymonkey.dailies.looks.LooksRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
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
@Mixin(LightTexture.class)
public abstract class LightTextureMixin {

    @Shadow
    @Final
    private DynamicTexture lightTexture;

    @Shadow
    @Final
    private NativeImage lightPixels;

    @Shadow
    private boolean updateLightTexture;

    @Unique
    private boolean dailies$fullBright = false;

    @Inject(method = "updateLightTexture", at = @At("HEAD"), cancellable = true)
    private void dailies$nightVision(float partialTicks, CallbackInfo ci) {
        if (LooksRenderer.nightVision()) {
            if (!this.dailies$fullBright) {
                for (int y = 0; y < 16; y++) {
                    for (int x = 0; x < 16; x++) {
                        this.lightPixels.setPixelRGBA(x, y, 0xFFFFFFFF);
                    }
                }
                this.lightTexture.upload();
                this.dailies$fullBright = true;
            }
            this.updateLightTexture = false;
            ci.cancel();
        } else if (this.dailies$fullBright) {
            this.dailies$fullBright = false;
            this.updateLightTexture = true;
        }
    }

}
