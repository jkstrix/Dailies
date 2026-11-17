package comedymonkey.dailies.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
        RecordingWriter writer = RecordingWriter.create(file, manifest);
        Records.Writer part = new Records.Writer();
        TimeIndex.Encoder times = new TimeIndex.Encoder();
        int pauseEvery = afreshEvery * SEGMENT_TICKS;
        byte[] world = HexFormat.of().parseHex(writer.putShared(("world " + source).getBytes(StandardCharsets.UTF_8)));
        byte[] still = HexFormat.of().parseHex(writer.putShared(("still chunk " + source).getBytes(StandardCharsets.UTF_8)));
        for (int segment = 0; segment < segments; segment++) {
            int start = segment * SEGMENT_TICKS;
            writer.beginSegment(start);
            part.number(Records.SNAPSHOT_BEGIN, start);
            if (segment == 0 || afreshEvery > 0 && segment % afreshEvery == 0) {
                part.record(Records.DISCONTINUITY);
            }
            part.record(Records.WORLD, world);
            part.record(Records.SHARED_PACKET, still);
            part.record(Records.SHARED_PACKET, HexFormat.of().parseHex(writer.putShared(("chunk " + source + " " + segment).getBytes(StandardCharsets.UTF_8))));
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
                    part.record(Records.TIME, times.next(realAt(source, tick, pauseEvery), GAME_START + tick));
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
                                chunks.add(segment.startTick + ":" + new String(recording.shared(HexFormat.of().formatHex(reader.payload(), 0, reader.length())), StandardCharsets.UTF_8));
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
    void aRecordIsAFewBytesATick() {
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
        String freshName = HexFormat.of().formatHex(Packing.hash(freshChunk));
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
            Assembler.Snapshot snapshot = new Assembler.Snapshot(described.take(), Map.of(freshName, freshChunk));

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
            assertArrayEquals(freshChunk, trimmed.shared(freshName));
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
