package comedymonkey.dailies.file;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * What is inside a segment: a run of records, each a kind, a length and that many bytes. A reader that meets a kind
 * it does not know skips it by its length, which is what lets later versions add kinds.
 */
public final class Records {

    /** The clock moves on. Payload: how many ticks, as a varint */
    public static final int TICK = 1;
    /** One game packet as the game encodes it for the wire (packet id, then body) */
    public static final int PACKET = 2;
    /**
     * A game packet kept as shared data, such as a chunk. Payload: the name (32 bytes) of the shared data that is its piece
     * list, which puts it back together from bytes and other shared data ({@link PacketPieces})
     */
    public static final int SHARED_PACKET = 3;
    /** A mod's message. Payload: its id as text (varint length, UTF-8), then the whole packet as for PACKET */
    public static final int PAYLOAD = 4;
    /** Everything from here to SNAPSHOT_END describes the whole world at this tick. Payload: the tick, as a varint */
    public static final int SNAPSHOT_BEGIN = 5;
    public static final int SNAPSHOT_END = 6;
    /** The registries and connection details packets are decoded with. Payload: the name of shared data (32 bytes) */
    public static final int WORLD = 7;
    /** The recorder's own HUD state */
    public static final int HUD = 8;
    /** A frame of voice chat */
    public static final int VOICE = 9;
    /** State added by an extension. Payload: the extension's id as text, then its bytes */
    public static final int EXTENSION = 10;
    /** The packets up to BUNDLE_END arrived as one bundle and must be applied as one */
    public static final int BUNDLE_BEGIN = 11;
    public static final int BUNDLE_END = 12;
    /**
     * Straight after SNAPSHOT_BEGIN: what came before this segment does not lead up to it (a pause, another world,
     * another dimension), so playing across the boundary has to rebuild the world from this snapshot
     */
    public static final int DISCONTINUITY = 13;
    /**
     * The real time and the game time of the tick the recording has reached (specification FILE-16). Payload: two
     * numbers, each the change since the TIME record before it in the same segment; see {@link TimeIndex}
     */
    public static final int TIME = 14;
    /**
     * Inside a snapshot: the chunks the snapshot names go here, each its stored chunk ({@link #SHARED_PACKET} as it was) and
     * then any stored light on top, in the order of {@link ChunkListing}. No payload: the chunks are in the segment's listing
     * block, read with {@link RecordingFile#listing}, so a jump finds them without unpacking the records and a snapshot
     * costs only the chunks that changed since the one before
     */
    public static final int CHUNKS = 15;

    private Records() {
    }

    /** Builds a run of records in memory */
    public static final class Writer {

        private byte[] bytes = new byte[64 * 1024];
        private int size = 0;

        public int size() {
            return this.size;
        }

        public void record(int kind) {
            this.varint(kind);
            this.varint(0);
        }

        public void record(int kind, byte[] payload) {
            this.record(kind, payload, 0, payload.length);
        }

        public void record(int kind, byte[] payload, int from, int length) {
            this.varint(kind);
            this.varint(length);
            this.raw(payload, from, length);
        }

        /** A record whose payload is one number */
        public void number(int kind, int value) {
            this.varint(kind);
            this.varint(varintBytes(value));
            this.varint(value);
        }

        /** A record whose payload is a piece of text followed by bytes */
        public void named(int kind, String name, byte[] payload, int from, int length) {
            byte[] text = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            this.varint(kind);
            this.varint(varintBytes(text.length) + text.length + length);
            this.varint(text.length);
            this.raw(text, 0, text.length);
            this.raw(payload, from, length);
        }

        /** Everything written so far. The writer is empty afterwards */
        public byte[] take() {
            byte[] taken = Arrays.copyOf(this.bytes, this.size);
            this.size = 0;
            return taken;
        }

        private void raw(byte[] source, int from, int length) {
            this.reserve(length);
            System.arraycopy(source, from, this.bytes, this.size, length);
            this.size += length;
        }

