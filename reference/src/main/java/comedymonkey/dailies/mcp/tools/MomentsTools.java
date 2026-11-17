package comedymonkey.dailies.mcp.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.mcp.McpArgs;
import comedymonkey.dailies.mcp.McpProtocol;
import comedymonkey.dailies.mcp.McpSchema;
import comedymonkey.dailies.mcp.McpTool;
import comedymonkey.dailies.mcp.McpToolException;
import comedymonkey.dailies.moments.MJson;
import comedymonkey.dailies.moments.Moments;
import comedymonkey.dailies.moments.MomentsConfig;
import comedymonkey.dailies.moments.MomentsHttp;
import comedymonkey.dailies.moments.analysis.ClipBuilder;
import comedymonkey.dailies.moments.analysis.MomentAnalysis;
import comedymonkey.dailies.moments.storage.MomentsSession;
import comedymonkey.dailies.moments.track.GameplayTracker;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Queries over the moments logs (gameplay events, player interactions, voice transcripts) and the clip candidates
 * built from them. The logs are plain data that is safe to read from the HTTP thread; only the tools that touch
 * the game hop to the client thread.
 */
public final class MomentsTools {

    private MomentsTools() {
    }

    private static JsonObject ignoredEvents(McpArgs args) {
        String action = args.optString("action");
        action = action == null ? "list" : action.trim().toLowerCase(java.util.Locale.ROOT);
        java.util.List<String> wanted = new java.util.ArrayList<>();
        java.util.List<String> given = args.optStringList("entries");
        for (String entry : given == null ? java.util.List.<String>of() : given) {
            if (!entry.isBlank()) {
                wanted.add(entry.trim());
            }
        }
        java.util.LinkedHashSet<String> entries = new java.util.LinkedHashSet<>(MomentsConfig.ignoredEvents());
        switch (action) {
            case "list" -> {}
            case "add" -> entries.addAll(wanted);
            case "remove" -> wanted.forEach(remove -> entries.removeIf(entry -> entry.equalsIgnoreCase(remove)));
            case "set" -> {
                entries.clear();
                entries.addAll(wanted);
            }
            case "clear" -> entries.clear();
            default -> throw new McpToolException("Unknown action '" + action + "'. Use list, add, remove, set or clear");
        }
        if (!action.equals("list")) {
            if ((action.equals("add") || action.equals("remove") || action.equals("set")) && wanted.isEmpty()) {
                throw new McpToolException("'" + action + "' needs entries");
            }
            MomentsConfig.setIgnoredEvents(entries);
        }
        JsonObject json = new JsonObject();
        com.google.gson.JsonArray list = new com.google.gson.JsonArray();
        MomentsConfig.ignoredEvents().forEach(list::add);
        json.add("ignored", list);
        return json;
    }

    /** Where voice goes, in one plain sentence, for the status tool and the page (specification MOM-37) */
    public static String voiceDestination() {
        if (MomentsConfig.whisperConfigured()) {
            return "AI is on: the voice audio Moments captures is sent to the transcription server at " + MomentsConfig.whisperUrl()
                + ", and clip details to the summary server when one is set. Players on the excluded list are never captured or sent.";
        }
        return "AI is off: nothing is sent anywhere. Voice audio stays in the moments folder on this computer.";
    }

    private static JsonObject voiceExcluded(McpArgs args) {
        String action = args.optString("action");
        action = action == null ? "list" : action.trim().toLowerCase(Locale.ROOT);
        List<String> wanted = new ArrayList<>();
        List<String> given = args.optStringList("players");
        for (String entry : given == null ? List.<String>of() : given) {
            // The setting is a comma-separated line, so a name cannot hold a comma
            if (!entry.isBlank() && !entry.contains(",")) {
                wanted.add(entry.trim());
            }
        }
        java.util.LinkedHashSet<String> entries = new java.util.LinkedHashSet<>(MomentsConfig.voiceExcluded());
        switch (action) {
            case "list" -> {}
            case "add" -> wanted.forEach(add -> {
                if (entries.stream().noneMatch(entry -> entry.equalsIgnoreCase(add))) {
                    entries.add(add);
                }
            });
            case "remove" -> wanted.forEach(remove -> entries.removeIf(entry -> entry.equalsIgnoreCase(remove)));
            case "set" -> {
                entries.clear();
                entries.addAll(wanted);
            }
            case "clear" -> entries.clear();
            default -> throw new McpToolException("Unknown action '" + action + "'. Use list, add, remove, set or clear");
        }
        if (!action.equals("list")) {
            if ((action.equals("add") || action.equals("remove") || action.equals("set")) && wanted.isEmpty()) {
                throw new McpToolException("'" + action + "' needs players: names or uuids");
            }
            MomentsConfig.setVoiceExcluded(entries);
        }
        JsonObject json = new JsonObject();
        JsonArray list = new JsonArray();
        MomentsConfig.voiceExcluded().forEach(list::add);
        json.add("excluded", list);
        json.addProperty("voice_goes_to", voiceDestination());
        return json;
    }

    private static final String SESSION_DESCRIPTION = "Session id from moments_sessions_list, or 'current' / 'latest'. Default: the running session, else the latest one";

    private static McpSchema schema() {
        return McpSchema.object().string("session", SESSION_DESCRIPTION);
    }

    private static McpSchema withCommonFilters(McpSchema schema) {
        return schema
            .string("player", "Only records involving this player (name or uuid)")
            .any("from", "Only records at or after this real time: ISO-8601 (eg. 2026-01-31T20:15:00Z, or local time without zone) or epoch millis")
            .any("to", "Only records at or before this real time: ISO-8601 or epoch millis")
            .integer("limit", "Maximum number of records to return")
            .integer("offset", "Number of matching records to skip (for paging)");
    }

