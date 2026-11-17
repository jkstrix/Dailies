package comedymonkey.dailies.mixin.sound;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.audio.Library;
import comedymonkey.dailies.sound.ExportSound;
import comedymonkey.dailies.sound.VoicePlayback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.IntBuffer;

/**
 * While an export captures sound, the game's sound engine opens an OpenAL loopback device in place of the speakers
 * (specification EXP-3): the game plays as usual, nothing is heard, and the export takes the samples. At any other
 * time the game opens its device exactly as it always does.
 */
@Mixin(Library.class)
public abstract class LibraryMixin {

    @Shadow
    private long currentDevice;

    @WrapOperation(method = "init", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/audio/Library;openDeviceOrFallback(Ljava/lang/String;)J"))
    private long dailies$openLoopback(String deviceName, Operation<Long> original) {
        long device = ExportSound.openLoopback();
        return device != 0L ? device : original.call(deviceName);
    }

    @WrapOperation(method = "init", at = @At(value = "INVOKE", target = "Lorg/lwjgl/openal/ALC10;alcCreateContext(JLjava/nio/IntBuffer;)J", remap = false))
    private long dailies$loopbackFormat(long device, IntBuffer attributes, Operation<Long> original) {
        // A loopback device has to be told the format its samples will be asked for in
        return original.call(device, ExportSound.isLoopback(device) ? ExportSound.contextAttributes() : attributes);
    }

    @Inject(method = "setHrtf", at = @At("HEAD"), cancellable = true)
    private void dailies$leaveLoopbackAlone(boolean enabled, CallbackInfo ci) {
        // Resetting a loopback device without its format fails; an export is plain stereo
        if (ExportSound.isLoopback(this.currentDevice)) {
            ci.cancel();
        }
    }

    @Inject(method = "cleanup", at = @At("HEAD"))
    private void dailies$deviceClosing(CallbackInfo ci) {
        VoicePlayback.contextClosing();
        ExportSound.deviceClosing(this.currentDevice);
    }

}
