package comedymonkey.dailies.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The recording file against specification chapter III (FILE-1 to FILE-8) and the crash rule of REC-6 */
class RecordingFileTest {

    @TempDir
    Path folder;

    private static RecordingManifest manifest() {
        RecordingManifest manifest = new RecordingManifest();
        manifest.id = "0b1e6f0e-5a52-4c61-9a37-0c1d2e3f4a5b";
        manifest.name = "First flight";
        manifest.created = 1_800_000_000_000L;
        manifest.gameVersion = "1.21.1";
        manifest.recorderName = "Recorder";
        manifest.worldName = "Test World";
        manifest.mods.add(new RecordingManifest.ModEntry("create", "6.0.10"));
        return manifest;
    }

    /** Data that does not compress away to nothing, different for every seed */
    private static byte[] data(int seed, int length) {
        byte[] bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
        for (int i = 0; i < length; i += 3) {
            bytes[i] = (byte) seed;
        }
        return bytes;
    }

    /** Three segments of 100 ticks, each written as two parts */
    private Path writeThreeSegments() throws IOException {
        Path file = this.folder.resolve("flight" + RecordingFile.EXTENSION);
        RecordingWriter writer = RecordingWriter.create(file, manifest());
        for (int segment = 0; segment < 3; segment++) {
            writer.beginSegment(segment * 100);
            byte[] first = data(segment * 2, 50_000);
            byte[] second = data(segment * 2 + 1, 20_000);
            writer.appendToSegment(first, 0, first.length, segment * 100 + 50);
            writer.appendToSegment(second, 0, second.length, segment * 100 + 100);
        }
        RecordingManifest.Marker marker = new RecordingManifest.Marker();
        marker.tick = 120;
        marker.colour = 0xFF5555;
        marker.description = "Take-off";
        writer.manifest().markers.add(marker);
        writer.finish();
        return file;
    }

    private static byte[] segmentData(int segment) {
        byte[] first = data(segment * 2, 50_000);
        byte[] second = data(segment * 2 + 1, 20_000);
        byte[] all = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }

    private static byte[] readSegment(RecordingFile recording, int number) throws IOException {
        try (InputStream in = recording.openSegment(number)) {
            return in.readAllBytes();
        }
    }

    @Test
    void roundTrip() throws IOException {
        Path file = this.writeThreeSegments();
        assertFalse(RecordingFile.isUnfinished(file));
        try (RecordingFile recording = RecordingFile.open(file)) {
            RecordingManifest manifest = recording.manifest();
            assertEquals("0b1e6f0e-5a52-4c61-9a37-0c1d2e3f4a5b", manifest.id);
            assertEquals("First flight", manifest.name);
            assertEquals(300, manifest.totalTicks);
            assertTrue(manifest.finished);
            assertEquals(3, manifest.segments.size());
            assertEquals(100, manifest.segments.get(1).startTick);
            assertEquals(200, manifest.segments.get(1).endTick);
            assertEquals("Take-off", manifest.markers.get(0).description);
            assertEquals("create", manifest.mods.get(0).id);
            for (int segment = 0; segment < 3; segment++) {
                assertArrayEquals(segmentData(segment), readSegment(recording, segment));
            }
            assertEquals(0, recording.segmentAt(0));
            assertEquals(1, recording.segmentAt(100));
            assertEquals(2, recording.segmentAt(299));
            assertNull(recording.editState());
            assertNull(recording.preview());
        }
    }

    /** FILE-3: the id is in the file, so a renamed or moved file is still the same recording */
    @Test
    void renamedFileKeepsItsIdentity() throws IOException {
        Path file = this.writeThreeSegments();
        Path moved = Files.createDirectories(this.folder.resolve("sub")).resolve("another name.dailies");
        Files.move(file, moved);
        assertEquals("0b1e6f0e-5a52-4c61-9a37-0c1d2e3f4a5b", RecordingFile.readManifest(moved).id);
    }

    /** FILE-4: damage inside one segment makes that segment unreadable and no other */
    @Test
    void damagedSegmentCostsOnlyItself() throws IOException {
        Path file = this.writeThreeSegments();
        long middleOfSecondSegment;
        try (RecordingFile recording = RecordingFile.open(file)) {
            // Segment 1 lies between the ends of segments 0 and 2; aim for the middle of the file's recorded data
            middleOfSecondSegment = Files.size(file) / 2;
            assertArrayEquals(segmentData(1), readSegment(recording, 1));
        }
        try (RandomAccessFile raw = new RandomAccessFile(file.toFile(), "rw")) {
            raw.seek(middleOfSecondSegment);
            int was = raw.read();
            raw.seek(middleOfSecondSegment);
            raw.write(was ^ 0x5A);
        }
        try (RecordingFile recording = RecordingFile.open(file)) {
            assertEquals(300, recording.totalTicks());
            assertArrayEquals(segmentData(0), readSegment(recording, 0));
            assertArrayEquals(segmentData(2), readSegment(recording, 2));
            RecordingFormatException damaged = assertThrows(RecordingFormatException.class, () -> readSegment(recording, 1));
            assertEquals(RecordingFormatException.Reason.DAMAGED, damaged.reason());
        }
    }

