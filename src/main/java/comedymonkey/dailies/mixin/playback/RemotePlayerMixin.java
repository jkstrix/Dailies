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

    @Inject(method = "hurtClient(Lnet/minecraft/world/damagesource/DamageSource;)Z", at = @At("HEAD"), cancellable = true)
    private void dailies$recorderWaitsToBeTold(DamageSource source, CallbackInfoReturnable<Boolean> cir) {
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
    @org.spongepowered.asm.mixin.Unique
    private float dailies$bob = 0.0F;

    /**
     * Head bob when watching through a player's eyes (the owner, 18 November: "a toggle in the editor ui and for the head
     * bob to be simulated"). The game sways the view from how far its own player has walked and a bob that eases up while
     * walking on the ground; another player is moved by being put at each recorded place, so neither grows and the view
     * stood still. Here both are worked out from how far the watched player moved this tick, the way the game works them
     * out for its own player, and the game's own head bob draws from them. The scene's look head_bob turns it off
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void dailies$simulatedHeadBob(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        net.minecraft.client.player.RemotePlayer self = (net.minecraft.client.player.RemotePlayer) (Object) this;
        if (!Playback.active() || net.minecraft.client.Minecraft.getInstance().getCameraEntity() != self) {
            return;
        }
        boolean wanted = !comedymonkey.dailies.editor.EditorSession.open() || comedymonkey.dailies.editor.EditorSession.scene().looks().headBob;
        double moved = Math.min(1.0, Math.hypot(self.getX() - self.xo, self.getZ() - self.zo));
        // The walked distance of the tick before was carried over when the tick began (ClientAvatarState.tick)
        net.minecraft.client.entity.ClientAvatarState state = self.avatarState();
        comedymonkey.dailies.mixin.access.ClientAvatarStateAccess bobs = (comedymonkey.dailies.mixin.access.ClientAvatarStateAccess) state;
        bobs.dailies$setBobO(this.dailies$bob);
        if (!wanted) {
            this.dailies$bob = 0.0F;
        } else {
            state.addWalkDistance((float) moved * 0.6F);
            float step = self.onGround() && !self.isDeadOrDying() ? (float) Math.min(0.1, moved) : 0.0F;
            this.dailies$bob += (step - this.dailies$bob) * 0.4F;
        }
        bobs.dailies$setBob(this.dailies$bob);
        if (!wanted) {
            bobs.dailies$setBobO(0.0F);
        }
    }

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
