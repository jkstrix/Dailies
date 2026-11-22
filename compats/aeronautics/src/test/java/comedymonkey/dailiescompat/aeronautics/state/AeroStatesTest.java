package comedymonkey.dailiescompat.aeronautics.state;

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
class AeroStatesTest {

    private static final String LINE = "{\"text\":\"Too far\",\"color\":\"#FF5555\"}";

    @Test
    void theAltitudeSensorKeepsItsHeightsTheirHandlesAndTheDrag() {
        AeroStates.Altitude sensor = new AeroStates.Altitude(123456789L, 0.75F, 0.25F, 0.7F, 0.3F, 14, false, true);
        AeroStates.Altitude read = AeroStates.readAltitude(AeroStates.altitude(sensor));
        assertEquals(sensor, read);
        assertEquals(0.75F, read.high());
        assertEquals(0.3F, read.shownLow());
        assertTrue(read.draggingHigh());
        assertFalse(read.draggingLow());
        assertArrayEquals(AeroStates.altitude(sensor), AeroStates.altitude(read));
    }

    @Test
    void theReceiverKeepsItsRangesItsBlockAndTheDistanceShown() {
        AeroStates.Receiver receiver = new AeroStates.Receiver(-42L, "simulated:modulating_linked_receiver[powered=false]", 8, 64, 31.25);
        AeroStates.Receiver read = AeroStates.readReceiver(AeroStates.receiver(receiver));
        assertEquals(receiver, read);
        assertEquals(31.25, read.distance());
        assertArrayEquals(AeroStates.receiver(receiver), AeroStates.receiver(read));
    }

    @Test
    void aNameplateKeepsItsNameAsTypedTheCursorAndItsColours() {
        AeroStates.Nameplate plate = new AeroStates.Nameplate(7L, "simulated:white_nameplate[facing=north,position=single]", "light_blue", true, "Skyhook ✈", 4, 9,
            23);
        AeroStates.Nameplate read = AeroStates.readNameplate(AeroStates.nameplate(plate));
        assertEquals(plate, read);
        assertEquals("Skyhook ✈", read.name());
        assertEquals(4, read.cursor());
        assertEquals(9, read.selection());
        assertArrayEquals(AeroStates.nameplate(plate), AeroStates.nameplate(read));
    }

