package comedymonkey.dailies.file;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The source map arithmetic of specification FILE-11, with the plan's own numbers */
class SpansTest {

    /** The example in the plan: an hour of mining cut out of A, then the next evening's session B */
    private static final List<Spans.Span> EPISODE = List.of(
        new Spans.Span("A", 0, 36_000, 0),
        new Spans.Span("A", 48_000, 72_000, 36_000),
        new Spans.Span("B", 0, 20_000, 60_000));

    @Test
    void aMomentOfAnOriginalIsFoundWhereItsSpanPutsIt() {
        assertEquals(38_000, Spans.toHere(EPISODE, "A", 50_000));
        assertEquals(0, Spans.toHere(EPISODE, "A", 0));
        assertEquals(35_999, Spans.toHere(EPISODE, "A", 35_999));
        assertEquals(36_000, Spans.toHere(EPISODE, "A", 48_000));
        assertEquals(60_000, Spans.toHere(EPISODE, "B", 0));
        assertEquals(79_999, Spans.toHere(EPISODE, "B", 19_999));
    }

    @Test
    void aMomentInARemovedStretchHasNoPlace() {
        assertEquals(-1, Spans.toHere(EPISODE, "A", 40_000));
        assertEquals(-1, Spans.toHere(EPISODE, "A", 47_999));
        assertEquals(-1, Spans.toHere(EPISODE, "A", 90_000));
        assertEquals(-1, Spans.toHere(EPISODE, "C", 10));
        assertEquals(-1, Spans.toHere(List.of(), "A", 10));
    }

    @Test
    void theVeryEndOfAStretchCountsAsTheTickAfterIt() {
        // B's last moment is the end of the recording
        assertEquals(80_000, Spans.toHere(EPISODE, "B", 20_000));
        // A's tick 36,000 is where the cut begins: the world there is the one the first stretch ends on
        assertEquals(36_000, Spans.toHere(EPISODE, "A", 36_000));
        // The end of one stretch that is the start of the next belongs to the next
        List<Spans.Span> split = List.of(new Spans.Span("A", 0, 100, 0), new Spans.Span("A", 100, 200, 500));
        assertEquals(500, Spans.toHere(split, "A", 100));
    }

    @Test
    void aTickHereGoesBackToItsOriginal() {
        assertEquals(new Spans.Place("A", 50_000), Spans.toSource(EPISODE, 38_000));
        assertEquals(new Spans.Place("A", 0), Spans.toSource(EPISODE, 0));
        assertEquals(new Spans.Place("B", 5), Spans.toSource(EPISODE, 60_005));
        assertNull(Spans.toSource(EPISODE, 80_000));
        assertNull(Spans.toSource(EPISODE, -1));
        for (int here : new int[]{0, 1, 35_999, 36_000, 59_999, 60_000, 79_999}) {
            Spans.Place place = Spans.toSource(EPISODE, here);
            assertEquals(here, Spans.toHere(EPISODE, place.source(), place.tick()));
        }
    }

    @Test
    void aPlainRecordingStandsForItself() {
        List<Spans.Span> own = Spans.effective(List.of(), "A", 500);
        assertEquals(List.of(new Spans.Span("A", 0, 500, 0)), own);
        assertEquals(123, Spans.toHere(own, "A", 123));
        assertEquals(EPISODE, Spans.effective(EPISODE, "X", 80_000));
        assertTrue(Spans.own("A", 0).isEmpty());
    }

    @Test
    void trimmingAPlainRecordingGivesThePlansSpans() {
        List<Spans.Range> keep = Spans.invert(List.of(new Spans.Range(36_000, 48_000)), 72_000);
        assertEquals(List.of(new Spans.Range(0, 36_000), new Spans.Range(48_000, 72_000)), keep);
        assertEquals(EPISODE.subList(0, 2), Spans.slice(Spans.own("A", 72_000), keep));
    }

    @Test
    void combiningAddsNoTime() {
        List<Spans.Span> trimmed = EPISODE.subList(0, 2);
        List<Spans.Span> joined = Spans.join(List.of(trimmed, Spans.own("B", 20_000)), List.of(60_000, 20_000));
        assertEquals(EPISODE, joined);
        int length = joined.stream().mapToInt(Spans.Span::length).sum();
        assertEquals(80_000, length);
        assertEquals(80_000, joined.get(joined.size() - 1).hereEnd());
    }

    @Test
    void anEditOfAnEditStillPointsAtTheOriginals() {
        // The episode is trimmed again: its first ten thousand ticks go, and so does a stretch across the join with B
        List<Spans.Range> keep = List.of(new Spans.Range(10_000, 59_000), new Spans.Range(61_000, 80_000));
        List<Spans.Span> second = Spans.slice(EPISODE, keep);
        assertEquals(List.of(
            new Spans.Span("A", 10_000, 36_000, 0),
            new Spans.Span("A", 48_000, 71_000, 26_000),
            new Spans.Span("B", 1_000, 20_000, 49_000)), second);
        // The moment at (A, 50,000) was at 38,000 in the episode, and the trim took 10,000 ticks from before it
        assertEquals(28_000, Spans.toHere(second, "A", 50_000));
        // Mapping through the two edits one after the other gives the same as the composed spans
        for (int tick : new int[]{10_000, 20_000, 35_999, 48_000, 60_000, 70_999}) {
            int inEpisode = Spans.toHere(EPISODE, "A", tick);
            assertEquals(Spans.remap(keep, inEpisode), Spans.toHere(second, "A", tick), "tick " + tick);
        }
        assertEquals(-1, Spans.toHere(second, "A", 5_000));
        assertEquals(-1, Spans.toHere(second, "B", 500));
        // A third edit: combined with a plain recording C in front
        List<Spans.Span> third = Spans.join(List.of(Spans.own("C", 400), second), List.of(400, 68_000));
        assertEquals(28_400, Spans.toHere(third, "A", 50_000));
        assertEquals(new Spans.Place("B", 1_000), Spans.toSource(third, 49_400));
        assertTrue(third.stream().noneMatch(span -> span.source().equals("episode")));
    }

