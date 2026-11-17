package comedymonkey.dailies.edit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Everything the editor knows about one recording: its scenes and which of them is being worked on (specification
 * EDIT-1). There is always at least one scene. Saved as JSON beside the recorded data (EDIT-7, FILE-8); a field or a
 * keyframe kind this version does not know is skipped, never an error.
 * <p>
 * The editor and the MCP tools do not change this directly: they go through {@link EditHistory}, which makes every
 * change a step that can be undone.
 */
public final class EditState {

    /** Written into every saved state. Raised only when an older version could no longer make sense of the JSON */
    public static final int VERSION = 1;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final List<Scene> scenes = new ArrayList<>();
    private int currentScene = 0;

    /** A fresh state: one empty scene called "Scene 1" */
    public EditState() {
        this.scenes.add(new Scene("Scene 1"));
    }

    /** The scenes in order. The list cannot be changed through this view */
    public List<Scene> scenes() {
        return Collections.unmodifiableList(this.scenes);
    }

    /** @throws IllegalArgumentException when there is no scene with this index */
    public Scene scene(int index) {
        if (index < 0 || index >= this.scenes.size()) {
            throw new IllegalArgumentException("'index' must be the index of a scene, from 0 to " + (this.scenes.size() - 1) + ", got " + index);
        }
        return this.scenes.get(index);
    }

    public int currentSceneIndex() {
        return this.currentScene;
    }

    /** The scene being worked on */
    public Scene currentScene() {
        return this.scenes.get(this.currentScene);
    }

    public void selectScene(int index) {
        this.scene(index);
        this.currentScene = index;
    }

    /** The name a new scene gets when none is given: "Scene N" with the first N not in use */
    public String freeSceneName() {
        for (int number = this.scenes.size() + 1; ; number++) {
            String name = "Scene " + number;
            if (this.scenes.stream().noneMatch(scene -> scene.name().equals(name))) {
                return name;
            }
        }
    }

    /** Puts a scene in at an index (the number of scenes puts it last) */
    public void addScene(int index, Scene scene) {
        if (index < 0 || index > this.scenes.size()) {
            throw new IllegalArgumentException("'index' must be a whole number from 0 to " + this.scenes.size() + ", got " + index);
        }
        this.scenes.add(index, scene);
        if (index <= this.currentScene && this.scenes.size() > 1) {
            this.currentScene++;
        }
    }

    /**
     * Removes a scene. The current scene stays current; when it is the one removed, its neighbour takes over.
     *
     * @throws IllegalArgumentException for the last scene left, which cannot be removed
     */
    public Scene removeScene(int index) {
        Scene scene = this.scene(index);
        if (this.scenes.size() == 1) {
            throw new IllegalArgumentException("The last scene cannot be removed: a recording always has at least one scene");
        }
        this.scenes.remove(index);
        if (index < this.currentScene || this.currentScene >= this.scenes.size()) {
            this.currentScene--;
        }
        return scene;
    }

    /** A separate state with the same content */
    public EditState copy() {
        EditState copy = new EditState();
        copy.scenes.clear();
        for (Scene scene : this.scenes) {
            copy.scenes.add(scene.copy());
        }
        copy.currentScene = this.currentScene;
        return copy;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("version", VERSION);
        json.addProperty("current_scene", this.currentScene);
        JsonArray array = new JsonArray();
        for (Scene scene : this.scenes) {
            array.add(scene.toJson());
        }
        json.add("scenes", array);
        return json;
    }

    /** Reads what can be read. Anything missing or not understood is left at its default; never an error */
    public static EditState fromJson(JsonObject json) {
        EditState state = new EditState();
        JsonElement scenes = json.get("scenes");
        if (scenes != null && scenes.isJsonArray()) {
            List<Scene> read = new ArrayList<>();
            for (JsonElement element : scenes.getAsJsonArray()) {
                if (element.isJsonObject()) {
                    read.add(Scene.fromJson(element.getAsJsonObject(), "Scene " + (read.size() + 1)));
                }
            }
            if (!read.isEmpty()) {
                state.scenes.clear();
                state.scenes.addAll(read);
            }
        }
        JsonElement current = json.get("current_scene");
        if (current != null && current.isJsonPrimitive() && current.getAsJsonPrimitive().isNumber()) {
            state.currentScene = Math.max(0, Math.min(state.scenes.size() - 1, current.getAsInt()));
        }
        return state;
    }

    /** The state as UTF-8 JSON, the same bytes for the same state */
    public byte[] toBytes() {
        return GSON.toJson(this.toJson()).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Reads a saved state. Unknown fields, unknown keyframe kinds and values out of range are skipped.
     *
     * @throws IllegalArgumentException only when the bytes are not a JSON object at all
     */
    public static EditState fromBytes(byte[] bytes) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("The editor state is not valid JSON: " + e.getMessage(), e);
        }
        if (parsed == null || !parsed.isJsonObject()) {
            throw new IllegalArgumentException("The editor state is not a JSON object");
        }
        return fromJson(parsed.getAsJsonObject());
    }

}