    public static void register(McpProtocol.Registrar registrar) {
        registrar.add("moments_status",
            "State of the moments tracker: whether a session is being tracked, record counts, voice chat availability, pending transcriptions, "
                + "and whether the configured Whisper and Ollama servers answer. Call this first when working with the moments_* tools.",
            schema(), McpTool.ThreadMode.HTTP, 0, MomentsTools::status);

        registrar.add("moments_ignored_events",
            "Read or change the list of events that are left out of every moments query, milestone list and clip, to cut noise. "
                + "An entry is an event type (block_mined), a type with a subject (mob_kill:minecraft:zombie, advancement:minecraft:story/root) or the name of a "
                + "custom or server event (close_call, animal_bred). * matches anything (job_*). Ignored events are still stored: removing an entry brings them back. "
                + "Saved in the mod's config (momentsIgnoredEvents). Always returns the list as it now is.",
            McpSchema.object()
                .string("action", "list (default), add, remove, set (replace the whole list) or clear")
                .stringArray("entries", "The entries to add, remove or set"),
            McpTool.ThreadMode.CLIENT, 10, MomentsTools::ignoredEvents);

        registrar.add("moments_voice_excluded",
            "Read or change the list of players whose voice is never captured for Moments or transcribed (momentsVoiceExcluded). "
                + "An entry is a player's name or uuid; case is ignored. Their voice is still heard live, and still recorded for playback when recordVoiceChat is on; "
                + "it never reaches the dialogue log, the loudness detector or the transcription server. Takes effect at once. Segments already captured stay, "
                + "but are no longer sent for transcription. Always returns the list as it now is, and where voice audio currently goes.",
            McpSchema.object()
                .string("action", "list (default), add, remove, set (replace the whole list) or clear")
                .stringArray("players", "The players to add, remove or set: names or uuids"),
            McpTool.ThreadMode.CLIENT, 10, MomentsTools::voiceExcluded);

        registrar.add("moments_sessions_list",
            "List the recorded play sessions, newest first: id, start/end time, world or server, local player, the replays recorded during the session and record counts.",
            McpSchema.object().integer("limit", "Maximum number of sessions (default 20)"),
            McpTool.ThreadMode.HTTP, 0, MomentsTools::sessionsList);

        registrar.add("moments_events_query",
            "Query the gameplay events of a session (voice_burst for laughter and shouting, with the speakers in metadata; mob_kill, player_kill, player_death, nearby_player_death, pet_death, biome_discovered, advancement, advancement_other, "
                + "item_crafted, block_placed, block_mined, tool_first_use, dimension_entered, structure_interaction, boss_encounter, boss_defeated, milestone, marker, custom, and with McpCraft installed its job board: job_posted, job_help_asked, job_claimed, "
                + "job_dropped, job_delivered, job_materials_complete, job_finished, job_removed, with the job in metadata; and for craft and contraptions: craft_assembled, craft_boarded, "
                + "craft_first_flight, altitude_record, speed_record, craft_lost, contraption_assembled, train_departed, train_arrived, with craft_id and metadata.sources saying whether the game, the server or both saw it). "
                + "Every event has real time, game time, position, dimension, biome, an importance score and, when a replay was being recorded, replay_uuid and replay_tick. "
                + "Kill and death events say how they were attributed in metadata.attribution (death_message, stats, heuristic, heuristic_unconfirmed).",
            withCommonFilters(schema())
                .any("type", "Event type, or an array of types")
                .string("subject", "Only events with this subject, eg. an entity type id (minecraft:zombie), item id, block id, biome id or advancement id")
                .bool("first_only", "Only events that were the first of their kind in this world")
                .string("keyword", "Only events whose type, subject or metadata contains this text (case-insensitive)"),
            McpTool.ThreadMode.HTTP, 0, MomentsTools::eventsQuery);

        registrar.add("moments_firsts",
            "First occurrences in a session, grouped by event type: first kill of each mob, first craft of each item, new biomes, new dimensions, ...",
            withCommonFilters(schema()), McpTool.ThreadMode.HTTP, 0, MomentsTools::firsts);

        registrar.add("moments_milestones",
            "The big moments of a session: milestones, advancements (own and other players'), boss encounters and defeats, player kills and deaths, and first entries into dimensions.",
            withCommonFilters(schema()), McpTool.ThreadMode.HTTP, 0, MomentsTools::milestones);

        registrar.add("moments_dialogue_query",
            "Query the voice chat segments of a session: speaker, start/end time, replay ticks, audio file, transcription status (pending, done, failed, empty) and text. "
                + "A segment that held sound but no words (laughter, a shout) is empty with non_speech = true.",
            withCommonFilters(schema())
                .string("speaker", "Only segments of this speaker (name or uuid). Same as 'player'")
                .string("status", "Only segments with this status: pending, done, failed or empty"),
            McpTool.ThreadMode.HTTP, 0, MomentsTools::dialogueQuery);

        registrar.add("moments_transcripts_search",
            "Search the voice chat transcripts of a session for a word or phrase (case-insensitive). Returns the matching lines with the lines spoken around them.",
            withCommonFilters(schema())
                .string("query", "Word or phrase to look for")
                .integer("context_lines", "How many lines before and after each match to include (default 2)")
                .required("query"),
            McpTool.ThreadMode.HTTP, 0, MomentsTools::transcriptsSearch);

        registrar.add("moments_interactions_query",
            "Query who was together with whom: meetings (two players staying close), group_formed / group_changed / group_dissolved (three or more) and group_move. "
                + "Includes the ones still going on (ongoing = true).",
            withCommonFilters(schema())
                .any("type", "Interaction type, or an array of types")
                .stringArray("players", "Only interactions that involve all of these players (names or uuids)")
                .number("min_duration", "Only interactions that lasted at least this many seconds"),
            McpTool.ThreadMode.HTTP, 0, MomentsTools::interactionsQuery);

        registrar.add("moments_notable",
            "The most important moments of a session, best first, each with its enriched context: what was said around it, who was there, what else happened and where.",
            withCommonFilters(schema())
                .number("min_importance", "Only events with at least this importance (0-100, default 0)")
                .any("type", "Event type, or an array of types"),
            McpTool.ThreadMode.HTTP, 0, MomentsTools::notable);

        registrar.add("moments_context",
            "Enriched context for one event or one point in time: the dialogue in the seconds before and after, overlapping interactions, other events, location and the most recent advancements.",
            schema()
                .string("event_id", "Event id from moments_events_query")
                .any("time", "Real time (ISO-8601 or epoch millis), instead of event_id")
                .integer("seconds", "Seconds before and after to look at (default: the momentsContextSeconds option)"),
            McpTool.ThreadMode.HTTP, 0, MomentsTools::context);

        registrar.add("moments_clips_generate",
            "Build clip candidates for a session and store them (replacing the previous candidates). Events whose windows overlap become one clip, clips are widened so they "
                + "don't cut a sentence in half, and dense conversations without events become clips of their own. Each clip has a title, summary, times, replay uuid and ticks, "
                + "the events, players, the full transcript and an importance score. With summarize the heuristic title/summary is returned right away with summary_pending = true "
                + "and the Ollama model's version shows up in moments_clips_list once it is ready (summary_source = ollama).",
            withCommonFilters(schema())
                .number("pre_roll", "Seconds before an event that the clip starts (default: momentsClipPreRollSeconds)")
                .number("post_roll", "Seconds after an event that the clip continues (default: momentsClipPostRollSeconds)")
                .number("min_importance", "Only events with at least this importance start a clip (default 25)")
                .integer("max_clips", "Keep at most this many clips, the most important ones (default 20)")
                .bool("summarize", "Ask the Ollama model for titles and summaries (default: the momentsSummarizeClips option)")
                .any("type", "Only these event types start a clip: a type or an array of types. Also leaves out the conversation-only clips")
                .string("keyword", "Only clips whose events or transcript contain this text"),
            McpTool.ThreadMode.HTTP, 0, MomentsTools::clipsGenerate);

        registrar.add("moments_clips_list",
            "List the stored clip candidates of a session (from the last moments_clips_generate), with the Ollama titles and summaries where they are ready. "
                + "Each clip has 'locations': every recording that holds it (the original, and any made from it by combining or trimming), with the stretches of the clip there in that "
                + "recording's own ticks and whether part of it was cut (cut, removed_ticks). Pass a location's recording_id to moments_cue to open that one.",
            withCommonFilters(schema())
                .any("type", "Only clips containing an event of this type (or one of an array of types)")
                .number("min_importance", "Only clips with at least this importance")
                .string("keyword", "Only clips whose title, summary, events or transcript contain this text"),
            McpTool.ThreadMode.HTTP, 0, MomentsTools::clipsList);

        registrar.add("moments_everyone",
            "Everyone's events over a session, one row per person, for the page's session strip (PLAY-18): this session's own, and those of every other session of the "
                + "same stretch of play, such as a friend's imported from a shared session (theirs = true), or one of your own on a linked recording. Each row has the "
                + "player, the session, and its events with time, type, description, score and replay position.",
            schema().number("min_importance", "Only events with at least this importance (default 0)"),
            McpTool.ThreadMode.HTTP, 0, MomentsTools::everyone);

        registrar.add("moments_transcribe_pending",
            "Queue the pending and failed voice segments of a session for transcription again, eg. after the Whisper server came back or when live transcription is off. Returns counts.",
            schema(), McpTool.ThreadMode.HTTP, 0, MomentsTools::transcribePending);

        registrar.add("moments_event_add",
            "Add a custom event at the current moment of the running session (position, game time and replay tick are filled in).",
            McpSchema.object()
                .string("label", "What happened, in a few words")
                .string("type", "Optional category for the event, stored as its subject (eg. 'funny', 'build_finished')")
                .obj("metadata", "Optional extra data to store with the event")
                .required("label"),
            MomentsTools::eventAdd);

        registrar.add("moments_cue",
            "Cue up a clip candidate or a single event for viewing: jumps to it in the open replay when that holds it, otherwise opens the original recording, or failing that the newest "
                + "combined or trimmed recording that holds it, and pauses there, ready to play. The reply lists every recording that holds it (locations) and says cut_from_open when the "
                + "open recording had it cut out. A moment whose recording id is lost is found by its time. "
                + "Give clip_id or event_id. Opening a replay takes a while: the reply says so (opening = true) and the jump follows by itself once the replay has loaded. "
                + "Refuses to pull the player out of a live world unless leave_world is true. The moments web page (GET / on this server) uses this for its Cue buttons.",
            schema()
                .string("clip_id", "Clip id from moments_clips_generate / moments_clips_list")
                .string("event_id", "Event id from moments_events_query, instead of clip_id")
                .number("lead_seconds", "For an event: how long before it to start (default 5)")
                .bool("pause", "Pause at the cue point (default true)")
                .bool("camera", "Also add the suggested smart camera (suggested_camera in moments_clips_list) as a keyframe at the cue point, following the player who recorded. Undo with editor_undo (default false)")
                .bool("leave_world", "Allow leaving the world that is being played right now to open the replay (default false)")
                .string("recording_id", "Open this recording, one of the clip's locations, instead of the best one. Fails with CUT when it does not hold the moment"),
            McpTool.ThreadMode.HTTP, 0, MomentsTools::cue);

        registrar.add("moments_clip_goto",
            "Jump to the start of a clip candidate in the open replay. Works when the open replay holds the clip (the recording it was made in, or one combined or trimmed from it); "
                + "says CUT and lists the recordings that still hold it when it was cut from this one, and otherwise says which replay to open.",
            schema().string("clip_id", "Clip id from moments_clips_generate / moments_clips_list").required("clip_id"),
            MomentsTools::clipGoto);

        registrar.add("moments_clip_to_scene",
            "Make a scene of the open replay from a clip candidate: a new scene named after the clip, with its export range set to the clip and the suggested smart camera "
                + "at its start, following the player who recorded. The new scene becomes the current one and the replay jumps to the clip's start, paused. All of it is one step: "
                + "editor_undo takes the whole scene away again. Works when the open replay holds the clip, as moments_clip_goto does; call moments_cue first to open the right one.",
            schema()
                .string("clip_id", "Clip id from moments_clips_generate / moments_clips_list")
                .string("name", "Name of the scene (default: the clip's title)")
                .bool("camera", "Add the suggested smart camera at the clip's start (default true)")
                .required("clip_id"),
            MomentsTools::clipToScene);
    }

    // --- Shared argument handling ---

    private static MomentsSession session(McpArgs args) {
        String wanted = args.optString("session");
        MomentsSession current = Moments.currentSession();
        if (wanted == null || wanted.isBlank()) {
            if (current != null) {
                return current;
            }
            wanted = "latest";
        }
        if (wanted.equalsIgnoreCase("current")) {
            if (current == null) {
                throw new McpToolException("No session is being tracked right now (the player is not in a world, or momentsEnabled is off). Use session 'latest' or an id from moments_sessions_list");
            }
            return current;
        }
        if (wanted.equalsIgnoreCase("latest")) {
            List<String> ids = Moments.storage().listSessionIds();
            if (ids.isEmpty()) {
                throw new McpToolException("No sessions have been recorded yet. A session starts when the player joins a world or server with momentsEnabled on");
            }
            wanted = ids.get(0);
        }
        MomentsSession session = Moments.storage().getSession(wanted);
        if (session == null) {
            throw new McpToolException("No session '" + wanted + "'. Use moments_sessions_list to see the available ones");
        }
        return session;
    }

