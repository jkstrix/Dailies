package comedymonkey.dailies.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure parts of H.264 made by several encoders at once: which encoder a frame goes to, its time, and joining */
class ParallelVideoSinkTest {

    private static final double FPS = 60.0;
    private static final double HALF = 0.5 / FPS;

    // ------------------------------------------------------------------ Routing

    /** Chunk after chunk, round the encoders in turn */
    @Test
    void framesGoChunkByChunkRoundTheEncoders() {
        assertEquals(0, ParallelVideoSink.laneOf(0, 60, 3));
        assertEquals(0, ParallelVideoSink.laneOf(59, 60, 3));
        assertEquals(1, ParallelVideoSink.laneOf(60, 60, 3));
        assertEquals(2, ParallelVideoSink.laneOf(179, 60, 3));
        assertEquals(0, ParallelVideoSink.laneOf(180, 60, 3));
        assertEquals(1, ParallelVideoSink.laneOf(240 + 59, 60, 3));
    }

    /** Every frame of the video is one encoder's, and each encoder's frames come back in order to the right places */
    @Test
    void eachEncodersFramesLandWhereTheyCameFrom() {
        int chunk = 7;
        int processes = 3;
        long[] given = new long[processes];
        Set<Long> seen = new HashSet<>();
        for (long frame = 0; frame < 500; frame++) {
            int lane = ParallelVideoSink.laneOf(frame, chunk, processes);
            assertEquals(frame, ParallelVideoSink.globalFrame(given[lane]++, lane, processes, chunk));
            assertTrue(seen.add(frame));
        }
    }

    /** The time-moving filter moves each frame by its chunk's shift: global frame plus the offset, both times alike */
    @Test
    void timestampsFilterMovesByTheChunkShift() {
        assertEquals("setts=pts=PTS+floor(PTS/60)*120+240:dts=DTS+floor(PTS/60)*120+240", ParallelVideoSink.timestamps(1, 3, 60));
        // The same shift as the global frame: local frame 75 of encoder 1 is chunk 1 of it, frame 15 within
        int chunk = 60;
        int processes = 3;
        int lane = 1;
        long local = 75;
        long moved = local + (local / chunk) * (long) (processes - 1) * chunk + lane * chunk + ParallelVideoSink.offset(processes, chunk);
        assertEquals(ParallelVideoSink.globalFrame(local, lane, processes, chunk) + ParallelVideoSink.offset(processes, chunk), moved);
    }

    /** No decoding time falls below nought, even with a few frames of B-frame delay and short chunks */
    @Test
    void offsetKeepsDecodingTimesAboveNought() {
        assertTrue(ParallelVideoSink.offset(2, 1) >= 4);
        assertEquals(180, ParallelVideoSink.offset(3, 60));
    }

    @Test
    void encoderArgumentsForceAFullKeyframeAtEveryChunk() {
        List<String> arguments = ParallelVideoSink.laneArguments(0, 3, 60, 60.0, "0.csv");
        assertTrue(arguments.containsAll(List.of("-g", "60", "-forced-idr", "1", "expr:eq(mod(n,60),0)", "mpegts", "csv")));
        assertEquals("3.000000", arguments.get(arguments.indexOf("-segment_time") + 1));
        assertEquals("0.008333", arguments.get(arguments.indexOf("-segment_time_delta") + 1));
    }

    // ------------------------------------------------------------------ Memory

    /** Chunks of a second with most of a chunk held for each encoder, where the memory allows */
    @Test
    void planHoldsMostOfAChunkWhereMemoryAllows() {
        long fourK = I420.size(3840, 2160);
        ParallelVideoSink.Plan plan = ParallelVideoSink.plan(60.0, fourK, 3, 2L << 30);
        assertNotNull(plan);
        assertEquals(60, plan.chunk());
        assertEquals(40, plan.buffers());
        assertEquals(30, ParallelVideoSink.plan(29.97, fourK, 3, 2L << 30).chunk());
    }

    /** Less memory: shorter chunks, never under half a second; too little: none at all */
    @Test
    void planShrinksThenGivesUp() {
        long fourK = I420.size(3840, 2160);
        ParallelVideoSink.Plan tight = ParallelVideoSink.plan(60.0, fourK, 3, 600L << 20);
        assertNotNull(tight);
        assertEquals(30, tight.chunk());
        assertEquals(16, tight.buffers());
        assertTrue(3L * tight.buffers() * fourK <= 600L << 20);
        assertNull(ParallelVideoSink.plan(60.0, fourK, 3, 100L << 20));
    }

    // ------------------------------------------------------------------ Joining

    private static ParallelVideoSink.Part part(String file, int fromFrame, int toFrame) {
        return new ParallelVideoSink.Part(file, fromFrame / FPS, toFrame / FPS);
    }

    @Test
    void readsTheEncodersListOfFiles() {
        ParallelVideoSink.Part part = ParallelVideoSink.parsePart("0-000001.ts,6.000000,7.000000");
        assertNotNull(part);
        assertEquals("0-000001.ts", part.file());
        assertEquals(6.0, part.start());
        assertEquals(7.0, part.end());
        assertEquals("a,b.ts", ParallelVideoSink.parsePart("\"a,b.ts\",1.5,2.5").file());
        assertNull(ParallelVideoSink.parsePart(""));
        assertNull(ParallelVideoSink.parsePart("x.ts,one,two"));
    }

