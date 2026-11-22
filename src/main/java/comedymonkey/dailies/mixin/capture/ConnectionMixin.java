package comedymonkey.dailies.mixin.capture;

import comedymonkey.dailies.capture.FrameTap;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets recording listen on every connection the game opens to a server, the one to its own built-in server included.
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin {

    @Inject(method = "configurePacketHandler", at = @At("TAIL"))
    private void dailies$listen(ChannelPipeline pipeline, CallbackInfo ci) {
        Connection self = (Connection) (Object) this;
        if (self.getReceiving() == PacketFlow.CLIENTBOUND) {
            FrameTap.install(pipeline, self);
        }
    }

}
