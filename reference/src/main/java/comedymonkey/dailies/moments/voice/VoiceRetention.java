package comedymonkey.dailies.moments.voice;

import com.google.gson.JsonObject;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.moments.MJson;
import comedymonkey.dailies.moments.storage.MomentsSession;
import comedymonkey.dailies.moments.storage.MomentsStorage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The voice half of the storage settings (specification REC-8): a voice segment's audio can be removed once it has
 * been transcribed, or once it is a number of days old. Both are off unless set, so the audio is kept. The words of a
 * transcribed segment, its times and who spoke stay; only the WAV goes, and the record says {@code audio_removed}.
 */
public final class VoiceRetention {

    private static final long DAY_MILLIS = 24L * 60 * 60 * 1000;

    private VoiceRetention() {
    }

    /** Whether a transcription has finished with the segment: words were found, or there were none to find */
    public static boolean transcribed(String status) {
        return "done".equals(status) || "empty".equals(status);
    }

    /**
     * Whether a voice segment's audio is to be removed now.
     *
     * @param status          the segment's transcription status: pending, done, empty or failed
     * @param recordedAt      when it was said, in milliseconds since 1970
     * @param onceTranscribed remove it as soon as it has been transcribed
     * @param afterDays       remove it once it is this many days old, transcribed or not; 0 never
     */
    public static boolean remove(String status, long recordedAt, long now, boolean onceTranscribed, int afterDays) {
        if (onceTranscribed && transcribed(status)) {
            return true;
        }
        return afterDays > 0 && recordedAt > 0 && now - recordedAt > afterDays * DAY_MILLIS;
    }

    /**
     * Goes through every session and removes the audio the settings say to. Reads and deletes files: not for the
     * client thread.
     *
     * @return how many bytes were freed
     */
    public static long apply(MomentsStorage storage, boolean onceTranscribed, int afterDays, long now) {
        if (!onceTranscribed && afterDays <= 0) {
            return 0;
        }
        long freed = 0;
        int removed = 0;
        for (String id : storage.listSessionIds()) {
            MomentsSession session = storage.getSession(id);
            if (session == null) {
                continue;
            }
            for (JsonObject record : session.list(MomentsSession.Kind.DIALOGUE)) {
                String audio = MJson.str(record, "audio_file");
                String recordId = MJson.str(record, "id");
                if (audio == null || recordId == null || record.has("audio_removed")) {
                    continue;
                }
                if (!remove(MJson.str(record, "status", "pending"), MJson.lng(record, "real_time", 0), now, onceTranscribed, afterDays)) {
                    continue;
                }
                Path file = session.dir.resolve(audio).normalize();
                if (!file.startsWith(session.dir.normalize())) {
                    continue;
                }
                try {
                    long size = Files.isRegularFile(file) ? Files.size(file) : 0;
                    Files.deleteIfExists(file);
                    freed += size;
                    removed++;
                    session.update(MomentsSession.Kind.DIALOGUE, recordId, json -> json.addProperty("audio_removed", true));
                } catch (IOException e) {
                    Host.LOGGER.warn("Moments: the audio of voice segment {} could not be removed", recordId, e);
                }
            }
        }
        if (removed > 0) {
            Host.LOGGER.info("Moments: removed the audio of {} voice segment(s) as the storage settings ask, {} bytes", removed, freed);
        }
        return freed;
    }

}
