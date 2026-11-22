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
 * The selection wheels' states (the toolbox's and the wrench's) come back as written, the same state writes the same
 * bytes, and the pointer playback draws the toolbox's wheel with lands on the segment recorded.
 */
class WheelStatesTest {

    private static final List<String> ITEMS = List.of("minecraft:iron_pickaxe", "create:wrench", "minecraft:torch", "create:cogwheel", "", "", "", "");

    private static States.ToolboxWheel toolboxWheel(String state, int hovered, boolean scrolling) {
        return new States.ToolboxWheel(state, List.of(new States.Toolbox("brown", ""), new States.Toolbox("red", "{\"text\":\"Tools\"}")), true,
            new States.Toolbox("red", "{\"text\":\"Tools\"}"), ITEMS, 0b0111, hovered, scrolling, 3, 20, "{\"text\":\"Wrench\"}");
    }

    @Test
    void theToolboxWheelKeepsWhatItPicksFromAndWhatIsUnderThePointer() {
        States.ToolboxWheel wheel = toolboxWheel("SELECT_ITEM", 1, false);
        States.ToolboxWheel read = States.readToolboxWheel(States.toolboxWheel(wheel));
        assertEquals(wheel, read);
        assertEquals("create:wrench", read.item(1));
        assertEquals("", read.item(9), "a slot past the eight is none");
        assertTrue(read.stocked(2));
        assertFalse(read.stocked(3), "the cogwheels' slot is kept for them but empty");
        assertEquals("red", read.from().colour());
        assertEquals(2, read.toolboxes().size());
        assertArrayEquals(States.toolboxWheel(wheel), States.toolboxWheel(read));
    }

    @Test
    void aToolboxWheelWithNothingPickedFromComesBack() {
        States.ToolboxWheel detach = new States.ToolboxWheel("DETACH", List.of(), false, new States.Toolbox("", ""), List.of(), 0, -5, false, 0, 7, "");
        assertEquals(detach, States.readToolboxWheel(States.toolboxWheel(detach)));
    }

    @Test
    void thePointerDrawnWithLandsOnTheRecordedSegment() {
        for (String state : List.of("SELECT_BOX", "SELECT_ITEM", "SELECT_ITEM_UNEQUIP")) {
            for (int slot = 0; slot < 8; slot++) {
                int[] at = toolboxWheel(state, slot, false).pointer();
                assertEquals(slot, States.ToolboxWheel.picked(state, at[0], at[1]), state + ", slot " + slot);
            }
            int[] deposit = toolboxWheel(state, States.ToolboxWheel.DEPOSIT, false).pointer();
            assertEquals(States.ToolboxWheel.DEPOSIT, States.ToolboxWheel.picked(state, deposit[0], deposit[1]), state + ", putting all back");
            int[] none = toolboxWheel(state, -1, false).pointer();
            assertEquals(-1, States.ToolboxWheel.picked(state, none[0], none[1]), state + ", nothing");
        }
        int[] middle = toolboxWheel("SELECT_ITEM_UNEQUIP", States.ToolboxWheel.UNEQUIP, false).pointer();
        assertEquals(States.ToolboxWheel.UNEQUIP, States.ToolboxWheel.picked("SELECT_ITEM_UNEQUIP", middle[0], middle[1]));
        int[] detach = toolboxWheel("DETACH", States.ToolboxWheel.UNEQUIP, false).pointer();
        assertEquals(States.ToolboxWheel.UNEQUIP, States.ToolboxWheel.picked("DETACH", detach[0], detach[1]));
        // Picking by scrolling: the pointer stays at the middle, where scrolling lasts
        assertArrayEquals(new int[]{0, 0}, toolboxWheel("SELECT_ITEM", 5, true).pointer());
    }

    @Test
    void theWrenchWheelKeepsTheBlockItsStatesAndTheOneHighlighted() {
        States.WrenchWheel wheel = new States.WrenchWheel(123456789L, "create:encased_fan[facing=north]", 0,
            List.of("create:encased_fan[facing=down]", "create:encased_fan[facing=up]", "create:encased_fan[facing=north]"), 2, true, -30, 70, 20, 30.0F,
            180.0F);
        States.WrenchWheel read = States.readWrenchWheel(States.wrenchWheel(wheel));
        assertEquals(wheel, read);
        assertEquals(3, read.states().size());
        assertArrayEquals(States.wrenchWheel(wheel), States.wrenchWheel(read));
        States.WrenchWheel still = new States.WrenchWheel(1L, "create:shaft[axis=x]", 0, List.of("create:shaft[axis=x]"), 0, false, 0, 0, 3, 0.0F, 0.0F);
        assertEquals(still, States.readWrenchWheel(States.wrenchWheel(still)));
    }

    @Test
    void damagedWheelsAreRefused() {
        byte[] toolbox = States.toolboxWheel(toolboxWheel("SELECT_ITEM", 1, false));
        assertThrows(IllegalArgumentException.class, () -> States.readToolboxWheel(Arrays.copyOf(toolbox, toolbox.length - 6)));
        byte[] wrench = States.wrenchWheel(new States.WrenchWheel(1L, "create:shaft[axis=x]", 0, List.of(), 0, false, 0, 0, 0, 0.0F, 0.0F));
        assertThrows(IllegalArgumentException.class, () -> States.readWrenchWheel(Arrays.copyOf(wrench, 12)));
    }

}
