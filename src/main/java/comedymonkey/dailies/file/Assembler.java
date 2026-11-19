package comedymonkey.dailies.file;

import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Builds a new recording out of pieces of others (specification FILE-9 and FILE-10). The sources are only read.
 * <p>
 * A piece is a range of ticks of one source. Whole segments are copied as they are, without unpacking them. A piece
 * that ends inside a segment has that segment's records cut off at the tick. A piece that starts inside a segment
 * needs a description of the world at that tick, which only playback can give ({@link Snapshot}); the segment's
 * records from that tick on follow it. The first segment of every piece is marked as not following on from what is
 * before it, so playback rebuilds the world there: that is all a boundary costs, and no ticks are added.
 * <p>
 * The chunks each snapshot names come along in listings of their own ({@link ChunkListing}), never copied as they are:
 * each is put together from its source and written again, whole for the first snapshot of every piece and as the
 * changes from the snapshot before it in the new recording otherwise, so no listing ever follows on from one that is
 * not there.
 * <p>
 * Server resource packs come along (specification REC-19): the records that say which are in force are copied with
 * the rest, and every pack those records name is stored in the new recording, once, with its pieces. A pack that only
 * a stretch that was cut used is left behind.
 * <p>
 * The new recording is packed with the mod's own dictionaries, like any recording made now. What it copies from a
 * source packed with the same ones goes across byte for byte; what comes from a source packed with others (made by a
 * Dailies whose dictionaries were trained afresh) is unpacked with the source's and packed again with the new ones.
 * <p>
 * Nothing here knows the game. What to put in the new recording's description is the caller's business; this fills
 * in the segments, their times, the length and the resource packs.
 */
public final class Assembler {

    private static final int PART_BYTES = 1024 * 1024;

    public interface Progress {
        /** @param fraction how much of the work is done, from 0 to 1 */
        void report(double fraction);

        /** Asked often. True stops the work: the half-written file is deleted and {@link Cancelled} is thrown */
        boolean cancelled();
    }

    public static final class Cancelled extends IOException {
        public Cancelled() {
            super("Cancelled");
        }
    }

    /**
     * The world at the tick a piece starts on, when that is not the start of a segment.
     *
     * @param records what goes between the start and the end of a snapshot, as records
     * @param shared  shared data those records and the listing name that the source does not hold, by name
     * @param chunks  the chunks the snapshot names where its records say {@link Records#CHUNKS}, or null for none
     */
    public record Snapshot(byte[] records, Map<String, byte[]> shared, @Nullable ChunkListing chunks) {
        public Snapshot(byte[] records, Map<String, byte[]> shared) {
            this(records, shared, null);
        }
    }

    /**
     * Ticks from (included) to to (not included) of a source.
     *
     * @param packKeys other keys for resource packs of this source, by the key the source knows them by, for when
     *                 another piece's source calls a different pack by the same key; null or empty for none
     */
    public record Piece(RecordingFile source, int from, int to, @Nullable Snapshot snapshot, @Nullable Map<String, String> packKeys) {
        public Piece(RecordingFile source, int from, int to, @Nullable Snapshot snapshot) {
            this(source, from, to, snapshot, null);
        }
    }

    private Assembler() {
    }

    /** Whether a piece that starts at this tick of the recording needs a {@link Snapshot}: true unless a segment starts there */
    public static boolean needsSnapshot(RecordingFile source, int from) {
        for (RecordingManifest.Segment segment : source.segments()) {
            if (segment.startTick == from && segment.endTick > segment.startTick) {
                return false;
            }
        }
        return true;
    }

