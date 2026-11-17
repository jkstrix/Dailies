package comedymonkey.dailies.file;

import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A recording on disk, for playback and for the editor. Segments are read one at a time and independently, so a
 * damaged one costs only its own stretch. The editor state and the name can be saved without touching the
 * recorded data. Reading is thread safe.
 */
public final class RecordingFile implements Closeable {

    public static final String EXTENSION = ".dailies";

    private Container container;
    private RecordingManifest manifest;
    private final boolean writable;

    private RecordingFile(Container container, boolean writable) throws IOException {
        this.container = container;
        this.writable = writable;
        this.manifest = RecordingManifest.fromBytes(container.read(container.manifest));
        describeSegments(container, this.manifest);
    }

    /** Opens a recording for watching only */
    public static RecordingFile open(Path file) throws IOException {
        return open(file, false);
    }

    /** Opens a recording so that its editor state and name can also be saved */
    public static RecordingFile openForEditing(Path file) throws IOException {
        return open(file, true);
    }

    private static RecordingFile open(Path file, boolean writable) throws IOException {
        Container container = Container.open(file, writable);
        try {
            return new RecordingFile(container, writable);
        } catch (IOException | RuntimeException e) {
            container.close();
            throw e;
        }
    }

    /** Just the description, without reading any recorded data */
    public static RecordingManifest readManifest(Path file) throws IOException {
        try (RecordingFile recording = open(file)) {
            return recording.manifest();
        }
    }

    /** Whether the file was left without its ending, as a crash mid-recording leaves it */
    public static boolean isUnfinished(Path file) throws IOException {
        try (RecordingFile recording = open(file)) {
            return !recording.container.complete;
        }
    }

    /**
     * Repairs a recording that was cut short: keeps every whole block, drops the torn end and finishes the file.
     *
     * @return the description of what was kept
     */
    public static RecordingManifest recover(Path file) throws IOException {
        try (RecordingFile recording = open(file, true)) {
            if (!recording.container.complete) {
                recording.container.truncateToEnd();
                recording.manifest.finished = true;
                recording.manifest.recovered = true;
                recording.container.append(Container.MANIFEST, 0, 0, 0, recording.manifest.toBytes());
                recording.container.appendIndex();
                recording.container.appendTrailer();
            }
            return recording.manifest();
        }
    }

    /** Fills in the segment list and the length from the segment parts actually in the file */
    static void describeSegments(Container container, RecordingManifest manifest) {
        List<RecordingManifest.Segment> segments = new ArrayList<>();
        int total = 0;
        synchronized (container) {
            for (Container.Entry part : container.parts) {
                RecordingManifest.Segment last = segments.isEmpty() ? null : segments.get(segments.size() - 1);
                if (last == null || last.number != part.a()) {
                    segments.add(new RecordingManifest.Segment(part.a(), part.b(), part.c()));
                } else {
                    last.endTick = Math.max(last.endTick, part.c());
                }
                total = Math.max(total, part.c());
            }
        }
        manifest.segments = segments;
        manifest.totalTicks = total;
    }

    public Path file() {
        return this.container.path;
    }

    /** A copy: changing it changes nothing */
    public RecordingManifest manifest() {
        return this.manifest.copy();
    }

    public String id() {
        return this.manifest.id;
    }

    public int totalTicks() {
        return this.manifest.totalTicks;
    }

    public List<RecordingManifest.Segment> segments() {
        return this.manifest().segments;
    }

    /** The number of the segment that holds a tick, or -1 when the recording is empty */
    public int segmentAt(int tick) {
        int found = -1;
        for (RecordingManifest.Segment segment : this.manifest.segments) {
            if (segment.startTick <= tick || found < 0) {
                found = segment.number;
            }
        }
        return found;
    }

