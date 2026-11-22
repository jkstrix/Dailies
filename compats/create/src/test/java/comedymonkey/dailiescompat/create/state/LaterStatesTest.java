package comedymonkey.dailiescompat.create.state;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void theWorldshapersScreenKeepsWhatIsBeingSet() {
        States.Worldshaper shaper = new States.Worldshaper("{\"text\":\"Handheld Worldshaper\"}", "minecraft:stone", "Checkered", "Cylinder",
            new int[]{4, 2, 1}, true, false, "Overlay", "Attached");
        States.Worldshaper read = States.readWorldshaper(States.worldshaper(shaper));
        assertEquals(shaper, read);
        assertEquals(2, read.param(1));
        assertEquals(1, read.param(5), "a number past the three is one");
        assertArrayEquals(States.worldshaper(shaper), States.worldshaper(read));
        assertFalse(shaper.equals(new States.Worldshaper(shaper.title(), shaper.block(), shaper.pattern(), shaper.brush(), new int[]{4, 3, 1}, true, false,
            shaper.tool(), shaper.placement())));
    }

    @Test
    void theSchematicsEditScreenKeepsTheCoordinatesAsTyped() {
        States.SchematicEdit edit = new States.SchematicEdit("station_roof.nbt", List.of("-12", "6", ""), 2, 0, 1, 2);
        States.SchematicEdit read = States.readSchematicEdit(States.schematicEdit(edit));
        assertEquals(edit, read);
        assertEquals("-12", read.coord(0));
        assertEquals("", read.coord(3));
    }

    @Test
    void theToolboxsMarksKeepEachSlotAndWhetherItsToolboxIsInReach() {
        States.ToolboxMarks marks = new States.ToolboxMarks(3, List.of(0, 3, 8), List.of(true, true, false));
        assertEquals(marks, States.readToolboxMarks(States.toolboxMarks(marks)));
    }

    @Test
    void theBlueprintRowKeepsItsIngredientsResultsAndShop() {
        States.Blueprint blueprint = new States.Blueprint(List.of(new States.Stack("minecraft:oak_planks", 2, false), new States.Stack("minecraft:coal", 1,
            true)), false, List.of(new States.Stack("minecraft:stick", 4, false)), false, false, false, 0, 0);
        assertEquals(blueprint, States.readBlueprint(States.blueprint(blueprint)));
        States.Blueprint shop = new States.Blueprint(List.of(new States.Stack("minecraft:emerald", 3, true)), false, List.of(new States.Stack(
            "create:cogwheel", 16, false)), false, true, false, 5, 2);
        States.Blueprint read = States.readBlueprint(States.blueprint(shop));
        assertEquals(shop, read);
        assertEquals(5, read.stock());
        assertEquals(2, read.purchases());
    }

    @Test
    void theTrackHintFadesInAsCreateFadesIt() {
        States.TrackHint hint = new States.TrackHint("{\"translate\":\"create.track.hold_for_smooth_curve\",\"with\":[\"Left Control\"]}", 5);
        assertEquals(hint, States.readTrackHint(States.trackHint(hint)));
        assertEquals(1 / 3.0F, hint.alpha(), 1e-6);
        assertEquals(0.1F, new States.TrackHint("", 4).alpha(), 1e-6, "never quite gone once shown");
        assertEquals(1.0F, new States.TrackHint("", 20).alpha(), 1e-6);
    }

    @Test
    void aPlacementHelperKeepsItsArrowAndGhostBlocks() {
        States.Placement placement = new States.Placement(7, 93.5F, 90.0F, "TEXTURE", 1.0F, List.of(new States.Ghost(-123456L, "create:shaft[axis=z]",
            0.5F, true, true), new States.Ghost(42L, "minecraft:stone", 1.0F, false, false)));
        States.Placement read = States.readPlacement(States.placement(placement));
        assertEquals(placement, read);
        assertEquals(0.49F, read.alpha(), 1e-6);
        assertEquals(1.0F, new States.Placement(12, 0, 0, "TRIANGLE", 1, List.of()).alpha());
    }

    @Test
    void theWandOfSymmetrysMirrorsKeepWhereAndHow() {
        List<States.Mirror> mirrors = List.of(new States.Mirror(0, 0.0, -61.0, -8.0, 1), new States.Mirror(2, 10.5, 64.0, -3.25, 0));
        assertEquals(mirrors, States.readMirrors(States.mirrors(mirrors)));
    }

    @Test
    void theStockKeepersExtrasKeepTheSearchAddressOrderAndScroll() {
        States.StockKeeper keeper = new States.StockKeeper("iron", true, 4, "Brasswick", List.of(new States.Stack("minecraft:iron_ingot", 64, false),
            new States.Stack("create:andesite_alloy", 12, false)), 2.5F);
        assertEquals(keeper, States.readStockKeeper(States.stockKeeper(keeper)));
    }

    @Test
    void damagedLaterStatesAreRefused() {
        byte[] blueprint = States.blueprint(new States.Blueprint(List.of(new States.Stack("minecraft:coal", 1, true)), true, List.of(), false, false, false,
            0, 0));
        assertThrows(IllegalArgumentException.class, () -> States.readBlueprint(Arrays.copyOf(blueprint, 10)));
        byte[] placement = States.placement(new States.Placement(1, 0, 0, "TEXTURE", 1, List.of(new States.Ghost(1L, "minecraft:stone", 1, false, true))));
        assertThrows(IllegalArgumentException.class, () -> States.readPlacement(Arrays.copyOf(placement, placement.length - 3)));
    }

    @Test
    void aScheduleKeepsTheEntrysEditorOpenOverIt() {
        States.Schedule editing = new States.Schedule(new byte[]{10, 0, 0, 0}, 1.5F, States.Schedule.INSTRUCTION, new byte[]{10, 1, 2, 0}, true,
            List.of("minecraft:coal", ""));
        States.Schedule read = States.readSchedule(States.schedule(editing));
        assertEquals(editing, read);
        assertEquals(List.of("minecraft:coal", ""), read.slots());
        assertFalse(editing.equals(new States.Schedule(new byte[]{10, 0, 0, 0}, 1.5F)), "an editor open is another state");
    }

    @Test
    void aScheduleRecordedBeforeItsEditorStillReads() {
        States.Schedule plain = new States.Schedule(new byte[]{10, 0, 0, 0}, 0.25F);
        byte[] now = States.schedule(plain);
        // As version 1 wrote it, before the editor was added: what follows the scroll left off
        byte[] before = Arrays.copyOf(now, 4 + 4 + 4);
        States.Schedule read = States.readSchedule(before);
        assertEquals(plain, read);
        assertEquals(States.Schedule.NOT_EDITING, read.editing());
    }

}
