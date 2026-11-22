package comedymonkey.dailies.mixin.effects;

import comedymonkey.dailies.playback.Playback;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Invisible entities are as invisible in playback as they were to the recorder, and come back as the recorder saw them
 * come back when it wears off. The viewer is a spectator, who would see them as ghosts; they are judged by the recorder
 * instead (a spectator recorder saw them, a teammate may, the rest did not). What they wear and hold still shows, as it
 * does in the game.
 */
@Mixin(Entity.class)
public abstract class InvisibleMixin {

    @Inject(method = "isInvisibleTo", at = @At("HEAD"), cancellable = true)
    private void dailies$asTheRecorderSawIt(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (!Playback.loaded()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (player != minecraft.player || minecraft.level == null) {
            return;
        }
        Entity self = (Entity) (Object) this;
        Player recorder = null;
        try {
            var manifest = Playback.manifest();
            if (manifest != null && manifest.recorderId != null && !manifest.recorderId.isEmpty()) {
                recorder = minecraft.level.getPlayerByUUID(UUID.fromString(manifest.recorderId));
            }
        } catch (IllegalArgumentException e) {
            recorder = null;
        }
        cir.setReturnValue(recorder != null && recorder != self ? self.isInvisibleTo(recorder) : self.isInvisible());
    }
}
