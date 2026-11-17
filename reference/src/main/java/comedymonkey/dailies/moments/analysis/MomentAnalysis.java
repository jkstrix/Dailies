package comedymonkey.dailies.moments.analysis;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import comedymonkey.dailies.moments.MJson;
import comedymonkey.dailies.moments.storage.MomentsSession;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A snapshot of one session's records with the derived views on top: importance scores, the enriched context of a
 * moment and short human-readable descriptions. Pure data processing, usable from any thread.
 */
public class MomentAnalysis {

    // --- Importance weights. Tune here; everything else reads these tables ---

    /** Base score per event type. Types that are not listed get {@link #DEFAULT_WEIGHT} */
    private static final Map<String, Double> TYPE_WEIGHTS = Map.ofEntries(
        Map.entry("boss_defeated", 80.0),
        Map.entry("boss_encounter", 50.0),
        Map.entry("player_kill", 60.0),
        Map.entry("player_death", 50.0),
        Map.entry("nearby_player_death", 40.0),
        Map.entry("pet_death", 35.0),
        Map.entry("marker", 60.0),
        Map.entry("custom", 50.0),
        Map.entry("milestone", 40.0),
        Map.entry("advancement", 35.0),
        Map.entry("advancement_other", 20.0),
        Map.entry("dimension_entered", 30.0),
        Map.entry("structure_interaction", 30.0),
        Map.entry("biome_discovered", 15.0),
        Map.entry("mob_kill", 15.0),
        Map.entry("item_crafted", 10.0),
        Map.entry("tool_first_use", 8.0),
        Map.entry("block_mined", 6.0),
        Map.entry("block_placed", 5.0),
        // McpCraft's job board
        Map.entry("job_finished", 45.0),
        Map.entry("job_posted", 30.0),
        Map.entry("job_materials_complete", 30.0),
        Map.entry("job_claimed", 25.0),
        Map.entry("job_help_asked", 25.0),
        Map.entry("job_delivered", 12.0),
        Map.entry("job_dropped", 10.0),
        Map.entry("job_removed", 8.0)
    );
    private static final double DEFAULT_WEIGHT = 10.0;
    /** Added when the event is the first of its kind in this world */
    private static final double FIRST_BONUS = 15.0;
    /** Added for challenge-frame advancements (metadata.rare) */
    private static final double RARE_BONUS = 30.0;
    /** Added when the event involves a notable (boss) mob, eg. a mob_kill of a warden */
    private static final double BOSS_BONUS = 25.0;
    /** Added per player involved beyond the first, up to {@link #PLAYERS_BONUS_MAX} */
    private static final double PER_PLAYER_BONUS = 5.0;
    private static final double PLAYERS_BONUS_MAX = 20.0;
    /** Added per spoken word per minute around the event, up to {@link #DIALOGUE_BONUS_MAX}. Lively talk is ~150 wpm */
    private static final double PER_WPM_BONUS = 0.15;
    private static final double DIALOGUE_BONUS_MAX = 25.0;
    public static final double MAX_SCORE = 100.0;

    public final MomentsSession session;
    public final List<JsonObject> events;
    public final List<JsonObject> interactions;
    public final List<JsonObject> dialogue;
    private final long contextMillis;

    /**
     * @param extraInteractions interactions that are not stored yet (the ones still going on), may be empty
     */
    public MomentAnalysis(MomentsSession session, List<JsonObject> extraInteractions, int contextSeconds) {
        this.session = session;
        this.events = session.list(MomentsSession.Kind.EVENTS);
        // The ignore list hides noise from every query and clip without deleting anything
        this.events.removeIf(event -> comedymonkey.dailies.moments.MomentsConfig.isIgnored(MJson.str(event, "type", ""), MJson.str(event, "subject")));
        this.interactions = session.list(MomentsSession.Kind.INTERACTIONS);
        this.interactions.addAll(extraInteractions);
        this.dialogue = session.list(MomentsSession.Kind.DIALOGUE);
        this.contextMillis = contextSeconds * 1000L;
    }

    // --- Lookups by time ---

    public List<JsonObject> eventsBetween(long from, long to) {
        List<JsonObject> list = new ArrayList<>();
        for (JsonObject event : this.events) {
            long time = MJson.time(event);
            if (time >= from && time <= to) {
                list.add(event);
            }
        }
        return list;
    }

    /** Dialogue segments that overlap the range */
    public List<JsonObject> dialogueBetween(long from, long to) {
        return overlapping(this.dialogue, from, to);
    }

