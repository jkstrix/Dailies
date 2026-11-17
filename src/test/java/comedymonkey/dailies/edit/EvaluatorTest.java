package comedymonkey.dailies.edit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the tracks say at a point in time (specification EDIT-2 and EDIT-4) */
class EvaluatorTest {

    private static final double EXACT = 1e-9;

    private static Track fov(Interpolation mode, double... tickThenValue) {
        Track track = new Track(KeyframeKind.FIELD_OF_VIEW);
        for (int i = 0; i < tickThenValue.length; i += 2) {
            track.put(new Keyframe.FieldOfView((int) tickThenValue[i], mode, tickThenValue[i + 1]));
        }
        return track;
    }

    private static Scene scene(Track... tracks) {
        Scene scene = new Scene("Test");
        for (Track track : tracks) {
            scene.addTrack(track);
        }
        return scene;
    }

    private static double value(Track track, double tick) {
        double[] values = Evaluator.blend(track, tick);
        assertNotNull(values, "no value at tick " + tick);
        return values[0];
    }

    @Test
    void nothingAppliesBeforeTheFirstKeyframeAndTheLastValueStays() {
        Scene scene = scene(fov(Interpolation.LINEAR, 10, 30, 20, 50));
        assertNull(Evaluator.fieldOfView(scene, 9.99));
        assertEquals(30.0, Evaluator.fieldOfView(scene, 10), EXACT);
        assertEquals(50.0, Evaluator.fieldOfView(scene, 20), EXACT);
        assertEquals(50.0, Evaluator.fieldOfView(scene, 5000), EXACT);
        assertNull(Evaluator.fieldOfView(scene(), 10));
    }

    @Test
    void theModeOfTheEarlierKeyframeDecides() {
        Track track = new Track(KeyframeKind.FIELD_OF_VIEW);
        track.put(new Keyframe.FieldOfView(0, Interpolation.HOLD, 10));
        track.put(new Keyframe.FieldOfView(10, Interpolation.LINEAR, 20));
        track.put(new Keyframe.FieldOfView(20, Interpolation.EASE_IN, 40));
        track.put(new Keyframe.FieldOfView(30, Interpolation.EASE_OUT, 60));
        track.put(new Keyframe.FieldOfView(40, Interpolation.EASE_IN_OUT, 80));
        track.put(new Keyframe.FieldOfView(50, Interpolation.HOLD, 100));

        assertEquals(10.0, value(track, 5), EXACT);
        assertEquals(10.0, value(track, 9.999), EXACT);
        assertEquals(20.0, value(track, 10), EXACT);
        assertEquals(25.0, value(track, 12.5), EXACT);
        assertEquals(30.0, value(track, 15), EXACT);
        // Ease in is behind a straight line half way, ease out ahead, ease in and out level with it
        assertEquals(45.0, value(track, 25), EXACT);
        assertEquals(75.0, value(track, 35), EXACT);
        assertEquals(90.0, value(track, 45), EXACT);
        assertTrue(value(track, 41) < 82.0);
        assertTrue(value(track, 49) > 98.0);
    }

    @Test
    void everyModePassesThroughEveryKeyframeAndStaysBetweenNeighbours() {
        for (Interpolation mode : Interpolation.values()) {
            Track track = fov(mode, 0, 70, 10, 30, 25, 90, 26, 60, 100, 61);
            for (Keyframe keyframe : track.keyframes()) {
                assertEquals(((Keyframe.FieldOfView) keyframe).fov(), value(track, keyframe.tick()), EXACT, mode.id());
            }
            for (double tick = 0; tick <= 100; tick += 0.125) {
                double value = value(track, tick);
                Keyframe.FieldOfView from = (Keyframe.FieldOfView) track.atOrBefore(tick);
                Keyframe.FieldOfView to = (Keyframe.FieldOfView) track.keyframes().get(Math.min(4, track.indexAtOrBefore(tick) + 1));
                assertTrue(value >= Math.min(from.fov(), to.fov()) - EXACT && value <= Math.max(from.fov(), to.fov()) + EXACT, mode.id() + " at " + tick);
            }
        }
    }