    /** FILE-5: the same chunk in every snapshot is stored once */
    @Test
    void sharedDataIsStoredOnce() throws IOException {
        Path file = this.folder.resolve("shared.dailies");
        RecordingWriter writer = RecordingWriter.create(file, manifest());
        byte[] chunk = data(7, 200_000);
        byte[] other = data(8, 1_000);
        String name = writer.putShared(chunk);
        long afterFirst = writer.bytesWritten();
        for (int snapshot = 0; snapshot < 20; snapshot++) {
            assertEquals(name, writer.putShared(chunk.clone()));
        }
        assertEquals(afterFirst, writer.bytesWritten());
        String otherName = writer.putShared(other);
        writer.finish();
        try (RecordingFile recording = RecordingFile.open(file)) {
            assertArrayEquals(chunk, recording.shared(name));
            assertArrayEquals(other, recording.shared(otherName));
            assertThrows(RecordingFormatException.class, () -> recording.shared("00".repeat(32)));
        }
    }

    /** FILE-7: a file from a newer version is refused with a message that says what to do */
    @Test
    void newerFormatIsRefused() throws IOException {
        Path file = this.writeThreeSegments();
        try (RandomAccessFile raw = new RandomAccessFile(file.toFile(), "rw")) {
            raw.seek(Container.MAGIC.length);
            raw.writeInt(Container.VERSION + 1);
        }
        RecordingFormatException refused = assertThrows(RecordingFormatException.class, () -> RecordingFile.open(file));
        assertEquals(RecordingFormatException.Reason.NEWER_FORMAT, refused.reason());
        assertTrue(refused.getMessage().contains("Update Dailies"), refused.getMessage());
    }

    @Test
    void otherFilesAreRefused() throws IOException {
        Path file = this.folder.resolve("notes.dailies");
        Files.writeString(file, "This is a text file that happens to have the right extension");
        RecordingFormatException refused = assertThrows(RecordingFormatException.class, () -> RecordingFile.open(file));
        assertEquals(RecordingFormatException.Reason.NOT_A_RECORDING, refused.reason());
    }

    /** FILE-8: saving the editor state adds a little to the end and leaves every recorded byte where it was */
    @Test
    void editorStateSavesWithoutRewriting() throws IOException {
        Path file = this.writeThreeSegments();
        byte[] before = Files.readAllBytes(file);
        byte[] state = "{\"scenes\":[{\"name\":\"Scene 1\"}]}".getBytes(StandardCharsets.UTF_8);
        try (RecordingFile recording = RecordingFile.openForEditing(file)) {
            recording.saveEditState("{}".getBytes(StandardCharsets.UTF_8));
            recording.saveEditState(state);
            recording.rename("Second flight");
            recording.savePreview(new byte[]{1, 2, 3});
            assertTrue(recording.deadBytes() > 0);
        }
        byte[] after = Files.readAllBytes(file);
        assertArrayEquals(before, Arrays.copyOf(after, before.length));
        assertTrue(after.length - before.length < 2_000, "saving grew the file by " + (after.length - before.length) + " bytes");

        try (RecordingFile recording = RecordingFile.openForEditing(file)) {
            assertArrayEquals(state, recording.editState());
            assertEquals("Second flight", recording.manifest().name);
            assertArrayEquals(new byte[]{1, 2, 3}, recording.preview());
            assertArrayEquals(segmentData(2), readSegment(recording, 2));

            recording.compact();
            assertEquals(0, recording.deadBytes());
            assertArrayEquals(state, recording.editState());
            assertEquals("Second flight", recording.manifest().name);
            assertArrayEquals(segmentData(1), readSegment(recording, 1));
        }
        assertTrue(Files.size(file) < after.length);
        try (RecordingFile recording = RecordingFile.open(file)) {
            assertThrows(IllegalStateException.class, () -> recording.saveEditState(state));
        }
    }

