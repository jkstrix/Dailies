package comedymonkey.dailies.moments.voice;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the transcription filter keeps and drops (specification MOM-33, plan 2.9) */
class TranscriptionFilterTest {

    private static JsonObject part(String text, double noSpeech, double logprob) {
        JsonObject json = new JsonObject();
        json.addProperty("start", 0.0);
        json.addProperty("end", 1.0);
        json.addProperty("text", text);
        json.addProperty("no_speech_prob", noSpeech);
        json.addProperty("avg_logprob", logprob);
        return json;
    }

    private static JsonObject apply(JsonObject... parts) {
        JsonArray segments = new JsonArray();
        for (JsonObject part : parts) {
            segments.add(part);
        }
        JsonObject response = new JsonObject();
        response.add("segments", segments);
        response.addProperty("language", "en");
        JsonObject record = new JsonObject();
        TranscriptionWorker.applyResponse(record, response, "test-model");
        return record;
    }

    @Test
    void speechIsKeptAsText() {
        JsonObject record = apply(part("Watch out", 0.1, -0.3), part("behind you", 0.2, -0.4));
        assertEquals("done", record.get("status").getAsString());
        assertEquals("Watch out behind you", record.get("text").getAsString());
        assertFalse(record.get("non_speech").getAsBoolean());
        assertEquals(2, record.getAsJsonArray("segments").size());
    }

    @Test
    void laughterIsKeptAndMarkedNotThrownAway() {
        JsonObject record = apply(part("ha ha ha", 0.9, -0.5));
        assertEquals("empty", record.get("status").getAsString(), "no words were said");
        assertEquals("", record.get("text").getAsString());
        assertTrue(record.get("non_speech").getAsBoolean());
        JsonArray segments = record.getAsJsonArray("segments");
        assertEquals(1, segments.size());
        assertTrue(segments.get(0).getAsJsonObject().get("non_speech").getAsBoolean());
        assertEquals(1, record.getAsJsonObject("metadata").get("non_speech_segments").getAsInt());
    }

    @Test
    void laughterBesideSpeechStaysOutOfTheText() {
        JsonObject record = apply(part("That was close", 0.1, -0.3), part("(laughs)", 0.8, -0.6));
        assertEquals("done", record.get("status").getAsString());
        assertEquals("That was close", record.get("text").getAsString());
        assertFalse(record.get("non_speech").getAsBoolean());
        assertEquals(2, record.getAsJsonArray("segments").size());
        assertEquals(3, record.get("word_count").getAsInt());
    }

    @Test
    void guessesAreStillDropped() {
        JsonObject record = apply(part("mumble", 0.2, -1.4));
        assertEquals("empty", record.get("status").getAsString());
        assertFalse(record.get("non_speech").getAsBoolean());
        assertEquals(0, record.getAsJsonArray("segments").size());
        assertEquals(1, record.getAsJsonObject("metadata").get("dropped_segments").getAsInt());
    }

}
