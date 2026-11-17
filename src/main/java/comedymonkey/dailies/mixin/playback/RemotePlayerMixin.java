package comedymonkey.dailies.mixin.playback;

import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * In playback the recorder is drawn as another player, and a game takes it that anything striking another player has
 * hurt them: an arrow that reaches one is taken out of the world there and then. The recorder's own game never
 * thought so of its own player and waited to be told, so an arrow turned by the recorder's shield bounced off and lay
 * on the ground in the live game, and vanished at the shield in playback. The recorded player answers as the
 * recorder's own game did.
 */
@Mixin(RemotePlayer.class)
public abstract class RemotePlayerMixin {

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void dailies$recorderWaitsToBeTold(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        RecordingManifest manifest = Playback.manifest();
        if (Playback.active() && manifest != null && ((Entity) (Object) this).getUUID().toString().equals(manifest.recorderId)) {
            cir.setReturnValue(false);
        }
    }

}
