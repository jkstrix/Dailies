package comedymonkey.dailies.capture;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How state that lives outside the vanilla world gets into a snapshot (specification REC-23): the physics objects of
 * chapter VII, for one. An extension is asked at the end of every snapshot and writes whatever a freshly joined game
 * would need, as packets, mod messages or records of its own.
 */
public final class SnapshotExtensions {

    @FunctionalInterface
    public interface Extension {
        /** Client thread. A failure is logged and costs only this extension's part of the snapshot */
        void write(Recorder.Out out, GameCodec codec) throws Exception;
    }

    private static final Map<String, Extension> EXTENSIONS = new ConcurrentHashMap<>();
    /** Those that can also describe a world that is being played back, for a snapshot written into a trimmed recording */
    private static final Map<String, Extension> IN_PLAYBACK = new ConcurrentHashMap<>();

    private SnapshotExtensions() {
    }

    public static void register(String id, Extension extension) {
        EXTENSIONS.put(id, extension);
    }

    /**
     * @param inPlayback whether the extension reads its state from the world itself, so that it describes a playback
     *                   world as well as a live one (a craft does; the recorder's own HUD and the skins the game has
     *                   downloaded do not, and playback carries those across itself)
     */
    public static void register(String id, Extension extension, boolean inPlayback) {
        EXTENSIONS.put(id, extension);
        if (inPlayback) {
            IN_PLAYBACK.put(id, extension);
        }
    }

    static Map<String, Extension> all() {
        return EXTENSIONS;
    }

    static Map<String, Extension> inPlayback() {
        return IN_PLAYBACK;
    }

}
