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

    /**
     * A dead recorder goes in a puff of smoke once the fall is over, a second after dying, as every other player saw it:
     * the server takes a dead player out of the others' worlds then. The recorder's own game was never told to, its player
     * lies under the death screen until the respawn, and so did the recorder in playback, for as long as the death screen
     * stayed up (the scripted test of falling into the void, 18 November)
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void dailies$deadRecorderGoes(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        net.minecraft.client.player.RemotePlayer self = (net.minecraft.client.player.RemotePlayer) (Object) this;
        // Nineteen: the recorder's death reaches playback with the recorded state of their own player, a tick after their
        // own game took it, so the live game's twentieth tick of dying is playback's nineteenth (seen in the void test)
        if (self.deathTime < 19 || self.isRemoved() || !Playback.active()) {
            return;
        }
        RecordingManifest manifest = Playback.manifest();
        if (manifest != null && self.getUUID().toString().equals(manifest.recorderId)) {
            self.handleEntityEvent((byte) 60);
            self.remove(Entity.RemovalReason.KILLED);
        }
    }

}