    public List<JsonObject> interactionsBetween(long from, long to) {
        return overlapping(this.interactions, from, to);
    }

    private static List<JsonObject> overlapping(List<JsonObject> records, long from, long to) {
        List<JsonObject> list = new ArrayList<>();
        for (JsonObject record : records) {
            if (MJson.time(record) <= to && MJson.endTime(record) >= from) {
                list.add(record);
            }
        }
        return list;
    }

    public static boolean hasText(JsonObject dialogue) {
        String text = MJson.str(dialogue, "text");
        return "done".equals(MJson.str(dialogue, "status")) && text != null && !text.isBlank();
    }

    public double wordsPerMinute(long from, long to) {
        int words = 0;
        for (JsonObject segment : this.dialogueBetween(from, to)) {
            if (hasText(segment)) {
                words += MJson.countWords(MJson.str(segment, "text"));
            }
        }
        double minutes = Math.max(1, to - from) / 60000.0;
        return words / minutes;
    }

    /** Names of the players that have something to do with the time range: actors, victims, speakers, people standing together */
    public Set<String> playersBetween(long from, long to) {
        Set<String> players = new LinkedHashSet<>();
        for (JsonObject event : this.eventsBetween(from, to)) {
            addEventPlayers(event, players);
        }
        for (JsonObject interaction : this.interactionsBetween(from, to)) {
            for (JsonElement element : MJson.arr(interaction, "players")) {
                addName(players, MJson.str(element.getAsJsonObject(), "name"));
            }
        }
        for (JsonObject segment : this.dialogueBetween(from, to)) {
            addName(players, MJson.str(MJson.obj(segment, "speaker"), "name"));
        }
        return players;
    }

    private static void addName(Set<String> players, @Nullable String name) {
        if (name != null && !name.isBlank()) {
            players.add(name);
        }
    }

    public static void addEventPlayers(JsonObject event, Set<String> players) {
        addName(players, MJson.str(MJson.obj(event, "player"), "name"));
        JsonObject metadata = MJson.obj(event, "metadata");
        if (metadata == null) {
            return;
        }
        for (String key : new String[]{"victim", "killer"}) {
            JsonObject party = MJson.obj(metadata, key);
            if (party != null && MJson.bool(party, "is_player")) {
                addName(players, MJson.str(party, "name"));
            }
        }
        for (JsonElement element : MJson.arr(metadata, "participants")) {
            if (element.isJsonObject()) {
                addName(players, MJson.str(element.getAsJsonObject(), "name"));
            }
        }
    }

    // --- Importance ---

    /**
     * Importance of an event, 0..100: the weight of its type, plus bonuses for being a first, a rare advancement or a
     * boss, for the number of players around and for how much was being said around it. See the tables at the top.
     */
    public double score(JsonObject event) {
        long time = MJson.time(event);
        JsonObject metadata = MJson.obj(event, "metadata");

        double score = TYPE_WEIGHTS.getOrDefault(MJson.str(event, "type", ""), DEFAULT_WEIGHT);
        if (MJson.bool(event, "first")) {
            score += FIRST_BONUS;
        }
        if (MJson.bool(metadata, "rare")) {
            score += RARE_BONUS;
        }
        if (MJson.bool(metadata, "boss")) {
            score += BOSS_BONUS;
        }

        Set<String> players = new LinkedHashSet<>();
        addEventPlayers(event, players);
        for (JsonObject interaction : this.interactionsBetween(time, time)) {
            for (JsonElement element : MJson.arr(interaction, "players")) {
                addName(players, MJson.str(element.getAsJsonObject(), "name"));
            }
        }
        score += Math.min(PLAYERS_BONUS_MAX, Math.max(0, players.size() - 1) * PER_PLAYER_BONUS);

        double wordsPerMinute = this.wordsPerMinute(time - this.contextMillis, time + this.contextMillis);
        score += Math.min(DIALOGUE_BONUS_MAX, wordsPerMinute * PER_WPM_BONUS);

        return Math.min(MAX_SCORE, MJson.round(score));
    }

    // --- Context ---

    @Nullable
    public JsonObject findEvent(String id) {
        for (JsonObject event : this.events) {
            if (id.equals(MJson.str(event, "id"))) {
                return event;
            }
        }
        return null;
    }