    private static long time(McpArgs args, String name, long fallback) {
        JsonElement element = args.element(name);
        if (element == null) {
            return fallback;
        }
        if (!element.isJsonPrimitive()) {
            throw new McpToolException("Argument '" + name + "' must be an ISO-8601 time or epoch millis");
        }
        String text = element.getAsString().trim();
        try {
            return (long) Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
        }
        try {
            return Instant.parse(text).toEpochMilli();
        } catch (Exception ignored) {
        }
        try {
            return OffsetDateTime.parse(text).toInstant().toEpochMilli();
        } catch (Exception ignored) {
        }
        try {
            return LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception ignored) {
        }
        throw new McpToolException("Argument '" + name + "' must be an ISO-8601 time (eg. 2026-01-31T20:15:00Z) or epoch millis, got '" + text + "'");
    }

    /** A string or an array of strings, lower-cased. Null when not given */
    @Nullable
    private static Set<String> stringSet(McpArgs args, String name) {
        JsonElement element = args.element(name);
        if (element == null) {
            return null;
        }
        Set<String> set = new HashSet<>();
        if (element.isJsonPrimitive()) {
            set.add(element.getAsString().trim().toLowerCase(Locale.ROOT));
        } else if (element.isJsonArray()) {
            for (JsonElement entry : element.getAsJsonArray()) {
                if (!entry.isJsonPrimitive()) {
                    throw new McpToolException("Argument '" + name + "' must be a string or an array of strings");
                }
                set.add(entry.getAsString().trim().toLowerCase(Locale.ROOT));
            }
        } else {
            throw new McpToolException("Argument '" + name + "' must be a string or an array of strings");
        }
        return set;
    }

