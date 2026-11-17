package comedymonkey.dailies.mixin.capture;

import comedymonkey.dailies.capture.Capture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A sound the server sends is played by naming the game's own player, as a sound of the player's own making is
 * (ClientLevelMixin). The one is in the recording already as the packet it came in, so Capture is told of it first and
 * does not write it twice. Each handler hands itself to the client thread before it plays anything; only that run counts.
 */
@Mixin(ClientPacketListener.class)
public abstract class ServerSoundMixin {

    @Inject(method = "handleSoundEvent", at = @At("HEAD"))
    private void dailies$serverSound(ClientboundSoundPacket packet, CallbackInfo ci) {
        if (Minecraft.getInstance().isSameThread()) {
            Capture.soundFromServer(packet.getSeed());
        }
    }

    @Inject(method = "handleSoundEntityEvent", at = @At("HEAD"))
    private void dailies$serverEntitySound(ClientboundSoundEntityPacket packet, CallbackInfo ci) {
        if (Minecraft.getInstance().isSameThread()) {
            Capture.soundFromServer(packet.getSeed());
        }
    }

}
