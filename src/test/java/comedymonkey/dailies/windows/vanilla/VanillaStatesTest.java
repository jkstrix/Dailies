package comedymonkey.dailies.windows.vanilla;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The states of the game's books, lectern and signs: each reads back as it was written, and a damaged one fails */
class VanillaStatesTest {

    private static final String HANDLE = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void pagesRoundTrip() {
        List<String> pages = List.of("{\"text\":\"Page one\"}", "", "{\"text\":\"Ünïcode, \n and a line\"}");
        assertEquals(pages, VanillaStates.readPages(VanillaStates.pages(pages)));
        assertEquals(List.of(), VanillaStates.readPages(VanillaStates.pages(List.of())));
    }

    @Test
    void pagesKeepAtMostAHundred() {
        List<String> pages = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            pages.add("page " + i);
        }
        assertEquals(pages.subList(0, 100), VanillaStates.readPages(VanillaStates.pages(pages)));
    }

    @Test
    void aBookReadRoundTrips() {
        for (int hand : new int[]{-1, 0, 1}) {
            VanillaStates.BookRead read = new VanillaStates.BookRead(hand, HANDLE, 2);
            assertEquals(read, VanillaStates.readBookRead(VanillaStates.bookRead(read)));
        }
    }

    @Test
    void aLecternRoundTrips() {
        VanillaStates.Lectern taker = new VanillaStates.Lectern(HANDLE, 1, true);
        VanillaStates.Lectern reader = new VanillaStates.Lectern(HANDLE, 0, false);
        assertEquals(taker, VanillaStates.readLectern(VanillaStates.lectern(taker)));
        assertEquals(reader, VanillaStates.readLectern(VanillaStates.lectern(reader)));
    }

    @Test
    void aBookBeingWrittenRoundTrips() {
        VanillaStates.BookWrite onePage = new VanillaStates.BookWrite(null, 0, "Dear diary,\ntoday", 11, 4, false, "", "ComedyMonkey");
        assertEquals(onePage, VanillaStates.readBookWrite(VanillaStates.bookWrite(onePage)));
        VanillaStates.BookWrite signing = new VanillaStates.BookWrite(HANDLE, 2, "the end", 7, 7, true, "My Dailies", "ComedyMonkey");
        assertEquals(signing, VanillaStates.readBookWrite(VanillaStates.bookWrite(signing)));
    }

    @Test
    void theOpenPageGoesBackAmongTheOthers() {
        List<String> pages = List.of("one", "two", "three");
        List<String> others = VanillaStates.others(pages, 1);
        assertEquals(List.of("one", "", "three"), others);
        // What is stored once does not change as the open page is typed on
        assertEquals(others, VanillaStates.others(List.of("one", "two and more", "three"), 1));
        VanillaStates.BookWrite state = new VanillaStates.BookWrite(HANDLE, 1, "two and more", 0, 0, false, "", "");
        assertEquals(List.of("one", "two and more", "three"), state.pagesWith(VanillaStates.readPages(VanillaStates.pages(others))));
        // A book of one page stores nothing once, and a page turned to past the end is a new empty one
        assertEquals(List.of("only"), new VanillaStates.BookWrite(null, 0, "only", 0, 0, false, "", "").pagesWith(null));
        assertEquals(List.of("one", "", "new"), new VanillaStates.BookWrite(HANDLE, 2, "new", 0, 0, false, "", "").pagesWith(List.of("one", "")));
    }

    @Test
    void aSignBeingEditedRoundTrips() {
        VanillaStates.SignEdit sign = new VanillaStates.SignEdit(-123456789L, "minecraft:oak_sign", "oak", false, true, Arrays.asList("Dailies", "was here", "", ""), 1,
            8, 3, "black", false);
        assertEquals(sign, VanillaStates.readSignEdit(VanillaStates.signEdit(sign)));
        VanillaStates.SignEdit hanging = new VanillaStates.SignEdit(42L, "minecraft:spruce_wall_hanging_sign", "spruce", true, false, List.of("Shop", "Open", "", ""), 3,
            0, 0, "light_blue", true);
        VanillaStates.SignEdit read = VanillaStates.readSignEdit(VanillaStates.signEdit(hanging));
        assertEquals(hanging, read);
        assertEquals("Open", read.line(1));
        assertEquals("", read.line(7));
    }

    @Test
    void aDamagedStateFails() {
        byte[] whole = VanillaStates.signEdit(new VanillaStates.SignEdit(0L, "minecraft:oak_sign", "oak", false, true, List.of("a", "b", "c", "d"), 0, 0, 0, "black",
            false));
        assertThrows(IllegalArgumentException.class, () -> VanillaStates.readSignEdit(Arrays.copyOf(whole, whole.length - 3)));
        assertThrows(IllegalArgumentException.class, () -> VanillaStates.readBookWrite(new byte[]{1, 0x7f, 0x7f, 0x7f, 0x7f}));
        assertThrows(IllegalArgumentException.class, () -> VanillaStates.readPages(new byte[]{0, 0, 0, 2, 0, 0, 0, 9, 'x'}));
        assertThrows(IllegalArgumentException.class, () -> VanillaStates.readBookRead(new byte[0]));
    }

    @Test
    void aStateWithMoreAtItsEndStillReads() {
        // A later version adds fields at the end only, which this one leaves unread
        byte[] state = VanillaStates.bookRead(new VanillaStates.BookRead(0, HANDLE, 3));
        byte[] longer = Arrays.copyOf(state, state.length + 4);
        assertEquals(3, VanillaStates.readBookRead(longer).page());
        assertNull(VanillaStates.readBookWrite(VanillaStates.bookWrite(new VanillaStates.BookWrite(null, 0, "", 0, 0, false, "", ""))).otherPages());
    }

}