    @Nullable
    private static String lower(McpArgs args, String name) {
        String value = args.optString(name);
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean inTimeRange(JsonObject record, long from, long to) {
        return MJson.endTime(record) >= from && MJson.time(record) <= to;
    }

    private static boolean eventInvolves(JsonObject event, String player) {
        JsonObject metadata = MJson.obj(event, "metadata");
        if (ClipBuilder.matches(MJson.obj(event, "player"), player) || ClipBuilder.matches(MJson.obj(metadata, "victim"), player)
                || ClipBuilder.matches(MJson.obj(metadata, "killer"), player)) {
            return true;
        }
        for (JsonElement element : MJson.arr(metadata, "participants")) {
            if (element.isJsonObject() && ClipBuilder.matches(element.getAsJsonObject(), player)) {
                return true;
            }
        }
        return false;
    }

    private static JsonObject page(McpArgs args, MomentsSession session, String name, List<JsonObject> matches, int defaultLimit) {
        int limit = args.optInteger("limit", defaultLimit);
        int offset = args.optInteger("offset", 0);
        if (limit < 1 || offset < 0) {
            throw new McpToolException("limit must be at least 1 and offset at least 0");
        }
        JsonArray array = new JsonArray();
        for (int i = offset; i < matches.size() && array.size() < limit; i++) {
            array.add(matches.get(i));
        }
        JsonObject json = new JsonObject();
        json.addProperty("session", session.id);
        json.addProperty("total", matches.size());
        json.addProperty("offset", offset);
        json.addProperty("truncated", offset + array.size() < matches.size());
        json.add(name, array);
        return json;
    }

    private static MomentAnalysis analysis(MomentsSession session, int contextSeconds) {
        return new MomentAnalysis(session, Moments.ongoingInteractions(session), contextSeconds);
    }

    /** The events of a session that pass the common filters and the type filter */
    private static List<JsonObject> filterEvents(McpArgs args, MomentAnalysis analysis, @Nullable Predicate<JsonObject> extra) {
        String player = lower(args, "player");
        long from = time(args, "from", Long.MIN_VALUE);
        long to = time(args, "to", Long.MAX_VALUE);
        Set<String> types = stringSet(args, "type");

        List<JsonObject> matches = new ArrayList<>();
        for (JsonObject event : analysis.events) {
            if (!inTimeRange(event, from, to) || (types != null && !types.contains(MJson.str(event, "type", "")))
                    || (player != null && !eventInvolves(event, player)) || (extra != null && !extra.test(event))) {
                continue;
            }
            matches.add(event);
        }
        return matches;
    }

    private static JsonObject withImportance(JsonObject event, MomentAnalysis analysis) {
        JsonObject copy = event.deepCopy();
        copy.addProperty("importance", analysis.score(event));
        copy.addProperty("description", MomentAnalysis.describe(event));
        return copy;
    }

    // --- Status and sessions ---

    private static JsonObject counts(MomentsSession session) {
        int pending = 0, failed = 0, done = 0, empty = 0;
        for (JsonObject segment : session.list(MomentsSession.Kind.DIALOGUE)) {
            switch (MJson.str(segment, "status", "pending")) {
                case "done" -> done += 1;
                case "failed" -> failed += 1;
                case "empty" -> empty += 1;
                default -> pending += 1;
            }
        }
        JsonObject json = new JsonObject();
        json.addProperty("events", session.count(MomentsSession.Kind.EVENTS));
        json.addProperty("interactions", session.count(MomentsSession.Kind.INTERACTIONS));
        json.addProperty("dialogue_segments", session.count(MomentsSession.Kind.DIALOGUE));
        json.addProperty("dialogue_pending", pending);
        json.addProperty("dialogue_done", done);
        json.addProperty("dialogue_empty", empty);
        json.addProperty("dialogue_failed", failed);
        json.addProperty("clips", session.clips().size());
        return json;
    }

    private static JsonObject sessionJson(MomentsSession session) {
        JsonObject json = session.meta();
        json.addProperty("id", session.id);
        json.addProperty("running", session == Moments.currentSession());
        json.addProperty("folder", session.dir.toAbsolutePath().toString());
        json.add("counts", counts(session));
        return json;
    }

    private static JsonObject status(McpArgs args) {
        // Both checks run at the same time and give up after two seconds
        CompletableFuture<Boolean> whisper = MomentsHttp.whisperReachable();
        CompletableFuture<Boolean> ollama = MomentsHttp.ollamaReachable();

        JsonObject json = new JsonObject();
        json.addProperty("enabled", MomentsConfig.enabled());
        MomentsSession current = Moments.currentSession();
        json.addProperty("tracking", current != null);
        json.addProperty("in_replay", Host.get().inPlayback());
        json.addProperty("storage_folder", Moments.storage().root().toAbsolutePath().toString());

        MomentsSession session = null;
        try {
            session = session(args);
        } catch (McpToolException ignored) {
            // Nothing recorded yet
        }
        json.add("session", session == null ? null : sessionJson(session));

        JsonObject voice = new JsonObject();
        String voiceReason = Moments.voiceUnavailableReason();
        voice.addProperty("available", voiceReason == null);
        voice.addProperty("source", "Simple Voice Chat");
        if (voiceReason != null) {
            voice.addProperty("reason", voiceReason);
        }
        json.add("voice", voice);

        JsonObject transcription = new JsonObject();
        transcription.addProperty("configured", MomentsConfig.whisperConfigured());
        if (!MomentsConfig.whisperConfigured()) {
            transcription.addProperty("note", "Optional and switched off (momentsAiEnabled is false, or momentsWhisperUrl is empty). Voice segments are logged without text and their audio is kept");
        }
        transcription.addProperty("live", MomentsConfig.transcribeLive());
        transcription.addProperty("queued", Moments.transcription().queueSize());
        transcription.addProperty("whisper_url", MomentsConfig.whisperUrl());
        transcription.addProperty("whisper_model", MomentsConfig.whisperModel());
        transcription.addProperty("whisper_language", MomentsConfig.whisperLanguage().isEmpty() ? "auto" : MomentsConfig.whisperLanguage());
        transcription.addProperty("whisper_reachable", whisper.join());
        transcription.addProperty("last_error", Moments.transcription().lastError());
        json.add("transcription", transcription);

        JsonObject summaries = new JsonObject();
        summaries.addProperty("configured", MomentsConfig.ollamaConfigured());
        if (!MomentsConfig.ollamaConfigured()) {
            summaries.addProperty("note", "Optional and switched off (momentsAiEnabled is false, or momentsOllamaUrl is empty). Clips keep the built-in titles and summaries");
        }
        summaries.addProperty("enabled", MomentsConfig.summarizeClips() && MomentsConfig.ollamaConfigured());
        summaries.addProperty("pending", Moments.summarizer().pending());
        summaries.addProperty("ollama_url", MomentsConfig.ollamaUrl());
        summaries.addProperty("ollama_model", MomentsConfig.ollamaModel());
        summaries.addProperty("ollama_reachable", ollama.join());
        summaries.addProperty("last_error", Moments.summarizer().lastError());
        json.add("summaries", summaries);

        JsonObject privacy = new JsonObject();
        JsonArray excluded = new JsonArray();
        MomentsConfig.voiceExcluded().forEach(excluded::add);
        privacy.add("voice_excluded", excluded);
        privacy.addProperty("voice_goes_to", voiceDestination());
        json.add("privacy", privacy);
        json.addProperty("event_pictures", MomentsConfig.eventPictures());

        GameplayTracker gameplay = Moments.gameplay();
        if (gameplay != null) {
            JsonObject statistics = gameplay.statsTotals();
            statistics.addProperty("poll_seconds", MomentsConfig.statsPollSeconds());
            json.add("statistics", statistics);
        }
        return json;
    }

    /** A session as the lists show it, marked when it is a friend's taken in from a shared session (FILE-15) */
    private static JsonObject sessionEntry(MomentsSession session) {
        JsonObject json = sessionJson(session);
        boolean theirs = comedymonkey.dailies.moments.storage.MomentsShare.theirs(session);
        json.addProperty("theirs", theirs);
        if (theirs) {
            json.add("shared_by", MJson.obj(session.meta(), "shared_by") == null ? new JsonObject() : MJson.obj(session.meta(), "shared_by").deepCopy());
        }
        return json;
    }

    private static JsonObject sessionsList(McpArgs args) {
        int limit = args.optInteger("limit", 20);
        if (limit < 1) {
            throw new McpToolException("limit must be at least 1");
        }
        List<String> ids = Moments.storage().listSessionIds();
        JsonArray array = new JsonArray();
        for (String id : ids) {
            if (array.size() >= limit) {
                break;
            }
            MomentsSession session = Moments.storage().getSession(id);
            if (session != null) {
                array.add(sessionEntry(session));
            }
        }
        JsonObject json = new JsonObject();
        json.addProperty("total", ids.size());
        json.add("sessions", array);
        return json;
    }

    // --- Everyone's events (PLAY-18) ---

    private static JsonObject everyone(McpArgs args) {
        MomentsSession session = session(args);
        double minimum = args.optNumber("min_importance", 0);
        long start = MJson.lng(session.meta(), "start_real_time", 0);
        long end = MJson.lng(session.meta(), "end_real_time", 0);
        if (end <= 0) {
            end = System.currentTimeMillis();
        }
        // The recordings linked to this session's, as the other players' sessions name them
        Set<String> linked = new HashSet<>();
        for (JsonElement replay : MJson.arr(session.meta(), "replays")) {
            try {
                linked.addAll(Host.get().linkedRecordings(replay.getAsString()));
            } catch (RuntimeException ignored) {
                // Without links only the time and the world say which sessions go together
            }
        }
        JsonArray rows = new JsonArray();
        rows.add(row(session, minimum, false));
        String world = MJson.str(session.meta(), "world_key");
        for (String id : Moments.storage().listSessionIds()) {
            MomentsSession other = Moments.storage().getSession(id);
            if (other == null || other == session) {
                continue;
            }
            long otherStart = MJson.lng(other.meta(), "start_real_time", 0);
            long otherEnd = MJson.lng(other.meta(), "end_real_time", 0);
            if (otherEnd <= 0) {
                otherEnd = otherStart > 0 ? Math.max(otherStart, System.currentTimeMillis()) : 0;
            }
            boolean overlaps = otherStart > 0 && otherStart <= end && otherEnd >= start;
            boolean sameRecording = false;
            for (JsonElement replay : MJson.arr(other.meta(), "replays")) {
                sameRecording |= replay.isJsonPrimitive() && linked.contains(replay.getAsString());
            }
            boolean sameWorld = world != null && world.equals(MJson.str(other.meta(), "world_key"));
            String me = MJson.str(MJson.obj(session.meta(), "local_player"), "uuid", "");
            boolean someoneElse = !me.equals(MJson.str(MJson.obj(other.meta(), "local_player"), "uuid", ""));
            if (overlaps && someoneElse && (sameRecording || sameWorld)) {
                rows.add(row(other, minimum, true));
            }
        }
        JsonObject json = new JsonObject();
        json.addProperty("session", session.id);
        json.addProperty("start_real_time", start);
        json.addProperty("end_real_time", end);
        json.add("rows", rows);
        return json;
    }

    private static JsonObject row(MomentsSession session, double minimum, boolean other) {
        MomentAnalysis analysis = analysis(session, MomentsConfig.contextSeconds());
        JsonObject row = new JsonObject();
        row.add("player", MJson.obj(session.meta(), "local_player") == null ? new JsonObject() : MJson.obj(session.meta(), "local_player").deepCopy());
        row.addProperty("session", session.id);
        row.addProperty("theirs", comedymonkey.dailies.moments.storage.MomentsShare.theirs(session));
        row.addProperty("own", !other);
        JsonArray events = new JsonArray();
        for (JsonObject event : analysis.events) {
            if (MomentsConfig.isIgnored(MJson.str(event, "type", ""), MJson.str(event, "subject"))) {
                continue;
            }
            double score = analysis.score(event);
            if (score < minimum) {
                continue;
            }
            JsonObject brief = new JsonObject();
            brief.addProperty("id", MJson.str(event, "id"));
            brief.addProperty("type", MJson.str(event, "type"));
            brief.addProperty("real_time", MJson.lng(event, "real_time", 0));
            brief.addProperty("description", MomentAnalysis.describe(event));
            brief.addProperty("importance", MJson.round(score));
            brief.addProperty("replay_uuid", MJson.str(event, "replay_uuid"));
            brief.addProperty("replay_tick", MJson.lng(event, "replay_tick", -1));
            events.add(brief);
        }
        row.add("events", events);
        return row;
    }

    // --- Events ---

    private static JsonObject eventsQuery(McpArgs args) {
        MomentsSession session = session(args);
        MomentAnalysis analysis = analysis(session, MomentsConfig.contextSeconds());
        String subject = lower(args, "subject");
        boolean firstOnly = args.optBool("first_only", false);
        String keyword = lower(args, "keyword");

        List<JsonObject> matches = filterEvents(args, analysis, event -> {
            if (firstOnly && !MJson.bool(event, "first")) {
                return false;
            }
            if (subject != null && !subject.equals(MJson.str(event, "subject", "").toLowerCase(Locale.ROOT))) {
                return false;
            }
            return keyword == null || MJson.containsIgnoreCase(MJson.str(event, "type"), keyword) || MJson.containsIgnoreCase(MJson.str(event, "subject"), keyword)
                || MJson.containsIgnoreCase(String.valueOf(event.get("metadata")), keyword);
        });
        List<JsonObject> scored = new ArrayList<>();
        // Only the page that is returned needs a score
        int offset = Math.max(0, args.optInteger("offset", 0));
        int limit = Math.max(1, args.optInteger("limit", 100));
        for (int i = 0; i < matches.size(); i++) {
            scored.add(i >= offset && i < offset + limit ? withImportance(matches.get(i), analysis) : matches.get(i));
        }
        return page(args, session, "events", scored, 100);
    }

    private static JsonObject grouped(McpArgs args, MomentsSession session, MomentAnalysis analysis, List<JsonObject> events) {
        int limit = args.optInteger("limit", 500);
        Map<String, JsonArray> byType = new LinkedHashMap<>();
        int returned = 0;
        for (JsonObject event : events) {
            if (returned >= limit) {
                break;
            }
            returned += 1;
            JsonObject compact = analysis.compactEvent(event, MJson.time(event));
            compact.remove("offset_seconds");
            compact.addProperty("real_time", MJson.time(event));
            compact.addProperty("real_time_iso", MJson.iso(MJson.time(event)));
            byType.computeIfAbsent(MJson.str(event, "type", "event"), key -> new JsonArray()).add(compact);
        }
        JsonObject groups = new JsonObject();
        for (Map.Entry<String, JsonArray> entry : byType.entrySet()) {
            groups.add(entry.getKey(), entry.getValue());
        }
        JsonObject json = new JsonObject();
        json.addProperty("session", session.id);
        json.addProperty("total", events.size());
        json.addProperty("truncated", returned < events.size());
        json.add("by_type", groups);
        return json;
    }

    private static JsonObject firsts(McpArgs args) {
        MomentsSession session = session(args);
        MomentAnalysis analysis = analysis(session, MomentsConfig.contextSeconds());
        return grouped(args, session, analysis, filterEvents(args, analysis, event -> MJson.bool(event, "first")));
    }

    private static final Set<String> MILESTONE_TYPES = Set.of("milestone", "advancement", "advancement_other", "boss_encounter", "boss_defeated",
        "player_kill", "player_death", "marker", "custom", "job_posted", "job_finished", "job_materials_complete");

    private static JsonObject milestones(McpArgs args) {
        MomentsSession session = session(args);
        MomentAnalysis analysis = analysis(session, MomentsConfig.contextSeconds());
        return grouped(args, session, analysis, filterEvents(args, analysis, event -> {
            String type = MJson.str(event, "type", "");
            return MILESTONE_TYPES.contains(type) || (type.equals("dimension_entered") && MJson.bool(event, "first"));
        }));
    }

    private static JsonObject notable(McpArgs args) {
        MomentsSession session = session(args);
        MomentAnalysis analysis = analysis(session, MomentsConfig.contextSeconds());
        double minImportance = args.optNumber("min_importance", 0);

        record Scored(JsonObject event, double score) {}
        List<Scored> scored = new ArrayList<>();
        for (JsonObject event : filterEvents(args, analysis, null)) {
            double score = analysis.score(event);
            if (score >= minImportance) {
                scored.add(new Scored(event, score));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());

        List<JsonObject> moments = new ArrayList<>();
        int offset = Math.max(0, args.optInteger("offset", 0));
        int limit = Math.max(1, args.optInteger("limit", 10));
        for (int i = 0; i < scored.size(); i++) {
            JsonObject json = new JsonObject();
            // The context is the expensive part, so only build it for the page that is returned
            if (i >= offset && i < offset + limit) {
                JsonObject event = scored.get(i).event();
                json.addProperty("importance", scored.get(i).score());
                json.addProperty("description", MomentAnalysis.describe(event));
                json.add("event", event);
                json.add("context", analysis.context(MJson.time(event), event));
            }
            moments.add(json);
        }
        return page(args, session, "moments", moments, 10);
    }

    private static JsonObject context(McpArgs args) {
        MomentsSession session = session(args);
        int seconds = args.optInteger("seconds", MomentsConfig.contextSeconds());
        if (seconds < 1 || seconds > 3600) {
            throw new McpToolException("seconds must be between 1 and 3600");
        }
        MomentAnalysis analysis = analysis(session, seconds);

        String eventId = args.optString("event_id");
        if ((eventId == null) == !args.has("time")) {
            throw new McpToolException("Give exactly one of 'event_id' or 'time'");
        }
        JsonObject json = new JsonObject();
        json.addProperty("session", session.id);
        if (eventId != null) {
            JsonObject event = analysis.findEvent(eventId);
            if (event == null) {
                throw new McpToolException("No event '" + eventId + "' in session " + session.id + ". Use moments_events_query to find event ids");
            }
            json.add("event", withImportance(event, analysis));
            json.add("context", analysis.context(MJson.time(event), event));
        } else {
            json.add("context", analysis.context(time(args, "time", 0), null));
        }
        return json;
    }

    // --- Dialogue ---

    private static List<JsonObject> filterDialogue(McpArgs args, MomentsSession session) {
        String speaker = lower(args, "speaker");
        if (speaker == null) {
            speaker = lower(args, "player");
        }
        long from = time(args, "from", Long.MIN_VALUE);
        long to = time(args, "to", Long.MAX_VALUE);
        String status = lower(args, "status");

        List<JsonObject> matches = new ArrayList<>();
        for (JsonObject segment : session.list(MomentsSession.Kind.DIALOGUE)) {
            if (!inTimeRange(segment, from, to) || (speaker != null && !ClipBuilder.matches(MJson.obj(segment, "speaker"), speaker))
                    || (status != null && !status.equals(MJson.str(segment, "status", "pending")))) {
                continue;
            }
            matches.add(segment);
        }
        return matches;
    }

    private static JsonObject dialogueQuery(McpArgs args) {
        MomentsSession session = session(args);
        return page(args, session, "dialogue", filterDialogue(args, session), 100);
    }

    private static JsonObject transcriptLine(JsonObject segment) {
        JsonObject json = new JsonObject();
        json.addProperty("dialogue_id", MJson.str(segment, "id"));
        json.addProperty("speaker", MJson.str(MJson.obj(segment, "speaker"), "name"));
        json.addProperty("real_time", MJson.time(segment));
        json.addProperty("real_time_iso", MJson.iso(MJson.time(segment)));
        json.addProperty("text", MJson.str(segment, "text", ""));
        if (segment.has("start_replay_uuid")) {
            json.add("replay_uuid", segment.get("start_replay_uuid"));
            json.add("replay_tick", segment.get("start_replay_tick"));
        }
        return json;
    }

    private static JsonObject transcriptsSearch(McpArgs args) {
        MomentsSession session = session(args);
        String query = args.string("query").trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            throw new McpToolException("query must not be empty");
        }
        int contextLines = args.optInteger("context_lines", 2);
        if (contextLines < 0 || contextLines > 50) {
            throw new McpToolException("context_lines must be between 0 and 50");
        }

        // The conversation as it was spoken: every transcribed line of the session, in order
        List<JsonObject> lines = new ArrayList<>();
        for (JsonObject segment : session.list(MomentsSession.Kind.DIALOGUE)) {
            if (MomentAnalysis.hasText(segment)) {
                lines.add(segment);
            }
        }
        lines.sort(Comparator.comparingLong(MJson::time));
        Set<JsonObject> allowed = new HashSet<>(filterDialogue(args, session));

        List<JsonObject> matches = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            JsonObject line = lines.get(i);
            if (!allowed.contains(line) || !MJson.containsIgnoreCase(MJson.str(line, "text"), query)) {
                continue;
            }
            JsonObject match = transcriptLine(line);
            JsonArray before = new JsonArray();
            for (int j = Math.max(0, i - contextLines); j < i; j++) {
                before.add(transcriptLine(lines.get(j)));
            }
            JsonArray after = new JsonArray();
            for (int j = i + 1; j < lines.size() && j <= i + contextLines; j++) {
                after.add(transcriptLine(lines.get(j)));
            }
            match.add("before", before);
            match.add("after", after);
            matches.add(match);
        }
        JsonObject json = page(args, session, "matches", matches, 50);
        json.addProperty("query", query);
        json.addProperty("transcribed_lines", lines.size());
        return json;
    }

