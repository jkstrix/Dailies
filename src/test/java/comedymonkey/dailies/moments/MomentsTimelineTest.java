package comedymonkey.dailies.moments;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import comedymonkey.dailies.file.Spans;
import comedymonkey.dailies.moments.MomentsTimeline.Band;
import comedymonkey.dailies.moments.MomentsTimeline.Cluster;
import comedymonkey.dailies.moments.MomentsTimeline.Mark;
import comedymonkey.dailies.moments.MomentsTimeline.Timeline;
import comedymonkey.dailies.smartcamera.SmartCameraMath;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Moments on the editor's timeline (specification EDIT-9): through a source map, and gathered where they would overlap */
class MomentsTimelineTest {

    private static final String ORIGINAL = "original";
    private static final BiPredicate<String, String> NOTHING_IGNORED = (type, subject) -> false;

    /** A recording's own source map as the thing that says where moments are, exactly as the core answers */
    private static MomentsTimeline.Mapper through(List<Spans.Span> spans) {
        return new MomentsTimeline.Mapper() {
            @Override
            public int tick(String recordingId, int tick) {
                return Spans.toHere(spans, recordingId, tick);
            }

            @Override
            public List<int[]> stretch(String recordingId, int start, int end) {
                List<int[]> pieces = new ArrayList<>();
                for (Spans.Span span : spans) {
                    int from = Math.max(start, span.sourceStart());
                    int to = Math.min(end, span.sourceEnd());
                    if (span.source().equals(recordingId) && to > from) {
                        pieces.add(new int[]{span.here() + (from - span.sourceStart()), span.here() + (to - span.sourceStart())});
                    }
                }
                return pieces;
            }
        };
    }

    private static JsonObject event(String id, String type, String subject, int tick) {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("type", type);
        json.addProperty("subject", subject);
        json.addProperty("replay_uuid", ORIGINAL);
        json.addProperty("replay_tick", tick);
        return json;
    }

    private static JsonObject clip(String id, String title, int start, int end, JsonObject... events) {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("title", title);
        json.addProperty("importance", 55.0);
        json.addProperty("replay_uuid", ORIGINAL);
        json.addProperty("start_replay_tick", start);
        json.addProperty("end_replay_tick", end);
        JsonArray inside = new JsonArray();
        for (JsonObject event : events) {
            inside.add(event);
        }
        json.add("events", inside);
        return json;
    }

    private static JsonObject talk(String speaker, int start, int end, String status, String text) {
        JsonObject who = new JsonObject();
        who.addProperty("uuid", speaker);
        who.addProperty("name", speaker.toUpperCase());
        JsonObject json = new JsonObject();
        json.add("speaker", who);
        json.addProperty("start_replay_uuid", ORIGINAL);
        json.addProperty("start_replay_tick", start);
        json.addProperty("end_replay_uuid", ORIGINAL);
        json.addProperty("end_replay_tick", end);
        json.addProperty("status", status);
        json.addProperty("text", text);
        return json;
    }

    private static MomentsTimeline.Source session(List<JsonObject> events, List<JsonObject> clips, List<JsonObject> dialogue) {
        return new MomentsTimeline.Source("s1", events, clips, dialogue, event -> event.get("replay_tick").getAsInt() / 10.0,
            event -> "what happened in " + event.get("id").getAsString());
    }

    private static final JsonObject KILL = event("e1", "mob_kill", "minecraft:pig", 300);
    private static final JsonObject EARLY = event("e2", "biome_discovered", "minecraft:plains", 40);
    private static final JsonObject LATE = event("e3", "marker", "Good bit", 900);
    private static final MomentsTimeline.Source SESSION = session(
        List.of(KILL, EARLY, LATE),
        List.of(clip("c1", "The pig", 250, 450, KILL), clip("c2", "The end", 880, 950, LATE)),
        List.of(talk("dev", 280, 330, "done", "Got it"), talk("sam", 100, 140, "pending", ""), talk("sam", 600, 900, "empty", "")));

    @Test
    void anUneditedRecordingHoldsItsMomentsWhereTheyWereMade() {
        Timeline timeline = MomentsTimeline.build(ORIGINAL, List.of(SESSION), through(Spans.own(ORIGINAL, 1000)), NOTHING_IGNORED, 1000);
        assertEquals(List.of(40, 300, 900), timeline.marks().stream().map(Mark::tick).toList(), "marks come in the order of their ticks");
        Mark kill = timeline.marks().get(1);
        assertEquals("mob_kill", kill.kind());
        assertEquals("what happened in e1", kill.description());
        assertEquals(30.0, kill.score());
        assertEquals("e1", kill.eventId());
        assertEquals("s1", kill.sessionId());

        assertEquals(2, timeline.bands().size());
        Band pig = timeline.bands().get(0);
        assertEquals(250, pig.start());
        assertEquals(450, pig.end());
        assertEquals("The pig", pig.title());
        assertFalse(pig.cut());

        // Whoever talked most comes first
        assertEquals(List.of("SAM", "DEV"), timeline.strips().stream().map(MomentsTimeline.Strip::speaker).toList());
        assertEquals("Got it", timeline.strips().get(1).talks().get(0).words());
        assertEquals("", timeline.strips().get(0).talks().get(0).words(), "what has not been written down has no words");
        assertEquals(100, timeline.strips().get(0).talks().get(0).start());
    }