    @Test
    void theSameFootageTwiceIsFoundAtItsFirstPlace() {
        List<Spans.Span> twice = Spans.join(List.of(Spans.own("A", 100), Spans.own("A", 100)), List.of(100, 100));
        assertEquals(2, twice.size());
        assertEquals(40, Spans.toHere(twice, "A", 40));
        assertEquals(new Spans.Place("A", 40), Spans.toSource(twice, 140));
    }

    @Test
    void aClipThatSurvivesWholeIsOneStretchAndNotCut() {
        Spans.Clip clip = Spans.clip(EPISODE, "A", 50_000, 50_600);
        assertEquals(List.of(new Spans.Stretch(38_000, 38_600, 50_000, 50_600)), clip.stretches());
        assertFalse(clip.cut());
        assertEquals(0, clip.removed());
        assertTrue(clip.present());
    }

    @Test
    void aClipWithACutInsidePlaysAsTwoPiecesBackToBackAndIsFlagged() {
        // From 300 ticks before the cut to 200 ticks after the 12,000 that were removed
        Spans.Clip clip = Spans.clip(EPISODE, "A", 35_700, 48_200);
        assertEquals(List.of(new Spans.Stretch(35_700, 36_000, 35_700, 36_000), new Spans.Stretch(36_000, 36_200, 48_000, 48_200)), clip.stretches());
        assertTrue(clip.cut());
        assertEquals(12_000, clip.removed());
        // Back to back: the second piece starts on the tick the first ends on
        assertEquals(clip.stretches().get(0).hereEnd(), clip.stretches().get(1).here());
    }

    @Test
    void aClipCutAtOneEndKeepsTheRest() {
        Spans.Clip clip = Spans.clip(EPISODE, "A", 47_000, 48_500);
        assertEquals(List.of(new Spans.Stretch(36_000, 36_500, 48_000, 48_500)), clip.stretches());
        assertTrue(clip.cut());
        assertEquals(1_000, clip.removed());
    }

    @Test
    void aClipInARemovedStretchIsGoneAndSaysHowMuch() {
        Spans.Clip clip = Spans.clip(EPISODE, "A", 40_000, 41_000);
        assertFalse(clip.present());
        assertTrue(clip.cut());
        assertEquals(1_000, clip.removed());
        assertFalse(Spans.clip(EPISODE, "C", 0, 10).present());
    }

    @Test
    void aClipOfNoLengthIsOneTick() {
        Spans.Clip clip = Spans.clip(EPISODE, "B", 7, 7);
        assertEquals(List.of(new Spans.Stretch(60_007, 60_008, 7, 8)), clip.stretches());
        assertFalse(clip.cut());
    }

    @Test
    void footageThatIsHereTwiceDoesNotHideAMissingPart() {
        List<Spans.Span> twice = List.of(new Spans.Span("A", 0, 50, 0), new Spans.Span("A", 0, 50, 50));
        Spans.Clip clip = Spans.clip(twice, "A", 0, 100);
        assertEquals(2, clip.stretches().size());
        assertTrue(clip.cut());
        assertEquals(50, clip.removed());
    }

    @Test
    void ticksOfTheRecordingItselfMoveWithTheKeptRanges() {
        List<Spans.Range> keep = List.of(new Spans.Range(100, 200), new Spans.Range(500, 600));
        assertEquals(0, Spans.remap(keep, 100));
        assertEquals(99, Spans.remap(keep, 199));
        assertEquals(-1, Spans.remap(keep, 200));
        assertEquals(-1, Spans.remap(keep, 50));
        assertEquals(100, Spans.remap(keep, 500));
        assertEquals(-1, Spans.remap(keep, 600));
    }

    @Test
    void rangesArePutInOrderJoinedAndKeptInsideTheRecording() {
        List<Spans.Range> messy = List.of(new Spans.Range(500, 700), new Spans.Range(-20, 100), new Spans.Range(90, 200), new Spans.Range(200, 250), new Spans.Range(900, 2_000),
            new Spans.Range(300, 300));
        assertEquals(List.of(new Spans.Range(0, 250), new Spans.Range(500, 700), new Spans.Range(900, 1_000)), Spans.normalise(messy, 1_000));
        assertEquals(List.of(new Spans.Range(250, 500), new Spans.Range(700, 900)), Spans.invert(messy, 1_000));
        assertEquals(List.of(new Spans.Range(0, 1_000)), Spans.invert(List.of(), 1_000));
        assertTrue(Spans.invert(List.of(new Spans.Range(0, 1_000)), 1_000).isEmpty());
    }

    @Test
    void stretchesThatFollowOnAreMadeOne() {
        List<Spans.Span> pieces = List.of(new Spans.Span("A", 100, 200, 100), new Spans.Span("A", 0, 100, 0), new Spans.Span("A", 300, 400, 200), new Spans.Span("B", 0, 0, 300));
        assertEquals(List.of(new Spans.Span("A", 0, 200, 0), new Spans.Span("A", 300, 400, 200)), Spans.merge(pieces));
    }

}
