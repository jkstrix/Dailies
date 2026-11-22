package comedymonkey.dailiescompat.createextras.state;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each claim's state comes back as it was written, with what playback draws from it, and the same state writes the
 * same bytes (Dailies writes a state again only when its bytes change).
 */
class ExtrasStatesTest {

    private static final String LINE = "{\"text\":\"Sneak to break\",\"color\":\"#FFAA00\"}";

    @Test
    void aPageKeepsEachThingDrawnInOrderWithItsTurnAndPlace() {
        float[] plain = {1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F};
        float[] scaled = {0.5F, 0.0F, 0.0F, 0.5F, 380.0F, 200.0F, 10.0F};
        ExtrasStates.Op fill = new ExtrasStates.Op(ExtrasStates.FILL, plain, new int[]{0, 0, 427, 240, 0, 0xFF00_0000}, "");
        ExtrasStates.Op blit = new ExtrasStates.Op(ExtrasStates.BLIT, scaled, new int[]{2, 4, 16, 16, Float.floatToIntBits(32.0F), Float.floatToIntBits(0.5F),
            16, 16, 64, 64, 0}, "create_jetpack:textures/gui/controls.png");
        ExtrasStates.Op sprite = new ExtrasStates.Op(ExtrasStates.SPRITE, plain, new int[]{10, 10, 9, 9, 0}, "minecraft:hud/air");
        ExtrasStates.Op text = new ExtrasStates.Op(ExtrasStates.TEXT, scaled, new int[]{0, 12, 0xFF_FFFF, 1}, LINE);
        ExtrasStates.Drawn drawn = new ExtrasStates.Drawn(427, 240, List.of(fill, blit, sprite, text));
        ExtrasStates.Drawn read = ExtrasStates.readDrawn(ExtrasStates.drawn(drawn));
        assertEquals(drawn, read);
        assertEquals(4, read.ops().size());
        assertEquals(ExtrasStates.BLIT, read.ops().get(1).kind());
        assertEquals(32.0F, read.ops().get(1).decimal(4));
        assertEquals(0.5F, read.ops().get(1).decimal(5));
        assertEquals("create_jetpack:textures/gui/controls.png", read.ops().get(1).text());
        assertEquals(380.0F, read.ops().get(3).pose()[4]);
        assertEquals(LINE, read.ops().get(3).text());
        assertArrayEquals(ExtrasStates.drawn(drawn), ExtrasStates.drawn(read));
        // An op is its own copy: changing what it was made from changes nothing
        int[] numbers = {1, 2, 3, 4, 5, 6};
        ExtrasStates.Op kept = new ExtrasStates.Op(ExtrasStates.FILL, plain, numbers, "");
        numbers[0] = 99;
        assertEquals(1, kept.numbers()[0]);
        assertThrows(IllegalArgumentException.class, () -> new ExtrasStates.Op(ExtrasStates.FILL, new float[3], new int[0], ""));
    }

    @Test
    void anOverlayKeepsAsFarFromTheEdgesItSatByOnAnotherScreen() {
        float[] plain = {1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F};
        // A jetpack's controls by the bottom right corner of a screen 427 by 240, drawn on one 480 by 270
        ExtrasStates.Drawn corner = new ExtrasStates.Drawn(427, 240, List.of(new ExtrasStates.Op(ExtrasStates.BLIT, plain,
            new int[]{377, 216, 16, 16, 0, 0, 16, 16, 64, 64, 0}, "create_jetpack:textures/gui/controls.png")));
        assertArrayEquals(new float[]{53.0F, 30.0F}, ExtrasStates.shift(corner, 480, 270));
        // A PDA by the top middle moves half the change across and none down
        ExtrasStates.Drawn top = new ExtrasStates.Drawn(427, 240, List.of(new ExtrasStates.Op(ExtrasStates.TEXT, plain,
            new int[]{200, 10, 0xFFFFFF, 0}, LINE)));
        assertArrayEquals(new float[]{26.5F, 0.0F}, ExtrasStates.shift(top, 480, 270));
        // The same screen size moves nothing
        assertArrayEquals(new float[]{0.0F, 0.0F}, ExtrasStates.shift(corner, 427, 240));
        // A blackout filling the whole screen is stretched, not moved, and leaves where the words sat to decide the move
        ExtrasStates.Op blackout = new ExtrasStates.Op(ExtrasStates.FILL, plain, new int[]{0, 0, 427, 240, 0, 0xFF00_0000}, "");
        ExtrasStates.Op words = new ExtrasStates.Op(ExtrasStates.TEXT, new float[]{1.3F, 0.0F, 0.0F, 1.3F, 213.5F, 120.0F, 0.0F},
            new int[]{-40, 0, 0xFFFFFF, 1}, LINE);
        ExtrasStates.Drawn implosion = new ExtrasStates.Drawn(427, 240, List.of(blackout, words));
        assertTrue(ExtrasStates.fillsScreen(blackout, implosion));
        assertFalse(ExtrasStates.fillsScreen(words, implosion));
        assertArrayEquals(new float[]{26.5F, 15.0F}, ExtrasStates.shift(implosion, 480, 270));
    }