    @Test
    void smoothIsACurveWithoutCornersAtTheKeyframes() {
        Track track = fov(Interpolation.SMOOTH, 0, 10, 100, 30, 200, 90, 300, 100);
        // Not a straight line between the keyframes
        assertTrue(Math.abs(value(track, 150) - 60.0) > 0.5);
        // The slope just before a keyframe is the slope just after it
        for (int tick : new int[]{100, 200}) {
            double before = (value(track, tick) - value(track, tick - 0.001)) / 0.001;
            double after = (value(track, tick + 0.001) - value(track, tick)) / 0.001;
            assertEquals(before, after, 0.01, "slope at " + tick);
            assertTrue(before > 0.0);
        }
        // And it is continuous everywhere
        double last = value(track, 0);
        for (double tick = 0.01; tick <= 300; tick += 0.01) {
            double now = value(track, tick);
            assertTrue(Math.abs(now - last) < 0.02 && now >= last - EXACT, "jump at " + tick);
            last = now;
        }
    }

    @Test
    void smoothDoesNotOvershootOnUnevenSpacing() {
        // One tick apart, then a thousand: a naive spline through these swings far outside the values
        Track track = fov(Interpolation.SMOOTH, 0, 20, 1, 30, 1001, 31, 1002, 21);
        for (double tick = 0; tick <= 1; tick += 0.01) {
            assertTrue(value(track, tick) >= 20 - EXACT && value(track, tick) <= 30 + EXACT, "at " + tick);
        }
        for (double tick = 1; tick <= 1001; tick += 0.25) {
            assertTrue(value(track, tick) >= 30 - EXACT && value(track, tick) <= 31 + EXACT, "at " + tick + ": " + value(track, tick));
        }
        for (double tick = 1001; tick <= 1002; tick += 0.01) {
            assertTrue(value(track, tick) >= 21 - EXACT && value(track, tick) <= 31 + EXACT, "at " + tick);
        }
        assertEquals(30.0, value(track, 1), EXACT);
        assertEquals(31.0, value(track, 1001), EXACT);
    }

    @Test
    void smoothStaysInRangeOnRandomTracks() {
        Random random = new Random(42);
        for (int round = 0; round < 200; round++) {
            Track track = new Track(KeyframeKind.FIELD_OF_VIEW);
            int tick = 0;
            for (int i = 0; i < 8; i++) {
                track.put(new Keyframe.FieldOfView(tick, Interpolation.SMOOTH, 1 + random.nextDouble() * 169));
                tick += random.nextBoolean() ? 1 + random.nextInt(3) : 1 + random.nextInt(2000);
            }
            List<Keyframe> keyframes = track.keyframes();
            for (int i = 0; i + 1 < keyframes.size(); i++) {
                double a = ((Keyframe.FieldOfView) keyframes.get(i)).fov();
                double b = ((Keyframe.FieldOfView) keyframes.get(i + 1)).fov();
                assertEquals(a, value(track, keyframes.get(i).tick()), EXACT);
                for (int step = 1; step < 50; step++) {
                    double at = keyframes.get(i).tick() + (keyframes.get(i + 1).tick() - keyframes.get(i).tick()) * step / 50.0;
                    double value = value(track, at);
                    assertTrue(value >= Math.min(a, b) - EXACT && value <= Math.max(a, b) + EXACT, "round " + round + " at " + at);
                }
            }
        }
    }

