package comedymonkey.dailies.edit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The twelve kinds of keyframe and their JSON (specification chapter V, "Kinds of keyframe") */
class KeyframeTest {

    /** The example of the kind as JSON text without one of its fields (never the first) */
    private static String without(KeyframeKind kind, String field) {
        String text = Keyframe.exampleJsonText(kind);
        String shorter = text.replaceAll(",\"" + field + "\":[^,}]*", "");
        assertTrue(shorter.length() < text.length(), field + " is not in " + text);
        return shorter;
    }

    /** The message of the error the example of the kind causes when one field is given this JSON value */
    private static String refused(KeyframeKind kind, String field, String value) {
        String text = field.equals("tick") ? Keyframe.exampleJsonText(kind) : without(kind, field);
        String spoilt = text.substring(0, text.length() - 1) + ",\"" + field + "\":" + value + "}";
        return assertThrows(IllegalArgumentException.class, () -> Keyframe.fromJsonText(kind, spoilt)).getMessage();
    }

    /** The message of the error the example of the kind causes when one field is left out */
    private static String refusedWithout(KeyframeKind kind, String field) {
        String text = without(kind, field);
        return assertThrows(IllegalArgumentException.class, () -> Keyframe.fromJsonText(kind, text)).getMessage();
    }

    @Test
    void thereAreThirteenKinds() {
        assertEquals(13, KeyframeKind.values().length);
    }

    @Test
    void everyKindHasAnExampleThatReadsBackTheSame() {
        for (KeyframeKind kind : KeyframeKind.values()) {
            Keyframe example = Keyframe.example(kind);
            assertEquals(kind, example.kind());
            String json = Keyframe.exampleJsonText(kind);
            assertTrue(json.startsWith("{\"tick\":120,\"interpolation\":\"" + example.interpolation().id() + "\",\""), kind.id() + ": " + json);
            Keyframe read = Keyframe.fromJsonText(kind, json);
            assertEquals(example, read, kind.id());
            assertEquals(json, read.toJsonText(), kind.id());
        }
    }

    @Test
    void tickAndInterpolationMayBeLeftOutAndAddedAfterwards() {
        Keyframe keyframe = Keyframe.fromJsonText(KeyframeKind.FIELD_OF_VIEW, "{\"fov\": 30}");
        assertEquals(0, keyframe.tick());
        assertEquals(Interpolation.LINEAR, keyframe.interpolation());
        Keyframe placed = keyframe.withTick(240).withInterpolation(Interpolation.SMOOTH);
        assertEquals(new Keyframe.FieldOfView(240, Interpolation.SMOOTH, 30.0), placed);
        // The original is untouched
        assertEquals(0, keyframe.tick());
    }

    @Test
    void withTickAndWithInterpolationKeepTheValueOfEveryKind() {
        for (KeyframeKind kind : KeyframeKind.values()) {
            Keyframe example = Keyframe.example(kind);
            Keyframe moved = example.withTick(7).withInterpolation(Interpolation.EASE_OUT);
            assertEquals(7, moved.tick());
            assertEquals(Interpolation.EASE_OUT, moved.interpolation());
            assertEquals(example, moved.withTick(example.tick()).withInterpolation(example.interpolation()), kind.id());
        }
    }

    @Test
    void unknownFieldsAreIgnored() {
        Keyframe keyframe = Keyframe.fromJsonText(KeyframeKind.SPEED, "{\"ticks_per_second\": 5, \"from_the_future\": [1, 2]}");
        assertEquals(new Keyframe.Speed(0, Interpolation.LINEAR, 5.0), keyframe);
    }

    @Test
    void errorsNameTheFieldAndTheRange() {
        String message = refused(KeyframeKind.FIELD_OF_VIEW, "fov", "500");
        assertEquals("'fov' must be a number from 1 to 170, got 500", message);

        message = refused(KeyframeKind.CAMERA, "pitch", "120");
        assertEquals("'pitch' must be a number from -90 to 90, got 120", message);

        message = refusedWithout(KeyframeKind.CAMERA, "x");
        assertTrue(message.startsWith("'x' is required"), message);

        message = refused(KeyframeKind.CAMERA, "y", "\"high\"");
        assertTrue(message.startsWith("'y' must be a number") && message.endsWith("got \"high\""), message);

        message = refused(KeyframeKind.SPEED, "ticks_per_second", "0");
        assertTrue(message.contains("'ticks_per_second'") && message.contains("0.05 to 2000"), message);

        message = refused(KeyframeKind.FOLLOW_ENTITY, "part", "\"elbow\"");
        assertEquals("'part' must be one of feet, body, head, eyes, got \"elbow\"", message);

        message = refused(KeyframeKind.FOLLOW_ENTITY, "mode", "\"hover\"");
        assertTrue(message.contains("keep_relative, watch_from_fixed"), message);

        message = refused(KeyframeKind.FOLLOW_ENTITY, "entity_uuid", "\"Steve\"");
        assertTrue(message.startsWith("'entity_uuid' must be a UUID"), message);

        message = refused(KeyframeKind.SMART_CAMERA, "mode", "\"selfie\"");
        assertEquals("'mode' must be one of chase, tripod, orbit, director, got \"selfie\"", message);

        message = refusedWithout(KeyframeKind.SMART_CAMERA, "target");
        assertTrue(message.startsWith("'target' is required"), message);

        message = refused(KeyframeKind.BLOCK_CHANGE, "x", "1.5");
        assertTrue(message.startsWith("'x' must be a whole number"), message);

        message = refused(KeyframeKind.BLOCK_CHANGE, "block_state", "\"\"");
        assertTrue(message.startsWith("'block_state' must be a string that is not empty"), message);

        message = refused(KeyframeKind.AUDIO, "volume", "3");
        assertEquals("'volume' must be a number from 0 to 2, got 3", message);

        message = refused(KeyframeKind.TIMELAPSE, "real_seconds", "0");
        assertTrue(message.contains("'real_seconds'"), message);

        message = refused(KeyframeKind.FREEZE, "hold_seconds", "-1");
        assertTrue(message.contains("'hold_seconds'") && message.contains("0 to 3600"), message);

        message = refused(KeyframeKind.TIME_OF_DAY, "time", "30000");
        assertTrue(message.contains("'time'") && message.contains("0 to 24000"), message);

        message = refused(KeyframeKind.ORBIT, "distance", "-1");
        assertTrue(message.contains("'distance'"), message);

        message = refused(KeyframeKind.CAMERA_SHAKE, "strength", "11");
        assertTrue(message.contains("'strength'") && message.contains("0 to 10"), message);

        message = refused(KeyframeKind.CAMERA, "tick", "-5");
        assertTrue(message.startsWith("'tick' must be a whole number from 0"), message);

        message = refused(KeyframeKind.CAMERA, "interpolation", "\"wobbly\"");
        assertTrue(message.contains("hold, linear, ease_in, ease_out, ease_in_out, smooth"), message);
    }

