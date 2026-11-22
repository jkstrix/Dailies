package comedymonkey.dailies.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What follows "/dailies mark" (specification REC-7): a colour, whether to store the position, a description, each optional */
class MarkWordsTest {

    @Test
    void nothingAtAllIsARedMarkerThatStoresThePosition() {
        for (String words : new String[]{null, "", "   "}) {
            MarkWords.Mark mark = MarkWords.parse(words);
            assertEquals(0xE0483A, mark.colour());
            assertEquals("red", mark.colourName());
            assertTrue(mark.savePosition());
            assertEquals("", mark.description());
        }
    }

    @Test
    void aColourIsReadByNameOrAsDigits() {
        assertEquals(0x4A7BE0, MarkWords.parse("blue").colour());
        assertEquals("blue", MarkWords.parse("BLUE").colourName());
        assertEquals(0x40C0FF, MarkWords.parse("#40c0ff").colour());
        assertEquals("#40C0FF", MarkWords.parse("#40c0ff").colourName());
        assertEquals(0x40C0FF, MarkWords.parse("40C0FF the pond").colour());
        assertNull(MarkWords.colour("bluish"));
        assertNull(MarkWords.colour("#12345"));
        assertNull(MarkWords.colour(null));
        assertEquals(8, MarkWords.colourNames().size());
        for (String name : MarkWords.colourNames()) {
            assertEquals(MarkWords.colour(name), MarkWords.parse(name).colour());
        }
    }

    @Test
    void savingThePositionIsAYesOrANo() {
        assertFalse(MarkWords.parse("no").savePosition());
        assertFalse(MarkWords.parse("green false").savePosition());
        assertFalse(MarkWords.parse("green OFF creeper hole").savePosition());
        assertTrue(MarkWords.parse("green yes").savePosition());
        assertTrue(MarkWords.parse("true").savePosition());
        assertEquals("", MarkWords.parse("green no").description());
    }

    @Test
    void whateverIsLeftIsTheDescription() {
        MarkWords.Mark all = MarkWords.parse("  purple   no   the creeper hole  ");
        assertEquals(0x9A5BD8, all.colour());
        assertFalse(all.savePosition());
        assertEquals("the creeper hole", all.description());

        MarkWords.Mark onlyWords = MarkWords.parse("that was close");
        assertEquals("red", onlyWords.colourName());
        assertTrue(onlyWords.savePosition());
        assertEquals("that was close", onlyWords.description());

        // Each part is only looked for in its own place
        assertEquals("no way blue", MarkWords.parse("white yes no way blue").description());
        assertEquals("blue yes", MarkWords.parse("yes blue yes").description());
        assertEquals("nothing happened", MarkWords.parse("nothing happened").description());
    }

    @Test
    void completionOffersWhatFitsWhereTheCursorIs() {
        List<String> first = MarkWords.suggestions("");
        assertTrue(first.containsAll(MarkWords.colourNames()));
        assertTrue(first.contains("yes") && first.contains("no"));
        assertEquals(List.of("blue"), MarkWords.suggestions("bl"));
        assertEquals(List.of("yellow", "yes"), MarkWords.suggestions("ye"));
        assertEquals(List.of("yes", "no"), MarkWords.suggestions("blue "));
        assertEquals(List.of("no"), MarkWords.suggestions("#40C0FF n"));
        // Past the colour and the yes or no it is a description, and nobody can guess that
        assertTrue(MarkWords.suggestions("blue no ").isEmpty());
        assertTrue(MarkWords.suggestions("a hole ").isEmpty());
        assertTrue(MarkWords.suggestions("no ").isEmpty());

        assertEquals(0, MarkWords.wordStart("bl"));
        assertEquals(5, MarkWords.wordStart("blue n"));
        assertEquals(5, MarkWords.wordStart("blue "));
    }

}
