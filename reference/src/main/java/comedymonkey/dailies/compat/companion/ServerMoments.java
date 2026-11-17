package comedymonkey.dailies.compat.companion;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.moments.Moments;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * Custom events sent by a server running the Dailies Companion mod (the companion/ project). They are
 * recorded as moments of type "custom".
 *
 * The companion is a server mod and players don't need a copy: without it in this game, Dailies registers the
 * companion's channel itself (same id, same three strings). With it installed here as well (singleplayer), the
 * companion owns the channel and re-posts what arrives as a NeoForge event, which is listened to by reflection.
 */
public final class ServerMoments {

    private static final String COMPANION = "dailies_companion";
    private static final String CLIENT_EVENT = "comedymonkey.dailiescompanion.MomentClientEvent";

    private ServerMoments() {
    }

    /** The companion's wire format, see its MomentPayload */
    public record Payload(String momentType, String label, String json) implements CustomPacketPayload {
        public static final Type<Payload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(COMPANION, "event"));
        public static final StreamCodec<ByteBuf, Payload> CODEC = StreamCodec.composite(ByteBufCodecs.stringUtf8(64), Payload::momentType,
            ByteBufCodecs.stringUtf8(256), Payload::label, ByteBufCodecs.stringUtf8(32767), Payload::json, Payload::new);

        @Override
        public Type<Payload> type() {
            return TYPE;
        }
    }

    /** @param registrar an optional registrar: servers without the companion must stay joinable */
    public static void registerPayload(PayloadRegistrar registrar) {
        if (Host.get().isModLoaded(COMPANION)) {
            return; // the companion registers it, see init()
        }
        registrar.playToClient(Payload.TYPE, Payload.CODEC, (payload, context) ->
            context.enqueueWork(() -> receive(payload.momentType(), payload.label(), payload.json())));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void init() {
        if (!Host.get().isModLoaded(COMPANION)) {
            return;
        }
        try {
            Class<?> eventClass = Class.forName(CLIENT_EVENT);
            Method type = eventClass.getMethod("type");
            Method label = eventClass.getMethod("label");
            Method json = eventClass.getMethod("json");
            Consumer<Event> listener = event -> {
                try {
                    receive((String) type.invoke(event), (String) label.invoke(event), (String) json.invoke(event));
                } catch (Throwable t) {
                    Host.LOGGER.warn("Dailies Companion: unable to read a server event", t);
                }
            };
            NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, (Class) eventClass, (Consumer) listener);
        } catch (Throwable t) {
            Host.LOGGER.error("Dailies Companion: unable to listen for server events", t);
        }
    }

    private static void receive(String type, String label, String json) {
        if (Host.get().inPlayback()) {
            return;
        }
        JsonObject data = null;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (parsed.isJsonObject()) {
                data = parsed.getAsJsonObject();
            }
        } catch (RuntimeException ignored) {
            // The label and type are still worth keeping
        }
        Moments.onServerMoment(type, label, data);
    }

}
