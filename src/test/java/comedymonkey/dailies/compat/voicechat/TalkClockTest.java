package comedymonkey.dailies.compat.voicechat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The clock voice chat's talking icons are timed by in playback */
class TalkClockTest {

    /** How long voice chat keeps an icon lit after the last piece of voice */
    private static final long LIT_MILLIS = 250L;

    @Test
    void itFollowsTheRecordingAndStandsStillWithIt() {
        TalkClock clock = new TalkClock();
        long start = clock.at(100);
        assertEquals(start + 50L, clock.at(101));
        assertEquals(start + 250L, clock.at(105));
        // Paused: however often it is read, the time stays, so who was talking at this tick stays lit
        assertEquals(start + 250L, clock.at(105));
        assertEquals(start + 250L, clock.at(105));
    }

    @Test
    void goingBackPutsOutWhatWasLit() {
        TalkClock clock = new TalkClock();
        long spoke = clock.at(400);
        long back = clock.at(200);
        assertTrue(back - spoke >= LIT_MILLIS, "an icon from tick 400 must not stay lit at tick 200");
        assertEquals(back + 50L, clock.at(201));
    }

    @Test
    void aLeapPutsOutEverythingWithoutTheTickChanging() {
        TalkClock clock = new TalkClock();
        long spoke = clock.at(10);
        clock.leap();
        assertTrue(clock.at(10) - spoke >= LIT_MILLIS);
    }

    @Test
    void itNeverGoesBackwards() {
        TalkClock clock = new TalkClock();
        long last = clock.at(0);
        for (int tick : new int[]{5, 3, 3, 40, 0, 1, 1000, 999}) {
            long now = clock.at(tick);
            assertTrue(now >= last, "at tick " + tick);
            last = now;
        }
    }

}
