package comedymonkey.dailies.compat.companion;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.capture.SnapshotExtensions;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.PlaybackExtensions;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The player's side of the Dailies Companion's server plugin API (specification SRV-6, SRV-7). A server plugin
 * pushes data for one player over the shared channel {@code dailies_companion:compat}; here it is routed by the
 * plugin's id to the client half registered with {@link #register}, and kept in the recording as a
 * {@code dailies:compat} record, so playback routes it again to the same client half. The last data from each plugin
 * goes into every snapshot, so a jump or a recording started later has it too.
 * <p>
 * One client half is built in: the companion's list of the plugins a server has ({@value #PLUGINS}), asked for as the
 * game joins a world. A compat reads it with {@link #serverVersion} (live) or {@link #recordedVersion} (playback) to
 * know whether its server half is there and which version it is.
 * <p>
 * As with the companion's moments, players need no copy of the companion: without it in this game, Dailies registers
 * the channel itself (same id, same format). With it here as well (the Dailies jar carries it, jar-in-jar, for singleplayer), the companion owns the
 * channel and re-posts what arrives as a NeoForge event, which is listened to by reflection.
 */
public final class CompanionCompat {

    public static final String EXTENSION = "dailies:compat";
    public static final String PLUGINS = "dailies_companion:plugins";
    private static final String COMPANION = "dailies_companion";
    private static final String CLIENT_EVENT = "comedymonkey.dailiescompanion.CompatClientEvent";
    private static final String COMPANION_PAYLOAD = "comedymonkey.dailiescompanion.CompatPayload";
    private static final int DATA = 0;
    private static final int ASK = 1;

    static final CompatRoutes ROUTES = new CompatRoutes();
    /** The last data from each plugin in live play, for snapshots */
    private static final Map<String, CompatRoutes.Arrival> LIVE = new ConcurrentHashMap<>();
    /** The last data from each plugin reached in playback, for a trimmed recording's first snapshot */
    private static final Map<String, CompatRoutes.Arrival> PLAYED = new ConcurrentHashMap<>();
    private static volatile Map<String, Integer> serverPlugins = Map.of();
    private static volatile Map<String, Integer> recordedPlugins = Map.of();
    private static boolean warned = false;

    private CompanionCompat() {
    }

    /** The shared channel's format, see the companion's CompatPayload */
    public record Payload(int kind, String id, int version, byte[] data) implements CustomPacketPayload {
        public static final Type<Payload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(COMPANION, "compat"));
        public static final StreamCodec<ByteBuf, Payload> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Payload::kind,
            ByteBufCodecs.stringUtf8(128), Payload::id, ByteBufCodecs.VAR_INT, Payload::version, ByteBufCodecs.byteArray(262144), Payload::data, Payload::new);

        @Override
        public Type<Payload> type() {
            return TYPE;
        }
    }

    /**
     * A client half: what to do with its server half's data, live and in playback.
     *
     * @throws IllegalArgumentException when the id is not namespaced or is taken
     */
    public static void register(String id, CompatRoutes.Handler handler) {
        ROUTES.register(id, handler);
    }

    /** The version of a plugin on the server the game is joined to; 0 when it has none, or has not said yet */
    public static int serverVersion(String id) {
        return serverPlugins.getOrDefault(id, 0);
    }

    /** The version of a plugin on the server a recording was made on, as far as playback has read; 0 when it had none */
    public static int recordedVersion(String id) {
        return recordedPlugins.getOrDefault(id, 0);
    }

    /** @param registrar an optional registrar: servers without the companion must stay joinable */
    public static void registerPayload(PayloadRegistrar registrar) {
        if (Host.get().isModLoaded(COMPANION)) {
            return; // the companion registers it, see init()
        }
        registrar.playBidirectional(Payload.TYPE, Payload.CODEC, (payload, context) -> {
            if (payload.kind() == DATA) {
                context.enqueueWork(() -> received(new CompatRoutes.Arrival(payload.id(), payload.version(), payload.data())));
            }
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void init() {
        register(PLUGINS, new CompatRoutes.Handler() {
            @Override
            public void received(int version, byte[] data) {
                serverPlugins = version == 0 ? Map.of() : Map.copyOf(CompatRoutes.pluginList(data));
            }

            @Override
            public void played(int version, byte[] data, boolean skipping) {
                recordedPlugins = version == 0 ? Map.of() : Map.copyOf(CompatRoutes.pluginList(data));
            }

            @Override
            public void reset() {
                recordedPlugins = Map.of();
            }
        });
        SnapshotExtensions.register(EXTENSION, (out, codec) -> LIVE.values().forEach(arrival -> out.extension(EXTENSION, arrival.encode())));
        PlaybackExtensions.register(EXTENSION, new PlaybackExtensions.Extension() {
            @Override
            public void play(byte[] data, int from, int length, boolean skipping) {
                CompatRoutes.Arrival arrival = CompatRoutes.Arrival.decode(data, from, length);
                PLAYED.put(arrival.id(), arrival);
                ROUTES.played(arrival, skipping);
            }

            @Override
            public void reset() {
                PLAYED.clear();
                ROUTES.resetAll();
            }

            @Override
            public void describe(Consumer<byte[]> out) {
                PLAYED.values().forEach(arrival -> out.accept(arrival.encode()));
            }
        });
        Playback.onClosing(() -> {
            PLAYED.clear();
            ROUTES.resetAll();
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> {
            LIVE.clear();
            serverPlugins = Map.of();
            ask(PLUGINS, 1);
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            LIVE.clear();
            serverPlugins = Map.of();
        });
        if (!Host.get().isModLoaded(COMPANION)) {
            return;
        }
        try {
            Class<?> eventClass = Class.forName(CLIENT_EVENT);
            Method id = eventClass.getMethod("id");
            Method version = eventClass.getMethod("version");
            Method data = eventClass.getMethod("data");
            Consumer<Event> listener = event -> {
                try {
                    received(new CompatRoutes.Arrival((String) id.invoke(event), (Integer) version.invoke(event), (byte[]) data.invoke(event)));
                } catch (Throwable t) {
                    Host.LOGGER.warn("Dailies Companion: unable to read a server plugin's data", t);
                }
            };
            NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, (Class) eventClass, (Consumer) listener);
        } catch (Throwable t) {
            Host.LOGGER.error("Dailies Companion: unable to listen for server plugins' data", t);
        }
    }

    /**
     * Asks the server's plugin of this id for what it has for this player; the answer arrives through the handler
     * registered for it. Does nothing in playback, or when the server is not listening on the channel. Client thread
     *
     * @param version the asking client half's version, which the plugin is told
     * @return whether the question was sent
     */
    public static boolean ask(String id, int version) {
        if (Host.get().inPlayback()) {
            return false;
        }
        ClientPacketListener listener = Minecraft.getInstance().getConnection();
        if (listener == null || !listener.hasChannel(Payload.TYPE)) {
            return false;
        }
        try {
            CustomPacketPayload question;
            if (Host.get().isModLoaded(COMPANION)) {
                // The companion owns the channel here, and only its own payload class can be sent on it
                question = (CustomPacketPayload) Class.forName(COMPANION_PAYLOAD).getMethod("ask", String.class, int.class).invoke(null, id, version);
            } else {
                question = new Payload(ASK, id, version, new byte[0]);
            }
            ClientPacketDistributor.sendToServer(question);
            return true;
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                Host.LOGGER.warn("Dailies Companion: unable to ask the server's plugins for their data. Said once", t);
            }
            return false;
        }
    }

    /** A plugin's data has arrived in live play: routed to its client half, kept for snapshots, and recorded. Client thread */
    static void received(CompatRoutes.Arrival arrival) {
        if (Host.get().inPlayback()) {
            // Playback reads what the recording kept instead (PLAY-12)
            return;
        }
        LIVE.put(arrival.id(), arrival);
        try {
            ROUTES.live(arrival);
        } catch (Throwable t) {
            Host.LOGGER.warn("Dailies Companion: the client half of {} failed on its data", arrival.id(), t);
        }
        Recorder.get().writeExtension(EXTENSION, arrival.encode());
    }

}
