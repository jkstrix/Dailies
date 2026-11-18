package comedymonkey.dailies.mixin.sound;

import comedymonkey.dailies.sound.ExportSound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import com.mojang.blaze3d.audio.SoundBuffer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * The game reads sound files in the background and starts each sound when its file is in. An export that captures
 * sound waits for those files before it takes a frame's samples, so a sound begins on the frame it was played on
 * and not whenever the disk was ready. Outside such an export nothing is noted.
 */
@Mixin(SoundBufferLibrary.class)
public abstract class SoundBufferLibraryMixin {

    @Inject(method = "getCompleteBuffer", at = @At("RETURN"))
    private void dailies$noteBuffer(ResourceLocation location, CallbackInfoReturnable<CompletableFuture<SoundBuffer>> cir) {
        ExportSound.loading(cir.getReturnValue());
    }

    @Inject(method = "getStream", at = @At("RETURN"))
    private void dailies$noteStream(ResourceLocation location, boolean looping, CallbackInfoReturnable<CompletableFuture<AudioStream>> cir) {
        ExportSound.loading(cir.getReturnValue());
    }

}
