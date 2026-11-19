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
        try {
            manifest.finished = false;
            container.append(Container.MANIFEST, 0, 0, 0, manifest.toBytes());
            container.force();
        } catch (IOException | RuntimeException e) {
            // Left open, the file would stay locked on Windows until the game closed; it holds nothing worth keeping
            container.closeAndDelete();
            throw e;
        }
        return new RecordingWriter(container, manifest);
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
        return this.putShared(data, hashOf(data));
    }

    /**
     * Stores shared data whose name the caller has already worked out with {@link #hashOf}, so that it is not worked out
     * twice. The name is trusted: one that is not the data's own would make playback find the wrong bytes.
     *
     * @return the name to ask for it by
     */
    public String putShared(byte[] data, byte[] hash) throws IOException {
        return this.putShared(data, 0, data.length, hash);
    }

    /** The same, for a stretch of an array: a section of a chunk packet, say, stored without being copied out first */
    public String putShared(byte[] data, int from, int length, byte[] hash) throws IOException {
        if (hash.length != Container.HASH_BYTES) {
            throw new IllegalArgumentException("A name of shared data is " + Container.HASH_BYTES + " bytes, not " + hash.length);
        }
        String name = HexFormat.of().formatHex(hash);
        if (!this.container.shared.containsKey(name)) {
            this.container.appendShared(name, Packing.pack(hash, data, from, length));
        }
        return name;
    }

    /** The name shared data is stored by: the SHA-256 of its bytes. Any thread */
    public static byte[] hashOf(byte[] data) {
        return Packing.hash(data);
    }

    /** How much of a stored file is read, and written, at a time */
    static final int PIECE_BYTES = 1024 * 1024;

    /**
     * Stores a whole file, such as a server's resource pack (REC-19), a piece at a time, so that it is never held in
     * memory whole. Pieces are shared data, so a file stored twice costs nothing the second time.
     *
     * @return what to keep in the manifest to get the file back; its key is left for the caller to set
     */
    public RecordingManifest.Pack putFile(Path source) throws IOException {
        RecordingManifest.Pack stored = new RecordingManifest.Pack();
        java.security.MessageDigest whole;
        try {
            whole = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] buffer = new byte[PIECE_BYTES];
        try (java.io.InputStream in = Files.newInputStream(source)) {
            int read;
            while ((read = in.readNBytes(buffer, 0, buffer.length)) > 0) {
                whole.update(buffer, 0, read);
                stored.pieces.add(this.putShared(read == buffer.length ? buffer : java.util.Arrays.copyOf(buffer, read)));
                stored.size += read;
            }
        }
        stored.sha256 = HexFormat.of().formatHex(whole.digest());
        return stored;
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
