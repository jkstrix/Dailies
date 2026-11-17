package comedymonkey.dailies.file;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * The bytes of a .dailies file. After a short preamble the file is nothing but blocks, one after another, each with
 * a kind, three numbers whose meaning depends on the kind, a length and a checksum. Blocks are only ever added at the
 * end, so nothing already written is touched again:
 * <ul>
 * <li>a recording grows by adding segment parts and shared blobs, and a crash loses only the block being written;</li>
 * <li>saving the editor state or a new name adds one small block and a new trailer;</li>
 * <li>a reader finds everything from the trailer, the last block of the file, or, when a crash left none, by walking
 * the blocks from the start and keeping those whose checksum holds.</li>
 * </ul>
 */
final class Container implements Closeable {

    static final byte[] MAGIC = "DAILIES\n".getBytes(StandardCharsets.US_ASCII);
    static final int VERSION = 1;
    static final int PREAMBLE_BYTES = 16;
    static final int HEADER_BYTES = 21;
    static final int HASH_BYTES = 32;

    static final byte MANIFEST = 1;
    /** a: segment number, b: the segment's first tick, c: the tick reached at the end of this part */
    static final byte SEGMENT_PART = 2;
    /** The payload starts with the SHA-256 of the blob's content, which is its name */
    static final byte SHARED = 3;
    static final byte PREVIEW = 4;
    static final byte EDIT = 5;
    /** Where every segment part and shared blob is */
    static final byte INDEX = 6;
    /** Four positions: the index, the manifest, the editor state and the preview (-1 when there is none) */
    static final byte TRAILER = 7;

    private static final int TRAILER_PAYLOAD_BYTES = 32;

    record Entry(byte kind, int a, int b, int c, long offset, int length) {
        long end() {
            return this.offset + HEADER_BYTES + this.length;
        }
    }

    final Path path;
    private final FileChannel channel;
    /** Segment parts, in file order */
    final List<Entry> parts = new ArrayList<>();
    /** Shared blobs by the hex of their hash */
    final Map<String, Entry> shared = new LinkedHashMap<>();
    Entry manifest = null;
    Entry edit = null;
    Entry preview = null;
    private Entry index = null;
    /** Where the next block goes */
    private long end;
    /** Whether the file ended with a trailer when it was opened */
    boolean complete = false;

    private Container(Path path, FileChannel channel) {
        this.path = path;
        this.channel = channel;
    }