    private static JsonObject transcribePending(McpArgs args) {
        MomentsSession session = session(args);
        if (!MomentsConfig.whisperConfigured()) {
            int waiting = 0;
            for (JsonObject segment : session.list(MomentsSession.Kind.DIALOGUE)) {
                String status = MJson.str(segment, "status", "pending");
                if (status.equals("pending") || status.equals("failed")) {
                    waiting += 1;
                }
            }
            JsonObject json = ok();
            json.addProperty("session", session.id);
            json.addProperty("queued", 0);
            json.addProperty("untranscribed", waiting);
            json.addProperty("whisper_configured", false);
            json.addProperty("note", "Nothing was queued: transcription is optional and switched off. Turn on momentsAiEnabled (config_set) and call this again; the audio of these segments is kept");
            return json;
        }
        int pending = 0, failed = 0;
        for (JsonObject segment : session.list(MomentsSession.Kind.DIALOGUE)) {
            String status = MJson.str(segment, "status", "pending");
            String id = MJson.str(segment, "id");
            if (status.equals("failed")) {
                failed += 1;
                session.update(MomentsSession.Kind.DIALOGUE, id, json -> {
                    json.addProperty("status", "pending");
                    json.addProperty("attempts", 0);
                });
            } else if (status.equals("pending")) {
                pending += 1;
            } else {
                continue;
            }
            Moments.transcription().enqueue(session.id, id);
        }
        JsonObject json = ok();
        json.addProperty("session", session.id);
        json.addProperty("queued", pending + failed);
        json.addProperty("were_pending", pending);
        json.addProperty("were_failed", failed);
        json.addProperty("whisper_reachable", MomentsHttp.whisperReachable().join());
        json.addProperty("note", "Transcription runs in the background. Poll moments_dialogue_query or moments_status");
        return json;
    }

    // --- Interactions ---

    private static JsonObject interactionsQuery(McpArgs args) {
        MomentsSession session = session(args);
        String player = lower(args, "player");
        long from = time(args, "from", Long.MIN_VALUE);
        long to = time(args, "to", Long.MAX_VALUE);
        Set<String> types = stringSet(args, "type");
        Set<String> players = stringSet(args, "players");
        if (players == null) {
            players = new HashSet<>();
        }
        if (player != null) {
            players.add(player);
        }
        double minDuration = args.optNumber("min_duration", 0);

        List<JsonObject> all = session.list(MomentsSession.Kind.INTERACTIONS);
        all.addAll(Moments.ongoingInteractions(session));
        List<JsonObject> matches = new ArrayList<>();
        for (JsonObject interaction : all) {
            if (!inTimeRange(interaction, from, to) || (types != null && !types.contains(MJson.str(interaction, "type", "")))
                    || MJson.dbl(interaction, "duration_seconds", 0) < minDuration) {
                continue;
            }
            boolean involvesAll = true;
            for (String wanted : players) {
                boolean found = false;
                for (JsonElement element : MJson.arr(interaction, "players")) {
                    found |= ClipBuilder.matches(element.getAsJsonObject(), wanted);
                }
                involvesAll &= found;
            }
            if (involvesAll) {
                matches.add(interaction);
            }
        }
        return page(args, session, "interactions", matches, 100);
    }

    // --- Clips ---

    private static JsonObject clipsGenerate(McpArgs args) {
        MomentsSession session = session(args);
        MomentAnalysis analysis = analysis(session, MomentsConfig.contextSeconds());

        ClipBuilder.Params params = new ClipBuilder.Params();
        double preRoll = args.optNumber("pre_roll", MomentsConfig.clipPreRollSeconds());
        double postRoll = args.optNumber("post_roll", MomentsConfig.clipPostRollSeconds());
        if (preRoll < 0 || preRoll > 3600 || postRoll < 0 || postRoll > 3600) {
            throw new McpToolException("pre_roll and post_roll must be between 0 and 3600 seconds");
        }
        params.preRollMillis = (long) (preRoll * 1000);
        params.postRollMillis = (long) (postRoll * 1000);
        params.minImportance = args.optNumber("min_importance", params.minImportance);
        params.maxClips = args.optInteger("max_clips", params.maxClips);
        if (params.maxClips < 1) {
            throw new McpToolException("max_clips must be at least 1");
        }
        params.player = lower(args, "player");
        params.types = stringSet(args, "type");
        params.from = time(args, "from", Long.MIN_VALUE);
        params.to = time(args, "to", Long.MAX_VALUE);
        params.keyword = lower(args, "keyword");
        // Optional: without an Ollama server the clips simply keep the built-in titles and summaries
        boolean summarize = args.optBool("summarize", MomentsConfig.summarizeClips()) && MomentsConfig.ollamaConfigured();

        List<JsonObject> clips = ClipBuilder.generate(analysis, params);

        // A clip that did not change keeps the summary the model already wrote for it
        Map<String, JsonObject> previous = new LinkedHashMap<>();
        for (JsonObject clip : session.clips()) {
            previous.put(MJson.str(clip, "id", ""), clip);
        }
        List<String> toSummarize = new ArrayList<>();
        for (JsonObject clip : clips) {
            JsonObject old = previous.get(MJson.str(clip, "id", ""));
            if (old != null && "ollama".equals(MJson.str(old, "summary_source")) && MJson.endTime(old) == MJson.endTime(clip)
                    && String.valueOf(old.get("transcript")).equals(String.valueOf(clip.get("transcript")))
                    && MJson.arr(old, "events").size() == MJson.arr(clip, "events").size()) {
                for (String key : new String[]{"title", "summary", "summary_source", "summary_model"}) {
                    clip.add(key, old.get(key));
                }
            } else if (summarize) {
                clip.addProperty("summary_pending", true);
                toSummarize.add(MJson.str(clip, "id"));
            }
        }

        JsonObject parameters = new JsonObject();
        parameters.addProperty("pre_roll", preRoll);
        parameters.addProperty("post_roll", postRoll);
        parameters.addProperty("min_importance", params.minImportance);
        parameters.addProperty("max_clips", params.maxClips);
        parameters.addProperty("summarize", summarize);
        parameters.addProperty("player", params.player);
        parameters.addProperty("keyword", params.keyword);
        if (params.types != null) {
            parameters.add("type", new Gson().toJsonTree(params.types));
        }
        session.setClips(parameters, clips);
        if (!toSummarize.isEmpty()) {
            Moments.summarizer().summarize(session, toSummarize);
        }

        JsonObject json = new JsonObject();
        json.addProperty("session", session.id);
        json.addProperty("total", clips.size());
        json.addProperty("summaries_requested", toSummarize.size());
        if (!toSummarize.isEmpty()) {
            json.addProperty("note", "Titles and summaries are the heuristic ones for now. Poll moments_clips_list until summary_pending is false");
        } else if (!MomentsConfig.ollamaConfigured()) {
            json.addProperty("note", "Titles and summaries are the built-in ones. Model-written summaries are optional and switched off (momentsAiEnabled)");
        }
        JsonArray array = new JsonArray();
        for (JsonObject clip : clips) {
            array.add(withSuggestedCamera(clip));
        }
        json.add("clips", array);
        return json;
    }

