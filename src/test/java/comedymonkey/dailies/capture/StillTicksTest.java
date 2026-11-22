package comedymonkey.dailies.capture;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Ticks recorded while the game stands paused under a play screen (specification REC-36) */
class StillTicksTest {

    @Test
    void aTickTheWorldRanIsRecordedWithoutAskingAboutTheScreen() {
        assertEquals(StillTicks.Kind.MOVING, StillTicks.of(false, () -> fail("the screen is asked about only while the game stands paused")));
    }

    @Test
    void pausedUnderAPlayScreenTheRecordingKeepsTime() {
        assertEquals(StillTicks.Kind.STILL, StillTicks.of(true, () -> true));
    }

    @Test
    void pausedUnderAnyOtherScreenTheRecordingsClockStops() {
        // The pause menu, settings, or a mod's screen built on a book that the book's claim turns down
        assertEquals(StillTicks.Kind.NONE, StillTicks.of(true, () -> false));
    }

    @Test
    void noStillRecordSinceTheWorldWasBuiltMeansNothingStands() {
        assertFalse(StillTicks.stands(-1, 0));
        assertFalse(StillTicks.stands(-1, 40));
    }

    @Test
    void aStillRecordStandsOnlyForTheTickItWasReadAt() {
        assertTrue(StillTicks.stands(41, 41));
        assertFalse(StillTicks.stands(40, 41));
    }

    /**
     * The records as the recorder writes them and playback reads them: each recorded tick writes its still record, if
     * any, then its count; a segment begins with a snapshot of the tick before. Playback loads a segment by reading up to
     * its first count, then each tick reads a count and everything up to the next. The world playback ticks after
     * reaching tick p stands for the live game's tick p + 1, so it must stand exactly when that tick was still, from the
     * start of the recording and from a snapshot taken in the middle of a still stretch, at its edges and inside it
     */
    @Test
    void playbackStandsForExactlyTheTicksTheLiveGameStoodPausedFor() {
        // Live ticks 1 to 30: the world runs, a book is read from tick 6 to 17, the world runs, a book again from 22 to 23
        boolean[] still = new boolean[32];
        for (int tick = 6; tick <= 17; tick++) {
            still[tick] = true;
        }
        still[22] = true;
        still[23] = true;
        List<String> records = new ArrayList<>();
        List<Integer> segmentAt = new ArrayList<>();
        List<Integer> segmentStart = new ArrayList<>();
        for (int tick = 1; tick <= 30; tick++) {
            if (tick == 1 || tick == 10 || tick == 18 || tick == 22) {
                segmentAt.add(records.size());
                segmentStart.add(tick - 1);
                records.add("snapshot");
            }
            if (still[tick]) {
                records.add("still");
            }
            records.add("tick");
        }

        for (int segment = 0; segment < segmentAt.size(); segment++) {
            int at = segmentAt.get(segment);
            int position = segmentStart.get(segment);
            int readAt = -1;
            // The load: everything up to the first count
            while (!records.get(at).equals("tick")) {
                if (records.get(at).equals("still")) {
                    readAt = position;
                }
                at++;
            }
            // Each tick: its count, then everything up to the next
            while (at < records.size()) {
                at++;
                position++;
                while (at < records.size() && !records.get(at).equals("tick")) {
                    if (records.get(at).equals("still")) {
                        readAt = position;
                    }
                    at++;
                }
                if (position < 30) {
                    assertEquals(still[position + 1], StillTicks.stands(readAt, position),
                        "from the segment at tick " + segmentStart.get(segment) + ", the world after tick " + position);
                }
            }
        }
    }

}