    /**
     * Writes the pieces, in order, as one recording.
     *
     * @param target   must not exist yet
     * @param manifest the new recording's description; its segments, their times and its length are filled in here
     */
    public static void write(Path target, RecordingManifest manifest, List<Piece> pieces, @Nullable byte[] preview, @Nullable byte[] editState, Progress progress) throws IOException {
        if (manifest.id == null || manifest.id.isEmpty()) {
            throw new IllegalArgumentException("A recording needs an id");
        }
        long allTicks = 0;
        for (Piece piece : pieces) {
            if (piece.to() <= piece.from() || piece.from() < 0 || piece.to() > piece.source().totalTicks()) {
                throw new IllegalArgumentException("A piece runs from tick " + piece.from() + " to " + piece.to() + " of a recording of " + piece.source().totalTicks() + " ticks");
            }
            allTicks += piece.to() - piece.from();
        }
        Files.createDirectories(target.toAbsolutePath().getParent());
        Container out = Container.create(target, Packing.Dictionaries.current());
        try {
            manifest.finished = false;
            manifest.times = new ArrayList<>();
            out.append(Container.MANIFEST, 0, 0, 0, manifest.toBytes());
            Job job = new Job(out, manifest, progress, Math.max(1, allTicks));
            int here = 0;
            for (Piece piece : pieces) {
                job.piece(piece, here);
                here += piece.to() - piece.from();
            }
            manifest.packs = new ArrayList<>(job.packs.values());
            if (preview != null) {
                out.append(Container.PREVIEW, 0, 0, 0, preview);
            }
            if (editState != null) {
                out.append(Container.EDIT, 0, 0, 0, editState);
            }
            RecordingFile.describeSegments(out, manifest);
            manifest.totalTicks = here;
            manifest.finished = true;
            out.append(Container.MANIFEST, 0, 0, 0, manifest.toBytes());
            out.appendIndex();
            out.appendTrailer();
            out.close();
        } catch (IOException | RuntimeException e) {
            out.close();
            Files.deleteIfExists(target);
            throw e;
        }
        progress.report(1.0);
    }

    private static final class Job {

        private final Container out;
        private final RecordingManifest manifest;
        private final Progress progress;
        private final long allTicks;
        private long doneTicks = 0;
        private int number = 0;
        /** The resource packs the new recording holds so far, by the key its records know them by */
        final Map<String, RecordingManifest.Pack> packs = new java.util.LinkedHashMap<>();
        private List<RecordingManifest.Pack> sourcePacks = List.of();
        private Map<String, String> packKeys = Map.of();
        private final Container.ListingChain listings = new Container.ListingChain();

        Job(Container out, RecordingManifest manifest, Progress progress, long allTicks) {
            this.out = out;
            this.manifest = manifest;
            this.progress = progress;
            this.allTicks = allTicks;
        }

        private void check() throws Cancelled {
            if (this.progress.cancelled()) {
                throw new Cancelled();
            }
        }

