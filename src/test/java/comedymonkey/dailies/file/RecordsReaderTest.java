package comedymonkey.dailies.file;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Walking the records of a segment, as written and as a damaged or crafted file has them (code review FILE-1) */
class RecordsReaderTest {

    private static byte[] sample() {
        Records.Writer writer = new Records.Writer();
        writer.number(Records.SNAPSHOT_BEGIN, 1200);
        writer.record(Records.SNAPSHOT_END);
        writer.record(Records.PACKET, new byte[]{1, 2, 3, 4});
        writer.named(Records.EXTENSION, "dailies:skin", new byte[]{9, 9}, 0, 2);
        writer.number(Records.TICK, 3);
        byte[] big = new byte[200_000];
        new Random(1).nextBytes(big);
        writer.record(Records.PACKET, big);
        writer.named(Records.PAYLOAD, "", new byte[0], 0, 0);
        return writer.take();
    }

    private static Records.Reader reader(byte[] bytes) {
        return new Records.Reader(new ByteArrayInputStream(bytes));
    }

    @Test
    void readsBackWhatWasWritten() throws IOException {
        try (Records.Reader reader = reader(sample())) {
            assertTrue(reader.next());
            assertEquals(Records.SNAPSHOT_BEGIN, reader.kind());
            assertEquals(1200, reader.number());
            assertTrue(reader.next());
            assertEquals(Records.SNAPSHOT_END, reader.kind());
            assertEquals(0, reader.length());
            assertTrue(reader.next());
            assertEquals(Records.PACKET, reader.kind());
            assertArrayEquals(new byte[]{1, 2, 3, 4}, Arrays.copyOf(reader.payload(), reader.length()));
            assertTrue(reader.next());
            assertEquals(Records.EXTENSION, reader.kind());
            assertEquals("dailies:skin", reader.name());
            assertEquals(reader.length() - 2, reader.bodyStart());
            assertEquals(9, reader.payload()[reader.bodyStart()]);
            assertTrue(reader.next());
            assertEquals(3, reader.number());
            assertTrue(reader.next());
            assertEquals(200_000, reader.length());
            assertTrue(reader.next());
            assertEquals(Records.PAYLOAD, reader.kind());
            assertEquals("", reader.name());
            assertEquals(1, reader.bodyStart());
            assertFalse(reader.next());
        }
    }

    @Test
    void aRecordWithoutANameSaysSo() throws IOException {
        try (Records.Reader reader = reader(sample())) {
            assertTrue(reader.next());
            assertThrows(IllegalStateException.class, reader::name);
            assertThrows(IllegalStateException.class, reader::bodyStart);
        }
    }

    @Test
    void everyCutIsDamageOrAnEarlyEnd() throws IOException {
        byte[] whole = sample();
        for (int cut = 0; cut < whole.length; cut += cut < 64 ? 1 : 4099) {
            try (Records.Reader reader = reader(Arrays.copyOf(whole, cut))) {
                try {
                    while (reader.next()) {
                        // Reading on until the cut
                    }
                } catch (RecordingFormatException damaged) {
                    assertEquals(RecordingFormatException.Reason.DAMAGED, damaged.reason());
                }
            }
        }
    }

    @Test
    void aRecordThatClaimsGigabytesIsDamageWithoutRoomMadeForIt() {
        // A packet record that says it is about two gigabytes long, followed by four bytes
        byte[] lying = {Records.PACKET, (byte) 0xFE, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x07, 1, 2, 3, 4};
        assertThrows(RecordingFormatException.class, () -> reader(lying).next());
        // One that says it is more than any length may be, with the bytes apparently to follow
        int tooLong = BoundedReader.MAX_LENGTH + 1;
        byte[] beyond = {Records.PACKET, (byte) (tooLong & 0x7F | 0x80), (byte) (tooLong >>> 7 & 0x7F | 0x80), (byte) (tooLong >>> 14 & 0x7F | 0x80),
            (byte) (tooLong >>> 21 & 0x7F | 0x80), (byte) (tooLong >>> 28)};
        assertThrows(RecordingFormatException.class, () -> reader(beyond).next());
        // A length whose varint never ends
        byte[] endless = {Records.PACKET, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0};
        assertThrows(RecordingFormatException.class, () -> reader(endless).next());
    }

    @Test
    void aBigLengthThatIsTrueStillReads() throws IOException {
        // Room is made a piece at a time as the bytes arrive, from a stream that hands them over a few at a time
        byte[] payload = new byte[1_000_003];
        new Random(7).nextBytes(payload);
        Records.Writer writer = new Records.Writer();
        writer.record(Records.PACKET, payload);
        byte[] bytes = writer.take();
        InputStream trickle = new ByteArrayInputStream(bytes) {
            @Override
            public synchronized int read(byte[] target, int from, int length) {
                return super.read(target, from, Math.min(length, 777));
            }
        };
        try (Records.Reader reader = new Records.Reader(trickle)) {
            assertTrue(reader.next());
            assertEquals(payload.length, reader.length());
            assertArrayEquals(payload, Arrays.copyOf(reader.payload(), reader.length()));
            assertFalse(reader.next());
        }
    }

    @Test
    void aNameLongerThanItsRecordIsDamage() {
        // An extension record of three bytes whose name says it is ten bytes long
        byte[] bytes = {Records.EXTENSION, 3, 10, 'a', 'b'};
        assertThrows(RecordingFormatException.class, () -> reader(bytes).next());
        // A payload record whose name length never ends
        byte[] endless = {Records.PAYLOAD, 2, (byte) 0x80, (byte) 0x80};
        assertThrows(RecordingFormatException.class, () -> reader(endless).next());
        // A name with nothing at all
        byte[] empty = {Records.EXTENSION, 0};
        assertThrows(RecordingFormatException.class, () -> reader(empty).next());
    }

    @Test
    void aTickCountThatCannotBeIsDamage() {
        // Minus one, as five bytes
        byte[] negative = {Records.TICK, 5, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x0F};
        assertThrows(RecordingFormatException.class, () -> reader(negative).next());
        // A number with bytes left over after it
        byte[] trailing = {Records.TICK, 2, 1, 1};
        assertThrows(RecordingFormatException.class, () -> reader(trailing).next());
        // No number at all
        byte[] none = {Records.SNAPSHOT_BEGIN, 0};
        assertThrows(RecordingFormatException.class, () -> reader(none).next());
    }

    @Test
    void anUnknownKindIsSteppedOver() throws IOException {
        Records.Writer writer = new Records.Writer();
        writer.record(99, "later versions".getBytes(StandardCharsets.UTF_8));
        writer.number(Records.TICK, 1);
        try (Records.Reader reader = reader(writer.take())) {
            assertTrue(reader.next());
            assertEquals(99, reader.kind());
            assertTrue(reader.next());
            assertEquals(1, reader.number());
        }
    }

}
