package comedymonkey.dailies.moments.voice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When a voice segment's audio may go (specification REC-8): kept unless a setting says otherwise */
class VoiceRetentionTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long DAY = 24L * 60 * 60 * 1000;

    @Test
    void keptByDefault() {
        assertFalse(VoiceRetention.remove("done", NOW - 1000 * DAY, NOW, false, 0));
        assertFalse(VoiceRetention.remove("pending", NOW - 1000 * DAY, NOW, false, 0));
    }

    @Test
    void onceTranscribedMeansDoneOrFoundEmpty() {
        assertTrue(VoiceRetention.remove("done", NOW, NOW, true, 0));
        assertTrue(VoiceRetention.remove("empty", NOW, NOW, true, 0));
        assertFalse(VoiceRetention.remove("pending", NOW, NOW, true, 0));
        // A failed transcription may yet be tried again by hand, so its audio stays
        assertFalse(VoiceRetention.remove("failed", NOW, NOW, true, 0));
    }

    @Test
    void afterDaysWhateverItsState() {
        assertTrue(VoiceRetention.remove("pending", NOW - 8 * DAY, NOW, false, 7));
        assertTrue(VoiceRetention.remove("failed", NOW - 8 * DAY, NOW, false, 7));
        assertFalse(VoiceRetention.remove("pending", NOW - 6 * DAY, NOW, false, 7));
        // A segment that does not say when it was made is never old
        assertFalse(VoiceRetention.remove("pending", 0, NOW, false, 7));
    }

}
