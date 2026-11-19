package comedymonkey.dailies.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chunks each snapshot names, kept in listing blocks of their own (FILE-5): whole every tenth snapshot and as changes
 * between, put together again for a jump, kept for the next, and written afresh by a trim or a join
 */
class ChunkListingTest {

    private static final int SEGMENT_TICKS = 40;
    private static final Assembler.Progress QUIET = new Assembler.Progress() {
        @Override
        public void report(double fraction) {
        }

        @Override
        public boolean cancelled() {
            return false;
        }
    };

    @TempDir
    Path folder;

    // --- A made-up journey: what each snapshot names ---

    /** One chunk as a snapshot names it: its version, and the version of the light on top, or -1 for none */
    private record Seen(int version, int light) {
    }

    /**
     * Snapshot after snapshot of a recorder walking east, then back west: chunks come into reach ahead and drop out
     * behind, a few change, and some have new light from a block next door (or lose it, being described afresh)
     */
    private static List<Map<Long, Seen>> journey(int snapshots, long seed) {
        Random random = new Random(seed);
        List<Map<Long, Seen>> all = new ArrayList<>();
        Map<Long, Seen> known = new HashMap<>();
        int centre = 0;
        for (int snapshot = 0; snapshot < snapshots; snapshot++) {
            if (snapshot % 3 == 2) {
                centre += snapshot < snapshots / 2 ? 1 : -1;
            }
            Map<Long, Seen> now = new TreeMap<>(ChunkListing::compare);
            for (int x = centre - 4; x <= centre + 4; x++) {
                for (int z = -4; z <= 4; z++) {
                    long key = ChunkListing.key(x, z);
                    Seen was = known.get(key);
                    Seen seen;
                    if (was == null) {
                        seen = new Seen(0, -1);
                    } else {
                        int roll = random.nextInt(40);
                        seen = roll == 0 ? new Seen(was.version + 1, -1) : roll == 1 ? new Seen(was.version, snapshot) : roll == 2 ? new Seen(was.version, -1) : was;
                    }
                    known.put(key, seen);
                    now.put(key, seen);
                }
            }
            known.keySet().retainAll(now.keySet());
            all.add(now);
        }
        return all;
    }

    private static byte[] chunkContent(long key, int version) {
        return ("chunk " + ChunkListing.x(key) + "," + ChunkListing.z(key) + " v" + version).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] lightContent(long key, int version, int light) {
        return ("light " + ChunkListing.x(key) + "," + ChunkListing.z(key) + " v" + version + " at " + light).getBytes(StandardCharsets.UTF_8);
    }

    /** The listing a snapshot names, by the names its chunks have when stored whole */
    private static ChunkListing listing(Map<Long, Seen> seen) {
        ChunkListing.Gatherer gatherer = new ChunkListing.Gatherer();
        seen.forEach((key, chunk) -> gatherer.put(key, PacketPieces.whole(chunkContent(key, chunk.version)).name(),
            chunk.light < 0 ? null : PacketPieces.whole(lightContent(key, chunk.version, chunk.light)).name()));
        return gatherer.finish();
    }

    private static byte[] stored(RecordingWriter writer, byte[] content) throws IOException {
        PacketPieces.Split split = PacketPieces.whole(content);
        for (PacketPieces.Blob blob : split.blobs()) {
            writer.putShared(blob.bytes(), blob.from(), blob.length(), blob.name());
        }
        return split.name();
    }

    private static RecordingManifest manifest(String name) {
        RecordingManifest manifest = new RecordingManifest();
        manifest.id = "id-" + name;
        manifest.name = name;
        manifest.gameVersion = "1.21.1";
        return manifest;
    }

