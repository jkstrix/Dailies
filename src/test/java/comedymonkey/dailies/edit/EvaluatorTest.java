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
            if (mode == Interpolation.HERMITE) {
                // The one mode that may swing past a keyframe, see hermiteIsACatmullRomCurveThatMayOvershoot
                continue;
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

        // Yaw 0 looks towards +Z, so the camera stands on the -Z side of the centre. Its feet are 8.38 above the
        // centre, so its eyes 10 above and 10 away along the ground: it looks down at 45 degrees, through the centre
        Evaluator.CameraPose pose = new Evaluator.OrbitValue(100, 64, 200, 10, 8.38, 0).pose();
        assertEquals(100.0, pose.x(), EXACT);
        assertEquals(72.38, pose.y(), EXACT);
        assertEquals(190.0, pose.z(), EXACT);
        assertEquals(0.0, pose.yaw(), EXACT);
        assertEquals(45.0, pose.pitch(), EXACT);
        // Yaw 90 looks towards -X, so the camera stands on the +X side; with its eyes level with the centre it looks
        // straight ahead
        pose = new Evaluator.OrbitValue(100, 64, 200, 10, -Evaluator.EYE_HEIGHT, 90).pose();
        assertEquals(110.0, pose.x(), EXACT);
        assertEquals(200.0, pose.z(), EXACT);
        assertEquals(0.0, pose.pitch(), EXACT);
        // Feet level with the centre: the eyes are 1.62 above it, so the camera looks down a little, not straight ahead
        pose = new Evaluator.OrbitValue(100, 64, 200, 8, 0, 0).pose();
        assertEquals(Math.toDegrees(Math.atan(1.62 / 8.0)), pose.pitch(), EXACT);
        assertTrue(pose.pitch() > 11.0 && pose.pitch() < 12.0);
    }

    @Test
    void hermiteIsACatmullRomCurveThatMayOvershoot() {
        assertEquals(Interpolation.HERMITE, Interpolation.parse("Hermite"));
        assertTrue(Interpolation.allowed().endsWith("smooth, hermite"));
        Track hermite = fov(Interpolation.HERMITE, 0, 1, 10, 11, 20, 11);
        Track smooth = fov(Interpolation.SMOOTH, 0, 1, 10, 11, 20, 11);
        // At tick 10 the slope is that of the line from tick 0 to tick 20: (11 - 1) / 20 = 0.5 a tick. At the ends it
        // is that of the stretch itself. Half way along a stretch a cubic gives (y1 + y2) / 2 + (m1 - m2) / 8, with
        // m1 and m2 the slopes times the stretch's length: (1 + 11) / 2 + (10 - 5) / 8 and (11 + 11) / 2 + (5 - 0) / 8
        assertEquals(6.625, value(hermite, 5), EXACT);
        assertEquals(11.625, value(hermite, 15), EXACT);
        // So it swings above 11 after tick 10, where the smooth curve stays level (a peak there gives it slope 0)
        assertEquals(11.0, value(smooth, 15), EXACT);
        // Through every keyframe, and without a corner at tick 10
        assertEquals(11.0, value(hermite, 10), EXACT);
        double before = (value(hermite, 10) - value(hermite, 9.999)) / 0.001;
        double after = (value(hermite, 10.001) - value(hermite, 10)) / 0.001;
        assertEquals(0.5, before, 1e-3);
        assertEquals(0.5, after, 1e-3);
    }

    @Test
    void hermiteYawCrossesTheSeamWithoutSpinning() {
        Track track = new Track(KeyframeKind.CAMERA);
        double[] yaws = {150, 170, -170, -150, -130};
        for (int i = 0; i < yaws.length; i++) {
            track.put(new Keyframe.Camera(i * 10, Interpolation.HERMITE, 0, 0, 0, yaws[i], 0, 0));
        }
        Scene scene = scene(track);
        // Every slope is two degrees a tick, so the curve is the steady turn itself, seam or no seam
        for (double tick = 0; tick <= 40; tick += 0.5) {
            double expected = Keyframe.wrapDegrees(150 + 2 * tick);
            assertEquals(0.0, Keyframe.wrapDegrees(Evaluator.camera(scene, tick).yaw() - expected), 1e-6, "at " + tick);
        }
    }

    @Test
    void aCurveTakesTheSlopeOfAStraightOrEasedNeighbour() {
        // Smooth from 1 to 11, then straight on to 31: the curve arrives at the line's slope, 2 a tick, so with m1 = 10
        // and m2 = 20 half way is (1 + 11) / 2 + (10 - 20) / 8
        Track track = new Track(KeyframeKind.FIELD_OF_VIEW);
        track.put(new Keyframe.FieldOfView(0, Interpolation.SMOOTH, 1));
        track.put(new Keyframe.FieldOfView(10, Interpolation.LINEAR, 11));
        track.put(new Keyframe.FieldOfView(20, Interpolation.LINEAR, 31));
        assertEquals(4.75, value(track, 5), EXACT);
        double before = (value(track, 10) - value(track, 9.999)) / 0.001;
        assertEquals(2.0, before, 1e-3);
        assertEquals(2.0, (value(track, 10.001) - value(track, 10)) / 0.001, 1e-9);

        // Before a stretch that eases in and out the curve arrives at rest: m2 = 0, so half way is 6 + 10 / 8
        track.put(new Keyframe.FieldOfView(10, Interpolation.EASE_IN_OUT, 11));
        assertEquals(7.25, value(track, 5), EXACT);
    }

    @Test
    void aCurveAfterAHoldStartsAsAtTheFirstKeyframe() {
        // The jump at tick 10 says nothing of how the curve should leave it: with no keyframe after tick 20 either,
        // the stretch from 10 to 20 is a straight line
        Track track = new Track(KeyframeKind.FIELD_OF_VIEW);
        track.put(new Keyframe.FieldOfView(0, Interpolation.HOLD, 100));
        track.put(new Keyframe.FieldOfView(10, Interpolation.SMOOTH, 1));
        track.put(new Keyframe.FieldOfView(20, Interpolation.SMOOTH, 11));
        assertEquals(100.0, value(track, 5), EXACT);
        assertEquals(6.0, value(track, 15), EXACT);
        assertEquals(3.5, value(track, 12.5), EXACT);
    }

    @Test
    void theTimeOfDayGoesForwardsRoundMidnight() {
        Track track = new Track(KeyframeKind.TIME_OF_DAY);
        track.put(new Keyframe.TimeOfDay(0, Interpolation.LINEAR, 22000));
        track.put(new Keyframe.TimeOfDay(40, Interpolation.LINEAR, 2000));
        // 4000 ticks forwards through midnight, not 20000 back through noon
        assertEquals(23000.0, value(track, 10), EXACT);
        assertEquals(0.0, value(track, 20), EXACT);
        assertEquals(1000.0, value(track, 30), EXACT);
        assertEquals(2000.0, value(track, 40), EXACT);

        // From morning to evening the day also goes forwards
        Track day = new Track(KeyframeKind.TIME_OF_DAY);
        day.put(new Keyframe.TimeOfDay(0, Interpolation.LINEAR, 2000));
        day.put(new Keyframe.TimeOfDay(40, Interpolation.LINEAR, 22000));
        assertEquals(12000.0, value(day, 20), EXACT);

        // A smooth curve through keyframes 1000 ticks apart, midnight among them, is a steady 100 ticks a tick
        Track smooth = new Track(KeyframeKind.TIME_OF_DAY);
        double[] times = {22000, 23000, 0, 1000};
        for (int i = 0; i < times.length; i++) {
            smooth.put(new Keyframe.TimeOfDay(i * 10, Interpolation.SMOOTH, times[i]));
        }
        assertEquals(22500.0, value(smooth, 5), EXACT);
        assertEquals(23500.0, value(smooth, 15), EXACT);
        assertEquals(500.0, value(smooth, 25), EXACT);
    }

    private static Track shake(Interpolation mode, double... tickThenFrequency) {
        Track track = new Track(KeyframeKind.CAMERA_SHAKE);
        for (int i = 0; i < tickThenFrequency.length; i += 2) {
            track.put(new Keyframe.CameraShake((int) tickThenFrequency[i], mode, 1, tickThenFrequency[i + 1]));
        }
        return track;
    }

    @Test
    void theShakePhaseAddsTheFrequencyUpOverTime() {
        // Frequency 2 rising to 4 over a second: by half way it has come 2 * 0.5 + 0.5 * 0.5 * 1 = 1.25, by the end
        // (2 + 4) / 2 = 3, and a second at 4 after that brings it to 7
        Track ramp = shake(Interpolation.LINEAR, 0, 2, 20, 4);
        assertEquals(0.0, Evaluator.shakePhase(ramp, 0), EXACT);
        assertEquals(1.25, Evaluator.shakePhase(ramp, 10), EXACT);
        assertEquals(3.0, Evaluator.shakePhase(ramp, 20), EXACT);
        assertEquals(7.0, Evaluator.shakePhase(ramp, 40), EXACT);

        // The same ramp late in a recording gives the same: only the time since the first keyframe counts
        Track late = shake(Interpolation.LINEAR, 10000, 2, 10020, 4);
        assertEquals(0.0, Evaluator.shakePhase(late, 9999), EXACT);
        assertEquals(1.25, Evaluator.shakePhase(late, 10010), EXACT);

        // A hold changes the speed at the next keyframe without a jump in the phase
        Track hold = shake(Interpolation.HOLD, 0, 2, 20, 4);
        assertEquals(2.0, Evaluator.shakePhase(hold, 20), EXACT);
        assertEquals(1.9999, Evaluator.shakePhase(hold, 19.999), 1e-9);
        assertEquals(4.0, Evaluator.shakePhase(hold, 30), EXACT);

        // Easing in: 2 + 2 t^2 over the second adds up to 2 + 2 / 3
        assertEquals(8.0 / 3.0, Evaluator.shakePhase(shake(Interpolation.EASE_IN, 0, 2, 20, 4), 20), EXACT);
        // A frequency of 0 still wobbles at the slowest rate, 0.1 a second
        assertEquals(0.1, Evaluator.shakePhase(shake(Interpolation.LINEAR, 0, 0), 20), EXACT);
        assertEquals(0.0, Evaluator.shakePhase(fov(Interpolation.LINEAR, 0, 70), 20), EXACT);
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
