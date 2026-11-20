package comedymonkey.dailies.playback;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Playback numbers registry entries as the recording's game did, and leaves alone what needs no change (PLAY-25) */
class NumberingTest {

    private static final String SOUNDS = "minecraft:sound_event";
    private static final String NOTHING = Numbering.NOTHING;

    /** A numbering from names in number order */
    private static Map<Integer, String> own(String... names) {
        Map<Integer, String> numbers = new LinkedHashMap<>();
        for (int i = 0; i < names.length; i++) {
            numbers.put(i, names[i]);
        }
        return numbers;
    }

    private static SortedMap<Integer, String> numbered(String... names) {
        return new TreeMap<>(own(names));
    }

    private static Numbering.Plan plan(List<String> recorded, Map<Integer, String> own) {
        return Numbering.plan(Map.of(SOUNDS, recorded), Map.of(SOUNDS, own), Map.of());
    }

    @Test
    void aKeptNumberingIsReadBackWithItsGaps() {
        // As a recording's world data lists it: in number order, nothing kept for a number that stands for nothing, and
        // on a server the numbers past those the server sent kept as nothing up to this game's count
        assertEquals(new TreeMap<>(Map.of(0, "a", 1, "b", 4, "c")), Numbering.numbering(List.of("a", "b", NOTHING, NOTHING, "c", NOTHING), NOTHING));
        assertEquals(new TreeMap<>(), Numbering.numbering(List.of(), NOTHING));
    }

    @Test
    void aNameKeptTwiceKeepsItsFirstNumber() {
        assertEquals(new TreeMap<>(Map.of(0, "a", 1, "b")), Numbering.numbering(List.of("a", "b", "a"), NOTHING));
    }

    @Test
    void theSameNumberingChangesNothing() {
        // A singleplayer recording: the server was this game
        Numbering.Plan plan = plan(List.of("a", "b", "c"), own("a", "b", "c"));
        assertTrue(plan.changesNothing());
        assertTrue(plan.missing().isEmpty());
    }

    @Test
    void entriesOnlyThisGameHasAfterTheRecordingsChangeNothing() {
        // Recorded before a mod was added, whose entries come last here; numbers past the count kept as nothing, as
        // recordings made before the numbering was read by name keep them
        assertTrue(plan(List.of("a", "b", NOTHING), own("a", "b", "x")).changesNothing());
    }

    @Test
    void aServerWithoutAModsSoundsNumbersEverythingAfterThemDifferently() {
        // This game has a client-only mod's sounds (x, y) among its own; the server never had them, so its numbers for c and d
        // are two lower. The recording's numbers are put in force, and x and y go after them
        Numbering.Plan plan = plan(List.of("a", "b", "c", "d"), own("a", "b", "x", "y", "c", "d"));
        assertEquals(numbered("a", "b", "c", "d", "x", "y"), plan.apply().get(SOUNDS));
        assertTrue(plan.missing().isEmpty());
    }

    @Test
    void anEntryThisGameLacksIsSaidAndItsNumberStandsForNothing() {
        // The recording's server had m, which this game has not: m is said once, its number stands for the silent sound,
        // and every number after it stays the recording's
        Numbering.Plan plan = Numbering.plan(Map.of(SOUNDS, List.of("a", "m", "b", "silent", "c")), Map.of(SOUNDS, own("a", "silent", "b", "c")),
            Map.of(SOUNDS, "silent"));
        assertEquals(Map.of(SOUNDS, List.of("m")), plan.missing());
        assertEquals(numbered("a", "silent", "b", "silent", "c"), plan.apply().get(SOUNDS));
    }

    @Test
    void withoutAnEntryForNothingTheLastEntryStandsIn() {
        // No filler given, or one this game does not have: the last of the recording's entries, which keeps its own number
        Map<Integer, String> own = own("a", "b", "c");
        SortedMap<Integer, String> expected = numbered("a", "c", "b", "c");
        assertEquals(expected, plan(List.of("a", "m", "b", "c"), own).apply().get(SOUNDS));
        assertEquals(expected, Numbering.plan(Map.of(SOUNDS, List.of("a", "m", "b", "c")), Map.of(SOUNDS, own), Map.of(SOUNDS, "gone")).apply().get(SOUNDS));
    }

    @Test
    void missingEntriesWithEverythingElseInPlaceChangeNothing() {
        // The recording's server had a mod this game lacks, numbered after everything else: nothing to change, said all the same
        Numbering.Plan plan = plan(List.of("a", "b", "m"), own("a", "b"));
        assertTrue(plan.changesNothing());
        assertEquals(Map.of(SOUNDS, List.of("m")), plan.missing());
    }

    @Test
    void registriesTheOtherGameLacksAreLeftAlone() {
        Map<String, List<String>> recorded = new LinkedHashMap<>();
        recorded.put(SOUNDS, List.of("b", "a"));
        recorded.put("gone:registry", List.of("q"));
        Map<String, Map<Integer, String>> own = new LinkedHashMap<>();
        own.put(SOUNDS, own("a", "b"));
        own.put("minecraft:particle_type", own("p", "q"));
        Numbering.Plan plan = Numbering.plan(recorded, own, Map.of());
        // Only the registry both have and number differently
        assertEquals(Map.of(SOUNDS, numbered("b", "a")), plan.apply());
        assertTrue(plan.missing().isEmpty());
    }

    @Test
    void everyNumberBelowTheTopStandsForSomething() {
        // A long registry with entries missing here and there: no gaps, so the game can walk it by number
        String[] ownNames = new String[200];
        List<String> recorded = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            ownNames[i] = "s" + i;
        }
        for (int i = 199; i >= 0; i--) {
            recorded.add("s" + i);
            if (i % 17 == 0) {
                recorded.add("lost" + i);
            }
        }
        SortedMap<Integer, String> numbering = plan(recorded, own(ownNames)).apply().get(SOUNDS);
        assertEquals(numbering.lastKey() + 1, numbering.size());
        // Each entry keeps the recording's number; the one standing in for the missing keeps its own as its highest
        for (int i = 0; i < 200; i++) {
            String name = "s" + i;
            assertEquals(recorded.indexOf(name), numbering.entrySet().stream().filter(e -> e.getValue().equals(name)).mapToInt(Map.Entry::getKey).max().orElse(-1));
        }
    }

}
