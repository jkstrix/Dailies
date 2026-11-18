package comedymonkey.dailies.moments.analysis;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plan 2.9: what the sound classifier named a burst moves its score, laughter a little above shouting */
class VoiceBurstScoreTest {

    private static JsonObject burst(String subject) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "voice_burst");
        event.addProperty("subject", subject);
        JsonObject metadata = new JsonObject();
        metadata.add("speakers", new JsonArray());
        event.add("metadata", metadata);
        return event;
    }

    @Test
    void laughterScoresAboveShouting() {
        double unclassified = MomentAnalysis.ownScore(burst("unclassified"));
        assertEquals(30.0, unclassified);
        assertEquals(unclassified, MomentAnalysis.ownScore(burst("shouting")));
        double laughter = MomentAnalysis.ownScore(burst("laughter"));
        assertTrue(laughter > MomentAnalysis.ownScore(burst("shouting")));
        assertTrue(laughter - unclassified <= 10.0, "laughter should be a little above, not a different kind of moment");
    }

}
