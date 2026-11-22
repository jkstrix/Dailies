package comedymonkey.dailies.capture;

import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.flag.FeatureFlags;
import net.neoforged.neoforge.network.connection.ConnectionType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What a game needs to know before it can make sense of any recorded packet: the registries the server sent, their
 * tags, the enabled features, the kind of connection, and which number stands for which block, item and so on.
 * A recording made in one long session holds this once.
 */
public final class WorldData {

    private static final int VERSION = 1;

    public ConnectionType connectionType = ConnectionType.NEOFORGE;
    public final Map<Identifier, List<RegistrySynchronization.PackedRegistryEntry>> registries = new LinkedHashMap<>();
    /** By registry, the tags as the wire carries them: tag name to the numbers of its members */
    public final Map<Identifier, Map<Identifier, IntList>> tags = new LinkedHashMap<>();
    public final List<Identifier> features = new ArrayList<>();
    /** By built-in registry, its entries in number order. Packets refer to blocks, items and the rest by these numbers */
    public final Map<Identifier, List<Identifier>> ids = new LinkedHashMap<>();

    public static WorldData capture(ClientPacketListener listener) {
        WorldData data = new WorldData();
        RegistryAccess access = listener.registryAccess();
        data.connectionType = listener.getConnectionType();
        RegistrySynchronization.packRegistries(access.createSerializationContext(NbtOps.INSTANCE), access, Set.of(),
            (key, entries) -> data.registries.put(key.identifier(), entries));
        access.registries().forEach(entry -> {
            Map<Identifier, IntList> tags = tagsOf(entry.value());
            if (!tags.isEmpty()) {
                data.tags.put(entry.key().identifier(), tags);
            }
        });
        data.features.addAll(FeatureFlags.REGISTRY.toNames(listener.enabledFeatures()));
        for (Registry<?> registry : BuiltInRegistries.REGISTRY) {
            data.ids.put(registry.key().identifier(), idsOf(registry));
        }
        return data;
    }

    private static <T> Map<Identifier, IntList> tagsOf(Registry<T> registry) {
        Map<Identifier, IntList> tags = new TreeMap<>();
        registry.getTags().forEach(pair -> {
            IntList members = new IntArrayList(pair.getSecond().size());
            for (Holder<T> holder : pair.getSecond()) {
                members.add(registry.getId(holder.value()));
            }
            tags.put(pair.getFirst().location(), members);
        });
        return tags;
    }

    private static <T> List<Identifier> idsOf(Registry<T> registry) {
        List<Identifier> keys = new ArrayList<>(registry.size());
        for (int i = 0; i < registry.size(); i++) {
            T value = registry.byId(i);
            Identifier key = value == null ? null : registry.getKey(value);
            keys.add(key == null ? Identifier.fromNamespaceAndPath("dailies", "missing") : key);
        }
        return keys;
    }

    public byte[] toBytes() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer(64 * 1024));
        try {
            buffer.writeVarInt(VERSION);
            buffer.writeUtf(this.connectionType.name());
            buffer.writeMap(this.registries, FriendlyByteBuf::writeIdentifier,
                (target, entries) -> target.writeCollection(entries, RegistrySynchronization.PackedRegistryEntry.STREAM_CODEC));
            buffer.writeMap(this.tags, FriendlyByteBuf::writeIdentifier,
                (target, tags) -> target.writeMap(tags, FriendlyByteBuf::writeIdentifier, FriendlyByteBuf::writeIntIdList));
            buffer.writeCollection(this.features, FriendlyByteBuf::writeIdentifier);
            buffer.writeMap(this.ids, FriendlyByteBuf::writeIdentifier, (target, keys) -> target.writeCollection(keys, FriendlyByteBuf::writeIdentifier));
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    public static WorldData fromBytes(byte[] bytes) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        WorldData data = new WorldData();
        int version = buffer.readVarInt();
        if (version > VERSION) {
            throw new IllegalStateException("The recording describes its world in a newer form (" + version + ") than this version of Dailies reads");
        }
        String type = buffer.readUtf();
        for (ConnectionType known : ConnectionType.values()) {
            if (known.name().equals(type)) {
                data.connectionType = known;
            }
        }
        data.registries.putAll(buffer.readMap(FriendlyByteBuf::readIdentifier,
            source -> source.readList(RegistrySynchronization.PackedRegistryEntry.STREAM_CODEC)));
        data.tags.putAll(buffer.readMap(FriendlyByteBuf::readIdentifier,
            source -> source.readMap(FriendlyByteBuf::readIdentifier, FriendlyByteBuf::readIntIdList)));
        data.features.addAll(buffer.readList(FriendlyByteBuf::readIdentifier));
        data.ids.putAll(buffer.readMap(FriendlyByteBuf::readIdentifier, source -> source.readList(FriendlyByteBuf::readIdentifier)));
        return data;
    }

    public static ResourceKey<? extends Registry<?>> registryKey(Identifier location) {
        return ResourceKey.createRegistryKey(location);
    }

}
