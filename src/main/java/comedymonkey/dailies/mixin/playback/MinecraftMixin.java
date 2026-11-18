package comedymonkey.dailies.mixin.playback;

import comedymonkey.dailies.playback.Playback;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The three places playback takes hold of the game's clock: the start of every frame (where jumps are carried out),
 * the pause flag (a paused recording is a paused game), and how long a tick lasts (the playback speed).
 * None of them does anything unless a recording is open.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Shadow
    private volatile boolean pause;

    @Inject(method = "runTick", at = @At("HEAD"))
    private void dailies$frameStart(boolean renderLevel, CallbackInfo ci) {
        Playback.frameStart();
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/DeltaTracker$Timer;updatePauseState(Z)V"))
    private void dailies$pause(boolean renderLevel, CallbackInfo ci) {
        if (Playback.ownsClock()) {
            this.pause = Playback.gamePaused();
        }
    }

    @Inject(method = "getTickTargetMillis", at = @At("HEAD"), cancellable = true)
    private void dailies$tickLength(float defaultValue, CallbackInfoReturnable<Float> cir) {
        if (Playback.ownsClock()) {
            cir.setReturnValue(Playback.millisPerTick());
        }
    }

}
