package comedymonkey.dailies.moments.analysis;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import comedymonkey.dailies.moments.MJson;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns a session's records into clip candidates: events whose windows overlap are clustered, the clip is widened
 * so it does not cut a sentence in half, and dense conversations without any event become candidates of their own.
 * Titles and summaries written here are the heuristic ones, built only from recorded facts.
 */
public final class ClipBuilder {

    /** Dialogue-only candidates need this many words within {@link #CONVERSATION_WINDOW_MILLIS} from at least two speakers */
    private static final int CONVERSATION_MIN_WORDS = 25;
    private static final long CONVERSATION_WINDOW_MILLIS = 20_000;
    private static final int CONVERSATION_MIN_SPEAKERS = 2;
    /** Segments closer together than this belong to the same conversation */
    private static final long CONVERSATION_GAP_MILLIS = 10_000;
    /** How far a clip may be widened on each side to keep a dialogue segment whole (segments are at most 30 s) */
    private static final long MAX_DIALOGUE_EXTENSION_MILLIS = 30_000;

    private ClipBuilder() {
    }

    public static class Params {
        public long preRollMillis = 15_000;
        public long postRollMillis = 20_000;
        public double minImportance = 25;
        public int maxClips = 20;
        /** Player name or uuid, lower case */
        @Nullable public String player;
        @Nullable public Set<String> types;
        public long from = Long.MIN_VALUE;
        public long to = Long.MAX_VALUE;
        /** Lower case */
        @Nullable public String keyword;
    }

    private static class Cluster {
        long start, end;
        final List<JsonObject> triggers = new ArrayList<>();
    }

    public static List<JsonObject> generate(MomentAnalysis view, Params params) {
        // 1. Events worth a clip
        List<JsonObject> triggers = new ArrayList<>();
        for (JsonObject event : view.events) {
            long time = MJson.time(event);
            if (time < params.from || time > params.to) {
                continue;
            }
            if (params.types != null && !params.types.contains(MJson.str(event, "type", ""))) {
                continue;
            }
            if (view.score(event) < params.minImportance) {
                continue;
            }
            triggers.add(event);
        }
        triggers.sort(Comparator.comparingLong(MJson::time));

        // 2. Cluster the ones whose windows overlap
        List<Cluster> clusters = new ArrayList<>();
        for (JsonObject trigger : triggers) {
            long time = MJson.time(trigger);
            Cluster last = clusters.isEmpty() ? null : clusters.get(clusters.size() - 1);
            if (last != null && time - params.preRollMillis <= last.end) {
                last.end = Math.max(last.end, time + params.postRollMillis);
            } else {
                last = new Cluster();
                last.start = time - params.preRollMillis;
                last.end = time + params.postRollMillis;
                clusters.add(last);
            }
            last.triggers.add(trigger);
        }

        // 3. Don't cut dialogue in half, then merge clusters that grew into each other
        for (Cluster cluster : clusters) {
            long start = cluster.start;
            long end = cluster.end;
            for (JsonObject segment : view.dialogueBetween(start, end)) {
                cluster.start = Math.min(cluster.start, Math.max(MJson.time(segment), start - MAX_DIALOGUE_EXTENSION_MILLIS));
                cluster.end = Math.max(cluster.end, Math.min(MJson.endTime(segment), end + MAX_DIALOGUE_EXTENSION_MILLIS));
            }
        }
        for (int i = clusters.size() - 1; i > 0; i--) {
            Cluster previous = clusters.get(i - 1);
            Cluster current = clusters.get(i);
            if (current.start <= previous.end) {
                previous.end = Math.max(previous.end, current.end);
                previous.triggers.addAll(current.triggers);
                clusters.remove(i);
            }
        }

        List<JsonObject> clips = new ArrayList<>();
        for (Cluster cluster : clusters) {
            clips.add(build(view, cluster.start, cluster.end, cluster.triggers));
        }

        // 4. Conversations that no event clip covers. Skipped when the caller asked for specific event types
        if (params.types == null) {
            List<JsonObject> conversation = new ArrayList<>();
            for (JsonObject segment : view.dialogue) {
                if (!MomentAnalysis.hasText(segment) || MJson.time(segment) < params.from || MJson.time(segment) > params.to) {
                    continue;
                }
                boolean covered = false;
                for (Cluster cluster : clusters) {
                    covered |= MJson.time(segment) <= cluster.end && MJson.endTime(segment) >= cluster.start;
                }
                if (covered) {
                    continue;
                }
                if (!conversation.isEmpty() && MJson.time(segment) - MJson.endTime(conversation.get(conversation.size() - 1)) > CONVERSATION_GAP_MILLIS) {
                    addConversation(view, conversation, clips);
                    conversation = new ArrayList<>();
                }
                conversation.add(segment);
            }
            addConversation(view, conversation, clips);
        }

        // 5. Filters that need the finished clip
        clips.removeIf(clip -> params.player != null && !involves(view, clip, params.player));
        clips.removeIf(clip -> params.keyword != null && !MJson.containsIgnoreCase(clip.toString(), params.keyword));

        // 6. Keep the best ones, in the order they happened
        clips.sort(Comparator.comparingDouble((JsonObject clip) -> MJson.dbl(clip, "importance", 0)).reversed());
        if (clips.size() > params.maxClips) {
            clips = new ArrayList<>(clips.subList(0, params.maxClips));
        }
        clips.sort(Comparator.comparingLong(MJson::time));
        return clips;
    }

