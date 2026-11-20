package comedymonkey.dailies.windows;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What {@link CompatCapture} writes of each compat (specification REC-26), kept apart from the game so that it is unit
 * tested. Playback reaches a tick in two ways: by plain play, which steps over the snapshot of each segment it runs into
 * ({@code playback.SegmentCrossing}; a jump that lands less than three seconds after a snapshot runs up to it from the
 * one before, {@code playback.JumpStart}), and by reading the segment's snapshot after forgetting everything. So the
 * records of the ticks carry every change on their own, measured against what they have written, and a snapshot only
 * describes: nothing counts as written by it. On the tick a snapshot is taken, whatever differs from what it described
 * is written as well, as for the recorder's windows ({@link WindowCapture}), so both ways arrive at the same state.
 */
final class CompatWrites {

    /** How far, in GUI units, the pointer must move to be written again, as for the recorder's windows */
    private static final float POINTER_STEP = 0.5F;

    /** A claim showing now: its name, its state, where the pointer is over its screen (or null), and how often it may be written */
    record Showing(String name, byte[] state, float @Nullable [] pointer, boolean share, int ticksBetween) {
    }

    /** What records hold of one compat: each claim's last state, and the pointer */
    private static final class Written {
        final Map<String, byte[]> states = new LinkedHashMap<>();
        final Map<String, Integer> writtenAt = new HashMap<>();
        String pointerOf = null;
        boolean share;
        float x;
        float y;
        int pointerAt;
    }

    /** A snapshot part that was never written: a jump to its snapshot holds nothing of the compat */
    private static final Written NOTHING = new Written();

    /** What the records of the ticks hold, by compat id: what plain play holds */
    private final Map<String, Written> written = new HashMap<>();
    /** What the snapshot taken this tick described, by compat id: what a jump that reads it holds */
    private final Map<String, Written> snapped = new HashMap<>();
    private boolean snapTaken = false;
    private int ticks = 0;

    /** A new recording: it holds nothing yet */
    void clear() {
        this.written.clear();
        this.snapped.clear();
    }

    /** The compats something may have to be written of: those written of, and those the snapshot taken this tick describes */
    Set<String> compats() {
        Set<String> compats = new LinkedHashSet<>(this.written.keySet());
        if (this.snapTaken) {
            compats.addAll(this.snapped.keySet());
        }
        return compats;
    }

    /**
     * One compat's part of a snapshot: every claim of its showing now, whole, with the pointer. Empty when none shows.
     * Nothing counts as written by it, as plain play steps over it
     */
    List<CompatRecord.Op> snapshot(String compatId, List<Showing> now) {
        Written described = new Written();
        List<CompatRecord.Op> ops = new ArrayList<>();
        for (Showing showing : now) {
            ops.add(CompatRecord.Op.state(showing.name(), showing.state()));
            described.states.put(showing.name(), showing.state());
            if (showing.pointer() != null) {
                ops.add(CompatRecord.Op.pointer(showing.name(), showing.share(), showing.pointer()[0], showing.pointer()[1]));
                described.pointerOf = showing.name();
                described.share = showing.share();
                described.x = showing.pointer()[0];
                described.y = showing.pointer()[1];
            }
        }
        this.snapped.put(compatId, described);
        return ops;
    }

    /**
     * A recorded tick begins
     *
     * @param snapshotTaken whether the recorder took a snapshot since the last tick: a compat whose part of it is missing
     *                      is then taken to have nothing in it
     */
    void tickBegins(boolean snapshotTaken) {
        this.ticks++;
        this.snapTaken = snapshotTaken;
    }

    /** The tick is over: the next has no snapshot unless one is taken */
    void tickEnds() {
        this.snapTaken = false;
        this.snapped.clear();
    }

    /**
     * What to write for one compat at the end of this tick: claims gone, states new or changed (when due), and the
     * pointer; and on a snapshot's tick, whatever differs from what it described and every change held back, so that
     * plain play and a jump to the snapshot hold the same
     */
    List<CompatRecord.Op> tick(String compatId, List<Showing> now) {
        Written was = this.written.computeIfAbsent(compatId, key -> new Written());
        Written snap = this.snapTaken ? this.snapped.getOrDefault(compatId, NOTHING) : null;
        List<CompatRecord.Op> ops = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Showing showing : now) {
            names.add(showing.name());
        }
        Set<String> gone = new LinkedHashSet<>(was.states.keySet());
        if (snap != null) {
            gone.addAll(snap.states.keySet());
        }
        gone.removeAll(names);
        for (String name : gone) {
            ops.add(CompatRecord.Op.gone(name));
            was.states.remove(name);
            was.writtenAt.remove(name);
            if (name.equals(was.pointerOf)) {
                was.pointerOf = null;
            }
        }
        for (Showing showing : now) {
            String name = showing.name();
            byte[] before = was.states.get(name);
            boolean fresh = before == null;
            boolean changed = !fresh && !Arrays.equals(before, showing.state());
            boolean due = this.ticks - was.writtenAt.getOrDefault(name, 0) >= Math.max(1, showing.ticksBetween());
            boolean offSnap = snap != null && !Arrays.equals(snap.states.get(name), showing.state());
            if (fresh || changed && (due || snap != null) || offSnap) {
                ops.add(CompatRecord.Op.state(name, showing.state()));
                was.states.put(name, showing.state());
                was.writtenAt.put(name, this.ticks);
            }
            if (showing.pointer() != null) {
                float x = showing.pointer()[0];
                float y = showing.pointer()[1];
                boolean moved = !name.equals(was.pointerOf) || showing.share() != was.share
                    || Math.abs(x - was.x) * (showing.share() ? 400 : 1) >= POINTER_STEP || Math.abs(y - was.y) * (showing.share() ? 300 : 1) >= POINTER_STEP;
                boolean pointerOffSnap = snap != null && (!name.equals(snap.pointerOf) || snap.share != showing.share() || snap.x != x || snap.y != y);
                if (fresh || !name.equals(was.pointerOf) || moved && (this.ticks - was.pointerAt >= WindowCapture.POINTER_TICKS || snap != null) || pointerOffSnap) {
                    ops.add(CompatRecord.Op.pointer(name, showing.share(), x, y));
                    was.pointerOf = name;
                    was.share = showing.share();
                    was.x = x;
                    was.y = y;
                    was.pointerAt = this.ticks;
                }
            }
        }
        return ops;
    }

}
