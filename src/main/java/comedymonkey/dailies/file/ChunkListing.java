package comedymonkey.dailies.file;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The chunks one snapshot names (specification FILE-5): for each chunk, where it is, the stored chunk it is, and, for a
 * chunk whose blocks are as stored but whose light changed because a block next door did, the stored light that goes on
 * top of it. A snapshot used to name its chunks in its own records, a thousand 32-byte names every two seconds, nearly
 * all the same as the snapshot before; names are random and nothing packs them smaller. Now the names live in blocks of
 * their own ({@link Container#LISTING}): every {@link #FULL_EVERY}th snapshot stores its whole listing, and the snapshots
 * between store only what changed since the one before, which a jump puts together again in memory.
 * <p>
 * Chunks are kept in one order, by x and then z, which is also the order a snapshot read gives them to the game; nothing
 * in the game depends on the order of whole chunks. A position is packed as the game packs it: x in the low 32 bits, z
 * in the high. A listing never changes once made.
 */
public final class ChunkListing {

    /**
     * A whole listing is stored every this many snapshots, and each snapshot between stores what changed since the one
     * before it. A jump reads one whole listing and at most this many less one sets of changes. A constant, not a setting:
     * the cost of a jump that has nothing cached depends on it
     */
    public static final int FULL_EVERY = 10;

    public static final ChunkListing EMPTY = new ChunkListing(new long[0], new byte[0], new int[0], new byte[0]);

    private static final int NAME = Container.HASH_BYTES;
    private static final int LIT = 1;
    /**
     * In a set of changes: the chunk keeps the stored chunk it had, and only its light is new (or gone), so its name is left
     * out. A quarter of the chunks that change between two snapshots change only so, from a block next door
     */
    private static final int SAME_CHUNK = 2;

    /** Packed positions, in order */
    private final long[] keys;
    /** The stored chunk of each, {@link #NAME} bytes each */
    private final byte[] chunks;
    /** Where each chunk's stored light is in {@link #lights}, counted in names, or -1 for none */
    private final int[] lightAt;
    private final byte[] lights;

    private ChunkListing(long[] keys, byte[] chunks, int[] lightAt, byte[] lights) {
        this.keys = keys;
        this.chunks = chunks;
        this.lightAt = lightAt;
        this.lights = lights;
    }

    public static int x(long key) {
        return (int) key;
    }

    public static int z(long key) {
        return (int) (key >>> 32);
    }

    /** A position packed as the game packs a chunk's */
    public static long key(int x, int z) {
        return (x & 0xFFFFFFFFL) | (z & 0xFFFFFFFFL) << 32;
    }

    /** The order chunks are kept in: by x, then by z */
    static int compare(long a, long b) {
        int byX = Integer.compare(x(a), x(b));
        return byX != 0 ? byX : Integer.compare(z(a), z(b));
    }

    public int size() {
        return this.keys.length;
    }

    public boolean isEmpty() {
        return this.keys.length == 0;
    }

    public long key(int index) {
        return this.keys[index];
    }

    /** The name of the stored chunk at an index */
    public byte[] chunk(int index) {
        return Arrays.copyOfRange(this.chunks, index * NAME, (index + 1) * NAME);
    }

    public String chunkHex(int index) {
        return HexFormat.of().formatHex(this.chunks, index * NAME, (index + 1) * NAME);
    }

    /** Whether the chunk at an index has stored light on top of it */
    public boolean lit(int index) {
        return this.lightAt[index] >= 0;
    }

    /** The name of the stored light on top of the chunk at an index, or null when it has the stored chunk's own */
    public @Nullable byte[] light(int index) {
        int at = this.lightAt[index];
        return at < 0 ? null : Arrays.copyOfRange(this.lights, at * NAME, (at + 1) * NAME);
    }

    public @Nullable String lightHex(int index) {
        int at = this.lightAt[index];
        return at < 0 ? null : HexFormat.of().formatHex(this.lights, at * NAME, (at + 1) * NAME);
    }

    /** Where a chunk is in the listing, or a negative number when it is not there */
    public int find(long key) {
        int lo = 0;
        int hi = this.keys.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int order = compare(this.keys[mid], key);
            if (order < 0) {
                lo = mid + 1;
            } else if (order > 0) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        return -(lo + 1);
    }

    /** Every name the listing holds, stored chunks and stored light, each as often as it is named */
    public List<String> names() {
        List<String> names = new ArrayList<>(this.keys.length + this.lights.length / NAME);
        for (int i = 0; i < this.keys.length; i++) {
            names.add(this.chunkHex(i));
            String light = this.lightHex(i);
            if (light != null) {
                names.add(light);
            }
        }
        return names;
    }

    /** About how much memory the listing takes, for a cache to count */
    long bytes() {
        return 64L + this.keys.length * 12L + this.chunks.length + this.lights.length;
    }

    private boolean sameEntry(int index, ChunkListing other, int otherIndex) {
        if (!Arrays.equals(this.chunks, index * NAME, (index + 1) * NAME, other.chunks, otherIndex * NAME, (otherIndex + 1) * NAME)) {
            return false;
        }
        int light = this.lightAt[index];
        int otherLight = other.lightAt[otherIndex];
        if (light < 0 || otherLight < 0) {
            return light < 0 && otherLight < 0;
        }
        return Arrays.equals(this.lights, light * NAME, (light + 1) * NAME, other.lights, otherLight * NAME, (otherLight + 1) * NAME);
    }

    /** The positions of the chunks this listing and another name differently, or only one of them has, in order */
    public long[] differences(ChunkListing other) {
        long[] found = new long[Math.max(16, Math.min(this.keys.length + other.keys.length, 1024))];
        int count = 0;
        int i = 0;
        int j = 0;
        while (i < this.keys.length || j < other.keys.length) {
            long key;
            int order = i >= this.keys.length ? 1 : j >= other.keys.length ? -1 : compare(this.keys[i], other.keys[j]);
            if (order < 0) {
                key = this.keys[i++];
            } else if (order > 0) {
                key = other.keys[j++];
            } else {
                boolean same = this.sameEntry(i, other, j);
                key = this.keys[i];
                i++;
                j++;
                if (same) {
                    continue;
                }
            }
            if (count == found.length) {
                found = Arrays.copyOf(found, found.length * 2);
            }
            found[count++] = key;
        }
        return Arrays.copyOf(found, count);
    }

    // --- Stored form ---

    /**
     * The whole listing, as a {@link Container#LISTING} block holds it before packing: how many chunks, then each chunk's
     * position as the steps in x and z from the one before (zigzag varints, nearly always a byte each), a byte of flags
     * ({@link #LIT}, {@link #SAME_CHUNK}), the stored chunk's name and, when lit, the stored light's; then how many chunks have gone, which for
     * a whole listing is none
     */
    public byte[] encodeFull() {
        Out out = new Out(16 + this.keys.length * (3 + NAME) + this.lights.length);
        out.varint(this.keys.length);
        long previous = 0;
        for (int i = 0; i < this.keys.length; i++) {
            this.entry(out, i, previous, false);
            previous = this.keys[i];
        }
        out.varint(0);
        return out.take();
    }

    /**
     * What changed from an earlier listing to this one, in the same form as {@link #encodeFull}: the chunks this one has
     * that the earlier did not, or names otherwise, then the positions of the chunks that have gone
     */
    public byte[] encodeChanges(ChunkListing from) {
        Out out = new Out(64);
        Out gone = new Out(16);
        int set = 0;
        int goneCount = 0;
        long previousSet = 0;
        long previousGone = 0;
        int i = 0;
        int j = 0;
        while (i < this.keys.length || j < from.keys.length) {
            int order = i >= this.keys.length ? 1 : j >= from.keys.length ? -1 : compare(this.keys[i], from.keys[j]);
            if (order > 0) {
                position(gone, from.keys[j], previousGone);
                previousGone = from.keys[j];
                goneCount++;
                j++;
                continue;
            }
            if (order < 0 || !this.sameEntry(i, from, j)) {
                boolean sameChunk = order == 0 && Arrays.equals(this.chunks, i * NAME, (i + 1) * NAME, from.chunks, j * NAME, (j + 1) * NAME);
                this.entry(out, i, previousSet, sameChunk);
                previousSet = this.keys[i];
                set++;
            }
            if (order == 0) {
                j++;
            }
            i++;
        }
        Out all = new Out(out.size + gone.size + 10);
        all.varint(set);
        all.raw(out.bytes, 0, out.size);
        all.varint(goneCount);
        all.raw(gone.bytes, 0, gone.size);
        return all.take();
    }

    private void entry(Out out, int index, long previous, boolean sameChunk) {
        position(out, this.keys[index], previous);
        int light = this.lightAt[index];
        out.raw(new byte[]{(byte) ((light >= 0 ? LIT : 0) | (sameChunk ? SAME_CHUNK : 0))}, 0, 1);
        if (!sameChunk) {
            out.raw(this.chunks, index * NAME, NAME);
        }
        if (light >= 0) {
            out.raw(this.lights, light * NAME, NAME);
        }
    }

    private static void position(Out out, long key, long previous) {
        out.zigzag(x(key) - x(previous));
        out.zigzag(z(key) - z(previous));
    }

    /**
     * A listing read back from what {@link #encodeFull} or {@link #encodeChanges} made
     *
     * @param base the listing the changes are from, or null for a whole listing
     * @throws RecordingFormatException when the bytes do not add up, are out of order, or speak of chunks the base does not have
     */
    public static ChunkListing decode(byte[] bytes, @Nullable ChunkListing base) throws RecordingFormatException {
        BoundedReader in = new BoundedReader(bytes);
        int setCount = in.length(in.readVarInt() & 0xFFFFFFFFL);
        // Each chunk takes at least its two steps and its flags, so a count that lies cannot ask for much
        BoundedReader.checkLength(setCount * 3L, in.remaining());
        long[] keys = new long[setCount];
        byte[] chunks = new byte[setCount * NAME];
        int[] lightAt = new int[setCount];
        byte[] lights = new byte[0];
        int lit = 0;
        long previous = 0;
        for (int i = 0; i < setCount; i++) {
            long key = key(x(previous) + unzigzag(in.readVarInt()), z(previous) + unzigzag(in.readVarInt()));
            if (i > 0 && compare(previous, key) >= 0) {
                throw BoundedReader.damaged("A listing of chunks in the recording is out of order");
            }
            keys[i] = key;
            previous = key;
            int flags = in.readByte();
            if ((flags & SAME_CHUNK) != 0) {
                int had = base == null ? -1 : base.find(key);
                if (had < 0) {
                    throw BoundedReader.damaged("A listing of chunks in the recording does not follow on from the one before it");
                }
                System.arraycopy(base.chunks, had * NAME, chunks, i * NAME, NAME);
            } else {
                System.arraycopy(in.readBytes(NAME), 0, chunks, i * NAME, NAME);
            }
            if ((flags & LIT) != 0) {
                if (lights.length < (lit + 1) * NAME) {
                    lights = Arrays.copyOf(lights, Math.max(NAME * 8, lights.length * 2));
                }
                System.arraycopy(in.readBytes(NAME), 0, lights, lit * NAME, NAME);
                lightAt[i] = lit++;
            } else {
                lightAt[i] = -1;
            }
        }
        int goneCount = in.length(in.readVarInt() & 0xFFFFFFFFL);
        BoundedReader.checkLength(goneCount * 2L, in.remaining());
        long[] gone = new long[goneCount];
        previous = 0;
        for (int i = 0; i < goneCount; i++) {
            long key = key(x(previous) + unzigzag(in.readVarInt()), z(previous) + unzigzag(in.readVarInt()));
            if (i > 0 && compare(previous, key) >= 0) {
                throw BoundedReader.damaged("A listing of chunks in the recording is out of order");
            }
            gone[i] = key;
            previous = key;
        }
        if (in.remaining() != 0) {
            throw BoundedReader.damaged("A listing of chunks in the recording has bytes left over");
        }
        ChunkListing set = new ChunkListing(keys, chunks, lightAt, Arrays.copyOf(lights, lit * NAME));
        if (base == null) {
            if (goneCount != 0) {
                throw BoundedReader.damaged("A whole listing of chunks in the recording says chunks have gone");
            }
            return set;
        }
        return base.with(set, gone);
    }

    /** This listing with the chunks of another put in, over any at the same place, and the chunks at the positions given taken out */
    private ChunkListing with(ChunkListing set, long[] gone) throws RecordingFormatException {
        Builder builder = new Builder(this.keys.length + set.keys.length);
        int i = 0;
        int j = 0;
        int g = 0;
        while (i < this.keys.length || j < set.keys.length) {
            int order = i >= this.keys.length ? 1 : j >= set.keys.length ? -1 : compare(this.keys[i], set.keys[j]);
            if (order > 0) {
                builder.add(set, j++);
                continue;
            }
            long key = this.keys[i];
            if (order == 0) {
                builder.add(set, j++);
            } else if (g < gone.length && gone[g] == key) {
                g++;
            } else {
                builder.add(this, i);
            }
            i++;
        }
        if (g != gone.length) {
            // A chunk said to have gone that was never there, or one both set and gone: these are not the changes from this listing
            throw BoundedReader.damaged("A listing of chunks in the recording does not follow on from the one before it");
        }
        return builder.finish();
    }

    /** Builds a listing in order from listings already in order */
    private static final class Builder {
        private long[] keys;
        private byte[] chunks;
        private int[] lightAt;
        private byte[] lights = new byte[0];
        private int count = 0;
        private int lit = 0;

        Builder(int room) {
            this.keys = new long[room];
            this.chunks = new byte[room * NAME];
            this.lightAt = new int[room];
        }

        void add(ChunkListing from, int index) {
            this.keys[this.count] = from.keys[index];
            System.arraycopy(from.chunks, index * NAME, this.chunks, this.count * NAME, NAME);
            int light = from.lightAt[index];
            if (light >= 0) {
                if (this.lights.length < (this.lit + 1) * NAME) {
                    this.lights = Arrays.copyOf(this.lights, Math.max(NAME * 8, this.lights.length * 2));
                }
                System.arraycopy(from.lights, light * NAME, this.lights, this.lit * NAME, NAME);
                this.lightAt[this.count] = this.lit++;
            } else {
                this.lightAt[this.count] = -1;
            }
            this.count++;
        }

        ChunkListing finish() {
            return new ChunkListing(Arrays.copyOf(this.keys, this.count), Arrays.copyOf(this.chunks, this.count * NAME), Arrays.copyOf(this.lightAt, this.count),
                Arrays.copyOf(this.lights, this.lit * NAME));
        }
    }

    /**
     * Gathers a listing as a snapshot names its chunks, in any order. A chunk named twice keeps the second. Not thread safe
     */
    public static final class Gatherer {
        private final Map<Long, byte[][]> entries = new TreeMap<>(ChunkListing::compare);

        /**
         * @param chunk the name of the stored chunk
         * @param light the name of the stored light that goes on top of it, or null
         */
        public void put(long key, byte[] chunk, @Nullable byte[] light) {
            if (chunk.length != NAME || light != null && light.length != NAME) {
                throw new IllegalArgumentException("A name of shared data is " + NAME + " bytes");
            }
            this.entries.put(key, new byte[][]{chunk, light});
        }

        public int size() {
            return this.entries.size();
        }

        public ChunkListing finish() {
            Builder builder = new Builder(this.entries.size());
            for (Map.Entry<Long, byte[][]> entry : this.entries.entrySet()) {
                byte[] chunk = entry.getValue()[0];
                byte[] light = entry.getValue()[1];
                builder.keys[builder.count] = entry.getKey();
                System.arraycopy(chunk, 0, builder.chunks, builder.count * NAME, NAME);
                if (light != null) {
                    if (builder.lights.length < (builder.lit + 1) * NAME) {
                        builder.lights = Arrays.copyOf(builder.lights, Math.max(NAME * 8, builder.lights.length * 2));
                    }
                    System.arraycopy(light, 0, builder.lights, builder.lit * NAME, NAME);
                    builder.lightAt[builder.count] = builder.lit++;
                } else {
                    builder.lightAt[builder.count] = -1;
                }
                builder.count++;
            }
            return builder.finish();
        }
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ChunkListing listing) || listing.keys.length != this.keys.length) {
            return false;
        }
        for (int i = 0; i < this.keys.length; i++) {
            if (this.keys[i] != listing.keys[i] || !this.sameEntry(i, listing, i)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(this.keys) * 31 + Arrays.hashCode(this.chunks);
    }

    @Override
    public String toString() {
        return "ChunkListing[" + this.keys.length + " chunks, " + this.lights.length / NAME + " lit]";
    }

    private static int unzigzag(int value) {
        return (value >>> 1) ^ -(value & 1);
    }

    /** A growing run of bytes */
    private static final class Out {
        byte[] bytes;
        int size = 0;

        Out(int room) {
            this.bytes = new byte[Math.max(16, room)];
        }

        void raw(byte[] source, int from, int length) {
            this.reserve(length);
            System.arraycopy(source, from, this.bytes, this.size, length);
            this.size += length;
        }

        void varint(int value) {
            this.reserve(5);
            while ((value & ~0x7F) != 0) {
                this.bytes[this.size++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            this.bytes[this.size++] = (byte) value;
        }

        void zigzag(int value) {
            this.varint((value << 1) ^ (value >> 31));
        }

        byte[] take() {
            return Arrays.copyOf(this.bytes, this.size);
        }

        private void reserve(int more) {
            if (this.size + more > this.bytes.length) {
                this.bytes = Arrays.copyOf(this.bytes, Math.max(this.bytes.length * 2, this.size + more));
            }
        }
    }

}