        void piece(Piece piece, int here) throws IOException {
            RecordingFile source = piece.source();
            List<RecordingManifest.Segment> segments = new ArrayList<>(source.segments());
            segments.sort(Comparator.comparingInt((RecordingManifest.Segment segment) -> segment.startTick).thenComparingInt(segment -> segment.number));
            boolean whole = piece.from() == 0 && piece.to() == source.totalTicks();
            this.sourcePacks = source.manifest().packs;
            this.packKeys = piece.packKeys() == null ? Map.of() : piece.packKeys();
            // Records that name a pack by another key have to be written again, so nothing is copied as it stands then
            boolean rekeyed = !this.packKeys.isEmpty();
            if (whole) {
                // Everything a recording stores is used by it somewhere, so there is nothing to pick out
                Container from = source.container();
                List<Map.Entry<String, Container.Entry>> blobs;
                synchronized (from) {
                    blobs = new ArrayList<>(from.shared.entrySet());
                }
                for (Map.Entry<String, Container.Entry> blob : blobs) {
                    this.check();
                    if (!this.has(blob.getKey())) {
                        this.out.appendShared(blob.getKey(), this.sharedPayload(from, blob.getValue(), blob.getKey()));
                    }
                }
                for (RecordingManifest.Pack pack : this.sourcePacks) {
                    this.usePack(source, pack.key);
                }
            }
            Map<Integer, RecordingManifest.SegmentTime> times = new HashMap<>();
            if (whole) {
                for (RecordingManifest.SegmentTime time : source.segmentTimes()) {
                    times.put(time.segment, time);
                }
            }
            boolean first = true;
            for (RecordingManifest.Segment segment : segments) {
                int lo = Math.max(piece.from(), segment.startTick);
                int hi = Math.min(piece.to(), segment.endTick);
                if (hi <= lo) {
                    continue;
                }
                this.check();
                int newStart = here + (lo - piece.from());
                // The chunks the new segment's snapshot names: the source segment's own, or those of the world described at
                // a tick inside it. Whole for the first segment of a piece, which follows on from nothing before it here
                Snapshot inside = lo > segment.startTick ? piece.snapshot() : null;
                ChunkListing listing = lo > segment.startTick ? (inside == null ? null : inside.chunks()) : source.listing(segment.number);
                if (listing != null) {
                    if (!whole || inside != null) {
                        for (String name : listing.names()) {
                            this.bring(source, inside, name, true);
                        }
                    }
                    this.listings.append(this.out, this.number, listing, first);
                }
                boolean untouched = !rekeyed && lo == segment.startTick && hi == segment.endTick && (!first || startsAfresh(source, segment));
                if (untouched && whole) {
                    this.copyParts(source, segment, newStart);
                    RecordingManifest.SegmentTime time = times.get(segment.number);
                    if (time != null) {
                        this.manifest.times.add(new RecordingManifest.SegmentTime(this.number, time.real, time.game));
                    }
                } else if (untouched) {
                    this.noteWhatIsUsed(source, segment);
                    this.copyParts(source, segment, newStart);
                } else {
                    Snapshot snapshot = lo > segment.startTick ? piece.snapshot() : null;
                    if (lo > segment.startTick && (snapshot == null || !first)) {
                        throw new IllegalArgumentException("Tick " + lo + " is not the start of a segment, so the piece that starts there needs a description of the world at that tick");
                    }
                    this.rewrite(source, segment, lo, hi, newStart, snapshot, first);
                }
                first = false;
                this.number++;
                this.doneTicks += hi - lo;
                this.progress.report(Math.min(1.0, this.doneTicks / (double) this.allTicks));
            }
        }

        private boolean has(String name) {
            synchronized (this.out) {
                return this.out.shared.containsKey(name);
            }
        }

        /** A record names a resource pack of the source: the new recording is to hold it, under the key the record will have */
        private void usePack(RecordingFile source, String key) throws IOException {
            String newKey = this.packKeys.getOrDefault(key, key);
            if (key.isEmpty() || this.packs.containsKey(newKey)) {
                return;
            }
            for (RecordingManifest.Pack known : this.sourcePacks) {
                if (known.key.equals(key)) {
                    RecordingManifest.Pack kept = new RecordingManifest.Pack();
                    kept.key = newKey;
                    kept.sha256 = known.sha256;
                    kept.size = known.size;
                    kept.pieces = new ArrayList<>(known.pieces);
                    for (String piece : known.pieces) {
                        this.bring(source, null, piece, false);
                    }
                    this.packs.put(newKey, kept);
                    return;
                }
            }
            // The source names a pack it does not hold: playback says so there, and will here
        }

        /** Takes note of the packs an extension record names. @return the record under its new keys, or null to write it as it is */
        private @Nullable byte[] packRecord(RecordingFile source, Records.Reader reader) throws IOException {
            if (!reader.name().equals(comedymonkey.dailies.packs.PackLog.ID)) {
                return null;
            }
            int from = reader.bodyStart();
            for (String key : comedymonkey.dailies.packs.PackLog.keys(reader.payload(), from, reader.length() - from)) {
                this.usePack(source, key);
            }
            return this.packKeys.isEmpty() ? null : comedymonkey.dailies.packs.PackLog.rekey(reader.payload(), from, reader.length() - from, key -> this.packKeys.getOrDefault(key, key));
        }

