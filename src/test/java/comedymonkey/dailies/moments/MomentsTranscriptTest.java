package comedymonkey.dailies.moments;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What was said, placed in a recording's ticks word by word (plan 2.4, specification EXP-11) */
class MomentsTranscriptTest {

    private static final double CLOSE = 1e-6;

    /** The recording looked at is the original, whole */
    private static final MomentsTranscript.Mapper SAME = (recording, start, end) ->
        recording.equals("orig") ? List.of(new MomentsTranscript.Piece(start, end, start, end)) : List.of();

    private static JsonObject segment(double start, double end, String text, double[]... words) {
        JsonObject json = new JsonObject();
        json.addProperty("start", start);
        json.addProperty("end", end);
        json.addProperty("text", text);
        if (words.length > 0) {
            JsonArray list = new JsonArray();
            String[] parts = text.split(" ");
            for (int i = 0; i < words.length; i++) {
                JsonObject word = new JsonObject();
                word.addProperty("start", words[i][0]);
                word.addProperty("end", words[i][1]);
                word.addProperty("word", parts[i]);
                list.add(word);
            }
            json.add("words", list);
        }
        return json;
    }

    private static JsonObject record(String speaker, int startTick, int endTick, double seconds, JsonObject... segments) {
        JsonObject json = new JsonObject();
        json.add("speaker", MJson.player(speaker.toLowerCase(), speaker));
        json.addProperty("status", "done");
        json.addProperty("start_replay_uuid", "orig");
        json.addProperty("start_replay_tick", startTick);
        json.addProperty("end_replay_uuid", "orig");
        json.addProperty("end_replay_tick", endTick);
        json.addProperty("duration_seconds", seconds);
        StringBuilder text = new StringBuilder();
        JsonArray list = new JsonArray();
        for (JsonObject segment : segments) {
            list.add(segment);
            text.append(text.isEmpty() ? "" : " ").append(segment.get("text").getAsString());
        }
        json.add("segments", list);
        json.addProperty("text", text.toString());
        return json;
    }

    @Test
    void timedWordsLandOnTheirTicks() {
        JsonObject said = record("Ann", 200, 260, 3.0, segment(0.0, 2.0, "hello world", new double[]{0.0, 0.5}, new double[]{1.0, 2.0}));
        MomentsTranscript.Transcript transcript = MomentsTranscript.build("orig", List.of(said), SAME, (id, name) -> false, 0, 10_000);
        assertEquals(1, transcript.lines().size());
        MomentsTranscript.Line line = transcript.lines().get(0);
        assertEquals("Ann", line.speaker());
        assertEquals("hello world", line.text());
        assertEquals(200.0, line.words().get(0).startTick(), CLOSE);
        assertEquals(210.0, line.words().get(0).endTick(), CLOSE);
        assertEquals(220.0, line.words().get(1).startTick(), CLOSE);
        assertEquals(240.0, line.words().get(1).endTick(), CLOSE);
        assertTrue(line.words().get(0).timed());
    }

    @Test
    void wordsWithoutTimingsAreSpreadEvenly() {
        JsonObject said = record("Ann", 100, 160, 3.0, segment(1.0, 3.0, "one two three four"));
        MomentsTranscript.Line line = MomentsTranscript.build("orig", List.of(said), SAME, (id, name) -> false, 0, 10_000).lines().get(0);
        assertEquals(4, line.words().size());
        // Two seconds of audio for four words: half a second, ten ticks, each
        for (int i = 0; i < 4; i++) {
            assertEquals(120.0 + 10 * i, line.words().get(i).startTick(), CLOSE);
            assertEquals(130.0 + 10 * i, line.words().get(i).endTick(), CLOSE);
            assertFalse(line.words().get(i).timed());
        }
    }

    @Test
    void theAudioIsSpreadOverTheTicksTheRecordingLogged() {
        // Three seconds of audio over 90 ticks: the game ran slow, so a second of audio is 30 ticks
        JsonObject said = record("Ann", 0, 90, 3.0, segment(0.0, 3.0, "a b c"));
        MomentsTranscript.Line line = MomentsTranscript.build("orig", List.of(said), SAME, (id, name) -> false, 0, 10_000).lines().get(0);
        assertEquals(30.0, line.words().get(1).startTick(), CLOSE);
        assertEquals(90.0, line.endTick(), CLOSE);
    }

    @Test
    void anEditedRecordingMovesAndCutsWords() {
        // The edited recording holds ticks 100 to 150 of the original at 0 to 50, and 200 to 300 at 50 to 150
        MomentsTranscript.Mapper edited = (recording, start, end) -> List.of(
            new MomentsTranscript.Piece(0, 50, 100, 150), new MomentsTranscript.Piece(50, 150, 200, 300));
        JsonObject said = record("Ann", 100, 300, 10.0, segment(0.0, 10.0, "kept cut after",
            new double[]{1.0, 2.0}, new double[]{4.0, 5.0}, new double[]{6.0, 7.0}));
        MomentsTranscript.Line line = MomentsTranscript.build("edited", List.of(said), edited, (id, name) -> false, 0, 10_000).lines().get(0);
        assertEquals("kept after", line.text());
        assertEquals(20.0, line.words().get(0).startTick(), CLOSE);
        assertEquals(70.0, line.words().get(1).startTick(), CLOSE);
    }

    @Test
    void onlyTheTicksAskedForAndNoExcludedVoices() {
        JsonObject ann = record("Ann", 0, 200, 10.0, segment(0.0, 2.0, "early"), segment(8.0, 10.0, "late"));
        JsonObject bob = record("Bob", 0, 200, 10.0, segment(8.0, 10.0, "secret"));
        MomentsTranscript.Transcript transcript = MomentsTranscript.build("orig", List.of(ann, bob), SAME, (id, name) -> name.equals("Bob"), 150, 400);
        assertEquals(1, transcript.lines().size());
        assertEquals("late", transcript.lines().get(0).text());
    }

    @Test
    void notYetWrittenDownAndLaughterAreLeftOut() {
        JsonObject pending = record("Ann", 0, 20, 1.0, segment(0.0, 1.0, "maybe"));
        pending.addProperty("status", "pending");
        JsonObject laugh = record("Ann", 0, 20, 1.0, segment(0.0, 1.0, "ha"));
        laugh.addProperty("non_speech", true);
        assertTrue(MomentsTranscript.build("orig", List.of(pending, laugh), SAME, (id, name) -> false, 0, 100).isEmpty());
    }

    @Test
    void spreadSharesTimeEqually() {
        List<MomentsTranscript.Word> words = MomentsTranscript.spread("a b", 2.0, 4.0);
        assertEquals(2, words.size());
        assertEquals(3.0, words.get(1).startTick(), CLOSE);
        assertTrue(MomentsTranscript.spread("  ", 0, 1).isEmpty());
    }

}
