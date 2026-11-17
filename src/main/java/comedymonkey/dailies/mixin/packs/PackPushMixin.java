package comedymonkey.dailies.mixin.packs;

import comedymonkey.dailies.packs.GamePacks;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Notes what a server said when it pushed a resource pack, in the configuration phase as well as in play (REC-19):
 * most servers push before there is a world, and so before any recording can be running.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class PackPushMixin {

    // After the hop to the client thread, which is the first thing the handler does
    @Inject(method = "handleResourcePackPush", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/common/ClientboundResourcePackPushPacket;id()Ljava/util/UUID;"))
    private void dailies$pushed(ClientboundResourcePackPushPacket packet, CallbackInfo ci) {
        GamePacks.pushPacket(packet);
    }

}
