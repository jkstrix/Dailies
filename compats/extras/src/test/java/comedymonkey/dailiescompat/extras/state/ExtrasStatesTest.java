package comedymonkey.dailiescompat.extras.state;

import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void theViewfinderKeepsItsZoomItsLockAndItsScope() {
        ExtrasStates.Viewfinder viewfinder = new ExtrasStates.Viewfinder(17, true, 1.0625F);
        ExtrasStates.Viewfinder read = ExtrasStates.readViewfinder(ExtrasStates.viewfinder(viewfinder));
        assertEquals(viewfinder, read);
        assertEquals(17, read.zoom());
        assertTrue(read.locked());
        assertEquals(1.0625F, read.scope());
        assertArrayEquals(ExtrasStates.viewfinder(viewfinder), ExtrasStates.viewfinder(read));
    }

    @Test
    void theProjectorKeepsItsAddressAsTypedAndTheCursor() {
        ExtrasStates.Projector projector = new ExtrasStates.Projector(-123456789L, "vista:wave_collector", "https://example.com/feed ✓", 12, 4);
        ExtrasStates.Projector read = ExtrasStates.readProjector(ExtrasStates.projector(projector));
        assertEquals(projector, read);
        assertEquals("https://example.com/feed ✓", read.url());
        assertEquals(12, read.cursor());
        assertEquals(4, read.selection());
        assertArrayEquals(ExtrasStates.projector(projector), ExtrasStates.projector(read));
    }

    @Test
    void theEmoteWheelKeepsItsPageItsEmotesItsSettingsAndThePointer() {
        String wave = "6f1d8f3e-1d2c-4a51-9a07-2f0a8b1f7c11";
        List<String> emotes = List.of(wave, "", "", "1c5e0a4d-0e3b-4d6f-8f0e-6b8d1c7a2e33", "", "", "", "");
        List<String> names = List.of("{\"text\":\"Wave\"}", "", "", "{\"color\":\"red\",\"text\":\"INVALID\"}", "", "", "", "");
        ExtrasStates.EmoteWheel wheel = new ExtrasStates.EmoteWheel(3, emotes, names, ExtrasStates.NO_SERVER, false, true, true, 0.31F, -0.12F);
        ExtrasStates.EmoteWheel read = ExtrasStates.readEmoteWheel(ExtrasStates.emoteWheel(wheel));
        assertEquals(wheel, read);
        assertEquals(3, read.page());
        assertEquals(ExtrasStates.PLACES, read.emotes().size());
        assertEquals(wave, read.emotes().get(0));
        assertEquals("", read.emotes().get(1));
        assertEquals(ExtrasStates.NO_SERVER, read.warning());
        assertTrue(read.dark());
        assertFalse(read.legacy());
        assertEquals(0.31F, read.pointerX());
        assertArrayEquals(ExtrasStates.emoteWheel(wheel), ExtrasStates.emoteWheel(read));
        // A name for each emote, or the wheel could not say what a place shows
        assertThrows(IllegalArgumentException.class, () -> new ExtrasStates.EmoteWheel(0, List.of(wave), List.of(), ExtrasStates.NO_WARNING, false, false,
            true, 0.0F, 0.0F));
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
        ExtrasStates.PipeSettings settings = new ExtrasStates.PipeSettings(1, -1, 4);
        ExtrasStates.PipeSettings readSettings = ExtrasStates.readPipeSettings(ExtrasStates.pipeSettings(settings));
        assertEquals(settings, readSettings);
        assertEquals(-1, readSettings.selected());
        ExtrasStates.PipeFilter filter = new ExtrasStates.PipeFilter("#minecraft:logs", "{CustomModelData:3}", true, false);
        ExtrasStates.PipeFilter readFilter = ExtrasStates.readPipeFilter(ExtrasStates.pipeFilter(filter));
        assertEquals(filter, readFilter);
        assertEquals("#minecraft:logs", readFilter.item());
        assertTrue(readFilter.exact());
        assertArrayEquals(ExtrasStates.pipeFilter(filter), ExtrasStates.pipeFilter(readFilter));
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
