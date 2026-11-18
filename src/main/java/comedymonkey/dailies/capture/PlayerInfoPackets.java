package comedymonkey.dailies.capture;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Entry;

import java.util.Collection;
import java.util.EnumSet;

/**
 * Builds the tab-list packet from plain entries. The game only offers constructors that take server-side players,
 * so the fields are written in the packet's own order and read back through its codec.
 */
public final class PlayerInfoPackets {

    private PlayerInfoPackets() {
    }

    /** Everything about a player except the chat-signing session, which a recording must never hold */
    public static EnumSet<Action> allButChat() {
        return EnumSet.of(Action.ADD_PLAYER, Action.UPDATE_GAME_MODE, Action.UPDATE_LISTED, Action.UPDATE_LATENCY, Action.UPDATE_DISPLAY_NAME);
    }

    public static ClientboundPlayerInfoUpdatePacket build(GameCodec codec, EnumSet<Action> actions, Collection<Entry> entries) {
        if (actions.contains(Action.INITIALIZE_CHAT)) {
            throw new IllegalArgumentException("Chat sessions are never written");
        }
        RegistryFriendlyByteBuf buffer = codec.buffer();
        try {
            buffer.writeEnumSet(actions, Action.class);
            buffer.writeCollection(entries, (target, entry) -> {
                target.writeUUID(entry.profileId());
                for (Action action : actions) {
                    switch (action) {
                        case ADD_PLAYER -> {
                            target.writeUtf(entry.profile().getName(), 16);
                            ByteBufCodecs.GAME_PROFILE_PROPERTIES.encode(target, entry.profile().getProperties());
                        }
                        case UPDATE_GAME_MODE -> target.writeVarInt(entry.gameMode().getId());
                        case UPDATE_LISTED -> target.writeBoolean(entry.listed());
                        case UPDATE_LATENCY -> target.writeVarInt(entry.latency());
                        case UPDATE_DISPLAY_NAME -> FriendlyByteBuf.writeNullable((RegistryFriendlyByteBuf) target, entry.displayName(), ComponentSerialization.TRUSTED_STREAM_CODEC);
                        default -> {
                        }
                    }
                }
            });
            return ClientboundPlayerInfoUpdatePacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

}
