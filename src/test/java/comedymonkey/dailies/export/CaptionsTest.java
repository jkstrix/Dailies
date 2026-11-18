package comedymonkey.dailies.export;

import comedymonkey.dailies.edit.Interpolation;
import comedymonkey.dailies.edit.Keyframe;
import comedymonkey.dailies.edit.KeyframeKind;
import comedymonkey.dailies.edit.Scene;
import comedymonkey.dailies.edit.TimeMap;
import comedymonkey.dailies.edit.Track;
import comedymonkey.dailies.moments.MomentsTranscript;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Captions timed in video time and written as SRT and WebVTT (specification EXP-11, EXP-15) */
class CaptionsTest {

    private static final double CLOSE = 1e-6;

    /** A line of evenly timed words from one tick to another */
    private static MomentsTranscript.Line line(String speaker, double from, double to, String text) {
        List<MomentsTranscript.Word> words = new ArrayList<>();
        String[] parts = text.split(" ");
        double share = (to - from) / parts.length;
        for (int i = 0; i < parts.length; i++) {
            words.add(new MomentsTranscript.Word(parts[i], from + i * share, from + (i + 1) * share, true));
        }
        return new MomentsTranscript.Line(speaker.toLowerCase(), speaker, from, to, text, words);
    }

    private static MomentsTranscript.Transcript transcript(MomentsTranscript.Line... lines) {
        return new MomentsTranscript.Transcript("rec", List.of(lines));
    }

    @Test
    void plainPlaybackIsTwentyTicksASecond() {
        Scene scene = new Scene("Test");
        TimeMap map = new TimeMap(scene, 100, 500);
        List<Captions.Cue> cues = Captions.cues(transcript(line("Ann", 140, 180, "look at that")), map::realSecondsAt, map.totalRealSeconds());
        assertEquals(1, cues.size());
        assertEquals(2.0, cues.get(0).start(), CLOSE);
        assertEquals(4.0, cues.get(0).end(), CLOSE);
        assertEquals(3, cues.get(0).words().size());
    }

    @Test
    void halfSpeedDoublesTheTimes() {
        Scene scene = new Scene("Test");
        Track speed = new Track(KeyframeKind.SPEED);
        speed.put(new Keyframe.Speed(0, Interpolation.HOLD, 10.0));
        scene.addTrack(speed);
        TimeMap map = new TimeMap(scene, 100, 500);
        List<Captions.Cue> cues = Captions.cues(transcript(line("Ann", 140, 180, "slow motion")), map::realSecondsAt, map.totalRealSeconds());
        assertEquals(4.0, cues.get(0).start(), 1e-3);
        assertEquals(8.0, cues.get(0).end(), 1e-3);
    }

    @Test
    void aFreezePushesLaterWordsBack() {
        Scene scene = new Scene("Test");
        Track freeze = new Track(KeyframeKind.FREEZE);
        freeze.put(new Keyframe.Freeze(150, Interpolation.HOLD, 3.0));
        scene.addTrack(freeze);
        TimeMap map = new TimeMap(scene, 100, 500);
        List<Captions.Cue> cues = Captions.cues(transcript(line("Ann", 140, 160, "before after")), map::realSecondsAt, map.totalRealSeconds());
        Captions.Cue cue = cues.get(0);
        // "before" runs from tick 140 to 150, "after" from 150 to 160 with the three seconds' hold in between
        assertEquals(2.0, cue.words().get(0).start(), CLOSE);
        assertEquals(2.5, cue.words().get(0).end(), CLOSE);
        assertEquals(2.5, cue.words().get(1).start(), CLOSE);
        assertEquals(6.0, cue.words().get(1).end(), CLOSE);
    }

    @Test
    void aTimelapseSqueezesWordsButLeavesThemReadable() {
        Scene scene = new Scene("Test");
        Track lapse = new Track(KeyframeKind.TIMELAPSE);
        lapse.put(new Keyframe.Timelapse(100, Interpolation.HOLD, 1.0));
        lapse.put(new Keyframe.Timelapse(500, Interpolation.HOLD, 1.0));
        scene.addTrack(lapse);
        TimeMap map = new TimeMap(scene, 100, 500);
        List<Captions.Cue> cues = Captions.cues(transcript(line("Ann", 140, 160, "quick")), map::realSecondsAt, map.totalRealSeconds());
        Captions.Cue cue = cues.get(0);
        assertEquals(0.1, cue.start(), CLOSE);
        assertEquals(Captions.SHORTEST, cue.end() - cue.start(), CLOSE);
    }

