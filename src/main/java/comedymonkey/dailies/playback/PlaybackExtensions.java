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

        /** Whether the world as it stands holds something of this extension's, so that a jump must apply every chunk again */
        default boolean needsNewWorld() {
            return false;
        }

        /** A jump has arrived and the world stands still at its tick: put right anything that only settles over time */
        default void arrived() {
        }

        /**
         * The world is about to be described as a snapshot, for a trimmed recording that starts part way through a
         * segment (specification FILE-10). Write the records that would bring a playback that has only just read a
         * snapshot to the state this extension holds now: whatever it has kept from records that are being cut away.
         * Each is written under the id the extension is registered with. Client thread
         */
        default void describe(java.util.function.Consumer<byte[]> out) throws Exception {
        }

        /** Just before the world is described: take out of it anything that is not the recording's own */
        default void beforeDescribe() {
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

    static void describeAll(java.util.function.BiConsumer<String, byte[]> out) {
        EXTENSIONS.forEach((id, extension) -> {
            try {
                extension.describe(data -> out.accept(id, data));
            } catch (Throwable t) {
                Host.LOGGER.warn("Dailies: the extension {} could not describe what it holds for a trimmed recording, so that part starts without it", id, t);
            }
        });
    }

    static void beforeDescribeAll() {
        EXTENSIONS.values().forEach(extension -> {
            try {
                extension.beforeDescribe();
            } catch (Throwable t) {
                Host.LOGGER.warn("Dailies: an extension could not get ready for the world to be described", t);
            }
        });
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

    static boolean anyNeedsNewWorld() {
        for (Extension extension : EXTENSIONS.values()) {
            try {
                if (extension.needsNewWorld()) {
                    return true;
                }
            } catch (Throwable t) {
                return true;
            }
        }
        return false;
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
