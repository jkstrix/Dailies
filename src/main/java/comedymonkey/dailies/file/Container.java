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
 * <li>a recording grows by adding segment parts, shared blobs and the listings of the chunks its snapshots name, and a
 * crash loses only the block being written;</li>
 * <li>saving the editor state or a new name adds one small block and a new trailer;</li>
 * <li>a reader finds everything from the trailer, the last block of the file, or, when a crash left none, by walking
 * the blocks from the start and keeping those whose checksum holds.</li>
 * </ul>
 * Every recording carries the dictionaries its blocks were packed with, written right after the preamble, and is read
 * with those and no others: a later Dailies with dictionaries trained afresh still opens it.
 */
final class Container implements Closeable {

    static final byte[] MAGIC = "DAILIES\n".getBytes(StandardCharsets.US_ASCII);
    /**
     * 3: the chunks a snapshot names are in listing blocks of their own, whole or as changes ({@link #LISTING}). Version 2
     * named them in the snapshot's records, and version 1 packed blocks with deflate; neither is read
     */
    static final int VERSION = 3;
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
    /**
     * A dictionary the recording's blocks are packed with, as it is (not packed itself). a: which, {@link #SHARED_DICTIONARY}
     * or {@link #PARTS_DICTIONARY}. Empty for none. Each is written once, before the manifest
     */
    static final byte DICTIONARY = 8;
    static final int SHARED_DICTIONARY = 0;
    static final int PARTS_DICTIONARY = 1;
    /**
     * The chunks one segment's snapshot names ({@link ChunkListing}), packed without a dictionary: names are random and no
     * dictionary helps them. a: segment number, b: the segment whose listing this one holds the changes from, which is
     * always an earlier one, or -1 for a whole listing, c: how many chunks the listing comes to. Written before the segment
     * part whose records say where in the snapshot the chunks go ({@link Records#CHUNKS}), so whatever names a listing has it
     */
    static final byte LISTING = 9;

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
    /** Chunk listings by segment number, in file order */
    final Map<Integer, Entry> listings = new LinkedHashMap<>();
    Entry manifest = null;
    /** The dictionary blocks, by {@link #SHARED_DICTIONARY} and {@link #PARTS_DICTIONARY} */
    final Entry[] dictionaryBlocks = new Entry[2];
    /**
     * What the blocks are packed with, read from the dictionary blocks when first needed: listing recordings reads only
     * their manifests, and should not read their dictionaries too
     */
    private volatile Packing.Dictionaries dictionaries = null;
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

