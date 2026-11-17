package comedymonkey.dailies.capture;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.GameProtocols;
import net.neoforged.neoforge.network.connection.ConnectionType;

/**
 * Turns game packets into the bytes the game itself would put on the wire, and back. Recording uses it for the few
 * packets it writes itself; playback uses it for every packet it shows again.
 */
public final class GameCodec {

    private final StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> codec;
    private final RegistryAccess registries;
    private final ConnectionType connectionType;

    public GameCodec(RegistryAccess registries, ConnectionType connectionType) {
        this.registries = registries;
        this.connectionType = connectionType;
        this.codec = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registries, connectionType)).codec();
    }

    public RegistryAccess registries() {
        return this.registries;
    }

    public ConnectionType connectionType() {
        return this.connectionType;
    }

    public byte[] encode(Packet<? super ClientGamePacketListener> packet) {
        ByteBuf buffer = Unpooled.buffer(256);
        try {
            this.codec.encode(buffer, packet);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    public Packet<? super ClientGamePacketListener> decode(byte[] bytes, int from, int length) {
        return this.codec.decode(Unpooled.wrappedBuffer(bytes, from, length));
    }

    /** A buffer for writing a packet's fields by hand, for the packets that have no usable constructor on a client */
    public RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(256), this.registries, this.connectionType);
    }

}