        /** Whether a segment already says that it does not follow on from the one before it */
        private static boolean startsAfresh(RecordingFile source, RecordingManifest.Segment segment) {
            try (Records.Reader start = new Records.Reader(source.openSegment(segment.number))) {
                return start.next() && start.kind() == Records.SNAPSHOT_BEGIN && start.next() && start.kind() == Records.DISCONTINUITY;
            } catch (IOException e) {
                return false;
            }
        }

        /** The segment's packed parts exactly as they are, under their new number and ticks */
        private void copyParts(RecordingFile source, RecordingManifest.Segment segment, int newStart) throws IOException {
            Container from = source.container();
            List<Container.Entry> parts = new ArrayList<>();
            synchronized (from) {
                for (Container.Entry part : from.parts) {
                    if (part.a() == segment.number) {
                        parts.add(part);
                    }
                }
            }
            boolean first = true;
            for (Container.Entry part : parts) {
                this.check();
                byte[] packed = this.partPayload(from, from.read(part), first && newStart != segment.startTick, newStart);
                first = false;
                this.out.append(Container.SEGMENT_PART, this.number, newStart, newStart + (part.c() - segment.startTick), packed);
            }
        }

        /**
         * A segment part of the source as the new recording stores it: the very bytes when both pack parts with the same
         * dictionary and the tick stays, unpacked and packed again otherwise
         *
         * @param renumber whether this is a segment's first part, whose snapshot is to say it is of tick newStart
         */
        private byte[] partPayload(Container from, byte[] packed, boolean renumber, int newStart) throws IOException {
            boolean same = Packing.Dictionary.same(from.dictionaries().parts(), this.out.dictionaries().parts());
            if (same && !renumber) {
                return packed;
            }
            byte[] bytes = from.unpackPart(packed);
            if (renumber) {
                byte[] changed = renumbered(bytes, newStart);
                if (changed != null) {
                    bytes = changed;
                } else if (same) {
                    return packed;
                }
            }
            return this.out.packPart(bytes, 0, bytes.length);
        }

        /**
         * A shared blob of the source as the new recording stores it: the very bytes when both pack shared data with the
         * same dictionary, unpacked and packed again otherwise
         */
        private byte[] sharedPayload(Container from, Container.Entry entry, String name) throws IOException {
            byte[] payload = from.read(entry);
            if (Packing.Dictionary.same(from.dictionaries().shared(), this.out.dictionaries().shared())) {
                return payload;
            }
            byte[] data = from.unpackShared(payload);
            return this.out.packShared(HexFormat.of().parseHex(name), data, 0, data.length);
        }

        /**
         * A segment's first part with the tick its snapshot says it is of put right, or null when it does not start the
         * way a segment should, and is then left exactly as it is
         */
        private static @Nullable byte[] renumbered(byte[] bytes, int newStart) {
            // The first record: its kind, its length, then the tick, each a varint
            int at = 0;
            if (bytes.length < 3 || bytes[at++] != Records.SNAPSHOT_BEGIN) {
                return null;
            }
            int length = bytes[at++];
            if (length < 1 || length > 5 || at + length > bytes.length) {
                return null;
            }
            Records.Writer start = new Records.Writer();
            start.number(Records.SNAPSHOT_BEGIN, newStart);
            byte[] head = start.take();
            byte[] all = new byte[head.length + bytes.length - at - length];
            System.arraycopy(head, 0, all, 0, head.length);
            System.arraycopy(bytes, at + length, all, head.length, bytes.length - at - length);
            return all;
        }

