package comedymonkey.dailies.file;

import java.io.EOFException;
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
    /** A game packet kept as shared data, such as a chunk. Payload: the name of the shared data (32 bytes) */
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

    /** Walks the records of a segment */
    public static final class Reader implements AutoCloseable {

        private final InputStream in;
        private int kind;
        private byte[] payload = new byte[0];
        private int length;

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
            this.length = this.varint(this.in.read());
            if (this.length < 0) {
                throw new RecordingFormatException(RecordingFormatException.Reason.DAMAGED, "A record of the recording has an impossible length");
            }
            if (this.payload.length < this.length) {
                this.payload = new byte[Math.max(this.length, this.payload.length * 2)];
            }
            int done = 0;
            while (done < this.length) {
                int read = this.in.read(this.payload, done, this.length - done);
                if (read < 0) {
                    throw new EOFException("The segment ends in the middle of a record");
                }
                done += read;
            }
            return true;
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

        /** The payload of a record written with {@link Writer#number} */
        public int number() {
            int value = 0;
            int shift = 0;
            for (int i = 0; i < this.length; i++) {
                value |= (this.payload[i] & 0x7F) << shift;
                shift += 7;
            }
            return value;
        }

        /** The text at the start of a record written with {@link Writer#named} */
        public String name() {
            return new String(this.payload, this.nameStart(), this.nameLength(), java.nio.charset.StandardCharsets.UTF_8);
        }

        /** Where the bytes after the name start */
        public int bodyStart() {
            return this.nameStart() + this.nameLength();
        }

        private int nameLength() {
            int value = 0;
            int shift = 0;
            for (int i = 0; i < this.length; i++) {
                value |= (this.payload[i] & 0x7F) << shift;
                if ((this.payload[i] & 0x80) == 0) {
                    break;
                }
                shift += 7;
            }
            return value;
        }

        private int nameStart() {
            int i = 0;
            while (i < this.length && (this.payload[i] & 0x80) != 0) {
                i++;
            }
            return i + 1;
        }

        private int varint(int first) throws IOException {
            int value = 0;
            int shift = 0;
            int current = first;
            while (true) {
                if (current < 0) {
                    throw new EOFException("The segment ends in the middle of a record");
                }
                value |= (current & 0x7F) << shift;
                if ((current & 0x80) == 0) {
                    return value;
                }
                shift += 7;
                if (shift > 35) {
                    throw new RecordingFormatException(RecordingFormatException.Reason.DAMAGED, "A record of the recording has an impossible number");
                }
                current = this.in.read();
            }
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
