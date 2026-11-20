package comedymonkey.dailies.windows;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A compat's records as playback keeps them: read back as written, played into the newest state of each claim, and
 * described again so that a jump or a trimmed recording lands where plain play does
 */
class CompatHeldTest {

    private static final byte[] LETTER = {1, 2, 3};
    private static final byte[] LETTER_TYPED = {1, 2, 3, 4, 5};
    private static final byte[] GOGGLES = {9};

    private static void play(CompatHeld held, byte[] record) {
        held.play(record, 0, record.length);
    }

    @Test
    void recordsReadBackAsTheyWereWritten() {
        List<CompatRecord.Op> ops = List.of(CompatRecord.Op.state("letter_write", LETTER), CompatRecord.Op.pointer("letter_write", true, 0.25F, 0.75F),
            CompatRecord.Op.gone("goggles"), CompatRecord.Op.state("empty", new byte[0]));
        byte[] record = CompatRecord.write(7, ops);
        CompatRecord.Read read = CompatRecord.read(record, 0, record.length);
        assertEquals(7, read.version());
        assertEquals(ops, read.ops());

        // From part way into a larger buffer, as playback hands records over
        byte[] padded = new byte[record.length + 5];
        System.arraycopy(record, 0, padded, 3, record.length);
        assertEquals(ops, CompatRecord.read(padded, 3, record.length).ops());
    }

    @Test
    void unreadableRecordsAreRefused() {
        byte[] record = CompatRecord.write(1, List.of(CompatRecord.Op.state("a", LETTER)));
        assertThrows(IllegalArgumentException.class, () -> CompatRecord.read(record, 0, record.length - 1));
        assertThrows(IllegalArgumentException.class, () -> CompatRecord.read(new byte[]{1}, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> CompatRecord.read(new byte[]{1, 9, 1, 'a'}, 0, 4));
        assertThrows(IllegalArgumentException.class, () -> CompatRecord.write(1, List.of()));
        // Nothing of a record that cannot be read is kept
        CompatHeld held = new CompatHeld();
        assertThrows(IllegalArgumentException.class, () -> held.play(record, 0, record.length - 1));
        assertTrue(held.isEmpty());
    }

    @Test
    void playingKeepsTheNewestStateOfEachClaim() {
        CompatHeld held = new CompatHeld();
        play(held, CompatRecord.write(1, List.of(CompatRecord.Op.state("letter_write", LETTER), CompatRecord.Op.pointer("letter_write", false, 12.5F, 40.0F))));
        play(held, CompatRecord.write(1, List.of(CompatRecord.Op.state("goggles", GOGGLES))));
        assertEquals(Set.of("letter_write", "goggles"), held.showing());
        assertArrayEquals(LETTER, held.held("letter_write").state());
        assertTrue(held.hasPointer("letter_write"));
        assertFalse(held.hasPointer("goggles"));

        byte[] typed = CompatRecord.write(2, List.of(CompatRecord.Op.state("letter_write", LETTER_TYPED)));
        CompatHeld.Played played = held.play(typed, 0, typed.length);
        assertEquals(Set.of("letter_write"), played.changed());
        assertFalse(played.pointerMoved());
        assertArrayEquals(LETTER_TYPED, held.held("letter_write").state());
        // Each state with the version that wrote it, for the compat to read
        assertEquals(2, held.held("letter_write").version());
        assertEquals(1, held.held("goggles").version());

        play(held, CompatRecord.write(2, List.of(CompatRecord.Op.gone("letter_write"))));
        assertNull(held.held("letter_write"));
        assertFalse(held.hasPointer("letter_write"));
        assertEquals(Set.of("goggles"), held.showing());
    }

    @Test
    void whatIsDescribedPlaysBackToTheSameState() {
        CompatHeld played = new CompatHeld();
        play(played, CompatRecord.write(1, List.of(CompatRecord.Op.state("goggles", GOGGLES))));
        play(played, CompatRecord.write(2, List.of(CompatRecord.Op.state("letter_read", LETTER))));
        play(played, CompatRecord.write(2, List.of(CompatRecord.Op.pointer("letter_read", true, 0.5F, 0.125F))));
        play(played, CompatRecord.write(2, List.of(CompatRecord.Op.state("letter_read", LETTER_TYPED))));

        // As a trimmed recording's first snapshot does: what is held, written again, read by a playback that has only
        // just read a snapshot
        CompatHeld after = new CompatHeld();
        List<byte[]> described = played.describe();
        assertEquals(2, described.size(), "one record for each version the states were written by");
        described.forEach(record -> play(after, record));
        assertEquals(played.showing(), after.showing());
        for (String name : played.showing()) {
            assertArrayEquals(played.held(name).state(), after.held(name).state(), name);
            assertEquals(played.held(name).version(), after.held(name).version(), name);
        }
        assertTrue(after.hasPointer("letter_read"));
        assertTrue(after.pointerShare);
        assertEquals(0.5F, after.pointerX);
        assertEquals(0.125F, after.pointerY);

        // A jump forgets everything first; nothing held describes as nothing
        played.clear();
        assertTrue(played.isEmpty());
        assertEquals(List.of(), played.describe());
    }

}
