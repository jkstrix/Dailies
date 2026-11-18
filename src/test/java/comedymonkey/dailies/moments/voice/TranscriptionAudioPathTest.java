package comedymonkey.dailies.moments.voice;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A dialogue record can come from someone else's shared session, so the audio it names is sent to the transcription
 * server only when it is one of the session's own WAVs
 */
class TranscriptionAudioPathTest {

    @Test
    void theSessionsOwnAudioIsFound() throws Exception {
        Path session = Files.createTempDirectory("dailies-session");
        Path audio = session.toAbsolutePath().normalize().resolve("audio");
        assertEquals(audio.resolve("0b5c6c1e-2a43-4c1d-9f5e-1d2a3b4c5d6e_1763496000000.wav"),
            TranscriptionWorker.audioPath(session, "audio/0b5c6c1e-2a43-4c1d-9f5e-1d2a3b4c5d6e_1763496000000.wav"));
        // Written on Windows with the other slash
        assertEquals(audio.resolve("a_1.wav"), TranscriptionWorker.audioPath(session, "audio\\a_1.wav"));
    }

    @Test
    void anythingElseIsNeverRead() throws Exception {
        Path session = Files.createTempDirectory("dailies-session");
        for (String name : List.of(
                "C:/Users/someone/Documents/secret.wav",
                "/etc/passwd",
                "\\\\attacker\\share\\x.wav",
                "//attacker/share/x.wav",
                "audio/../../other/x.wav",
                "audio/../session.json",
                "../audio/x.wav",
                "audio/sub/x.wav",
                "audio/x.mp3",
                "audio/.wav",
                "audio/x.wav/..",
                "D:audio/x.wav",
                "x.wav",
                "audio/x .wav",
                "")) {
            assertNull(TranscriptionWorker.audioPath(session, name), name);
        }
    }

}