    @Test
    void theDiagramKeepsItsSettingsAndForcesAsTheirCodecsWroteThem() {
        AeroStates.Diagram diagram = new AeroStates.Diagram(311, new byte[]{1, 2, 3, 4, 5}, new byte[]{9, 8, 7}, true, 140.5F, 0.6F);
        AeroStates.Diagram read = AeroStates.readDiagram(AeroStates.diagram(diagram));
        assertEquals(diagram, read);
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, read.config());
        assertArrayEquals(new byte[]{9, 8, 7}, read.forces());
        assertTrue(read.paperOut());
        // Before the server's first answer there are no forces
        AeroStates.Diagram waiting = new AeroStates.Diagram(311, new byte[]{1}, new byte[0], false, 10.0F, 0.0F);
        assertEquals(0, AeroStates.readDiagram(AeroStates.diagram(waiting)).forces().length);
    }

    @Test
    void theTypewriterKeepsItsKeysTheListAndAnEntryOpen() {
        AeroStates.Typewriter typewriter = new AeroStates.Typewriter(List.of(new AeroStates.Key(65, "minecraft:redstone", ""),
            new AeroStates.Key(87, "minecraft:diamond", "minecraft:gold_ingot")), true, 19.0F, true, 65, true);
        AeroStates.Typewriter read = AeroStates.readTypewriter(AeroStates.typewriter(typewriter));
        assertEquals(typewriter, read);
        assertEquals(2, read.keys().size());
        assertEquals("minecraft:gold_ingot", read.keys().get(1).second());
        assertTrue(read.listOpen());
        assertEquals(65, read.entryKey());
        assertTrue(read.waitingForKey());
        assertArrayEquals(AeroStates.typewriter(typewriter), AeroStates.typewriter(read));
        AeroStates.Typewriter plain = new AeroStates.Typewriter(List.of(), false, 0.0F, false, -1, false);
        assertEquals(plain, AeroStates.readTypewriter(AeroStates.typewriter(plain)));
    }

    @Test
    void linesKeepTheirPlaceFromTheMiddleAndTheFoot() {
        AeroStates.Lines lines = new AeroStates.Lines(List.of(new AeroStates.Line(LINE, -60, 68, 120, 0xFF_B3C3EF, true),
            new AeroStates.Line("{\"text\":\"Lift: 2.5 m\"}", -30, 82, 60, 0xFF_FFFFFF, false)));
        AeroStates.Lines read = AeroStates.readLines(AeroStates.lines(lines));
        assertEquals(lines, read);
        assertEquals(-60, read.lines().get(0).fromMiddle());
        assertEquals(68, read.lines().get(0).fromFoot());
        assertFalse(read.lines().get(1).backdrop());
        assertArrayEquals(AeroStates.lines(lines), AeroStates.lines(read));
    }

    @Test
    void theBindingKeepsItsLines() {
        AeroStates.Binding binding = new AeroStates.Binding(List.of("{\"text\":\"Bind mode\",\"color\":\"gold\"}", LINE));
        AeroStates.Binding read = AeroStates.readBinding(AeroStates.binding(binding));
        assertEquals(binding, read);
        assertArrayEquals(AeroStates.binding(binding), AeroStates.binding(read));
    }

    @Test
    void eachLeverKeepsItsReadout() {
        for (AeroStates.Lever lever : List.of(new AeroStates.Lever(AeroStates.ASSEMBLER, new float[]{0.2F, 0.35F}, false),
            new AeroStates.Lever(AeroStates.WHEEL, new float[]{0, 10, 90, -1, 45.5F}, false),
            new AeroStates.Lever(AeroStates.THROTTLE, new float[]{11, 0.6F, 0.7F}, true))) {
            AeroStates.Lever read = AeroStates.readLever(AeroStates.lever(lever));
            assertEquals(lever, read);
            assertArrayEquals(AeroStates.lever(lever), AeroStates.lever(read));
        }
        AeroStates.Lever wheel = AeroStates.readLever(AeroStates.lever(new AeroStates.Lever(AeroStates.WHEEL, new float[]{0, 10, 90, -1, 45.5F}, false)));
        assertEquals(45.5F, wheel.number(4));
        // A number a state does not have reads as nothing
        assertEquals(0.0F, wheel.number(9));
        assertTrue(AeroStates.readLever(AeroStates.lever(new AeroStates.Lever(AeroStates.THROTTLE, new float[]{3, 0, 0}, true))).flag());
    }

    @Test
    void theStaffsLocksKeepTheirPlaces() {
        AeroStates.Points points = new AeroStates.Points(new double[]{1.5, 70.25, -3.0, 100.0, 64.0, 100.0});
        AeroStates.Points read = AeroStates.readPoints(AeroStates.points(points));
        assertEquals(points, read);
        assertEquals(2, read.count());
        assertArrayEquals(AeroStates.points(points), AeroStates.points(read));
    }

    @Test
    void theStatusLineFadesAsTheGameFadesIt() {
        AeroStates.Status status = new AeroStates.Status(LINE, 60, false);
        AeroStates.Status read = AeroStates.readStatus(AeroStates.status(status));
        assertEquals(status, read);
        assertEquals(255, read.alpha(0.0F));
        AeroStates.Status ending = new AeroStates.Status(LINE, 10, true);
        assertEquals(127, AeroStates.readStatus(AeroStates.status(ending)).alpha(0.0F));
        assertTrue(AeroStates.readStatus(AeroStates.status(ending)).animated());
    }

    @Test
    void theTrackRowKeepsItsCount() {
        AeroStates.TrackRow row = new AeroStates.TrackRow(130, false, true);
        AeroStates.TrackRow read = AeroStates.readTrackRow(AeroStates.trackRow(row));
        assertEquals(row, read);
        assertArrayEquals(AeroStates.trackRow(row), AeroStates.trackRow(read));
    }

    @Test
    void handlesKeepTheirPointsTexturesAndColours() {
        AeroStates.Handles handles = new AeroStates.Handles(List.of(
            new AeroStates.Handle(AeroStates.ARM, new double[]{0.5, -59.0, 7.5, 2.5, -58.0, 9.5}, "simulatedcoasters:textures/misc/arm_hover.png", 1, 1, 1, 0.98F,
                true),
            new AeroStates.Handle(AeroStates.TILT, new double[]{0, 1, 2, 0, 1, 0, 1, 0, 0}, "simulatedcoasters:textures/misc/tilt.png", 1, 0.9F, 0.4F, 1, false)));
        AeroStates.Handles read = AeroStates.readHandles(AeroStates.handles(handles));
        assertEquals(handles, read);
        assertEquals(9.5, read.handles().get(0).coord(5));
        assertTrue(read.handles().get(0).flag());
        assertEquals(0.0, read.handles().get(1).coord(20));
        assertArrayEquals(AeroStates.handles(handles), AeroStates.handles(read));
    }

    @Test
    void aDamagedRecordFailsInsteadOfFillingTheMemory() {
        assertThrows(IllegalArgumentException.class, () -> AeroStates.readTypewriter(new byte[]{0x7F, 0, 0, 0}));
        assertThrows(IllegalArgumentException.class, () -> AeroStates.readStatus(new byte[]{0, 0}));
    }

    @Test
    void aStateFromALaterVersionReadsWhatThisOneKnows() {
        byte[] written = AeroStates.trackRow(new AeroStates.TrackRow(64, true, false));
        byte[] longer = java.util.Arrays.copyOf(written, written.length + 8);
        assertEquals(new AeroStates.TrackRow(64, true, false), AeroStates.readTrackRow(longer));
    }

}