    /** @param dictionaries what every block of the new recording is packed with, written into it first */
    static Container create(Path path, Packing.Dictionaries dictionaries) throws IOException {
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE);
        Container container = new Container(path, channel);
        try {
            ByteBuffer preamble = ByteBuffer.allocate(PREAMBLE_BYTES);
            preamble.put(MAGIC).putInt(VERSION).putInt(0).flip();
            writeFully(channel, preamble, 0);
            container.end = PREAMBLE_BYTES;
            container.append(DICTIONARY, SHARED_DICTIONARY, 0, 0, dictionaries.shared() == null ? new byte[0] : dictionaries.shared().bytes);
            container.append(DICTIONARY, PARTS_DICTIONARY, 0, 0, dictionaries.parts() == null ? new byte[0] : dictionaries.parts().bytes);
            container.dictionaries = dictionaries;
        } catch (IOException | RuntimeException e) {
            // A channel left open keeps the file locked on Windows until the game closes. The file is new and ours, and empty
            container.closeAndDelete();
            throw e;
        }
        return container;
    }

    /**
     * What this recording's blocks are packed with: its own dictionaries, never the mod's
     *
     * @throws RecordingFormatException when a dictionary block is damaged
     */
    Packing.Dictionaries dictionaries() throws IOException {
        Packing.Dictionaries known = this.dictionaries;
        if (known == null) {
            synchronized (this) {
                if (this.dictionaries == null) {
                    this.dictionaries = this.readDictionaries();
                }
                known = this.dictionaries;
            }
        }
        return known;
    }

    /** A segment part's data, packed as this recording packs it */
    byte[] packPart(byte[] data, int from, int length) throws IOException {
        return Packing.pack(this.dictionaries().parts(), new byte[0], data, from, length);
    }

    /** A segment part's payload, unpacked */
    byte[] unpackPart(byte[] payload) throws IOException {
        return Packing.unpack(this.dictionaries().parts(), payload, 0);
    }

    /** A shared blob's payload: its name, then its data packed as this recording packs it */
    byte[] packShared(byte[] hash, byte[] data, int from, int length) throws IOException {
        return Packing.pack(this.dictionaries().shared(), hash, data, from, length);
    }

    /** A shared blob's data, from its payload */
    byte[] unpackShared(byte[] payload) throws IOException {
        return Packing.unpack(this.dictionaries().shared(), payload, HASH_BYTES);
    }

    /** A chunk listing's payload */
    static byte[] packListing(byte[] data) {
        return Packing.pack(null, new byte[0], data, 0, data.length);
    }

    static byte[] unpackListing(byte[] payload) throws IOException {
        return Packing.unpack(null, payload, 0);
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
        if (version < VERSION) {
            // Before release the format changed without a way back: recordings packed with deflate, or whose snapshots name
            // their chunks in their own records, are not read
            throw new RecordingFormatException(RecordingFormatException.Reason.NOT_A_RECORDING, this.path.getFileName() + " was made by a test version of Dailies (format " + version
                + ") whose recordings this version cannot open");
        }
        if (!this.loadFromTrailer(size)) {
            this.loadByWalking(size);
        }
        for (Entry entry : this.dictionaryBlocks) {
            if (entry == null) {
                throw new RecordingFormatException(RecordingFormatException.Reason.DAMAGED, this.path.getFileName() + " is too badly damaged to open: a dictionary it was packed with is missing");
            }
            if (entry.length > Packing.MAX_DICTIONARY) {
                throw new RecordingFormatException(RecordingFormatException.Reason.DAMAGED, this.path.getFileName() + " is damaged: a dictionary it was packed with is " + entry.length + " bytes long");
            }
        }
    }

    /** The dictionaries the blocks were packed with, from the recording's own dictionary blocks */
    private Packing.Dictionaries readDictionaries() throws IOException {
        Packing.Dictionary[] found = new Packing.Dictionary[2];
        for (int which = 0; which < 2; which++) {
            found[which] = Packing.Dictionary.of(this.read(this.dictionaryBlocks[which]));
        }
        return new Packing.Dictionaries(found[SHARED_DICTIONARY], found[PARTS_DICTIONARY]);
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
            Map<Integer, Entry> listedListings = new LinkedHashMap<>();
            Entry[] listedDictionaries = new Entry[2];
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
                } else if (kind == LISTING) {
                    listedListings.put(entry.a, entry);
                } else if (kind == DICTIONARY && (entry.a == SHARED_DICTIONARY || entry.a == PARTS_DICTIONARY) && listedDictionaries[entry.a] == null) {
                    listedDictionaries[entry.a] = entry;
                } else {
                    return false;
                }
            }
            this.parts.addAll(listedParts);
            this.shared.putAll(listedShared);
            this.listings.putAll(listedListings);
            System.arraycopy(listedDictionaries, 0, this.dictionaryBlocks, 0, 2);
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
            this.listings.clear();
            Arrays.fill(this.dictionaryBlocks, null);
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
                case LISTING -> this.listings.put(entry.a, entry);
                case SHARED -> {
                    if (payload.length >= HASH_BYTES) {
                        this.shared.put(HexFormat.of().formatHex(payload, 0, HASH_BYTES), entry);
                    }
                }
                case PREVIEW -> this.preview = entry;
                case EDIT -> this.edit = entry;
                case DICTIONARY -> {
                    // Written once each; should a damaged file hold another, the first is the one its blocks were packed with
                    if ((entry.a == SHARED_DICTIONARY || entry.a == PARTS_DICTIONARY) && this.dictionaryBlocks[entry.a] == null) {
                        this.dictionaryBlocks[entry.a] = entry;
                    }
                }
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
        if (kind < MANIFEST || kind > LISTING || entry.length < 0 || entry.end() > size) {
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
            case LISTING -> this.listings.put(a, entry);
            case PREVIEW -> this.preview = entry;
            case EDIT -> this.edit = entry;
            case INDEX -> this.index = entry;
            case DICTIONARY -> {
                if (a == SHARED_DICTIONARY || a == PARTS_DICTIONARY) {
                    this.dictionaryBlocks[a] = entry;
                }
            }
            default -> {
            }
        }
        return entry;
    }

    synchronized void appendShared(String hashHex, byte[] payload) throws IOException {
        this.shared.put(hashHex, this.append(SHARED, 0, 0, 0, payload));
    }

    /**
     * Writes the chunk listings of one recording in order, each whole or as the changes from the one written before it.
     * Whole the first time, whenever asked (the first snapshot after a cut or a join), and every
     * {@link ChunkListing#FULL_EVERY}th time, so a jump never reads more than that many listing blocks
     */
    static final class ListingChain {
        private ChunkListing last = null;
        private int lastSegment = -1;
        private int sinceWhole = 0;

        /** @param whole whether to store this one whole whatever came before */
        void append(Container out, int segment, ChunkListing listing, boolean whole) throws IOException {
            if (this.last == null || segment <= this.lastSegment || this.sinceWhole + 1 >= ChunkListing.FULL_EVERY) {
                whole = true;
            }
            byte[] data = whole ? listing.encodeFull() : listing.encodeChanges(this.last);
            out.append(LISTING, segment, whole ? -1 : this.lastSegment, listing.size(), packListing(data));
            this.sinceWhole = whole ? 0 : this.sinceWhole + 1;
            this.last = listing;
            this.lastSegment = segment;
        }
    }

    /** The dictionary blocks there are, in order */
    synchronized List<Entry> dictionaryEntries() {
        List<Entry> found = new ArrayList<>(2);
        for (Entry entry : this.dictionaryBlocks) {
            if (entry != null) {
                found.add(entry);
            }
        }
        return found;
    }

    /** Lists the dictionaries, segment parts, chunk listings and shared blobs written so far, for the next trailer to point at */
    synchronized void appendIndex() throws IOException {
        List<Entry> dictionaryEntries = this.dictionaryEntries();
        int count = dictionaryEntries.size() + this.parts.size() + this.listings.size() + this.shared.size();
        ByteBuffer listed = ByteBuffer.allocate(4 + count * 25 + this.shared.size() * HASH_BYTES);
        listed.putInt(count);
        for (Entry entry : dictionaryEntries) {
            listed.put(entry.kind).putInt(entry.a).putInt(entry.b).putInt(entry.c).putLong(entry.offset).putInt(entry.length);
        }
        for (Entry entry : this.parts) {
            listed.put(entry.kind).putInt(entry.a).putInt(entry.b).putInt(entry.c).putLong(entry.offset).putInt(entry.length);
        }
        for (Entry entry : this.listings.values()) {
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

    /** Closes a file that never got going and deletes it: it holds nothing but the start of a recording */
    void closeAndDelete() {
        try {
            this.channel.close();
        } catch (IOException ignored) {
            // Closing is all that can be tried; deleting below may still work
        }
        try {
            java.nio.file.Files.deleteIfExists(this.path);
        } catch (IOException ignored) {
            // Nothing more can be done about it here
        }
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
