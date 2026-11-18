package comedymonkey.dailies.moments.track;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How many events a server may send, and how much of each is kept */
class EventBudgetTest {

    @Test
    void aBurstThenSoManyASecond() {
        EventBudget budget = new EventBudget(5, 20, 0);
        for (int i = 0; i < 20; i++) {
            assertTrue(budget.take(0), "event " + i);
        }
        assertFalse(budget.take(0));
        // A fifth of a second later there is room for one more
        assertFalse(budget.take(100));
        assertTrue(budget.take(200));
        assertFalse(budget.take(200));
        assertEquals(3, budget.dropped());
        // A long quiet spell fills it up again, but never past the burst
        for (int i = 0; i < 20; i++) {
            assertTrue(budget.take(60_000));
        }
        assertFalse(budget.take(60_000));
    }

    @Test
    void aFloodGetsNoMoreThanTheRate() {
        EventBudget budget = new EventBudget(5, 20, 0);
        int kept = 0;
        // A thousand events a second for ten seconds
        for (long millis = 0; millis < 10_000; millis++) {
            if (budget.take(millis)) {
                kept++;
            }
        }
        assertTrue(kept >= 65 && kept <= 71, "kept " + kept);
    }

    @Test
    void aClockGoingBackGivesNothingAway() {
        EventBudget budget = new EventBudget(5, 1, 10_000);
        assertTrue(budget.take(10_000));
        assertFalse(budget.take(5_000));
        assertFalse(budget.take(10_100));
        assertTrue(budget.take(10_200));
    }

    @Test
    void theDataKeptIsCappedAndSaysSo() {
        JsonObject data = new JsonObject();
        data.addProperty("craft_id", "abc");
        data.addProperty("huge", "x".repeat(20_000));
        data.addProperty("count", 3);
        JsonObject kept = EventBudget.kept(data, 8 * 1024);
        assertEquals("abc", kept.get("craft_id").getAsString());
        assertEquals(3, kept.get("count").getAsInt());
        assertFalse(kept.has("huge"));
        assertTrue(kept.get("data_cut").getAsBoolean());
        assertTrue(kept.toString().length() <= 8 * 1024);

        JsonObject small = new JsonObject();
        small.addProperty("a", 1);
        assertEquals(small, EventBudget.kept(small, 8 * 1024));
    }

}