        /** Reads through a segment that is to be copied as it is: the shared data it names is brought across, and its first times noted */
        private void noteWhatIsUsed(RecordingFile source, RecordingManifest.Segment segment) throws IOException {
            TimeIndex.Decoder decoder = new TimeIndex.Decoder();
            boolean timed = false;
            int count = 0;
            try (Records.Reader reader = new Records.Reader(source.openSegment(segment.number))) {
                while (reader.next()) {
                    if ((++count & 1023) == 0) {
                        this.check();
                    }
                    int kind = reader.kind();
                    if (kind == Records.SHARED_PACKET || kind == Records.WORLD) {
                        this.bring(source, null, HexFormat.of().formatHex(reader.payload(), 0, reader.length()), kind == Records.SHARED_PACKET);
                    } else if (kind == Records.EXTENSION) {
                        this.packRecord(source, reader);
                    } else if (kind == Records.TIME && !timed) {
                        decoder.read(reader.payload(), reader.length());
                        this.manifest.times.add(new RecordingManifest.SegmentTime(this.number, decoder.realTime(), decoder.gameTime()));
                        timed = true;
                    }
                }
            }
        }

        /**
         * Brings shared data across, once
         *
         * @param pieces whether it is a piece list (file.PacketPieces), whose parts and inner lists come across with it,
         *               before it, so that a new recording that holds a piece list always holds all it is made of
         */
        private void bring(RecordingFile source, @Nullable Snapshot snapshot, String name, boolean pieces) throws IOException {
            this.bring(source, snapshot, name, pieces, false);
        }

        private void bring(RecordingFile source, @Nullable Snapshot snapshot, String name, boolean pieces, boolean inner) throws IOException {
            if (this.has(name)) {
                return;
            }
            byte[] fresh = snapshot == null ? null : snapshot.shared().get(name);
            if (pieces) {
                byte[] list = fresh != null ? fresh : source.hasShared(name) ? source.shared(name) : null;
                List<PacketPieces.Reference> references;
                try {
                    references = list == null ? List.of() : PacketPieces.references(list);
                } catch (RecordingFormatException damaged) {
                    // Not a piece list: brought as it is, and playback steps over it there as it does here
                    references = List.of();
                }
                for (PacketPieces.Reference reference : references) {
                    // A list inside a list holds only parts, so this goes no deeper than that, whatever a damaged one says
                    this.bring(source, snapshot, reference.name(), !inner && reference.kind() == PacketPieces.LIST, true);
                }
            }
            if (fresh != null) {
                this.out.appendShared(name, this.out.packShared(HexFormat.of().parseHex(name), fresh, 0, fresh.length));
                return;
            }
            Container from = source.container();
            Container.Entry entry;
            synchronized (from) {
                entry = from.shared.get(name);
            }
            if (entry != null) {
                this.out.appendShared(name, this.sharedPayload(from, entry, name));
            }
            // Shared data the source itself has lost stays lost: playback steps over it there, and will here
        }

