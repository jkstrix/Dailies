package comedymonkey.dailies.mixin.renderers;

import com.mojang.blaze3d.shaders.Uniform;
import comedymonkey.dailies.export.Export;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Veil, which Create Aeronautics draws some of its effects with, gives the game's shaders a "VeilRenderTime" uniform
 * read from the real clock as each shader's usual uniforms are set. While an export holds the clock it is set again,
 * just before the shader is used, from the export's own clock, so those effects move at the video's pace and not at
 * the pace the export happens to run (specification REN-4). A shader without the uniform costs one look-up while an
 * export runs and nothing otherwise.
 */
@Mixin(ShaderInstance.class)
public abstract class ShaderTimeMixin {

    @Inject(method = "apply", at = @At("HEAD"), require = 0)
    private void dailies$exportClock(CallbackInfo ci) {
        if (Export.holdsClock()) {
            Uniform time = ((ShaderInstance) (Object) this).getUniform("VeilRenderTime");
            if (time != null) {
                time.set((Export.frameClockMillis() % 3_600_000L) / 1000.0F);
            }
        }
    }

}
