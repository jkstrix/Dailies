package comedymonkey.dailies.compat.voicechat;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.SnapshotExtensions;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.PlaybackExtensions;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The "voice_chat" look: playback shows what Simple Voice Chat showed while recording, drawn by voice chat itself. The
 * icons over players' heads (talking, whispering, voice chat turned off, not connected, in another group) and the
 * group display at the side of the screen come from voice chat's own renderer, which reads voice chat's client state;
 * while the look is on, that state is the recording's.
 * <ul>
 * <li>Recording: voice chat tells a game the players' states and the groups once, when it joins, and then only what
 * changes. Those messages are recorded with every other mod message; and so that a recording started part way, or
 * jumped into, knows them too, each snapshot holds what voice chat knew at that moment, as records of
 * {@link #EXTENSION} in voice chat's own message format.</li>
 * <li>Playback: the messages {@link VoiceChatPayloads} lets through are read with voice chat's own message classes into
 * {@link RecordedVoiceChat}, whatever the look says. While the look is on, that is copied into voice chat's client
 * state; when it is turned off, or the recording closes, the state voice chat had before is put back exactly.</li>
 * <li>Talking: Dailies plays recorded voice itself, so voice chat never hears it. Each piece of recorded voice tells
 * voice chat's talk cache that its speaker is talking (or whispering), timed by a {@link TalkClock} that follows the
 * recording rather than the wall clock, so an export lights the same icons playback did.</li>
 * </ul>
 * Voice chat's public API only reads these things, so its public client classes are reached by reflection, found once
 * by name: nothing here is compiled against voice chat. Without it, or with a version that differs, the look does
 * nothing and says so once in the log.
 */
public final class VoiceChatLook {

    /** The snapshot's record of what voice chat knew: a message id, then the message as voice chat writes it */
    public static final String EXTENSION = "dailies:voicechat";

    private static final String MOD = "voicechat";
    private static final String CLIENT = "de.maxhenkel.voicechat.voice.client.";
    private static final String NET = "de.maxhenkel.voicechat.net.";
    private static final String COMMON = "de.maxhenkel.voicechat.voice.common.";

    private static final RecordedVoiceChat<Object, Object> RECORDED = new RecordedVoiceChat<>();
    private static final TalkClock CLOCK = new TalkClock();
    private static final Supplier<Long> RECORDING_CLOCK = () -> CLOCK.at(Playback.position());

    // Voice chat's classes, found once
    private static boolean looked = false;
    private static boolean failed = false;
    private static @Nullable Method stateManager, groupManager, client, talkCache, updateTalking, setTimestampSupplier, stateId, groupId, fromBytes, toBytes;
    private static @Nullable Field states, group, groups, timestampSupplier;
    private static final Map<VoiceChatPayloads.Kind, Constructor<?>> MAKERS = new EnumMap<>(VoiceChatPayloads.Kind.class);
    private static final Map<VoiceChatPayloads.Kind, Method> READERS = new EnumMap<>(VoiceChatPayloads.Kind.class);
    private static @Nullable Constructor<?> statesPacket, addGroupPacket, joinedGroupPacket;

    // While the look is shown: what voice chat had before, to put back
    private static volatile boolean shown = false;
    private static Map<Object, Object> ownStates = Map.of();
    private static @Nullable Object ownGroup;
    private static Map<Object, Object> ownGroups = Map.of();
    private static int pushed = Integer.MIN_VALUE;
    private static @Nullable Object pushedStates;
    /** The talk cache given the recording's clock, and the clock it had */
    private static @Nullable Object clockedCache;
    private static @Nullable Object cacheClock;

    private VoiceChatLook() {
    }

    public static void init() {
        PlaybackExtensions.register(EXTENSION, new PlaybackExtensions.Extension() {
            @Override
            public void play(byte[] data, int from, int length, boolean skipping) {
                FriendlyByteBuf in = new FriendlyByteBuf(Unpooled.wrappedBuffer(data, from, length));
                String id = in.readUtf();
                recorded(id, in);
            }

            @Override
            public void reset() {
                // The snapshot about to be read says it all again
                RECORDED.clear();
                CLOCK.leap();
            }

            @Override
            public void describe(java.util.function.Consumer<byte[]> out) throws Exception {
                if (ready() && !RECORDED.isEmpty()) {
                    write(RECORDED.states().values(), RECORDED.groups().values(), RECORDED.group(), out);
                }
            }
        });
        SnapshotExtensions.register(EXTENSION, (out, codec) -> snapshot(data -> out.extension(EXTENSION, data)));
        Playback.onClosing(() -> {
            hide();
            RECORDED.clear();
        });
    }

    /** Whether Simple Voice Chat is here in a version this look can work with */
    public static boolean available() {
        return ready();
    }

    // ------------------------------------------------------------------ Recording

    /** At the end of each snapshot taken while recording: what voice chat knows now */
    private static void snapshot(java.util.function.Consumer<byte[]> out) throws Exception {
        if (!ready() || shown) {
            return;
        }
        Object players = stateManager.invoke(null);
        Object groupList = groupManager.invoke(null);
        Map<?, ?> known = (Map<?, ?>) states.get(players);
        Map<?, ?> all = (Map<?, ?>) groups.get(groupList);
        Object own = group.get(players);
        if ((known == null || known.isEmpty()) && (all == null || all.isEmpty()) && own == null) {
            return;
        }
        write(known == null ? java.util.List.of() : known.values(), all == null ? java.util.List.of() : all.values(), own, out);
    }

    private static void write(Collection<?> playerStates, Collection<?> groupList, @Nullable Object own, java.util.function.Consumer<byte[]> out) throws Exception {
        out.accept(record(VoiceChatPayloads.Kind.STATES, statesPacket.newInstance(new java.util.ArrayList<>(playerStates))));
        for (Object one : groupList) {
            out.accept(record(VoiceChatPayloads.Kind.ADD_GROUP, addGroupPacket.newInstance(one)));
        }
        out.accept(record(VoiceChatPayloads.Kind.JOINED_GROUP, joinedGroupPacket.newInstance(own, false)));
    }

    private static byte[] record(VoiceChatPayloads.Kind kind, Object message) throws Exception {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer(256));
        try {
            buffer.writeUtf(VoiceChatPayloads.id(kind));
            toBytes.invoke(message, buffer);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    // ------------------------------------------------------------------ Playback

    /**
     * A recorded voice chat message has been reached in playback, as the whole packet that arrived. It never goes to
     * voice chat's own handlers (playback.PayloadRules keeps it from them); what it says is read here. Client thread
     */
    public static void payload(String id, byte[] frame, int from, int length) {
        if (VoiceChatPayloads.shown(id) == null || !ready()) {
            return;
        }
        FriendlyByteBuf in = new FriendlyByteBuf(Unpooled.wrappedBuffer(frame, from, length));
        try {
            in.readVarInt();
            in.readResourceLocation();
        } catch (RuntimeException e) {
            return;
        }
        recorded(id, in);
    }

    private static void recorded(String id, FriendlyByteBuf body) {
        VoiceChatPayloads.Kind kind = VoiceChatPayloads.shown(id);
        if (kind == null || !ready()) {
            return;
        }
        try {
            Object message = fromBytes.invoke(MAKERS.get(kind).newInstance(), body);
            Object said = READERS.get(kind).invoke(message);
            switch (kind) {
                case STATE -> {
                    if (said != null) {
                        RECORDED.state((UUID) stateId.invoke(said), said);
                    }
                }
                case STATES -> {
                    Map<UUID, Object> all = new LinkedHashMap<>();
                    if (said instanceof Collection<?> each) {
                        for (Object state : each) {
                            all.put((UUID) stateId.invoke(state), state);
                        }
                    }
                    RECORDED.states(all);
                }
                case REMOVE_STATE -> RECORDED.removeState((UUID) said);
                case ADD_GROUP -> {
                    if (said != null) {
                        RECORDED.addGroup((UUID) groupId.invoke(said), said);
                    }
                }
                case REMOVE_GROUP -> RECORDED.removeGroup((UUID) said);
                case JOINED_GROUP -> RECORDED.joined((UUID) said);
            }
        } catch (Throwable t) {
            // One message from a voice chat that writes it differently: skipped, and said once
            failOnce("a recorded voice chat message (" + id + ") could not be read, so the voice chat look may show less than was there", t);
        }
    }

    /**
     * Once per drawn frame, with whether the scene being shown wants the look. Puts the recording's state into voice
     * chat, keeps it there as the recording goes on, and puts voice chat's own back once it is no longer wanted.
     * Client thread
     */
    public static void frame(boolean wanted) {
        boolean want = wanted && Playback.loaded() && ready();
        if (!want) {
            if (shown) {
                hide();
            }
            return;
        }
        try {
            Object players = stateManager.invoke(null);
            Object groupList = groupManager.invoke(null);
            if (!shown) {
                ownStates = copy(states.get(players));
                ownGroup = group.get(players);
                ownGroups = copy(groups.get(groupList));
                shown = true;
                pushed = Integer.MIN_VALUE;
            }
            Object now = states.get(players);
            // Voice chat empties its state by itself when a world is left, which a rebuild of the playback world can do
            boolean emptied = now != pushedStates || !(now instanceof Map<?, ?> map) || map.size() != RECORDED.states().size()
                || !java.util.Objects.equals(group.get(players), RECORDED.group());
            if (pushed != RECORDED.changes() || emptied) {
                Map<Object, Object> copy = new HashMap<>(RECORDED.states());
                states.set(players, copy);
                pushedStates = copy;
                group.set(players, RECORDED.group());
                @SuppressWarnings("unchecked")
                Map<Object, Object> list = (Map<Object, Object>) groups.get(groupList);
                list.clear();
                list.putAll(RECORDED.groups());
                pushed = RECORDED.changes();
            }
            clockTalking();
        } catch (Throwable t) {
            failOnce("Simple Voice Chat's state could not be set, so the voice chat look shows nothing", t);
            hide();
            failed = true;
        }
    }

    /** Gives the talk cache of voice chat's client as it is now the recording's clock; a new client brings a new cache */
    private static void clockTalking() throws Exception {
        Object voice = client.invoke(null);
        Object cache = voice == null ? null : talkCache.invoke(voice);
        if (cache == clockedCache) {
            return;
        }
        unclockTalking();
        if (cache != null) {
            cacheClock = timestampSupplier.get(cache);
            setTimestampSupplier.invoke(cache, RECORDING_CLOCK);
            clockedCache = cache;
        }
    }

    private static void unclockTalking() {
        Object cache = clockedCache;
        clockedCache = null;
        if (cache != null) {
            try {
                setTimestampSupplier.invoke(cache, cacheClock != null ? cacheClock : (Supplier<Long>) System::currentTimeMillis);
            } catch (Throwable t) {
                Host.LOGGER.debug("Dailies: voice chat's talk cache could not be given its own clock back", t);
            }
        }
        cacheClock = null;
    }

    /** Puts voice chat's own state back as it was before the look was shown */
    private static void hide() {
        if (!shown) {
            return;
        }
        shown = false;
        CLOCK.leap();
        try {
            Object players = stateManager.invoke(null);
            Object groupList = groupManager.invoke(null);
            states.set(players, new HashMap<>(ownStates));
            group.set(players, ownGroup);
            @SuppressWarnings("unchecked")
            Map<Object, Object> list = (Map<Object, Object>) groups.get(groupList);
            list.clear();
            list.putAll(ownGroups);
        } catch (Throwable t) {
            failOnce("Simple Voice Chat's own state could not be put back after the voice chat look", t);
        } finally {
            unclockTalking();
            ownStates = Map.of();
            ownGroup = null;
            ownGroups = Map.of();
            pushedStates = null;
            pushed = Integer.MIN_VALUE;
        }
    }

    /**
     * A piece of recorded voice has been reached at its tick, as the recording plays or is exported: while the look is
     * shown, voice chat is told that its speaker is talking, so its own renderer lights their icon. Client thread
     */
    public static void spoke(UUID speaker, boolean whispering) {
        Object cache = clockedCache;
        if (!shown || cache == null) {
            return;
        }
        try {
            updateTalking.invoke(cache, speaker, whispering);
        } catch (Throwable t) {
            failOnce("Simple Voice Chat could not be told who is talking, so talking icons are not shown", t);
        }
    }

    // ------------------------------------------------------------------ Finding voice chat

    private static boolean ready() {
        if (!looked) {
            looked = true;
            if (Host.get().isModLoaded(MOD)) {
                try {
                    find();
                } catch (Throwable t) {
                    failed = true;
                    Host.LOGGER.warn("Dailies: this version of Simple Voice Chat is not one the voice chat look knows, so it does nothing. Said once", t);
                }
            } else {
                failed = true;
            }
        }
        return !failed;
    }

    private static void find() throws Exception {
        Class<?> manager = Class.forName(CLIENT + "ClientManager");
        stateManager = manager.getMethod("getPlayerStateManager");
        groupManager = manager.getMethod("getGroupManager");
        client = manager.getMethod("getClient");
        Class<?> players = Class.forName(CLIENT + "ClientPlayerStateManager");
        states = open(players, "states");
        group = open(players, "group");
        groups = open(Class.forName(CLIENT + "ClientGroupManager"), "groups");
        talkCache = Class.forName(CLIENT + "ClientVoicechat").getMethod("getTalkCache");
        Class<?> cache = Class.forName(CLIENT + "TalkCache");
        updateTalking = cache.getMethod("updateTalking", UUID.class, boolean.class);
        setTimestampSupplier = cache.getMethod("setTimestampSupplier", Supplier.class);
        timestampSupplier = open(cache, "timestampSupplier");
        Class<?> playerState = Class.forName(COMMON + "PlayerState");
        Class<?> clientGroup = Class.forName(COMMON + "ClientGroup");
        stateId = playerState.getMethod("getUuid");
        groupId = clientGroup.getMethod("getId");
        Class<?> packet = Class.forName(NET + "Packet");
        fromBytes = packet.getMethod("fromBytes", FriendlyByteBuf.class);
        toBytes = packet.getMethod("toBytes", FriendlyByteBuf.class);
        message(VoiceChatPayloads.Kind.STATE, "PlayerStatePacket", "getPlayerState");
        message(VoiceChatPayloads.Kind.STATES, "PlayerStatesPacket", "getPlayerStates");
        message(VoiceChatPayloads.Kind.REMOVE_STATE, "RemovePlayerStatePacket", "getId");
        message(VoiceChatPayloads.Kind.ADD_GROUP, "AddGroupPacket", "getGroup");
        message(VoiceChatPayloads.Kind.REMOVE_GROUP, "RemoveGroupPacket", "getGroupId");
        message(VoiceChatPayloads.Kind.JOINED_GROUP, "JoinedGroupPacket", "getGroup");
        statesPacket = Class.forName(NET + "PlayerStatesPacket").getConstructor(Collection.class);
        addGroupPacket = Class.forName(NET + "AddGroupPacket").getConstructor(clientGroup);
        joinedGroupPacket = Class.forName(NET + "JoinedGroupPacket").getConstructor(UUID.class, boolean.class);
    }

    private static void message(VoiceChatPayloads.Kind kind, String name, String reader) throws Exception {
        Class<?> type = Class.forName(NET + name);
        MAKERS.put(kind, type.getConstructor());
        READERS.put(kind, type.getMethod(reader));
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> copy(@Nullable Object map) {
        return map == null ? new HashMap<>() : new HashMap<>((Map<Object, Object>) map);
    }

    private static Field open(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final java.util.Set<String> SAID = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void failOnce(String what, Throwable problem) {
        if (SAID.add(what)) {
            Host.LOGGER.warn("Dailies: {}. Said once", what, problem);
        }
    }

}
