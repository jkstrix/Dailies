package comedymonkey.dailies.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A recording carries the dictionaries it was packed with and is read with those, never the mod's own, so that a later
 * Dailies with dictionaries trained afresh still opens it
 */
class RecordingDictionariesTest {

    @TempDir
    Path folder;

    private static RecordingManifest manifest(String id) {
        RecordingManifest manifest = new RecordingManifest();
        manifest.id = id;
        manifest.name = id;
        manifest.gameVersion = "1.21.1";
        return manifest;
    }

    private static byte[] part(int segment) {
        StringBuilder text = new StringBuilder();
        for (int tick = 0; tick < 2_000; tick++) {
            text.append("snapshot s1t").append(segment * 100 + tick % 100).append(' ');
        }
        return text.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] chunk(int number) {
        return ("chunk " + number + " section light world still ").repeat(200).getBytes(StandardCharsets.US_ASCII);
    }

    /** Two segments and two pieces of shared data, packed with the dictionaries given */
    private Path write(String name, Packing.Dictionaries dictionaries, boolean finish) throws IOException {
        Path file = this.folder.resolve(name + RecordingFile.EXTENSION);
        RecordingWriter writer = RecordingWriter.create(file, manifest(name), dictionaries);
        for (int segment = 0; segment < 2; segment++) {
            writer.beginSegment(segment * 100);
            byte[] bytes = part(segment);
            writer.appendToSegment(bytes, 0, bytes.length, segment * 100 + 100);
            writer.putShared(chunk(segment));
        }
        if (finish) {
            writer.finish();
        } else {
            writer.close();
        }
        return file;
    }

    private static void readsBack(RecordingFile recording) throws IOException {
        for (int segment = 0; segment < 2; segment++) {
            try (InputStream in = recording.openSegment(segment)) {
                assertArrayEquals(part(segment), in.readAllBytes());
            }
            assertArrayEquals(chunk(segment), recording.shared(java.util.HexFormat.of().formatHex(Packing.hash(chunk(segment)))));
        }
    }

    @Test
    void aNewRecordingCarriesTheModsOwnDictionariesFirst() throws IOException {
        Path file = this.folder.resolve("now" + RecordingFile.EXTENSION);
        RecordingWriter.create(file, manifest("now")).finish();
        try (RecordingFile recording = RecordingFile.open(file)) {
            assertTrue(recording.container().dictionaries().same(Packing.Dictionaries.current()));
        }
        // Right after the preamble: the dictionary for shared data, then the one for segment parts, each as it is
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer header = ByteBuffer.wrap(bytes, Container.PREAMBLE_BYTES, Container.HEADER_BYTES);
        assertEquals(Container.DICTIONARY, header.get());
        assertEquals(Container.SHARED_DICTIONARY, header.getInt());
        header.getInt();
        header.getInt();
        int length = header.getInt();
        byte[] stored = Arrays.copyOfRange(bytes, Container.PREAMBLE_BYTES + Container.HEADER_BYTES, Container.PREAMBLE_BYTES + Container.HEADER_BYTES + length);
        assertArrayEquals(Packing.Dictionaries.current().shared().bytes, stored);
    }

    @Test
    void aRecordingIsReadWithItsOwnDictionariesWhenTheModsDiffer() throws IOException {
        Packing.Dictionaries other = TestDictionaries.other();
        assertFalse(other.same(Packing.Dictionaries.current()));
        Path file = this.write("older", other, true);
        try (RecordingFile recording = RecordingFile.open(file)) {
            assertTrue(recording.container().dictionaries().same(other));
            readsBack(recording);
            // The mod's own would not do: what was packed with the recording's dictionary is not what they unpack
            Container container = recording.container();
            byte[] packed = container.read(container.parts.get(0));
            try {
                assertFalse(Arrays.equals(part(0), Packing.unpack(Packing.Dictionaries.current().parts(), packed, 0)));
            } catch (RecordingFormatException expected) {
                // As likely
            }
        }
    }

    @Test
    void aCrashedRecordingIsRecoveredWithItsOwnDictionaries() throws IOException {
        Path file = this.write("crashed", TestDictionaries.other(), false);
        assertTrue(RecordingFile.isUnfinished(file));
        RecordingFile.recover(file);
        try (RecordingFile recording = RecordingFile.open(file)) {
            assertTrue(recording.container().dictionaries().same(TestDictionaries.other()));
            readsBack(recording);
        }
    }

    @Test
    void aRecordingPackedWithoutDictionariesOpens() throws IOException {
        Path file = this.write("plain", Packing.Dictionaries.NONE, true);
        try (RecordingFile recording = RecordingFile.open(file)) {
            assertTrue(recording.container().dictionaries().same(Packing.Dictionaries.NONE));
            readsBack(recording);
        }
    }

    @Test
    void aDamagedDictionaryIsADamagedRecording() throws IOException {
        Path file = this.write("damaged", Packing.Dictionaries.current(), true);
        try (RandomAccessFile raw = new RandomAccessFile(file.toFile(), "rw")) {
            long at = Container.PREAMBLE_BYTES + Container.HEADER_BYTES + 100;
            raw.seek(at);
            int b = raw.read();
            raw.seek(at);
            raw.write(b ^ 0x10);
        }
        // Its description still opens, as the list of recordings reads it; the recorded data does not
        assertEquals("damaged", RecordingFile.readManifest(file).name);
        try (RecordingFile recording = RecordingFile.open(file)) {
            RecordingFormatException damaged = assertThrows(RecordingFormatException.class, () -> recording.openSegment(0).readAllBytes());
            assertEquals(RecordingFormatException.Reason.DAMAGED, damaged.reason());
        }
    }

    /** No backwards compatibility before release: a recording of format 1, packed with deflate, is refused with a message */
    @Test
    void aRecordingOfFormatOneIsRefused() throws IOException {
        Path file = this.write("format one", Packing.Dictionaries.current(), true);
        try (RandomAccessFile raw = new RandomAccessFile(file.toFile(), "rw")) {
            raw.seek(Container.MAGIC.length);
            raw.writeInt(1);
        }
        RecordingFormatException refused = assertThrows(RecordingFormatException.class, () -> RecordingFile.open(file));
        assertTrue(refused.getMessage().contains("test version"), refused.getMessage());
    }

}
