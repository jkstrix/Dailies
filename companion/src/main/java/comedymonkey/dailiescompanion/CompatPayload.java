package comedymonkey.dailiescompanion;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Both ways on one shared channel, {@code dailies_companion:compat}: the data a server plugin (see
 * {@link CompanionPlugins}) has for one player, and a player's game asking a plugin for it. The wire format is a
 * varint kind ({@link #DATA} or {@link #ASK}), the plugin's id (up to 128 characters), a varint version and a byte
 * string (up to {@link #MAX_DATA} bytes), so a recorder can register the same channel itself and needs no copy of
 * this mod on the client.
 */
public record CompatPayload(int kind, String id, int version, byte[] data) implements CustomPacketPayload {
	/** server → client: a plugin's data; version is the plugin's, 0 when this server has no plugin of that id */
	public static final int DATA = 0;
	/** client → server: send me what this plugin has for me; version is the client half's, data is empty */
	public static final int ASK = 1;
	public static final int MAX_ID = 128, MAX_DATA = 262144;

	public static final Type<CompatPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(DailiesCompanion.MODID, "compat"));
	public static final StreamCodec<ByteBuf, CompatPayload> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, CompatPayload::kind,
		ByteBufCodecs.stringUtf8(MAX_ID), CompatPayload::id, ByteBufCodecs.VAR_INT, CompatPayload::version,
		ByteBufCodecs.byteArray(MAX_DATA), CompatPayload::data, CompatPayload::new);

	@Override
	public Type<CompatPayload> type() {
		return TYPE;
	}

	/** A request for a plugin's data, for a client half to send; a recorder with this mod beside it builds one here */
	public static CompatPayload ask(String id, int version) {
		return new CompatPayload(ASK, id, version, new byte[0]);
	}

	static void register(RegisterPayloadHandlersEvent e) {
		// optional: players without a recorder join as usual
		e.registrar("1").optional().playBidirectional(TYPE, CODEC, (p, ctx) -> {
			if (ctx.flow().isServerbound()) {
				if (p.kind() == ASK && ctx.player() instanceof ServerPlayer player)
					ctx.enqueueWork(() -> CompanionPlugins.asked(player, p.id(), p.version()));
			} else if (p.kind() == DATA) {
				// when this mod is in the player's game too (singleplayer, say): hand it on as a NeoForge event, on the client thread
				ctx.enqueueWork(() -> NeoForge.EVENT_BUS.post(new CompatClientEvent(p.id(), p.version(), p.data())));
			}
		});
	}
}
