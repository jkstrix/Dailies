package comedymonkey.dailies.moments;

import com.google.gson.JsonObject;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.moments.analysis.ClipSummarizer;
import comedymonkey.dailies.moments.storage.FirstsStore;
import comedymonkey.dailies.moments.storage.MomentsSession;
import comedymonkey.dailies.moments.storage.MomentsStorage;
import comedymonkey.dailies.moments.track.GameplayTracker;
import comedymonkey.dailies.moments.track.InteractionTracker;
import comedymonkey.dailies.moments.voice.TranscriptionWorker;
import comedymonkey.dailies.moments.voice.VoiceSegmenter;
import comedymonkey.dailies.moments.voice.WavFiles;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.advancements.AdvancementTree;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.stats.Stat;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Entry point of the moments system: while the player is in a world (not a replay) it keeps a session that logs
 * gameplay events, player interactions and voice chat. Everything here is client-side only.
 * <p>
 * The hooks (from NeoForge events and the mixins in mixin/moments) do nothing when no session is active.
 */
public final class Moments {

    private static final String NO_VOICE_CHAT = "Simple Voice Chat is not installed. Voice chat is the only audio that is ever transcribed";

    private static class Active {
        MomentsSession session;
        FirstsStore firsts;
        GameplayTracker gameplay;
        InteractionTracker interactions;
        final VoiceSegmenter segmenter = new VoiceSegmenter();
        String lastReplayUuid = null;
        String biome = null;
        int ticks = 0;
        int ticksWithoutPlayer = 0;
    }

    private static MomentsStorage storage;
    private static TranscriptionWorker transcription;
    private static final ClipSummarizer SUMMARIZER = new ClipSummarizer();
    private static volatile Active active = null;
    private static volatile TimeRef current = TimeRef.EMPTY;
    private static final Map<UUID, String> PLAYER_NAMES = new ConcurrentHashMap<>();
    private static int ticksSinceLogin = Integer.MAX_VALUE / 2;

    private Moments() {
    }

