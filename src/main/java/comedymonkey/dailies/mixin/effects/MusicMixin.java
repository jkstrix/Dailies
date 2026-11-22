package comedymonkey.dailies.mixin.effects;

import comedymonkey.dailies.export.Export;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.MusicManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No background music over a recording being watched or exported: the game would pick music for the playback world as
 * if it were being played. What plays stops as a recording opens; the recording's own sounds (jukeboxes, note blocks)
 * are world sounds and play on. Music comes back after closing, as the game chooses.
 */
@Mixin(MusicManager.class)
public abstract class MusicMixin {

    @Shadow
    private SoundInstance currentMusic;

    @Shadow
    public abstract void stopPlaying();

    // Nothing starts while a recording is open or exporting, whoever asks
    @Inject(method = "startPlaying", at = @At("HEAD"), cancellable = true)
    private void dailies$noNewMusicInPlayback(net.minecraft.sounds.Music music, CallbackInfo ci) {
        if (Playback.active() || Export.active()) {
            ci.cancel();
        }
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void dailies$noMusicInPlayback(CallbackInfo ci) {
        if (Playback.active() || Export.active()) {
            if (this.currentMusic != null) {
                this.stopPlaying();
            }
            ci.cancel();
        }
    }
}