    /**
     * Writes the journey as the recorder writes a recording: each segment's first part, then the chunks it names stored
     * and listed, then the part that says where they go
     *
     * @param finish whether to end the file properly; otherwise it is left as a crash leaves it
     */
    private Path record(String name, List<Map<Long, Seen>> journey, boolean finish) throws IOException {
        Path file = this.folder.resolve(name + RecordingFile.EXTENSION);
        RecordingWriter writer = RecordingWriter.create(file, manifest(name));
        Records.Writer part = new Records.Writer();
        for (int segment = 0; segment < journey.size(); segment++) {
            int start = segment * SEGMENT_TICKS;
            writer.beginSegment(start);
            part.number(Records.SNAPSHOT_BEGIN, start);
            if (segment == 0) {
                part.record(Records.DISCONTINUITY);
            }
            part.record(Records.PACKET, ("snapshot of " + start).getBytes(StandardCharsets.UTF_8));
            byte[] bytes = part.take();
            writer.appendToSegment(bytes, 0, bytes.length, start);
            ChunkListing.Gatherer gatherer = new ChunkListing.Gatherer();
            for (Map.Entry<Long, Seen> chunk : journey.get(segment).entrySet()) {
                long key = chunk.getKey();
                Seen seen = chunk.getValue();
                gatherer.put(key, stored(writer, chunkContent(key, seen.version)), seen.light < 0 ? null : stored(writer, lightContent(key, seen.version, seen.light)));
            }
            writer.putListing(gatherer.finish());
            part.record(Records.CHUNKS);
            part.record(Records.SNAPSHOT_END);
            part.number(Records.TICK, SEGMENT_TICKS);
            bytes = part.take();
            writer.appendToSegment(bytes, 0, bytes.length, start + SEGMENT_TICKS);
        }
        if (finish) {
            writer.finish();
        } else {
            writer.close();
        }
        return file;
    }

    /** Whether each segment's listing block is whole or follows on from the one before, as the rule says */
    private static void assertChain(RecordingFile file, int... wholeAt) {
        List<Integer> whole = new ArrayList<>();
        for (int at : wholeAt) {
            whole.add(at);
        }
        int previous = -1;
        for (Container.Entry entry : new ArrayList<>(file.container().listings.values())) {
            if (whole.contains(entry.a())) {
                assertEquals(-1, entry.b(), "the listing of segment " + entry.a() + " should be whole");
            } else {
                assertEquals(previous, entry.b(), "the listing of segment " + entry.a() + " should hold the changes from the one before it");
            }
            previous = entry.a();
        }
    }

    // --- The listing itself ---

    @Test
    void changesFromTheSnapshotBeforeComeToWhatAWholeListingSays() throws IOException {
        List<Map<Long, Seen>> journey = journey(60, 7);
        ChunkListing previous = null;
        for (Map<Long, Seen> snapshot : journey) {
            ChunkListing listing = listing(snapshot);
            assertEquals(listing, ChunkListing.decode(listing.encodeFull(), null), "a whole listing reads back as it was");
            if (previous != null) {
                byte[] changes = listing.encodeChanges(previous);
                assertEquals(listing, ChunkListing.decode(changes, previous), "the changes from the listing before come to the whole listing");
                assertTrue(changes.length < listing.encodeFull().length, "the changes take less than the whole");
                // What differs is exactly what a chunk-by-chunk comparison finds
                List<Long> expected = new ArrayList<>();
                java.util.Set<Long> keys = new java.util.TreeSet<>(ChunkListing::compare);
                for (int i = 0; i < listing.size(); i++) {
                    keys.add(listing.key(i));
                }
                for (int i = 0; i < previous.size(); i++) {
                    keys.add(previous.key(i));
                }
                for (long key : keys) {
                    int now = listing.find(key);
                    int was = previous.find(key);
                    if (now < 0 || was < 0 || !listing.chunkHex(now).equals(previous.chunkHex(was)) || !java.util.Objects.equals(listing.lightHex(now), previous.lightHex(was))) {
                        expected.add(key);
                    }
                }
                assertEquals(expected, java.util.Arrays.stream(listing.differences(previous)).boxed().toList());
            }
            previous = listing;
        }
    }

    @Test
    void aListingKeepsItsChunksInOrderWithTheirLight() throws IOException {
        ChunkListing.Gatherer gatherer = new ChunkListing.Gatherer();
        byte[] a = PacketPieces.whole("a".getBytes(StandardCharsets.UTF_8)).name();
        byte[] b = PacketPieces.whole("b".getBytes(StandardCharsets.UTF_8)).name();
        byte[] light = PacketPieces.whole("light".getBytes(StandardCharsets.UTF_8)).name();
        gatherer.put(ChunkListing.key(3, -2), a, null);
        gatherer.put(ChunkListing.key(-30_000_000, 5), b, light);
        gatherer.put(ChunkListing.key(3, -9), b, null);
        gatherer.put(ChunkListing.key(3, -2), b, light);
        ChunkListing listing = ChunkListing.decode(gatherer.finish().encodeFull(), null);
        assertEquals(3, listing.size());
        assertEquals(ChunkListing.key(-30_000_000, 5), listing.key(0));
        assertEquals(ChunkListing.key(3, -9), listing.key(1));
        assertEquals(ChunkListing.key(3, -2), listing.key(2));
        assertArrayEquals(b, listing.chunk(2), "a chunk named twice keeps the second");
        assertArrayEquals(light, listing.light(2));
        assertNull(listing.light(1));
        assertEquals(1, listing.find(ChunkListing.key(3, -9)));
        assertTrue(listing.find(ChunkListing.key(3, 0)) < 0);
        assertEquals(List.of(listing.chunkHex(0), listing.lightHex(0), listing.chunkHex(1), listing.chunkHex(2), listing.lightHex(2)), listing.names());
    }