    private static void addConversation(MomentAnalysis view, List<JsonObject> conversation, List<JsonObject> clips) {
        if (conversation.isEmpty()) {
            return;
        }
        boolean dense = false;
        for (int i = 0; i < conversation.size() && !dense; i++) {
            long windowStart = MJson.time(conversation.get(i));
            int words = 0;
            Set<String> speakers = new LinkedHashSet<>();
            for (int j = i; j < conversation.size() && MJson.time(conversation.get(j)) <= windowStart + CONVERSATION_WINDOW_MILLIS; j++) {
                words += MJson.countWords(MJson.str(conversation.get(j), "text"));
                speakers.add(MJson.str(MJson.obj(conversation.get(j), "speaker"), "uuid", "?"));
            }
            dense = words >= CONVERSATION_MIN_WORDS && speakers.size() >= CONVERSATION_MIN_SPEAKERS;
        }
        if (!dense) {
            return;
        }
        long start = MJson.time(conversation.get(0)) - 1000;
        long end = MJson.endTime(conversation.get(conversation.size() - 1)) + 1000;
        clips.add(build(view, start, end, List.of()));
    }

    private static boolean involves(MomentAnalysis view, JsonObject clip, String player) {
        long start = MJson.time(clip);
        long end = MJson.endTime(clip);
        for (JsonObject event : view.eventsBetween(start, end)) {
            JsonObject metadata = MJson.obj(event, "metadata");
            if (matches(MJson.obj(event, "player"), player) || matches(MJson.obj(metadata, "victim"), player) || matches(MJson.obj(metadata, "killer"), player)) {
                return true;
            }
        }
        for (JsonObject segment : view.dialogueBetween(start, end)) {
            if (matches(MJson.obj(segment, "speaker"), player)) {
                return true;
            }
        }
        for (JsonObject interaction : view.interactionsBetween(start, end)) {
            for (JsonElement element : MJson.arr(interaction, "players")) {
                if (matches(element.getAsJsonObject(), player)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether a {uuid, name} object is the player the filter (lower case name or uuid) asks for */
    public static boolean matches(@Nullable JsonObject player, String filter) {
        if (player == null) {
            return false;
        }
        String name = MJson.str(player, "name");
        String uuid = MJson.str(player, "uuid");
        return (name != null && name.toLowerCase(Locale.ROOT).equals(filter)) || (uuid != null && uuid.toLowerCase(Locale.ROOT).equals(filter));
    }

    private static JsonObject build(MomentAnalysis view, long start, long end, List<JsonObject> triggers) {
        JsonObject clip = new JsonObject();
        clip.addProperty("id", "c" + start);
        clip.addProperty("kind", triggers.isEmpty() ? "dialogue" : "event");

        // Events: everything inside the clip, the ones that caused it are marked
        JsonArray events = new JsonArray();
        double bestScore = 0;
        JsonObject bestTrigger = null;
        List<String> descriptions = new ArrayList<>();
        for (JsonObject event : view.eventsBetween(start, end)) {
            JsonObject compact = view.compactEvent(event, start);
            boolean trigger = triggers.contains(event);
            compact.addProperty("trigger", trigger);
            events.add(compact);
            double score = MJson.dbl(compact, "importance", 0);
            if (trigger) {
                descriptions.add(MomentAnalysis.describe(event));
                if (score > bestScore) {
                    bestScore = score;
                    bestTrigger = event;
                }
            }
        }

        JsonArray transcript = new JsonArray();
        int words = 0;
        Set<String> speakers = new LinkedHashSet<>();
        for (JsonObject segment : view.dialogueBetween(start, end)) {
            transcript.add(MomentAnalysis.transcriptLine(segment, start));
            if (MomentAnalysis.hasText(segment)) {
                words += MJson.countWords(MJson.str(segment, "text"));
                speakers.add(MJson.str(MJson.obj(segment, "speaker"), "name", "Unknown"));
            }
        }

        Set<String> playerNames = view.playersBetween(start, end);
        JsonArray players = new JsonArray();
        for (String name : playerNames) {
            players.add(name);
        }

        // Importance: the best trigger plus a little for every further one; conversations score by their length
        double importance;
        if (bestTrigger != null) {
            importance = bestScore + Math.min(20, 3.0 * (triggers.size() - 1));
        } else {
            importance = 25 + Math.min(45, words * 0.5) + 5 * Math.max(0, speakers.size() - 1);
        }
        importance = Math.min(MomentAnalysis.MAX_SCORE, MJson.round(importance));

        // Heuristic title and summary
        String title;
        StringBuilder summary = new StringBuilder();
        if (bestTrigger != null) {
            title = MomentAnalysis.describe(bestTrigger);
            for (int i = 0; i < descriptions.size() && i < 6; i++) {
                if (i > 0) summary.append("; ");
                summary.append(descriptions.get(i));
            }
            if (descriptions.size() > 6) {
                summary.append("; and ").append(descriptions.size() - 6).append(" more");
            }
            summary.append('.');
            if (words > 0) {
                summary.append(" ").append(words).append(" words of voice chat from ").append(String.join(", ", speakers)).append('.');
            }
        } else {
            title = "Conversation between " + String.join(", ", speakers);
            StringBuilder spoken = new StringBuilder();
            for (JsonElement line : transcript) {
                if (!spoken.isEmpty()) spoken.append(' ');
                spoken.append(MJson.str(line.getAsJsonObject(), "text", ""));
            }
            String excerpt = spoken.length() > 160 ? spoken.substring(0, 160) + "..." : spoken.toString();
            summary.append(words).append(" words of voice chat: \"").append(excerpt).append('"');
        }
        clip.addProperty("title", title);
        clip.addProperty("summary", summary.toString());
        clip.addProperty("summary_source", "heuristic");
        clip.addProperty("summary_pending", false);
        clip.addProperty("heuristic_title", title);
        clip.addProperty("heuristic_summary", summary.toString());
        clip.addProperty("importance", importance);

        clip.addProperty("real_time", start);
        clip.addProperty("start_real_time", start);
        clip.addProperty("start_real_time_iso", MJson.iso(start));
        clip.addProperty("end_real_time", end);
        clip.addProperty("end_real_time_iso", MJson.iso(end));
        clip.addProperty("duration_seconds", MJson.round((end - start) / 1000.0));

        // Game time and replay ticks at the clip's edges are extrapolated from a record inside it, at 20 ticks per
        // second. They are off by however long the game or the recording was paused in between
        JsonObject gameAnchor = null;
        JsonObject replayAnchor = null;
        String replayPrefix = "";
        for (JsonObject event : view.eventsBetween(start, end)) {
            if (gameAnchor == null && MJson.lng(event, "game_time", -1) >= 0) gameAnchor = event;
            if (replayAnchor == null && event.has("replay_uuid")) replayAnchor = event;
        }
        String gamePrefix = "";
        for (JsonObject segment : view.dialogueBetween(start, end)) {
            if (gameAnchor == null && MJson.lng(segment, "start_game_time", -1) >= 0) {
                gameAnchor = segment;
                gamePrefix = "start_";
            }
            if (replayAnchor == null && segment.has("start_replay_uuid")) {
                replayAnchor = segment;
                replayPrefix = "start_";
            }
        }
        if (gameAnchor != null) {
            long anchorGameTime = MJson.lng(gameAnchor, gamePrefix + "game_time", 0);
            long anchorTime = MJson.time(gameAnchor);
            clip.addProperty("start_game_time", Math.max(0, anchorGameTime + (start - anchorTime) / 50));
            clip.addProperty("end_game_time", Math.max(0, anchorGameTime + (end - anchorTime) / 50));
            clip.add("dimension", gameAnchor.get("dimension"));
        }
        if (replayAnchor != null) {
            long anchorTick = MJson.lng(replayAnchor, replayPrefix + "replay_tick", 0);
            long anchorTime = MJson.time(replayAnchor);
            clip.addProperty("replay_uuid", MJson.str(replayAnchor, replayPrefix + "replay_uuid"));
            clip.addProperty("start_replay_tick", Math.max(0, anchorTick + (start - anchorTime) / 50));
            clip.addProperty("end_replay_tick", Math.max(0, anchorTick + (end - anchorTime) / 50));
        }

        clip.add("players", players);
        clip.add("events", events);
        clip.add("transcript", transcript);
        clip.addProperty("word_count", words);
        return clip;
    }

}
