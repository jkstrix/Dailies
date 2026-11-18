package comedymonkey.dailies.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What an export that never finished left beside its output is told apart from everything else, and taken away */
class TemporaryFilesTest {

    private static final long OURS = ProcessHandle.current().pid();

    @Test
    void everyTemporaryNameSaysWhoseItIs() {
        assertEquals(OptionalLong.of(OURS), TemporaryFiles.owner(TemporaryFiles.picture("100% run", ".mp4"), false));
        assertEquals(OptionalLong.of(OURS), TemporaryFiles.owner(TemporaryFiles.sound("clip"), false));
        assertEquals(OptionalLong.of(OURS), TemporaryFiles.owner(TemporaryFiles.voice("clip", 3), false));
        assertEquals(OptionalLong.of(OURS), TemporaryFiles.owner(TemporaryFiles.track("clip", "game"), false));
        assertEquals(OptionalLong.of(OURS), TemporaryFiles.owner(TemporaryFiles.track("clip", "music-in"), false));
        assertEquals(OptionalLong.of(OURS), TemporaryFiles.owner(TemporaryFiles.track("clip", "voice2"), false));
        assertEquals(OptionalLong.of(OURS), TemporaryFiles.owner(TemporaryFiles.parts(), true));
        assertEquals(OptionalLong.of(1234), TemporaryFiles.owner("2026-11-18 Scene 1.dailies-picture-1234.mkv", false));
    }

    @Test
    void nothingElseIsTakenForOne() {
        assertTrue(TemporaryFiles.owner("clip.mp4", false).isEmpty());
        assertTrue(TemporaryFiles.owner("clip.wav", false).isEmpty());
        assertTrue(TemporaryFiles.owner("notes.pcm", false).isEmpty());
        assertTrue(TemporaryFiles.owner("clip.game.wav", false).isEmpty());
        assertTrue(TemporaryFiles.owner("clip.dailies-picture.mp4", false).isEmpty(), "no process id, no claim");
        assertTrue(TemporaryFiles.owner(".dailies-parts-abc", true).isEmpty());
        // A file with a folder's name, or a folder with a file's, is not one
        assertTrue(TemporaryFiles.owner(TemporaryFiles.parts(), false).isEmpty());
        assertTrue(TemporaryFiles.owner(TemporaryFiles.sound("clip"), true).isEmpty());
    }

    /** The parts folder carries nothing the person typed, so no percent sign of theirs reaches the encoders */
    @Test
    void thePartsFolderIsNamedByNothingTyped() {
        String parts = TemporaryFiles.parts();
        assertTrue(parts.startsWith(".dailies-parts-" + OURS + "-"));
        assertFalse(parts.contains("%"));
        assertTrue(!parts.equals(TemporaryFiles.parts()), "a new one every time");
    }

    @Test
    void whatAnEndedGameLeftIsTakenAwayAndNothingElse(@TempDir Path folder) throws Exception {
        long gone = 999_999_991L;
        long running = 999_999_992L;
        Path deadPicture = Files.writeString(folder.resolve("clip.dailies-picture-" + gone + ".mp4"), "x");
        Path deadSound = Files.writeString(folder.resolve("clip.dailies-sound-" + gone + ".pcm"), "x");
        Path deadVoice = Files.writeString(folder.resolve("clip.dailies-sound-" + gone + "-voice0.pcm"), "x");
        Path deadTrack = Files.writeString(folder.resolve("clip.dailies-track-" + gone + "-mix.pcm"), "x");
        Path deadParts = Files.createDirectory(folder.resolve(".dailies-parts-" + gone + "-0f1e2d3c-aaaa-bbbb-cccc-000000000000"));
        Files.writeString(deadParts.resolve("0-000001.ts"), "x");
        Files.writeString(deadParts.resolve("0.csv"), "x");
        Path livePicture = Files.writeString(folder.resolve("other.dailies-picture-" + running + ".mp4"), "x");
        Path liveParts = Files.createDirectory(folder.resolve(".dailies-parts-" + running + "-0f1e2d3c-aaaa-bbbb-cccc-000000000001"));
        Path video = Files.writeString(folder.resolve("clip.mp4"), "x");
        Path stem = Files.writeString(folder.resolve("clip.game.wav"), "x");
        Path someFolder = Files.createDirectory(folder.resolve("clip.dailies-parts"));

        int removed = TemporaryFiles.sweep(List.of(folder), pid -> pid == running, System.currentTimeMillis());

        assertEquals(5, removed);
        assertFalse(Files.exists(deadPicture));
        assertFalse(Files.exists(deadSound));
        assertFalse(Files.exists(deadVoice));
        assertFalse(Files.exists(deadTrack));
        assertFalse(Files.exists(deadParts));
        assertTrue(Files.exists(livePicture), "an export still running in another game is left alone");
        assertTrue(Files.exists(liveParts));
        assertTrue(Files.exists(video));
        assertTrue(Files.exists(stem));
        assertTrue(Files.exists(someFolder));
    }

    @Test
    void aWeekOldLeftoverGoesEvenWhenItsProcessIdIsInUse(@TempDir Path folder) throws Exception {
        long now = System.currentTimeMillis();
        Path old = Files.writeString(folder.resolve("clip.dailies-sound-" + OURS + ".pcm"), "x");
        Files.setLastModifiedTime(old, FileTime.fromMillis(now - TemporaryFiles.FORGOTTEN_MILLIS - 60_000));
        Path fresh = Files.writeString(folder.resolve("other.dailies-sound-" + OURS + ".pcm"), "x");
        assertEquals(1, TemporaryFiles.sweep(List.of(folder), pid -> true, now));
        assertFalse(Files.exists(old));
        assertTrue(Files.exists(fresh));
    }

    @Test
    void aMissingFolderOrOneGivenTwiceIsNoTrouble(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("clip.dailies-sound-999999993.pcm"), "x");
        assertEquals(1, TemporaryFiles.sweep(List.of(folder, folder, folder.resolve("nowhere")), pid -> false, System.currentTimeMillis()));
    }

}