        /**
         * Writes the part of a segment from tick lo to tick hi, record by record.
         *
         * @param snapshot the world at lo, when lo is after the segment's own snapshot
         * @param afresh   whether the new segment must say that it does not follow on from the one before it
         */
        private void rewrite(RecordingFile source, RecordingManifest.Segment segment, int lo, int hi, int newStart, @Nullable Snapshot snapshot, boolean afresh) throws IOException {
            Records.Writer writer = new Records.Writer();
            TimeIndex.Decoder decoder = new TimeIndex.Decoder();
            TimeIndex.Encoder encoder = new TimeIndex.Encoder();
            boolean fromInside = lo > segment.startTick;
            int reached = newStart;

            if (fromInside) {
                writer.number(Records.SNAPSHOT_BEGIN, newStart);
                writer.record(Records.DISCONTINUITY);
                try (Records.Reader described = new Records.Reader(new ByteArrayInputStream(snapshot.records()))) {
                    while (described.next()) {
                        int kind = described.kind();
                        if (kind == Records.SNAPSHOT_BEGIN || kind == Records.SNAPSHOT_END || kind == Records.DISCONTINUITY || kind == Records.TICK || kind == Records.TIME) {
                            continue;
                        }
                        if (kind == Records.SHARED_PACKET || kind == Records.WORLD) {
                            this.bring(source, snapshot, HexFormat.of().formatHex(described.payload(), 0, described.length()), kind == Records.SHARED_PACKET);
                        }
                        byte[] rekeyed = kind == Records.EXTENSION ? this.packRecord(source, described) : null;
                        if (rekeyed != null) {
                            writer.named(Records.EXTENSION, described.name(), rekeyed, 0, rekeyed.length);
                            continue;
                        }
                        writer.record(kind, described.payload(), 0, described.length());
                    }
                }
                writer.record(Records.SNAPSHOT_END);
            }

            // Where the source's records have got to, and whether the time of the first kept tick has been written
            int position = segment.startTick;
            boolean timed = false;
            boolean justBegun = false;
            int count = 0;
            try (Records.Reader reader = new Records.Reader(source.openSegment(segment.number))) {
                while (reader.next()) {
                    if ((++count & 1023) == 0) {
                        this.check();
                    }
                    int kind = reader.kind();
                    if (kind == Records.TICK) {
                        int ticks = reader.number();
                        int from = Math.max(position, lo);
                        int to = Math.min(position + ticks, hi);
                        if (to > from) {
                            if (fromInside && !timed && decoder.any()) {
                                // The times of the tick the piece starts on, which the records left out would have given
                                this.time(writer, encoder, decoder);
                                timed = true;
                            }
                            writer.number(Records.TICK, to - from);
                            reached += to - from;
                        }
                        position += ticks;
                        if (position >= hi && hi < segment.endTick) {
                            // Cut short: whatever happened on the tick the piece stops at belongs to what was cut
                            break;
                        }
                        continue;
                    }
                    if (kind == Records.TIME) {
                        decoder.read(reader.payload(), reader.length());
                        if (position > lo || !fromInside && position == lo) {
                            if (!timed) {
                                this.manifest.times.add(new RecordingManifest.SegmentTime(this.number, decoder.realTime(), decoder.gameTime()));
                                timed = true;
                            }
                            writer.record(Records.TIME, encoder.next(decoder.realTime(), decoder.gameTime()));
                        }
                        continue;
                    }
                    if (position < lo || fromInside && position == lo) {
                        // Before the piece, or already part of the world as the snapshot describes it
                        continue;
                    }
                    if (justBegun) {
                        justBegun = false;
                        if (afresh && kind != Records.DISCONTINUITY) {
                            writer.record(Records.DISCONTINUITY);
                        }
                    }
                    if (kind == Records.SNAPSHOT_BEGIN) {
                        writer.number(Records.SNAPSHOT_BEGIN, newStart);
                        justBegun = true;
                    } else {
                        if (kind == Records.SHARED_PACKET || kind == Records.WORLD) {
                            this.bring(source, null, HexFormat.of().formatHex(reader.payload(), 0, reader.length()), kind == Records.SHARED_PACKET);
                        }
                        byte[] rekeyed = kind == Records.EXTENSION ? this.packRecord(source, reader) : null;
                        if (rekeyed != null) {
                            writer.named(Records.EXTENSION, reader.name(), rekeyed, 0, rekeyed.length);
                        } else {
                            writer.record(kind, reader.payload(), 0, reader.length());
                        }
                    }
                    if (writer.size() >= PART_BYTES) {
                        this.part(writer, newStart, reached);
                    }
                }
            }
            if (writer.size() > 0) {
                this.part(writer, newStart, reached);
            }
        }

        private void time(Records.Writer writer, TimeIndex.Encoder encoder, TimeIndex.Decoder decoder) {
            this.manifest.times.add(new RecordingManifest.SegmentTime(this.number, decoder.realTime(), decoder.gameTime()));
            writer.record(Records.TIME, encoder.next(decoder.realTime(), decoder.gameTime()));
        }

        private void part(Records.Writer writer, int startTick, int endTick) throws IOException {
            byte[] bytes = writer.take();
            this.out.append(Container.SEGMENT_PART, this.number, startTick, endTick, this.out.packPart(bytes, 0, bytes.length));
        }
    }

}
