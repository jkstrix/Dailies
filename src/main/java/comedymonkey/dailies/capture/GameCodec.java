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

    private byte[] payloadPrefix = null;

    /**
     * A mod message as a whole packet, written from the message's own codec. For messages a mod keeps off the normal
     * connection, which the game's packet codec may not know how to write.
     */
    public byte[] payloadFrame(net.minecraft.resources.ResourceLocation id, java.util.function.Consumer<RegistryFriendlyByteBuf> body) {
        if (this.payloadPrefix == null) {
            // Every custom payload packet starts with the same packet number; take it from one the game can always write
            byte[] sample = this.encode(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(new net.minecraft.network.protocol.common.custom.BrandPayload("")));
            int length = 1;
            while ((sample[length - 1] & 0x80) != 0) {
                length++;
            }
            this.payloadPrefix = java.util.Arrays.copyOf(sample, length);
        }
        RegistryFriendlyByteBuf buffer = this.buffer();
        try {
            buffer.writeBytes(this.payloadPrefix);
            buffer.writeResourceLocation(id);
            body.accept(buffer);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    /** A buffer for writing a packet's fields by hand, for the packets that have no usable constructor on a client */
    public RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(256), this.registries, this.connectionType);
    }

}
