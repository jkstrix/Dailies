package comedymonkey.dailiescompanion;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * server → client: one moment. The wire format is three strings (type, label, a JSON object), so a recorder can
 * register the same channel itself and needs no copy of this mod on the client.
 */
public record MomentPayload(String momentType, String label, String json) implements CustomPacketPayload {
	public static final int MAX_TYPE = 64, MAX_LABEL = 256, MAX_JSON = 32767;

	public static final Type<MomentPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(DailiesCompanion.MODID, "event"));
	public static final StreamCodec<ByteBuf, MomentPayload> CODEC = StreamCodec.composite(ByteBufCodecs.stringUtf8(MAX_TYPE), MomentPayload::momentType,
		ByteBufCodecs.stringUtf8(MAX_LABEL), MomentPayload::label, ByteBufCodecs.stringUtf8(MAX_JSON), MomentPayload::json, MomentPayload::new);

	@Override
	public Type<MomentPayload> type() {
		return TYPE;
	}

	static void register(RegisterPayloadHandlersEvent e) {
		// optional: players without this mod, or without a recorder, join as usual
		e.registrar("1").optional().playToClient(TYPE, CODEC,
			// when this mod is in the player's game too (singleplayer, say): hand it on as a NeoForge event, on the client thread
			(p, ctx) -> ctx.enqueueWork(() -> NeoForge.EVENT_BUS.post(new MomentClientEvent(p.momentType(), p.label(), p.json()))));
	}
}