    @Test
    void yawTakesTheShortWayRound() {
        Track track = new Track(KeyframeKind.CAMERA);
        track.put(new Keyframe.Camera(0, Interpolation.LINEAR, 0, 64, 0, 170, 0, 0));
        track.put(new Keyframe.Camera(10, Interpolation.LINEAR, 10, 64, 0, -170, 20, 90));
        track.put(new Keyframe.Camera(20, Interpolation.LINEAR, 10, 64, 0, 0, 20, 90));
        Scene scene = scene(track);

        Evaluator.CameraPose quarter = Evaluator.camera(scene, 2.5);
        assertEquals(2.5, quarter.x(), EXACT);
        assertEquals(175.0, quarter.yaw(), EXACT);
        assertEquals(5.0, quarter.pitch(), EXACT);
        assertEquals(22.5, quarter.roll(), EXACT);
        // Across the seam at 180 the value jumps to -180 but the direction does not
        assertEquals(-175.0, Evaluator.camera(scene, 7.5).yaw(), EXACT);
        assertEquals(180.0, Math.abs(Evaluator.camera(scene, 5).yaw()), EXACT);
        // From -170 to 0 the short way is forwards through -85, not back through 95
        assertEquals(-85.0, Evaluator.camera(scene, 15).yaw(), EXACT);
        assertEquals(0.0, Evaluator.camera(scene, 20).yaw(), EXACT);
    }

    @Test
    void smoothYawCrossesTheSeamWithoutSpinning() {
        Track track = new Track(KeyframeKind.CAMERA);
        double[] yaws = {150, 170, -170, -150, -130};
        for (int i = 0; i < yaws.length; i++) {
            track.put(new Keyframe.Camera(i * 10, Interpolation.SMOOTH, 0, 0, 0, yaws[i], 0, 0));
        }
        Scene scene = scene(track);
        // A steady turn of two degrees a tick, seam or no seam
        for (double tick = 0; tick <= 40; tick += 0.5) {
            double expected = Keyframe.wrapDegrees(150 + 2 * tick);
            assertEquals(0.0, Keyframe.wrapDegrees(Evaluator.camera(scene, tick).yaw() - expected), 1e-6, "at " + tick);
        }
    }

    @Test
    void orbitAngleTakesTheShortWayRoundAndGivesACameraLookingAtTheCentre() {
        Track track = new Track(KeyframeKind.ORBIT);
        track.put(new Keyframe.Orbit(0, Interpolation.LINEAR, 100, 64, 200, 10, 0, -160));
        track.put(new Keyframe.Orbit(20, Interpolation.LINEAR, 100, 64, 200, 20, 10, 160));
        Scene scene = scene(track);
        Evaluator.OrbitValue half = Evaluator.orbit(scene, 10);
        assertEquals(180.0, Math.abs(half.angle()), EXACT);
        assertEquals(15.0, half.distance(), EXACT);
        assertEquals(5.0, half.height(), EXACT);

        // Yaw 0 looks towards +Z, so the camera stands on the -Z side of the centre
        Evaluator.CameraPose pose = new Evaluator.OrbitValue(100, 64, 200, 10, 10, 0).pose();
        assertEquals(100.0, pose.x(), EXACT);
        assertEquals(74.0, pose.y(), EXACT);
        assertEquals(190.0, pose.z(), EXACT);
        assertEquals(0.0, pose.yaw(), EXACT);
        assertEquals(45.0, pose.pitch(), EXACT);
        // Yaw 90 looks towards -X, so the camera stands on the +X side
        pose = new Evaluator.OrbitValue(100, 64, 200, 10, 0, 90).pose();
        assertEquals(110.0, pose.x(), EXACT);
        assertEquals(200.0, pose.z(), EXACT);
        assertEquals(0.0, pose.pitch(), EXACT);
    }