    private static JsonObject clipsList(McpArgs args) {
        MomentsSession session = session(args);
        String player = lower(args, "player");
        long from = time(args, "from", Long.MIN_VALUE);
        long to = time(args, "to", Long.MAX_VALUE);
        Set<String> types = stringSet(args, "type");
        double minImportance = args.optNumber("min_importance", 0);
        String keyword = lower(args, "keyword");

        List<JsonObject> matches = new ArrayList<>();
        for (JsonObject clip : session.clips()) {
            if (!inTimeRange(clip, from, to) || MJson.dbl(clip, "importance", 0) < minImportance
                    || (keyword != null && !MJson.containsIgnoreCase(clip.toString(), keyword))) {
                continue;
            }
            if (player != null) {
                boolean found = false;
                for (JsonElement element : MJson.arr(clip, "players")) {
                    found |= element.getAsString().toLowerCase(Locale.ROOT).equals(player);
                }
                if (!found) continue;
            }
            if (types != null) {
                boolean found = false;
                for (JsonElement element : MJson.arr(clip, "events")) {
                    found |= types.contains(MJson.str(element.getAsJsonObject(), "type", ""));
                }
                if (!found) continue;
            }
            matches.add(withSuggestedCamera(clip));
        }
        JsonObject json = page(args, session, "clips", matches, 100);
        json.addProperty("summaries_pending", Moments.summarizer().pending());
        return json;
    }

    // --- Tools that need the game ---

    private static JsonObject eventAdd(McpArgs args) {
        String type = args.optString("type");
        return addEvent(type, args.string("label"), args.has("metadata") ? args.object("metadata") : null);
    }

    /**
     * Logs a custom event at the current moment of the running session: what moments_event_add does, for the
     * "/dailies moment" command and its key as well. Client thread.
     *
     * @throws McpToolException when no session is being tracked or the label is empty
     */
    public static JsonObject addEvent(@Nullable String type, String label, @Nullable JsonObject metadata) {
        GameplayTracker gameplay = Moments.gameplay();
        MomentsSession session = Moments.currentSession();
        if (gameplay == null || session == null) {
            throw new McpToolException("No session is being tracked right now. Events can only be added while the player is in a world (not a replay) with momentsEnabled on");
        }
        String words = label == null ? "" : label.trim();
        if (words.isEmpty()) {
            throw new McpToolException("label must not be empty");
        }

        JsonObject json = ok();
        json.addProperty("session", session.id);
        json.add("event", gameplay.addCustom(type == null || type.isBlank() ? null : type.trim(), words, metadata));
        return json;
    }

    /** The jump that is waiting for its replay to finish loading. Only the newest request counts */
    private static volatile Object pendingCue = null;

