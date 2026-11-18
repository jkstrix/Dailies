package comedymonkey.dailies.ui;

import java.util.Locale;

/**
 * Where the notches of a time ruler go, for the playback bar's gauge and the editor's timeline. The gap between notches
 * is a round length of time taken from a ladder, the smallest that leaves them far enough apart at the span and width
 * shown, so a long recording on a narrow bar gets a notch every few minutes rather than a solid comb, and a zoomed-in
 * timeline gets one every tick. Every notch's place is worked out from its own tick, never by adding up gaps, so the
 * notches stay evenly spaced however many there are.
 */
final class Ruler {

    /** The round lengths a gap may be, in ticks: 1 and 5 ticks, half a second, 1, 2, 5, 10, 15 and 30 seconds, then minutes and hours */
    static final long[] LADDER = {
        1, 5, 10, 20, 40, 100, 200, 300, 600,
        1_200, 2_400, 6_000, 12_000, 18_000, 36_000,
        72_000, 144_000, 216_000, 432_000, 864_000, 1_728_000
    };
    /** The fewest pixels between two small notches */
    static final double MINOR_GAP = 7.0;
    /** The fewest pixels between two tall notches */
    static final double MAJOR_GAP = 40.0;

    private Ruler() {
    }

    /**
     * The gap in ticks between small notches: the shortest rung at least {@code gap} pixels long when {@code span} ticks
     * are shown across {@code width} pixels.
     */
    static long minor(double span, double width, double gap) {
        return shortest(1, span, width, gap);
    }

    /**
     * The gap in ticks between tall notches: the shortest rung that is a whole number of small gaps, so every tall notch
     * is also a small one, and at least {@code gap} pixels long. It is the small gap itself when that is already long enough.
     */
    static long major(long minor, double span, double width, double gap) {
        return shortest(Math.max(1, minor), span, width, gap);
    }

    private static long shortest(long multipleOf, double span, double width, double gap) {
        double pixelsPerTick = span > 0.0 && width > 0.0 ? width / span : 0.0;
        for (long rung : LADDER) {
            if (rung >= multipleOf && rung % multipleOf == 0 && rung * pixelsPerTick >= gap) {
                return rung;
            }
        }
        // Past the top of the ladder: whole multiples of its top rung, doubling until they are far enough apart
        long rung = LADDER[LADDER.length - 1];
        while (rung * pixelsPerTick < gap && rung < Long.MAX_VALUE / 4) {
            rung *= 2;
        }
        return rung;
    }

    /** The first notch at or after {@code from}, for notches every {@code every} ticks */
    static long first(double from, long every) {
        return (long) Math.ceil(Math.max(0.0, from) / every) * every;
    }

    /** Where a tick falls on a ruler whose left edge shows {@code start} and which shows {@code span} ticks over {@code width} pixels */
    static int x(long tick, double start, double span, double left, double width) {
        return (int) Math.floor(left + (tick - start) / span * width + 0.5);
    }

    /**
     * The words under a tall notch: minutes and seconds where the tall notches fall on whole seconds, and hundredths of
     * a second as well where they do not, so two notches never read the same.
     */
    static String label(long tick, long major) {
        long safe = Math.max(0, tick);
        long seconds = safe / 20;
        if (major % 20 == 0) {
            return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
        }
        return String.format(Locale.ROOT, "%d:%02d.%02d", seconds / 60, seconds % 60, (safe % 20) * 5);
    }

}
