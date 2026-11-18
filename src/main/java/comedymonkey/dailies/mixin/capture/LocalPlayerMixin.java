package comedymonkey.dailies.mixin.capture;

import comedymonkey.dailies.capture.Capture;
import net.minecraft.client.player.LocalPlayer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.ThreadLocalRandom;

/**
 * A server never tells a player about their own arm swings, or the sounds their own body makes (footsteps, landing,
 * eating, splashing), so recording has to see them where they start. The game plays those sounds straight to its own
 * speakers here, without passing through the level's seeded sounds that other players' sounds arrive by.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {

    @Inject(method = "swing", at = @At("HEAD"))
    private void dailies$swing(InteractionHand hand, CallbackInfo ci) {
        Capture.ownSwing((LocalPlayer) (Object) this, hand);
    }


    @WrapOperation(method = "playSound(Lnet/minecraft/sounds/SoundEvent;FF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;playLocalSound(DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFZ)V"))
    private void dailies$ownSound(Level level, double x, double y, double z, SoundEvent sound, SoundSource source, float volume, float pitch, boolean distanceDelay,
                                  Operation<Void> original) {
        original.call(level, x, y, z, sound, source, volume, pitch, distanceDelay);
        if (!Capture.capturing()) {
            return;
        }
        // The seed picks which of the sound's variants playback plays. It is drawn from a random of its own: drawing from
        // the level's, for every footstep, changed what the game itself went on to pick at random
        LocalPlayer self = (LocalPlayer) (Object) this;
        Capture.ownSound(self, null, x, y, z, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), source, volume, pitch, ThreadLocalRandom.current().nextLong());
    }

}
