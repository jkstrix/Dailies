package comedymonkey.dailies.mixin.sable;

import comedymonkey.dailies.compat.sable.SableCompat;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sable sends the poses of its physics craft over a channel of its own, which a recording of the normal connection never sees. This records
 * each message where the Sable client takes it in. Skipped without complaint when Sable is absent or has changed.
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.network.packets.ClientboundSableSnapshotDualPacket", remap = false)
public abstract class SableMotionMixin {

    @Inject(method = "handleClient(Lnet/minecraft/world/level/Level;)V", at = @At("HEAD"), remap = false, require = 0)
    private void dailies$record(Level level, CallbackInfo ci) {
        SableCompat.motion(this);
    }

    @Inject(method = "handle", at = @At("HEAD"), remap = false, require = 0)
    private void dailies$normalStart(CallbackInfo ci) {
        SableCompat.arrivingNormally(true);
    }

    @Inject(method = "handle", at = @At("RETURN"), remap = false, require = 0)
    private void dailies$normalEnd(CallbackInfo ci) {
        SableCompat.arrivingNormally(false);
    }

}
