package comedymonkey.dailies.playback;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which snapshot a jump starts from */
class JumpStartTest {

    private static final int RUN_UP = 60;
    /** Segments starting at these ticks; the last does not follow on from the one before */
    private static final int[] STARTS = {0, 1000, 1150, 3000};

    private static int segmentAt(int tick) {
        int found = 0;
        for (int i = 0; i < STARTS.length; i++) {
            if (STARTS[i] <= tick) {
                found = i;
            }
        }
        return found;
    }

    private static int start(int target, int position, boolean quick, boolean again) {
        return JumpStart.segment(target, position, quick, again, RUN_UP, JumpStartTest::segmentAt, index -> STARTS[index],
            index -> index > 0 && index < 3);
    }

    @Test
    void aShortJumpAheadPlaysOn() {
        assertEquals(-1, start(1300, 1200, false, false));
    }

    @Test
    void aJumpBackStartsFromTheSnapshotBefore() {
        assertEquals(1, start(1100, 1200, false, false));
        assertEquals(0, start(500, 1200, false, false));
    }

    @Test
    void aJumpFarAheadStartsFromTheNearerSnapshot() {
        assertEquals(2, start(1400, 900, false, false));
    }

    @Test
    void landingJustAfterASnapshotStartsFromTheOneBeforeForTheRunUp() {
        assertEquals(1, start(1160, 1300, false, false));
        // Unless the jump is made while dragging, which lands sooner
        assertEquals(2, start(1160, 1300, true, false));
        // Or the snapshot does not follow on from the one before
        assertEquals(3, start(3010, 3100, false, false));
    }

    @Test
    void aJumpMadeAgainKeepsTheRunUp() {
        // The playhead was dragged to 1160 and left there; refining reaches the same tick exactly, run-up and all
        assertEquals(1, start(1160, 1160, false, true));
        // Well past a snapshot, from that snapshot
        assertEquals(2, start(1300, 1300, false, true));
    }

    @Test
    void eachJumpIsDecidedFromWhereTheRecordingStands() {
        // At 1000, ten seconds on and straight back in one frame: the first would start from the snapshot at 1150, the
        // second plays on from where the recording still is, landing where it was asked to
        assertEquals(2, start(1250, 1000, false, false));
        assertEquals(-1, start(1000, 1000, false, false));
    }

}