    static Container create(Path path) throws IOException {
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE);
        Container container = new Container(path, channel);
        ByteBuffer preamble = ByteBuffer.allocate(PREAMBLE_BYTES);
        preamble.put(MAGIC).putInt(VERSION).putInt(0).flip();
        writeFully(channel, preamble, 0);
        container.end = PREAMBLE_BYTES;
        return container;
    }

    /**
     * @param writable whether blocks may be added (the editor state, a new name, or the repair of a crashed recording)
     */
    static Container open(Path path, boolean writable) throws IOException {
        FileChannel channel = writable ? FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE) : FileChannel.open(path, StandardOpenOption.READ);
        Container container = new Container(path, channel);
        try {
            container.load();
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
        return container;
    }

    private void load() throws IOException {
        long size = this.channel.size();
        ByteBuffer preamble = ByteBuffer.allocate(PREAMBLE_BYTES);
        if (size < PREAMBLE_BYTES || !readFully(this.channel, preamble, 0)) {
            throw new RecordingFormatException(RecordingFormatException.Reason.NOT_A_RECORDING, this.path.getFileName() + " is not a Dailies recording");
        }
        preamble.flip();
        byte[] magic = new byte[MAGIC.length];
        preamble.get(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new RecordingFormatException(RecordingFormatException.Reason.NOT_A_RECORDING, this.path.getFileName() + " is not a Dailies recording");
        }
        int version = preamble.getInt();
        if (version > VERSION) {
            throw new RecordingFormatException(RecordingFormatException.Reason.NEWER_FORMAT, this.path.getFileName() + " was made by a newer version of Dailies (format " + version
                + ", this version reads up to " + VERSION + "). Update Dailies to open it");
        }
        if (!this.loadFromTrailer(size)) {
            this.loadByWalking(size);
        }
    }

    private boolean loadFromTrailer(long size) {
        try {
            long trailerOffset = size - HEADER_BYTES - TRAILER_PAYLOAD_BYTES;
            if (trailerOffset < PREAMBLE_BYTES) {
                return false;
            }
            Entry trailer = this.header(trailerOffset, size);
            if (trailer == null || trailer.kind != TRAILER || trailer.length != TRAILER_PAYLOAD_BYTES) {
                return false;
            }
            ByteBuffer positions = ByteBuffer.wrap(this.read(trailer));
            long indexOffset = positions.getLong();
            long manifestOffset = positions.getLong();
            long editOffset = positions.getLong();
            long previewOffset = positions.getLong();

            Entry indexEntry = this.expect(indexOffset, INDEX, size);
            Entry manifestEntry = this.expect(manifestOffset, MANIFEST, size);
            if (indexEntry == null || manifestEntry == null) {
                return false;
            }
            ByteBuffer listed = ByteBuffer.wrap(this.read(indexEntry));
            int count = listed.getInt();
            List<Entry> listedParts = new ArrayList<>();
            Map<String, Entry> listedShared = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                byte kind = listed.get();
                Entry entry = new Entry(kind, listed.getInt(), listed.getInt(), listed.getInt(), listed.getLong(), listed.getInt());
                if (entry.offset < PREAMBLE_BYTES || entry.end() > size) {
                    return false;
                }
                if (kind == SHARED) {
                    byte[] hash = new byte[HASH_BYTES];
                    listed.get(hash);
                    listedShared.put(HexFormat.of().formatHex(hash), entry);
                } else if (kind == SEGMENT_PART) {
                    listedParts.add(entry);
                } else {
                    return false;
                }
            }
            this.parts.addAll(listedParts);
            this.shared.putAll(listedShared);
            this.index = indexEntry;
            this.manifest = manifestEntry;
            this.edit = editOffset < 0 ? null : this.expect(editOffset, EDIT, size);
            this.preview = previewOffset < 0 ? null : this.expect(previewOffset, PREVIEW, size);
            this.end = size;
            this.complete = true;
            return true;
        } catch (IOException | RuntimeException e) {
            // A trailer that cannot be followed is treated like a missing one
            this.parts.clear();
            this.shared.clear();
            return false;
        }
    }

    /** Reads every block in turn and keeps what is whole. Stops at the first block that is not */
    private void loadByWalking(long size) throws IOException {
        long position = PREAMBLE_BYTES;
        while (position + HEADER_BYTES <= size) {
            Entry entry = this.header(position, size);
            if (entry == null) {
                break;
            }
            byte[] payload;
            try {
                payload = this.read(entry);
            } catch (RecordingFormatException e) {
                break;
            }
            switch (entry.kind) {
                case MANIFEST -> this.manifest = entry;
                case SEGMENT_PART -> this.parts.add(entry);
                case SHARED -> {
                    if (payload.length >= HASH_BYTES) {
                        this.shared.put(HexFormat.of().formatHex(payload, 0, HASH_BYTES), entry);
                    }
                }
                case PREVIEW -> this.preview = entry;
                case EDIT -> this.edit = entry;
                default -> {
                }
            }
            position = entry.end();
        }
        this.end = position;
        this.index = null;
        this.complete = false;
        if (this.manifest == null) {
            throw new RecordingFormatException(RecordingFormatException.Reason.DAMAGED, this.path.getFileName() + " is too badly damaged to open: its description is missing");
        }
    }

    private Entry expect(long offset, byte kind, long size) throws IOException {
        Entry entry = offset < PREAMBLE_BYTES ? null : this.header(offset, size);
        return entry == null || entry.kind != kind ? null : entry;
    }

    /** The block header at a position, or null when there is no believable one there */
    private Entry header(long offset, long size) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
        if (offset + HEADER_BYTES > size || !readFully(this.channel, header, offset)) {
            return null;
        }
        header.flip();
        byte kind = header.get();
        Entry entry = new Entry(kind, header.getInt(), header.getInt(), header.getInt(), offset, header.getInt());
        if (kind < MANIFEST || kind > TRAILER || entry.length < 0 || entry.end() > size) {
            return null;
        }
        return entry;
    }

    /** A block's payload, checked against its checksum */
    byte[] read(Entry entry) throws IOException {
        ByteBuffer block = ByteBuffer.allocate(HEADER_BYTES + entry.length);
        if (!readFully(this.channel, block, entry.offset)) {
            throw new RecordingFormatException(RecordingFormatException.Reason.DAMAGED, "The file ends in the middle of a block");
        }
        byte[] bytes = block.array();
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, HEADER_BYTES - 4);
        crc.update(bytes, HEADER_BYTES, entry.length);
        int stored = ByteBuffer.wrap(bytes, HEADER_BYTES - 4, 4).getInt();
        if (stored != (int) crc.getValue()) {
            throw new RecordingFormatException(RecordingFormatException.Reason.DAMAGED, "A block of the recording is damaged");
        }
        return Arrays.copyOfRange(bytes, HEADER_BYTES, bytes.length);
    }

    synchronized Entry append(byte kind, int a, int b, int c, byte[] payload) throws IOException {
        return this.append(kind, a, b, c, payload, 0, payload.length);
    }

    synchronized Entry append(byte kind, int a, int b, int c, byte[] payload, int from, int length) throws IOException {
        ByteBuffer block = ByteBuffer.allocate(HEADER_BYTES + length);
        block.put(kind).putInt(a).putInt(b).putInt(c).putInt(length);
        CRC32 crc = new CRC32();
        crc.update(block.array(), 0, HEADER_BYTES - 4);
        crc.update(payload, from, length);
        block.putInt((int) crc.getValue());
        block.put(payload, from, length);
        block.flip();
        Entry entry = new Entry(kind, a, b, c, this.end, length);
        writeFully(this.channel, block, this.end);
        this.end = entry.end();
        switch (kind) {
            case MANIFEST -> this.manifest = entry;
            case SEGMENT_PART -> this.parts.add(entry);
            case PREVIEW -> this.preview = entry;
            case EDIT -> this.edit = entry;
            case INDEX -> this.index = entry;
            default -> {
            }
        }
        return entry;
    }

    synchronized void appendShared(String hashHex, byte[] payload) throws IOException {
        this.shared.put(hashHex, this.append(SHARED, 0, 0, 0, payload));
    }

    /** Lists the segment parts and shared blobs written so far, for the next trailer to point at */
    synchronized void appendIndex() throws IOException {
        ByteBuffer listed = ByteBuffer.allocate(4 + (this.parts.size() + this.shared.size()) * 25 + this.shared.size() * HASH_BYTES);
        listed.putInt(this.parts.size() + this.shared.size());
        for (Entry entry : this.parts) {
            listed.put(entry.kind).putInt(entry.a).putInt(entry.b).putInt(entry.c).putLong(entry.offset).putInt(entry.length);
        }
        for (Map.Entry<String, Entry> blob : this.shared.entrySet()) {
            Entry entry = blob.getValue();
            listed.put(entry.kind).putInt(entry.a).putInt(entry.b).putInt(entry.c).putLong(entry.offset).putInt(entry.length);
            listed.put(HexFormat.of().parseHex(blob.getKey()));
        }
        this.append(INDEX, 0, 0, 0, listed.array());
    }

    /** Ends the file properly and makes sure it is on the disk. Needs a manifest and an index */
    synchronized void appendTrailer() throws IOException {
        if (this.index == null) {
            this.appendIndex();
        }
        ByteBuffer positions = ByteBuffer.allocate(TRAILER_PAYLOAD_BYTES);
        positions.putLong(this.index.offset).putLong(this.manifest.offset);
        positions.putLong(this.edit == null ? -1 : this.edit.offset).putLong(this.preview == null ? -1 : this.preview.offset);
        this.append(TRAILER, 0, 0, 0, positions.array());
        this.channel.force(false);
        this.complete = true;
    }

    /** Cuts off anything after the last whole block, which a crash can leave behind */
    synchronized void truncateToEnd() throws IOException {
        if (this.channel.size() > this.end) {
            this.channel.truncate(this.end);
        }
    }

    synchronized long size() {
        return this.end;
    }

    void force() throws IOException {
        this.channel.force(false);
    }

    @Override
    public void close() throws IOException {
        this.channel.close();
    }

    private static boolean readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position);
            if (read < 0) {
                return false;
            }
            position += read;
        }
        return true;
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            int written = channel.write(buffer, position);
            if (written < 0) {
                throw new EOFException();
            }
            position += written;
        }
    }

}
