package comedymonkey.dailies.library;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A shared session (FILE-15): a recording and its moments in one file, and nothing in it able to land elsewhere */
class SharePackTest {

    @TempDir
    Path folder;

    @Test
    void aPackHoldsTheRecordingAndTheMomentsAndUnpacksAsItWas() throws IOException {
        byte[] recording = new byte[200_000];
        for (int i = 0; i < recording.length; i++) {
            recording[i] = (byte) (i * 31);
        }
        Path file = this.folder.resolve("evening.dailies");
        Files.write(file, recording);
        JsonObject info = new JsonObject();
        info.addProperty("recording_id", "abc");
        info.addProperty("recorder_name", "Alex");
        Path pack = this.folder.resolve("out").resolve("evening" + SharePack.EXTENSION);
        SharePack.write(pack, info, List.of(
            SharePack.Item.of(SharePack.RECORDING, file),
            SharePack.Item.of("moments/20261117-200000/events.jsonl", "{\"id\":\"e1\"}\n".getBytes(StandardCharsets.UTF_8)),
            SharePack.Item.of("moments/20261117-200000/audio/x_1.wav", new byte[]{1, 2, 3})));
        assertTrue(Files.isRegularFile(pack));
        assertFalse(Files.exists(pack.resolveSibling(pack.getFileName() + ".packing")), "nothing half-written is left behind");

        JsonObject described = SharePack.info(pack);
        assertEquals("Alex", described.get("recorder_name").getAsString());
        assertEquals(SharePack.FORMAT, described.get("format").getAsInt());

        Path into = this.folder.resolve("unpacked");
        JsonObject unpacked = SharePack.unpack(pack, into);
        assertEquals("abc", unpacked.get("recording_id").getAsString());
        assertArrayEquals(recording, Files.readAllBytes(into.resolve(SharePack.RECORDING)));
        assertEquals("{\"id\":\"e1\"}\n", Files.readString(into.resolve("moments/20261117-200000/events.jsonl")));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(into.resolve("moments/20261117-200000/audio/x_1.wav")));
    }

    @Test
    void namesThatWouldLandOutsideAreRefused() throws IOException {
        assertFalse(SharePack.safe("../evil.dll"));
        assertFalse(SharePack.safe("moments/../../evil"));
        assertFalse(SharePack.safe("/etc/passwd"));
        assertFalse(SharePack.safe("C:/Windows/evil"));
        assertFalse(SharePack.safe("moments\\evil"));
        assertTrue(SharePack.safe("moments/s/events.jsonl"));
        assertThrows(IllegalArgumentException.class, () -> SharePack.Item.of("../x", new byte[0]));

        Path hostile = this.folder.resolve("hostile" + SharePack.EXTENSION);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(hostile))) {
            zip.putNextEntry(new ZipEntry(SharePack.INFO));
            zip.write("{\"format\":1}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("../escaped.txt"));
            zip.write(new byte[]{1});
            zip.closeEntry();
        }
        assertThrows(IOException.class, () -> SharePack.unpack(hostile, this.folder.resolve("in")));
        assertFalse(Files.exists(this.folder.resolve("escaped.txt")));
    }

    @Test
    void aPackFromANewerDailiesOrWithoutARecordingIsRefused() throws IOException {
        Path newer = this.folder.resolve("newer" + SharePack.EXTENSION);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(newer))) {
            zip.putNextEntry(new ZipEntry(SharePack.INFO));
            zip.write("{\"format\":99}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(SharePack.RECORDING));
            zip.write(new byte[]{1});
            zip.closeEntry();
        }
        assertThrows(IOException.class, () -> SharePack.unpack(newer, this.folder.resolve("a")));
        Path empty = this.folder.resolve("empty" + SharePack.EXTENSION);
        SharePack.write(empty, new JsonObject(), List.of());
        assertThrows(IOException.class, () -> SharePack.unpack(empty, this.folder.resolve("b")));
        Path notAPack = this.folder.resolve("plain.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(notAPack))) {
            zip.putNextEntry(new ZipEntry("readme.txt"));
            zip.closeEntry();
        }
        assertThrows(IOException.class, () -> SharePack.info(notAPack));
    }


    @Test
    void aPackWithMoreFilesOrBytesThanAllowedIsRefused() throws IOException {
        Path pack = this.folder.resolve("many" + SharePack.EXTENSION);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(pack))) {
            zip.putNextEntry(new ZipEntry(SharePack.INFO));
            zip.write("{}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(SharePack.RECORDING));
            zip.write(new byte[5000]);
            zip.closeEntry();
            for (int i = 0; i < 10; i++) {
                zip.putNextEntry(new ZipEntry("moments/s/audio/" + i + ".wav"));
                zip.write(new byte[]{1});
                zip.closeEntry();
            }
        }
        assertThrows(IOException.class, () -> SharePack.unpack(pack, this.folder.resolve("a"), 1_000_000, 5));
        assertThrows(IOException.class, () -> SharePack.unpack(pack, this.folder.resolve("b"), 4000, 1000));
        // Within both, it unpacks
        SharePack.unpack(pack, this.folder.resolve("c"), 1_000_000, 1000);
        assertTrue(Files.isRegularFile(this.folder.resolve("c").resolve(SharePack.RECORDING)));
        assertEquals(1, Files.size(this.folder.resolve("c/moments/s/audio/9.wav")));
    }

}
