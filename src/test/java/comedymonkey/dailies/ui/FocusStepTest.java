package comedymonkey.dailies.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Where Tab takes the keys next, in the editor and in every other Dailies screen */
class FocusStepTest {

    @Test
    void fromNothingTabStartsAtTheFirstAndShiftTabAtTheLast() {
        assertEquals(0, FocusStep.next(5, -1, false));
        assertEquals(4, FocusStep.next(5, -1, true));
    }

    @Test
    void tabGoesOnAndShiftTabGoesBack() {
        assertEquals(3, FocusStep.next(5, 2, false));
        assertEquals(1, FocusStep.next(5, 2, true));
    }

    @Test
    void itGoesRoundAtTheEnds() {
        assertEquals(0, FocusStep.next(5, 4, false));
        assertEquals(4, FocusStep.next(5, 0, true));
        assertEquals(0, FocusStep.next(1, 0, false));
        assertEquals(0, FocusStep.next(1, 0, true));
    }

    @Test
    void withNothingToVisitThereIsNowhereToGo() {
        assertEquals(-1, FocusStep.next(0, -1, false));
        assertEquals(-1, FocusStep.next(0, -1, true));
    }
}
