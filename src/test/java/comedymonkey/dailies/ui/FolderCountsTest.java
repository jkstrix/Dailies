package comedymonkey.dailies.ui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** How many recordings the library's folder rows say they hold */
class FolderCountsTest {

    @Test
    void aFolderCountsWhatIsInItAndInTheFoldersInsideIt() {
        Map<String, Integer> counts = FolderCounts.of(List.of("trips", "trips/2026", "trips/2026/june", "builds", ""));
        assertEquals(3, counts.get("trips"));
        assertEquals(2, counts.get("trips/2026"));
        assertEquals(1, counts.get("trips/2026/june"));
        assertEquals(1, counts.get("builds"));
    }

    @Test
    void aFolderWhoseNameBeginsTheSameIsNotInside() {
        Map<String, Integer> counts = FolderCounts.of(List.of("trip", "trips", "trips/a"));
        assertEquals(1, counts.get("trip"));
        assertEquals(2, counts.get("trips"));
    }

    @Test
    void recordingsAtTheTopCountTowardsNoFolder() {
        Map<String, Integer> counts = FolderCounts.of(List.of("", "", "a"));
        assertNull(counts.get(""));
        assertEquals(1, counts.size());
    }

    @Test
    void theSameAsLookingAtEveryRecordingForEachFolder() {
        List<String> holding = List.of("a", "a/b", "a/b/c", "a/bc", "ab", "b/a", "", "a/b");
        Map<String, Integer> counts = FolderCounts.of(holding);
        for (String folder : List.of("a", "a/b", "a/b/c", "a/bc", "ab", "b", "b/a", "c")) {
            int slow = 0;
            for (String in : holding) {
                if (in.equals(folder) || in.startsWith(folder + "/")) {
                    slow++;
                }
            }
            assertEquals(slow, counts.getOrDefault(folder, 0), folder);
        }
    }
}