    @Test
    void theSameRangesHoldWhenMadeInCode() {
        assertThrows(IllegalArgumentException.class, () -> new Keyframe.FieldOfView(0, Interpolation.LINEAR, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new Keyframe.FieldOfView(-1, Interpolation.LINEAR, 70));
        assertThrows(IllegalArgumentException.class, () -> new Keyframe.Camera(0, Interpolation.LINEAR, Double.NaN, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Keyframe.Audio(0, Interpolation.LINEAR, " ", 1));
    }

    @Test
    void yawAndOrbitAngleAreBroughtIntoOneTurn() {
        assertEquals(-10.0, new Keyframe.Camera(0, Interpolation.LINEAR, 0, 0, 0, 350, 0, 0).yaw(), 1e-9);
        assertEquals(-180.0, new Keyframe.Camera(0, Interpolation.LINEAR, 0, 0, 0, 180, 0, 0).yaw(), 1e-9);
        assertEquals(90.0, new Keyframe.Orbit(0, Interpolation.LINEAR, 0, 0, 0, 5, 0, -270).angle(), 1e-9);
    }

    @Test
    void smartCameraKeepsItsSettings() {
        String json = "{\"tick\": 40, \"target\": \"Alex\", \"mode\": \"ORBIT\", \"radius\": 8.0, \"degrees_per_second\": 20, \"allow_tripod\": false}";
        Keyframe.SmartCamera keyframe = (Keyframe.SmartCamera) Keyframe.fromJsonText(KeyframeKind.SMART_CAMERA, json);
        assertEquals(40, keyframe.tick());
        assertEquals("Alex", keyframe.target());
        assertEquals("orbit", keyframe.mode());
        // Settings this class knows nothing about are carried along untouched
        assertEquals("{\"tick\":40,\"interpolation\":\"linear\",\"target\":\"Alex\",\"mode\":\"orbit\",\"radius\":8.0,\"degrees_per_second\":20,"
                + "\"allow_tripod\":false}", keyframe.toJsonText());
        assertEquals(keyframe, Keyframe.fromJsonText(KeyframeKind.SMART_CAMERA, keyframe.toJsonText()));
        assertEquals(keyframe.withTick(41), Keyframe.fromJsonText(KeyframeKind.SMART_CAMERA, keyframe.toJsonText()).withTick(41));
        assertFalse(keyframe.equals(keyframe.withTick(41)));
    }

    @Test
    void kindsAndInterpolationsAreFoundWhateverTheCase() {
        assertEquals(KeyframeKind.CAMERA, KeyframeKind.parse("CAMERA"));
        assertEquals(KeyframeKind.FIELD_OF_VIEW, KeyframeKind.parse("fov"));
        assertEquals(KeyframeKind.TIME_OF_DAY, KeyframeKind.parse("Time_Of_Day"));
        assertNull(KeyframeKind.find("hologram"));
        assertNotNull(KeyframeKind.find("follow_entity"));
        String message = assertThrows(IllegalArgumentException.class, () -> KeyframeKind.parse("hologram")).getMessage();
        assertTrue(message.contains("camera, orbit, follow_entity, smart_camera"), message);
        assertEquals(Interpolation.EASE_IN_OUT, Interpolation.parse("Ease_In_Out"));
        for (KeyframeKind kind : KeyframeKind.values()) {
            assertEquals(kind, KeyframeKind.parse(kind.id()));
        }
    }

    @Test
    void easingStartsAtNoughtAndEndsAtOne() {
        for (Interpolation mode : Interpolation.values()) {
            assertEquals(0.0, mode.ease(0.0), 1e-12, mode.id());
            assertEquals(1.0, mode.ease(1.0), 1e-12, mode.id());
        }
        assertEquals(0.0, Interpolation.HOLD.ease(0.99));
        assertTrue(Interpolation.EASE_IN.ease(0.5) < 0.5);
        assertTrue(Interpolation.EASE_OUT.ease(0.5) > 0.5);
        assertEquals(0.5, Interpolation.EASE_IN_OUT.ease(0.5), 1e-12);
    }

}
