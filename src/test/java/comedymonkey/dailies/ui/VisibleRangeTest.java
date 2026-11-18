package comedymonkey.dailies.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which keyframes of a track the timeline draws: those whose ticks are within what it shows */
class VisibleRangeTest {

    private static int[] of(int[] ticks, double from, double to) {
        return VisibleRange.of(ticks.length, i -> ticks[i], from, to);
    }

    @Test
    void thoseWithinAreFoundAndTheEndsCount() {
        int[] ticks = {0, 10, 20, 30, 40};
        assertArrayEquals(new int[]{1, 4}, of(ticks, 10, 30));
        assertArrayEquals(new int[]{1, 3}, of(ticks, 5, 25));
        assertArrayEquals(new int[]{0, 5}, of(ticks, -100, 100));
    }

    @Test
    void noneWithinGivesAnEmptyRun() {
        int[] ticks = {0, 10, 20};
        int[] before = of(ticks, -50, -1);
        assertEquals(before[0], before[1]);
        int[] after = of(ticks, 21, 90);
        assertEquals(after[0], after[1]);
        int[] between = of(ticks, 11, 19);
        assertEquals(between[0], between[1]);
        assertArrayEquals(new int[]{0, 0}, of(new int[0], 0, 100));
    }

    @Test
    void severalOnOneTickAreAllTaken() {
        int[] ticks = {5, 7, 7, 7, 9};
        assertArrayEquals(new int[]{1, 4}, of(ticks, 7, 7));
        assertArrayEquals(new int[]{1, 4}, of(ticks, 6.5, 8.5));
    }

    @Test
    void theSameAsLookingAtEveryOne() {
        int[] ticks = {0, 3, 3, 8, 15, 15, 16, 40, 41, 100};
        for (double from = -5; from < 110; from += 2.5) {
            for (double to = from; to < 115; to += 3.5) {
                int first = 0;
                while (first < ticks.length && ticks[first] < from) {
                    first++;
                }
                int end = first;
                while (end < ticks.length && ticks[end] <= to) {
                    end++;
                }
                assertArrayEquals(new int[]{first, end}, of(ticks, from, to), from + " to " + to);
            }
        }
    }
}
