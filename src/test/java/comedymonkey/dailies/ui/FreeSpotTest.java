package comedymonkey.dailies.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Dailies' buttons find room among other mods' buttons, never on top of them */
class FreeSpotTest {

    @Test
    void aPlaceAnotherModTookIsPassedOver() {
        // Another mod's square above the language button, where Dailies' column used to go
        List<FreeSpot.Box> taken = List.of(new FreeSpot.Box(36, 180, 20, 20), new FreeSpot.Box(36, 156, 20, 20), new FreeSpot.Box(60, 132, 200, 20));
        List<List<FreeSpot.Box>> candidates = List.of(
            FreeSpot.line(36, 156, 0, -24, 3, 20),
            FreeSpot.line(12, 180, 0, -24, 3, 20));
        assertEquals(1, FreeSpot.first(candidates, taken, 320, 240, 2));
    }

    @Test
    void everySquareMustBeFreeNotJustTheFirst() {
        List<FreeSpot.Box> taken = List.of(new FreeSpot.Box(100, 52, 20, 20));
        assertFalse(FreeSpot.free(FreeSpot.line(100, 100, 0, -24, 3, 20), taken, 320, 240, 2));
        assertTrue(FreeSpot.free(FreeSpot.line(130, 100, 0, -24, 3, 20), taken, 320, 240, 2));
    }

    @Test
    void squaresOffTheScreenAreNotFree() {
        assertFalse(FreeSpot.free(FreeSpot.line(10, 30, 0, -24, 3, 20), List.of(), 320, 240, 2));
        assertFalse(FreeSpot.free(FreeSpot.line(310, 100, 0, -24, 3, 20), List.of(), 320, 240, 2));
        assertEquals(-1, FreeSpot.first(List.of(FreeSpot.line(-30, 100, 24, 0, 3, 20)), List.of(), 320, 240, 2));
    }

    @Test
    void touchingCountsAsOverlapping() {
        FreeSpot.Box button = new FreeSpot.Box(50, 50, 20, 20);
        assertTrue(new FreeSpot.Box(71, 50, 20, 20).near(button, 2));
        assertFalse(new FreeSpot.Box(72, 50, 20, 20).near(button, 2));
    }

}
