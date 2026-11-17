package comedymonkey.dailies.playback;

import comedymonkey.dailies.Host;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The playback half of an extension (specification REC-23): what to do with the records an extension wrote while
 * recording, and what to clear before the world is rebuilt from a snapshot.
 */
public final class PlaybackExtensions {

    public interface Extension {
        /** A record this extension wrote has been reached. Client thread */
        void play(byte[] data, int from, int length, boolean skipping) throws Exception;

        /** The world is about to be rebuilt from a snapshot: drop anything held from before */
        default void reset() {
        }

        /** A jump has arrived and the world stands still at its tick: put right anything that only settles over time */
        default void arrived() {
        }
    }

    private static final Map<String, Extension> EXTENSIONS = new ConcurrentHashMap<>();

    private PlaybackExtensions() {
    }

    public static void register(String id, Extension extension) {
        EXTENSIONS.put(id, extension);
    }

    static Extension find(String id) {
        return EXTENSIONS.get(id);
    }

    static void arrivedAll() {
        EXTENSIONS.values().forEach(extension -> {
            try {
                extension.arrived();
            } catch (Throwable t) {
                Host.LOGGER.warn("Dailies: an extension could not settle after a jump", t);
            }
        });
    }

    static void resetAll() {
        EXTENSIONS.values().forEach(extension -> {
            try {
                extension.reset();
            } catch (Throwable t) {
                Host.LOGGER.warn("Dailies: an extension could not be reset before a jump", t);
            }
        });
    }

}
