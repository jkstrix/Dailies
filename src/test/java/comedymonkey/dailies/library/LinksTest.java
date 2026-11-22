package comedymonkey.dailies.library;

import comedymonkey.dailies.file.RecordingManifest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Points of view (specification PLAY-18): only different players' recordings are linked */
class LinksTest {

    private static RecordingManifest by(String recorderId) {
        RecordingManifest manifest = new RecordingManifest();
        manifest.recorderId = recorderId;
        return manifest;
    }

    @Test
    void onePlayersRecordingsAreOnePointOfView() {
        assertTrue(Links.samePlayer(by("a"), by("a")));
    }

    @Test
    void differentPlayersAreDifferentPointsOfView() {
        assertFalse(Links.samePlayer(by("a"), by("b")));
    }

    @Test
    void anUnknownRecorderIsNotTakenForTheSamePlayer() {
        assertFalse(Links.samePlayer(by(""), by("")));
    }
}
