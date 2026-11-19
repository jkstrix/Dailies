package comedymonkey.dailies.windows;

import comedymonkey.dailies.file.Assembler;
import comedymonkey.dailies.file.RecordingFile;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.file.RecordingWriter;
import comedymonkey.dailies.file.Records;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What compats store once in a recording (specification REC-28, PLAY-23): kept once however often it is stored,
 * carried whole through trimming and combining (FILE-9, FILE-10), and found by its handle from a snapshot far from where
 * it was stored, as a jump lands.
 */
class CompatBlobsTest {

    private static final int SEGMENT_TICKS = 100;
    private static final String COMPAT = "test_compat:blobs";
    private static final String CLAIM = "schematic";

    @TempDir
    Path folder;

    private static final Assembler.Progress QUIET = new Assembler.Progress() {
        @Override
        public void report(double fraction) {
        }

        @Override
        public boolean cancelled() {
            return false;
        }
    };

    private static byte[] blob(int seed) {
        byte[] bytes = new byte[50_000];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    private static String handle(byte[] data) {
        return HexFormat.of().formatHex(RecordingWriter.hashOf(data));
    }

    private static RecordingManifest manifest(String id) {
        RecordingManifest manifest = new RecordingManifest();
        manifest.id = id;
        manifest.name = id;
        manifest.gameVersion = "1.21.1";
        return manifest;
    }

    /** A compat record whose one state is a handle, as a compat's state names its stored bytes */
    private static byte[] naming(String handle) {
        return CompatRecord.write(1, List.of(CompatRecord.Op.state(CLAIM, handle.getBytes(StandardCharsets.UTF_8))));
    }

    /**
     * Three segments of 100 ticks; the blobs are stored (each twice) before the first, and every snapshot holds the
     * compat's state naming the first of them, as every snapshot holds the state of each part showing
     */
    private Path record(String name, byte[]... blobs) throws IOException {
        Path file = this.folder.resolve(name + RecordingFile.EXTENSION);
        RecordingWriter writer = RecordingWriter.create(file, manifest("id-" + name));
        for (byte[] blob : blobs) {
            writer.putCompatBlob(blob, RecordingWriter.hashOf(blob));
            writer.putCompatBlob(blob, RecordingWriter.hashOf(blob));
        }
        byte[] state = naming(handle(blobs[0]));
        Records.Writer part = new Records.Writer();
        for (int segment = 0; segment < 3; segment++) {
            int start = segment * SEGMENT_TICKS;
            writer.beginSegment(start);
            part.number(Records.SNAPSHOT_BEGIN, start);
            if (segment == 0) {
                part.record(Records.DISCONTINUITY);
            }
            part.named(Records.EXTENSION, COMPAT, state, 0, state.length);
            part.record(Records.SNAPSHOT_END);
            for (int tick = start + 1; tick <= start + SEGMENT_TICKS; tick++) {
                part.number(Records.TICK, 1);
            }
            byte[] bytes = part.take();
            writer.appendToSegment(bytes, 0, bytes.length, start + SEGMENT_TICKS);
        }
        writer.finish();
        return file;
    }

    /** What playback holds for the compat once it has read the first snapshot of a recording, as a jump to its start does */
    private static CompatHeld heldAtStart(RecordingFile recording) throws IOException {
        CompatHeld held = new CompatHeld();
        RecordingManifest.Segment first = recording.segments().get(0);
        try (Records.Reader reader = new Records.Reader(recording.openSegment(first.number))) {
            while (reader.next() && reader.kind() != Records.SNAPSHOT_END) {
                if (reader.kind() == Records.EXTENSION && reader.name().equals(COMPAT)) {
                    held.play(reader.payload(), reader.bodyStart(), reader.length() - reader.bodyStart());
                }
            }
        }
        return held;
    }

    @Test
    void theSameBytesStoredAgainAreKeptOnceAndListedOnce() throws IOException {
        byte[] one = blob(1);
        try (RecordingFile recording = RecordingFile.open(this.record("once", one))) {
            assertEquals(List.of(handle(one)), recording.manifest().compatBlobs);
            assertEquals(1, recording.sharedCount());
            assertArrayEquals(one, CompatBlobs.find(recording, handle(one)));
        }
    }

    @Test
    void aTrimFromALaterSegmentHoldsTheBlobAndItsSnapshotFindsIt() throws IOException {
        byte[] one = blob(2);
        Path target = this.folder.resolve("trimmed" + RecordingFile.EXTENSION);
        try (RecordingFile source = RecordingFile.open(this.record("source", one))) {
            // Ticks 230 to 280 start inside the last segment: playback describes the compat as it holds it there
            Records.Writer described = new Records.Writer();
            byte[] state = naming(handle(one));
            described.named(Records.EXTENSION, COMPAT, state, 0, state.length);
            Assembler.write(target, manifest("trimmed"), List.of(
                new Assembler.Piece(source, 230, 280, new Assembler.Snapshot(described.take(), Map.of()))), null, null, QUIET);
        }
        try (RecordingFile trimmed = RecordingFile.open(target)) {
            assertEquals(List.of(handle(one)), trimmed.manifest().compatBlobs);
            CompatHeld held = heldAtStart(trimmed);
            CompatHeld.Held state = held.held(CLAIM);
            assertNotNull(state, "the snapshot holds the compat's state");
            String named = new String(state.state(), StandardCharsets.UTF_8);
            assertArrayEquals(one, CompatBlobs.find(trimmed, named));
        }
    }

    @Test
    void aCombinedRecordingHoldsEveryBlobOfEachSourceOnce() throws IOException {
        byte[] one = blob(3);
        byte[] two = blob(4);
        Path target = this.folder.resolve("combined" + RecordingFile.EXTENSION);
        try (RecordingFile first = RecordingFile.open(this.record("first", one));
             RecordingFile second = RecordingFile.open(this.record("second", one, two))) {
            Assembler.write(target, manifest("combined"), List.of(
                new Assembler.Piece(first, 0, first.totalTicks(), null),
                new Assembler.Piece(second, 100, 200, null)), null, null, QUIET);
        }
        try (RecordingFile combined = RecordingFile.open(target)) {
            assertEquals(List.of(handle(one), handle(two)), combined.manifest().compatBlobs);
            assertEquals(2, combined.sharedCount());
            assertArrayEquals(one, CompatBlobs.find(combined, handle(one)));
            assertArrayEquals(two, CompatBlobs.find(combined, handle(two)));
        }
    }

    @Test
    void onlyACompatsOwnBlobsAreFound() throws IOException {
        Path file = this.folder.resolve("other" + RecordingFile.EXTENSION);
        byte[] other = blob(5);
        RecordingWriter writer = RecordingWriter.create(file, manifest("id-other"));
        String chunk = writer.putShared(other);
        writer.finish();
        try (RecordingFile recording = RecordingFile.open(file)) {
            assertTrue(recording.hasShared(chunk));
            assertNull(CompatBlobs.find(recording, chunk), "shared data that is not a compat's is not handed out");
        }
        // A description from elsewhere naming something that is not a hash keeps only the hashes
        RecordingManifest named = manifest("named");
        named.compatBlobs = new ArrayList<>(List.of(handle(other), "../../escape", "ABC"));
        assertEquals(List.of(handle(other)), RecordingManifest.fromBytes(named.toBytes()).compatBlobs);
    }

}
