package comedymonkey.dailies.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** The word the editor's pick panel is built afresh by: it changes with what is listed, never with the clock */
class PickShapeTest {

    @Test
    void theSameThingsInAnotherOrderAreTheSameShape() {
        // Mobs walking about change which is nearest, which must not take the ring away from the control the keys are on
        assertEquals(PickShape.of(7, new int[]{3, 1, 2}), PickShape.of(7, new int[]{1, 2, 3}));
    }

    @Test
    void somethingComingOrGoingChangesTheShape() {
        assertNotEquals(PickShape.of(7, new int[]{1, 2}), PickShape.of(7, new int[]{1, 2, 3}));
        assertNotEquals(PickShape.of(7, new int[]{1, 2, 3}), PickShape.of(7, new int[]{1, 3}));
    }

    @Test
    void anotherPickIsAnotherShape() {
        assertNotEquals(PickShape.of(7, new int[]{1}), PickShape.of(8, new int[]{1}));
        assertNotEquals(PickShape.of(0, new int[0]), PickShape.of(8, new int[0]));
    }

    @Test
    void idsCannotRunTogether() {
        assertNotEquals(PickShape.of(1, new int[]{12}), PickShape.of(1, new int[]{1, 2}));
    }

    @Test
    void theIdsGivenAreLeftAsTheyWere() {
        int[] ids = {3, 1, 2};
        PickShape.of(1, ids);
        assertEquals(3, ids[0]);
        assertEquals(1, ids[1]);
        assertEquals(2, ids[2]);
    }
}
