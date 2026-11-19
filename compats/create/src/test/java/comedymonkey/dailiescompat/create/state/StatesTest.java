package comedymonkey.dailiescompat.create.state;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each claim's state comes back as it was written, with what playback draws from it, and the same state writes the
 * same bytes (Dailies writes a state again only when its bytes change).
 */
class StatesTest {

    private static final String SPEED = "{\"text\":\"Rotation Speed\"}";

    @Test
    void aBoardOfNumbersKeepsTheValuePickedAndItsLabel() {
        States.Board board = new States.Board(SPEED, List.of("{\"text\":\"⟳\",\"bold\":true}", "{\"text\":\"⟲\",\"bold\":true}"), 256, 32, 1, 64,
            "{\"text\":\"64 RPM\"}", List.of(), List.of());
        States.Board read = States.readBoard(States.board(board));
        assertEquals(board, read);
        assertFalse(read.iconMode());
        assertEquals(1, read.row());
        assertEquals(64, read.value());
        assertEquals("{\"text\":\"64 RPM\"}", read.label());
        assertArrayEquals(States.board(board), States.board(read));
    }

    @Test
    void aBoardOfModesKeepsEveryModesNameAndIcon() {
        States.Board board = new States.Board(SPEED, List.of("{\"text\":\"\"}"), 2, 1, 0, 2, "",
            List.of("create.mode.a", "create.mode.b", "create.mode.c"), List.of("I_A", "I_B", ""));
        States.Board read = States.readBoard(States.board(board));
        assertEquals(board, read);
        assertTrue(read.iconMode());
        assertEquals("create.mode.c", read.optionKey(2));
        assertEquals("I_B", read.optionIcon(1));
        assertEquals("", read.optionIcon(2));
        assertEquals("", read.optionKey(3), "a column past the modes has no name");
        assertEquals("", read.optionIcon(-1));
    }

    @Test
    void valueBoxesKeepTheirPlaceAndWhatTheyShow() {
        float[] pose = new float[16];
        for (int i = 0; i < 16; i++) {
            pose[i] = i * 0.25F - 1.0F;
        }
        States.Box speed = new States.Box(123456789L, pose, 1 / 64.0F, -1, false, "VALUE_BOX_HOVER_6PX", States.TEXT, "{\"text\":\"64\"}", "", "", null);
        States.Box mode = new States.Box(-5L, new float[16], 1 / 32.0F, 0xFF0000, true, "VALUE_BOX_HOVER_4PX", States.ICON, "", "I_ROTATE_CCW", "", null);
        States.Box filter = new States.Box(7L, new float[16], 1 / 64.0F, -1, true, "VALUE_BOX_HOVER_6PX", States.ITEM, "", "", "minecraft:iron_ingot",
            "{\"text\":\"16\"}");
        List<States.Box> boxes = List.of(speed, mode, filter);
        List<States.Box> read = States.readBoxes(States.boxes(boxes));
        assertEquals(boxes, read);
        assertEquals(123456789L, read.get(0).pos());
        assertArrayEquals(pose, read.get(0).pose());
        assertFalse(read.get(0).passive());
        assertEquals(States.ICON, read.get(1).kind());
        assertEquals(0xFF0000, read.get(1).overrideColor());
        assertNull(read.get(1).count(), "no amount is not an empty amount");
        assertEquals("{\"text\":\"16\"}", read.get(2).count());
        assertArrayEquals(States.boxes(boxes), States.boxes(read));
    }

    @Test
    void theTipFadesAsCreateFadesIt() {
        States.Tip tip = new States.Tip(List.of(SPEED, "{\"translate\":\"create.gui.value_settings.hold_to_edit\"}"), 8);
        assertEquals(tip, States.readTip(States.tip(tip)));
        assertEquals(0.0F, new States.Tip(List.of(), 11).alpha(), 1e-6);
        assertEquals(0.6F, new States.Tip(List.of(), 8).alpha(), 1e-6);
        assertEquals(1.0F, new States.Tip(List.of(), 5).alpha(), 1e-6, "steady, as while the box stays aimed at");
        assertEquals(0.4F, new States.Tip(List.of(), 2).alpha(), 1e-6);
    }

    @Test
    void theGogglesKeepTheirLinesAndIcon() {
        States.Goggles goggles = new States.Goggles(List.of("{\"text\":\"Kinetic Stats\"}", "{\"text\":\" 16 RPM\",\"color\":\"aqua\"}"), "create:goggles");
        States.Goggles read = States.readGoggles(States.goggles(goggles));
        assertEquals(goggles, read);
        assertEquals(2, read.lines().size());
        assertEquals("create:goggles", read.icon());
    }

    @Test
    void aGearshiftKeepsItsInstructionsInOrder() {
        States.Gearshift gearshift = new States.Gearshift(List.of(new States.Instruction("turn_angle", "forward", 90),
            new States.Instruction("delay", "forward_fast", 40), new States.Instruction("end", "forward", 1)));
        States.Gearshift read = States.readGearshift(States.gearshift(gearshift));
        assertEquals(gearshift, read);
        assertEquals("delay", read.instructions().get(1).type());
        assertEquals(40, read.instructions().get(1).value());
    }

    @Test
    void aThresholdSwitchKeepsItsThresholds() {
        States.Threshold threshold = new States.Threshold(-987654321L, 128, 512, true);
        assertEquals(threshold, States.readThreshold(States.threshold(threshold)));
    }

    @Test
    void filterExtrasKeepModesListsAndTheAddress() {
        States.Filter filter = new States.Filter(new int[]{1, 0}, List.of(), "");
        States.Filter attributes = new States.Filter(new int[]{2}, List.of("{\"text\":\"Selected\"}", "{\"text\":\"- is smeltable\"}"), "");
        States.Filter address = new States.Filter(new int[0], List.of(), "Brasswick/*");
        for (States.Filter each : List.of(filter, attributes, address)) {
            States.Filter read = States.readFilter(States.filter(each));
            assertEquals(each, read);
            assertTrue(Arrays.equals(each.modes(), read.modes()));
        }
    }

    @Test
    void anElevatorContactKeepsItsNamesAndDoors() {
        States.Elevator elevator = new States.Elevator("B2", "Basement two", "NORTH");
        assertEquals(elevator, States.readElevator(States.elevator(elevator)));
    }

    @Test
    void aDamagedRecordIsRefused() {
        byte[] whole = States.board(new States.Board(SPEED, List.of(), 1, 1, 0, 0, "", List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> States.readBoard(Arrays.copyOf(whole, whole.length - 3)));
        assertThrows(IllegalArgumentException.class, () -> States.readBoxes(new byte[]{0x7F, 0, 0, 0}));
    }

    @Test
    void textOutsideTheBasicPlaneComesBackWhole() {
        States.Tip tip = new States.Tip(List.of("{\"text\":\"⚙ Kraftwerk – ünter 🚂\"}"), 5);
        assertEquals(tip, States.readTip(States.tip(tip)));
    }

}
