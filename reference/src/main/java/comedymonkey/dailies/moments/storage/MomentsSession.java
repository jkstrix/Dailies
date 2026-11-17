package comedymonkey.dailies.moments.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.moments.MJson;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One play session on disk and in memory: session.json, the three append-only JSONL logs and clips.json.
 * <p>
 * A record is changed by appending a new line with the same id, which supersedes the earlier one (the file is
 * compacted when the session is loaded again). Records handed out by this class must not be modified; use
 * {@link #update} which works on a copy, so readers on other threads never see a half-changed record.
 */
public class MomentsSession {

    public enum Kind {
        EVENTS("events.jsonl", "e", "event"),
        INTERACTIONS("interactions.jsonl", "i", "interaction"),
        DIALOGUE("dialogue.jsonl", "d", "dialogue");

        final String fileName;
        final String idPrefix;
        final String liveName;

        Kind(String fileName, String idPrefix, String liveName) {
            this.fileName = fileName;
            this.idPrefix = idPrefix;
            this.liveName = liveName;
        }
    }

    private final MomentsStorage storage;
    public final String id;
    public final Path dir;
    private JsonObject meta;
    private final Map<Kind, LinkedHashMap<String, JsonObject>> records = new EnumMap<>(Kind.class);
    private final Map<Kind, Integer> nextSequence = new EnumMap<>(Kind.class);
    private JsonObject clips = new JsonObject();

    MomentsSession(MomentsStorage storage, String id, Path dir, JsonObject meta) {
        this.storage = storage;
        this.id = id;
        this.dir = dir;
        this.meta = meta;
        for (Kind kind : Kind.values()) {
            this.records.put(kind, new LinkedHashMap<>());
            this.nextSequence.put(kind, 1);
        }
        this.clips.add("clips", new JsonArray());
    }

    static MomentsSession load(MomentsStorage storage, String id, Path dir) {
        JsonObject meta = readObject(dir.resolve("session.json"));
        if (meta == null) {
            meta = new JsonObject();
            meta.addProperty("id", id);
        }
        MomentsSession session = new MomentsSession(storage, id, dir, meta);

        for (Kind kind : Kind.values()) {
            Path file = dir.resolve(kind.fileName);
            if (!Files.exists(file)) {
                continue;
            }
            LinkedHashMap<String, JsonObject> map = session.records.get(kind);
            boolean needsCompaction = false;
            int maxSequence = 0;
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonObject record;
                    try {
                        record = JsonParser.parseString(line).getAsJsonObject();
                    } catch (Exception e) {
                        // Usually the last line of a session that ended in a crash
                        needsCompaction = true;
                        continue;
                    }
                    String recordId = MJson.str(record, "id");
                    if (recordId == null) {
                        needsCompaction = true;
                        continue;
                    }
                    // A later line for the same id replaces the earlier one but keeps its place in the order
                    needsCompaction |= map.put(recordId, record) != null;
                    try {
                        maxSequence = Math.max(maxSequence, Integer.parseInt(recordId.substring(kind.idPrefix.length())));
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception e) {
                Host.LOGGER.error("Moments: unable to read {}", file, e);
            }
            session.nextSequence.put(kind, maxSequence + 1);
            if (needsCompaction) {
                StringBuilder builder = new StringBuilder();
                for (JsonObject record : map.values()) {
                    builder.append(MJson.GSON.toJson(record)).append('\n');
                }
                storage.writeFile(file, builder.toString());
            }
        }

        JsonObject clips = readObject(dir.resolve("clips.json"));
        if (clips != null && clips.has("clips") && clips.get("clips").isJsonArray()) {
            session.clips = clips;
        }
        return session;
    }

    @Nullable
    private static JsonObject readObject(Path file) {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            Host.LOGGER.error("Moments: unable to read {}", file, e);
            return null;
        }
    }

    // --- session.json ---

    public synchronized JsonObject meta() {
        return this.meta.deepCopy();
    }

    public synchronized void updateMeta(Consumer<JsonObject> change) {
        JsonObject copy = this.meta.deepCopy();
        change.accept(copy);
        this.meta = copy;
        this.saveMeta();
    }

    synchronized void saveMeta() {
        this.storage.writeFile(this.dir.resolve("session.json"), MJson.PRETTY.toJson(this.meta));
    }

    public synchronized boolean addReplay(String replayUuid) {
        for (JsonElement element : MJson.arr(this.meta, "replays")) {
            if (element.getAsString().equals(replayUuid)) {
                return false;
            }
        }
        this.updateMeta(meta -> {
            JsonArray replays = MJson.arr(meta, "replays");
            replays.add(new JsonPrimitive(replayUuid));
            meta.add("replays", replays);
        });
        return true;
    }

    public long startTime() {
        return MJson.lng(this.meta, "start_real_time", 0);
    }

    // --- Records ---

    /** Give the record an id, remember it and append it to its log. The record must not be changed afterwards */
    public synchronized JsonObject add(Kind kind, JsonObject record) {
        int sequence = this.nextSequence.get(kind);
        this.nextSequence.put(kind, sequence + 1);
        String recordId = kind.idPrefix + sequence;

        // id first, it makes the raw files easier to read
        JsonObject stored = new JsonObject();
        stored.addProperty("id", recordId);
        for (Map.Entry<String, JsonElement> entry : record.entrySet()) {
            if (!entry.getKey().equals("id")) {
                stored.add(entry.getKey(), entry.getValue());
            }
        }
        this.records.get(kind).put(recordId, stored);
        this.storage.append(this.dir.resolve(kind.fileName), MJson.GSON.toJson(stored));
        this.announce(kind.liveName, recordId, "added");
        return stored;
    }

    /** Change a record. The change is applied to a copy, which then replaces the record and is appended to the log */
    @Nullable
    public synchronized JsonObject update(Kind kind, String recordId, Consumer<JsonObject> change) {
        JsonObject current = this.records.get(kind).get(recordId);
        if (current == null) {
            return null;
        }
        JsonObject copy = current.deepCopy();
        change.accept(copy);
        copy.addProperty("id", recordId);
        this.records.get(kind).put(recordId, copy);
        this.storage.append(this.dir.resolve(kind.fileName), MJson.GSON.toJson(copy));
        this.announce(kind.liveName, recordId, "changed");
        return copy;
    }

    @Nullable
    public synchronized JsonObject get(Kind kind, String recordId) {
        return this.records.get(kind).get(recordId);
    }

    /** A snapshot of the records, oldest first */
    public synchronized List<JsonObject> list(Kind kind) {
        return new ArrayList<>(this.records.get(kind).values());
    }

    public synchronized int count(Kind kind) {
        return this.records.get(kind).size();
    }

    // --- Clips ---

    public synchronized List<JsonObject> clips() {
        List<JsonObject> list = new ArrayList<>();
        for (JsonElement element : MJson.arr(this.clips, "clips")) {
            list.add(element.getAsJsonObject());
        }
        return list;
    }

    public synchronized void setClips(JsonObject parameters, List<JsonObject> clips) {
        JsonObject json = new JsonObject();
        json.addProperty("session", this.id);
        json.addProperty("generated_at", System.currentTimeMillis());
        json.add("parameters", parameters);
        JsonArray array = new JsonArray();
        for (JsonObject clip : clips) {
            array.add(clip);
        }
        json.add("clips", array);
        this.clips = json;
        this.saveClips();
    }

    @Nullable
    public synchronized JsonObject updateClip(String clipId, Consumer<JsonObject> change) {
        JsonArray array = MJson.arr(this.clips, "clips");
        for (int i = 0; i < array.size(); i++) {
            JsonObject clip = array.get(i).getAsJsonObject();
            if (clipId.equals(MJson.str(clip, "id"))) {
                JsonObject copy = clip.deepCopy();
                change.accept(copy);
                JsonObject clips = new JsonObject();
                for (Map.Entry<String, JsonElement> entry : this.clips.entrySet()) {
                    clips.add(entry.getKey(), entry.getValue());
                }
                JsonArray newArray = new JsonArray();
                for (int j = 0; j < array.size(); j++) {
                    newArray.add(j == i ? copy : array.get(j));
                }
                clips.add("clips", newArray);
                this.clips = clips;
                this.saveClips();
                return copy;
            }
        }
        return null;
    }

    private void saveClips() {
        this.storage.writeFile(this.dir.resolve("clips.json"), MJson.PRETTY.toJson(this.clips));
        this.announce("clips", null, "changed");
    }

    /** Tells the open moments pages that something of this session is new or different (GET /live) */
    private static final java.util.concurrent.atomic.AtomicLong CHANGES = new java.util.concurrent.atomic.AtomicLong();

    /** Goes up whenever any record of any session is added or changed; for whoever keeps something worked out from them */
    public static long changes() {
        return CHANGES.get();
    }

    private void announce(String what, @Nullable String recordId, String change) {
        CHANGES.incrementAndGet();
        if (comedymonkey.dailies.mcp.LiveHub.listeners() == 0) {
            return;
        }
        JsonObject json = new JsonObject();
        json.addProperty("session", this.id);
        if (recordId != null) {
            json.addProperty("id", recordId);
        }
        json.addProperty("change", change);
        comedymonkey.dailies.mcp.LiveHub.publish(what, MJson.GSON.toJson(json));
    }

    public Path audioDir() {
        return this.dir.resolve("audio");
    }

    public MomentsStorage storage() {
        return this.storage;
    }

}