    @Test
    void theStatusLineKeepsItsTextItsTicksAndItsFade() {
        ExtrasStates.Status status = new ExtrasStates.Status(LINE, 37, false);
        ExtrasStates.Status read = ExtrasStates.readStatus(ExtrasStates.status(status));
        assertEquals(status, read);
        assertEquals(255, read.alpha(0.0F));
        // In its last ten ticks it fades, as the game's line does
        assertEquals(127, new ExtrasStates.Status(LINE, 10, true).alpha(0.0F));
        assertArrayEquals(ExtrasStates.status(status), ExtrasStates.status(read));
    }

    @Test
    void thePulseGeneratorKeepsItsInstructionsAsItsOwnSavedForm() {
        String instructions = "[{Type:\"output\",Param:5,Signal:15},{Type:\"wait_for\",Param:20,Signal:0},{Type:\"end\"}]";
        ExtrasStates.Pulse pulse = new ExtrasStates.Pulse(123456789L, "create_connected:sequenced_pulse_generator[facing=north]", instructions);
        ExtrasStates.Pulse read = ExtrasStates.readPulse(ExtrasStates.pulse(pulse));
        assertEquals(pulse, read);
        assertEquals(instructions, read.instructions());
        assertArrayEquals(ExtrasStates.pulse(pulse), ExtrasStates.pulse(read));
    }

    @Test
    void theClutchKeepsItsModeItsValueAndItsOperation() {
        ExtrasStates.Clutch clutch = new ExtrasStates.Clutch(-42L, "createcasing:brass_automatic_clutch[axis=x]", 3, 2048, 2);
        ExtrasStates.Clutch read = ExtrasStates.readClutch(ExtrasStates.clutch(clutch));
        assertEquals(clutch, read);
        assertEquals(2048, read.value());
        assertEquals(2, read.operation());
        assertArrayEquals(ExtrasStates.clutch(clutch), ExtrasStates.clutch(read));
    }

    @Test
    void aWheelKeepsWhatItPicksForItsItemsAndThePick() {
        ExtrasStates.Wheel recaser = new ExtrasStates.Wheel("createcasing:recaser", List.of("create:andesite_casing", "create:brass_casing"), 1, 7, false, 0,
            "");
        ExtrasStates.Wheel read = ExtrasStates.readWheel(ExtrasStates.wheel(recaser));
        assertEquals(recaser, read);
        assertEquals(List.of("create:andesite_casing", "create:brass_casing"), read.items());
        assertEquals(1, read.picked());
        assertArrayEquals(ExtrasStates.wheel(recaser), ExtrasStates.wheel(read));
        ExtrasStates.Wheel tags = new ExtrasStates.Wheel("railways:palettes/cycle_groups/base", List.of("railways:slashed_locometal", "railways:riveted_locometal"),
            -1, 20, true, 1, "{Painted:1b}");
        ExtrasStates.Wheel readTags = ExtrasStates.readWheel(ExtrasStates.wheel(tags));
        assertEquals(tags, readTags);
        assertTrue(readTags.scrolling());
        assertEquals("{Painted:1b}", readTags.tag());
    }