    /** REC-6: a recording cut off mid-write keeps everything that was written whole */
    @Test
    void crashedRecordingIsRecovered() throws IOException {
        Path file = this.folder.resolve("crashed.dailies");
        RecordingWriter writer = RecordingWriter.create(file, manifest());
        writer.beginSegment(0);
        byte[] first = data(0, 50_000);
        byte[] second = data(1, 20_000);
        writer.appendToSegment(first, 0, first.length, 50);
        writer.appendToSegment(second, 0, second.length, 100);
        writer.beginSegment(100);
        byte[] third = data(2, 30_000);
        writer.appendToSegment(third, 0, third.length, 140);
        long whole = writer.bytesWritten();
        byte[] torn = data(3, 30_000);
        writer.appendToSegment(torn, 0, torn.length, 180);
        writer.close();
        // The game died half way through writing the last part
        try (RandomAccessFile raw = new RandomAccessFile(file.toFile(), "rw")) {
            raw.setLength(whole + (Files.size(file) - whole) / 2);
        }

        assertTrue(RecordingFile.isUnfinished(file));
        assertFalse(RecordingFile.readManifest(file).finished);

        RecordingManifest recovered = RecordingFile.recover(file);
        assertTrue(recovered.finished);
        assertTrue(recovered.recovered);
        assertEquals(140, recovered.totalTicks);
        assertEquals(2, recovered.segments.size());

        assertFalse(RecordingFile.isUnfinished(file));
        try (RecordingFile recording = RecordingFile.open(file)) {
            assertEquals(140, recording.totalTicks());
            byte[] segment0 = Arrays.copyOf(first, first.length + second.length);
            System.arraycopy(second, 0, segment0, first.length, second.length);
            assertArrayEquals(segment0, readSegment(recording, 0));
            assertArrayEquals(third, readSegment(recording, 1));
        }
    }

    /** A crash right after the file was created leaves an empty recording, not an unreadable file */
    @Test
    void emptyCrashedRecordingStillOpens() throws IOException {
        Path file = this.folder.resolve("empty.dailies");
        RecordingWriter.create(file, manifest()).close();
        assertTrue(RecordingFile.isUnfinished(file));
        assertEquals(0, RecordingFile.recover(file).totalTicks);
    }

    @Test
    void discardRemovesTheFile() throws IOException {
        Path file = this.folder.resolve("cancelled.dailies");
        RecordingWriter writer = RecordingWriter.create(file, manifest());
        writer.beginSegment(0);
        writer.appendToSegment(new byte[100], 0, 100, 10);
        writer.discard();
        assertFalse(Files.exists(file));
    }

    /** REC-19: a resource pack from a server is kept whole in the recording, once, and comes back out byte for byte */
    @Test
    void aStoredFileIsKeptOnceAndComesBackWhole() throws IOException {
        byte[] pack = data(77, RecordingWriter.PIECE_BYTES * 2 + 12_345);
        Path source = this.folder.resolve("pack.zip");
        Files.write(source, pack);
        Path file = this.folder.resolve("packs" + RecordingFile.EXTENSION);
        RecordingWriter writer = RecordingWriter.create(file, manifest());
        writer.beginSegment(0);
        writer.appendToSegment(new byte[]{1, 2, 3}, 0, 3, 20);
        RecordingManifest.Pack stored = writer.putFile(source);
        stored.key = "first";
        writer.manifest().packs.add(stored);
        long sizeWithOne = writer.bytesWritten();
        // Pushed again: nothing more is written
        RecordingManifest.Pack again = writer.putFile(source);
        assertEquals(sizeWithOne, writer.bytesWritten());
        assertEquals(stored.sha256, again.sha256);
        assertEquals(stored.pieces, again.pieces);
        assertEquals(3, stored.pieces.size());
        assertEquals(pack.length, stored.size);
        writer.finish();

        try (RecordingFile recording = RecordingFile.open(file)) {
            RecordingManifest read = recording.manifest();
            assertEquals(1, read.packs.size());
            assertEquals("first", read.packs.get(0).key);
            assertEquals(3, recording.sharedCount());
            Path out = this.folder.resolve("cache").resolve("out.zip");
            recording.extractFile(read.packs.get(0), out);
            assertArrayEquals(pack, Files.readAllBytes(out));
            // What is written out is checked against what was put in
            RecordingManifest.Pack wrong = read.packs.get(0);
            wrong.sha256 = "00" + wrong.sha256.substring(2);
            Path refused = this.folder.resolve("cache").resolve("refused.zip");
            assertThrows(RecordingFormatException.class, () -> recording.extractFile(wrong, refused));
            assertFalse(Files.exists(refused));
            try (java.util.stream.Stream<Path> left = Files.list(this.folder.resolve("cache"))) {
                assertEquals(1, left.count());
            }
        }
    }

    /** A recording from before packs were kept has none, and still opens */
    @Test
    void aManifestWithoutPacksReadsAsHavingNone() throws IOException {
        RecordingManifest old = RecordingManifest.fromBytes("{\"id\":\"abc\",\"name\":\"Old\"}".getBytes(StandardCharsets.UTF_8));
        assertTrue(old.packs.isEmpty());
    }

}