    public static void init() {
        storage = new MomentsStorage(Host.get().dataDirectory().resolve("moments"));
        transcription = new TranscriptionWorker(storage);

        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            try {
                tick();
            } catch (Throwable t) {
                Host.LOGGER.error("Moments: error while tracking, ending the session", t);
                endSession();
            }
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> ticksSinceLogin = 0);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> endSession());

        // These two also fire on the integrated server's thread in singleplayer; only the client's own player counts
        NeoForge.EVENT_BUS.addListener((AttackEntityEvent event) -> {
            Active session = active;
            if (session != null && event.getEntity() instanceof LocalPlayer player) {
                guarded(() -> session.gameplay.onAttack(player, event.getTarget()));
            }
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.ItemCraftedEvent event) -> {
            Active session = active;
            if (session != null && event.getEntity() instanceof LocalPlayer) {
                ItemStack crafted = event.getCrafting().copy();
                guarded(() -> session.gameplay.onItemCrafted(crafted));
            }
        });
        NeoForge.EVENT_BUS.addListener((ClientChatReceivedEvent.System event) -> {
            Active session = active;
            if (session != null && !event.isOverlay()) {
                guarded(() -> session.gameplay.onSystemChat(event.getMessage()));
            }
        });

        // Ends voice segments once their speaker has been silent for the configured gap
        ScheduledExecutorService voiceTimer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Dailies-Moments-Voice");
            thread.setDaemon(true);
            return thread;
        });
        voiceTimer.scheduleWithFixedDelay(() -> {
            try {
                Active session = active;
                if (session != null) {
                    storeSegments(session, session.segmenter.flush(System.currentTimeMillis(), MomentsConfig.voiceGapMillis()));
                }
            } catch (Throwable t) {
                Host.LOGGER.error("Moments: error while finishing voice segments", t);
            }
        }, 250, 250, TimeUnit.MILLISECONDS);
    }

    /** A broken hook must never take the game down with it */
    private static void guarded(Runnable runnable) {
        try {
            runnable.run();
        } catch (Throwable t) {
            Host.LOGGER.error("Moments: error in an event hook", t);
        }
    }

    // --- Accessors ---

    public static MomentsStorage storage() {
        return storage;
    }

    public static TranscriptionWorker transcription() {
        return transcription;
    }

    public static ClipSummarizer summarizer() {
        return SUMMARIZER;
    }

    @Nullable
    public static MomentsSession currentSession() {
        Active session = active;
        return session == null ? null : session.session;
    }

    /** The gameplay tracker of the running session. Client thread only */
    @Nullable
    public static GameplayTracker gameplay() {
        Active session = active;
        return session == null ? null : session.gameplay;
    }

    /** Meetings and groups of the given session that are still going on (only the running session has any) */
    public static List<JsonObject> ongoingInteractions(MomentsSession of) {
        Active session = active;
        return session != null && session.session == of ? session.interactions.ongoing() : List.of();
    }

    /** Where and when "now" is. Usable from any thread; the game state in it is from the last client tick */
    public static TimeRef now() {
        return current.withRealTime(System.currentTimeMillis());
    }

    public static boolean voiceChatInstalled() {
        return Host.get().isModLoaded("voicechat");
    }

    /** Null when voice chat can be captured, otherwise why not */
    @Nullable
    public static String voiceUnavailableReason() {
        return voiceChatInstalled() ? null : NO_VOICE_CHAT;
    }

    // --- Session lifecycle ---

    private static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (ticksSinceLogin < Integer.MAX_VALUE / 2) {
            ticksSinceLogin += 1;
        }

        boolean inReplay = Host.get().inPlayback();
        Active session = active;
        if (inReplay || !MomentsConfig.enabled()) {
            if (session != null) {
                endSession();
            }
            return;
        }
        if (player == null || level == null) {
            // Disconnecting ends the session through the LoggingOut event; this is for anything that slips past it
            if (session != null && ++session.ticksWithoutPlayer > 200) {
                endSession();
            }
            return;
        }

        if (session == null) {
            session = startSession(minecraft, player);
        }
        session.ticksWithoutPlayer = 0;
        session.ticks += 1;

        if (session.ticks % 20 == 1) {
            BlockPos pos = player.blockPosition();
            session.biome = level.getBiome(pos).unwrapKey().map(key -> key.location().toString()).orElse(null);

            ClientPacketListener connection = minecraft.getConnection();
            if (connection != null) {
                for (PlayerInfo info : connection.getOnlinePlayers()) {
                    PLAYER_NAMES.put(info.getProfile().getId(), info.getProfile().getName());
                }
            }
        }

        // The recording being made right now, if any: its unique id and how many ticks it holds so far
        String replayUuid = Host.get().recordingId();
        int replayTick = -1;
        if (replayUuid != null) {
            replayTick = Host.get().recordingTick();
            if (!replayUuid.equals(session.lastReplayUuid)) {
                session.lastReplayUuid = replayUuid;
                session.session.addReplay(replayUuid);
            }
        }
        current = new TimeRef(System.currentTimeMillis(), level.getGameTime(), level.getDayTime(), level.dimension().location().toString(),
            session.biome, player.getX(), player.getY(), player.getZ(), replayUuid, replayTick);

        session.gameplay.tick(minecraft, player, level, session.biome);
        if (session.ticks % 20 == 0) {
            session.interactions.second(level);
        }
    }

    private static Active startSession(Minecraft minecraft, LocalPlayer player) {
        String worldName = null;
        String worldKey = "unknown";
        String worldType = "unknown";
        String address = null;

        IntegratedServer integratedServer = minecraft.getSingleplayerServer();
        ServerData serverData = minecraft.getCurrentServer();
        if (integratedServer != null) {
            worldType = "singleplayer";
            worldName = integratedServer.getWorldData().getLevelName();
            // The save folder is what identifies a singleplayer world; its display name can be shared
            String folder = integratedServer.getWorldPath(LevelResource.ROOT).normalize().getFileName().toString();
            worldKey = "sp_" + sanitize(folder);
        } else if (serverData != null) {
            worldType = "multiplayer";
            worldName = serverData.name;
            address = serverData.ip;
            worldKey = "mp_" + sanitize(serverData.ip);
        }

        JsonObject meta = new JsonObject();
        long now = System.currentTimeMillis();
        meta.addProperty("start_real_time", now);
        meta.addProperty("start_real_time_iso", MJson.iso(now));
        meta.add("end_real_time", null);
        meta.addProperty("world_name", worldName);
        meta.addProperty("world_type", worldType);
        meta.addProperty("world_key", worldKey);
        meta.addProperty("server_address", address);
        meta.add("local_player", MJson.player(player.getUUID().toString(), player.getGameProfile().getName()));
        meta.add("replays", new com.google.gson.JsonArray());
        meta.addProperty("mod_version", Host.get().modVersion());

        Active session = new Active();
        session.session = storage.createSession(meta);
        session.firsts = storage.getFirsts(worldKey);
        // Right after logging in the server sends every statistic on the first request; later it only sends changes
        boolean statsBaselineComplete = ticksSinceLogin < 20 * 30;
        session.gameplay = new GameplayTracker(session.session, session.firsts, player.getUUID(), player.getGameProfile().getName(), statsBaselineComplete);
        session.interactions = new InteractionTracker(session.session, session.firsts);
        PLAYER_NAMES.put(player.getUUID(), player.getGameProfile().getName());
        active = session;
        Host.LOGGER.info("Moments: started session {} ({} '{}')", session.session.id, worldType, worldName);
        return session;
    }

    private static String sanitize(String name) {
        String sanitized = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return sanitized.isEmpty() ? "unnamed" : sanitized;
    }

    private static void endSession() {
        Active session = active;
        if (session == null) {
            return;
        }
        active = null;
        current = TimeRef.EMPTY;
        try {
            storeSegments(session, session.segmenter.flushAll());
            session.interactions.finish();
            session.firsts.save();
            long now = System.currentTimeMillis();
            session.session.updateMeta(meta -> {
                meta.addProperty("end_real_time", now);
                meta.addProperty("end_real_time_iso", MJson.iso(now));
            });
            Host.LOGGER.info("Moments: ended session {} ({} events, {} interactions, {} voice segments)", session.session.id,
                session.session.count(MomentsSession.Kind.EVENTS), session.session.count(MomentsSession.Kind.INTERACTIONS),
                session.session.count(MomentsSession.Kind.DIALOGUE));
        } catch (Throwable t) {
            Host.LOGGER.error("Moments: error while ending session {}", session.session.id, t);
        }
    }

    // --- Hooks from the mixins and from the mod itself. All on the client thread ---

    public static void onBlockMined(BlockPos pos, BlockState state) {
        Active session = active;
        LocalPlayer player = Minecraft.getInstance().player;
        if (session != null && player != null) {
            guarded(() -> session.gameplay.onBlockMined(player, pos, state));
        }
    }

    public static void onUseItemOn(ItemStack heldBefore, BlockState clickedState, BlockPos clickedPos, Direction face, InteractionResult result) {
        Active session = active;
        ClientLevel level = Minecraft.getInstance().level;
        if (session != null && level != null) {
            guarded(() -> session.gameplay.onUseItemOn(level, heldBefore, clickedState, clickedPos, face, result));
        }
    }

    public static void onAdvancements(ClientboundUpdateAdvancementsPacket packet, AdvancementTree tree) {
        Active session = active;
        if (session != null) {
            guarded(() -> session.gameplay.onAdvancements(packet, tree));
        }
    }

    /** A job board event from McpCraft that the local player has a part in, see McpCraftCompat */
    public static void onJobBoardEvent(String jobEvent, com.google.gson.JsonObject data) {
        Active session = active;
        if (session != null) {
            guarded(() -> session.gameplay.onJobBoardEvent(jobEvent, data));
        }
    }

    /** A custom event sent by the server (Dailies Companion), see ServerMoments */
    public static void onServerMoment(String customType, String label, @Nullable com.google.gson.JsonObject data) {
        Active session = active;
        if (session != null) {
            guarded(() -> session.gameplay.addServerEvent(customType, label, data));
        }
    }

    public static void onCombatKill(Component message) {
        Active session = active;
        if (session != null) {
            guarded(() -> session.gameplay.onCombatKill(message));
        }
    }

    public static void onStats(Object2IntMap<Stat<?>> stats) {
        Active session = active;
        if (session != null) {
            guarded(() -> session.gameplay.onStats(stats));
        }
    }

    public static void onMarker(@Nullable String description, int colour) {
        Active session = active;
        if (session != null) {
            guarded(() -> session.gameplay.onMarker(description, colour));
        }
    }

    public static boolean isTracking() {
        return active != null;
    }

    // --- Voice ---

    /**
     * Audio from Simple Voice Chat: 48 kHz mono 16-bit PCM of one speaker. This is the only way audio gets into the
     * dialogue log in normal play. Called from the voice chat's threads by the voice chat capture.
     */
    public static void onVoiceChatAudio(UUID speaker, short[] pcm48k) {
        feedVoice(speaker, pcm48k);
    }

    /** Feeds audio as if it came from voice chat. Only for the dev self test (-Ddailies.test=true) */
    public static void injectVoiceForSelfTest(UUID speaker, short[] pcm48k) {
        if (!Boolean.getBoolean("dailies.test")) {
            throw new IllegalStateException("Voice can only be injected by the self test");
        }
        feedVoice(speaker, pcm48k);
    }

    private static void feedVoice(UUID speaker, short[] pcm48k) {
        Active session = active;
        if (session == null || pcm48k == null || pcm48k.length == 0) {
            return;
        }
        try {
            storeSegments(session, session.segmenter.feed(speaker, pcm48k, System.currentTimeMillis(), now(), MomentsConfig.voiceGapMillis()));
        } catch (Throwable t) {
            Host.LOGGER.error("Moments: error while handling voice audio", t);
        }
    }

    private static void storeSegments(Active session, List<VoiceSegmenter.Segment> segments) {
        for (VoiceSegmenter.Segment segment : segments) {
            String speaker = segment.speaker().toString();
            String audioFile = "audio/" + speaker + "_" + segment.startMillis() + ".wav";
            storage.writeBytes(session.session.dir.resolve(audioFile), WavFiles.encode(segment.pcm16k(), VoiceSegmenter.OUTPUT_SAMPLE_RATE));

            TimeRef start = ((TimeRef) segment.startRef()).withRealTime(segment.startMillis());
            TimeRef end = ((TimeRef) segment.endRef()).withRealTime(segment.endMillis());

            JsonObject record = new JsonObject();
            record.add("speaker", MJson.player(speaker, PLAYER_NAMES.getOrDefault(segment.speaker(), "Unknown")));
            record.addProperty("real_time", segment.startMillis());
            start.applyTo(record, "start_");
            end.applyTo(record, "end_");
            record.addProperty("day_time", start.dayTime());
            record.addProperty("dimension", start.dimension());
            record.addProperty("duration_seconds", MJson.round(segment.durationSeconds()));
            record.addProperty("audio_file", audioFile);
            record.addProperty("status", "pending");
            record.addProperty("text", "");
            record.add("language", null);
            record.add("avg_logprob", null);
            record.add("no_speech_prob", null);
            record.add("segments", new com.google.gson.JsonArray());
            record.add("whisper_model", null);
            record.addProperty("attempts", 0);
            record.add("metadata", new JsonObject());

            JsonObject stored = session.session.add(MomentsSession.Kind.DIALOGUE, record);
            if (MomentsConfig.transcribeLive() && MomentsConfig.whisperConfigured()) {
                transcription.enqueue(session.session.id, MJson.str(stored, "id"));
            }
        }
    }

}
