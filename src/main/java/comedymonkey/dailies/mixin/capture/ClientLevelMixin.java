package comedymonkey.dailies.mixin.capture;

import comedymonkey.dailies.capture.Capture;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A server tells every player how far the cracks have spread on a block someone is mining, except the miner, whose
 * own game works it out. Recording has to see the recorder's own mining where the game shows it.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {

    @Inject(method = "destroyBlockProgress", at = @At("HEAD"))
    private void dailies$cracks(int breakerId, BlockPos pos, int progress, CallbackInfo ci) {
        Capture.cracks(breakerId, pos, progress);
    }

    /**
     * The same goes for what a player's own act sets off in the world: a block breaking up under their hand is shown by
     * their own game and sent by the server to everyone else. What a server sends arrives here with no player named.
     */
    @Inject(method = "levelEvent", at = @At("HEAD"))
    private void dailies$ownEvent(@Nullable Player player, int type, BlockPos pos, int data, CallbackInfo ci) {
        if (player != null) {
            Capture.ownEvent(player, type, pos, data);
        }
    }

    /**
     * And for the sounds a player makes: their footsteps, the block they put down, the door they open. Their own game
     * plays these for them, and the server sends them to everyone else. A sound the server sent comes through here too,
     * naming the game's own player; Capture tells the two apart (ServerSoundMixin).
     */
    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V", at = @At("HEAD"))
    private void dailies$ownSound(@Nullable Player player, double x, double y, double z, Holder<SoundEvent> sound, SoundSource source, float volume, float pitch, long seed,
                                  CallbackInfo ci) {
        if (player != null) {
            Capture.ownSound(player, null, x, y, z, sound, source, volume, pitch, seed);
        }
    }

    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
        at = @At("HEAD"))
    private void dailies$ownEntitySound(@Nullable Player player, Entity entity, Holder<SoundEvent> sound, SoundSource source, float volume, float pitch, long seed, CallbackInfo ci) {
        if (player != null) {
            Capture.ownSound(player, entity, entity.getX(), entity.getY(), entity.getZ(), sound, source, volume, pitch, seed);
        }
    }

}
