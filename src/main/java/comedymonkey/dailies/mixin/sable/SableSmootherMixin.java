package comedymonkey.dailies.mixin.sable;

import comedymonkey.dailies.compat.sable.SableCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * After a jump in playback, every recorded pose of a craft is older than the Sable smoother will accept, and it
 * would throw them all away. This lets the craft hold the last of them. Does nothing outside playback.
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.network.client.SubLevelSnapshotInterpolator", remap = false)
public abstract class SableSmootherMixin {

    @Inject(method = "tick(D)V", at = @At("HEAD"), remap = false, require = 0)
    private void dailies$holdAfterJump(double backTick, CallbackInfo ci) {
        SableCompat.beforeSmootherTick(this, backTick);
    }

}