    /** Everything that is known about the seconds around a moment */
    public JsonObject context(long time, @Nullable JsonObject event) {
        long from = time - this.contextMillis;
        long to = time + this.contextMillis;

        JsonObject json = new JsonObject();
        json.addProperty("real_time", time);
        json.addProperty("real_time_iso", MJson.iso(time));
        json.addProperty("window_seconds", this.contextMillis / 1000);

        // Where it was: the event itself, or else the closest record that knows
        JsonObject place = event;
        if (place == null) {
            long best = Long.MAX_VALUE;
            for (JsonObject candidate : this.events) {
                long distance = Math.abs(MJson.time(candidate) - time);
                if (distance < best && candidate.has("position")) {
                    best = distance;
                    place = candidate;
                }
            }
        }
        if (place != null) {
            JsonObject location = new JsonObject();
            location.add("dimension", place.get("dimension"));
            location.add("biome", place.get("biome"));
            location.add("position", place.get("position"));
            if (place != event) {
                location.addProperty("from_event", MJson.str(place, "id"));
            }
            json.add("location", location);
        }

        JsonArray dialogue = new JsonArray();
        for (JsonObject segment : this.dialogueBetween(from, to)) {
            dialogue.add(transcriptLine(segment, time));
        }
        json.add("dialogue", dialogue);
        json.addProperty("words_per_minute", MJson.round(this.wordsPerMinute(from, to)));

        JsonArray interactions = new JsonArray();
        for (JsonObject interaction : this.interactionsBetween(from, to)) {
            interactions.add(compactInteraction(interaction));
        }
        json.add("interactions", interactions);

        JsonArray others = new JsonArray();
        for (JsonObject other : this.eventsBetween(from, to)) {
            if (other != event) {
                others.add(this.compactEvent(other, time));
            }
        }
        json.add("events", others);

        // What the player had most recently achieved before this
        JsonArray advancements = new JsonArray();
        for (int i = this.events.size() - 1; i >= 0 && advancements.size() < 3; i--) {
            JsonObject candidate = this.events.get(i);
            if (MJson.time(candidate) <= time && candidate != event && "advancement".equals(MJson.str(candidate, "type"))) {
                advancements.add(this.compactEvent(candidate, time));
            }
        }
        json.add("recent_advancements", advancements);

        JsonArray players = new JsonArray();
        for (String player : this.playersBetween(from, to)) {
            players.add(player);
        }
        json.add("players", players);
        return json;
    }

    public JsonObject compactEvent(JsonObject event, long relativeTo) {
        JsonObject json = new JsonObject();
        json.addProperty("id", MJson.str(event, "id"));
        json.addProperty("type", MJson.str(event, "type"));
        json.addProperty("subject", MJson.str(event, "subject"));
        json.addProperty("first", MJson.bool(event, "first"));
        json.addProperty("player", MJson.str(MJson.obj(event, "player"), "name"));
        json.addProperty("description", describe(event));
        json.addProperty("importance", this.score(event));
        json.addProperty("offset_seconds", MJson.round((MJson.time(event) - relativeTo) / 1000.0));
        if (event.has("replay_tick")) {
            json.add("replay_tick", event.get("replay_tick"));
        }
        return json;
    }

    public static JsonObject compactInteraction(JsonObject interaction) {
        JsonObject json = new JsonObject();
        for (String key : new String[]{"id", "type", "real_time", "end_real_time", "duration_seconds", "ongoing", "first_meeting"}) {
            if (interaction.has(key)) {
                json.add(key, interaction.get(key));
            }
        }
        JsonArray players = new JsonArray();
        for (JsonElement element : MJson.arr(interaction, "players")) {
            players.add(MJson.str(element.getAsJsonObject(), "name"));
        }
        json.add("players", players);
        return json;
    }

    public static JsonObject transcriptLine(JsonObject segment, long relativeTo) {
        JsonObject json = new JsonObject();
        json.addProperty("dialogue_id", MJson.str(segment, "id"));
        json.addProperty("speaker", MJson.str(MJson.obj(segment, "speaker"), "name"));
        json.addProperty("offset_seconds", MJson.round((MJson.time(segment) - relativeTo) / 1000.0));
        json.addProperty("duration_seconds", MJson.dbl(segment, "duration_seconds", 0));
        json.addProperty("text", MJson.str(segment, "text", ""));
        String status = MJson.str(segment, "status", "pending");
        if (!status.equals("done")) {
            json.addProperty("status", status);
        }
        return json;
    }

    // --- Descriptions ---

