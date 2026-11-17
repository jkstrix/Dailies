package comedymonkey.dailies.edit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One cut of a recording (specification EDIT-1): a name, its own tracks, the range of ticks to export and its
 * {@link Looks}. Tracks are ordered from the top of the timeline down; where two enabled tracks of a kind both apply,
 * the upper one (the lower index) wins.
 */
public final class Scene {

    private String name;
    private final List<Track> tracks = new ArrayList<>();
    /** -1 while no range is set */
    private int exportStart = -1;
    private int exportEnd = -1;
    private Looks looks = new Looks();

    public Scene(String name) {
        this.name = name == null ? "" : name;
    }

    public String name() {
        return this.name;
    }

    public void setName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("'name' must be a string that is not empty");
        }
        this.name = name.trim();
    }

    /** The tracks from the top of the timeline down. The list cannot be changed through this view */
    public List<Track> tracks() {
        return Collections.unmodifiableList(this.tracks);
    }

    /** @throws IllegalArgumentException when there is no track with this index */
    public Track track(int index) {
        if (index < 0 || index >= this.tracks.size()) {
            throw new IllegalArgumentException("'track' must be the index of a track, " + (this.tracks.isEmpty()
                    ? "but the scene has no tracks yet" : "from 0 to " + (this.tracks.size() - 1)) + ", got " + index);
        }
        return this.tracks.get(index);
    }

    /** The index of the uppermost track of the kind, or -1 */
    public int firstTrackOf(KeyframeKind kind) {
        for (int i = 0; i < this.tracks.size(); i++) {
            if (this.tracks.get(i).kind() == kind) {
                return i;
            }
        }
        return -1;
    }

    /** Adds a track at the bottom and returns its index */
    public int addTrack(Track track) {
        this.tracks.add(track);
        return this.tracks.size() - 1;
    }

    /** Puts a track in at an index, moving that one and the later ones down */
    public void addTrack(int index, Track track) {
        if (index < 0 || index > this.tracks.size()) {
            throw new IllegalArgumentException("'to' must be a whole number from 0 to " + this.tracks.size() + ", got " + index);
        }
        this.tracks.add(index, track);
    }

    public Track removeTrack(int index) {
        Track track = this.track(index);
        this.tracks.remove(index);
        return track;
    }

    public boolean hasExportRange() {
        return this.exportStart >= 0;
    }

    /** First tick to export, or -1 while no range is set */
    public int exportStart() {
        return this.exportStart;
    }

    /** Last tick to export, or -1 while no range is set */
    public int exportEnd() {
        return this.exportEnd;
    }

    public void setExportRange(int startTick, int endTick) {
        if (startTick < 0 || startTick > Keyframe.MAX_TICK) {
            throw new IllegalArgumentException("'start_tick' must be a whole number from 0 to " + Keyframe.MAX_TICK + ", got " + startTick);
        }
        if (endTick < startTick || endTick > Keyframe.MAX_TICK) {
            throw new IllegalArgumentException("'end_tick' must be a whole number from start_tick (" + startTick + ") to " + Keyframe.MAX_TICK + ", got " + endTick);
        }
        this.exportStart = startTick;
        this.exportEnd = endTick;
    }

    public void clearExportRange() {
        this.exportStart = -1;
        this.exportEnd = -1;
    }

    /** The first tick any keyframe of the scene sits on, or -1 when there are none */
    public int firstKeyframeTick() {
        int first = -1;
        for (Track track : this.tracks) {
            if (!track.isEmpty() && (first < 0 || track.firstTick() < first)) {
                first = track.firstTick();
            }
        }
        return first;
    }

    /** The last tick any keyframe of the scene sits on, or -1 when there are none */
    public int lastKeyframeTick() {
        int last = -1;
        for (Track track : this.tracks) {
            last = Math.max(last, track.lastTick());
        }
        return last;
    }

    public Looks looks() {
        return this.looks;
    }

    /** A separate scene with the same content */
    public Scene copy() {
        Scene copy = new Scene(this.name);
        for (Track track : this.tracks) {
            copy.tracks.add(track.copy());
        }
        copy.exportStart = this.exportStart;
        copy.exportEnd = this.exportEnd;
        copy.looks = this.looks.copy();
        return copy;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("name", this.name);
        if (this.hasExportRange()) {
            json.addProperty("export_start", this.exportStart);
            json.addProperty("export_end", this.exportEnd);
        }
        JsonArray array = new JsonArray();
        for (Track track : this.tracks) {
            array.add(track.toJson());
        }
        json.add("tracks", array);
        json.add("looks", this.looks.toJson());
        return json;
    }

    /** Reads a scene as leniently as possible: tracks of a kind this version does not know are left out */
    public static Scene fromJson(JsonObject json, String fallbackName) {
        JsonElement name = json.get("name");
        Scene scene = new Scene(name != null && name.isJsonPrimitive() && !name.getAsString().isBlank() ? name.getAsString() : fallbackName);
        try {
            if (json.has("export_start") && json.has("export_end")) {
                scene.setExportRange(JsonFields.integer(json, "export_start", 0, Keyframe.MAX_TICK), JsonFields.integer(json, "export_end", 0, Keyframe.MAX_TICK));
            }
        } catch (IllegalArgumentException ignored) {
            // No range then
        }
        JsonElement tracks = json.get("tracks");
        if (tracks != null && tracks.isJsonArray()) {
            for (JsonElement element : tracks.getAsJsonArray()) {
                @Nullable Track track = element.isJsonObject() ? Track.fromJson(element.getAsJsonObject()) : null;
                if (track != null) {
                    scene.tracks.add(track);
                }
            }
        }
        JsonElement looks = json.get("looks");
        scene.looks = Looks.fromJson(looks != null && looks.isJsonObject() ? looks.getAsJsonObject() : null);
        return scene;
    }

}
