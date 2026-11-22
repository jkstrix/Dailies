package comedymonkey.dailies.edit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One row of the timeline: keyframes of exactly one kind, kept in tick order with at most one on a tick
 * (specification EDIT-2, EDIT-3). A track can be named and switched off.
 * <p>
 * Every change in the editor is made on a copy (see {@link EditHistory}), so the same track is a new object after
 * each change. What belongs to the track itself rather than to one version of it, such as the springs of a camera
 * that follows someone, is kept by its {@link #id()}, which every copy shares.
 */
public final class Track {

    /**
     * Who a track is, the same for all its copies and different for every track made afresh. Compared by identity,
     * so it can key a map that lets go of what it holds once no copy of the track is left. Not saved: a recording
     * opened again gives its tracks new ones.
     */
    public static final class Id {
        private static final AtomicLong NEXT = new AtomicLong();
        private final long number = NEXT.incrementAndGet();

        @Override
        public String toString() {
            return "track " + this.number;
        }
    }

    private final Id id;
    private final KeyframeKind kind;
    /** Empty means the track is shown under the name of its kind */
    private String name = "";
    private boolean enabled = true;
    private final List<Keyframe> keyframes = new ArrayList<>();

    public Track(KeyframeKind kind) {
        this(kind, new Id());
    }

    private Track(KeyframeKind kind, Id id) {
        if (kind == null) {
            throw new IllegalArgumentException("'type' is required: one of " + KeyframeKind.allowed());
        }
        this.kind = kind;
        this.id = id;
    }

    /** Who this track is: kept by {@link #copy}, so the same through every change made in the editor */
    public Id id() {
        return this.id;
    }

    public KeyframeKind kind() {
        return this.kind;
    }

    /** The name given by the user; empty when none was given */
    public String name() {
        return this.name;
    }

    /** The name to show: the user's, or the kind's when there is none */
    public String title() {
        return this.name.isEmpty() ? this.kind.title() : this.name;
    }

    public void setName(@Nullable String name) {
        this.name = name == null ? "" : name.trim();
    }

    /** A track that is switched off is ignored in playback and export */
    public boolean enabled() {
        return this.enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** The keyframes in tick order. The list cannot be changed through this view */
    public List<Keyframe> keyframes() {
        return Collections.unmodifiableList(this.keyframes);
    }

    public boolean isEmpty() {
        return this.keyframes.isEmpty();
    }

    public int size() {
        return this.keyframes.size();
    }

    /** The tick of the first keyframe, or -1 for an empty track */
    public int firstTick() {
        return this.keyframes.isEmpty() ? -1 : this.keyframes.get(0).tick();
    }

    /** The tick of the last keyframe, or -1 for an empty track */
    public int lastTick() {
        return this.keyframes.isEmpty() ? -1 : this.keyframes.get(this.keyframes.size() - 1).tick();
    }

    /** Whether this track has a say at the tick: it is switched on and its first keyframe is at or before the tick */
    public boolean appliesAt(double tick) {
        return this.enabled && !this.keyframes.isEmpty() && this.keyframes.get(0).tick() <= tick;
    }

    /** The index of the last keyframe at or before the tick, or -1 when the tick is before them all */
    public int indexAtOrBefore(double tick) {
        int low = 0;
        int high = this.keyframes.size() - 1;
        int found = -1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (this.keyframes.get(middle).tick() <= tick) {
                found = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return found;
    }

    /** The last keyframe at or before the tick, or null when the tick is before them all */
    public @Nullable Keyframe atOrBefore(double tick) {
        int index = this.indexAtOrBefore(tick);
        return index < 0 ? null : this.keyframes.get(index);
    }

    /** The keyframe exactly on the tick, or null */
    public @Nullable Keyframe at(int tick) {
        int index = this.indexAtOrBefore(tick);
        return index >= 0 && this.keyframes.get(index).tick() == tick ? this.keyframes.get(index) : null;
    }

    /**
     * Adds a keyframe, taking the place of one already on its tick.
     *
     * @return the keyframe that was replaced, or null
     */
    public @Nullable Keyframe put(Keyframe keyframe) {
        if (keyframe.kind() != this.kind) {
            throw new IllegalArgumentException("A " + keyframe.kind().id() + " keyframe cannot go on a " + this.kind.id() + " track");
        }
        int index = this.indexAtOrBefore(keyframe.tick());
        if (index >= 0 && this.keyframes.get(index).tick() == keyframe.tick()) {
            return this.keyframes.set(index, keyframe);
        }
        this.keyframes.add(index + 1, keyframe);
        return null;
    }

    /** Removes the keyframe on the tick and returns it, or null when there was none */
    public @Nullable Keyframe remove(int tick) {
        int index = this.indexAtOrBefore(tick);
        if (index >= 0 && this.keyframes.get(index).tick() == tick) {
            return this.keyframes.remove(index);
        }
        return null;
    }

    public void clear() {
        this.keyframes.clear();
    }

    /** A separate track with the same content and the same {@link #id()} */
    public Track copy() {
        Track copy = new Track(this.kind, this.id);
        copy.name = this.name;
        copy.enabled = this.enabled;
        copy.keyframes.addAll(this.keyframes);
        return copy;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("kind", this.kind.id());
        json.addProperty("name", this.name);
        json.addProperty("enabled", this.enabled);
        JsonArray array = new JsonArray();
        for (Keyframe keyframe : this.keyframes) {
            array.add(keyframe.toJson());
        }
        json.add("keyframes", array);
        return json;
    }

    /**
     * Reads a track as leniently as possible: keyframes that cannot be read are left out.
     *
     * @return null when the track's kind is not one this version knows
     */
    public static @Nullable Track fromJson(JsonObject json) {
        JsonElement kindId = json.get("kind");
        KeyframeKind kind = kindId != null && kindId.isJsonPrimitive() ? KeyframeKind.find(kindId.getAsString()) : null;
        if (kind == null) {
            return null;
        }
        Track track = new Track(kind);
        JsonElement name = json.get("name");
        if (name != null && name.isJsonPrimitive()) {
            track.setName(name.getAsString());
        }
        JsonElement enabled = json.get("enabled");
        if (enabled != null && enabled.isJsonPrimitive() && enabled.getAsJsonPrimitive().isBoolean()) {
            track.enabled = enabled.getAsBoolean();
        }
        JsonElement keyframes = json.get("keyframes");
        if (keyframes != null && keyframes.isJsonArray()) {
            for (JsonElement element : keyframes.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                try {
                    track.put(Keyframe.fromJson(kind, element.getAsJsonObject()));
                } catch (IllegalArgumentException ignored) {
                    // A keyframe from a newer or damaged file; the rest of the track is still good
                }
            }
        }
        return track;
    }

}
