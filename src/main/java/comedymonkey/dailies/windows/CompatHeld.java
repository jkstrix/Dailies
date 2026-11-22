package comedymonkey.dailies.windows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What one compat's records hold at the point playback has reached (specification REC-26, PLAY-21): the newest state of
 * each of its claims that is showing, with the version that wrote it, and the pointer over its screen. Playing a record
 * is keeping its states; describing what is held, for a trimmed recording's first snapshot (FILE-10), is writing them
 * again, which is enough because every state is whole. No game in here, so it is unit tested.
 */
final class CompatHeld {

    /** A claim's newest state; a new object whenever a record brings one, so a drawing built from it knows to be built again */
    record Held(byte[] state, int version) {
    }

    /** What {@link #play} found */
    record Played(Set<String> changed, boolean pointerMoved) {
    }

    private final Map<String, Held> held = new LinkedHashMap<>();
    private String pointerOf = null;
    private int pointerVersion = 0;
    boolean pointerShare = false;
    float pointerX;
    float pointerY;

    /**
     * Keeps what one record says.
     *
     * @throws IllegalArgumentException for a record that cannot be read; nothing of it is kept then
     */
    Played play(byte[] data, int from, int length) {
        CompatRecord.Read read = CompatRecord.read(data, from, length);
        Set<String> changed = new TreeSet<>();
        boolean moved = false;
        for (CompatRecord.Op op : read.ops()) {
            switch (op.kind()) {
                case CompatRecord.STATE -> {
                    this.held.put(op.name(), new Held(op.state(), read.version()));
                    changed.add(op.name());
                }
                case CompatRecord.GONE -> {
                    if (this.held.remove(op.name()) != null) {
                        changed.add(op.name());
                    }
                    if (op.name().equals(this.pointerOf)) {
                        this.pointerOf = null;
                    }
                }
                case CompatRecord.POINTER -> {
                    this.pointerOf = op.name();
                    this.pointerVersion = read.version();
                    this.pointerShare = op.share();
                    this.pointerX = op.x();
                    this.pointerY = op.y();
                    moved = true;
                }
                default -> {
                }
            }
        }
        return new Played(changed, moved);
    }

    /** The newest state of a claim, or null while it is not showing */
    Held held(String name) {
        return this.held.get(name);
    }

    /** The claims showing, in the order their states first came */
    Set<String> showing() {
        return this.held.keySet();
    }

    /** Whether the pointer over this claim's screen has been recorded */
    boolean hasPointer(String name) {
        return name.equals(this.pointerOf) && this.held.containsKey(name);
    }

    boolean isEmpty() {
        return this.held.isEmpty();
    }

    /**
     * Records that bring a playback that has only just read a snapshot to what is held now: one for each version the
     * states were written by, each holding its states and the pointer
     */
    List<byte[]> describe() {
        Map<Integer, List<CompatRecord.Op>> byVersion = new LinkedHashMap<>();
        this.held.forEach((name, held) -> byVersion.computeIfAbsent(held.version(), version -> new ArrayList<>()).add(CompatRecord.Op.state(name, held.state())));
        if (this.pointerOf != null && this.held.containsKey(this.pointerOf)) {
            byVersion.computeIfAbsent(this.pointerVersion, version -> new ArrayList<>())
                .add(CompatRecord.Op.pointer(this.pointerOf, this.pointerShare, this.pointerX, this.pointerY));
        }
        List<byte[]> records = new ArrayList<>();
        byVersion.forEach((version, ops) -> records.add(CompatRecord.write(version, ops)));
        return records;
    }

    void clear() {
        this.held.clear();
        this.pointerOf = null;
        this.pointerVersion = 0;
        this.pointerShare = false;
        this.pointerX = 0.0F;
        this.pointerY = 0.0F;
    }

}
