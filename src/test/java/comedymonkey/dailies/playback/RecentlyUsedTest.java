package comedymonkey.dailies.playback;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What playback remembers of stored chunks stays within its bound */
class RecentlyUsedTest {

    @Test
    void theOneUsedLongestAgoMakesRoom() {
        RecentlyUsed<String, Integer> map = new RecentlyUsed<>(2);
        map.put("a", 1);
        map.put("b", 2);
        // Used again, so "b" is now the one used longest ago
        assertEquals(1, map.get("a"));
        map.put("c", 3);
        assertEquals(2, map.size());
        assertTrue(map.containsKey("a"));
        assertFalse(map.containsKey("b"));
        assertTrue(map.containsKey("c"));
    }

    @Test
    void neverHoldsMoreThanItsBound() {
        RecentlyUsed<Integer, Integer> map = new RecentlyUsed<>(100);
        for (int i = 0; i < 10_000; i++) {
            map.put(i, i);
        }
        assertEquals(100, map.size());
        assertTrue(map.containsKey(9_999));
        assertFalse(map.containsKey(0));
    }

    @Test
    void aBoundOfNothingIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new RecentlyUsed<String, String>(0));
    }

}
