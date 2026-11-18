package comedymonkey.dailies.mixin.export;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.Tesselator;
import comedymonkey.dailies.export.Export;
import comedymonkey.dailies.ui.ExportProgress;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * While an export draws at its own resolution, the window on the desktop does not show the picture as it is: a still
 * picture is shown scaled to fit, not a corner of a huge one or a postage stamp of a small one, and while a video is
 * exported the window shows only the progress panel, drawn in its own pixels so it is whole at any size. During a video
 * export the panel is drawn and the window shown only a few dozen times a second ({@link Export#windowFrameDue}): the
 * rest of the frames go straight on to the next.
 */
@Mixin(Minecraft.class)
public abstract class ExportPreviewMixin {

    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;blitToScreen(II)V"))
    private void dailies$fitToWindow(RenderTarget target, int width, int height) {
        if (Export.holdsClock()) {
            if (Export.windowFrameDue()) {
                ExportProgress.showInWindow();
            }
        } else if (Export.holdsWindow()) {
            int[] fitted = Export.previewSize(width, height);
            target.blitToScreen(fitted[0], fitted[1]);
        } else {
            target.blitToScreen(width, height);
        }
    }

    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;updateDisplay()V"), require = 0)
    private void dailies$showWhenDue(Window window) {
        if (Export.windowThisFrame()) {
            window.updateDisplay();
            return;
        }
        // Not shown this frame: only what the game does at every frame besides showing it, the calls queued for this
        // thread and the shared vertex buffer let go of
        RenderSystem.replayQueue();
        Tesselator.getInstance().clear();
    }

}
