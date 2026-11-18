package comedymonkey.dailies.moments.track;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Objects;

/**
 * Two sources, one event (plan 2.1): the player's game and the companion on the server can both report the same craft
 * being assembled or lost. A report that arrives within {@link CraftRules#MERGE_MILLIS} of an earlier one of the same
 * type, about the same craft, from the other source, becomes part of that event instead of a second one, as deaths do.
 * <p>
 * A report that does not name its craft matches any craft; two that name different craft never match. Kept apart from
 * the game so that it can be tested on its own.
 */
public final class SourceMerge {

    /** One event that a later report may still join */
    public record Entry(String eventId, String type, @Nullable String craft, String source, long time) {
    }

    private final long window;
    private final ArrayDeque<Entry> recent = new ArrayDeque<>();

    public SourceMerge() {
        this(CraftRules.MERGE_MILLIS);
    }

    public SourceMerge(long windowMillis) {
        this.window = windowMillis;
    }

    /**
     * The event a report joins, or null when it is a new one.
     *
     * @param source "game" or "server": a report never joins an event from its own source, since two of those are two
     *               things that happened
     */
    public synchronized @Nullable Entry find(String type, @Nullable String craft, String source, long now) {
        this.forget(now);
        Iterator<Entry> newestFirst = this.recent.descendingIterator();
        while (newestFirst.hasNext()) {
            Entry entry = newestFirst.next();
            if (!entry.type().equals(type) || entry.source().equals(source) || entry.source().startsWith("both:") || Math.abs(now - entry.time()) > this.window) {
                continue;
            }
            if (craft != null && entry.craft() != null && !craft.equals(entry.craft())) {
                continue;
            }
            return entry;
        }
        return null;
    }

    /** Remembers a new event so that a report from the other source can join it */
    public synchronized void remember(String eventId, String type, @Nullable String craft, String source, long now) {
        this.forget(now);
        this.recent.addLast(new Entry(Objects.requireNonNull(eventId), type, craft, source, now));
    }

    /** Remembers that an event has been joined, so that it cannot be joined again by a third report from the same source */
    public synchronized void joined(Entry entry, String source) {
        this.recent.remove(entry);
        this.recent.addLast(new Entry(entry.eventId(), entry.type(), entry.craft(), "both:" + entry.source() + "+" + source, entry.time()));
    }

    private void forget(long now) {
        while (!this.recent.isEmpty() && now - this.recent.peekFirst().time() > this.window * 2) {
            this.recent.removeFirst();
        }
    }

}
