package comedymonkey.dailies.mixin.export;

import com.mojang.blaze3d.platform.Window;
import comedymonkey.dailies.export.Export;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While an export is drawing frames at its own resolution, the size of the real window on the desktop must not
 * reach the game: resizing or minimising the window would otherwise change the size of the pictures half way through.
 */
@Mixin(Window.class)
public abstract class WindowMixin {

    @Inject(method = "onFramebufferResize", at = @At("HEAD"), cancellable = true)
    private void dailies$keepExportSize(int width, int height, CallbackInfo ci) {
        if (Export.holdsWindow()) {
            Export.windowResized(width, height);
            ci.cancel();
        }
    }

}
