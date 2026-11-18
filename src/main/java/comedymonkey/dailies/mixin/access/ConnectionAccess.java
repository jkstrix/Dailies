package comedymonkey.dailies.mixin.access;

import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Playback hands recorded packets straight to a listener, so it sets the listener without a protocol handshake */
@Mixin(Connection.class)
public interface ConnectionAccess {

    @Accessor("packetListener")
    void dailies$setPacketListener(PacketListener listener);

}
