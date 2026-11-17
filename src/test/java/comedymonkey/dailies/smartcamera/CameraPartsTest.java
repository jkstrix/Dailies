package comedymonkey.dailies.smartcamera;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The parts the camera solvers are built from, each on its own */
class CameraPartsTest {

    private static final double DT = 0.05;

    @Test
    void theHeadingIgnoresJitterAndFollowsATurn() {
        HeadingFilter filter = new HeadingFilter();
        filter.reset(0.0);
        double worst = 0.0;
        for (int step = 0; step < 100; step++) {
            double wobble = (step % 2 == 0 ? 8.0 : -8.0);
            worst = Math.max(worst, Math.abs(filter.step(wobble, 1.0, 15.0, 0.6, DT)));
        }
        assertTrue(worst < 1.0, "wobbling within the dead band swung the heading by " + worst);
        double most = 0.0;
        for (int step = 0; step < 60; step++) {
            most = Math.max(most, filter.step(90.0, 1.0, 15.0, 0.6, DT));
        }
        assertEquals(90.0, filter.value(), 3.0, "a right-angle turn was not followed within three seconds");
        assertTrue(most < 95.0, "the heading overshot the turn: " + most);
    }

    @Test
    void framesBetweenStepsRunAlongTheCurveAndNeverAcrossACut() {
        PoseBlend blend = new PoseBlend();
        for (int tick = 0; tick <= 3; tick++) {
            blend.push(new PoseBlend.Pose(tick, tick * 2.0, 64.0, 0.0, 170.0 + tick * 5.0, 0.0, 70.0, false, false));
        }
        PoseBlend.Pose middle = blend.at(2.5);
        assertNotNull(middle);
        assertEquals(5.0, middle.x(), 1.0e-9);
        // Through 180 the short way round
        assertEquals(-177.5, middle.yaw(), 1.0e-9);
        blend.push(new PoseBlend.Pose(4, 100.0, 64.0, 0.0, 0.0, 0.0, 70.0, false, true));
        assertEquals(100.0, blend.at(3.5).x(), 1.0e-9, "a frame before a cut was blended across it");
        assertEquals(null, blend.at(9.0));
    }

    @Test
    void clearanceEasesInBeforeAWallAndNeverPassesThroughIt() {
        // A wall the camera's wanted place sweeps into as the subject walks along it
        VoxelWorld world = new VoxelWorld(64).box(2, 64, 10, 2, 70, 30);
        Clearance clearance = new Clearance();
        Vec previous = null;
        for (int step = 0; step < 200; step++) {
            Vec subject = new Vec(0.5, 65.4, step * 0.2);
            Vec wanted = subject.add(4.0, 1.0, -3.0);
            Vec eye = clearance.solve(world, subject, wanted, 0.0, step == 0 ? 0.0 : DT);
            assertFalse(world.solid(eye), "the camera went into the wall at step " + step);
            if (previous != null) {
                assertEquals(1.0, world.clip(previous, eye), 1.0e-9, "the camera went through the wall at step " + step);
            }
            previous = eye;
        }
    }

