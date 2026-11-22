package comedymonkey.dailies.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The notches of the playback bar's gauge and the editor's ruler */
class RulerTest {

    @Test
    void aLongRecordingOnANarrowBarGetsAFewNotchesNotAComb() {
        // Nine minutes across 400 pixels: a small notch every 10 seconds, a tall one every minute
        double span = 9 * 60 * 20;
        assertEquals(200, Ruler.minor(span, 400, Ruler.MINOR_GAP));
        assertEquals(1_200, Ruler.major(200, span, 400, Ruler.MAJOR_GAP));
        // The same recording across 1000 pixels: every 5 seconds and every 30
        assertEquals(100, Ruler.minor(span, 1000, Ruler.MINOR_GAP));
        assertEquals(600, Ruler.major(100, span, 1000, Ruler.MAJOR_GAP));
    }

    @Test
    void zoomingInComesDownTheLadderToSingleTicks() {
        // Two seconds across 600 pixels: 15 pixels a tick
        assertEquals(1, Ruler.minor(40, 600, Ruler.MINOR_GAP));
        assertEquals(5, Ruler.major(1, 40, 600, Ruler.MAJOR_GAP));
        // Twenty seconds across 600 pixels: 1.5 pixels a tick
        assertEquals(5, Ruler.minor(400, 600, Ruler.MINOR_GAP));
        assertEquals(40, Ruler.major(5, 400, 600, Ruler.MAJOR_GAP));
    }

    @Test
    void notchesAreNeverCloserThanAsked() {
        for (double span = 40; span < 20.0 * 60 * 60 * 30; span *= 1.37) {
            for (double width = 60; width < 4000; width *= 1.9) {
                long minor = Ruler.minor(span, width, Ruler.MINOR_GAP);
                long major = Ruler.major(minor, span, width, Ruler.MAJOR_GAP);
                assertTrue(minor * width / span >= Ruler.MINOR_GAP, "small notches too close at " + span + " over " + width);
                assertTrue(major * width / span >= Ruler.MAJOR_GAP, "tall notches too close at " + span + " over " + width);
                assertEquals(0, major % minor, "a tall notch that is not a small one at " + span + " over " + width);
            }
        }
    }

    @Test
    void theGapIsTheShortestRungThatIsFarEnoughApart() {
        for (double span = 40; span < 20.0 * 60 * 60 * 10; span *= 1.29) {
            double width = 500;
            long minor = Ruler.minor(span, width, Ruler.MINOR_GAP);
            for (long rung : Ruler.LADDER) {
                if (rung < minor) {
                    assertTrue(rung * width / span < Ruler.MINOR_GAP, "a shorter rung " + rung + " would have done at " + span);
                }
            }
        }
    }

    @Test
    void everyNotchIsPlacedFromItsOwnTickSoTheGapsNeverDrift() {
        // A zoomed view that starts part of the way along, where adding up rounded gaps would creep
        double start = 1234.5;
        double span = 7_777;
        double width = 913;
        long every = Ruler.minor(span, width, Ruler.MINOR_GAP);
        long first = Ruler.first(start, every);
        assertTrue(first >= start && first - every < start);
        double exact = width * every / span;
        int previous = Ruler.x(first, start, span, 10, width);
        int count = 0;
        for (long tick = first; tick <= start + span; tick += every) {
            int x = Ruler.x(tick, start, span, 10, width);
            assertEquals(10 + (tick - start) / span * width, x, 0.5);
            if (tick > first) {
                assertTrue(Math.abs(x - previous - exact) <= 1.0, "a gap of " + (x - previous) + " where " + exact + " was due");
            }
            previous = x;
            count++;
        }
        assertTrue(count > 10);
    }

    @Test
    void labelsReadAsTimesAndNeverRepeat() {
        assertEquals("0:00", Ruler.label(0, 1_200));
        assertEquals("9:00", Ruler.label(10_800, 1_200));
        assertEquals("0:01.25", Ruler.label(25, 5));
        assertEquals("0:00.05", Ruler.label(1, 1));
        assertEquals("75:00", Ruler.label(90_000, 36_000));
    }

}