    @Test
    void theUpperTrackWinsWhereItApplies() {
        Track upper = fov(Interpolation.LINEAR, 100, 30, 200, 40);
        Track lower = fov(Interpolation.LINEAR, 0, 90);
        Scene scene = scene(upper, lower);

        // Before the upper track's first keyframe the lower one has its say
        assertEquals(90.0, Evaluator.fieldOfView(scene, 50), EXACT);
        assertSame(lower, Evaluator.applyingTrack(scene, KeyframeKind.FIELD_OF_VIEW, 99.9));
        assertEquals(30.0, Evaluator.fieldOfView(scene, 100), EXACT);
        assertEquals(35.0, Evaluator.fieldOfView(scene, 150), EXACT);
        // And it keeps winning after its last keyframe
        assertEquals(40.0, Evaluator.fieldOfView(scene, 900), EXACT);

        upper.setEnabled(false);
        assertEquals(90.0, Evaluator.fieldOfView(scene, 150), EXACT);
        lower.setEnabled(false);
        assertNull(Evaluator.fieldOfView(scene, 150));
        // An empty track on top applies nowhere
        upper.setEnabled(true);
        upper.clear();
        lower.setEnabled(true);
        assertEquals(90.0, Evaluator.fieldOfView(scene, 150), EXACT);
    }

    @Test
    void tracksOfOtherKindsDoNotInterfere() {
        Track speed = new Track(KeyframeKind.SPEED);
        speed.put(new Keyframe.Speed(0, Interpolation.LINEAR, 10));
        speed.put(new Keyframe.Speed(100, Interpolation.LINEAR, 30));
        Track shake = new Track(KeyframeKind.CAMERA_SHAKE);
        shake.put(new Keyframe.CameraShake(0, Interpolation.LINEAR, 0, 2));
        shake.put(new Keyframe.CameraShake(10, Interpolation.LINEAR, 1, 4));
        Track time = new Track(KeyframeKind.TIME_OF_DAY);
        time.put(new Keyframe.TimeOfDay(0, Interpolation.LINEAR, 0));
        time.put(new Keyframe.TimeOfDay(100, Interpolation.LINEAR, 12000));
        Scene scene = scene(speed, shake, time);

        assertEquals(20.0, Evaluator.speed(scene, 50), EXACT);
        assertEquals(new Evaluator.ShakeValue(0.5, 3.0), Evaluator.shake(scene, 5));
        assertEquals(3000.0, Evaluator.timeOfDay(scene, 25), EXACT);
        assertNull(Evaluator.fieldOfView(scene, 50));
        assertNull(Evaluator.camera(scene, 50));
        assertEquals(TimeMap.DEFAULT_TICKS_PER_SECOND, Evaluator.speed(scene(), 50));
    }

    @Test
    void followBlendsItsOffsetButNotItsTarget() {
        UUID first = UUID.fromString("0b1e6f0e-5a52-4c61-9a37-0c1d2e3f4a5b");
        UUID second = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");
        Track track = new Track(KeyframeKind.FOLLOW_ENTITY);
        track.put(new Keyframe.FollowEntity(0, Interpolation.LINEAR, first, 7, Keyframe.BodyPart.HEAD, 0, 2, -4, Keyframe.FollowMode.KEEP_RELATIVE));
        track.put(new Keyframe.FollowEntity(10, Interpolation.LINEAR, second, 9, Keyframe.BodyPart.FEET, 2, 4, -8, Keyframe.FollowMode.WATCH_FROM_FIXED));
        Scene scene = scene(track);

        assertNull(Evaluator.follow(scene, -1));
        assertEquals(new Evaluator.FollowValue(first, 7, Keyframe.BodyPart.HEAD, Keyframe.FollowMode.KEEP_RELATIVE, 1, 3, -6), Evaluator.follow(scene, 5));
        assertEquals(new Evaluator.FollowValue(second, 9, Keyframe.BodyPart.FEET, Keyframe.FollowMode.WATCH_FROM_FIXED, 2, 4, -8), Evaluator.follow(scene, 10));
    }

