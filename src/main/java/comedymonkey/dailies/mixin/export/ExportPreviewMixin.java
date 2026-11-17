package comedymonkey.dailies.mixin.export;

import com.mojang.blaze3d.pipeline.RenderTarget;
import comedymonkey.dailies.export.Export;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * While an export draws at its own resolution, the window on the desktop shows each picture scaled to fit, not a
 * corner of a huge one or a postage stamp of a small one.
 */
@Mixin(Minecraft.class)
public abstract class ExportPreviewMixin {

    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;blitToScreen(II)V"))
    private void dailies$fitToWindow(RenderTarget target, int width, int height) {
        if (Export.holdsWindow()) {
            int[] fitted = Export.previewSize(width, height);
            target.blitToScreen(fitted[0], fitted[1]);
        } else {
            target.blitToScreen(width, height);
        }
    }

}
