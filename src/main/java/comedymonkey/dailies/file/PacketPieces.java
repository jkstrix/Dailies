package comedymonkey.dailies.file;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * A packet stored in pieces (specification FILE-5). The shared data a {@link Records#SHARED_PACKET} record names is a
 * piece list: the packet is its pieces put end to end. A piece is bytes written out in the list itself, or the name
 * of other shared data whose bytes go there (a part), or the name of another piece list whose packet goes there. A
 * chunk is split so that each of its sections, each section's sky light and block light, its heightmaps and its block
 * entities are parts of their own: a part that has not changed keeps its name, so a door that opens stores one section
 * again, not the whole chunk, and every all-dark or all-lit stretch of light in a recording is one part.
 * <p>
 * A piece list is a run of pieces, each a kind byte and then: for {@link #BYTES} a varint length and that many bytes,
 * for {@link #PART} and {@link #LIST} a 32-byte name. A list inside a list may hold only bytes and parts, so a packet
 * is never more than two lists deep. Nothing here knows the game: how a packet is cut up is the caller's business,
 * and putting it back together only ever puts bytes end to end.
 */
public final class PacketPieces {

    /** Bytes written out in the list. Then: a varint length and the bytes */
    public static final int BYTES = 0;
    /** The bytes of other shared data. Then: its name */
    public static final int PART = 1;
    /** The packet another piece list makes. Then: its name */
    public static final int LIST = 2;

    /**
     * The most a packet put back together may come to. A game packet is at most 8 MB unpacked; a list that names the same
     * part over and over could otherwise ask for any amount
     */
    public static final int MAX_PACKET = 16 * 1024 * 1024;

    private PacketPieces() {
    }

    /** Shared data to store: {@code length} bytes of {@code bytes} from {@code from}, under its name, the SHA-256 of those bytes */
    public record Blob(byte[] name, byte[] bytes, int from, int length) {
        public String hex() {
            return HexFormat.of().formatHex(this.name);
        }

        /** The bytes on their own */
        public byte[] copy() {
            return Arrays.copyOfRange(this.bytes, this.from, this.from + this.length);
        }
    }

    /**
     * A packet cut into pieces
     *
     * @param name  the name of its piece list, which is what a record names
     * @param blobs everything to store for it, each list after the parts it names and the packet's own list last, so that
     *              shared data that is there always has what it names there too
     */
    public record Split(byte[] name, List<Blob> blobs) {
        /** How many bytes it comes to before packing, all told */
        public long bytes() {
            long sum = 0;
            for (Blob blob : this.blobs) {
                sum += blob.length();
            }
            return sum;
        }
    }

    /** A packet kept as one piece of bytes in its list: stored once however often it is named, as any shared data, but in no parts */
    public static Split whole(byte[] packet) {
        Builder builder = new Builder();
        builder.bytes(packet, 0, packet.length);
        return builder.finish();
    }

    /** Builds a piece list, piece by piece. Bytes given one after another become one piece */
    public static final class Builder {

        private final List<Blob> blobs;
        private final boolean inner;
        private byte[] list = new byte[256];
        private int size = 0;
        private int literalFrom = -1;

        public Builder() {
            this(new ArrayList<>(), false);
        }

        private Builder(List<Blob> blobs, boolean inner) {
            this.blobs = blobs;
            this.inner = inner;
        }

        /** Bytes that go in the list as they are: a length before a run of parts, say */
        public Builder bytes(byte[] source, int from, int length) {
            if (this.literalFrom < 0) {
                this.literalFrom = this.size;
                // Room for the kind and a length of up to five bytes, put right when the run ends
                this.reserve(6);
                this.size += 6;
            }
            this.raw(source, from, length);
            return this;
        }

        /** A stretch that is stored as shared data of its own and named here */
        public Builder part(byte[] source, int from, int length) {
            byte[] name = Packing.hash(source, from, length);
            this.blobs.add(new Blob(name, source, from, length));
            this.named(PART, name);
            return this;
        }

        /**
         * A run of pieces gathered in a list of its own, stored and named here: the sections of a chunk, say, which change
         * one at a time while the rest of the chunk is named again as it was
         */
        public Builder list(java.util.function.Consumer<Builder> fill) {
            if (this.inner) {
                throw new IllegalStateException("A list inside a list cannot hold another");
            }
            Builder inner = new Builder(this.blobs, true);
            fill.accept(inner);
            byte[] bytes = inner.take();
            byte[] name = Packing.hash(bytes);
            this.blobs.add(new Blob(name, bytes, 0, bytes.length));
            this.named(LIST, name);
            return this;
        }

        /** The packet's piece list, stored last */
        public Split finish() {
            byte[] bytes = this.take();
            byte[] name = Packing.hash(bytes);
            this.blobs.add(new Blob(name, bytes, 0, bytes.length));
            return new Split(name, List.copyOf(this.blobs));
        }

        private void named(int kind, byte[] name) {
            this.endLiteral();
            this.reserve(1 + name.length);
            this.list[this.size++] = (byte) kind;
            System.arraycopy(name, 0, this.list, this.size, name.length);
            this.size += name.length;
        }

        private byte[] take() {
            this.endLiteral();
            return Arrays.copyOf(this.list, this.size);
        }

        /** Writes the kind and length of the run of bytes being gathered in front of it, closing up the room left */
        private void endLiteral() {
            if (this.literalFrom < 0) {
                return;
            }
            int bodyFrom = this.literalFrom + 6;
            int length = this.size - bodyFrom;
            byte[] head = new byte[6];
            int at = 0;
            head[at++] = (byte) BYTES;
            int value = length;
            while ((value & ~0x7F) != 0) {
                head[at++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            head[at++] = (byte) value;
            System.arraycopy(head, 0, this.list, this.literalFrom, at);
            System.arraycopy(this.list, bodyFrom, this.list, this.literalFrom + at, length);
            this.size = this.literalFrom + at + length;
            this.literalFrom = -1;
        }

        private void raw(byte[] source, int from, int length) {
            this.reserve(length);
            System.arraycopy(source, from, this.list, this.size, length);
            this.size += length;
        }

        private void reserve(int more) {
            if (this.size + more > this.list.length) {
                this.list = Arrays.copyOf(this.list, Math.max(this.list.length * 2, this.size + more));
            }
        }
    }

    /** Where the shared data a piece list names comes from */
    public interface Store {
        byte[] get(String name) throws IOException;
    }

    /** One name a piece list holds: a part's, or a list's */
    public record Reference(int kind, String name) {
    }

    /**
     * The names a piece list holds, in order, with repeats, not looking inside the lists it names
     *
     * @throws RecordingFormatException when it is not a piece list
     */
    public static List<Reference> references(byte[] list) throws RecordingFormatException {
        List<Reference> names = new ArrayList<>();
        BoundedReader in = new BoundedReader(list);
        while (in.remaining() > 0) {
            int kind = in.readByte();
            if (kind == BYTES) {
                in.skip(in.readVarInt());
            } else if (kind == PART || kind == LIST) {
                names.add(new Reference(kind, HexFormat.of().formatHex(in.readBytes(Container.HASH_BYTES))));
            } else {
                throw BoundedReader.damaged("A stored packet holds a piece of a kind there is not");
            }
        }
        return names;
    }

    /**
     * The packet a piece list makes
     *
     * @throws RecordingFormatException when it is not a piece list, nests lists too deep or comes to more than {@link #MAX_PACKET}
     */
    public static byte[] rebuild(byte[] list, Store shared) throws IOException {
        Output out = new Output();
        put(list, shared, out, false);
        return Arrays.copyOf(out.bytes, out.size);
    }

    private static final class Output {
        byte[] bytes = new byte[64 * 1024];
        int size = 0;

        void add(byte[] source, int from, int length) throws RecordingFormatException {
            if ((long) this.size + length > MAX_PACKET) {
                throw BoundedReader.damaged("A stored packet comes to more than any packet can be");
            }
            if (this.size + length > this.bytes.length) {
                this.bytes = Arrays.copyOf(this.bytes, Math.max(this.bytes.length * 2, this.size + length));
            }
            System.arraycopy(source, from, this.bytes, this.size, length);
            this.size += length;
        }
    }

    private static void put(byte[] list, Store shared, Output out, boolean inner) throws IOException {
        BoundedReader in = new BoundedReader(list);
        while (in.remaining() > 0) {
            int kind = in.readByte();
            if (kind == BYTES) {
                int length = in.length(in.readVarInt());
                out.add(list, in.position(), length);
                in.skip(length);
            } else if (kind == PART) {
                byte[] part = shared.get(HexFormat.of().formatHex(in.readBytes(Container.HASH_BYTES)));
                out.add(part, 0, part.length);
            } else if (kind == LIST && !inner) {
                put(shared.get(HexFormat.of().formatHex(in.readBytes(Container.HASH_BYTES))), shared, out, true);
            } else {
                throw BoundedReader.damaged(kind == LIST ? "A stored packet nests its pieces deeper than they can be" : "A stored packet holds a piece of a kind there is not");
            }
        }
    }

}