    /**
     * The data of one segment, from its snapshot on. Reading throws a {@link RecordingFormatException} at the point
     * where the segment is damaged; everything read before that is good.
     */
    public InputStream openSegment(int number) {
        List<Container.Entry> parts = new ArrayList<>();
        synchronized (this.container) {
            for (Container.Entry part : this.container.parts) {
                if (part.a() == number) {
                    parts.add(part);
                }
            }
        }
        Container source = this.container;
        return new InputStream() {
            private int next = 0;
            private byte[] current = new byte[0];
            private int position = 0;

            private boolean fill() throws IOException {
                while (this.position >= this.current.length) {
                    if (this.next >= parts.size()) {
                        return false;
                    }
                    this.current = Packing.unpack(source.read(parts.get(this.next++)), 0);
                    this.position = 0;
                }
                return true;
            }

            @Override
            public int read() throws IOException {
                return this.fill() ? this.current[this.position++] & 0xFF : -1;
            }

            @Override
            public int read(byte[] target, int from, int length) throws IOException {
                if (length == 0) {
                    return 0;
                }
                if (!this.fill()) {
                    return -1;
                }
                int count = Math.min(length, this.current.length - this.position);
                System.arraycopy(this.current, this.position, target, from, count);
                this.position += count;
                return count;
            }
        };
    }

    // --- The time index (FILE-16) ---

    private TimeIndex.Table timesOf = null;
    private int timesOfSegment = -1;
    private List<RecordingManifest.SegmentTime> segmentTimes = null;

    private synchronized TimeIndex.Table times(RecordingManifest.Segment segment) throws IOException {
        if (this.timesOf == null || this.timesOfSegment != segment.number) {
            this.timesOf = TimeIndex.Table.read(this.openSegment(segment.number), segment.startTick, segment.endTick);
            this.timesOfSegment = segment.number;
        }
        return this.timesOf;
    }

    /**
     * The real time and game time of each segment's first tick. Taken from the description where it has them all, and
     * read from the segments themselves otherwise (a recording repaired after a crash). Empty for a recording made
     * before times were kept.
     */
    public synchronized List<RecordingManifest.SegmentTime> segmentTimes() throws IOException {
        if (this.segmentTimes == null) {
            List<RecordingManifest.SegmentTime> found = new ArrayList<>();
            java.util.Map<Integer, RecordingManifest.SegmentTime> stored = new java.util.HashMap<>();
            for (RecordingManifest.SegmentTime time : this.manifest.times) {
                stored.put(time.segment, time);
            }
            for (RecordingManifest.Segment segment : this.manifest.segments) {
                RecordingManifest.SegmentTime time = stored.get(segment.number);
                if (time == null) {
                    TimeIndex.Table table = this.times(segment);
                    TimeIndex.Sample first = table.any() ? table.at(segment.startTick) : null;
                    time = first == null ? null : new RecordingManifest.SegmentTime(segment.number, first.realTime(), first.gameTime());
                }
                if (time != null) {
                    found.add(time);
                }
            }
            this.segmentTimes = found;
        }
        return this.segmentTimes;
    }

    /** Whether the recording holds a time index: false for one made before times were kept */
    public boolean hasTimes() throws IOException {
        return !this.segmentTimes().isEmpty();
    }

    /**
     * Every time a tick has. Nearly always one. A tick on which one segment ends and the next begins has two, and where
     * the next does not follow on (a pause, another world, a cut, a join) they can be far apart: the first is when the
     * recording reached that tick, the second when it went on from it. Whatever happened in between, with the
     * recording standing still, is on that tick too. Empty when the recording holds no times.
     */
    public List<TimeIndex.Sample> timesAt(int tick) throws IOException {
        List<TimeIndex.Sample> found = new ArrayList<>();
        for (RecordingManifest.Segment segment : this.manifest.segments) {
            if (segment.startTick <= tick && tick <= segment.endTick) {
                TimeIndex.Table table = this.times(segment);
                TimeIndex.Sample sample = table.any() ? table.exactly(tick) : null;
                if (sample != null && !found.contains(sample)) {
                    found.add(sample);
                }
            }
        }
        return found;
    }

