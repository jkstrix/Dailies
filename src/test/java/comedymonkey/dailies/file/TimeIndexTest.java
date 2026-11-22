package comedymonkey.dailies.file;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The time index read back from records, whole, damaged and crafted (FILE-16, code review FILE-1) */
class TimeIndexTest {

    private static final long REAL = 1_800_000_000_000L;
    private static final long GAME = 5_000_000L;

    /** A segment from {@code start}, {@code ticks} long, as the recorder writes it: times after the snapshot and after every tick */
    private static byte[] segment(int start, int ticks) {
        Records.Writer writer = new Records.Writer();
        TimeIndex.Encoder encoder = new TimeIndex.Encoder();
        writer.number(Records.SNAPSHOT_BEGIN, start);
        writer.record(Records.SNAPSHOT_END);
        writer.record(Records.TIME, encoder.next(real(start), game(start)));
        for (int tick = start + 1; tick <= start + ticks; tick++) {
            writer.record(Records.PACKET, new byte[]{(byte) tick});
            writer.number(Records.TICK, 1);
            writer.record(Records.TIME, encoder.next(real(tick), game(tick)));
        }
        return writer.take();
    }

    private static long real(int tick) {
        return REAL + tick * 50L + (tick % 3);
    }

    private static long game(int tick) {
        return GAME + tick;
    }

    private static TimeIndex.Table read(byte[] bytes, int start, int end) throws IOException {
        return TimeIndex.Table.read(new ByteArrayInputStream(bytes), start, end);
    }

    @Test
    void timesComeBackAsTheyWereWritten() throws IOException {
        TimeIndex.Table table = read(segment(400, 100), 400, 500);
        assertTrue(table.any());
        for (int tick = 400; tick <= 500; tick++) {
            TimeIndex.Sample sample = table.exactly(tick);
            assertNotNull(sample, "tick " + tick);
            assertEquals(real(tick), sample.realTime());
            assertEquals(game(tick), sample.gameTime());
        }
        assertNull(table.exactly(399));
        assertNull(table.exactly(501));
        assertEquals(game(500), table.at(10_000).gameTime());
        assertEquals(game(400), table.at(-5).gameTime());
        assertEquals(450, table.tickAtGame(game(450)));
        assertEquals(-1, table.tickAtGame(game(600)));
        assertEquals(460, table.nearest(real(460) + 10).tick());
    }

    @Test
    void theEncoderAndDecoderAgreeOnAnyTimes() throws RecordingFormatException {
        Random random = new Random(3);
        TimeIndex.Encoder encoder = new TimeIndex.Encoder();
        TimeIndex.Decoder decoder = new TimeIndex.Decoder();
        long[] reals = {0, Long.MAX_VALUE / 2, -1, Long.MIN_VALUE / 2, REAL};
        for (int i = 0; i < 1000; i++) {
            long real = i < reals.length ? reals[i] : REAL + random.nextInt(1_000_000) - 500_000;
            long game = i < reals.length ? -reals[i] : random.nextLong() >> 8;
            byte[] bytes = encoder.next(real, game);
            decoder.read(bytes, bytes.length);
            assertEquals(real, decoder.realTime());
            assertEquals(game, decoder.gameTime());
        }
        assertTrue(decoder.any());
        decoder.reset();
        assertFalse(decoder.any());
    }

    @Test
    void aTimeRecordCutShortIsDamage() {
        TimeIndex.Decoder decoder = new TimeIndex.Decoder();
        byte[] bytes = new TimeIndex.Encoder().next(REAL, GAME);
        assertThrows(RecordingFormatException.class, () -> decoder.read(bytes, bytes.length - 1));
        assertThrows(RecordingFormatException.class, () -> decoder.read(new byte[0], 0));
        assertThrows(RecordingFormatException.class, () -> decoder.read(new byte[]{(byte) 0x80}, 1));
    }

    @Test
    void aDamagedSegmentKeepsTheTimesBeforeTheDamage() throws IOException {
        byte[] whole = segment(0, 50);
        // Cut in the middle of the records: what came before still stands, and nothing is thrown
        byte[] cut = Arrays.copyOf(whole, whole.length / 2 + 1);
        TimeIndex.Table table = read(cut, 0, 50);
        assertTrue(table.any());
        assertEquals(real(0), table.exactly(0).realTime());
        assertNull(table.exactly(50));
        // Every cut reads without throwing
        for (int length = 0; length < whole.length; length += 7) {
            read(Arrays.copyOf(whole, length), 0, 50);
        }
    }

    @Test
    void ticksAndLengthsThatCannotBeDoNotCrashOrFillMemory() throws IOException {
        // Part headers that claim the segment runs from the first tick there can be to the last
        TimeIndex.Table everything = read(segment(0, 20), Integer.MIN_VALUE, Integer.MAX_VALUE);
        assertNotNull(everything);
        // A segment that ends before it begins
        TimeIndex.Table backwards = read(segment(0, 20), 100, 0);
        assertNotNull(backwards.exactly(100));
        assertNull(backwards.exactly(101));

        // Ticks that add up past the largest int, then a time
        Records.Writer writer = new Records.Writer();
        TimeIndex.Encoder encoder = new TimeIndex.Encoder();
        writer.record(Records.TIME, encoder.next(REAL, GAME));
        for (int i = 0; i < 3; i++) {
            writer.number(Records.TICK, Integer.MAX_VALUE);
        }
        writer.record(Records.TIME, encoder.next(REAL + 1, GAME + 1));
        TimeIndex.Table jumped = read(writer.take(), 0, Integer.MAX_VALUE);
        assertEquals(REAL, jumped.exactly(0).realTime());
        assertNull(jumped.exactly(Integer.MAX_VALUE));

        // A time far past the start, within what the headers claim: kept only within the most a segment can have
        writer = new Records.Writer();
        encoder = new TimeIndex.Encoder();
        writer.number(Records.TICK, TimeIndex.Table.MOST_TICKS + 5);
        writer.record(Records.TIME, encoder.next(REAL, GAME));
        TimeIndex.Table far = read(writer.take(), 0, Integer.MAX_VALUE);
        assertFalse(far.any());

        // A time record whose numbers are cut short ends the reading there, as damage
        writer = new Records.Writer();
        encoder = new TimeIndex.Encoder();
        writer.record(Records.TIME, encoder.next(REAL, GAME));
        writer.number(Records.TICK, 1);
        writer.record(Records.TIME, new byte[]{(byte) 0x80});
        writer.number(Records.TICK, 1);
        writer.record(Records.TIME, encoder.next(REAL + 100, GAME + 2));
        TimeIndex.Table torn = read(writer.take(), 0, 2);
        assertNotNull(torn.exactly(0));
        assertNull(torn.exactly(1));
        assertNull(torn.exactly(2));
    }

}