    @Test
    void aLongLineIsShownAPieceAtATime() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            text.append(i == 0 ? "" : " ").append("word").append(i);
        }
        Scene scene = new Scene("Test");
        TimeMap map = new TimeMap(scene, 0, 2000);
        List<Captions.Cue> cues = Captions.cues(transcript(line("Ann", 0, 400, text.toString())), map::realSecondsAt, map.totalRealSeconds());
        assertTrue(cues.size() > 1);
        for (Captions.Cue cue : cues) {
            assertTrue(cue.text().length() <= Captions.CUE_CHARACTERS, cue.text());
        }
        for (int i = 1; i < cues.size(); i++) {
            assertTrue(cues.get(i).start() >= cues.get(i - 1).end() - CLOSE, "pieces of one line overlap");
        }
    }

    @Test
    void srtIsNumberedAndUsesCommas() {
        List<Captions.Cue> cues = List.of(new Captions.Cue(1.5, 3.25, "Ann", "hello there", List.of()),
            new Captions.Cue(3661.001, 3662.0, "Bob", "hi", List.of()));
        String srt = Captions.srt(cues, true);
        assertEquals("1\n00:00:01,500 --> 00:00:03,250\nAnn: hello there\n\n2\n01:01:01,001 --> 01:01:02,000\nBob: hi\n\n", srt);
        assertEquals("1\n00:00:01,500 --> 00:00:03,250\nhello there\n\n2\n01:01:01,001 --> 01:01:02,000\nhi\n\n", Captions.srt(cues, false));
    }

    @Test
    void vttHasAHeaderStopsAndVoices() {
        List<Captions.Cue> cues = List.of(new Captions.Cue(0.0, 2.0, "Ann", "a <b> & c", List.of()));
        assertEquals("WEBVTT\n\n00:00:00.000 --> 00:00:02.000\n<v Ann>Ann: a &lt;b&gt; &amp; c\n\n", Captions.vtt(cues, true));
        assertEquals("WEBVTT\n\n00:00:00.000 --> 00:00:02.000\na &lt;b&gt; &amp; c\n\n", Captions.vtt(cues, false));
    }

    @Test
    void rowsBreakAtSpaces() {
        assertEquals(List.of("one two", "three"), Captions.rows("one two three", 8));
        assertEquals(List.of("abcdefghij"), Captions.rows("abcdefghij", 4));
    }

    @Test
    void whatIsDrawnFollowsTheStyle() {
        Scene scene = new Scene("Test");
        TimeMap map = new TimeMap(scene, 0, 400);
        List<Captions.Cue> cues = Captions.cues(transcript(line("Ann", 20, 80, "one two three"), line("Bob", 40, 60, "yes")), map::realSecondsAt, map.totalRealSeconds());
        assertNull(Captions.at(cues, 0.5, Captions.Style.LINES, false));
        assertEquals("one two three", Captions.at(cues, 1.2, Captions.Style.LINES, false));
        assertEquals("Ann: one two three\nBob: yes", Captions.at(cues, 2.5, Captions.Style.LINES, true));
        assertEquals("one", Captions.at(cues, 1.2, Captions.Style.WORD_BY_WORD, false));
        assertEquals("three", Captions.at(cues, 3.5, Captions.Style.WORD_BY_WORD, false));
        // Bob began last, so he has the floor while he speaks
        assertEquals("Bob: yes", Captions.at(cues, 2.5, Captions.Style.WORD_BY_WORD, true));
        assertNull(Captions.at(cues, 4.5, Captions.Style.WORD_BY_WORD, false));
    }

    @Test
    void fileChoicesAreChecked() {
        assertTrue(Captions.files("both")[0] && Captions.files("both")[1]);
        assertTrue(!Captions.files("none")[0] && !Captions.files("none")[1]);
        assertThrows(IllegalArgumentException.class, () -> Captions.files("ass"));
        assertEquals(Captions.Style.WORD_BY_WORD, Captions.style("word by word"));
        assertEquals(Captions.Style.LINES, Captions.style(""));
    }

}
