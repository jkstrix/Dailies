package comedymonkey.dailies.file;

import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdDictDecompress;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Compression and content hashing for the blocks of a recording. Packed data is the original length as four bytes,
 * then one Zstandard frame of the data, made with the recording's dictionary for that kind of block (or none). The frame
 * leaves out its own length, its dictionary's id and a checksum: the length is the four bytes in front, the dictionary
 * is the one the recording carries ({@link Container#DICTIONARY}), and the block has a checksum of its own.
 */
final class Packing {

    /**
     * Level 9 of 22. Measured on real recordings cut into section pieces (ZstdBenchmarkTest), level 9 with a trained
     * dictionary packs to 86% of what deflate level 3 did and unpacks more than twice as fast, and still packs at some
     * 80 MB a second on the recording's own writer thread
     */
    static final int LEVEL = 9;

    /**
     * The most one block may unpack to. A block says how long it is unpacked, and a crafted or damaged file could say
     * anything, so a length above this is damage and nothing is made for it. Zstandard can shrink a run of zeros (a
     * section of air, a chunk of all-dark light) to a few bytes, so no ratio of packed to unpacked bytes can be trusted the
     * way it could with deflate; this is a plain ceiling instead, well above anything Dailies writes as one block: a
     * packet is at most {@link PacketPieces#MAX_PACKET}, a segment part about a megabyte past the last record put in it,
     * and a snapshot's part holds the world's records besides its chunks, which are stored apart. Packing refuses more
     */
    static final int MAX_UNPACKED = 4 * PacketPieces.MAX_PACKET;

    /** The most a dictionary stored in a recording may be. Dailies' own are 64 KB; zstd's trainer makes nothing near this */
    static final int MAX_DICTIONARY = 1024 * 1024;

    private static final ThreadLocal<MessageDigest> DIGEST = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    });

    // The native compressors are kept and used again, a few of each: the recording's writer packs thousands of blobs and
    // playback unpacks dozens to a chunk, and making one for each was most of what it cost. A pool rather than one per
    // thread, because their memory is outside Java's and a thread that ends would never give its own back
    private static final Pool<ZstdCompressCtx> PACKERS = new Pool<>(() -> {
        ZstdCompressCtx packer = new ZstdCompressCtx();
        packer.setLevel(LEVEL);
        packer.setContentSize(false);
        packer.setDictID(false);
        packer.setChecksum(false);
        return packer;
    });
    private static final Pool<ZstdDecompressCtx> UNPACKERS = new Pool<>(ZstdDecompressCtx::new);

    private Packing() {
    }

    /**
     * A dictionary for packing one kind of block, known by the SHA-256 of its bytes. Recordings that carry the same
     * dictionary share one, and its native forms are made only when first needed: a recording only played never needs
     * the one for packing.
     */
    static final class Dictionary {
        private static final Map<String, Dictionary> KNOWN = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Dictionary> eldest) {
                // Dropped ones are freed by the garbage collector once no recording holds them
                return this.size() > 16;
            }
        };

        final byte[] bytes;
        final String name;
        private ZstdDictCompress forPacking = null;
        private ZstdDictDecompress forUnpacking = null;

        private Dictionary(byte[] bytes, String name) {
            this.bytes = bytes;
            this.name = name;
        }

        /** The dictionary with these bytes, or null for none (no bytes) */
        static @Nullable Dictionary of(byte[] bytes) {
            if (bytes.length == 0) {
                return null;
            }
            String name = HexFormat.of().formatHex(hash(bytes));
            synchronized (KNOWN) {
                return KNOWN.computeIfAbsent(name, key -> new Dictionary(bytes.clone(), key));
            }
        }

        synchronized ZstdDictCompress forPacking() {
            if (this.forPacking == null) {
                ZstdNative.load();
                this.forPacking = new ZstdDictCompress(this.bytes, LEVEL);
            }
            return this.forPacking;
        }

        synchronized ZstdDictDecompress forUnpacking() {
            if (this.forUnpacking == null) {
                ZstdNative.load();
                this.forUnpacking = new ZstdDictDecompress(this.bytes);
            }
            return this.forUnpacking;
        }

        /** Whether data packed with one can be unpacked with the other: no dictionary is the same as no dictionary */
        static boolean same(@Nullable Dictionary a, @Nullable Dictionary b) {
            return a == null ? b == null : b != null && a.name.equals(b.name);
        }
    }

    /**
     * The two dictionaries of a recording: one for shared data (the pieces of chunks, mostly), one for segment parts (the
     * stream of packets). A recording carries the ones it was written with and is always read with those.
     */
    record Dictionaries(@Nullable Dictionary shared, @Nullable Dictionary parts) {

        static final Dictionaries NONE = new Dictionaries(null, null);

        /** Where the mod's own are, beside its classes */
        static final String SHARED_RESOURCE = "/assets/dailies/zstd/shared.dict";
        static final String PARTS_RESOURCE = "/assets/dailies/zstd/parts.dict";

        private static Dictionaries current = null;

        /**
         * The dictionaries new recordings are written with: the ones packed into the mod, trained on real recordings
         * (gradlew trainDictionaries)
         */
        static synchronized Dictionaries current() {
            if (current == null) {
                current = new Dictionaries(Dictionary.of(resource(SHARED_RESOURCE)), Dictionary.of(resource(PARTS_RESOURCE)));
            }
            return current;
        }

        private static byte[] resource(String name) {
            try (InputStream in = Packing.class.getResourceAsStream(name)) {
                if (in == null) {
                    throw new IllegalStateException("This build of Dailies is missing " + name + ", a dictionary recordings are packed with");
                }
                return in.readAllBytes();
            } catch (IOException e) {
                throw new IllegalStateException("The dictionary " + name + " packed into Dailies cannot be read", e);
            }
        }

        boolean same(Dictionaries other) {
            return Dictionary.same(this.shared, other.shared) && Dictionary.same(this.parts, other.parts);
        }
    }

    /**
     * @param dictionary the recording's dictionary for this kind of block, or null for none
     * @param prefix     bytes to put in front of the packed data, uncompressed (may be empty)
     * @throws IllegalArgumentException when the data is more than {@link #MAX_UNPACKED}, which could never be read back
     */
    static byte[] pack(@Nullable Dictionary dictionary, byte[] prefix, byte[] data, int from, int length) {
        if (length > MAX_UNPACKED) {
            throw new IllegalArgumentException("A block of " + length + " bytes is more than a recording may hold as one (" + MAX_UNPACKED + ")");
        }
        ZstdNative.load();
        ZstdDictCompress table = dictionary == null ? null : dictionary.forPacking();
        ZstdCompressCtx packer = PACKERS.take();
        try {
            packer.loadDict(table);
            byte[] out = new byte[prefix.length + 4 + (int) com.github.luben.zstd.Zstd.compressBound(length)];
            System.arraycopy(prefix, 0, out, 0, prefix.length);
            ByteBuffer.wrap(out, prefix.length, 4).putInt(length);
            int at = prefix.length + 4;
            int made = packer.compressByteArray(out, at, out.length - at, data, from, length);
            return java.util.Arrays.copyOf(out, at + made);
        } finally {
            PACKERS.give(packer);
        }
    }

    /**
     * @param dictionary the dictionary the block was packed with: the recording's own for its kind, never the mod's
     * @param from       where the packed data starts in the payload, after any prefix
     * @throws RecordingFormatException when the payload is not packed data, says it unpacks to more than any block may, or
     *                                  does not unpack to exactly the length it says
     */
    static byte[] unpack(@Nullable Dictionary dictionary, byte[] payload, int from) throws RecordingFormatException {
        if (from < 0 || from > payload.length) {
            throw damaged();
        }
        BoundedReader in = new BoundedReader(payload, from, payload.length - from);
        int length = BoundedReader.checkLength(in.readInt(), MAX_UNPACKED);
        if (in.remaining() == 0) {
            // Even nothing packs to a frame of a few bytes
            throw damaged();
        }
        ZstdNative.load();
        ZstdDictDecompress table = dictionary == null ? null : dictionary.forUnpacking();
        byte[] data = new byte[length];
        ZstdDecompressCtx unpacker = UNPACKERS.take();
        try {
            unpacker.loadDict(table);
            int made = unpacker.decompressByteArray(data, 0, length, payload, from + 4, in.remaining());
            if (made != length) {
                throw damaged();
            }
            return data;
        } catch (RuntimeException e) {
            // zstd says what is wrong (a frame cut short, more data than the length said, not a frame at all) as a ZstdException
            throw damaged();
        } finally {
            UNPACKERS.give(unpacker);
        }
    }

    /** The SHA-256 of the data, which is what shared data is named by */
    static byte[] hash(byte[] data) {
        // digest() leaves it ready for the next
        return DIGEST.get().digest(data);
    }

    /** The SHA-256 of a stretch of an array, the same as of a copy of just that stretch */
    static byte[] hash(byte[] data, int from, int length) {
        MessageDigest digest = DIGEST.get();
        digest.update(data, from, length);
        return digest.digest();
    }

    private static RecordingFormatException damaged() {
        return new RecordingFormatException(RecordingFormatException.Reason.DAMAGED, "A block of the recording cannot be unpacked");
    }

    /** A few native compressors to use again. One beyond what is kept is closed at once, giving its memory back */
    private static final class Pool<T extends java.io.Closeable> {
        private static final int KEPT = 8;
        private final ArrayDeque<T> idle = new ArrayDeque<>();
        private final Supplier<T> make;

        Pool(Supplier<T> make) {
            this.make = make;
        }

        T take() {
            synchronized (this.idle) {
                T kept = this.idle.pollFirst();
                if (kept != null) {
                    return kept;
                }
            }
            return this.make.get();
        }

        void give(T used) {
            synchronized (this.idle) {
                if (this.idle.size() < KEPT) {
                    this.idle.addFirst(used);
                    return;
                }
            }
            try {
                used.close();
            } catch (IOException ignored) {
                // Nothing to be done; it was only being let go
            }
        }
    }

}
