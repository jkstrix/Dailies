package comedymonkey.dailies.windows;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The recorder's windows as a recording keeps them: records read back as written, and a jump lands where play does */
class WindowStateTest {

    private static final byte[] TITLE = {9, 8, 7};
    private static final byte[] COBBLE = {1, 2, 3, 4};
    private static final byte[] COAL = {5, 6};
    private static final byte[] IRON = {7, 7, 7};

    private static WindowState read(byte[]... records) {
        WindowState state = new WindowState();
        for (byte[] record : records) {
            state.apply(record, 0, record.length);
        }
        return state;
    }

    private static byte[] chest() {
        return new WindowState.Parts().open(WindowState.Source.SERVER, 3, "minecraft:generic_9x3", TITLE, null, -1, 0)
            .slot(0, COBBLE).slot(26, COAL).slot(60, IRON).pointer(false, 30.5F, 20.25F, 0).bytes();
    }

    @Test
    void aWholeWindowReadsBackAsItWasWritten() {
        WindowState state = read(chest());
        assertTrue(state.open());
        assertEquals(WindowState.Source.SERVER, state.source());
        assertEquals("minecraft:generic_9x3", state.menuType());
        assertEquals(3, state.containerId);
        assertArrayEquals(TITLE, state.title);
        assertNull(state.extra);
        assertArrayEquals(COBBLE, state.slot(0));
        assertArrayEquals(COAL, state.slot(26));
        assertArrayEquals(IRON, state.slot(60));
        assertEquals(0, state.slot(1).length);
        assertEquals(30.5F, state.pointerX);
        assertEquals(20.25F, state.pointerY);
        assertEquals(0, state.hovered());

        byte[] whole = state.whole();
        WindowState again = read(whole);
        assertEquals(state, again);
        assertArrayEquals(whole, again.whole());
    }

    @Test
    void everyKindOfPartReadsBack() {
        byte[] extra = {42, 0, -1};
        byte[] offers = {11, 12, 13, 14};
        WindowState state = read(new WindowState.Parts().open(WindowState.Source.ADVANCED, 7, "create:toolbox", TITLE, extra, -1, 0)
            .data(0, 200).data(3, -5).offers(offers).carried(IRON).pointer(true, 0.25F, 0.75F, -1).bytes());
        assertArrayEquals(extra, state.extra);
        assertEquals(Map.of(0, 200, 3, -5), state.dataSlots());
        assertArrayEquals(offers, state.offers);
        assertArrayEquals(IRON, state.carried);
        assertTrue(state.pointerShare);
        assertEquals(-1, state.hovered());
        assertEquals(state, read(state.whole()));

        WindowState horse = read(new WindowState.Parts().open(WindowState.Source.HORSE, 9, "", TITLE, null, 1234, 5).bytes());
        assertEquals(1234, horse.horseEntity);
        assertEquals(5, horse.horseColumns);
        assertEquals(horse, read(horse.whole()));

        WindowState inventory = read(new WindowState.Parts().open(WindowState.Source.INVENTORY, 0, "", TITLE, null, -1, 0).slot(36, COBBLE).bytes());
        assertEquals(WindowState.Source.INVENTORY, inventory.source());
        assertEquals(inventory, read(inventory.whole()));
    }

    @Test
    void changesOnlyTouchWhatTheyName() {
        WindowState state = read(chest(), new WindowState.Parts().slot(0, WindowState.NONE).slot(1, COAL).data(2, 9).bytes());
        assertEquals(0, state.slot(0).length);
        assertArrayEquals(COAL, state.slot(1));
        assertArrayEquals(COAL, state.slot(26));
        assertEquals(9, state.data(2));
        assertEquals(30.5F, state.pointerX, "a record with no pointer leaves it where it was");
    }

    @Test
    void whatEachRecordChangedIsReported() {
        WindowState state = new WindowState();
        byte[] open = chest();
        int changed = state.apply(open, 0, open.length);
        assertTrue((changed & WindowState.OPENED) != 0 && (changed & WindowState.CONTENTS) != 0);
        byte[] slot = new WindowState.Parts().slot(4, COAL).bytes();
        assertEquals(WindowState.CONTENTS, state.apply(slot, 0, slot.length));
        byte[] pointer = new WindowState.Parts().pointer(false, 1, 2, 3).bytes();
        assertEquals(WindowState.MOVED, state.apply(pointer, 0, pointer.length));
        byte[] close = new WindowState.Parts().close().bytes();
        assertEquals(WindowState.CLOSED, state.apply(close, 0, close.length));
        assertFalse(state.open());
        assertEquals(0, state.apply(close, 0, close.length), "closing what is closed changes nothing");
    }

    @Test
    void openingAnotherWindowForgetsTheOneBefore() {
        WindowState state = read(chest(), new WindowState.Parts().open(WindowState.Source.SERVER, 4, "minecraft:furnace", TITLE, null, -1, 0).slot(1, COAL).bytes());
        assertEquals("minecraft:furnace", state.menuType());
        assertEquals(0, state.slot(0).length);
        assertEquals(0, state.slot(26).length);
        assertFalse(state.pointer);
    }

