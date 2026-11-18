package comedymonkey.dailies.playback;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Riding with someone through jumps: held by UUID, and given up only when they are really not there */
class RideTargetTest {

    private static final UUID RECORDER = UUID.fromString("0b1e6f0e-5a52-4c61-9a37-0c1d2e3f4a5b");

    @Test
    void theRideIsKeptThroughEveryRoughLandingOfADrag() {
        RideTarget ride = new RideTarget();
        ride.ride(RECORDER, 12);
        for (int tick = 400; tick > 0; tick -= 37) {
            assertFalse(ride.missing(tick, true));
            assertFalse(ride.landed(false, true));
        }
        assertEquals(RECORDER, ride.uuid());
    }

    @Test
    void anExactLandingWithoutThemGivesUp() {
        RideTarget ride = new RideTarget();
        ride.ride(RECORDER, 12);
        assertFalse(ride.landed(true, false));
        assertTrue(ride.landed(false, false));
    }

    @Test
    void inPlainPlayARespawnIsWaitedFor() {
        RideTarget ride = new RideTarget();
        ride.ride(RECORDER, 12);
        assertFalse(ride.missing(1000, false));
        assertFalse(ride.missing(1000 + RideTarget.LOST_TICKS, false));
        ride.seen(57);
        assertEquals(57, ride.id());
        assertFalse(ride.missing(2000, false));
        assertTrue(ride.missing(2001 + RideTarget.LOST_TICKS, false));
    }

    @Test
    void timeGoneIsCountedFromWhereAJumpLands() {
        RideTarget ride = new RideTarget();
        ride.ride(RECORDER, 12);
        assertFalse(ride.missing(5000, false));
        // A jump far back while they were missing: not counted as five thousand ticks gone
        assertFalse(ride.missing(100, true));
        assertFalse(ride.missing(150, false));
    }

    @Test
    void nobodyRiddenGivesNothingUp() {
        RideTarget ride = new RideTarget();
        assertFalse(ride.landed(false, false));
        assertFalse(ride.missing(10, false));
        ride.ride(RECORDER, 3);
        ride.clear();
        assertNull(ride.uuid());
        assertEquals(-1, ride.id());
    }

}
