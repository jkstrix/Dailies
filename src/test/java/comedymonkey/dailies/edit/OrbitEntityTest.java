package comedymonkey.dailies.edit;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An orbit keyframe may be centred on an entity; scenes from before that still load as they were */
class OrbitEntityTest {

    private static final UUID PIG = UUID.fromString("0b1e6f0e-5a52-4c61-9a37-0c1d2e3f4a5b");

    @Test
    void anOldOrbitLoadsRoundItsPointAndWritesNoEntity() {
        String old = "{\"tick\":20,\"interpolation\":\"linear\",\"centre_x\":10.0,\"centre_y\":64.0,\"centre_z\":-3.0,\"distance\":8.0,\"height\":2.0,\"angle\":45.0}";
        Keyframe.Orbit orbit = (Keyframe.Orbit) Keyframe.fromJsonText(KeyframeKind.ORBIT, old);
        assertFalse(orbit.onEntity());
        assertNull(orbit.entityUuid());
        assertEquals(old, orbit.toJsonText());
        // A fixed centre is still required
        assertThrows(IllegalArgumentException.class, () -> Keyframe.fromJsonText(KeyframeKind.ORBIT, "{\"distance\":8.0}"));
    }

    @Test
    void anOrbitCentredOnAnEntityKeepsItThroughJsonAndEdits() {
        String json = "{\"tick\":20,\"distance\":6.0,\"height\":1.5,\"angle\":90.0,\"entity_uuid\":\"" + PIG + "\",\"entity_id\":412}";
        Keyframe.Orbit orbit = (Keyframe.Orbit) Keyframe.fromJsonText(KeyframeKind.ORBIT, json);
        assertTrue(orbit.onEntity());
        assertEquals(PIG, orbit.entityUuid());
        assertEquals(412, orbit.entityId());
        assertEquals(0.0, orbit.centreX(), 0.0);
        assertEquals(orbit, Keyframe.fromJsonText(KeyframeKind.ORBIT, orbit.toJsonText()));
        assertEquals(PIG, orbit.withTick(40).entityUuid());
        assertEquals(PIG, orbit.withInterpolation(Interpolation.SMOOTH).entityUuid());
        assertFalse(orbit.withEntity(null, 5, 1, 2, 3).onEntity());
    }

    @Test
    void theEvaluatorTakesTheEntityFromTheEarlierKeyframeAndBlendsTheRest() {
        Scene scene = new Scene("Test");
        Track track = new Track(KeyframeKind.ORBIT);
        track.put(new Keyframe.Orbit(0, Interpolation.LINEAR, 0, 0, 0, 4, 1, 0, PIG, 7));
        track.put(new Keyframe.Orbit(20, Interpolation.LINEAR, 0, 0, 0, 8, 3, 90));
        scene.addTrack(track);
        Evaluator.OrbitValue half = Evaluator.orbit(scene, 10);
        assertEquals(PIG, half.entityUuid());
        assertEquals(7, half.entityId());
        assertEquals(6.0, half.distance(), 1.0e-9);
        assertEquals(45.0, half.angle(), 1.0e-9);
        assertFalse(Evaluator.orbit(scene, 30).onEntity());
    }

}
