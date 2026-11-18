package comedymonkey.dailies.file;

import comedymonkey.dailies.packs.PackLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Server resource packs through combining and trimming (specification REC-19 with FILE-9 and FILE-10): the new
 * recording says the right packs are in force at every tick, holds each pack once, and holds none that only a
 * stretch that was cut used.
 */
class AssemblerPacksTest {

    private static final int SEGMENT_TICKS = 100;
    private static final int POP = 150;
    private static final int PUSH = 250;

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

    private static byte[] packBytes(int seed) {
        byte[] bytes = new byte[700_000];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    private static PackLog.Pack push(String key) {
        return push(key, UUID.randomUUID());
    }

    private static PackLog.Pack push(String key, UUID id) {
        return new PackLog.Pack(id, "http://example.invalid/pack.zip", "", false, "", key);
    }

    /** The id a made-up recording's server pushed its pack under, the first time (0) and the second (1) */
    private static UUID pushId(String recording, int which) {
        return UUID.nameUUIDFromBytes((recording + which).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Three segments of 100 ticks. With a pack: it is in force from the first snapshot, popped at tick 150 and pushed
     * again, under another id, at tick 250. Without: every snapshot says that none is in force.
     */
    private Path record(String name, byte[] pack, String key) throws IOException {
        Path file = this.folder.resolve(name + RecordingFile.EXTENSION);
        RecordingManifest manifest = new RecordingManifest();
        manifest.id = "id-" + name;
        manifest.name = name;
        manifest.gameVersion = "1.21.1";
        RecordingWriter writer = RecordingWriter.create(file, manifest);
        if (pack != null) {
            Path packFile = this.folder.resolve(name + "-pack.zip");
            Files.write(packFile, pack);
            RecordingManifest.Pack stored = writer.putFile(packFile);
            stored.key = key;
            writer.manifest().packs.add(stored);
        }
        Records.Writer part = new Records.Writer();
        PackLog log = new PackLog();
        if (pack != null) {
            log.push(push(key, pushId(name, 0)), 0);
        }
        for (int segment = 0; segment < 3; segment++) {
            int start = segment * SEGMENT_TICKS;
            writer.beginSegment(start);
            part.number(Records.SNAPSHOT_BEGIN, start);
            if (segment == 0) {
                part.record(Records.DISCONTINUITY);
            }
            byte[] state = PackLog.state(log.inForce());
            part.named(Records.EXTENSION, PackLog.ID, state, 0, state.length);
            part.record(Records.SNAPSHOT_END);
            for (int tick = start + 1; tick <= start + SEGMENT_TICKS; tick++) {
                part.number(Records.TICK, 1);
                if (pack != null && tick == POP) {
                    byte[] popped = PackLog.popped(log.inForce().get(0).id());
                    log.apply(popped, 0, popped.length);
                    part.named(Records.EXTENSION, PackLog.ID, popped, 0, popped.length);
                } else if (pack != null && tick == PUSH) {
                    byte[] pushed = PackLog.pushed(push(key, pushId(name, 1)), 0);
                    log.apply(pushed, 0, pushed.length);
                    part.named(Records.EXTENSION, PackLog.ID, pushed, 0, pushed.length);
                }
            }
            byte[] bytes = part.take();
            writer.appendToSegment(bytes, 0, bytes.length, start + SEGMENT_TICKS);
        }
        writer.finish();
        return file;
    }

    /** The content keys in force at every tick, read the way playback reads them: a segment that starts afresh forgets what came before */
    private static List<List<String>> inForce(RecordingFile recording) throws IOException {
        List<List<String>> atTick = new ArrayList<>();
        for (int i = 0; i <= recording.totalTicks(); i++) {
            atTick.add(null);
        }
        PackLog log = new PackLog();
        for (RecordingManifest.Segment segment : recording.segments()) {
            int position = segment.startTick;
            try (Records.Reader reader = new Records.Reader(recording.openSegment(segment.number))) {
                while (reader.next()) {
                    if (reader.kind() == Records.DISCONTINUITY) {
                        log.clear();
                    } else if (reader.kind() == Records.TICK) {
                        atTick.set(position, log.contents());
                        position += reader.number();
                    } else if (reader.kind() == Records.EXTENSION && reader.name().equals(PackLog.ID)) {
                        log.apply(reader.payload(), reader.bodyStart(), reader.length() - reader.bodyStart());
                    }
                }
            }
            atTick.set(position, log.contents());
        }
        return atTick;
    }

    private static RecordingManifest newManifest(String id) {
        RecordingManifest manifest = new RecordingManifest();
        manifest.id = id;
        manifest.name = id;
        manifest.gameVersion = "1.21.1";
        return manifest;
    }

    private byte[] extracted(RecordingFile recording, RecordingManifest.Pack pack) throws IOException {
        Path out = this.folder.resolve("out-" + UUID.randomUUID() + ".zip");
        recording.extractFile(pack, out);
        return Files.readAllBytes(out);
    }

    /** What playback hands over for a cut inside a segment: here only the packs in force, as the playback half of the packs writes them */
    private static Assembler.Snapshot snapshot(List<PackLog.Pack> inForce) {
        Records.Writer described = new Records.Writer();
        byte[] state = PackLog.state(inForce);
        described.named(Records.EXTENSION, PackLog.ID, state, 0, state.length);
        return new Assembler.Snapshot(described.take(), Map.of());
    }

    @Test
    void aTrimAcrossThePopAndThePushSaysTheRightPacksAtEveryTickAndHoldsThePackOnce() throws IOException {
        byte[] pack = packBytes(1);
        Path target = this.folder.resolve("trimmed" + RecordingFile.EXTENSION);
        try (RecordingFile source = RecordingFile.open(this.record("packed", pack, "abc"))) {
            assertEquals(List.of("abc"), inForce(source).get(POP - 1));
            assertEquals(List.of(), inForce(source).get(POP));
            assertEquals(List.of("abc"), inForce(source).get(PUSH));
            // Ticks 130 to 170 (across the pop) and 240 to 280 (across the second push): both start inside a segment
            Assembler.write(target, newManifest("trimmed"), List.of(
                new Assembler.Piece(source, 130, 170, snapshot(List.of(push("abc", pushId("packed", 0))))),
                new Assembler.Piece(source, 240, 280, snapshot(List.of()))), null, null, QUIET);
            try (RecordingFile trimmed = RecordingFile.open(target)) {
                List<List<String>> inForce = inForce(trimmed);
                List<List<String>> original = inForce(source);
                for (int here = 0; here <= 80; here++) {
                    int there = here < 40 ? 130 + here : 240 + (here - 40);
                    assertEquals(original.get(there), inForce.get(here), "tick " + here + " of the trim, tick " + there + " of the source");
                }
                assertEquals(List.of("abc"), inForce.get(0));
                assertEquals(List.of(), inForce.get(20));
                assertEquals(List.of(), inForce.get(40));
                assertEquals(List.of("abc"), inForce.get(50));
                assertEquals(1, trimmed.manifest().packs.size());
                RecordingManifest.Pack kept = trimmed.manifest().packs.get(0);
                assertEquals("abc", kept.key);
                assertEquals(source.manifest().packs.get(0).sha256, kept.sha256);
                assertArrayEquals(pack, this.extracted(trimmed, kept));
                // Its pieces and nothing else: stored once however many stretches use it
                assertEquals(kept.pieces.size(), trimmed.sharedCount());
            }
        }
    }

    @Test
    void aPackThatOnlyACutStretchUsedIsLeftBehind() throws IOException {
        Path target = this.folder.resolve("without" + RecordingFile.EXTENSION);
        try (RecordingFile source = RecordingFile.open(this.record("packed", packBytes(1), "abc"))) {
            // Only the stretch between the pop and the second push, cut on a segment boundary at one end and inside one at the other
            Assembler.write(target, newManifest("without"), List.of(new Assembler.Piece(source, 160, 240, snapshot(List.of()))), null, null, QUIET);
            try (RecordingFile trimmed = RecordingFile.open(target)) {
                assertTrue(trimmed.manifest().packs.isEmpty());
                assertEquals(0, trimmed.sharedCount());
                assertTrue(inForce(trimmed).stream().allMatch(List::isEmpty));
            }
            // A whole segment copied as it is still brings the pack its snapshot names
            Path second = this.folder.resolve("first-segment" + RecordingFile.EXTENSION);
            Assembler.write(second, newManifest("first-segment"), List.of(new Assembler.Piece(source, 0, 100, null)), null, null, QUIET);
            try (RecordingFile trimmed = RecordingFile.open(second)) {
                assertEquals(1, trimmed.manifest().packs.size());
                assertEquals(List.of("abc"), inForce(trimmed).get(50));
            }
        }
    }

    @Test
    void combinedSourcesSwitchPacksAtTheBoundaryAndAPackSharedByBothIsStoredOnce() throws IOException {
        byte[] pack = packBytes(1);
        Path target = this.folder.resolve("joined" + RecordingFile.EXTENSION);
        try (RecordingFile packed = RecordingFile.open(this.record("packed", pack, "abc")); RecordingFile bare = RecordingFile.open(this.record("bare", null, null));
             RecordingFile again = RecordingFile.open(this.record("again", pack, "abc"))) {
            Assembler.write(target, newManifest("joined"), List.of(new Assembler.Piece(packed, 0, 300, null), new Assembler.Piece(bare, 0, 300, null),
                new Assembler.Piece(again, 0, 300, null)), null, null, QUIET);
            try (RecordingFile joined = RecordingFile.open(target)) {
                List<List<String>> inForce = inForce(joined);
                assertEquals(List.of("abc"), inForce.get(299));
                // The boundary: the second source's opening snapshot says none, on the tick the first ends at
                assertEquals(List.of(), inForce.get(301));
                assertEquals(List.of(), inForce.get(450));
                assertEquals(List.of("abc"), inForce.get(601));
                assertEquals(List.of(), inForce.get(600 + POP));
                assertEquals(List.of("abc"), inForce.get(600 + PUSH));
                assertEquals(1, joined.manifest().packs.size());
                assertEquals(joined.manifest().packs.get(0).pieces.size(), joined.sharedCount());
                assertArrayEquals(pack, this.extracted(joined, joined.manifest().packs.get(0)));
            }
        }
    }

    @Test
    void twoPacksUnderOneKeyAreToldApartAndEachSourceStillGetsItsOwn() throws IOException {
        byte[] one = packBytes(1);
        byte[] two = packBytes(2);
        Path target = this.folder.resolve("clash" + RecordingFile.EXTENSION);
        try (RecordingFile first = RecordingFile.open(this.record("first", one, "local-0011")); RecordingFile second = RecordingFile.open(this.record("second", two, "local-0011"))) {
            assertNotEquals(first.manifest().packs.get(0).sha256, second.manifest().packs.get(0).sha256);
            Assembler.write(target, newManifest("clash"), List.of(new Assembler.Piece(first, 0, 300, null),
                new Assembler.Piece(second, 0, 300, null, Map.of("local-0011", "local-0011-other"))), null, null, QUIET);
            try (RecordingFile joined = RecordingFile.open(target)) {
                List<List<String>> inForce = inForce(joined);
                assertEquals(List.of("local-0011"), inForce.get(100));
                assertEquals(List.of("local-0011-other"), inForce.get(301));
                assertEquals(List.of(), inForce.get(300 + POP));
                assertEquals(List.of("local-0011-other"), inForce.get(300 + PUSH));
                assertEquals(List.of("local-0011-other"), inForce.get(600));
                assertEquals(2, joined.manifest().packs.size());
                for (RecordingManifest.Pack pack : joined.manifest().packs) {
                    assertArrayEquals(pack.key.equals("local-0011") ? one : two, this.extracted(joined, pack));
                }
            }
        }
    }

    @Test
    void aRecordIsWrittenAgainOnlyWhereAKeyChanges() {
        PackLog.Pack pack = push("abc");
        byte[] pushed = PackLog.pushed(pack, 2);
        assertEquals(List.of("abc"), PackLog.keys(pushed, 0, pushed.length));
        assertEquals(null, PackLog.rekey(pushed, 0, pushed.length, key -> key));
        byte[] renamed = PackLog.rekey(pushed, 0, pushed.length, key -> key + "-2");
        PackLog log = new PackLog();
        log.apply(renamed, 0, renamed.length);
        assertEquals(List.of("abc-2"), log.contents());
        assertEquals(pack.id(), log.inForce().get(0).id());
        byte[] state = PackLog.state(List.of(pack, push(""), push("def")));
        assertEquals(List.of("abc", "def"), PackLog.keys(state, 0, state.length));
        byte[] popped = PackLog.popped(pack.id());
        assertTrue(PackLog.keys(popped, 0, popped.length).isEmpty());
        assertEquals(null, PackLog.rekey(popped, 0, popped.length, key -> "x"));
    }

}
