package comedymonkey.dailiescompat.create.state;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The states the Create compat gained once the whole of Create's play interface was taken in: a placed schematic's
 * blocks, and the parts after it, come back as written, and the same state writes the same bytes. A record written
 * before a field was added at its end is still read.
 */
class LaterStatesTest {

    private static final String HANDLE = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private static float[] placed() {
        float[] placed = new float[16];
        for (int i = 0; i < 16; i++) {
            placed[i] = i * 0.25F;
        }
        return placed;
    }

    @Test
    void aPlacedSchematicNamesItsStoredFileAndTheCopyDrawn() {
        States.Schematic schematic = new States.Schematic(2, List.of("DEPLOY", "MOVE", "FLIP"), 2, false, 0.0F, new double[]{3, 2, 3}, true, placed(),
            new float[0], -1, false, HANDLE, 2);
        States.Schematic read = States.readSchematic(States.schematic(schematic));
        assertEquals(schematic, read);
        assertEquals(HANDLE, read.blob());
        assertEquals(2, read.mirror());
        assertArrayEquals(States.schematic(schematic), States.schematic(read));
        assertFalse(schematic.equals(new States.Schematic(2, List.of("DEPLOY", "MOVE", "FLIP"), 2, false, 0.0F, new double[]{3, 2, 3}, true, placed(),
            new float[0], -1, false, HANDLE, 1)), "another mirrored copy is another state");
    }

    @Test
    void aSchematicRecordedBeforeItsBlocksStillReads() {
        States.Schematic schematic = new States.Schematic(0, List.of("DEPLOY"), 0, true, 3.0F, new double[]{1, 1, 1}, true, placed(), new float[0], 2, true);
        byte[] now = States.schematic(schematic);
        // As version 1 wrote it before the handle and the copy were added: those two left off the end
        byte[] before = Arrays.copyOf(now, now.length - 4 - 1);
        States.Schematic read = States.readSchematic(before);
        assertEquals(schematic, read);
        assertEquals("", read.blob());
        assertEquals(0, read.mirror());
    }

}