    @Test
    void changesThatDoNotFollowOnFromTheListingGivenAreRefused() throws IOException {
        List<Map<Long, Seen>> journey = journey(10, 3);
        // The recorder moved on at the eighth snapshot, so chunks went out of reach behind it
        ChunkListing later = listing(journey.get(8));
        byte[] changes = later.encodeChanges(listing(journey.get(7)));
        // From the wrong listing, or none: chunks said to have gone that it never had
        assertThrows(RecordingFormatException.class, () -> ChunkListing.decode(changes, ChunkListing.EMPTY));
        assertThrows(RecordingFormatException.class, () -> ChunkListing.decode(changes, null));
        // A chunk whose light alone changed, said to keep a stored chunk the listing given does not have
        Map<Long, Seen> lit = new TreeMap<>(ChunkListing::compare);
        lit.putAll(journey.get(8));
        long key = lit.keySet().iterator().next();
        lit.put(key, new Seen(lit.get(key).version, 99));
        byte[] relit = listing(lit).encodeChanges(later);
        assertEquals(listing(lit), ChunkListing.decode(relit, later));
        assertTrue(relit.length < 2 * Container.HASH_BYTES + 10, "only the new light is named: " + relit.length + " bytes");
        assertThrows(RecordingFormatException.class, () -> ChunkListing.decode(relit, ChunkListing.EMPTY));
        byte[] whole = later.encodeFull();
        assertThrows(RecordingFormatException.class, () -> ChunkListing.decode(java.util.Arrays.copyOf(whole, whole.length - 1), null));
        assertThrows(RecordingFormatException.class, () -> ChunkListing.decode(java.util.Arrays.copyOf(whole, whole.length + 1), null));
    }

    // --- In a recording ---

    @Test
    void aJumpToAnySnapshotPutsTogetherTheListingItNamed() throws IOException {
        List<Map<Long, Seen>> journey = journey(35, 11);
        Path path = this.record("journey", journey, true);
        try (RecordingFile file = RecordingFile.open(path)) {
            assertChain(file, 0, 10, 20, 30);
            for (int segment = 0; segment < journey.size(); segment++) {
                assertEquals(listing(journey.get(segment)), file.listing(segment), "the listing of segment " + segment);
                for (String name : file.listing(segment).names()) {
                    assertTrue(file.hasShared(name), "the recording holds every chunk its listings name");
                }
            }
            RecordingFile.ListingWeight weight = file.listingWeight(number -> true);
            assertEquals(4, weight.whole());
            assertEquals(31, weight.changes());
            assertTrue(weight.changeBytes() / weight.changes() < weight.wholeBytes() / weight.whole(), "a listing of changes is smaller than a whole one: " + weight);
        }
        // In any order, each time from nothing kept: from the whole listing at or before it and no further back
        List<Integer> order = new ArrayList<>();
        for (int segment = 0; segment < journey.size(); segment++) {
            order.add(segment);
        }
        Collections.shuffle(order, new Random(5));
        for (int segment : order) {
            try (RecordingFile file = RecordingFile.open(path)) {
                assertEquals(listing(journey.get(segment)), file.listing(segment), "the listing of segment " + segment + " put together from nothing");
                int whole = segment / ChunkListing.FULL_EVERY * ChunkListing.FULL_EVERY;
                for (int other = 0; other < journey.size(); other++) {
                    assertEquals(other >= whole && other <= segment, file.listingKept(other), "after a jump to segment " + segment + ", whether segment " + other + "'s listing is kept");
                }
            }
        }
    }

    @Test
    void aRecordingWhoseSnapshotsNameTheirChunksInTheirRecordsIsRefused() throws IOException {
        Path path = this.record("format two", journey(2, 1), true);
        try (RandomAccessFile raw = new RandomAccessFile(path.toFile(), "rw")) {
            raw.seek(Container.MAGIC.length);
            raw.writeInt(2);
        }
        RecordingFormatException refused = assertThrows(RecordingFormatException.class, () -> RecordingFile.open(path));
        assertTrue(refused.getMessage().contains("test version") && refused.getMessage().contains("format 2"), refused.getMessage());
    }

