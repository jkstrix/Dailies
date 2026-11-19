package comedymonkey.dailies.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Combining and trimming at the level of the file (specification FILE-9, FILE-10) and the time index (FILE-16),
 * on recordings made up for the purpose: every tick carries one packet that says which source and tick it is, so
 * what a new recording holds at each of its ticks can be read straight back.
 */
class AssemblerTest {

    private static final int SEGMENT_TICKS = 100;
    private static final long REAL_START = 1_800_000_000_000L;
    private static final long GAME_START = 5_000L;

    @TempDir
    Path folder;

    /** What the made-up recordings are packed with; null for the mod's own dictionaries, as a recording made now is */
    private Packing.Dictionaries packWith = null;

    private static final Assembler.Progress QUIET = new Assembler.Progress() {
        @Override
        public void report(double fraction) {
        }

        @Override
        public boolean cancelled() {
            return false;
        }
    };

    /** The real time of a tick of a made-up recording: fifty milliseconds a tick, and a ten second pause before each discontinuity */
    private static long realAt(int source, int tick, int pauseEvery) {
        return REAL_START + source * 10_000_000L + tick * 50L + (pauseEvery > 0 ? (tick / pauseEvery) * 10_000L : 0);
    }

    private static byte[] packet(int source, int tick) {
        return ("s" + source + "t" + tick).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * A recording of whole segments of 100 ticks. Its snapshots name a world and one chunk that never changes, and one
     * chunk of the segment's own; the snapshot also holds a packet that says which tick it describes.
     *
     * @param afreshEvery every this many segments one starts afresh (after a pause); 0 for only the first
     * @param timed       whether it holds a time index
     */
    private Path record(String name, int source, int segments, int afreshEvery, boolean timed) throws IOException {
        Path file = this.folder.resolve(name + RecordingFile.EXTENSION);
        RecordingManifest manifest = new RecordingManifest();
        manifest.id = "id-" + name;
        manifest.name = name;
        manifest.created = REAL_START + source;
        manifest.gameVersion = "1.21.1";
        manifest.worldName = "World " + source;
        RecordingWriter writer = this.packWith == null ? RecordingWriter.create(file, manifest) : RecordingWriter.create(file, manifest, this.packWith);
        Records.Writer part = new Records.Writer();
        TimeIndex.Encoder times = new TimeIndex.Encoder();
        int pauseEvery = afreshEvery * SEGMENT_TICKS;
        byte[] world = HexFormat.of().parseHex(writer.putShared(("world " + source).getBytes(StandardCharsets.UTF_8)));
        byte[] still = stored(writer, PacketPieces.whole(("still chunk " + source).getBytes(StandardCharsets.UTF_8)));
        for (int segment = 0; segment < segments; segment++) {
            int start = segment * SEGMENT_TICKS;
            writer.beginSegment(start);
            part.number(Records.SNAPSHOT_BEGIN, start);
            if (segment == 0 || afreshEvery > 0 && segment % afreshEvery == 0) {
                part.record(Records.DISCONTINUITY);
            }
            part.record(Records.WORLD, world);
            part.record(Records.SHARED_PACKET, still);
            part.record(Records.SHARED_PACKET, stored(writer, PacketPieces.whole(("chunk " + source + " " + segment).getBytes(StandardCharsets.UTF_8))));
            part.record(Records.PACKET, ("snapshot s" + source + "t" + start).getBytes(StandardCharsets.UTF_8));
            part.record(Records.SNAPSHOT_END);
            if (timed) {
                times.reset();
                part.record(Records.TIME, times.next(realAt(source, start, pauseEvery), GAME_START + start));
                writer.manifest().times.add(new RecordingManifest.SegmentTime(segment, realAt(source, start, pauseEvery), GAME_START + start));
            }
            for (int tick = start + 1; tick <= start + SEGMENT_TICKS; tick++) {
                part.number(Records.TICK, 1);
                if (timed) {
                    // The tick a pause falls on is reached before the pause; the next segment starts on it after
                    long real = pauseEvery > 0 && tick % pauseEvery == 0 ? realAt(source, tick - 1, pauseEvery) + 50 : realAt(source, tick, pauseEvery);
                    part.record(Records.TIME, times.next(real, GAME_START + tick));
                }
                // What happened on the way to this tick
                part.record(Records.PACKET, packet(source, tick));
                if (tick == start + 50) {
                    byte[] bytes = part.take();
                    writer.appendToSegment(bytes, 0, bytes.length, tick);
                }
            }
            byte[] bytes = part.take();
            writer.appendToSegment(bytes, 0, bytes.length, start + SEGMENT_TICKS);
        }
        writer.setPreview(("preview " + name).getBytes(StandardCharsets.UTF_8));
        writer.manifest().realStart = timed ? realAt(source, 0, pauseEvery) : 0;
        writer.manifest().realEnd = timed ? realAt(source, segments * SEGMENT_TICKS, pauseEvery) : 0;
        writer.finish();
        return file;
    }

    /** Stores a packet's pieces as the recorder's writer does, and gives the name to record */
    private static byte[] stored(RecordingWriter writer, PacketPieces.Split split) throws IOException {
        for (PacketPieces.Blob blob : split.blobs()) {
            writer.putShared(blob.bytes(), blob.from(), blob.length(), blob.name());
        }
        return split.name();
    }

    /** What a recording would show at each tick: the last packet applied on reaching it, or the snapshot's own when it starts there */
    private record Played(List<String> atTick, List<Integer> afreshAt, List<String> worlds, List<String> chunksAtStart, List<long[]> times) {
    }

    /** Plays a recording the way playback does, as far as the records go: segment by segment, tick by tick */
    private static Played play(RecordingFile recording) throws IOException {
        List<String> atTick = new ArrayList<>();
        List<Integer> afresh = new ArrayList<>();
        List<String> worlds = new ArrayList<>();
        List<String> chunks = new ArrayList<>();
        List<long[]> times = new ArrayList<>();
        for (int i = 0; i <= recording.totalTicks(); i++) {
            atTick.add(null);
            times.add(null);
        }
        int expectedStart = 0;
        for (RecordingManifest.Segment segment : recording.segments()) {
            assertEquals(expectedStart, segment.startTick, "segments must follow on from one another");
            expectedStart = segment.endTick;
            int position = segment.startTick;
            boolean inSnapshot = false;
            boolean first = true;
            TimeIndex.Decoder decoder = new TimeIndex.Decoder();
            try (Records.Reader reader = new Records.Reader(recording.openSegment(segment.number))) {
                while (reader.next()) {
                    int kind = reader.kind();
                    if (first) {
                        assertEquals(Records.SNAPSHOT_BEGIN, kind, "a segment starts with its snapshot");
                        assertEquals(segment.startTick, reader.number(), "a snapshot says which tick it is of");
                        first = false;
                        inSnapshot = true;
                        continue;
                    }
                    switch (kind) {
                        case Records.DISCONTINUITY -> afresh.add(segment.startTick);
                        case Records.SNAPSHOT_END -> inSnapshot = false;
                        case Records.TICK -> position += reader.number();
                        case Records.TIME -> {
                            decoder.read(reader.payload(), reader.length());
                            times.set(position, new long[]{decoder.realTime(), decoder.gameTime()});
                        }
                        case Records.WORLD -> worlds.add(new String(recording.shared(HexFormat.of().formatHex(reader.payload(), 0, reader.length())), StandardCharsets.UTF_8));
                        case Records.SHARED_PACKET -> {
                            if (inSnapshot) {
                                chunks.add(segment.startTick + ":" + new String(recording.packet(HexFormat.of().formatHex(reader.payload(), 0, reader.length())), StandardCharsets.UTF_8));
                            }
                        }
                        case Records.PACKET -> atTick.set(position, new String(reader.payload(), 0, reader.length(), StandardCharsets.UTF_8));
                        default -> {
                        }
                    }
                }
            }
            assertEquals(segment.endTick, position, "a segment's records reach the tick its parts say");
        }
        assertEquals(recording.totalTicks(), expectedStart);
        return new Played(atTick, afresh, worlds, chunks, times);
    }

    private static RecordingManifest newManifest(String id) {
        RecordingManifest manifest = new RecordingManifest();
        manifest.id = id;
        manifest.name = id;
        manifest.gameVersion = "1.21.1";
        return manifest;
    }

    // --- The time index (FILE-16) ---

    @Test
    void everyTickHasItsRealTimeAndGameTime() throws IOException {
        Path file = this.record("timed", 0, 3, 2, true);
        try (RecordingFile recording = RecordingFile.open(file)) {
            assertTrue(recording.hasTimes());
            for (int tick : new int[]{0, 1, 99, 100, 101, 199, 200, 250, 300}) {
                TimeIndex.Sample sample = recording.timeAt(tick);
                assertNotNull(sample, "tick " + tick);
                assertEquals(realAt(0, tick, 200), sample.realTime(), "real time at tick " + tick);
                assertEquals(GAME_START + tick, sample.gameTime(), "game time at tick " + tick);
            }
        }
    }

    @Test
    void everyTickOfSeveralSegmentsReadsBackItsOwnTimesAndABoundaryTickBoth() throws IOException {
        // Five segments of 100 ticks; before ticks 200 and 400 the recording stood still for ten seconds (a pause)
        Path file = this.record("many", 0, 5, 2, true);
        try (RecordingFile recording = RecordingFile.open(file)) {
            for (int tick = 0; tick <= 500; tick++) {
                List<TimeIndex.Sample> times = recording.timesAt(tick);
                boolean paused = tick == 200 || tick == 400;
                assertEquals(paused ? 2 : 1, times.size(), "tick " + tick + " has " + times.size() + " times");
                if (paused) {
                    // Reached before the pause, gone on from after it
                    assertEquals(realAt(0, tick - 1, 200) + 50, times.get(0).realTime(), "tick " + tick + " as it was reached");
                    assertEquals(realAt(0, tick, 200), times.get(1).realTime(), "tick " + tick + " as it went on");
                    assertEquals(10_000, times.get(1).realTime() - times.get(0).realTime());
                    assertEquals(times.get(1), recording.timeAt(tick));
                } else {
                    assertEquals(realAt(0, tick, 200), times.get(0).realTime(), "tick " + tick);
                    assertEquals(times.get(0), recording.timeAt(tick));
                }
                assertEquals(GAME_START + tick, times.get(0).gameTime(), "game time at tick " + tick);
            }
            // A boundary that is not a pause (tick 100, tick 300) has one time: the two segments agree
            assertEquals(1, recording.timesAt(100).size());
            assertEquals(1, recording.timesAt(300).size());
            // A moment in the middle of the pause is nearest the tick the recording stood on, from either side
            TimeIndex.Nearest inPause = recording.tickNearest(realAt(0, 200, 200) - 4_000);
            assertEquals(200, inPause.tick());
            assertEquals(4_000, inPause.errorMillis());
            assertTrue(recording.timesAt(501).isEmpty());
        }
    }

    @Test
    void theTickNearestARealTimeIsFoundAndSaysHowFarOffItIs() throws IOException {
        Path file = this.record("timed", 0, 4, 2, true);
        try (RecordingFile recording = RecordingFile.open(file)) {
            // Exactly on a tick, in the first segment and in a later one after the pause
            assertEquals(new TimeIndex.Nearest(37, realAt(0, 37, 200), GAME_START + 37, 0), recording.tickNearest(realAt(0, 37, 200)));
            assertEquals(new TimeIndex.Nearest(333, realAt(0, 333, 200), GAME_START + 333, 0), recording.tickNearest(realAt(0, 333, 200)));
            // Between two ticks: the closer one
            TimeIndex.Nearest between = recording.tickNearest(realAt(0, 150, 200) + 20);
            assertEquals(150, between.tick());
            assertEquals(20, between.errorMillis());
            // In the ten second pause before tick 200: the answer is a tick beside it, and seconds off
            TimeIndex.Nearest paused = recording.tickNearest(realAt(0, 199, 200) + 4_000);
            assertTrue(paused.tick() == 199 || paused.tick() == 200, "tick " + paused.tick());
            assertTrue(paused.errorMillis() >= 3_900, "error " + paused.errorMillis());
            // Long before and long after: the ends, far off
            assertEquals(0, recording.tickNearest(REAL_START - 60_000).tick());
            assertEquals(60_000, recording.tickNearest(REAL_START - 60_000).errorMillis());
            assertEquals(400, recording.tickNearest(realAt(0, 400, 200) + 60_000).tick());
        }
    }

    @Test
    void aRecordIsAFewBytesATick() throws RecordingFormatException {
        TimeIndex.Encoder encoder = new TimeIndex.Encoder();
        assertTrue(encoder.next(REAL_START, GAME_START).length <= 10);
        int most = 0;
        for (int tick = 1; tick < 1000; tick++) {
            most = Math.max(most, encoder.next(REAL_START + tick * 50L + (tick % 7), GAME_START + tick).length);
        }
        assertTrue(most <= 2, "a tick's times took " + most + " bytes");
        // Time that goes backwards (a clock put right) and a game time set by command are still held exactly
        TimeIndex.Decoder decoder = new TimeIndex.Decoder();
        TimeIndex.Encoder odd = new TimeIndex.Encoder();
        long[][] samples = {{REAL_START, 100}, {REAL_START - 3_600_000, 101}, {REAL_START + 5, 24_000_000_000L}, {Long.MAX_VALUE / 4, 0}};
        for (long[] sample : samples) {
            byte[] bytes = odd.next(sample[0], sample[1]);
            decoder.read(bytes, bytes.length);
            assertEquals(sample[0], decoder.realTime());
            assertEquals(sample[1], decoder.gameTime());
        }
    }

    @Test
    void anOldRecordingWithoutTimesStillOpensAndSaysItHasNone() throws IOException {
        Path file = this.record("old", 0, 2, 0, false);
        try (RecordingFile recording = RecordingFile.open(file)) {
            assertFalse(recording.hasTimes());
            assertNull(recording.timeAt(50));
            assertNull(recording.tickNearest(REAL_START));
            assertEquals("s0t150", play(recording).atTick().get(150));
        }
    }

    @Test
    void timesAreReadFromTheSegmentsWhenTheDescriptionLacksThem() throws IOException {
        // As a crash leaves it: the description was written at the start, before any segment's times were known
        Path file = this.record("crashed", 0, 3, 0, true);
        try (RecordingFile recording = RecordingFile.openForEditing(file)) {
            RecordingManifest bare = recording.manifest();
            bare.times.clear();
            recording.container().append(Container.MANIFEST, 0, 0, 0, bare.toBytes());
            recording.container().appendTrailer();
        }
        try (RecordingFile recording = RecordingFile.open(file)) {
            assertTrue(recording.manifest().times.isEmpty());
            assertEquals(3, recording.segmentTimes().size());
            assertEquals(realAt(0, 200, 0), recording.segmentTimes().get(2).real);
            assertEquals(250, recording.tickNearest(realAt(0, 250, 0)).tick());
        }
    }

    // --- Combine (FILE-9) ---

    @Test
    void combiningPlaysEachSourceInTurnAndAddsNoTicks() throws IOException {
        Path a = this.record("a", 1, 2, 0, true);
        Path b = this.record("b", 2, 3, 0, true);
        Path target = this.folder.resolve("joined" + RecordingFile.EXTENSION);
        try (RecordingFile first = RecordingFile.open(a); RecordingFile second = RecordingFile.open(b)) {
            Assembler.write(target, newManifest("joined"), List.of(new Assembler.Piece(first, 0, 200, null), new Assembler.Piece(second, 0, 300, null)),
                "picture".getBytes(StandardCharsets.UTF_8), "{}".getBytes(StandardCharsets.UTF_8), QUIET);
        }
        try (RecordingFile joined = RecordingFile.open(target)) {
            assertEquals(500, joined.totalTicks());
            assertTrue(joined.manifest().finished);
            assertEquals(5, joined.segments().size());
            Played played = play(joined);
            assertEquals("s1t1", played.atTick().get(1));
            assertEquals("s1t199", played.atTick().get(199));
            // The boundary: the second source's opening snapshot, on the tick the first ends at
            assertEquals("snapshot s2t0", played.atTick().get(200));
            assertEquals("s2t1", played.atTick().get(201));
            assertEquals("s2t300", played.atTick().get(500));
            // Playback rebuilds the world at the start and at the boundary, and nowhere else
            assertEquals(List.of(0, 200), played.afreshAt());
            assertEquals(List.of("world 1", "world 1", "world 2", "world 2", "world 2"), played.worlds());
            // Each source's times, at the ticks they now have
            assertEquals(realAt(1, 150, 0), joined.timeAt(150).realTime());
            assertEquals(realAt(2, 0, 0), joined.timeAt(200).realTime());
            assertEquals(realAt(2, 120, 0), joined.timeAt(320).realTime());
            assertEquals(320, joined.tickNearest(realAt(2, 120, 0)).tick());
            assertEquals(150, joined.tickNearest(realAt(1, 150, 0)).tick());
            assertEquals(5, joined.manifest().times.size());
            assertArrayEquals("picture".getBytes(StandardCharsets.UTF_8), joined.preview());
            assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8), joined.editState());
        }
        // The sources are as they were
        try (RecordingFile first = RecordingFile.open(a)) {
            assertEquals(200, first.totalTicks());
            assertEquals("s1t77", play(first).atTick().get(77));
        }
    }

    /**
     * A source packed with other dictionaries, as one made by a Dailies whose dictionaries were trained afresh: combined
     * whole, trimmed on segment boundaries and cut inside a segment, every new recording is packed with the mod's own
     * dictionaries and plays the same
     */
    @Test
    void sourcesPackedWithOtherDictionariesArePackedAgainWithTheModsOwn() throws IOException {
        this.packWith = TestDictionaries.other();
        // Every segment starts afresh, so a cut on a boundary copies the segment after it, renumbered
        Path older = this.record("older", 1, 4, 1, true);
        this.packWith = null;
        Path now = this.record("now", 2, 2, 0, true);
        Path joined = this.folder.resolve("joined both" + RecordingFile.EXTENSION);
        Path trimmed = this.folder.resolve("trimmed older" + RecordingFile.EXTENSION);
        try (RecordingFile first = RecordingFile.open(older); RecordingFile second = RecordingFile.open(now)) {
            assertTrue(first.container().dictionaries().same(TestDictionaries.other()));
            Assembler.write(joined, newManifest("joined both"), List.of(new Assembler.Piece(first, 0, 400, null), new Assembler.Piece(second, 0, 200, null)), null, null, QUIET);
            // A cut on a boundary (the segment after it renumbered) and a cut inside a segment's records
            Assembler.write(trimmed, newManifest("trimmed older"), List.of(new Assembler.Piece(first, 0, 150, null), new Assembler.Piece(first, 300, 400, null)), null, null, QUIET);
        }
        for (Path file : List.of(joined, trimmed)) {
            try (RecordingFile result = RecordingFile.open(file)) {
                assertTrue(result.container().dictionaries().same(Packing.Dictionaries.current()), file.getFileName().toString());
            }
        }
        try (RecordingFile result = RecordingFile.open(joined)) {
            assertEquals(600, result.totalTicks());
            Played played = play(result);
            assertEquals("s1t1", played.atTick().get(1));
            assertEquals("s1t399", played.atTick().get(399));
            assertEquals("snapshot s2t0", played.atTick().get(400));
            assertEquals("s2t200", played.atTick().get(600));
            assertEquals(List.of("world 1", "world 1", "world 1", "world 1", "world 2", "world 2"), played.worlds());
            assertTrue(played.chunksAtStart().contains("300:chunk 1 3"));
            assertEquals(List.of(0, 100, 200, 300, 400), played.afreshAt());
            assertEquals(realAt(1, 250, 100), result.timeAt(250).realTime());
        }
        try (RecordingFile result = RecordingFile.open(trimmed)) {
            assertEquals(250, result.totalTicks());
            Played played = play(result);
            assertEquals("s1t149", played.atTick().get(149));
            assertEquals("snapshot s1t300", played.atTick().get(150));
            assertEquals("s1t400", played.atTick().get(250));
            assertEquals(List.of(0, 100, 150), played.afreshAt());
            assertTrue(played.chunksAtStart().contains("150:chunk 1 3"));
        }
    }

    @Test
    void combiningARecordingWithItselfStoresItsSharedDataOnce() throws IOException {
        Path a = this.record("a", 1, 2, 0, true);
        Path target = this.folder.resolve("twice" + RecordingFile.EXTENSION);
        try (RecordingFile first = RecordingFile.open(a); RecordingFile again = RecordingFile.open(a)) {
            Assembler.write(target, newManifest("twice"), List.of(new Assembler.Piece(first, 0, 200, null), new Assembler.Piece(again, 0, 200, null)), null, null, QUIET);
            try (RecordingFile twice = RecordingFile.open(target)) {
                assertEquals(400, twice.totalTicks());
                assertEquals(first.sharedCount(), twice.sharedCount());
                assertEquals("s1t150", play(twice).atTick().get(350));
            }
        }
    }

    // --- Trim (FILE-10) ---

    @Test
    void aCutOnSegmentBoundariesCopiesSegmentsAndMarksTheJoin() throws IOException {
        Path a = this.record("a", 1, 5, 0, true);
        Path target = this.folder.resolve("trimmed" + RecordingFile.EXTENSION);
        try (RecordingFile source = RecordingFile.open(a)) {
            assertFalse(Assembler.needsSnapshot(source, 0));
            assertFalse(Assembler.needsSnapshot(source, 300));
            assertTrue(Assembler.needsSnapshot(source, 301));
            // Ticks 100 to 300 are cut
            Assembler.write(target, newManifest("trimmed"), List.of(new Assembler.Piece(source, 0, 100, null), new Assembler.Piece(source, 300, 500, null)), null, null, QUIET);
        }
        try (RecordingFile trimmed = RecordingFile.open(target)) {
            assertEquals(300, trimmed.totalTicks());
            Played played = play(trimmed);
            assertEquals("s1t99", played.atTick().get(99));
            assertEquals("snapshot s1t300", played.atTick().get(100));
            assertEquals("s1t301", played.atTick().get(101));
            assertEquals("s1t500", played.atTick().get(300));
            // The segment after the cut followed on in the source; here it must not
            assertEquals(List.of(0, 100), played.afreshAt());
            // The chunks of the segments that were cut are not carried along
            assertEquals(2 + 3, trimmed.sharedCount());
            assertEquals(realAt(1, 300, 0), trimmed.timeAt(100).realTime());
            assertEquals(realAt(1, 99, 0), trimmed.timeAt(99).realTime());
            assertEquals(GAME_START + 450, trimmed.timeAt(250).gameTime());
            assertEquals(3, trimmed.manifest().times.size());
            assertEquals(realAt(1, 300, 0), trimmed.manifest().times.get(1).real);
        }
    }

    @Test
    void aCutInsideASegmentEndsItThereAndStartsAgainFromAFreshSnapshot() throws IOException {
        Path a = this.record("a", 1, 4, 0, true);
        Path target = this.folder.resolve("inside" + RecordingFile.EXTENSION);
        byte[] freshChunk = "chunk as playback had it at 263".getBytes(StandardCharsets.UTF_8);
        PacketPieces.Split fresh = PacketPieces.whole(freshChunk);
        String freshName = HexFormat.of().formatHex(fresh.name());
        try (RecordingFile source = RecordingFile.open(a)) {
            // What playback would hand over: the world it holds at tick 263, as the records of a snapshot
            Records.Writer described = new Records.Writer();
            try (Records.Reader original = new Records.Reader(source.openSegment(2))) {
                while (original.next() && original.kind() != Records.WORLD) {
                    // Find the name of the world the source uses
                }
                described.record(Records.WORLD, original.payload(), 0, original.length());
            }
            described.record(Records.SHARED_PACKET, HexFormat.of().parseHex(freshName));
            described.record(Records.PACKET, "snapshot s1t263".getBytes(StandardCharsets.UTF_8));
            Assembler.Snapshot snapshot = new Assembler.Snapshot(described.take(), Map.of(freshName, fresh.blobs().get(0).copy()));

            // Without the snapshot the piece cannot be written, and nothing is left behind
            assertThrows(IllegalArgumentException.class, () -> Assembler.write(target, newManifest("inside"),
                List.of(new Assembler.Piece(source, 0, 137, null), new Assembler.Piece(source, 263, 400, null)), null, null, QUIET));
            assertFalse(Files.exists(target));

            // Ticks 137 to 263 are cut: neither end is the start of a segment
            Assembler.write(target, newManifest("inside"), List.of(new Assembler.Piece(source, 0, 137, null), new Assembler.Piece(source, 263, 400, snapshot)), null, null, QUIET);
        }
        try (RecordingFile trimmed = RecordingFile.open(target)) {
            assertEquals(137 + 137, trimmed.totalTicks());
            Played played = play(trimmed);
            assertEquals("s1t99", played.atTick().get(99));
            assertEquals("s1t136", played.atTick().get(136));
            // What happened on the way to tick 137 belongs to the stretch that was cut; the fresh snapshot stands there instead
            assertEquals("snapshot s1t263", played.atTick().get(137));
            // What happened on tick 263 itself is already in that snapshot and is not played again
            assertEquals("s1t264", played.atTick().get(138));
            assertEquals("s1t299", played.atTick().get(173));
            assertEquals("snapshot s1t300", played.atTick().get(174));
            assertEquals("s1t301", played.atTick().get(175));
            assertEquals("s1t400", played.atTick().get(274));
            assertEquals(List.of(0, 137), played.afreshAt());
            // Segments: 0-100, 100-137, 137-174 (from the fresh snapshot), 174-274
            assertEquals(List.of(0, 100, 137, 174), trimmed.segments().stream().map(segment -> segment.startTick).toList());
            assertTrue(played.chunksAtStart().contains("137:chunk as playback had it at 263"));
            assertArrayEquals(freshChunk, trimmed.packet(freshName));
            // The times carry straight on from the cut: tick 137 here is tick 263 of the source
            assertEquals(realAt(1, 136, 0), trimmed.timeAt(136).realTime());
            assertEquals(realAt(1, 263, 0), trimmed.timeAt(137).realTime());
            assertEquals(realAt(1, 264, 0), trimmed.timeAt(138).realTime());
            assertEquals(GAME_START + 399, trimmed.timeAt(273).gameTime());
            assertEquals(138, trimmed.tickNearest(realAt(1, 264, 0)).tick());
            assertEquals(realAt(1, 263, 0), trimmed.manifest().times.get(2).real);
        }
    }

    @Test
    void aTrimOfATrimReadsBackTheSame() throws IOException {
        Path a = this.record("a", 1, 5, 0, true);
        Path once = this.folder.resolve("once" + RecordingFile.EXTENSION);
        Path twice = this.folder.resolve("twice" + RecordingFile.EXTENSION);
        try (RecordingFile source = RecordingFile.open(a)) {
            Assembler.write(once, newManifest("once"), List.of(new Assembler.Piece(source, 0, 150, null), new Assembler.Piece(source, 300, 500, null)), null, null, QUIET);
        }
        try (RecordingFile source = RecordingFile.open(once)) {
            // Segments of the first trim: 0-100, 100-150, 150-250, 250-350
            assertEquals(List.of(0, 100, 150, 250), source.segments().stream().map(segment -> segment.startTick).toList());
            Assembler.write(twice, newManifest("twice"), List.of(new Assembler.Piece(source, 100, 250, null)), null, null, QUIET);
        }
        try (RecordingFile result = RecordingFile.open(twice)) {
            assertEquals(150, result.totalTicks());
            Played played = play(result);
            assertEquals("snapshot s1t100", played.atTick().get(0));
            assertEquals("s1t149", played.atTick().get(49));
            assertEquals("snapshot s1t300", played.atTick().get(50));
            assertEquals("s1t400", played.atTick().get(150));
            assertEquals(List.of(0, 50), played.afreshAt());
        }
    }

    // --- Chunks stored in pieces (FILE-5) ---

    private static final String AIR = "air section ".repeat(4);
    private static final String LIT = "#".repeat(64);

    /**
     * A made-up chunk cut up as a real one is: a head of bytes, heightmaps, a list of eight sections, block entities and a
     * list of light. Every section is air but one, which says what it holds; all its light is all lit
     */
    private static PacketPieces.Split chunk(int x, String section, Map<String, byte[]> expected) {
        List<String> sections = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            sections.add(i == 3 ? section : AIR);
        }
        StringBuilder whole = new StringBuilder("chunk " + x + "|heights " + x + "|8|");
        sections.forEach(whole::append);
        whole.append("entities ").append(x).append("|masks|");
        for (int i = 0; i < 10; i++) {
            whole.append(LIT);
        }
        byte[] packet = whole.toString().getBytes(StandardCharsets.UTF_8);
        PacketPieces.Builder builder = new PacketPieces.Builder();
        int at = ("chunk " + x + "|").length();
        builder.bytes(packet, 0, at);
        int heights = ("heights " + x + "|").length();
        builder.part(packet, at, heights);
        int sectionsAt = at + heights;
        builder.list(list -> {
            list.bytes(packet, sectionsAt, 2);
            int from = sectionsAt + 2;
            for (String each : sections) {
                list.part(packet, from, each.length());
                from += each.length();
            }
        });
        int entities = sectionsAt + 2 + sections.stream().mapToInt(String::length).sum();
        int entitiesLength = ("entities " + x + "|").length();
        builder.part(packet, entities, entitiesLength);
        int lightAt = entities + entitiesLength;
        builder.list(list -> {
            list.bytes(packet, lightAt, "masks|".length());
            for (int i = 0; i < 10; i++) {
                list.part(packet, lightAt + "masks|".length() + i * LIT.length(), LIT.length());
            }
        });
        PacketPieces.Split split = builder.finish();
        expected.put(HexFormat.of().formatHex(split.name()), packet);
        return split;
    }

    /**
     * A recording of whole segments of 100 ticks whose snapshots name two chunks in pieces: one that never changes and one
     * whose odd section is different in every segment
     */
    private Path recordPieces(String name, int source, int segments, Map<String, byte[]> expected) throws IOException {
        Path file = this.folder.resolve(name + RecordingFile.EXTENSION);
        RecordingWriter writer = RecordingWriter.create(file, newManifest("id-" + name));
        Records.Writer part = new Records.Writer();
        byte[] world = HexFormat.of().parseHex(writer.putShared(("world " + source).getBytes(StandardCharsets.UTF_8)));
        byte[] still = stored(writer, chunk(source * 10, "stone section " + source, expected));
        for (int segment = 0; segment < segments; segment++) {
            int start = segment * SEGMENT_TICKS;
            writer.beginSegment(start);
            part.number(Records.SNAPSHOT_BEGIN, start);
            if (segment == 0) {
                part.record(Records.DISCONTINUITY);
            }
            part.record(Records.WORLD, world);
            part.record(Records.SHARED_PACKET, still);
            part.record(Records.SHARED_PACKET, stored(writer, chunk(source * 10 + 1, "section of segment " + source + "." + segment, expected)));
            part.record(Records.SNAPSHOT_END);
            for (int tick = start + 1; tick <= start + SEGMENT_TICKS; tick++) {
                part.number(Records.TICK, 1);
                part.record(Records.PACKET, packet(source, tick));
            }
            byte[] bytes = part.take();
            writer.appendToSegment(bytes, 0, bytes.length, start + SEGMENT_TICKS);
        }
        writer.finish();
        return file;
    }

    /**
     * Every stored packet a recording names is there with every piece it is made of, and puts back together exactly as
     * it was made
     *
     * @return the names of every piece of every stored packet the recording names
     */
    private static java.util.Set<String> everyPieceThere(RecordingFile recording, Map<String, byte[]> expected) throws IOException {
        java.util.Set<String> pieces = new java.util.HashSet<>();
        int named = 0;
        for (RecordingManifest.Segment segment : recording.segments()) {
            try (Records.Reader reader = new Records.Reader(recording.openSegment(segment.number))) {
                while (reader.next()) {
                    if (reader.kind() != Records.SHARED_PACKET) {
                        continue;
                    }
                    String name = HexFormat.of().formatHex(reader.payload(), 0, reader.length());
                    named++;
                    for (String piece : recording.piecesOf(name)) {
                        assertTrue(recording.hasShared(piece), "a piece of a stored packet is missing");
                        pieces.add(piece);
                    }
                    assertArrayEquals(expected.get(name), recording.packet(name));
                }
            }
        }
        assertTrue(named > 0);
        return pieces;
    }

    private static String partName(String text) {
        return HexFormat.of().formatHex(Packing.hash(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aChunkInPiecesSharesItsUnchangedSectionsAndLight() throws IOException {
        Map<String, byte[]> expected = new HashMap<>();
        Path a = this.recordPieces("pieces", 1, 4, expected);
        try (RecordingFile recording = RecordingFile.open(a)) {
            java.util.Set<String> pieces = everyPieceThere(recording, expected);
            // One air section and one all-lit stretch of light for every chunk in every snapshot
            assertTrue(pieces.contains(partName(AIR)));
            assertTrue(pieces.contains(partName(LIT)));
            // The world; the chunk that never changes, with its own list, heightmaps, block entities, stone section and
            // list of sections; the air, the light and the list of light both chunks share; and the other chunk's
            // heightmaps and block entities once, with its own list, its list of sections and its odd section for each of
            // the four segments
            assertEquals(1 + 5 + 3 + 2 + 4 * 3, recording.sharedCount());
        }
    }

    @Test
    void trimmingAndCombiningCarryEveryPieceAChunkIsMadeOf() throws IOException {
        Map<String, byte[]> expected = new HashMap<>();
        Path a = this.recordPieces("pieces a", 1, 4, expected);
        Path b = this.recordPieces("pieces b", 2, 2, expected);

        // Whole segments: the second and third
        Path copied = this.folder.resolve("copied" + RecordingFile.EXTENSION);
        try (RecordingFile source = RecordingFile.open(a)) {
            Assembler.write(copied, newManifest("copied"), List.of(new Assembler.Piece(source, 100, 300, null)), null, null, QUIET);
        }
        try (RecordingFile result = RecordingFile.open(copied)) {
            java.util.Set<String> pieces = everyPieceThere(result, expected);
            assertTrue(pieces.contains(partName("section of segment 1.1")) && pieces.contains(partName("section of segment 1.2")));
            // The sections of the segments that were cut stay behind
            assertFalse(result.hasShared(partName("section of segment 1.0")));
            assertFalse(result.hasShared(partName("section of segment 1.3")));
        }

        // A cut inside a segment, with a fresh snapshot whose chunk is made partly of pieces the source holds and partly of new ones
        Path inside = this.folder.resolve("inside pieces" + RecordingFile.EXTENSION);
        try (RecordingFile source = RecordingFile.open(a)) {
            PacketPieces.Split fresh = chunk(11, "section as playback had it at 250", expected);
            Map<String, byte[]> kept = new HashMap<>();
            for (PacketPieces.Blob blob : fresh.blobs()) {
                if (!source.hasShared(blob.hex())) {
                    kept.put(blob.hex(), blob.copy());
                }
            }
            // The air, the light and the chunk's heightmaps and block entities are the source's already
            assertTrue(kept.size() < fresh.blobs().stream().map(PacketPieces.Blob::hex).distinct().count());
            Records.Writer described = new Records.Writer();
            try (Records.Reader original = new Records.Reader(source.openSegment(2))) {
                while (original.next() && original.kind() != Records.WORLD) {
                    // Find the name of the world the source uses
                }
                described.record(Records.WORLD, original.payload(), 0, original.length());
            }
            described.record(Records.SHARED_PACKET, fresh.name());
            Assembler.write(inside, newManifest("inside pieces"), List.of(new Assembler.Piece(source, 0, 150, null),
                new Assembler.Piece(source, 250, 400, new Assembler.Snapshot(described.take(), kept))), null, null, QUIET);
        }
        try (RecordingFile result = RecordingFile.open(inside)) {
            java.util.Set<String> pieces = everyPieceThere(result, expected);
            assertTrue(pieces.contains(partName("section as playback had it at 250")));
            assertTrue(pieces.contains(partName("section of segment 1.0")) && pieces.contains(partName("section of segment 1.3")));
        }

        // Combining a recording with a trimmed one
        Path joined = this.folder.resolve("joined pieces" + RecordingFile.EXTENSION);
        try (RecordingFile first = RecordingFile.open(inside); RecordingFile second = RecordingFile.open(b)) {
            Assembler.write(joined, newManifest("joined pieces"), List.of(new Assembler.Piece(second, 0, 200, null), new Assembler.Piece(first, 0, first.totalTicks(), null)),
                null, null, QUIET);
        }
        try (RecordingFile result = RecordingFile.open(joined)) {
            java.util.Set<String> pieces = everyPieceThere(result, expected);
            assertTrue(pieces.contains(partName("stone section 2")) && pieces.contains(partName("stone section 1")));
            assertTrue(pieces.contains(partName("section as playback had it at 250")));
        }
    }

    @Test
    void cancellingLeavesNothingBehind() throws IOException {
        Path a = this.record("a", 1, 3, 0, true);
        Path target = this.folder.resolve("cancelled" + RecordingFile.EXTENSION);
        try (RecordingFile source = RecordingFile.open(a)) {
            List<Double> reported = new ArrayList<>();
            assertThrows(Assembler.Cancelled.class, () -> Assembler.write(target, newManifest("cancelled"), List.of(new Assembler.Piece(source, 0, 300, null)), null, null,
                new Assembler.Progress() {
                    @Override
                    public void report(double fraction) {
                        reported.add(fraction);
                    }

                    @Override
                    public boolean cancelled() {
                        return !reported.isEmpty();
                    }
                }));
            assertFalse(reported.isEmpty());
        }
        assertFalse(Files.exists(target));
    }

    @Test
    void progressRunsFromNothingToAll() throws IOException {
        Path a = this.record("a", 1, 4, 0, true);
        Path target = this.folder.resolve("progress" + RecordingFile.EXTENSION);
        List<Double> reported = new ArrayList<>();
        try (RecordingFile source = RecordingFile.open(a)) {
            Assembler.write(target, newManifest("progress"), List.of(new Assembler.Piece(source, 100, 400, null)), null, null, new Assembler.Progress() {
                @Override
                public void report(double fraction) {
                    reported.add(fraction);
                }

                @Override
                public boolean cancelled() {
                    return false;
                }
            });
        }
        assertTrue(reported.size() >= 3);
        for (int i = 1; i < reported.size(); i++) {
            assertTrue(reported.get(i) >= reported.get(i - 1));
        }
        assertEquals(1.0, reported.get(reported.size() - 1));
    }

}