    @Test
    void keepingPlaceKeepsTheOffsetAtASteadySpeedAndSmoothsAHop() {
        TetherSolver tether = new TetherSolver();
        Vec offset = new Vec(0.0, 2.0, -5.0);
        TetherSolver.Result result = null;
        for (int step = 0; step < 100; step++) {
            Vec point = new Vec(0.5, 65.6, step * 0.25);
            result = tether.keep(CameraWorld.OPEN, point, offset, point, 0.25, 0.1, 70.0, 16.0 / 9.0, step == 0 ? -1.0 : DT, true);
        }
        Vec point = new Vec(0.5, 65.6, 99 * 0.25);
        assertTrue(result.eye().distanceTo(point.add(offset)) < 0.05, "at a steady walk the camera is " + result.eye().distanceTo(point.add(offset)) + " off its place");
        // A hop of a block is smoothed, not copied
        Vec hopped = point.add(0.0, 1.0, 0.25);
        TetherSolver.Result after = tether.keep(CameraWorld.OPEN, hopped, offset, hopped, 0.25, 0.1, 70.0, 16.0 / 9.0, DT, true);
        assertTrue(after.eye().y() - result.eye().y() < 0.5, "a hop moved the camera up by " + (after.eye().y() - result.eye().y()));
        // A teleport starts afresh, at the offset at once
        Vec far = new Vec(500.5, 70.0, 0.5);
        TetherSolver.Result jumped = tether.keep(CameraWorld.OPEN, far, offset, far, 0.25, 0.1, 70.0, 16.0 / 9.0, DT, true);
        assertTrue(jumped.eye().distanceTo(far.add(offset)) < 1.0e-6);
    }

    @Test
    void keepingPlaceBehindAWallPullsInAndWatchingTurnsSmoothly() {
        VoxelWorld world = new VoxelWorld(64).box(-5, 64, -4, 5, 70, -4);
        TetherSolver tether = new TetherSolver();
        Vec point = new Vec(0.5, 65.6, 0.5);
        TetherSolver.Result result = null;
        for (int step = 0; step < 60; step++) {
            result = tether.keep(world, point, new Vec(0.0, 1.0, -8.0), point, 0.25, 0.1, 70.0, 16.0 / 9.0, step == 0 ? -1.0 : DT, true);
            assertFalse(world.solid(result.eye()));
        }
        assertTrue(result.eye().z() > -3.0, "the camera stayed behind the wall at " + result.eye());
        assertTrue(CameraProbe.visibility(world, result.eye(), point, 1.8) > 0.5);

        TetherSolver watcher = new TetherSolver();
        Vec eye = new Vec(10.5, 67.0, 0.5);
        double lastYaw = Double.NaN;
        for (int step = 0; step < 80; step++) {
            Vec walker = new Vec(0.5, 65.6, step * 0.2);
            TetherSolver.Result watched = watcher.watch(eye, walker, walker, 0.25, 70.0, 16.0 / 9.0, step == 0 ? -1.0 : DT);
            if (!Double.isNaN(lastYaw)) {
                assertTrue(Math.abs(SmartCameraMath.wrapDegrees(watched.yaw() - lastYaw)) < 5.0, "the watching camera snapped round at step " + step);
            }
            lastYaw = watched.yaw();
            SmartCameraMath.ScreenPoint seen = SmartCameraMath.project(eye.x(), eye.y(), eye.z(), watched.yaw(), watched.pitch(), walker.x(), walker.y(),
                walker.z(), 70.0, 16.0 / 9.0);
            assertTrue(seen.inFront() && Math.abs(seen.x()) < 0.5, "the walker drifted to " + seen.x() + " of the frame at step " + step);
        }
    }

    @Test
    void theTrailFollowsTheWayRoundACorner() {
        Motion motion = new Motion();
        SubjectSample start = SubjectSample.walking(new Vec(0.5, 64, 0.5), 0.0);
        motion.reset(start, Vec.ZERO, CameraWorld.OPEN);
        for (int step = 1; step <= 40; step++) {
            Vec feet = step <= 20 ? new Vec(0.5, 64, 0.5 + step * 0.25) : new Vec(0.5 + (step - 20) * 0.25, 64, 5.5);
            motion.step(SubjectSample.walking(feet, step <= 20 ? 0.0 : -90.0), CameraWorld.OPEN, 15.0, DT);
        }
        Vec aim = new Vec(5.5, 65.35, 5.5);
        Vec back = motion.trailPoint(aim, 6.0);
        // Five blocks back along the second leg is the corner, one more is up the first leg
        assertEquals(0.5, back.x(), 1.0e-6);
        assertEquals(4.5, back.z(), 1.0e-6);
    }

}
