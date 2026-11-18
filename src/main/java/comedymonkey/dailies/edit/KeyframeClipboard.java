package comedymonkey.dailies.edit;

import java.util.List;

/**
 * Keyframes that were copied, each with the index of the track it came from, ready to be pasted any number of times
 * (specification EDIT-3). Made by {@link EditHistory#copy}; it does not change when the scene does afterwards.
 */
public final class KeyframeClipboard {

    /** One copied keyframe and the index of the track it was on */
    public record Entry(int track, Keyframe keyframe) {
    }

    private final List<Entry> entries;

    KeyframeClipboard(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    public List<Entry> entries() {
        return this.entries;
    }

    public boolean isEmpty() {
        return this.entries.isEmpty();
    }

    public int size() {
        return this.entries.size();
    }

    /** The earliest tick among the copied keyframes, or -1 when there are none */
    public int firstTick() {
        return this.entries.stream().mapToInt(entry -> entry.keyframe().tick()).min().orElse(-1);
    }

    /** The latest tick among the copied keyframes, or -1 when there are none */
    public int lastTick() {
        return this.entries.stream().mapToInt(entry -> entry.keyframe().tick()).max().orElse(-1);
    }

}
