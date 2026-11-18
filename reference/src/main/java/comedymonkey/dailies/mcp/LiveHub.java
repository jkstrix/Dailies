package comedymonkey.dailies.mcp;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The moments page's live feed (GET /live): whoever writes a moment says so here, and every open page hears of it
 * at once. A message only says what changed; the page then asks for the data through the tools it already uses, so
 * there is one way of reading moments, not two.
 * <p>
 * Publishing never waits: a listener that has stopped reading is dropped, and with nobody listening it costs nothing.
 */
public final class LiveHub {

    public static final int MAX_LISTENERS = 8;
    private static final int QUEUE_LENGTH = 512;

    public static final class Listener {
        private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>(QUEUE_LENGTH);
        private volatile boolean dropped = false;
        private @Nullable Runnable whenDropped = null;

        /** The next message, already in event-stream form, or null when none came in time */
        public @Nullable String next(long timeout, TimeUnit unit) throws InterruptedException {
            return this.queue.poll(timeout, unit);
        }

        /** True once the listener fell too far behind and was let go */
        public boolean dropped() {
            return this.dropped;
        }

        /**
         * What to do the moment the listener is let go, such as closing its connection: a page that stopped reading
         * leaves its thread stuck writing to it, and only closing the connection frees that thread. Runs at once when
         * the listener was let go already. It runs on the thread that published, so it must be quick.
         */
        public void whenDropped(Runnable action) {
            boolean now;
            synchronized (this) {
                this.whenDropped = action;
                now = this.dropped;
            }
            if (now) {
                action.run();
            }
        }

        private void drop() {
            Runnable action;
            synchronized (this) {
                this.dropped = true;
                action = this.whenDropped;
            }
            if (action != null) {
                try {
                    action.run();
                } catch (RuntimeException ignored) {
                    // Publishing never fails because a listener could not be let go tidily
                }
            }
        }
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private LiveHub() {
    }

    /** A new listener, or null when there are already as many as the server will carry */
    public static synchronized @Nullable Listener listen() {
        if (LISTENERS.size() >= MAX_LISTENERS) {
            return null;
        }
        Listener listener = new Listener();
        LISTENERS.add(listener);
        return listener;
    }

    public static void leave(Listener listener) {
        LISTENERS.remove(listener);
    }

    public static int listeners() {
        return LISTENERS.size();
    }

    /**
     * @param event the kind of change: event, dialogue, interaction or clips
     * @param json one line of JSON saying which record of which session
     */
    public static void publish(String event, String json) {
        if (LISTENERS.isEmpty()) {
            return;
        }
        String message = "event: " + event + "\ndata: " + json.replace("\r", "").replace("\n", " ") + "\n\n";
        for (Listener listener : LISTENERS) {
            if (!listener.queue.offer(message)) {
                LISTENERS.remove(listener);
                listener.drop();
            }
        }
    }

}