    @Test
    void aClosedWindowIsDescribedAsAClose() {
        WindowState closed = read(chest(), new WindowState.Parts().close().bytes());
        assertFalse(closed.open());
        WindowState fresh = read(chest());
        byte[] whole = closed.whole();
        fresh.apply(whole, 0, whole.length);
        assertFalse(fresh.open());
        assertEquals(new WindowState(), fresh);
    }

    @Test
    void anEmptySlotIsTheSameAsOneNeverWritten() {
        WindowState written = read(chest(), new WindowState.Parts().slot(80, WindowState.NONE).bytes());
        WindowState not = read(chest());
        assertEquals(written, not);
        WindowState zero = read(chest(), new WindowState.Parts().data(1, 0).bytes());
        assertEquals(not, zero, "a data slot at nought is the same as one never written");
    }

    /**
     * A furnace opened, smelting, the pointer moving, an item picked up and put down, closed, the inventory opened.
     * Playing it through, and jumping to any record by reading what a snapshot there would hold and then the records
     * after it, arrive at the same window every time; and so does a recording trimmed there, which starts with what
     * playback describes
     */
    @Test
    void aJumpLandsInTheSameWindowAsPlayingThrough() {
        List<byte[]> records = new ArrayList<>();
        records.add(new WindowState.Parts().open(WindowState.Source.SERVER, 5, "minecraft:furnace", TITLE, null, -1, 0)
            .slot(0, IRON).slot(1, COAL).pointer(false, 56, 17, 0).bytes());
        for (int progress = 1; progress <= 10; progress++) {
            WindowState.Parts parts = new WindowState.Parts().data(2, progress * 20).data(3, 200);
            if (progress % 3 == 0) {
                parts.pointer(false, 56 + progress, 17 + progress, progress % 2 == 0 ? 1 : -1);
            }
            records.add(parts.bytes());
        }
        records.add(new WindowState.Parts().slot(2, IRON).slot(0, WindowState.NONE).bytes());
        records.add(new WindowState.Parts().slot(2, WindowState.NONE).carried(IRON).pointer(false, 116, 35, 2).bytes());
        records.add(new WindowState.Parts().carried(WindowState.NONE).slot(30, IRON).pointer(false, 26, 100, 30).bytes());
        records.add(new WindowState.Parts().close().bytes());
        records.add(new WindowState.Parts().open(WindowState.Source.INVENTORY, 0, "", TITLE, null, -1, 0).slot(36, IRON).pointer(false, 10, 10, -1).bytes());
        records.add(new WindowState.Parts().slot(36, COBBLE).bytes());

        WindowState through = new WindowState();
        List<WindowState> after = new ArrayList<>();
        for (byte[] record : records) {
            through.apply(record, 0, record.length);
            after.add(through.copy());
        }
        for (int at = 0; at < records.size(); at++) {
            // The snapshot taken after record `at`, as the recorder writes it, then everything after it
            WindowState jumped = new WindowState();
            byte[] snapshot = after.get(at).whole();
            jumped.apply(snapshot, 0, snapshot.length);
            assertEquals(after.get(at), jumped, "landing after record " + at);
            for (int next = at + 1; next < records.size(); next++) {
                jumped.apply(records.get(next), 0, records.get(next).length);
                assertEquals(after.get(next), jumped, "playing on from a landing after record " + at + ", at record " + next);
            }
        }
        assertEquals(WindowState.Source.INVENTORY, through.source());
        assertArrayEquals(COBBLE, through.slot(36));
    }

    @Test
    void theStateBeforeAChangeIsNotTheStateAfterIt() {
        WindowState before = read(chest());
        WindowState after = read(chest(), new WindowState.Parts().carried(COAL).bytes());
        assertNotEquals(before, after);
    }

    @Test
    void recordsThisVersionCannotReadAreRefused() {
        byte[] future = {2, WindowState.CLOSE};
        assertThrows(IllegalArgumentException.class, () -> read(future));
        byte[] unknownPart = {WindowState.VERSION, 99};
        assertThrows(IllegalArgumentException.class, () -> read(unknownPart));
        byte[] cut = chest();
        byte[] shorter = java.util.Arrays.copyOf(cut, cut.length - 3);
        assertThrows(IllegalArgumentException.class, () -> read(shorter));
        byte[] unknownSource = new WindowState.Parts().open(WindowState.Source.SERVER, 1, "x:y", TITLE, null, -1, 0).bytes();
        unknownSource[2] = 40;
        assertThrows(IllegalArgumentException.class, () -> read(unknownSource));
    }

    @Test
    void negativeAndLargeNumbersReadBack() {
        WindowState state = read(new WindowState.Parts().open(WindowState.Source.SERVER, 127, "a:b", TITLE, null, -1, 0)
            .data(0, Integer.MIN_VALUE).data(1, Integer.MAX_VALUE).data(300, -1).slot(1000, COAL).bytes());
        assertEquals(Integer.MIN_VALUE, state.data(0));
        assertEquals(Integer.MAX_VALUE, state.data(1));
        assertEquals(-1, state.data(300));
        assertArrayEquals(COAL, state.slot(1000));
        assertEquals(127, state.containerId);
    }

}
