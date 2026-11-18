package comedymonkey.dailies.edit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * One cut of a recording (specification EDIT-1): a name, its own tracks, the range of ticks to export and its
 * {@link Looks}. Tracks are ordered from the top of the timeline down; where two enabled tracks of a kind both apply,
 * the upper one (the lower index) wins.
 * <p>
 * While {@link EditHistory} makes a step, the scene it works on is a draft ({@link #draft}): it starts out sharing its
 * tracks and looks with the scene it was made from, and copies each one the first time it is handed out, so that
 * only what the step touches is copied and the scene it came from is never altered.
 */
public final class Scene {

    private String name;
    private final List<Track> tracks = new ArrayList<>();
    /** -1 while no range is set */
    private int exportStart = -1;
    private int exportEnd = -1;
    private Looks looks = new Looks();
    /** Other recordings of the same stretch that this scene can show (PLAY-19), in the order they were linked */
    private final List<LinkedRecording> linked = new ArrayList<>();
    /** While this is a draft: the tracks that are its own, which it may hand out to be changed. Null otherwise */
    private @Nullable Set<Track> ownTracks = null;
    /** While this is a draft: whether its looks are its own yet */
    private boolean ownLooks = true;

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
        if (this.ownTracks != null) {
            for (int i = 0; i < this.tracks.size(); i++) {
                this.own(i);
            }
        }
        return Collections.unmodifiableList(this.tracks);
    }

    /** @throws IllegalArgumentException when there is no track with this index */
    public Track track(int index) {
        if (index < 0 || index >= this.tracks.size()) {
            throw new IllegalArgumentException("'track' must be the index of a track, " + (this.tracks.isEmpty()
                    ? "but the scene has no tracks yet" : "from 0 to " + (this.tracks.size() - 1)) + ", got " + index);
        }
        return this.own(index);
    }

    /** The track at the index, first copied when this is a draft that does not have its own yet */
    private Track own(int index) {
        Track track = this.tracks.get(index);
        if (this.ownTracks != null && !this.ownTracks.contains(track)) {
            track = track.copy();
            this.tracks.set(index, track);
            this.ownTracks.add(track);
        }
        return track;
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
        if (this.ownTracks != null) {
            this.ownTracks.add(track);
        }
        return this.tracks.size() - 1;
    }

    /** Puts a track in at an index, moving that one and the later ones down */
    public void addTrack(int index, Track track) {
        if (index < 0 || index > this.tracks.size()) {
            throw new IllegalArgumentException("'to' must be a whole number from 0 to " + this.tracks.size() + ", got " + index);
        }
        this.tracks.add(index, track);
        if (this.ownTracks != null) {
            this.ownTracks.add(track);
        }
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
        if (!this.ownLooks) {
            this.looks = this.looks.copy();
            this.ownLooks = true;
        }
        return this.looks;
    }

    /** The other recordings this scene can show, as lanes on the timeline. The list cannot be changed through this view */
    public List<LinkedRecording> linked() {
        return Collections.unmodifiableList(this.linked);
    }

    /** The linked recording with this id, or null */
    public @Nullable LinkedRecording linked(String recordingId) {
        for (LinkedRecording recording : this.linked) {
            if (recording.recordingId().equals(recordingId)) {
                return recording;
            }
        }
        return null;
    }

    /**
     * Adds a recording the scene can show, or brings the name of one already there up to date.
     *
     * @return false when it was there already
     */
    public boolean link(LinkedRecording recording) {
        for (int i = 0; i < this.linked.size(); i++) {
            if (this.linked.get(i).recordingId().equals(recording.recordingId())) {
                this.linked.set(i, recording);
                return false;
            }
        }
        this.linked.add(recording);
        return true;
    }

    /** @return false when the scene had no such recording */
    public boolean unlink(String recordingId) {
        return this.linked.removeIf(recording -> recording.recordingId().equals(recordingId));
    }

    /**
     * A draft for {@link EditHistory} to change: a separate scene with the same content that shares its tracks and
     * looks with this one until they are asked for. Call {@link #seal} once the change is made.
     */
    Scene draft() {
        Scene draft = new Scene(this.name);
        draft.tracks.addAll(this.tracks);
        draft.exportStart = this.exportStart;
        draft.exportEnd = this.exportEnd;
        draft.looks = this.looks;
        draft.linked.addAll(this.linked);
        draft.ownTracks = Collections.newSetFromMap(new IdentityHashMap<>());
        draft.ownLooks = false;
        return draft;
    }

    /** The change is made: from now on the scene is only read, and hands out what it holds as it is */
    void seal() {
        this.ownTracks = null;
        this.ownLooks = true;
    }

    /** A separate scene with the same content, sharing nothing that can change */
    public Scene copy() {
        Scene copy = new Scene(this.name);
        for (Track track : this.tracks) {
            copy.tracks.add(track.copy());
        }
        copy.exportStart = this.exportStart;
        copy.exportEnd = this.exportEnd;
        copy.looks = this.looks.copy();
        copy.linked.addAll(this.linked);
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
        if (!this.linked.isEmpty()) {
            JsonArray linked = new JsonArray();
            for (LinkedRecording recording : this.linked) {
                linked.add(recording.toJson());
            }
            json.add("linked", linked);
        }
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
        JsonElement linked = json.get("linked");
        if (linked != null && linked.isJsonArray()) {
            for (JsonElement element : linked.getAsJsonArray()) {
                LinkedRecording recording = LinkedRecording.fromJson(element);
                if (recording != null) {
                    scene.link(recording);
                }
            }
        }
        return scene;
    }

}
