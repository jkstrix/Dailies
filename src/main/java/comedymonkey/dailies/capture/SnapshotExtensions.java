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

    private SnapshotExtensions() {
    }

    public static void register(String id, Extension extension) {
        EXTENSIONS.put(id, extension);
    }

    static Map<String, Extension> all() {
        return EXTENSIONS;
    }

}
