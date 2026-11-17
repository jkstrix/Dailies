package comedymonkey.dailies.file;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

/**
 * Writes a recording as it is being made. Recorded data goes in as parts of the current segment, each part written
 * whole and checksummed, so a crash loses only what had not been handed over yet. Not thread safe: one thread, the
 * recording's own writer, calls it.
 */
public final class RecordingWriter implements Closeable {

    private final Container container;
    private final RecordingManifest manifest;
    private int segment = -1;
    private int segmentStartTick = 0;
    private boolean closed = false;

    private RecordingWriter(Container container, RecordingManifest manifest) {
        this.container = container;
        this.manifest = manifest;
    }

    /** Starts a new file. The manifest must have its id; it is written at once so that a crash leaves a file that says what it is */
    public static RecordingWriter create(Path file, RecordingManifest manifest) throws IOException {
        if (manifest.id == null || manifest.id.isEmpty()) {
            throw new IllegalArgumentException("A recording needs an id");
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Container container = Container.create(file);
        RecordingWriter writer = new RecordingWriter(container, manifest);
        manifest.finished = false;
        container.append(Container.MANIFEST, 0, 0, 0, manifest.toBytes());
        container.force();
        return writer;
    }

    public Path file() {
        return this.container.path;
    }

    /** The description being built up. Change it freely; it is written by {@link #writeManifest} and {@link #finish} */
    public RecordingManifest manifest() {
        return this.manifest;
    }

    /** The number of the segment being written, from 0, or -1 before the first */
    public int segment() {
        return this.segment;
    }

    /** Every part added from now on belongs to a new segment, which must start with a snapshot */
    public void beginSegment(int startTick) {
        this.segment++;
        this.segmentStartTick = startTick;
    }

    /**
     * Adds the next stretch of the current segment's data.
     *
     * @param endTick how many ticks the recording holds once this part is in
     */
    public void appendToSegment(byte[] data, int from, int length, int endTick) throws IOException {
        if (this.segment < 0) {
            throw new IllegalStateException("beginSegment has not been called");
        }
        this.container.append(Container.SEGMENT_PART, this.segment, this.segmentStartTick, endTick, Packing.pack(new byte[0], data, from, length));
    }

    /**
     * Stores data that may turn up many times, such as an unchanged chunk in snapshot after snapshot. It is kept once.
     *
     * @return the name to ask for it by
     */
    public String putShared(byte[] data) throws IOException {
        byte[] hash = Packing.hash(data);
        String name = HexFormat.of().formatHex(hash);
        if (!this.container.shared.containsKey(name)) {
            this.container.appendShared(name, Packing.pack(hash, data, 0, data.length));
        }
        return name;
    }

    public void setPreview(byte[] png) throws IOException {
        this.container.append(Container.PREVIEW, 0, 0, 0, png);
    }

    /** Writes the description as it stands now, so that a crash keeps it (a new marker, say) */
    public void writeManifest() throws IOException {
        this.container.append(Container.MANIFEST, 0, 0, 0, this.manifest.toBytes());
    }

    /** Makes sure everything added so far is on the disk */
    public void sync() throws IOException {
        this.container.force();
    }

    public long bytesWritten() {
        return this.container.size();
    }

    /** Ends the recording properly and closes the file */
    public void finish() throws IOException {
        RecordingFile.describeSegments(this.container, this.manifest);
        this.manifest.finished = true;
        this.writeManifest();
        this.container.appendIndex();
        this.container.appendTrailer();
        this.close();
    }

    /** Closes the file and deletes it */
    public void discard() throws IOException {
        this.close();
        Files.deleteIfExists(this.container.path);
    }

    @Override
    public void close() throws IOException {
        if (!this.closed) {
            this.closed = true;
            this.container.close();
        }
    }

}
