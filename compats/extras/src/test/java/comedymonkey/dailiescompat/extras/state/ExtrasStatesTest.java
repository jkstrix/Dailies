package comedymonkey.dailiescompat.extras.state;

import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each claim's state comes back as it was written, with what playback draws from it, and the same state writes the
 * same bytes (Dailies writes a state again only when its bytes change).
 */
class ExtrasStatesTest {

    @Test
    void aCompassKeepsEachLineItsSideAndItsPlace() {
        ExtrasStates.Lines lines = new ExtrasStates.Lines(List.of(new ExtrasStates.Line("Status", 7, 7, 0xFFFFFF, false),
            new ExtrasStates.Line("Found", 7, 16, 0xAAAAAA, false), new ExtrasStates.Line("Plains Village", 7, 34, 0xAAAAAA, false),
            new ExtrasStates.Line("Distance", 120, 88, 0xFFFFFF, true)));
        ExtrasStates.Lines read = ExtrasStates.readLines(ExtrasStates.lines(lines));
        assertEquals(lines, read);
        assertEquals(4, read.lines().size());
        assertEquals("Plains Village", read.lines().get(2).text());
        assertTrue(read.lines().get(3).right());
        assertEquals(120, read.lines().get(3).fromSide());
        assertArrayEquals(ExtrasStates.lines(lines), ExtrasStates.lines(read));
    }

    @Test
    void aBackpackKeepsItsItemsItsTabItsScrollAndItsSearch() {
        byte[] saved = {10, 0, 0, 9, 0, 5, 's', 'l', 'o', 't', 's', 10, 0, 0, 0, 0, 0};
        ExtrasStates.Backpack backpack = new ExtrasStates.Backpack("{\"translate\":\"item.sophisticatedbackpacks.backpack\"}", new ExtrasStates.Items(saved),
            2, 36.5F, "iron");
        ExtrasStates.Backpack read = ExtrasStates.readBackpack(ExtrasStates.backpack(backpack));
        assertEquals(backpack, read);
        assertArrayEquals(saved, read.items().saved());
        assertEquals(2, read.openTab());
        assertEquals(36.5F, read.scroll());
        assertEquals("iron", read.search());
        assertArrayEquals(ExtrasStates.backpack(backpack), ExtrasStates.backpack(read));
        // No tab open, nothing searched
        ExtrasStates.Backpack plain = new ExtrasStates.Backpack("", new ExtrasStates.Items(new byte[0]), -1, 0.0F, "");
        assertEquals(plain, ExtrasStates.readBackpack(ExtrasStates.backpack(plain)));
    }

    @Test
    void itemsAreKeptAsTheirBytesAndNeverShared() {
        byte[] saved = {1, 2, 3};
        ExtrasStates.Items items = new ExtrasStates.Items(saved);
        saved[0] = 9;
        assertEquals(1, items.saved()[0]);
        items.saved()[1] = 9;
        assertEquals(2, items.saved()[1]);
        assertEquals(new ExtrasStates.Items(new byte[]{1, 2, 3}), items);
        assertNotEquals(new ExtrasStates.Items(new byte[]{1, 2}), items);
    }

    @Test
    void aNautilusKeepsWhichOneItsSizeAndItsItems() {
        ExtrasStates.Nautilus nautilus = new ExtrasStates.Nautilus(4711, 1, new ExtrasStates.Items(new byte[]{10, 0, 0, 0}));
        ExtrasStates.Nautilus read = ExtrasStates.readNautilus(ExtrasStates.nautilus(nautilus));
        assertEquals(nautilus, read);
        assertEquals(4711, read.entity());
        assertEquals(1, read.size());
        assertArrayEquals(ExtrasStates.nautilus(nautilus), ExtrasStates.nautilus(read));
    }

    @Test
    void theWindowsExtrasComeBackAsTheyWere() {
        ExtrasStates.Curios curios = new ExtrasStates.Curios(2, true);
        assertEquals(curios, ExtrasStates.readCurios(ExtrasStates.curios(curios)));
        ExtrasStates.Page page = new ExtrasStates.Page(3);
        assertEquals(page, ExtrasStates.readPage(ExtrasStates.page(page)));
    }

    @Test
    void theStatusLineFadesAsTheGameFadesIt() {
        ExtrasStates.Status status = new ExtrasStates.Status("{\"text\":\"Press Shift to let go\"}", 60, false);
        ExtrasStates.Status read = ExtrasStates.readStatus(ExtrasStates.status(status));
        assertEquals(status, read);
        assertEquals(255, read.alpha(0.0F));
        // The last twenty ticks fade it out
        assertEquals(127, new ExtrasStates.Status("", 10, false).alpha(0.0F));
        assertEquals(0, new ExtrasStates.Status("", 0, true).alpha(0.5F));
    }

    @Test
    void aDamagedRecordFailsRatherThanFillingTheMemory() {
        byte[] lines = ExtrasStates.lines(new ExtrasStates.Lines(List.of(new ExtrasStates.Line("Found", 7, 16, 0xAAAAAA, false))));
        // A length of a hundred million where the count of lines goes
        lines[0] = 0x05;
        lines[1] = (byte) 0xF5;
        assertThrows(UncheckedIOException.class, () -> ExtrasStates.readLines(lines));
        assertThrows(UncheckedIOException.class, () -> ExtrasStates.readBackpack(new byte[]{0, 0}));
    }

}
