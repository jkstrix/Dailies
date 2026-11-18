package comedymonkey.dailies.export;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The lengths a WAV file's header says, however long the track (EXP-12) */
class WavHeaderTest {

    private static String tag(ByteBuffer head, int at) {
        byte[] four = new byte[4];
        head.get(at, four);
        return new String(four, StandardCharsets.US_ASCII);
    }

    private static ByteBuffer read(byte[] header) {
        return ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
    }

    @Test
    void aShortTrackIsPlainWav() {
        long frames = 48_000L * 60;
        ByteBuffer head = read(WavHeader.of(frames, 2, 48_000));
        assertEquals(44, head.capacity());
        assertEquals("RIFF", tag(head, 0));
        assertEquals(36 + frames * 4, Integer.toUnsignedLong(head.getInt(4)));
        assertEquals("WAVE", tag(head, 8));
        assertEquals("fmt ", tag(head, 12));
        assertEquals(16, head.getInt(16));
        assertEquals(1, head.getShort(20));
        assertEquals(2, head.getShort(22));
        assertEquals(48_000, head.getInt(24));
        assertEquals(48_000 * 4, head.getInt(28));
        assertEquals(4, head.getShort(32));
        assertEquals(16, head.getShort(34));
        assertEquals("data", tag(head, 36));
        assertEquals(frames * 4, Integer.toUnsignedLong(head.getInt(40)));
    }

    @Test
    void monoHalvesTheBytes() {
        ByteBuffer head = read(WavHeader.of(1000, 1, 48_000));
        assertEquals(1, head.getShort(22));
        assertEquals(48_000 * 2, head.getInt(28));
        assertEquals(2, head.getShort(32));
        assertEquals(2000, head.getInt(40));
    }

    /** Past 2 GiB the lengths no longer fit a signed int, but still fit the four unsigned bytes WAV gives them */
    @Test
    void overTwoGibibytesTheLengthsAreStillRight() {
        long frames = 48_000L * 60 * 60 * 4; // four hours of stereo: 2.6 GiB
        long data = frames * 4;
        ByteBuffer head = read(WavHeader.of(frames, 2, 48_000));
        assertEquals(44, head.capacity());
        assertEquals("RIFF", tag(head, 0));
        assertEquals(36 + data, Integer.toUnsignedLong(head.getInt(4)));
        assertEquals(data, Integer.toUnsignedLong(head.getInt(40)));
    }

    @Test
    void theLongestPlainWavStaysPlain() {
        long frames = (WavHeader.PLAIN_LIMIT - 36) / 4;
        ByteBuffer head = read(WavHeader.of(frames, 2, 48_000));
        assertEquals(44, head.capacity());
        assertEquals("RIFF", tag(head, 0));
    }

    /** Past 4 GiB the four bytes cannot say it: RF64, with the lengths in eight bytes */
    @Test
    void overFourGibibytesItIsRf64() {
        long frames = 48_000L * 60 * 60 * 7; // seven hours of stereo: 4.5 GiB
        long data = frames * 4;
        ByteBuffer head = read(WavHeader.of(frames, 2, 48_000));
        assertEquals(80, head.capacity());
        assertEquals("RF64", tag(head, 0));
        assertEquals(0xFFFFFFFFL, Integer.toUnsignedLong(head.getInt(4)));
        assertEquals("WAVE", tag(head, 8));
        assertEquals("ds64", tag(head, 12));
        assertEquals(28, head.getInt(16));
        // The whole file less its first eight bytes: the header's other 72 and the samples
        assertEquals(72 + data, head.getLong(20));
        assertEquals(data, head.getLong(28));
        assertEquals(frames, head.getLong(36));
        assertEquals(0, head.getInt(44));
        assertEquals("fmt ", tag(head, 48));
        assertEquals(16, head.getInt(52));
        assertEquals(1, head.getShort(56));
        assertEquals(2, head.getShort(58));
        assertEquals(48_000, head.getInt(60));
        assertEquals(48_000 * 4, head.getInt(64));
        assertEquals(4, head.getShort(68));
        assertEquals(16, head.getShort(70));
        assertEquals("data", tag(head, 72));
        assertEquals(0xFFFFFFFFL, Integer.toUnsignedLong(head.getInt(76)));
    }

}
