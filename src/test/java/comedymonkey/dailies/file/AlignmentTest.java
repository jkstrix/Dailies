package comedymonkey.dailies.file;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lining up two players' recordings of one evening by the server's game time (FILE-16, PLAY-18) */
class AlignmentTest {

    private static final long EVENING = 1_790_000_000_000L;

    /** Mine: started at game time 10000, paused for a minute after 600 ticks, went on for 400 more */
    private static Alignment mine() {
        return Alignment.of(List.of(
            new Alignment.Run(0, 600, 10_000, EVENING),
            new Alignment.Run(600, 1000, 11_800, EVENING + 90_000)));
    }

    /** A friend's: pressed record 200 ticks after me and kept going, their computer's clock 7 seconds fast */
    private static Alignment theirs() {
        return Alignment.of(List.of(new Alignment.Run(0, 2000, 10_200, EVENING + 10_000 + 7_000)));
    }

    @Test
    void aTickLinesUpWithTheTickOfTheSameGameTimeThere() {
        assertEquals(10_250, mine().gameAt(250));
        assertEquals(50, mine().map(250, theirs()), "200 ticks later on their side of the same moment");
        assertEquals(1_800 - 200 + 100, mine().map(700, theirs()), "after my pause, by game time, not by my ticks");
        assertEquals(-1, mine().map(100, theirs()), "they were not recording yet");
        assertEquals(250, theirs().map(50, mine()));
        assertEquals(-1, theirs().map(800, mine()), "I had paused then");
        assertEquals(50.25, mine().map(250.25, theirs()), 1e-9, "a moment between ticks goes across as it is");
    }

    @Test
    void theBoundaryTickBelongsToTheLaterRun() {
        assertEquals(11_800, mine().gameAt(600));
        assertEquals(-1, mine().tickAtGame(10_700), "game time I never recorded");
        assertEquals(600, mine().tickAtGame(11_800));
    }

    @Test
    void sharedStretchesAreListedInThisRecordingsTicks() {
        // My pause is a jump in game time, not in my ticks: all of my ticks from 200 on are also theirs
        List<int[]> shared = mine().shared(theirs());
        assertEquals(1, shared.size());
        assertArrayEquals(new int[]{200, 1000}, shared.get(0));
        assertEquals(801, mine().sharedTicks(theirs()));
        List<int[]> back = theirs().shared(mine());
        assertEquals(2, back.size(), "on their side my pause leaves a gap");
        assertArrayEquals(new int[]{0, 400}, back.get(0));
        assertArrayEquals(new int[]{1600, 2000}, back.get(1));
    }

    @Test
    void touchingStretchesAreOne() {
        Alignment straight = Alignment.of(List.of(new Alignment.Run(0, 100, 0, EVENING), new Alignment.Run(100, 200, 100, EVENING + 5_000)));
        Alignment other = Alignment.of(List.of(new Alignment.Run(0, 500, 0, EVENING)));
        List<int[]> shared = straight.shared(other);
        assertEquals(1, shared.size());
        assertArrayEquals(new int[]{0, 200}, shared.get(0));
    }

    @Test
    void theSameStretchNeedsGameTimeInCommonAndClocksThatAgree() {
        assertTrue(mine().sameStretch(theirs()));
        Alignment anotherDay = Alignment.of(List.of(new Alignment.Run(0, 2000, 10_200, EVENING + 86_400_000L)));
        assertFalse(mine().sameStretch(anotherDay), "the same game time a day later is another world");
        Alignment brief = Alignment.of(List.of(new Alignment.Run(0, 10, 10_590, EVENING + 29_500)));
        assertFalse(mine().sameStretch(brief), "half a second in common is not worth a point of view");
        assertFalse(mine().sameStretch(Alignment.of(List.of())), "a recording without a time index lines up with nothing");
    }

    @Test
    void anEditOfTheSameFootageIsNotAnotherPointOfView() {
        assertTrue(Alignment.sameFootage("a", Set.of("a"), "a", Set.of("a")));
        assertTrue(Alignment.sameFootage("a", Set.of("a"), "t", Set.of("a")), "a trim of mine");
        assertTrue(Alignment.sameFootage("c1", Set.of("x", "y"), "c2", Set.of("y", "z")), "two edits sharing an original");
        assertFalse(Alignment.sameFootage("mine", Set.of("mine"), "theirs", Set.of("theirs")));
    }

    @Test
    void aDescriptionGivesItsRuns() {
        RecordingManifest manifest = new RecordingManifest();
        manifest.segments.add(new RecordingManifest.Segment(1, 0, 600));
        manifest.segments.add(new RecordingManifest.Segment(2, 600, 1000));
        manifest.times.add(new RecordingManifest.SegmentTime(1, EVENING, 10_000));
        manifest.times.add(new RecordingManifest.SegmentTime(2, EVENING + 90_000, 11_800));
        Alignment runs = Alignment.of(manifest);
        assertEquals(2, runs.runs().size());
        assertEquals(50, runs.map(250, theirs()));
        manifest.times.clear();
        assertTrue(Alignment.of(manifest).isEmpty(), "made before the time index");
    }

}