    /**
     * The real time and game time at a tick, or null when the recording does not hold them. Reads one segment, never
     * plays it. For a tick on which two segments meet this is the later time, when the recording went on from it;
     * {@link #timesAt} gives both.
     */
    public @Nullable TimeIndex.Sample timeAt(int tick) throws IOException {
        RecordingManifest.Segment holder = null;
        for (RecordingManifest.Segment segment : this.manifest.segments) {
            if (segment.startTick <= tick || holder == null) {
                holder = segment;
            }
        }
        if (holder == null) {
            return null;
        }
        TimeIndex.Table table = this.times(holder);
        return table.any() ? table.at(tick) : null;
    }

    /**
     * The tick whose real time is closest to the one given, or null when the recording holds no times. Real time stops
     * and jumps where ticks do not (a pause, an edit), so the answer says how far off it is and the caller decides
     * whether that counts as inside the recording.
     */
    public @Nullable TimeIndex.Nearest tickNearest(long realTime) throws IOException {
        List<RecordingManifest.SegmentTime> starts = this.segmentTimes();
        if (starts.isEmpty()) {
            return null;
        }
        // The segment that holds the time starts at or before it; the few that start closest before it are read, and
        // the one that starts first after it in case the time falls just ahead of everything
        List<RecordingManifest.SegmentTime> order = new ArrayList<>(starts);
        order.sort(java.util.Comparator.comparingLong((RecordingManifest.SegmentTime time) -> time.real <= realTime ? realTime - time.real : Long.MAX_VALUE / 2 + (time.real - realTime)));
        TimeIndex.Nearest best = null;
        for (RecordingManifest.SegmentTime start : order.subList(0, Math.min(3, order.size()))) {
            for (RecordingManifest.Segment segment : this.manifest.segments) {
                if (segment.number == start.segment) {
                    TimeIndex.Nearest nearest = this.times(segment).nearest(realTime);
                    if (nearest != null && (best == null || nearest.errorMillis() < best.errorMillis())) {
                        best = nearest;
                    }
                }
            }
        }
        return best;
    }

    /** The source map: the stretches of originals this recording is made of, or itself when it was never edited (FILE-11) */
    public List<Spans.Span> sourceMap() {
        return this.manifest.sourceMap();
    }

    Container container() {
        return this.container;
    }

    public boolean hasShared(String name) {
        synchronized (this.container) {
            return this.container.shared.containsKey(name);
        }
    }

    /** Data stored with {@link RecordingWriter#putShared}, by the name that returned */
    public byte[] shared(String name) throws IOException {
        Container.Entry entry;
        synchronized (this.container) {
            entry = this.container.shared.get(name);
        }
        if (entry == null) {
            throw new RecordingFormatException(RecordingFormatException.Reason.DAMAGED, "The recording is missing shared data " + name);
        }
        return Packing.unpack(this.container.read(entry), Container.HASH_BYTES);
    }