    @Test
    void aListingPutTogetherOnceIsKept() throws IOException {
        Path path = this.record("kept", journey(25, 2), true);
        try (RecordingFile file = RecordingFile.open(path)) {
            ChunkListing first = file.listing(17);
            assertSame(first, file.listing(17), "the same listing, not put together again");
            // Stepping back a snapshot finds it put together on the way
            assertTrue(file.listingKept(16));
            ChunkListing earlier = file.listing(16);
            assertSame(earlier, file.listing(16));
            // A later one follows on from what is kept
            assertNotNull(file.listing(19));
            assertTrue(file.listingKept(18));
            assertNull(file.listing(99), "a segment that does not exist has no listing");
        }
    }

    @Test
    void aRunOfJumpsReadsEachListingBlockOnce() throws IOException {
        List<Map<Long, Seen>> journey = journey(35, 8);
        Path path = this.record("jumps", journey, true);
        try (RecordingFile file = RecordingFile.open(path)) {
            // A jump back from the end: the whole listing at 30 and the changes up to 33
            file.listing(33);
            assertEquals(4, file.listingBlocksRead.get());
            // Stepping back, and forward again past where it was, reads only what it has not read
            file.listing(31);
            file.listing(34);
            assertEquals(5, file.listingBlocksRead.get());
            // Forwards through every snapshot and back again, as the budgets jump: each block once in all
            for (int segment = 0; segment < journey.size(); segment++) {
                file.listing(segment);
            }
            for (int segment = journey.size() - 1; segment >= 0; segment--) {
                assertEquals(listing(journey.get(segment)), file.listing(segment));
            }
            assertEquals(journey.size(), file.listingBlocksRead.get(), "every listing block read once");
        }
    }

    @Test
    void aRecordingCutShortStillFindsItsListings() throws IOException {
        List<Map<Long, Seen>> journey = journey(15, 4);
        Path path = this.record("crashed", journey, false);
        assertTrue(RecordingFile.isUnfinished(path));
        try (RecordingFile file = RecordingFile.open(path)) {
            for (int segment = 0; segment < journey.size(); segment++) {
                assertEquals(listing(journey.get(segment)), file.listing(segment), "found by walking the blocks: segment " + segment);
            }
        }
        // Torn in the middle of the last block: everything before it is still there
        try (RandomAccessFile raw = new RandomAccessFile(path.toFile(), "rw")) {
            raw.setLength(raw.length() - 7);
        }
        try (RecordingFile file = RecordingFile.open(path)) {
            assertEquals(listing(journey.get(13)), file.listing(13));
        }
        RecordingFile.recover(path);
        try (RecordingFile file = RecordingFile.open(path)) {
            assertTrue(!RecordingFile.isUnfinished(path));
            for (int segment = 0; segment < 14; segment++) {
                assertEquals(listing(journey.get(segment)), file.listing(segment), "listed in the index after the repair: segment " + segment);
            }
            assertChain(file, 0, 10);
        }
    }

    @Test
    void aTrimOfWholeSegmentsWritesItsListingsAfresh() throws IOException {
        List<Map<Long, Seen>> journey = journey(35, 12);
        Path path = this.record("long", journey, true);
        Path trimmed = this.folder.resolve("trimmed" + RecordingFile.EXTENSION);
        try (RecordingFile source = RecordingFile.open(path)) {
            Assembler.write(trimmed, manifest("trimmed"), List.of(new Assembler.Piece(source, 5 * SEGMENT_TICKS, 27 * SEGMENT_TICKS, null)), null, null, QUIET);
        }
        try (RecordingFile file = RecordingFile.open(trimmed)) {
            assertEquals(22, file.segments().size());
            // Whole at the start, which in the source held only changes, and every tenth after
            assertChain(file, 0, 10, 20);
            for (int segment = 0; segment < 22; segment++) {
                ChunkListing listing = file.listing(file.segments().get(segment).number);
                assertEquals(listing(journey.get(segment + 5)), listing, "the listing of the trimmed recording's segment " + segment);
                for (String name : listing.names()) {
                    assertTrue(file.hasShared(name), "the trimmed recording holds every chunk its listings name");
                }
            }
        }
        // The whole recording copied as it is
        Path copy = this.folder.resolve("copy" + RecordingFile.EXTENSION);
        try (RecordingFile source = RecordingFile.open(path)) {
            Assembler.write(copy, manifest("copy"), List.of(new Assembler.Piece(source, 0, source.totalTicks(), null)), null, null, QUIET);
        }
        try (RecordingFile file = RecordingFile.open(copy)) {
            assertChain(file, 0, 10, 20, 30);
            for (int segment = 0; segment < journey.size(); segment++) {
                assertEquals(listing(journey.get(segment)), file.listing(segment));
            }
        }
    }

