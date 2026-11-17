package comedymonkey.dailies.moments.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.moments.MJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * What has already happened in one world/server (dailies/moments/firsts/&lt;world&gt;.json), so that "first"
 * means first in that world and not just first this session. A scope is eg. "&lt;player uuid&gt;|mob_kill" and
 * holds the subjects (entity types, items, ...) that were seen; the scope "pairs" holds the player pairs that have met.
 */
public class FirstsStore {

    private final MomentsStorage storage;
    private final Path file;
    private final Map<String, Set<String>> scopes = new LinkedHashMap<>();
    private boolean dirty = false;

    private FirstsStore(MomentsStorage storage, Path file) {
        this.storage = storage;
        this.file = file;
    }

    static FirstsStore load(MomentsStorage storage, Path file) {
        FirstsStore store = new FirstsStore(storage, file);
        if (Files.exists(file)) {
            try {
                JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject scopes = MJson.obj(json, "scopes");
                if (scopes != null) {
                    for (Map.Entry<String, JsonElement> entry : scopes.entrySet()) {
                        Set<String> set = new LinkedHashSet<>();
                        for (JsonElement element : entry.getValue().getAsJsonArray()) {
                            set.add(element.getAsString());
                        }
                        store.scopes.put(entry.getKey(), set);
                    }
                }
            } catch (Exception e) {
                Host.LOGGER.error("Moments: unable to read {}", file, e);
            }
        }
        return store;
    }

    /** @return true when the subject was not known in this scope yet. Saved straight away */
    public synchronized boolean mark(String scope, String subject) {
        if (this.markQuiet(scope, subject)) {
            this.save();
            return true;
        }
        return false;
    }

    /** Like {@link #mark} but without saving, for bulk changes. Call {@link #save} afterwards */
    public synchronized boolean markQuiet(String scope, String subject) {
        boolean added = this.scopes.computeIfAbsent(scope, key -> new LinkedHashSet<>()).add(subject);
        this.dirty |= added;
        return added;
    }

    public synchronized boolean contains(String scope, String subject) {
        Set<String> set = this.scopes.get(scope);
        return set != null && set.contains(subject);
    }

    public synchronized void save() {
        if (!this.dirty) {
            return;
        }
        this.dirty = false;
        JsonObject scopes = new JsonObject();
        for (Map.Entry<String, Set<String>> entry : this.scopes.entrySet()) {
            JsonArray array = new JsonArray();
            for (String subject : entry.getValue()) {
                array.add(subject);
            }
            scopes.add(entry.getKey(), array);
        }
        JsonObject json = new JsonObject();
        json.add("scopes", scopes);
        this.storage.writeFile(this.file, MJson.PRETTY.toJson(json));
    }

}
