package comedymonkey.dailies.moments.voice;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Word timings from the transcription server are kept with their segments (plan 2.4) */
class TranscriptionWordsTest {

    private static JsonObject part(double start, double end, String text, double noSpeech) {
        JsonObject json = new JsonObject();
        json.addProperty("start", start);
        json.addProperty("end", end);
        json.addProperty("text", text);
        json.addProperty("no_speech_prob", noSpeech);
        json.addProperty("avg_logprob", -0.2);
        return json;
    }

    private static JsonObject word(double start, double end, String text) {
        JsonObject json = new JsonObject();
        json.addProperty("start", start);
        json.addProperty("end", end);
        json.addProperty("word", text);
        return json;
    }

    @Test
    void wordsBesideTheSegmentsGoToTheSegmentTheyFallIn() {
        JsonArray segments = new JsonArray();
        segments.add(part(0.0, 1.0, "Watch out", 0.1));
        segments.add(part(1.0, 2.0, "ha ha", 0.9));
        segments.add(part(2.0, 3.0, "behind you", 0.1));
        JsonArray words = new JsonArray();
        words.add(word(0.0, 0.4, " Watch"));
        words.add(word(0.5, 0.9, " out"));
        words.add(word(1.1, 1.9, " ha"));
        words.add(word(2.1, 2.4, " behind"));
        words.add(word(2.5, 2.9, " you"));
        JsonObject response = new JsonObject();
        response.add("segments", segments);
        response.add("words", words);
        JsonObject record = new JsonObject();
        TranscriptionWorker.applyResponse(record, response, "test-model");

        JsonArray stored = record.getAsJsonArray("segments");
        JsonArray first = stored.get(0).getAsJsonObject().getAsJsonArray("words");
        assertEquals(2, first.size());
        assertEquals("Watch", first.get(0).getAsJsonObject().get("word").getAsString());
        assertEquals(0.5, first.get(1).getAsJsonObject().get("start").getAsDouble(), 1e-9);
        // Laughter keeps no words; the last segment has its own two
        assertFalse(stored.get(1).getAsJsonObject().has("words"));
        assertEquals(2, stored.get(2).getAsJsonObject().getAsJsonArray("words").size());
    }

    @Test
    void wordsInsideASegmentAreKept() {
        JsonObject segment = part(0.0, 1.0, "Hello", 0.1);
        JsonArray own = new JsonArray();
        own.add(word(0.1, 0.6, "Hello"));
        segment.add("words", own);
        JsonArray segments = new JsonArray();
        segments.add(segment);
        JsonObject response = new JsonObject();
        response.add("segments", segments);
        JsonObject record = new JsonObject();
        TranscriptionWorker.applyResponse(record, response, "test-model");
        JsonArray words = record.getAsJsonArray("segments").get(0).getAsJsonObject().getAsJsonArray("words");
        assertEquals(1, words.size());
        assertEquals(0.6, words.get(0).getAsJsonObject().get("end").getAsDouble(), 1e-9);
    }

}
