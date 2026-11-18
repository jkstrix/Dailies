package comedymonkey.dailies.ui;

import java.util.function.IntUnaryOperator;

/**
 * Which of a run of things in tick order fall within a stretch of ticks, found by halving rather than by looking at
 * every one, so that a track with thousands of keyframes costs a frame only those on screen. No game here, so it can be
 * tested on its own.
 */
final class VisibleRange {

    private VisibleRange() {
    }

    /**
     * @param size how many there are
     * @param tick the tick of each, by its place; never smaller than the tick of the one before
     * @return the place of the first at or after {@code from}, and the place after the last at or before {@code to}:
     * those from the first up to but not including the second are within. Both are the same when none is
     */
    static int[] of(int size, IntUnaryOperator tick, double from, double to) {
        int first = firstAbove(size, tick, from, false);
        int end = Math.max(first, firstAbove(size, tick, to, true));
        return new int[]{first, end};
    }

    /** The first place whose tick is at or past the limit, or past it alone when {@code strictly}; size when none is */
    private static int firstAbove(int size, IntUnaryOperator tick, double limit, boolean strictly) {
        int low = 0;
        int high = size;
        while (low < high) {
            int middle = (low + high) >>> 1;
            int at = tick.applyAsInt(middle);
            if (strictly ? at > limit : at >= limit) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        return low;
    }

}