    private static JsonObject cue(McpArgs args) throws Exception {
        MomentsSession session = session(args);
        String clipId = args.optString("clip_id");
        String eventId = args.optString("event_id");
        if ((clipId == null) == (eventId == null)) {
            throw new McpToolException("Give exactly one of 'clip_id' or 'event_id'");
        }
        boolean pause = args.optBool("pause", true);
        boolean withCamera = args.optBool("camera", false);
        JsonObject camera;

        String replayUuid;
        long wantedTick;
        long realTime;
        String title;
        JsonObject clipCued = null;
        if (clipId != null) {
            JsonObject clip = null;
            for (JsonObject candidate : session.clips()) {
                if (clipId.equals(MJson.str(candidate, "id"))) {
                    clip = candidate;
                }
            }
            if (clip == null) {
                throw new McpToolException("No clip '" + clipId + "' in session " + session.id + ". Use moments_clips_list (or moments_clips_generate first)");
            }
            clipCued = clip;
            replayUuid = MJson.str(clip, "replay_uuid");
            wantedTick = MJson.lng(clip, "start_replay_tick", 0);
            realTime = MJson.lng(clip, "start_real_time", 0);
            title = MJson.str(clip, "title");
            camera = suggestCamera(MJson.arr(clip, "events"));
        } else {
            JsonObject event = session.get(MomentsSession.Kind.EVENTS, eventId);
            if (event == null) {
                throw new McpToolException("No event '" + eventId + "' in session " + session.id);
            }
            long lead = Math.round(Math.max(0, args.optNumber("lead_seconds", 5)) * 20);
            replayUuid = MJson.str(event, "replay_uuid");
            wantedTick = MJson.lng(event, "replay_tick", 0) - lead;
            realTime = MJson.lng(event, "real_time", 0) - lead * 50;
            title = MomentAnalysis.describe(event);
            JsonArray single = new JsonArray();
            single.add(event);
            camera = suggestCamera(single);
        }
        // The camera follows whoever made the recording; by uuid, so it does not matter whether they are loaded yet
        JsonObject cameraArgs = null;
        if (withCamera) {
            cameraArgs = camera.getAsJsonObject("settings").deepCopy();
            cameraArgs.addProperty("mode", MJson.str(camera, "mode"));
            String recorder = MJson.str(MJson.obj(session.meta(), "local_player"), "uuid");
            if (recorder != null) {
                cameraArgs.addProperty("target", recorder);
            }
        }
        JsonObject finalCameraArgs = cameraArgs;
        if (replayUuid == null && realTime <= 0) {
            throw new McpToolException("This has no replay position: no replay was being recorded when it happened");
        }
        if (Host.get().exportRunning()) {
            throw new McpToolException("An export is in progress and controls playback itself");
        }
        int tick = (int) Math.max(0, wantedTick);

        // Every recording that holds it, best first: the open one, the original, then the newest (FILE-13). A moment keeps
        // the id and tick it was recorded with; combined and trimmed recordings know which of those ticks they hold
        String world = MJson.str(session.meta(), "world_name");
        List<Host.Located> found = new ArrayList<>();
        if (clipCued != null && replayUuid != null) {
            // A clip is cued at the start of the first piece of it that is still there
            int endTick = (int) Math.max(tick, MJson.lng(clipCued, "end_replay_tick", tick));
            for (Host.ClipLocation location : Host.get().locateClip(replayUuid, tick, endTick)) {
                found.add(new Host.Located(location.file(), location.recordingId(), location.name(), location.stretches().get(0).tick(), location.open(), location.original()));
            }
        }
        boolean byTime = false;
        if (found.isEmpty() && replayUuid != null) {
            found.addAll(Host.get().locate(replayUuid, tick));
        }
        if (found.isEmpty() && replayUuid != null && eventId != null) {
            // The run-up to the event was cut, but the event itself may not have been
            long lead = Math.round(Math.max(0, args.optNumber("lead_seconds", 5)) * 20);
            found.addAll(Host.get().locate(replayUuid, (int) Math.max(0, wantedTick + lead)));
        }
        if (found.isEmpty() && realTime > 0) {
            // Only answers when nothing here is, or is made from, that recording: the time index is the fallback (FILE-16)
            found.addAll(Host.get().locate(replayUuid, tick, realTime, world));
            byTime = !found.isEmpty();
        }
        JsonArray locations = new JsonArray();
        for (Host.Located located : found) {
            locations.add(describe(located));
        }
        boolean cutFromOpen = replayUuid != null && Host.get().openRecordingMadeFrom(replayUuid) && found.stream().noneMatch(Host.Located::open);

        String wantedRecording = args.optString("recording_id");
        if (wantedRecording != null && !wantedRecording.isBlank()) {
            List<Host.Located> chosen = new ArrayList<>();
            for (Host.Located located : found) {
                if (located.recordingId().equals(wantedRecording)) {
                    chosen.add(located);
                }
            }
            if (chosen.isEmpty() && replayUuid != null) {
                // Another player's recording of the same moment (PLAY-18): cued at the tick that lines up with it there
                int viewEnd = clipCued != null ? (int) Math.max(tick, MJson.lng(clipCued, "end_replay_tick", tick)) : tick + 100;
                for (Host.PointOfView view : Host.get().pointsOfView(replayUuid, tick, viewEnd)) {
                    if (view.recordingId().equals(wantedRecording)) {
                        chosen.add(new Host.Located(view.file(), view.recordingId(), view.name(), view.startTick(), view.open(), false));
                    }
                }
            }
            if (chosen.isEmpty()) {
                throw new McpToolException("CUT: this was cut from this recording (id " + wantedRecording + "), or was never in it. " + stillHeldBy(found));
            }
            found = chosen;
        }
        if (found.isEmpty()) {
            if (replayUuid == null) {
                throw new McpToolException("This has no recording id, and no recording of '" + world + "' holds the time it happened at");
            }
            throw new McpToolException("CUT: no recording in the recordings folder holds this any more. It was recorded in the recording with id " + replayUuid
                + ", which has been moved or deleted, or it was cut from every recording made from that one");
        }
        Host.Located best = found.get(0);
        int target = best.tick();
        String bestId = best.recordingId();

        Minecraft minecraft = Minecraft.getInstance();
        // Ask the client thread what is open right now
        Boolean isOpen = minecraft.submit(() -> {
            if (!bestId.equals(Host.get().openRecordingId())) {
                return false;
            }
            jump(target, pause, finalCameraArgs);
            return true;
        }).get(10, java.util.concurrent.TimeUnit.SECONDS);

        JsonObject json = ok();
        json.addProperty("title", title);
        json.add("suggested_camera", camera);
        json.addProperty("camera_added", withCamera);
        json.addProperty("replay_uuid", replayUuid);
        json.addProperty("recording_id", bestId);
        json.addProperty("recording_name", best.name());
        json.addProperty("target_tick", target);
        json.addProperty("found_by", byTime ? "time" : "id");
        json.add("locations", locations);
        if (cutFromOpen) {
            json.addProperty("cut_from_open", true);
            json.addProperty("cut_note", "Cut from this recording. " + stillHeldBy(found));
        }
        if (isOpen) {
            pendingCue = null;
            json.addProperty("cued", true);
            json.addProperty("opening", false);
            return json;
        }

        Path file = best.file();
        boolean inLiveWorld = minecraft.level != null && !Host.get().inPlayback();
        if (inLiveWorld && !args.optBool("leave_world", false)) {
            throw new McpToolException("LEAVE_WORLD_NEEDED: cueing this means leaving the world you are playing to open the recording '" + file.getFileName() + "'. Call again with leave_world = true to do that");
        }

        Object token = new Object();
        pendingCue = token;
        minecraft.tell(() -> {
            try {
                Host.get().openRecording(file);
            } catch (Throwable t) {
                Host.LOGGER.error("Moments: failed to open recording {} for a cue", file, t);
                pendingCue = null;
            }
        });
        Thread waiter = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 180_000;
            try {
                while (pendingCue == token && System.currentTimeMillis() < deadline) {
                    Thread.sleep(250);
                    boolean done = minecraft.submit(() -> {
                        if (pendingCue != token || minecraft.player == null || minecraft.level == null || !bestId.equals(Host.get().openRecordingId())) {
                            return false;
                        }
                        jump(target, pause, finalCameraArgs);
                        return true;
                    }).get(10, java.util.concurrent.TimeUnit.SECONDS);
                    if (done) {
                        break;
                    }
                }
            } catch (Exception ignored) {
                // The game is closing, or the recording never loaded
            }
            if (pendingCue == token) {
                pendingCue = null;
            }
        }, "Dailies-Moments-Cue");
        waiter.setDaemon(true);
        waiter.start();

        json.addProperty("cued", false);
        json.addProperty("opening", true);
        json.addProperty("file", file.getFileName().toString());
        json.addProperty("note", "The recording is loading. The jump happens by itself once it is open");
        return json;
    }

    private static JsonObject describe(Host.Located located) {
        JsonObject json = new JsonObject();
        json.addProperty("recording_id", located.recordingId());
        json.addProperty("name", located.name());
        json.addProperty("file", located.file().getFileName().toString());
        json.addProperty("tick", located.tick());
        json.addProperty("open", located.open());
        json.addProperty("original", located.original());
        return json;
    }

    /** A sentence naming the recordings that hold a moment, for the message that says it was cut from another */
    private static String stillHeldBy(List<Host.Located> found) {
        if (found.isEmpty()) {
            return "No recording in the recordings folder holds it any more";
        }
        List<String> names = new ArrayList<>();
        for (Host.Located located : found) {
            names.add("'" + located.name() + "' (" + (located.original() ? "the original, " : "") + "id " + located.recordingId() + ", tick " + located.tick() + ")");
        }
        return "Recordings that still hold it: " + String.join(", ", names);
    }

    /**
     * Every recording that holds a clip, best first: the stretches of it there, and whether any of it was cut. The
     * page lists these, each with its own cue button, so the edited episode can be opened over the raw footage.
     */
    /**
     * Every other player's recording that covers a clip (PLAY-18), each with the clip's stretch in its own ticks. Pass one's
     * recording_id to moments_cue to watch the clip from there.
     */
    private static JsonArray clipPointsOfView(JsonObject clip) {
        JsonArray array = new JsonArray();
        String replayUuid = MJson.str(clip, "replay_uuid");
        if (replayUuid == null) {
            return array;
        }
        int start = (int) Math.max(0, MJson.lng(clip, "start_replay_tick", 0));
        int end = (int) Math.max(start, MJson.lng(clip, "end_replay_tick", start));
        try {
            for (Host.PointOfView view : Host.get().pointsOfView(replayUuid, start, end)) {
                JsonObject json = new JsonObject();
                json.addProperty("recording_id", view.recordingId());
                json.addProperty("name", view.name());
                json.addProperty("recorder", view.recorder());
                json.addProperty("file", view.file().getFileName().toString());
                json.addProperty("start_tick", view.startTick());
                json.addProperty("end_tick", view.endTick());
                json.addProperty("open", view.open());
                array.add(json);
            }
        } catch (RuntimeException e) {
            Host.LOGGER.warn("Moments: the other points of view of a clip could not be looked up", e);
        }
        return array;
    }

    private static JsonArray clipLocations(JsonObject clip) {
        JsonArray array = new JsonArray();
        String replayUuid = MJson.str(clip, "replay_uuid");
        if (replayUuid == null) {
            return array;
        }
        int start = (int) Math.max(0, MJson.lng(clip, "start_replay_tick", 0));
        int end = (int) Math.max(start, MJson.lng(clip, "end_replay_tick", start));
        for (Host.ClipLocation location : Host.get().locateClip(replayUuid, start, end)) {
            JsonObject json = new JsonObject();
            json.addProperty("recording_id", location.recordingId());
            json.addProperty("name", location.name());
            json.addProperty("file", location.file().getFileName().toString());
            json.addProperty("open", location.open());
            json.addProperty("original", location.original());
            json.addProperty("cut", location.cut());
            json.addProperty("removed_ticks", location.removedTicks());
            json.addProperty("removed_seconds", MJson.round(location.removedTicks() / 20.0));
            JsonArray stretches = new JsonArray();
            for (Host.Stretch stretch : location.stretches()) {
                JsonObject piece = new JsonObject();
                piece.addProperty("tick", stretch.tick());
                piece.addProperty("end_tick", stretch.endTick());
                piece.addProperty("source_tick", stretch.sourceTick());
                piece.addProperty("source_end_tick", stretch.sourceEndTick());
                stretches.add(piece);
            }
            json.add("stretches", stretches);
            array.add(json);
        }
        return array;
    }

    /** Client thread, with the right recording open */
    private static void jump(int tick, boolean pause, @Nullable JsonObject cameraArgs) {
        int target = Math.min(tick, Host.get().openRecordingTicks());
        Host.get().seek(target, pause);
        if (cameraArgs != null) {
            try {
                JsonObject arguments = cameraArgs.deepCopy();
                arguments.addProperty("tick", target);
                Host.get().addSmartCamera(arguments);
            } catch (Exception e) {
                Host.LOGGER.warn("Moments: the suggested camera could not be added at tick {}: {}", target, e.getMessage());
            }
        }
    }

    // --- Suggested camera ---

    private static final Set<String> ACTION_EVENTS = Set.of("boss_encounter", "boss_defeated", "player_kill", "mob_kill", "player_death", "nearby_player_death",
        "pet_death", "boss_fight", "close_call", "totem_saved", "explosion", "lightning_strike");
    private static final Set<String> TRAVEL_EVENTS = Set.of("dimension_entered", "biome_discovered", "structure_interaction", "big_fall");

    private static JsonObject withSuggestedCamera(JsonObject clip) {
        JsonObject copy = clip.deepCopy();
        copy.add("suggested_camera", suggestCamera(MJson.arr(clip, "events")));
        JsonArray locations = clipLocations(clip);
        copy.add("locations", locations);
        copy.add("points_of_view", clipPointsOfView(clip));
        if (locations.isEmpty() && MJson.str(clip, "replay_uuid") != null) {
            // Rule 3 of the source map: a clip that is nowhere any more says so
            copy.addProperty("cut_everywhere", true);
        }
        return copy;
    }

    /**
     * A starting point for filming a clip, picked from what happens in it: quick cuts for action, a follow shot
     * for travel, a slow circle for calmer moments. Server and custom events count by their name (the subject).
     * Tripods are left to the director, which places its own; a lone tripod needs a position somebody chose.
     */
    private static JsonObject suggestCamera(JsonArray events) {
        int action = 0, travel = 0;
        for (JsonElement element : events) {
            JsonObject event = element.getAsJsonObject();
            String type = MJson.str(event, "type", "");
            String kind = type.equals("custom") ? MJson.str(event, "subject", "") : type;
            if (ACTION_EVENTS.contains(kind)) {
                action += 1;
            } else if (TRAVEL_EVENTS.contains(kind)) {
                travel += 1;
            }
        }

        JsonObject settings = new JsonObject();
        String mode, label, reason;
        if (action > 0) {
            mode = "director";
            label = "Director, quick cuts";
            reason = "There is action in this clip, so it cuts between angles every few seconds";
            settings.addProperty("min_shot_seconds", 2.0);
            settings.addProperty("max_shot_seconds", 5.0);
        } else if (travel > 0) {
            mode = "chase";
            label = "Chase, wide";
            reason = "The player is on the move, so the camera follows from behind and to one side with room ahead of them";
            settings.addProperty("distance", 8.0);
            settings.addProperty("height", 3.0);
            settings.addProperty("side_angle", 25.0);
            settings.addProperty("lead_room", 0.35);
        } else if (events.isEmpty()) {
            mode = "orbit";
            label = "Orbit, slow and wide";
            reason = "This is a conversation, so the camera circles slowly from further out to keep the group in view";
            settings.addProperty("radius", 10.0);
            settings.addProperty("height", 3.0);
            settings.addProperty("degrees_per_second", 8.0);
        } else {
            mode = "orbit";
            label = "Orbit, slow";
            reason = "A calmer moment, so the camera circles the player slowly";
            settings.addProperty("radius", 6.0);
            settings.addProperty("degrees_per_second", 12.0);
        }
        JsonObject json = new JsonObject();
        json.addProperty("mode", mode);
        json.addProperty("label", label);
        json.addProperty("reason", reason);
        json.add("settings", settings);
        return json;
    }

    /** Where the open recording holds a clip, or the reason, as an error, that it does not */
    private static Host.ClipLocation inOpenRecording(JsonObject clip, String replayUuid) {
        int clipStart = (int) Math.max(0, MJson.lng(clip, "start_replay_tick", 0));
        int clipEnd = (int) Math.max(clipStart, MJson.lng(clip, "end_replay_tick", clipStart));
        List<Host.ClipLocation> locations = Host.get().locateClip(replayUuid, clipStart, clipEnd);
        Host.ClipLocation here = null;
        List<Host.Located> elsewhere = new ArrayList<>();
        for (Host.ClipLocation location : locations) {
            if (location.open()) {
                here = location;
            } else {
                elsewhere.add(new Host.Located(location.file(), location.recordingId(), location.name(), location.stretches().get(0).tick(), false, location.original()));
            }
        }
        String open = Host.get().openRecordingId();
        if (here == null) {
            // Not through the index: the open recording answers for itself, through its own spans (FILE-14)
            int tick = Host.get().openRecordingContains(replayUuid, clipStart);
            if (tick >= 0) {
                here = new Host.ClipLocation(Path.of(""), open, "", List.of(new Host.Stretch(tick, tick + Math.max(1, clipEnd - clipStart), clipStart, Math.max(clipStart + 1, clipEnd))), false, 0, true, false);
            }
        }
        if (here == null) {
            if (open != null && Host.get().openRecordingMadeFrom(replayUuid)) {
                throw new McpToolException("CUT: this clip was cut from this recording. " + stillHeldBy(elsewhere) + ". Use moments_cue, which opens one for you");
            }
            throw new McpToolException((open == null ? "No recording is open. " : "The open recording is a different one. ")
                + "This clip is in the recording with id " + replayUuid + ". Use moments_cue, which opens it for you");
        }
        return here;
    }

    private static JsonObject clipToScene(McpArgs args) throws Exception {
        MomentsSession session = session(args);
        return makeScene(session, args.string("clip_id"), args.optString("name"), args.optBool("camera", true));
    }

    /** {@link #makeScene} for the editor, which knows a clip by its session and id. Client thread */
    public static JsonObject makeScene(String sessionId, String clipId) throws Exception {
        MomentsSession session = Moments.storage().getSession(sessionId);
        if (session == null) {
            throw new McpToolException("The play session this clip belongs to is gone");
        }
        return makeScene(session, clipId, null, true);
    }

    /**
     * A scene of the open recording for a clip: named after it, exporting exactly it, with the suggested camera at its
     * start. One step to undo. Client thread.
     */
    private static JsonObject makeScene(MomentsSession session, String clipId, @Nullable String wantedName, boolean withCamera) throws Exception {
        JsonObject clip = null;
        for (JsonObject candidate : session.clips()) {
            if (clipId.equals(MJson.str(candidate, "id"))) {
                clip = candidate;
            }
        }
        if (clip == null) {
            throw new McpToolException("No clip '" + clipId + "' in session " + session.id + ". Use moments_clips_list (or moments_clips_generate first)");
        }
        String replayUuid = MJson.str(clip, "replay_uuid");
        if (replayUuid == null) {
            throw new McpToolException("This clip has no recording position: nothing was being recorded when it happened");
        }
        Host.ClipLocation here = inOpenRecording(clip, replayUuid);
        if (Host.get().exportRunning()) {
            throw new McpToolException("An export is in progress and controls playback itself");
        }
        int total = Host.get().openRecordingTicks();
        int startTick = Math.max(0, Math.min(total, here.stretches().get(0).tick()));
        int endTick = Math.max(startTick, Math.min(total, here.stretches().get(here.stretches().size() - 1).endTick()));

        String title = MJson.str(clip, "title", "Clip");
        String name = wantedName == null || wantedName.isBlank() ? title : wantedName.trim();
        if (name.length() > 60) {
            name = name.substring(0, 60).trim();
        }
        JsonObject camera = suggestCamera(MJson.arr(clip, "events"));
        JsonObject cameraArgs = camera.getAsJsonObject("settings").deepCopy();
        cameraArgs.addProperty("mode", MJson.str(camera, "mode"));
        cameraArgs.addProperty("tick", startTick);
        // The camera follows whoever made the recording; by uuid, so it does not matter whether they are loaded yet
        String recorder = MJson.str(MJson.obj(session.meta(), "local_player"), "uuid");
        if (recorder != null) {
            cameraArgs.addProperty("target", recorder);
        }

        String sceneName = name;
        int[] index = {-1};
        boolean[] cameraAdded = {false};
        Host.get().asOneEdit("Make scene \"" + sceneName + "\" from a clip", () -> {
            index[0] = Host.get().createScene(sceneName, startTick, endTick);
            if (withCamera) {
                try {
                    Host.get().addSmartCamera(cameraArgs);
                    cameraAdded[0] = true;
                } catch (Exception e) {
                    // The scene and its range are still worth having
                    Host.LOGGER.warn("Moments: the suggested camera could not be added to the new scene: {}", e.getMessage());
                }
            }
        });
        Host.get().seek(startTick, true);

        JsonObject json = ok();
        json.addProperty("clip_id", clipId);
        json.addProperty("scene", index[0]);
        json.addProperty("scene_name", sceneName);
        json.addProperty("start_tick", startTick);
        json.addProperty("end_tick", endTick);
        json.addProperty("camera_added", cameraAdded[0]);
        json.add("suggested_camera", camera);
        json.addProperty("cut", here.cut());
        json.addProperty("note", "The scene is the current one and the replay is jumping to its start. editor_undo removes the scene, its range and its camera in one step"
            + (here.cut() ? ". Part of this clip was cut from this recording: the range runs over what is left" : ""));
        return json;
    }

    private static JsonObject clipGoto(McpArgs args) {
        MomentsSession session = session(args);
        String clipId = args.string("clip_id");
        JsonObject clip = null;
        for (JsonObject candidate : session.clips()) {
            if (clipId.equals(MJson.str(candidate, "id"))) {
                clip = candidate;
            }
        }
        if (clip == null) {
            throw new McpToolException("No clip '" + clipId + "' in session " + session.id + ". Use moments_clips_list (or moments_clips_generate first)");
        }
        String replayUuid = MJson.str(clip, "replay_uuid");
        if (replayUuid == null) {
            throw new McpToolException("This clip has no recording position: nothing was being recorded when it happened");
        }
        Host.ClipLocation here = inOpenRecording(clip, replayUuid);
        if (Host.get().exportRunning()) {
            throw new McpToolException("An export is in progress and controls playback itself");
        }

        int total = Host.get().openRecordingTicks();
        int startTick = Math.max(0, Math.min(total, here.stretches().get(0).tick()));
        int endTick = Math.max(startTick, Math.min(total, here.stretches().get(here.stretches().size() - 1).endTick()));
        Host.get().seek(startTick, false);

        JsonObject json = ok();
        json.addProperty("clip_id", clipId);
        json.addProperty("title", MJson.str(clip, "title"));
        json.addProperty("target_tick", startTick);
        json.addProperty("end_tick", endTick);
        json.addProperty("total_ticks", total);
        json.addProperty("cut", here.cut());
        json.addProperty("removed_ticks", here.removedTicks());
        JsonArray stretches = new JsonArray();
        for (Host.Stretch stretch : here.stretches()) {
            JsonObject piece = new JsonObject();
            piece.addProperty("tick", stretch.tick());
            piece.addProperty("end_tick", stretch.endTick());
            stretches.add(piece);
        }
        json.add("stretches", stretches);
        json.addProperty("note", "The jump happens asynchronously, dailies_status shows the tick once it has been reached"
            + (here.cut() ? ". Part of this clip was cut from this recording: what is left plays back to back" : ""));
        return json;
    }

    private static JsonObject ok() {
        JsonObject json = new JsonObject();
        json.addProperty("ok", true);
        return json;
    }

}