    @Test
    void aTrimmedRecordingHoldsWhatWasKeptAtItsNewTicks() {
        // Ticks 200 to 500 of the original were kept
        List<Spans.Span> spans = Spans.slice(Spans.own(ORIGINAL, 1000), List.of(new Spans.Range(200, 500)));
        Timeline timeline = MomentsTimeline.build("trimmed", List.of(SESSION), through(spans), NOTHING_IGNORED, 300);
        assertEquals(1, timeline.marks().size(), "the moments before and after the kept stretch were cut");
        assertEquals(100, timeline.marks().get(0).tick());
        assertEquals("e1", timeline.marks().get(0).eventId());

        assertEquals(1, timeline.bands().size());
        assertEquals(50, timeline.bands().get(0).start());
        assertEquals(250, timeline.bands().get(0).end());
        assertFalse(timeline.bands().get(0).cut());

        assertEquals(1, timeline.strips().size(), "the speaker whose words were all cut has no strip");
        assertEquals("DEV", timeline.strips().get(0).speaker());
        assertEquals(80, timeline.strips().get(0).talks().get(0).start());
        assertEquals(130, timeline.strips().get(0).talks().get(0).end());
    }

    @Test
    void aCutThroughAClipLeavesABandOverWhatIsLeftAndSaysSo() {
        // Ticks 300 to 400 were cut, through the middle of the first clip and just as the kill happened
        List<Spans.Span> spans = Spans.slice(Spans.own(ORIGINAL, 1000), List.of(new Spans.Range(0, 300), new Spans.Range(400, 1000)));
        Timeline timeline = MomentsTimeline.build("cut", List.of(SESSION), through(spans), NOTHING_IGNORED, 900);
        Band pig = timeline.bands().get(0);
        assertEquals(250, pig.start());
        assertEquals(350, pig.end(), "the two pieces play back to back, so the band is as long as what is left");
        assertTrue(pig.cut());
        Band end = timeline.bands().get(1);
        assertEquals(780, end.start());
        assertFalse(end.cut());
        // The talk that straddled the start of the cut keeps the part before it
        MomentsTimeline.Talk dev = timeline.strips().stream().filter(strip -> strip.speaker().equals("DEV")).findFirst().orElseThrow().talks().get(0);
        assertEquals(280, dev.start());
        assertEquals(300, dev.end());
        assertEquals(List.of(40, 300, 800), timeline.marks().stream().map(Mark::tick).toList(),
            "a moment at the very tick a cut begins counts as the tick the recording carries on from");
    }

    @Test
    void aCombinedRecordingHoldsEachPartsMomentsAfterTheOneBefore() {
        // Another recording of 500 ticks first, then the original
        List<Spans.Span> spans = Spans.join(List.of(Spans.own("other", 500), Spans.own(ORIGINAL, 1000)), List.of(500, 1000));
        Timeline timeline = MomentsTimeline.build("combined", List.of(SESSION), through(spans), NOTHING_IGNORED, 1500);
        assertEquals(List.of(540, 800, 1400), timeline.marks().stream().map(Mark::tick).toList());
        assertEquals(750, timeline.bands().get(0).start());
    }

    @Test
    void momentsOfARecordingThatIsNotPartOfThisOneAreLeftOut() {
        Timeline timeline = MomentsTimeline.build("unrelated", List.of(SESSION), through(Spans.own("unrelated", 1000)), NOTHING_IGNORED, 1000);
        assertTrue(timeline.isEmpty());
    }

    @Test
    void momentsMadeWhileNothingWasRecordedAreLeftOut() {
        JsonObject unrecorded = new JsonObject();
        unrecorded.addProperty("id", "e9");
        unrecorded.addProperty("type", "custom");
        Timeline timeline = MomentsTimeline.build(ORIGINAL, List.of(session(List.of(unrecorded), List.of(), List.of())), through(Spans.own(ORIGINAL, 1000)), NOTHING_IGNORED, 1000);
        assertTrue(timeline.isEmpty());
    }

