package comedymonkey.dailies.capture;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;

import java.util.ArrayList;
import java.util.List;

/**
 * Listens on the game's own connection without changing anything on it. One handler copies each packet's bytes as
 * they arrive; a second, further along, sees the packet they became. The pair is then handed to the client thread
 * in a task that runs just before the game's own handling of that packet, so capture sees packets in exactly the
 * order, and at exactly the moment, the game applies them.
 */
public final class FrameTap {

    private static final String FRAMES = "dailies_frames";
    private static final String PACKETS = "dailies_packets";

    private final List<byte[]> pending = new ArrayList<>();

    private FrameTap() {
    }

    /** Called once the game has built the pipeline of a connection to a server */
    public static void install(ChannelPipeline pipeline, Connection connection) {
        try {
            if (pipeline.get(FRAMES) != null || pipeline.get("packet_handler") == null) {
                return;
            }
            // Frames are whole and decompressed once they reach whichever handler turns them into packets
            String decoder = pipeline.get("decoder") != null ? "decoder" : pipeline.get("inbound_config") != null ? "inbound_config" : null;
            if (decoder == null) {
                return;
            }
            FrameTap tap = new FrameTap();
            pipeline.addBefore(decoder, FRAMES, tap.new Frames());
            pipeline.addBefore("packet_handler", PACKETS, tap.new Packets(connection));
        } catch (RuntimeException e) {
            comedymonkey.dailies.Host.LOGGER.error("Dailies cannot listen to this connection, so it cannot be recorded", e);
        }
    }

    private final class Frames extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
            if (message instanceof ByteBuf frame) {
                byte[] copy = new byte[frame.readableBytes()];
                frame.getBytes(frame.readerIndex(), copy);
                FrameTap.this.pending.add(copy);
            }
            super.channelRead(context, message);
        }
    }

    private final class Packets extends ChannelInboundHandlerAdapter {
        private final Connection connection;

        Packets(Connection connection) {
            this.connection = connection;
        }

        @Override
        public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
            if (message instanceof Packet<?> packet) {
                List<byte[]> pending = FrameTap.this.pending;
                if (!pending.isEmpty() && this.connection.getPacketListener() instanceof ClientGamePacketListener) {
                    List<byte[]> frames = new ArrayList<>(pending);
                    try {
                        Capture.arrived(packet, frames);
                        if (Capture.wantsApplied()) {
                            Minecraft.getInstance().execute(() -> Capture.applied(packet, frames));
                        }
                    } catch (Throwable t) {
                        Capture.failed(t);
                    }
                }
                pending.clear();
            }
            super.channelRead(context, message);
        }
    }

}
