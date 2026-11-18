package comedymonkey.dailies.file;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Lengths read from a file are never believed (code review FILE-1) */
class BoundedReaderTest {

    @Test
    void readsWhatWasWritten() throws RecordingFormatException {
        ByteBuffer buffer = ByteBuffer.allocate(64);
        buffer.put((byte) 7).putInt(-123456).putLong(Long.MIN_VALUE + 99);
        // 300 as a varint, then text of three bytes
        buffer.put((byte) 0xAC).put((byte) 0x02);
        buffer.put((byte) 3).put("abc".getBytes(StandardCharsets.UTF_8));
        buffer.put(new byte[]{9, 8, 7});
        byte[] bytes = new byte[buffer.position()];
        buffer.flip().get(bytes);

        BoundedReader in = new BoundedReader(bytes);
        assertEquals(7, in.readByte());
        assertEquals(-123456, in.readInt());
        assertEquals(Long.MIN_VALUE + 99, in.readLong());
        assertEquals(300, in.readVarInt());
        assertEquals("abc", in.readText());
        assertArrayEquals(new byte[]{9, 8, 7}, in.readBytes(3));
        assertEquals(0, in.remaining());
    }

    @Test
    void readsOnlyItsOwnStretch() throws RecordingFormatException {
        byte[] bytes = {1, 2, 3, 4, 5, 6};
        BoundedReader in = new BoundedReader(bytes, 2, 3);
        assertEquals(2, in.position());
        assertEquals(3, in.readByte());
        in.skip(1);
        assertEquals(5, in.readByte());
        assertThrows(RecordingFormatException.class, in::readByte);
        assertThrows(IndexOutOfBoundsException.class, () -> new BoundedReader(bytes, 4, 3));
        assertThrows(IndexOutOfBoundsException.class, () -> new BoundedReader(bytes, -1, 2));
    }

    @Test
    void aLengthLongerThanWhatIsLeftIsDamage() {
        byte[] bytes = ByteBuffer.allocate(8).putInt(Integer.MAX_VALUE).putInt(0).array();
        BoundedReader in = new BoundedReader(bytes);
        RecordingFormatException damaged = assertThrows(RecordingFormatException.class, () -> in.readBytes(in.readInt()));
        assertEquals(RecordingFormatException.Reason.DAMAGED, damaged.reason());
        assertThrows(RecordingFormatException.class, () -> new BoundedReader(new byte[4]).readBytes(5));
        assertThrows(RecordingFormatException.class, () -> new BoundedReader(new byte[4]).readBytes(-1));
        assertThrows(RecordingFormatException.class, () -> new BoundedReader(new byte[4]).skip(5));
    }

    @Test
    void lengthsAreCappedWhateverIsAvailable() throws RecordingFormatException {
        assertEquals(BoundedReader.MAX_LENGTH, BoundedReader.checkLength(BoundedReader.MAX_LENGTH, Long.MAX_VALUE));
        assertThrows(RecordingFormatException.class, () -> BoundedReader.checkLength(BoundedReader.MAX_LENGTH + 1L, Long.MAX_VALUE));
        assertThrows(RecordingFormatException.class, () -> BoundedReader.checkLength(-1, 10));
        assertThrows(RecordingFormatException.class, () -> BoundedReader.checkLength(11, 10));
        assertEquals(10, BoundedReader.checkLength(10, 10));
    }

    @Test
    void cutShortNumbersAreDamage() {
        assertThrows(RecordingFormatException.class, () -> new BoundedReader(new byte[3]).readInt());
        assertThrows(RecordingFormatException.class, () -> new BoundedReader(new byte[7]).readLong());
        // Every byte says another follows, and none does
        assertThrows(RecordingFormatException.class, () -> new BoundedReader(new byte[]{(byte) 0x80, (byte) 0x80}).readVarInt());
        // Longer than any int or long can be
        byte[] endless = new byte[12];
        java.util.Arrays.fill(endless, (byte) 0xFF);
        assertThrows(RecordingFormatException.class, () -> new BoundedReader(endless).readVarInt());
        assertThrows(RecordingFormatException.class, () -> new BoundedReader(endless).readVarLong());
        // Text that says it is longer than what follows
        assertThrows(RecordingFormatException.class, () -> new BoundedReader(new byte[]{5, 'a', 'b'}).readText());
    }

    @Test
    void varLongsReachTheFullRange() throws RecordingFormatException {
        byte[] bytes = new byte[10];
        java.util.Arrays.fill(bytes, 0, 9, (byte) 0xFF);
        bytes[9] = 0x01;
        assertEquals(-1L, new BoundedReader(bytes).readVarLong());
    }

}
