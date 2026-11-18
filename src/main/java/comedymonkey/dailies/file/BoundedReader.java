package comedymonkey.dailies.file;

import java.nio.charset.StandardCharsets;

/**
 * Reads numbers, lengths and bytes from a stretch of bytes that came from a file, and never believes a length it is
 * given. A checksum only says a block is as it was written, not that whoever wrote it was honest: a crafted file can
 * say a piece is two gigabytes long, and making room for that before looking would end the game. So every length is
 * checked against the bytes actually left and against {@link #MAX_LENGTH} before anything is made for it, and
 * anything that does not add up, or runs past the end, is a {@link RecordingFormatException} saying the recording is
 * damaged.
 */
public final class BoundedReader {

    /**
     * The most any one length in a recording may say, whatever the bytes around it: far more than anything Dailies
     * writes as one piece (a segment part is about a megabyte, a stored file is kept a megabyte at a time)
     */
    public static final int MAX_LENGTH = 256 * 1024 * 1024;

    private final byte[] bytes;
    private final int end;
    private int position;

    /** Reads {@code length} bytes of {@code bytes}, from {@code from} */
    public BoundedReader(byte[] bytes, int from, int length) {
        if (from < 0 || length < 0 || from > bytes.length - length) {
            throw new IndexOutOfBoundsException("The stretch " + from + " + " + length + " is not inside " + bytes.length + " bytes");
        }
        this.bytes = bytes;
        this.position = from;
        this.end = from + length;
    }

    public BoundedReader(byte[] bytes) {
        this(bytes, 0, bytes.length);
    }

    /** Where the next byte is read from, counted from the start of the array */
    public int position() {
        return this.position;
    }

    /** How many bytes are left to read */
    public int remaining() {
        return this.end - this.position;
    }

    public byte readByte() throws RecordingFormatException {
        this.need(1);
        return this.bytes[this.position++];
    }

    /** Four bytes, the highest first */
    public int readInt() throws RecordingFormatException {
        this.need(4);
        int value = 0;
        for (int i = 0; i < 4; i++) {
            value = value << 8 | this.bytes[this.position++] & 0xFF;
        }
        return value;
    }

    /** Eight bytes, the highest first */
    public long readLong() throws RecordingFormatException {
        long high = this.readInt() & 0xFFFFFFFFL;
        return high << 32 | this.readInt() & 0xFFFFFFFFL;
    }

    /** A number written seven bits a byte, the lowest first, in at most five bytes */
    public int readVarInt() throws RecordingFormatException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            byte current = this.readByte();
            value |= (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                return value;
            }
        }
        throw damaged("A number in the recording is longer than any number can be");
    }

    /** A number written seven bits a byte, the lowest first, in at most ten bytes */
    public long readVarLong() throws RecordingFormatException {
        long value = 0;
        for (int shift = 0; shift < 70; shift += 7) {
            byte current = this.readByte();
            value |= (long) (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                return value;
            }
        }
        throw damaged("A number in the recording is longer than any number can be");
    }

    /**
     * A length the bytes have just given, checked: not negative, not more than is left, not more than {@link #MAX_LENGTH}
     *
     * @return the length, now safe to make room for
     */
    public int length(long length) throws RecordingFormatException {
        return checkLength(length, this.remaining());
    }

    /** That many bytes, copied out */
    public byte[] readBytes(int length) throws RecordingFormatException {
        int checked = this.length(length);
        byte[] copy = new byte[checked];
        System.arraycopy(this.bytes, this.position, copy, 0, checked);
        this.position += checked;
        return copy;
    }

    /** Steps over that many bytes */
    public void skip(int length) throws RecordingFormatException {
        this.position += this.length(length);
    }

    /** Text written as its length in UTF-8 bytes, as a varint, then the bytes */
    public String readText() throws RecordingFormatException {
        int length = this.length(this.readVarInt());
        String text = new String(this.bytes, this.position, length, StandardCharsets.UTF_8);
        this.position += length;
        return text;
    }

    /**
     * Checks a length read from a file against what there is to fill it and against {@link #MAX_LENGTH}
     *
     * @param available how many bytes there are that it could be made of
     */
    public static int checkLength(long length, long available) throws RecordingFormatException {
        if (length < 0 || length > available || length > MAX_LENGTH) {
            throw damaged("A part of the recording says it is " + length + " bytes long, which cannot be so");
        }
        return (int) length;
    }

    static RecordingFormatException damaged(String message) {
        return new RecordingFormatException(RecordingFormatException.Reason.DAMAGED, message);
    }

    private void need(int count) throws RecordingFormatException {
        if (this.remaining() < count) {
            throw damaged("A part of the recording ends before it should");
        }
    }

}
