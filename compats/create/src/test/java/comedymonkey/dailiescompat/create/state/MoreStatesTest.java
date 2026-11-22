package comedymonkey.dailiescompat.create.state;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The states of the Create compat's later claims (outlines, the status line, the train's bar, a linked controller being
 * bound, a schematic held and named, the station, display link, factory gauge and symmetry wand screens) come back as
 * written, with what playback draws from them, and the same state writes the same bytes.
 */
class MoreStatesTest {

    private static States.Outline box(double minX, int colour, float alpha) {
        return new States.Outline(States.BOX, new double[]{minX, -60, 6, minX + 2, -59, 7}, new long[0], "", colour, 1 / 64.0F, true, false, true,
            0xF000F0, "GLUE", "GLUE", 2, alpha);
    }

    @Test
    void outlinesKeepTheirShapeColourWidthTexturesAndFade() {
        States.Outline glue = box(0, 0xFF_4D9162, 1.0F);
        States.Outline cluster = new States.Outline(States.CLUSTER, new double[0], new long[]{-5L, 3L, 1234567L}, "", 0xFF_4D9162, 1 / 64.0F, true, false,
            true, 0xF000F0, "", "", -1, 0.5F);
        States.Outline chased = new States.Outline(States.CHASED_LINE, new double[]{0, 0, 0, 1, 2, 3, 0.25, 1}, new long[0], "", -1, 1 / 16.0F, false,
            true, false, 0xF000F0, "", "", -1, 1.0F);
        States.Outline floating = new States.Outline(States.FLOATING_ITEM, new double[]{1.5, -59.5, 2.5}, new long[0], "create:shaft", -1, 1 / 32.0F, true,
            false, false, 0xF000F0, "", "", -1, 1.0F);
        List<States.Outline> outlines = List.of(glue, cluster, chased, floating);
        List<States.Outline> read = States.readOutlines(States.outlines(outlines));
        assertEquals(outlines, read);
        assertEquals(2.0, read.get(0).coord(3), 1e-9);
        assertEquals(0.0, read.get(0).coord(9), "a number past the shape is none");
        assertArrayEquals(new long[]{-5L, 3L, 1234567L}, read.get(1).blocks());
        assertEquals(0.25, read.get(2).coord(6), 1e-9);
        assertEquals("create:shaft", read.get(3).item());
        assertEquals("GLUE", read.get(0).highlightTexture());
        assertEquals(2, read.get(0).highlightedFace());
        assertEquals(0.5F, read.get(1).alpha());
        assertArrayEquals(States.outlines(outlines), States.outlines(read));
    }

    @Test
    void outlinesThatDifferOnlyInFadeOrColourAreNotTheSame() {
        assertFalse(box(0, -1, 1.0F).equals(box(0, -1, 0.5F)));
        assertFalse(box(0, -1, 1.0F).equals(box(0, 0xFF_FF0000, 1.0F)));
        assertEquals(box(1, -1, 1.0F), box(1, -1, 1.0F));
    }

    @Test
    void theStatusLineFadesAsTheGameFadesIt() {
        States.Status status = new States.Status("{\"translate\":\"create.super_glue.too_far\",\"color\":\"#ff6171\"}", 40, false);
        assertEquals(status, States.readStatus(States.status(status)));
        assertEquals(255, status.alpha(0.0F), "steady while more than a second is left");
        assertEquals(127, new States.Status("", 10, false).alpha(0.0F));
        assertEquals(114, new States.Status("", 10, false).alpha(1.0F), "fading on between ticks");
    }

    @Test
    void theTrainsBarKeepsItsGaugesPromptAndArrow() {
        States.TrainHud hud = new States.TrainHud(0.5F, 0.75F, 80.0F, "{\"text\":\"Next stop: Brasswick\"}", true, -22.5F);
        States.TrainHud read = States.readTrainHud(States.trainHud(hud));
        assertEquals(hud, read);
        assertTrue(read.promptShadow());
        States.TrainHud quiet = new States.TrainHud(0.05F, 1.0F, 0.0F, "", false, 0.0F);
        assertEquals(quiet, States.readTrainHud(States.trainHud(quiet)));
    }

    @Test
    void aControllerBeingBoundKeepsItsLines() {
        States.BindMode bind = new States.BindMode(List.of("{\"text\":\"Bind mode active\"}", "{\"text\":\"Press W, A, S, D, Space or Shift\"}"));
        assertEquals(bind, States.readBindMode(States.bindMode(bind)));
    }

