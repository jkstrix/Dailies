package comedymonkey.dailies.ui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The selection's count of changes, which the editor compares in place of a hash that two selections can share */
class RevisedSetTest {

    @Test
    void everyChangeIsCounted() {
        RevisedSet<String> set = new RevisedSet<>();
        long start = set.revision();
        set.add("a");
        long added = set.revision();
        assertNotEquals(start, added);
        set.remove("a");
        assertNotEquals(added, set.revision());
    }

    @Test
    void whatChangesNothingIsNotCounted() {
        RevisedSet<String> set = new RevisedSet<>();
        set.add("a");
        long now = set.revision();
        assertFalse(set.add("a"));
        assertFalse(set.remove("b"));
        set.retainAll(Set.of("a"));
        assertEquals(now, set.revision());
        set.clear();
        long cleared = set.revision();
        set.clear();
        assertEquals(cleared, set.revision());
    }

    @Test
    void twoSelectionsWithTheSameHashAreStillTold() {
        // Integers hash to themselves and a set's hash is the sum of its members', so {1, 4} and {2, 3} share one
        RevisedSet<Integer> set = new RevisedSet<>();
        set.add(1);
        set.add(4);
        int hash = set.hashCode();
        long seen = set.revision();
        set.clear();
        set.add(2);
        set.add(3);
        assertEquals(hash, set.hashCode());
        assertNotEquals(seen, set.revision());
    }

    @Test
    void takingAwayThroughTheIteratorIsCounted() {
        RevisedSet<String> set = new RevisedSet<>();
        set.addAll(List.of("a", "b", "c"));
        long before = set.revision();
        set.removeIf(member -> member.equals("b"));
        assertNotEquals(before, set.revision());
        long after = set.revision();
        set.retainAll(Set.of("a"));
        assertNotEquals(after, set.revision());
        assertEquals(Set.of("a"), set);
    }

    @Test
    void itKeepsTheOrderThingsWerePutIn() {
        RevisedSet<String> set = new RevisedSet<>();
        set.addAll(List.of("c", "a", "b"));
        assertEquals(List.of("c", "a", "b"), List.copyOf(set));
        assertTrue(set.contains("a"));
        assertEquals(3, set.size());
    }
}
