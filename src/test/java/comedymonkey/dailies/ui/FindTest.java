package comedymonkey.dailies.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The search of the settings screen and the narrowing of a dropdown's list */
class FindTest {

    @Test
    void nothingTypedFindsEverything() {
        assertTrue(Find.matches("", "Port"));
        assertTrue(Find.matches("   ", "Port"));
        assertTrue(Find.matches(null, "Port"));
        assertEquals(List.of(0, 1, 2), Find.filter(List.of("a", "b", "c"), ""));
        assertEquals(List.of(0, 1, 2), Find.filter(List.of("a", "b", "c"), null));
    }

    @Test
    void everyWordMustBeSomewhereInAnyOrderAndAnyCase() {
        String label = "Seconds between Stored Worlds";
        String comment = "How often a recording stores the whole world afresh";
        assertTrue(Find.matches("stored sec", label, comment));
        assertTrue(Find.matches("WORLD often", label, comment));
        assertFalse(Find.matches("stored minutes", label, comment));
    }

    @Test
    void aNameInCamelCaseIsFoundAsWrittenAndAsWords() {
        assertTrue(Find.matches("mcpport", "Port", "mcpPort"));
        assertTrue(Find.matches("mcp port", "Port", "mcpPort"));
        assertTrue(Find.matches("updates per second", "recorderUpdatesPerSecond"));
        assertFalse(Find.matches("mcp portal", "Port", "mcpPort"));
        assertEquals("recorder updates per second", Find.plain("recorderUpdatesPerSecond"));
        assertEquals("ease in out", Find.plain("EASE_IN_OUT"));
    }

    @Test
    void theDescriptionIsSearchedAsWellAsTheName() {
        assertTrue(Find.matches("pause menu", "Pause Menu Buttons", "controlsLocation", "The side of the pause menu the recording buttons sit on"));
        assertTrue(Find.matches("sit on", "Buttons", "controlsLocation", "The side of the pause menu the recording buttons sit on"));
        assertTrue(Find.matches("x", "a", null, "x"));
    }

    @Test
    void aListIsNarrowedBestFirst() {
        List<String> codecs = List.of("H.264", "H.265 (HEVC)", "AV1", "VP9", "ProRes 4444", "Lossless hevc");
        // Starts with it, then a word starts with it, then merely holds it
        assertEquals(List.of(1, 5), Find.filter(codecs, "hevc").stream().sorted().toList());
        assertEquals(List.of(1, 5), Find.filter(codecs, "HEVC").stream().sorted().toList());
        assertEquals(1, Find.filter(codecs, "hevc").get(0));
        assertEquals(List.of(0, 1), Find.filter(codecs, "h."));
        assertEquals(List.of(4), Find.filter(codecs, "44"));
        assertEquals(List.of(), Find.filter(codecs, "theora"));
    }

    @Test
    void linesThatStartWithWhatWasTypedComeBeforeLinesThatHoldIt() {
        List<String> languages = List.of("Indonesian (id)", "English (en)", "Hindi (hi)", "Finnish (fi)");
        assertEquals(List.of(0, 2, 3), Find.filter(languages, "in"));
        assertEquals(List.of(1), Find.filter(languages, "en"));
        assertEquals(List.of(3), Find.filter(languages, "fi"));
    }

}