    /** FFmpeg lists an encoder's first file as starting at nought; it starts where that encoder's first chunk does */
    @Test
    void firstFileOfEachEncoderStartsWhereItsFirstChunkDoes() {
        List<ParallelVideoSink.Part> listed = List.of(new ParallelVideoSink.Part("1-000000.ts", 0.0, 5.0), part("1-000001.ts", 420, 480));
        List<ParallelVideoSink.Part> parts = ParallelVideoSink.laneParts(listed, 4.0);
        assertEquals(4.0, parts.get(0).start());
        assertEquals(5.0, parts.get(0).end());
        assertEquals(listed.get(1), parts.get(1));
        assertTrue(ParallelVideoSink.laneParts(List.of(), 4.0).isEmpty());
    }

    /** All the encoders' files, in order of time, whichever encoder wrote them */
    @Test
    void joinsInOrderOfTime() {
        int chunk = 60;
        int processes = 3;
        int offset = ParallelVideoSink.offset(processes, chunk);
        List<ParallelVideoSink.Part> parts = new ArrayList<>();
        // Each encoder's files, listed encoder by encoder as they are read: 1000 frames, the last chunk short
        for (int lane = 0; lane < processes; lane++) {
            for (int c = lane; c * chunk < 1000; c += processes) {
                parts.add(part(lane + "-" + c, offset + c * chunk, offset + Math.min(1000, (c + 1) * chunk)));
            }
        }
        List<ParallelVideoSink.Part> order = ParallelVideoSink.joinOrder(parts, offset / FPS, HALF);
        assertEquals(17, order.size());
        for (int c = 0; c < order.size(); c++) {
            assertEquals((c % processes) + "-" + c, order.get(c).file());
        }
        assertEquals(1000, ParallelVideoSink.framesCovered(order, offset / FPS, FPS));
    }

    /** An encoder that stopped early: what is joined runs from the start to the first gap, and still plays (EXP-6) */
    @Test
    void joinStopsAtTheFirstGap() {
        List<ParallelVideoSink.Part> parts = List.of(
            part("0-0", 0, 60), part("1-0", 60, 120), part("2-0", 120, 180),
            part("0-1", 180, 240), /* encoder 1's second chunk never finished */ part("2-1", 300, 360), part("0-2", 360, 420));
        List<ParallelVideoSink.Part> order = ParallelVideoSink.joinOrder(parts, 0.0, HALF);
        assertEquals(List.of("0-0", "1-0", "2-0", "0-1"), order.stream().map(ParallelVideoSink.Part::file).toList());
        assertEquals(240, ParallelVideoSink.framesCovered(order, 0.0, FPS));
        // Nothing at the very start: nothing to join
        assertTrue(ParallelVideoSink.joinOrder(List.of(part("1-0", 60, 120)), 0.0, HALF).isEmpty());
        assertEquals(0, ParallelVideoSink.framesCovered(List.of(), 0.0, FPS));
    }

    /** Times written to six places still meet at a frame rate that does not divide a second evenly */
    @Test
    void joinToleratesRoundedTimes() {
        double fps = 30000.0 / 1001.0;
        List<ParallelVideoSink.Part> parts = new ArrayList<>();
        for (int c = 0; c < 50; c++) {
            double start = Math.round(c * 30 / fps * 1e6) / 1e6;
            double end = Math.round((c + 1) * 30 / fps * 1e6) / 1e6;
            parts.add(new ParallelVideoSink.Part("p" + c, start, end));
        }
        List<ParallelVideoSink.Part> order = ParallelVideoSink.joinOrder(parts, 0.0, 0.5 / fps);
        assertEquals(50, order.size());
        assertEquals(1500, ParallelVideoSink.framesCovered(order, 0.0, fps));
    }

    @Test
    void chunkFramesAreWholeFrames() {
        assertEquals(60, ParallelVideoSink.chunkFrames(60.0, 1.0));
        assertEquals(30, ParallelVideoSink.chunkFrames(29.97, 1.0));
        assertEquals(1, ParallelVideoSink.chunkFrames(0.1, 1.0));
        assertEquals(2, ParallelVideoSink.buffersFor(3, 3));
        assertEquals(40, ParallelVideoSink.buffersFor(60, 3));
        assertEquals(30, ParallelVideoSink.buffersFor(60, 2));
    }

    // ------------------------------------------------------------------ Where the parts go

    /**
     * The encoders read the whole path of their parts as a numbering pattern: a percent sign in the folder's path
     * (say a folder called "100% done") must reach them doubled, so only the part's own number is a pattern (EXP-2)
     */
    @Test
    void aPercentSignInTheFolderIsDoubledForTheEncoders(@TempDir Path parent) {
        Path folder = parent.resolve("100% done").resolve(TemporaryFiles.parts());
        String template = ParallelVideoSink.segmentTemplate(folder, 2);
        String separator = folder.getFileSystem().getSeparator();
        assertTrue(template.contains("100%% done"), template);
        assertTrue(template.endsWith(separator + "2-%06d.ts"), template);
        // Read as FFmpeg reads it, "%%" is a percent sign: one pattern is left, the part's number
        assertEquals(1, template.replace("%%", "").chars().filter(c -> c == '%').count(), template);
        assertEquals(folder.toAbsolutePath() + separator + "2-%06d.ts", template.replace("%%", "%"));
    }

    @Test
    void aFolderWithoutPercentSignsIsLeftAsItIs(@TempDir Path parent) {
        Path folder = parent.resolve(TemporaryFiles.parts());
        String separator = folder.getFileSystem().getSeparator();
        assertEquals(folder.toAbsolutePath() + separator + "0-%06d.ts", ParallelVideoSink.segmentTemplate(folder, 0));
    }

}