    @Test
    void kindsThatDoNotBlendGiveTheLatestKeyframe() {
        Track smart = new Track(KeyframeKind.SMART_CAMERA);
        smart.put(Keyframe.example(KeyframeKind.SMART_CAMERA).withTick(50));
        smart.put(Keyframe.example(KeyframeKind.SMART_CAMERA).withTick(80));
        Scene scene = scene(smart);
        assertNull(Evaluator.smartCamera(scene, 49.5));
        assertEquals(50, Evaluator.smartCamera(scene, 79.9).tick());
        assertEquals(80, Evaluator.smartCamera(scene, 80).tick());
        assertEquals(80, Evaluator.latest(scene, KeyframeKind.SMART_CAMERA, 500).tick());
        assertNull(Evaluator.blend(smart, 60));
    }

    @Test
    void theCameraDriverIsTheUppermostCameraTrackThatApplies() {
        Track orbit = new Track(KeyframeKind.ORBIT);
        orbit.put(Keyframe.example(KeyframeKind.ORBIT).withTick(100));
        Track fov = fov(Interpolation.LINEAR, 0, 70);
        Track camera = new Track(KeyframeKind.CAMERA);
        camera.put(Keyframe.example(KeyframeKind.CAMERA).withTick(10));
        Scene scene = scene(fov, orbit, camera);
        assertNull(Evaluator.cameraDriver(scene, 5));
        assertSame(camera, Evaluator.cameraDriver(scene, 50));
        assertSame(orbit, Evaluator.cameraDriver(scene, 100));
        orbit.setEnabled(false);
        assertSame(camera, Evaluator.cameraDriver(scene, 100));
    }

    @Test
    void blockChangesPileUpAndTheLatestWinsPerPosition() {
        Track upper = new Track(KeyframeKind.BLOCK_CHANGE);
        upper.put(new Keyframe.BlockChange(10, Interpolation.HOLD, 1, 2, 3, "minecraft:stone"));
        upper.put(new Keyframe.BlockChange(30, Interpolation.HOLD, 1, 2, 3, "minecraft:glass"));
        Track lower = new Track(KeyframeKind.BLOCK_CHANGE);
        lower.put(new Keyframe.BlockChange(10, Interpolation.HOLD, 1, 2, 3, "minecraft:dirt"));
        lower.put(new Keyframe.BlockChange(20, Interpolation.HOLD, 4, 5, 6, "minecraft:air"));
        Scene scene = scene(upper, lower);
        Evaluator.BlockPosition first = new Evaluator.BlockPosition(1, 2, 3);
        Evaluator.BlockPosition second = new Evaluator.BlockPosition(4, 5, 6);

        assertTrue(Evaluator.blockChanges(scene, 9).isEmpty());
        assertEquals(Map.of(first, "minecraft:stone"), Evaluator.blockChanges(scene, 10));
        assertEquals(Map.of(first, "minecraft:stone", second, "minecraft:air"), Evaluator.blockChanges(scene, 25));
        assertEquals(Map.of(first, "minecraft:glass", second, "minecraft:air"), Evaluator.blockChanges(scene, 30.5));
        upper.setEnabled(false);
        assertEquals(Map.of(first, "minecraft:dirt", second, "minecraft:air"), Evaluator.blockChanges(scene, 30.5));
    }

    @Test
    void audioCuesComeFromAllEnabledTracksInTickOrder() {
        Track one = new Track(KeyframeKind.AUDIO);
        one.put(new Keyframe.Audio(50, Interpolation.HOLD, "b.ogg", 1));
        one.put(new Keyframe.Audio(5, Interpolation.HOLD, "a.ogg", 0.5));
        Track two = new Track(KeyframeKind.AUDIO);
        two.put(new Keyframe.Audio(20, Interpolation.HOLD, "c.ogg", 1));
        Track off = new Track(KeyframeKind.AUDIO);
        off.put(new Keyframe.Audio(1, Interpolation.HOLD, "d.ogg", 1));
        off.setEnabled(false);
        assertEquals(List.of("a.ogg", "c.ogg", "b.ogg"), Evaluator.audioCues(scene(one, two, off)).stream().map(Keyframe.Audio::file).toList());
    }

}
