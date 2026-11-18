package comedymonkey.dailies.ui;

import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Settings changed a moment ago whose saving is still under way, so that what shows them can show the new value
 * meanwhile. Each change is known by a ticket of its own rather than by its value: when the same setting is changed
 * twice, the end of the first save must not take away the second, even when the two values happen to be equal. No game
 * here, so it can be tested on its own. Any thread.
 */
final class PendingValues {

    /** One change on its way. Two tickets are never equal, whatever their values */
    static final class Ticket {
        final Object value;

        private Ticket(Object value) {
            this.value = value;
        }
    }

    private final Map<String, Ticket> waiting = new ConcurrentHashMap<>();

    /** A change is on its way; it stands for the setting until its save is done or a newer change takes its place */
    Ticket put(String name, Object value) {
        Ticket ticket = new Ticket(value);
        this.waiting.put(name, ticket);
        return ticket;
    }

    /** The value the setting will have once what is on its way has been saved, or null when nothing is */
    @Nullable Object get(String name) {
        Ticket ticket = this.waiting.get(name);
        return ticket == null ? null : ticket.value;
    }

    /** The save of a change is over. A newer change of the same setting still stands */
    void done(String name, Ticket ticket) {
        this.waiting.remove(name, ticket);
    }

    boolean isEmpty() {
        return this.waiting.isEmpty();
    }

}