    @Test
    void theIgnoreListHidesMarksAndTheClipsBuiltOnNothingElse() {
        BiPredicate<String, String> kills = (type, subject) -> type.equals("mob_kill");
        Timeline timeline = MomentsTimeline.build(ORIGINAL, List.of(SESSION), through(Spans.own(ORIGINAL, 1000)), kills, 1000);
        assertEquals(List.of("e2", "e3"), timeline.marks().stream().map(Mark::eventId).toList());
        assertEquals(List.of("The end"), timeline.bands().stream().map(Band::title).toList());
        assertEquals(2, timeline.strips().size(), "what was said is not an event, and stays");
    }

    // --- Clustering ---

    private static List<Mark> marksAt(int... ticks) {
        List<Mark> marks = new ArrayList<>();
        for (int i = 0; i < ticks.length; i++) {
            marks.add(new Mark(ticks[i], "custom", null, "m" + i, i, "e" + i, "s1"));
        }
        return marks;
    }

    @Test
    void marksWithRoomBetweenThemStayApart() {
        List<Cluster> clusters = MomentsTimeline.cluster(marksAt(0, 100, 200), 0, 1.0, 13.0, null);
        assertEquals(3, clusters.size());
        assertEquals(List.of(0.0, 100.0, 200.0), clusters.stream().map(Cluster::x).toList());
        assertEquals(1, clusters.get(0).count());
    }

    @Test
    void marksThatWouldOverlapBecomeOneWithACount() {
        // A tenth of a pixel per tick: ticks 0 to 100 are ten pixels, under the thirteen two marks need
        List<Cluster> clusters = MomentsTimeline.cluster(marksAt(0, 40, 100, 1000, 1010, 5000), 0, 0.1, 13.0, null);
        assertEquals(List.of(3, 2, 1), clusters.stream().map(Cluster::count).toList());
        assertEquals(5.0, clusters.get(0).x(), 1.0e-9, "a cluster is drawn at the middle of what it holds");
        assertEquals("m2", clusters.get(0).best().description(), "the highest score speaks for a cluster");
        assertEquals(6, clusters.stream().mapToInt(Cluster::count).sum(), "no mark is lost");
    }

    @Test
    void aLongRunOfCloseMarksBreaksIntoSeveralClusters() {
        int[] ticks = new int[60];
        for (int i = 0; i < ticks.length; i++) {
            ticks[i] = i * 5;
        }
        // Five pixels apart: each alone would overlap the next, but one cluster for all sixty would say nothing
        List<Cluster> clusters = MomentsTimeline.cluster(marksAt(ticks), 0, 1.0, 13.0, null);
        assertEquals(20, clusters.size());
        for (int i = 1; i < clusters.size(); i++) {
            assertTrue(clusters.get(i).x() - clusters.get(i - 1).x() >= 13.0, "clusters " + (i - 1) + " and " + i + " overlap");
        }
        assertEquals(60, clusters.stream().mapToInt(Cluster::count).sum());
    }

    @Test
    void zoomingInTellsAClusterApartAndOnlyWhatIsWantedIsDrawn() {
        List<Mark> marks = marksAt(1000, 1010, 1020, 9000);
        assertEquals(2, MomentsTimeline.cluster(marks, 0, 0.1, 13.0, null).size());
        List<Cluster> near = MomentsTimeline.cluster(marks, 990, 2.0, 13.0, mark -> mark.tick() < 2000);
        assertEquals(3, near.size());
        assertEquals(20.0, near.get(0).x(), 1.0e-9, "positions are measured from the left edge of what is shown");
    }

    // --- A tall frame (plan 2.2) ---

    @Test
    void autoZoomFitsTheSubjectToTheNarrowSideOfATallFrame() {
        double wide = SmartCameraMath.autoZoomFov(2.0, 10.0, 0.3, 5.0, 150.0);
        assertEquals(wide, SmartCameraMath.autoZoomFov(2.0, 10.0, 0.3, 5.0, 150.0, 16.0 / 9.0), 1.0e-9, "a wide frame is as it always was");
        assertEquals(wide, SmartCameraMath.autoZoomFov(2.0, 10.0, 0.3, 5.0, 150.0, 1.0), 1.0e-9);
        double tall = SmartCameraMath.autoZoomFov(2.0, 10.0, 0.3, 5.0, 150.0, 9.0 / 16.0);
        assertTrue(tall > wide, "a tall frame needs a wider view for the same subject to fit its width");
        // In the tall frame the subject is 0.3 of the width: half the view's width at that distance is tan(fov / 2) x aspect x distance
        double halfWidth = Math.tan(Math.toRadians(tall) / 2.0) * (9.0 / 16.0) * 10.0;
        assertEquals(0.3, 2.0 / (2.0 * halfWidth), 1.0e-9);
        assertEquals(90.0, SmartCameraMath.autoZoomFov(2.0, 1.0, 0.3, 30.0, 90.0, 9.0 / 16.0), 1.0e-9, "and never past the limits set");
    }

}
