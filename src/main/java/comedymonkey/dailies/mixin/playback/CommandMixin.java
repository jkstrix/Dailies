package comedymonkey.dailies.mixin.playback;

import comedymonkey.dailies.command.DailiesCommand;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While a recording is watched, a typed command goes nowhere unless it is "/dailies", which the game runs on this
 * computer (specification PLAY-4). In a live world nothing here does anything.
 */
@Mixin(ClientPacketListener.class)
public abstract class CommandMixin {

    @Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
    private void dailies$ownCommandsOnly(String command, CallbackInfo ci) {
        if (DailiesCommand.turnedAway(command)) {
            ci.cancel();
        }
    }

    @Inject(method = "sendUnsignedCommand", at = @At("HEAD"), cancellable = true)
    private void dailies$ownUnsignedCommandsOnly(String command, CallbackInfoReturnable<Boolean> cir) {
        if (DailiesCommand.turnedAway(command)) {
            cir.setReturnValue(true);
        }
    }

}
