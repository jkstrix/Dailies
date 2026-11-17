package comedymonkey.dailies.mixin.moments;

import comedymonkey.dailies.moments.Moments;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAwardStatsPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The two packets the moments system uses for exact kill/death attribution: the player's own statistics and the
 * death screen message. Both handlers rethrow off the client thread before reaching RETURN, so these only run on it.
 */
@Mixin(ClientPacketListener.class)
public class MixinClientPacketListenerMoments {

    @Inject(method = "handleAwardStats", at = @At("RETURN"))
    public void dailies$momentsAwardStats(ClientboundAwardStatsPacket packet, CallbackInfo ci) {
        if (Minecraft.getInstance().isSameThread()) {
            Moments.onStats(packet.stats());
        }
    }

    @Inject(method = "handlePlayerCombatKill", at = @At("RETURN"))
    public void dailies$momentsCombatKill(ClientboundPlayerCombatKillPacket packet, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.isSameThread() && minecraft.player != null && packet.playerId() == minecraft.player.getId()) {
            Moments.onCombatKill(packet.message());
        }
    }

}
