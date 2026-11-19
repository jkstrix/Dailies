package comedymonkey.dailies.windows;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.file.RecordingFile;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.file.RecordingWriter;
import comedymonkey.dailies.file.SafePaths;
import comedymonkey.dailies.playback.Playback;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What compats store once in a recording (specification REC-28 and PLAY-23): bytes too large to ride in every snapshot
 * with a state, such as a schematic, which a state names by a handle instead. The handle is the SHA-256 of the bytes in
 * hex, so the same bytes stored again are the same handle and are stored once; the recording keeps them as shared data
 * (packed, as all of it is) and lists them in its manifest, so trimming and combining carry them (FILE-9, FILE-10), and
 * playback finds them by the handle wherever it has jumped to.
 */
final class CompatBlobs {

    /** The most bytes one blob may hold, as for the largest packet a recording keeps */
    static final int MOST_BYTES = 16 * 1024 * 1024;
    /** How many bytes of blobs read in playback are kept, so a state drawn frame after frame reads its blob once */
    private static final long KEPT_BYTES = 32L * 1024 * 1024;

    /** The recording the set below belongs to */
    private static @Nullable String recording = null;
    /** The handles already handed to that recording's writer */
    private static final Set<String> STORED = new HashSet<>();
    /** Blobs read in playback, by recording id and handle, the most recently used last */
    private static final Map<String, byte[]> KEPT = new LinkedHashMap<>(16, 0.75f, true);
    private static long keptBytes = 0;

    /** The array last stored and its hash; compats hand over arrays they no longer change (see storeOnce) */
    private static byte @Nullable [] lastData = null;
    private static byte @Nullable [] lastHash = null;

    private CompatBlobs() {
    }

    /** See {@link comedymonkey.dailies.api.compat.DailiesCompats#storeOnce} */
    static synchronized String store(byte[] data) {
        Objects.requireNonNull(data, "data");
        if (data.length > MOST_BYTES) {
            throw new IllegalArgumentException("A compat's stored bytes are at most " + MOST_BYTES + " long, not " + data.length);
        }
        // The same array handed over again (a compat storing its file again so a recording started since gets it) is
        // not hashed again: hashing up to 16 MB every couple of seconds would cost a frame now and then
        byte[] hash;
        if (data == lastData && lastHash != null) {
            hash = lastHash;
        } else {
            hash = RecordingWriter.hashOf(data);
            lastData = data;
            lastHash = hash;
        }
        String handle = HexFormat.of().formatHex(hash);
        Recorder recorder = Recorder.get();
        String id = recorder.recordingId();
        if (id != null) {
            if (!id.equals(recording)) {
                recording = id;
                STORED.clear();
            }
            if (STORED.add(handle)) {
                // A copy: the compat may go on using its array, and the writer thread reads this one later
                recorder.keepCompatBlob(data.clone(), hash);
            }
        }
        return handle;
    }

    /** See {@link comedymonkey.dailies.api.compat.DailiesCompats#stored} */
    static synchronized byte @Nullable [] read(String handle) {
        RecordingFile file = Playback.file();
        if (file == null || !Playback.active() || handle == null || !SafePaths.matches(handle, SafePaths.SHA256)) {
            return null;
        }
        String key = file.id() + "/" + handle;
        byte[] kept = KEPT.get(key);
        if (kept != null) {
            return kept.clone();
        }
        byte[] read;
        try {
            read = find(file, handle);
        } catch (IOException damaged) {
            Host.LOGGER.warn("Dailies: a compat's stored bytes {} could not be read from the recording", handle, damaged);
            return null;
        }
        if (read == null) {
            return null;
        }
        KEPT.put(key, read);
        keptBytes += read.length;
        var oldest = KEPT.values().iterator();
        while (keptBytes > KEPT_BYTES && oldest.hasNext()) {
            keptBytes -= oldest.next().length;
            oldest.remove();
        }
        return read.clone();
    }

    /**
     * A compat's stored bytes in a recording by their handle, or null when the recording does not list them as a compat's
     * (a compat reads its own blobs, never the recording's other shared data) or has lost them
     */
    static byte @Nullable [] find(RecordingFile file, String handle) throws IOException {
        RecordingManifest manifest = file.manifest();
        if (!manifest.compatBlobs.contains(handle) || !file.hasShared(handle)) {
            return null;
        }
        return file.shared(handle);
    }

}
