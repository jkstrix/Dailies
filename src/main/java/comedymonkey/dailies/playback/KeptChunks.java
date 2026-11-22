package comedymonkey.dailies.playback;

import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What playback knows of each chunk of a world it keeps across jumps: which stored chunk it was built from, for as long
 * as nothing has changed it since, and which light a snapshot then put on top of it. A jump back may leave a chunk as
 * it is only when it is exactly what the snapshot being read would build, light included. Chunks are keyed by their
 * packed position, stored chunks and stored light by the name the recording gives them. Nothing here touches the game.
 * <p>
 * A snapshot names a chunk whose blocks are as stored, but whose light changed because a block next door did, as the
 * stored chunk followed straight away by its light as it then was (capture.Snapshots). So the same stored chunk can
 * stand for different light at different snapshots: built at a later snapshot it has that snapshot's light on it, and
 * a jump back to an earlier snapshot that names the stored chunk alone must not keep it like that.
 */
final class KeptChunks {

    /** For each chunk, the stored chunk it was built from, for as long as nothing has changed it since */
    private final Map<Long, String> clean = new HashMap<>();
    /**
     * The same, for as long as nothing has changed its blocks, light or biomes since: what its block entities hold may
     * have changed (machines send theirs all the time), but what the game has drawn of it has not
     */
    private final Map<Long, String> sameBlocks = new HashMap<>();
    /** Which chunk each stored chunk in {@link #sameBlocks} is: kept only as long as that, so it never outgrows the world */
    private final Map<String, Long> chunkOfStored = new HashMap<>();
    /** The stored light a snapshot put on top of a chunk's stored chunk, by chunk, for as long as {@link #sameBlocks} holds */
    private final Map<Long, String> lightOn = new HashMap<>();
    /** The chunks the snapshot being read has left as the kept world had them, by chunk, with the stored chunk each is */
    private final Map<Long, String> keptNow = new LinkedHashMap<>();

    /** A chunk was built, from a stored chunk or (null) from a packet of its own: whatever light it had is gone with it */
    void built(long chunk, @Nullable String stored) {
        this.lightOn.remove(chunk);
        if (stored == null) {
            this.clean.remove(chunk);
            this.forgetSameBlocks(chunk);
            return;
        }
        this.clean.put(chunk, stored);
        this.forgetSameBlocks(chunk);
        this.sameBlocks.put(chunk, stored);
        this.chunkOfStored.put(stored, chunk);
    }

    /**
     * A snapshot gave a chunk built from its stored chunk its light, straight after naming it: the chunk is still the
     * stored one, with this light on top
     *
     * @param light the name of the stored light, or null when it came as a packet of its own
     */
    void lit(long chunk, @Nullable String light) {
        if (!this.sameBlocks.containsKey(chunk)) {
            return;
        }
        if (light == null) {
            // Light no snapshot can name again: the chunk can no longer be told apart from one changed by hand
            this.changed(chunk);
        } else {
            this.lightOn.put(chunk, light);
        }
    }

    /** What a block entity holds, or a block's event: nothing drawn of the chunk has changed, but it is not as stored */
    void changedHeld(long chunk) {
        this.clean.remove(chunk);
    }

    /** The chunk's blocks, light or biomes changed, or the chunk was let go */
    void changed(long chunk) {
        this.clean.remove(chunk);
        this.forgetSameBlocks(chunk);
        this.lightOn.remove(chunk);
    }

    /** The world was thrown away */
    void clear() {
        this.clean.clear();
        this.sameBlocks.clear();
        this.chunkOfStored.clear();
        this.lightOn.clear();
        this.keptNow.clear();
    }

    /** Whether no chunk is known to be as stored */
    boolean isEmpty() {
        return this.clean.isEmpty() && this.sameBlocks.isEmpty();
    }

    /** The stored chunk a chunk was built from, while nothing has changed it since; else null */
    @Nullable String clean(long chunk) {
        return this.clean.get(chunk);
    }

    /** The stored chunk a chunk was built from, while nothing drawn of it has changed since; else null */
    @Nullable String sameBlocks(long chunk) {
        return this.sameBlocks.get(chunk);
    }

    /** Which chunk a stored chunk was built into, while that chunk's blocks are still as stored; else null */
    @Nullable Long chunkOf(String stored) {
        return this.chunkOfStored.get(stored);
    }

    /** The stored light a snapshot put on top of a chunk's stored chunk, while that still holds; else null */
    @Nullable String lightOn(long chunk) {
        return this.lightOn.get(chunk);
    }

    /** How many stored chunks are remembered by name: never more than the chunks whose blocks are as stored */
    int namesKnown() {
        return this.chunkOfStored.size();
    }

    // --- One snapshot read into a kept world ---

    /** A snapshot begins: nothing has been kept by it yet */
    void snapshotBegins() {
        this.keptNow.clear();
    }

    /** The snapshot named a stored chunk the kept world already has in that place, and the chunk was left as it is */
    void kept(long chunk, String stored) {
        this.keptNow.put(chunk, stored);
    }

    /**
     * What to do with light the snapshot gives a chunk
     *
     * @param leaveOut the chunk was kept and already has exactly this light on it: the light need not be given again
     * @param putBack  the stored chunk to put in again before the light, as a world built from nothing would have it,
     *                 because the chunk was kept with other light on it; or null
     */
    record Light(boolean leaveOut, @Nullable String putBack) {
        static final Light GIVE = new Light(false, null);
        static final Light LEAVE_OUT = new Light(true, null);
    }

    /**
     * The snapshot gives a chunk light. Where that chunk was left as the kept world had it, the light of any part of it
     * the new light does not speak of would stay as it was: then the stored chunk goes in again first, unless the very
     * same light is on it already
     *
     * @param light the name of the stored light, or null when it came as a packet of its own
     */
    Light light(long chunk, @Nullable String light) {
        String kept = this.keptNow.remove(chunk);
        if (kept == null) {
            return Light.GIVE;
        }
        if (light != null && light.equals(this.lightOn.get(chunk))) {
            return Light.LEAVE_OUT;
        }
        return new Light(false, kept);
    }

    /**
     * At the end of a snapshot: the chunks it left as the kept world had them, and gave no light of their own, that
     * still have light on top that a later snapshot gave. Each must have its stored chunk put in again, or it would keep
     * light from later in the recording. Forgets what this snapshot kept
     *
     * @return the stored chunk to put back, by chunk
     */
    Map<Long, String> unlit() {
        Map<Long, String> putBack = new LinkedHashMap<>();
        this.keptNow.forEach((chunk, stored) -> {
            if (this.lightOn.containsKey(chunk)) {
                putBack.put(chunk, stored);
            }
        });
        this.keptNow.clear();
        return putBack;
    }

    private void forgetSameBlocks(long chunk) {
        String was = this.sameBlocks.remove(chunk);
        if (was != null) {
            this.chunkOfStored.remove(was, chunk);
        }
    }

}