        private void varint(int value) {
            this.reserve(5);
            while ((value & ~0x7F) != 0) {
                this.bytes[this.size++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            this.bytes[this.size++] = (byte) value;
        }

        private void reserve(int more) {
            if (this.size + more > this.bytes.length) {
                this.bytes = Arrays.copyOf(this.bytes, Math.max(this.bytes.length * 2, this.size + more));
            }
        }
    }

    /**
     * Walks the records of a segment. A segment that is cut short or does not add up throws a
     * {@link RecordingFormatException} at the record where it goes wrong; everything read before that is good
     */
    public static final class Reader implements AutoCloseable {

        private final InputStream in;
        private int kind;
        private byte[] payload = new byte[0];
        private int length;
        /** Where the name of a {@link #PAYLOAD} or {@link #EXTENSION} record starts and how long it is; -1 for other kinds */
        private int nameStart = -1;
        private int nameLength = 0;

        public Reader(InputStream in) {
            this.in = in;
        }

        /** Moves to the next record. False at the end of the segment */
        public boolean next() throws IOException {
            int first = this.in.read();
            if (first < 0) {
                return false;
            }
            this.kind = this.varint(first);
            this.length = BoundedReader.checkLength(this.varint(this.in.read()) & 0xFFFFFFFFL, BoundedReader.MAX_LENGTH);
            int done = 0;
            while (done < this.length) {
                if (done == this.payload.length) {
                    // Room is made as the bytes actually arrive, so a length that lies cannot ask for more than the segment holds
                    this.payload = Arrays.copyOf(this.payload, (int) Math.min(this.length, Math.max(64 * 1024L, this.payload.length * 2L)));
                }
                int read = this.in.read(this.payload, done, Math.min(this.length, this.payload.length) - done);
                if (read < 0) {
                    throw cutShort();
                }
                done += read;
            }
            this.check();
            return true;
        }

        /** What later calls take for granted about the record just read is checked once, here */
        private void check() throws RecordingFormatException {
            this.nameStart = -1;
            this.nameLength = 0;
            if (this.kind == PAYLOAD || this.kind == EXTENSION) {
                BoundedReader name = new BoundedReader(this.payload, 0, this.length);
                this.nameLength = name.length(name.readVarInt());
                this.nameStart = name.position();
            } else if (this.kind == TICK || this.kind == SNAPSHOT_BEGIN) {
                BoundedReader number = new BoundedReader(this.payload, 0, this.length);
                if (number.readVarInt() < 0 || number.remaining() != 0) {
                    throw BoundedReader.damaged("A record of the recording holds a number that cannot be so");
                }
            }
        }

        public int kind() {
            return this.kind;
        }

        /** The current record's bytes. Only the first {@link #length} are its payload, and they are overwritten by {@link #next} */
        public byte[] payload() {
            return this.payload;
        }

        public int length() {
            return this.length;
        }

        /** The payload of a record written with {@link Writer#number}. {@link #next} has checked it of a TICK or SNAPSHOT_BEGIN */
        public int number() {
            int value = 0;
            int shift = 0;
            for (int i = 0; i < this.length && shift < 35; i++) {
                value |= (this.payload[i] & 0x7F) << shift;
                shift += 7;
            }
            return value;
        }

        /** The text at the start of a record written with {@link Writer#named}: a {@link #PAYLOAD} or an {@link #EXTENSION} */
        public String name() {
            this.requireName();
            return new String(this.payload, this.nameStart, this.nameLength, java.nio.charset.StandardCharsets.UTF_8);
        }

        /** Where the bytes after the name start */
        public int bodyStart() {
            this.requireName();
            return this.nameStart + this.nameLength;
        }

        private void requireName() {
            if (this.nameStart < 0) {
                throw new IllegalStateException("A record of kind " + this.kind + " has no name");
            }
        }

        private int varint(int first) throws IOException {
            int value = 0;
            int shift = 0;
            int current = first;
            while (true) {
                if (current < 0) {
                    throw cutShort();
                }
                value |= (current & 0x7F) << shift;
                if ((current & 0x80) == 0) {
                    return value;
                }
                shift += 7;
                if (shift >= 35) {
                    throw BoundedReader.damaged("A record of the recording has an impossible number");
                }
                current = this.in.read();
            }
        }

        private static RecordingFormatException cutShort() {
            return BoundedReader.damaged("The segment ends in the middle of a record");
        }

        @Override
        public void close() throws IOException {
            this.in.close();
        }
    }

    private static int varintBytes(int value) {
        int bytes = 1;
        while ((value & ~0x7F) != 0) {
            bytes++;
            value >>>= 7;
        }
        return bytes;
    }

}
