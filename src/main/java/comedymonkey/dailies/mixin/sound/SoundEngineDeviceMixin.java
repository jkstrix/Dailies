package comedymonkey.dailies.mixin.sound;

import comedymonkey.dailies.sound.ExportSound;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The game looks every second whether a better sound device has turned up and restarts its sound if so. While an
 * export holds the loopback device that would cut every sound off, so the check is switched off until the export ends.
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineDeviceMixin {

    @Inject(method = "shouldChangeDevice", at = @At("HEAD"), cancellable = true)
    private void dailies$keepLoopback(CallbackInfoReturnable<Boolean> cir) {
        if (ExportSound.holdsDevice()) {
            cir.setReturnValue(false);
        }
    }

}
