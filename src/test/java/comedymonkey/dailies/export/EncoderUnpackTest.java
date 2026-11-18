package comedymonkey.dailies.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The packed encoder is unpacked once, and never written over while its stamp says it is the same (EXP-1) */
class EncoderUnpackTest {

    private static final String PLATFORM = "windows-x86_64";

    /** A packed encoder as the build makes one: deflated entries whose sizes are only known once read */
    private static byte[] packed(Map<String, String> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                JarEntry entry = new JarEntry(file.getKey());
                // A fixed time, so the same files always pack to the same bytes and the same stamp
                entry.setTime(1_700_000_000_000L);
                jar.putNextEntry(entry);
                jar.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] usual() throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("org/bytedeco/ffmpeg/" + PLATFORM + "/", "");
        files.put("org/bytedeco/ffmpeg/" + PLATFORM + "/ffmpeg.exe", "the encoder");
        files.put("org/bytedeco/ffmpeg/" + PLATFORM + "/ffprobe.exe", "the inspector");
        files.put("org/bytedeco/ffmpeg/" + PLATFORM + "/avcodec-61.dll", "the codecs");
        files.put("org/bytedeco/ffmpeg/linux-x86_64/ffmpeg", "another platform's encoder");
        // The folder's own entry, empty, is passed over
        return packed(files);
    }

    private static Encoder.Packed from(byte[] bytes) {
        return () -> new ByteArrayInputStream(bytes);
    }

    @Test
    void theFirstTimeEverythingIsUnpackedAndStamped(@TempDir Path folder) throws Exception {
        Path program = Encoder.unpack(from(usual()), folder, PLATFORM, "1.0");
        assertEquals(folder.resolve("ffmpeg.exe"), program);
        assertEquals("the encoder", Files.readString(program));
        assertEquals("the codecs", Files.readString(folder.resolve("avcodec-61.dll")));
        assertEquals("the inspector", Files.readString(folder.resolve("ffprobe.exe")));
        assertFalse(Files.exists(folder.resolve("ffmpeg")), "another platform's files stay packed");
        assertEquals(Encoder.stamp(from(usual()), "1.0"), Files.readString(folder.resolve(Encoder.STAMP)));
        try (var left = Files.list(folder)) {
            assertTrue(left.noneMatch(path -> path.getFileName().toString().endsWith(Encoder.UNPACKING)), "nothing half unpacked is left");
        }
    }

    @Test
    void aMatchingStampWritesNothingAtAll(@TempDir Path folder) throws Exception {
        Encoder.unpack(from(usual()), folder, PLATFORM, "1.0");
        // Were it written again, this would be put back
        Files.writeString(folder.resolve("avcodec-61.dll"), "in use by another game");
        Encoder.unpack(from(usual()), folder, PLATFORM, "1.0");
        assertEquals("in use by another game", Files.readString(folder.resolve("avcodec-61.dll")));
    }

    @Test
    void anotherBuildIsUnpackedAgain(@TempDir Path folder) throws Exception {
        Encoder.unpack(from(usual()), folder, PLATFORM, "1.0");
        Files.writeString(folder.resolve("avcodec-61.dll"), "an older build's codecs");
        Encoder.unpack(from(usual()), folder, PLATFORM, "1.1");
        assertEquals("the codecs", Files.readString(folder.resolve("avcodec-61.dll")));
        assertEquals(Encoder.stamp(from(usual()), "1.1"), Files.readString(folder.resolve(Encoder.STAMP)));
    }

    @Test
    void aDifferentEncoderUnderTheSameVersionIsUnpackedAgain(@TempDir Path folder) throws Exception {
        Encoder.unpack(from(usual()), folder, PLATFORM, "1.0");
        Map<String, String> changed = new LinkedHashMap<>();
        changed.put("org/bytedeco/ffmpeg/" + PLATFORM + "/ffmpeg.exe", "a newer encoder");
        byte[] newer = packed(changed);
        assertNotEquals(Encoder.stamp(from(usual()), "1.0"), Encoder.stamp(from(newer), "1.0"));
        Encoder.unpack(from(newer), folder, PLATFORM, "1.0");
        assertEquals("a newer encoder", Files.readString(folder.resolve("ffmpeg.exe")));
    }

    @Test
    void aMissingProgramIsUnpackedAgainWhateverTheStampSays(@TempDir Path folder) throws Exception {
        Encoder.unpack(from(usual()), folder, PLATFORM, "1.0");
        Files.delete(folder.resolve("ffmpeg.exe"));
        Path program = Encoder.unpack(from(usual()), folder, PLATFORM, "1.0");
        assertEquals("the encoder", Files.readString(program));
    }

    /** On Windows a file another program has open cannot be replaced: one with the same bytes is not tried */
    @Test
    void aFileInUseWithTheSameBytesIsLeftWhereItIs(@TempDir Path folder) throws Exception {
        Encoder.unpack(from(usual()), folder, PLATFORM, "1.0");
        try (FileChannel open = FileChannel.open(folder.resolve("ffmpeg.exe"), StandardOpenOption.READ)) {
            Encoder.unpack(from(usual()), folder, PLATFORM, "1.1");
            assertTrue(open.isOpen());
        }
        assertEquals("the encoder", Files.readString(folder.resolve("ffmpeg.exe")));
    }

    @Test
    void whatAnUnpackingCutShortLeftIsTakenAway(@TempDir Path folder) throws Exception {
        Path left = folder.resolve("avcodec-61.dll.0000" + Encoder.UNPACKING);
        Files.writeString(left, "half");
        Encoder.unpack(from(usual()), folder, PLATFORM, "1.0");
        assertFalse(Files.exists(left));
    }

    @Test
    void anIncompleteEncoderIsRefusedAndNotStamped(@TempDir Path folder) throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("org/bytedeco/ffmpeg/" + PLATFORM + "/avcodec-61.dll", "the codecs");
        byte[] incomplete = packed(files);
        assertThrows(IOException.class, () -> Encoder.unpack(from(incomplete), folder, PLATFORM, "1.0"));
        assertFalse(Files.exists(folder.resolve(Encoder.STAMP)));
    }

    @Test
    void theStampDecides() {
        assertTrue(Encoder.upToDate("Dailies 1.0, 5 bytes, CRC32 ab", "Dailies 1.0, 5 bytes, CRC32 ab", true));
        assertTrue(Encoder.upToDate("Dailies 1.0, 5 bytes, CRC32 ab\n", "Dailies 1.0, 5 bytes, CRC32 ab", true), "a line ending added by hand changes nothing");
        assertFalse(Encoder.upToDate("Dailies 1.0, 5 bytes, CRC32 ab", "Dailies 1.0, 5 bytes, CRC32 ab", false), "no program, no trust");
        assertFalse(Encoder.upToDate(null, "Dailies 1.0, 5 bytes, CRC32 ab", true), "no stamp: an unpacking that never finished");
        assertFalse(Encoder.upToDate("Dailies 0.9, 5 bytes, CRC32 ab", "Dailies 1.0, 5 bytes, CRC32 ab", true));
    }

}
