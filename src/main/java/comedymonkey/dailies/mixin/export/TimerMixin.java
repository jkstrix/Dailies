package comedymonkey.dailies.mixin.export;

import comedymonkey.dailies.export.Export;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * During an export the game's clock does not follow real time at all. Each frame is drawn at an exact point of the
 * recording, worked out from the frame's number, however long the frame before took (specification EXP-2).
 */
@Mixin(DeltaTracker.Timer.class)
public abstract class TimerMixin {

    @Shadow
    private float deltaTicks;
    @Shadow
    private float deltaTickResidual;
    @Shadow
    private long lastMs;

    @Inject(method = "advanceGameTime", at = @At("HEAD"), cancellable = true)
    private void dailies$exactTime(long timeMillis, CallbackInfoReturnable<Integer> cir) {
        if (Export.holdsClock()) {
            // The export runs the ticks it needs itself; here the game is only told how far between two ticks the frame lies
            this.deltaTicks = Export.ticksPerFrame();
            this.deltaTickResidual = Export.partialTick();
            this.lastMs = timeMillis;
            cir.setReturnValue(0);
        }
    }

}