    @Test
    void theBogeyMenuKeepsItsCategoryItsStyleAndItsScroll() {
        ExtrasStates.Bogeys bogeys = new ExtrasStates.Bogeys(2, "railways:wide_default", 0.375F, 12);
        ExtrasStates.Bogeys read = ExtrasStates.readBogeys(ExtrasStates.bogeys(bogeys));
        assertEquals(bogeys, read);
        assertEquals("railways:wide_default", read.style());
        assertEquals(0.375F, read.scroll());
        assertArrayEquals(ExtrasStates.bogeys(bogeys), ExtrasStates.bogeys(read));
    }

    @Test
    void theSonarKeepsItsViewItsFloorAndItsMarks() {
        ExtrasStates.Sonar sonar = new ExtrasStates.Sonar(987654321L, 3.5, -12.25, 1.6F, 45.0F, -30.0F, 18.5F, List.of(
            new ExtrasStates.Contact(120.5F, 64.0F, 0, "{\"translate\":\"create_submarine.command_sub.mine\"}"),
            new ExtrasStates.Contact(80.0F, 30.25F, 3, "{\"text\":\"HMS Dailies\"}")));
        ExtrasStates.Sonar read = ExtrasStates.readSonar(ExtrasStates.sonar(sonar));
        assertEquals(sonar, read);
        assertEquals(2, read.contacts().size());
        assertEquals(3, read.contacts().get(1).kind());
        assertEquals(-12.25, read.panZ());
        assertArrayEquals(ExtrasStates.sonar(sonar), ExtrasStates.sonar(read));
        ExtrasStates.Sonar empty = new ExtrasStates.Sonar(1L, 0, 0, 1.0F, 0, 0, 0, List.of());
        assertTrue(ExtrasStates.readSonar(ExtrasStates.sonar(empty)).contacts().isEmpty());
    }

    @Test
    void theConsoleKeepsTheWidgetUnderThePointerAndTheTyping() {
        ExtrasStates.Console console = new ExtrasStates.Console(55L, 4, 0.25F, 0.75F, 55L, "120");
        ExtrasStates.Console read = ExtrasStates.readConsole(ExtrasStates.console(console));
        assertEquals(console, read);
        assertEquals("120", read.typed());
        assertArrayEquals(ExtrasStates.console(console), ExtrasStates.console(read));
        ExtrasStates.Console looking = new ExtrasStates.Console(55L, 2, 0.5F, 0.5F, ExtrasStates.NOWHERE, "");
        assertEquals(ExtrasStates.NOWHERE, ExtrasStates.readConsole(ExtrasStates.console(looking)).typing());
    }

    @Test
    void theTubeKeepsItsArrowAndItsOutline() {
        ExtrasStates.Arrow arrow = new ExtrasStates.Arrow(false, -67.5F);
        ExtrasStates.Arrow readArrow = ExtrasStates.readArrow(ExtrasStates.arrow(arrow));
        assertEquals(arrow, readArrow);
        assertFalse(readArrow.canTurn());
        assertArrayEquals(ExtrasStates.arrow(arrow), ExtrasStates.arrow(readArrow));
        ExtrasStates.Outline outline = new ExtrasStates.Outline(77L, "create_hypertube:hypertube[axis=z]", true);
        ExtrasStates.Outline readOutline = ExtrasStates.readOutline(ExtrasStates.outline(outline));
        assertEquals(outline, readOutline);
        assertTrue(readOutline.canPlace());
        assertArrayEquals(ExtrasStates.outline(outline), ExtrasStates.outline(readOutline));
    }

    @Test
    void aDamagedRecordFailsInsteadOfFillingTheMemory() {
        assertThrows(IllegalArgumentException.class, () -> ExtrasStates.readSonar(new byte[]{1, 2, 3}));
        byte[] huge = ExtrasStates.status(new ExtrasStates.Status("x", 1, false));
        huge[0] = 0x7F;
        assertThrows(IllegalArgumentException.class, () -> ExtrasStates.readStatus(huge));
    }

    @Test
    void aLaterVersionsFieldsAtTheEndAreLeftUnread() {
        byte[] state = ExtrasStates.arrow(new ExtrasStates.Arrow(true, 22.5F));
        byte[] longer = java.util.Arrays.copyOf(state, state.length + 4);
        assertEquals(new ExtrasStates.Arrow(true, 22.5F), ExtrasStates.readArrow(longer));
    }

}
