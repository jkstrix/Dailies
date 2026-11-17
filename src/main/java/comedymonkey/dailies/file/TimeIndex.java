package comedymonkey.dailies.file;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * The time index of a recording (specification FILE-16): for every tick, the real time (milliseconds since 1970) and
 * the world's game time. It can never be worked out afterwards, so it is recorded from the first version.
 * <p>
 * It is stored as {@link Records#TIME} records among the others: one straight after each segment's snapshot, for the
 * segment's first tick, and one after every tick. Each holds two numbers, the change in real time and the change in
 * game time since the record before it in the same segment; the first of a segment counts from zero, so it holds the
 * times themselves and a segment can be read without any other. Ticks are fifty milliseconds and one game tick apart
 * nearly always, so a record is four bytes. Reading it needs no game: the records of other kinds are stepped over.
 */
public final class TimeIndex {

    /** The times of one tick */
    public record Sample(long realTime, long gameTime) {
    }

    /**
     * The tick whose real time is closest to one asked for.
     *
     * @param errorMillis how far the tick's real time is from the one asked for, never negative
     */
    public record Nearest(int tick, long realTime, long gameTime, long errorMillis) {
    }

    private TimeIndex() {
    }

    /** Writes the records of one segment. Start a new one, or call {@link #reset}, for each segment */
    public static final class Encoder {

        private long real = 0;
        private long game = 0;

        public void reset() {
            this.real = 0;
            this.game = 0;
        }

        /** The payload of the next record */
        public byte[] next(long realTime, long gameTime) {
            byte[] bytes = new byte[20];
            int length = putVarLong(bytes, 0, zigzag(realTime - this.real));
            length = putVarLong(bytes, length, zigzag(gameTime - this.game));
            this.real = realTime;
            this.game = gameTime;
            return Arrays.copyOf(bytes, length);
        }
    }

    /** Reads the records of one segment in order */
    public static final class Decoder {

        private long real = 0;
        private long game = 0;
        private boolean any = false;

        public void reset() {
            this.real = 0;
            this.game = 0;
            this.any = false;
        }

        public void read(byte[] payload, int length) {
            long[] at = {0};
            this.real += unzigzag(getVarLong(payload, length, at));
            this.game += unzigzag(getVarLong(payload, length, at));
            this.any = true;
        }

        /** Whether any record has been read since the last reset */
        public boolean any() {
            return this.any;
        }

        public long realTime() {
            return this.real;
        }

        public long gameTime() {
            return this.game;
        }
    }

    /** The times of every tick of one segment */
    public static final class Table {

        private final int startTick;
        private final long[] real;
        private final long[] game;
        private final boolean[] known;
        private boolean any = false;

        private Table(int startTick, int ticks) {
            this.startTick = startTick;
            this.real = new long[ticks + 1];
            this.game = new long[ticks + 1];
            this.known = new boolean[ticks + 1];
        }

        /**
         * Reads a segment's times from its records.
         *
         * @param endTick the tick the segment reaches
         */
        public static Table read(InputStream segment, int startTick, int endTick) throws IOException {
            Table table = new Table(startTick, Math.max(0, endTick - startTick));
            Decoder decoder = new Decoder();
            int position = 0;
            try (Records.Reader reader = new Records.Reader(segment)) {
                while (reader.next()) {
                    if (reader.kind() == Records.TICK) {
                        position += reader.number();
                    } else if (reader.kind() == Records.TIME) {
                        decoder.read(reader.payload(), reader.length());
                        if (position < table.known.length) {
                            table.real[position] = decoder.realTime();
                            table.game[position] = decoder.gameTime();
                            table.known[position] = true;
                            table.any = true;
                        }
                    }
                }
            } catch (RecordingFormatException damaged) {
                // What was read before the damage still stands
            }
            return table;
        }

        /** False for a segment recorded before the time index existed */
        public boolean any() {
            return this.any;
        }

        /** The times of this very tick, or null when the segment has none for it */
        public @Nullable Sample exactly(int tick) {
            int index = tick - this.startTick;
            return index >= 0 && index < this.known.length && this.known[index] ? new Sample(this.real[index], this.game[index]) : null;
        }

        /** The times of a tick, or of the nearest tick before it that has any; null when there is none */
        public @Nullable Sample at(int tick) {
            int index = Math.min(this.known.length - 1, tick - this.startTick);
            for (; index >= 0; index--) {
                if (this.known[index]) {
                    return new Sample(this.real[index], this.game[index]);
                }
            }
            for (index = 0; index < this.known.length; index++) {
                if (this.known[index]) {
                    return new Sample(this.real[index], this.game[index]);
                }
            }
            return null;
        }

        /**
         * The first tick of this segment whose game time is the one given, or the tick just before it was passed;
         * -1 when the segment never had it. Game time is the server's clock, so this is where another player's
         * recording of the same moment lines up with this one (PLAY-18)
         */
        public int tickAtGame(long gameTime) {
            int before = -1;
            for (int index = 0; index < this.known.length; index++) {
                if (!this.known[index]) {
                    continue;
                }
                if (this.game[index] == gameTime) {
                    return this.startTick + index;
                }
                if (this.game[index] < gameTime) {
                    before = this.startTick + index;
                } else if (before >= 0) {
                    // Passed between two ticks: the server ran ahead of the client for a moment
                    return before;
                }
            }
            return -1;
        }

        /** The tick of this segment whose real time is closest to the one given, or null when the segment has no times */
        public @Nullable Nearest nearest(long realTime) {
            Nearest best = null;
            for (int index = 0; index < this.known.length; index++) {
                if (!this.known[index]) {
                    continue;
                }
                long error = Math.abs(this.real[index] - realTime);
                if (best == null || error < best.errorMillis()) {
                    best = new Nearest(this.startTick + index, this.real[index], this.game[index], error);
                }
            }
            return best;
        }
    }

    private static long zigzag(long value) {
        return (value << 1) ^ (value >> 63);
    }

    private static long unzigzag(long value) {
        return (value >>> 1) ^ -(value & 1);
    }

    private static int putVarLong(byte[] bytes, int at, long value) {
        while ((value & ~0x7FL) != 0) {
            bytes[at++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        bytes[at++] = (byte) value;
        return at;
    }

    private static long getVarLong(byte[] bytes, int length, long[] at) {
        long value = 0;
        int shift = 0;
        while (at[0] < length && shift < 64) {
            byte current = bytes[(int) at[0]++];
            value |= (long) (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        return value;
    }

}
