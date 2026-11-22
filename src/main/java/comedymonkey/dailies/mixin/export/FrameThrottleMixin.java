package comedymonkey.dailies.mixin.export;

import comedymonkey.dailies.export.Export;
import comedymonkey.dailies.playback.Playback;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The game drops to 30 frames a second after a minute without a key or the mouse, and to 10 while its window is
 * minimised. Neither is idleness while a video is exported (often in a minimised or hidden window) or a recording is
 * watched hands off, so neither throttle applies then; the frame limit the export or the player set still does.
 */
@Mixin(FramerateLimitTracker.class)
public abstract class FrameThrottleMixin {

    @Inject(method = "getThrottleReason", at = @At("HEAD"), cancellable = true)
    private void dailies$noThrottleWhileWorking(CallbackInfoReturnable<FramerateLimitTracker.FramerateThrottleReason> cir) {
        if (Export.active() || Playback.loaded()) {
            cir.setReturnValue(FramerateLimitTracker.FramerateThrottleReason.NONE);
        }
    }
}
