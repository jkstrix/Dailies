package comedymonkey.dailies.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The downloaded encoder is kept only when it is exactly the one the build names, and fetched once */
class EncoderDownloadTest {

    @TempDir
    Path temp;

    private Path source(String text) throws IOException {
        Path file = temp.resolve("source.jar");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void keptWhenItMatches() throws IOException {
        Path source = source("an encoder");
        String sha = EncoderDownload.sha256(source);
        Path folder = temp.resolve("downloads");
        Path kept = EncoderDownload.fetch(source.toUri().toString(), sha, Files.size(source), folder, "encoder.jar", "test", line -> { });
        assertEquals("an encoder", Files.readString(kept, StandardCharsets.UTF_8));
        // Fetched once: with the source gone, the kept copy is used
        Files.delete(source);
        assertEquals(kept, EncoderDownload.fetch(source.toUri().toString(), sha, kept.toFile().length(), folder, "encoder.jar", "test", line -> { }));
        assertFalse(EncoderDownload.downloading());
    }

    @Test
    void refusedWhenTheChecksumDiffers() throws IOException {
        Path source = source("not the encoder the build names");
        Path folder = temp.resolve("downloads");
        IOException refused = assertThrows(IOException.class, () -> EncoderDownload.fetch(source.toUri().toString(),
            "0".repeat(64), Files.size(source), folder, "encoder.jar", "test", line -> { }));
        assertTrue(refused.getMessage().contains("nothing was kept"), refused.getMessage());
        assertFalse(Files.exists(folder.resolve("encoder.jar")));
        try (var left = Files.list(folder)) {
            assertEquals(0, left.count(), "a partial download was left behind");
        }
    }

    @Test
    void refusedWhenTheLengthDiffers() throws IOException {
        Path source = source("an encoder");
        String sha = EncoderDownload.sha256(source);
        Path folder = temp.resolve("downloads");
        assertThrows(IOException.class, () -> EncoderDownload.fetch(source.toUri().toString(), sha, Files.size(source) + 5, folder, "encoder.jar", "test", line -> { }));
        assertThrows(IOException.class, () -> EncoderDownload.fetch(source.toUri().toString(), sha, Files.size(source) - 2, folder, "encoder.jar", "test", line -> { }));
        assertFalse(Files.exists(folder.resolve("encoder.jar")));
    }

    @Test
    void aWrongKeptCopyIsFetchedAgain() throws IOException {
        Path source = source("an encoder");
        String sha = EncoderDownload.sha256(source);
        Path folder = Files.createDirectories(temp.resolve("downloads"));
        Files.writeString(folder.resolve("encoder.jar"), "an old, broken encoder!", StandardCharsets.UTF_8);
        Path kept = EncoderDownload.fetch(source.toUri().toString(), sha, Files.size(source), folder, "encoder.jar", "test", line -> { });
        assertEquals("an encoder", Files.readString(kept, StandardCharsets.UTF_8));
    }
}
