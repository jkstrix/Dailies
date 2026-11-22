package comedymonkey.dailies.mixin.export;

import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import comedymonkey.dailies.export.Export;
import comedymonkey.dailies.export.WindowPicture;
import comedymonkey.dailies.ui.ExportProgress;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * While an export draws at its own resolution, the window on the desktop does not show the picture as it is: a still
 * picture is shown scaled to fit, not a corner of a huge one or a postage stamp of a small one, and while a video is
 * exported the window shows only the progress panel, sized in its own pixels so it is whole at any size. During a video
 * export the panel is drawn and the window shown only a few dozen times a second ({@link Export#windowFrameDue}): on
 * the rest of the frames the window's next picture is not even asked for, so nothing is drawn into it or shown.
 */
@Mixin(Minecraft.class)
public abstract class ExportPreviewMixin {

    @Redirect(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;acquireNextTexture()V"))
    private void dailies$showWhenDue(GpuSurface surface) throws SurfaceException {
        if (Export.holdsClock() && !Export.windowFrameDue()) {
            // Not shown this frame: the game draws the frame, and hands nothing to the window
            return;
        }
        surface.acquireNextTexture();
    }

    @Redirect(method = "renderFrame", at = @At(value = "INVOKE",
        target = "Lcom/mojang/renderpearl/api/device/GpuSurface;blitFromTexture(Lcom/mojang/renderpearl/api/commands/CommandEncoder;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V"))
    private void dailies$fitToWindow(GpuSurface surface, CommandEncoder encoder, GpuTextureView picture) {
        if (Export.holdsClock()) {
            if (!ExportProgress.showInWindow(surface, encoder)) {
                // The window has to be given something once it has been asked for a picture
                surface.blitFromTexture(encoder, picture);
            }
        } else if (Export.holdsWindow()) {
            WindowPicture.show(surface, encoder, picture, 0x000000);
        } else {
            surface.blitFromTexture(encoder, picture);
        }
    }

}