    /** "minecraft:ender_dragon" -> "Ender Dragon" */
    public static String pretty(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return "unknown";
        }
        String path = id.contains(":") && !id.contains(" ") ? id.substring(id.indexOf(':') + 1) : id;
        if (path.contains("/")) {
            path = path.substring(path.lastIndexOf('/') + 1);
        }
        StringBuilder builder = new StringBuilder();
        for (String word : path.replace('_', ' ').trim().split("\\s+")) {
            if (word.isEmpty()) continue;
            if (!builder.isEmpty()) builder.append(' ');
            builder.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
        }
        return builder.isEmpty() ? id : builder.toString();
    }

    /** One short factual line for an event, built only from what was recorded */
    public static String describe(JsonObject event) {
        String type = MJson.str(event, "type", "event");
        String subject = MJson.str(event, "subject");
        boolean first = MJson.bool(event, "first");
        JsonObject metadata = MJson.obj(event, "metadata");
        String player = MJson.str(MJson.obj(event, "player"), "name", "Player");

        switch (type) {
            case "mob_kill":
                return player + (first ? " killed their first " : " killed a ") + pretty(subject);
            case "player_kill":
                return player + " killed " + MJson.str(MJson.obj(metadata, "victim"), "name", pretty(subject));
            case "player_death":
            case "nearby_player_death":
            case "pet_death": {
                String message = MJson.str(metadata, "message");
                if (message != null && !message.isBlank()) {
                    return message;
                }
                String victim = MJson.str(MJson.obj(metadata, "victim"), "name", player);
                return victim + " died";
            }
            case "biome_discovered":
                return player + " discovered the " + pretty(subject) + " biome";
            case "dimension_entered":
                return player + (first ? " entered " : " went to ") + pretty(subject) + (first ? " for the first time" : "");
            case "advancement":
            case "advancement_other": {
                String title = MJson.str(metadata, "title", pretty(subject));
                return player + " made the advancement \"" + title + "\"";
            }
            case "item_crafted":
                return player + (first ? " crafted their first " : " crafted ") + pretty(subject);
            case "block_placed":
                return player + (first ? " placed their first " : " placed ") + pretty(subject);
            case "block_mined":
                return player + (first ? " mined their first " : " mined ") + pretty(subject);
            case "tool_first_use":
                return player + " used a " + pretty(subject) + " for the first time";
            case "structure_interaction":
                return player + " found a " + pretty(subject) + (MJson.str(metadata, "structure") != null ? " (" + pretty(MJson.str(metadata, "structure")) + ")" : "");
            case "boss_encounter":
                return player + " encountered " + MJson.str(metadata, "name", pretty(subject));
            case "boss_defeated":
                return MJson.str(metadata, "name", pretty(subject)) + " was defeated";
            case "milestone":
                return player + ": " + MJson.str(metadata, "label", pretty(subject));
            case "job_posted":
            case "job_help_asked":
            case "job_claimed":
            case "job_dropped":
            case "job_delivered":
            case "job_materials_complete":
            case "job_finished":
            case "job_removed":
                return describeJob(type, metadata, player);
            case "marker":
            case "custom": {
                String label = MJson.str(metadata, "label");
                return label != null && !label.isBlank() ? label : pretty(subject);
            }
            default:
                return pretty(type) + (subject != null ? ": " + pretty(subject) : "");
        }
    }

    /** McpCraft job board events: who did what to which job */
    private static String describeJob(String type, JsonObject metadata, String localPlayer) {
        String title = MJson.str(metadata, "title", "");
        String job = title.isBlank() ? "job " + MJson.str(metadata, "job_id", "") : "job \"" + title + "\"";
        String named = MJson.str(metadata, "player");
        String who = named != null && !named.isBlank() ? named : localPlayer;
        switch (type) {
            case "job_posted":
                return MJson.str(metadata, "creator", localPlayer) + " posted the " + job;
            case "job_help_asked":
                return who + " asked for help with the " + job;
            case "job_claimed":
                return who + (MJson.bool(metadata, "helper") ? " signed up to bring materials for the " : " claimed the ") + job;
            case "job_dropped":
                return who + " gave up the " + job;
            case "job_delivered": {
                long count = MJson.lng(metadata, "count", 0);
                return who + " delivered " + count + (count == 1 ? " item" : " items") + " to the " + job;
            }
            case "job_materials_complete":
                return "Everything the " + job + " needs has been delivered";
            case "job_finished": {
                // "by" is a player's name, or how it was finished (the Schematicannon, automatic check, supplied)
                String by = MJson.str(metadata, "by", "");
                if (named != null && !named.isBlank()) {
                    return named + " finished the " + job;
                }
                return "The " + job + " was finished" + (by.isBlank() ? "" : " (" + by + ")");
            }
            default:
                return "The " + job + " was taken down";
        }
    }

}