    @Test
    void aTrimThatCutsInsideASegmentStartsWithTheWholeListingOfTheWorldDescribed() throws IOException {
        List<Map<Long, Seen>> journey = journey(20, 13);
        Path path = this.record("cut", journey, true);
        // The world at tick 5.5 segments in, as playback would describe it: the source's chunks there, one changed by hand
        // since, stored afresh, which the source does not hold
        Map<Long, Seen> described = new TreeMap<>(ChunkListing::compare);
        described.putAll(journey.get(5));
        long changed = described.keySet().iterator().next();
        described.put(changed, new Seen(500, -1));
        ChunkListing chunks = listing(described);
        byte[] fresh = PacketPieces.whole(chunkContent(changed, 500)).blobs().get(0).copy();
        Map<String, byte[]> shared = Map.of(chunks.chunkHex(chunks.find(changed)), fresh);
        Records.Writer records = new Records.Writer();
        records.record(Records.PACKET, "described".getBytes(StandardCharsets.UTF_8));
        records.record(Records.CHUNKS);
        Assembler.Snapshot snapshot = new Assembler.Snapshot(records.take(), shared, chunks);
        int from = 5 * SEGMENT_TICKS + SEGMENT_TICKS / 2;
        int to = 17 * SEGMENT_TICKS + 10;
        Path trimmed = this.folder.resolve("cut inside" + RecordingFile.EXTENSION);
        try (RecordingFile source = RecordingFile.open(path)) {
            assertTrue(Assembler.needsSnapshot(source, from));
            Assembler.write(trimmed, manifest("cut inside"), List.of(new Assembler.Piece(source, from, to, snapshot)), null, null, QUIET);
        }
        try (RecordingFile file = RecordingFile.open(trimmed)) {
            List<RecordingManifest.Segment> segments = file.segments();
            assertEquals(13, segments.size());
            assertChain(file, 0, 10);
            assertEquals(chunks, file.listing(segments.get(0).number), "the first snapshot names the chunks of the world described");
            assertTrue(file.hasShared(chunks.chunkHex(chunks.find(changed))), "the chunk stored afresh came with it");
            for (int segment = 1; segment < segments.size(); segment++) {
                assertEquals(listing(journey.get(segment + 5)), file.listing(segments.get(segment).number), "the trimmed recording's segment " + segment
                    + ", whose listing follows on from the world described");
            }
            // The cut segment at the end keeps its snapshot, and so its listing
            assertEquals(17 * SEGMENT_TICKS - from, segments.get(segments.size() - 1).startTick);
        }
    }

    @Test
    void aJoinStartsEachPieceWithAWholeListing() throws IOException {
        List<Map<Long, Seen>> first = journey(14, 21);
        List<Map<Long, Seen>> second = journey(12, 22);
        Path one = this.record("one", first, true);
        Path two = this.record("two", second, true);
        Path joined = this.folder.resolve("joined" + RecordingFile.EXTENSION);
        try (RecordingFile a = RecordingFile.open(one); RecordingFile b = RecordingFile.open(two)) {
            Assembler.write(joined, manifest("joined"), List.of(new Assembler.Piece(a, 0, a.totalTicks(), null), new Assembler.Piece(b, 3 * SEGMENT_TICKS, 11 * SEGMENT_TICKS, null),
                new Assembler.Piece(a, 2 * SEGMENT_TICKS, 4 * SEGMENT_TICKS, null)), null, null, QUIET);
        }
        try (RecordingFile file = RecordingFile.open(joined)) {
            assertEquals(14 + 8 + 2, file.segments().size());
            // The first of each piece is whole, and so is every tenth since the last whole one
            assertChain(file, 0, 10, 14, 22);
            List<ChunkListing> expected = new ArrayList<>();
            first.forEach(seen -> expected.add(listing(seen)));
            second.subList(3, 11).forEach(seen -> expected.add(listing(seen)));
            first.subList(2, 4).forEach(seen -> expected.add(listing(seen)));
            for (int segment = 0; segment < expected.size(); segment++) {
                assertEquals(expected.get(segment), file.listing(file.segments().get(segment).number), "the joined recording's segment " + segment);
            }
        }
    }

}