    @Test
    void aSchematicHeldKeepsItsToolsAndWhereItStands() {
        float[] placed = new float[16];
        for (int i = 0; i < 16; i++) {
            placed[i] = i * 0.5F - 3.0F;
        }
        States.Schematic deployed = new States.Schematic(4, List.of("DEPLOY", "MOVE", "MOVE_Y", "ROTATE", "FLIP", "PRINT"), 3, true, 7.5F,
            new double[]{12, 5, 9}, true, placed, new float[0], 1, true);
        States.Schematic read = States.readSchematic(States.schematic(deployed));
        assertEquals(deployed, read);
        assertEquals("ROTATE", read.tool());
        assertArrayEquals(placed, read.placed());
        assertEquals(0, read.preview().length, "no deploy preview once placed and moved");
        assertArrayEquals(new double[]{12, 5, 9}, read.size());

        States.Schematic held = new States.Schematic(-1, List.of("DEPLOY"), 0, false, 0.0F, new double[]{3, 2, 3}, false, new float[0], placed, -1, false);
        States.Schematic heldRead = States.readSchematic(States.schematic(held));
        assertEquals(held, heldRead);
        assertEquals(-1, heldRead.hotbarSlot());
        assertEquals("DEPLOY", heldRead.tool());
        assertEquals("", new States.Schematic(0, List.of(), 0, false, 0, new double[3], false, new float[0], new float[0], -1, false).tool());
    }

    @Test
    void aStationKeepsItsNamesDoorsAndTrain() {
        States.Station station = new States.Station(123456789L, false, "Brasswick Halt", "Night Mail", "NORTH",
            "0f8a2c44-2d1e-4b7a-9c35-6a2f0e1d9b77", true);
        assertEquals(station, States.readStation(States.station(station)));
        States.Station assembling = new States.Station(-42L, true, "Track Station", "", "", "", false);
        States.Station read = States.readStation(States.station(assembling));
        assertEquals(assembling, read);
        assertTrue(read.assembling());
    }

    @Test
    void aDisplayLinkKeepsItsSourceLineAndSettings() {
        byte[] settings = {10, 0, 0, 8, 0, 5, 'L', 'a', 'b', 'e', 'l', 0, 3, 'R', 'P', 'M', 0};
        States.DisplayLink link = new States.DisplayLink(77L, "create:kinetic_speed", 2, settings);
        States.DisplayLink read = States.readDisplayLink(States.displayLink(link));
        assertEquals(link, read);
        assertArrayEquals(settings, read.config());
        assertEquals(new States.DisplayLink(77L, "", 0, new byte[0]), States.readDisplayLink(States.displayLink(new States.DisplayLink(77L, "", 0,
            new byte[0]))), "a link with nothing to read from");
    }

    @Test
    void aFactoryGaugeKeepsWhatIsBeingSet() {
        States.FactoryPanel panel = new States.FactoryPanel(5L, "TOP_LEFT", "Brasswick/*", 10, 16, new int[]{4, 1, 64}, true);
        States.FactoryPanel read = States.readFactoryPanel(States.factoryPanel(panel));
        assertEquals(panel, read);
        assertArrayEquals(new int[]{4, 1, 64}, read.inputCounts());
        assertFalse(panel.equals(new States.FactoryPanel(5L, "TOP_LEFT", "Brasswick/*", 10, 16, new int[]{4, 2, 64}, true)));
    }

    @Test
    void aSchematicBeingNamedKeepsTheNameAndCursor() {
        States.SchematicName name = new States.SchematicName("Station roof", 7);
        assertEquals(name, States.readSchematicName(States.schematicName(name)));
    }

    @Test
    void theSymmetryWandKeepsItsMirror() {
        States.Symmetry symmetry = new States.Symmetry(2, 1);
        assertEquals(symmetry, States.readSymmetry(States.symmetry(symmetry)));
    }

    @Test
    void damagedLaterRecordsAreRefused() {
        byte[] outlines = States.outlines(List.of(box(0, -1, 1.0F)));
        assertThrows(IllegalArgumentException.class, () -> States.readOutlines(Arrays.copyOf(outlines, outlines.length - 5)));
        byte[] link = States.displayLink(new States.DisplayLink(1L, "x", 0, new byte[64]));
        assertThrows(IllegalArgumentException.class, () -> States.readDisplayLink(Arrays.copyOf(link, 30)), "settings cut short");
        assertThrows(IllegalArgumentException.class, () -> States.readSchematic(new byte[]{0, 0, 0, 1, 0x7F, 0, 0, 0}));
    }

}
