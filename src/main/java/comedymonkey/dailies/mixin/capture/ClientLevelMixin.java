package comedymonkey.dailies.mixin.capture;

import comedymonkey.dailies.capture.Capture;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import net.minecraft.client.Minecraft;
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

}