    /**
     * Writes a file stored with {@link RecordingWriter#putFile} out again, a piece at a time, and checks it is whole.
     * The target appears only once it is complete.
     */
    public void extractFile(RecordingManifest.Pack stored, Path target) throws IOException {
        java.security.MessageDigest whole;
        try {
            whole = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path partial = target.resolveSibling(target.getFileName() + "." + Long.toHexString(System.nanoTime()) + ".part");
        try {
            long size = 0;
            try (java.io.OutputStream out = Files.newOutputStream(partial)) {
                for (String piece : stored.pieces) {
                    byte[] bytes = this.shared(piece);
                    whole.update(bytes);
                    out.write(bytes);
                    size += bytes.length;
                }
            }
            if (size != stored.size || !java.util.HexFormat.of().formatHex(whole.digest()).equals(stored.sha256)) {
                throw new RecordingFormatException(RecordingFormatException.Reason.DAMAGED, "A file stored in the recording is not what was put in");
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    /** How many pieces of shared data the recording holds */
    public int sharedCount() {
        synchronized (this.container) {
            return this.container.shared.size();
        }
    }

    public @Nullable byte[] preview() throws IOException {
        Container.Entry entry = this.container.preview;
        return entry == null ? null : this.container.read(entry);
    }

    public @Nullable byte[] editState() throws IOException {
        Container.Entry entry = this.container.edit;
        return entry == null ? null : this.container.read(entry);
    }

    /** Saves the editor state. Adds a small block to the end of the file; the recorded data is not rewritten */
    public synchronized void saveEditState(byte[] state) throws IOException {
        this.requireWritable();
        this.container.append(Container.EDIT, 0, 0, 0, state);
        this.container.appendTrailer();
    }

    public synchronized void rename(String name) throws IOException {
        this.requireWritable();
        this.manifest.name = name;
        this.container.append(Container.MANIFEST, 0, 0, 0, this.manifest.toBytes());
        this.container.appendTrailer();
    }

    public synchronized void savePreview(byte[] png) throws IOException {
        this.requireWritable();
        this.container.append(Container.PREVIEW, 0, 0, 0, png);
        this.container.appendTrailer();
    }

    /** How much of the file is old editor states, names and previews that newer ones replaced */
    public synchronized long deadBytes() {
        return this.container.size() - this.liveBytes();
    }

    private long liveBytes() {
        long live = Container.PREAMBLE_BYTES;
        synchronized (this.container) {
            for (Container.Entry part : this.container.parts) {
                live += Container.HEADER_BYTES + part.length();
            }
            for (Container.Entry blob : this.container.shared.values()) {
                live += Container.HEADER_BYTES + blob.length();
            }
            for (Container.Entry single : new Container.Entry[]{this.container.manifest, this.container.edit, this.container.preview}) {
                if (single != null) {
                    live += Container.HEADER_BYTES + single.length();
                }
            }
            // The index (25 bytes an entry, plus a hash for each shared blob) and the trailer
            live += Container.HEADER_BYTES + 4 + (this.container.parts.size() + this.container.shared.size()) * 25L + this.container.shared.size() * (long) Container.HASH_BYTES;
            live += Container.HEADER_BYTES + 32;
        }
        return live;
    }

    /**
     * Rewrites the file without its dead blocks. This copies the whole recording, so it is for the rare moment when
     * {@link #deadBytes} has grown large, never for an ordinary save.
     */
    public synchronized void compact() throws IOException {
        this.requireWritable();
        Path file = this.container.path;
        Path temporary = file.resolveSibling(file.getFileName() + ".compacting");
        Files.deleteIfExists(temporary);
        Container fresh = Container.create(temporary);
        try {
            fresh.append(Container.MANIFEST, 0, 0, 0, this.manifest.toBytes());
            synchronized (this.container) {
                for (Container.Entry part : this.container.parts) {
                    fresh.append(Container.SEGMENT_PART, part.a(), part.b(), part.c(), this.container.read(part));
                }
                for (Map.Entry<String, Container.Entry> blob : this.container.shared.entrySet()) {
                    fresh.appendShared(blob.getKey(), this.container.read(blob.getValue()));
                }
            }
            byte[] preview = this.preview();
            if (preview != null) {
                fresh.append(Container.PREVIEW, 0, 0, 0, preview);
            }
            byte[] edit = this.editState();
            if (edit != null) {
                fresh.append(Container.EDIT, 0, 0, 0, edit);
            }
            fresh.appendIndex();
            fresh.appendTrailer();
        } catch (IOException | RuntimeException e) {
            fresh.close();
            Files.deleteIfExists(temporary);
            throw e;
        }
        fresh.close();
        this.container.close();
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        this.container = Container.open(file, true);
    }

    private void requireWritable() {
        if (!this.writable) {
            throw new IllegalStateException("The recording was opened for watching only");
        }
    }

    @Override
    public void close() throws IOException {
        this.container.close();
    }

}
