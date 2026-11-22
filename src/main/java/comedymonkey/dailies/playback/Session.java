package comedymonkey.dailies.playback;

import com.mojang.authlib.GameProfile;
import comedymonkey.dailies.capture.GameCodec;
import comedymonkey.dailies.capture.WorldData;
import comedymonkey.dailies.mixin.access.ConnectionAccess;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.CommonListenerCookie;
import net.minecraft.client.multiplayer.RegistryDataCollector;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.ServerLinks;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.world.flag.FeatureFlags;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The game's side of a connection with nothing on the other end. Recorded packets are handed to its listener
 * directly, at the recorded times; whatever the game tries to send back is thrown away. Because there is no server
 * of any kind, nothing in a playback world can generate terrain, spawn mobs or run a machine's server logic.
 */
final class Session {

    /** The person watching. Never the recorder's own profile: two entities cannot share an id */
    static final GameProfile VIEWER = new GameProfile(UUID.nameUUIDFromBytes("Dailies viewer".getBytes(StandardCharsets.UTF_8)), "Viewer");

    final Connection connection;
    final ClientPacketListener listener;
    final GameCodec codec;
    /** The name of the shared data the registries came from */
    final String worldName;
    private final EmbeddedChannel channel;

    private Session(Connection connection, ClientPacketListener listener, GameCodec codec, String worldName, EmbeddedChannel channel) {
        this.connection = connection;
        this.listener = listener;
        this.codec = codec;
        this.worldName = worldName;
        this.channel = channel;
    }

    private static String cachedWorld = null;
    private static RegistryAccess.Frozen cachedRegistries = null;

    static Session create(Minecraft minecraft, String worldName, WorldData data) {
        // Numbered as the recording's game numbered them before anything is read: the tags below name their members by
        // number, and every packet after names its sounds, particles and blocks so (PLAY-25)
        RegistryNumbering.use(worldName, data);
        RegistryAccess.Frozen registries;
        if (worldName.equals(cachedWorld)) {
            registries = cachedRegistries;
        } else {
            registries = registries(data);
            cachedWorld = worldName;
            cachedRegistries = registries;
        }

        Connection connection = new Connection(PacketFlow.CLIENTBOUND);
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("dailies_sink", new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                ReferenceCountUtil.release(message);
                promise.trySuccess();
            }
        });
        channel.pipeline().addLast("packet_handler", connection);
        channel.pipeline().fireChannelActive();
        // Every mod message this game can read is accepted, as if a server with the same mods had agreed to it
        NetworkRegistry.configureMockConnection(connection);

        ClientPacketListener listener = new ClientPacketListener(minecraft, connection, new CommonListenerCookie(VIEWER,
            minecraft.getTelemetryManager().createWorldSessionManager(false, null, null), registries, FeatureFlags.REGISTRY.fromNames(data.features),
            "dailies", null, null, Map.of(), null, false, Map.of(), ServerLinks.EMPTY, data.connectionType));
        ((ConnectionAccess) connection).dailies$setPacketListener(listener);
        return new Session(connection, listener, new GameCodec(registries, data.connectionType), worldName, channel);
    }

    private static RegistryAccess.Frozen registries(WorldData data) {
        RegistryDataCollector collector = new RegistryDataCollector();
        data.registries.forEach((location, entries) -> collector.appendContents(WorldData.registryKey(location), entries));
        Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> tags = new HashMap<>();
        data.tags.forEach((location, members) -> {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                buffer.writeMap(members, FriendlyByteBuf::writeIdentifier, FriendlyByteBuf::writeIntIdList);
                tags.put(WorldData.registryKey(location), TagNetworkSerialization.NetworkPayload.read(buffer));
            } finally {
                buffer.release();
            }
        });
        collector.appendTags(tags);
        return collector.collectGameRegistries(location -> Optional.empty(), RegistryAccess.EMPTY, false);
    }

    static void forgetRegistries() {
        cachedWorld = null;
        cachedRegistries = null;
    }

    void close() {
        try {
            this.channel.close();
        } catch (RuntimeException ignored) {
            // Nothing is listening
        }
    }

}
