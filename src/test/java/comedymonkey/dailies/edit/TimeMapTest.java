package comedymonkey.dailies.edit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Video seconds against recording ticks under speed, timelapse and freeze keyframes (specification EXP-2) */
class TimeMapTest {

    private static final double CLOSE = 1e-6;

    private static Track speed(Interpolation mode, double... tickThenSpeed) {
        Track track = new Track(KeyframeKind.SPEED);
        for (int i = 0; i < tickThenSpeed.length; i += 2) {
            track.put(new Keyframe.Speed((int) tickThenSpeed[i], mode, tickThenSpeed[i + 1]));
        }
        return track;
    }

    private static Track timelapse(double... tickThenSeconds) {
        Track track = new Track(KeyframeKind.TIMELAPSE);
        for (int i = 0; i < tickThenSeconds.length; i += 2) {
            track.put(new Keyframe.Timelapse((int) tickThenSeconds[i], Interpolation.HOLD, tickThenSeconds[i + 1]));
        }
        return track;
    }

    private static Track freeze(double... tickThenSeconds) {
        Track track = new Track(KeyframeKind.FREEZE);
        for (int i = 0; i < tickThenSeconds.length; i += 2) {
            track.put(new Keyframe.Freeze((int) tickThenSeconds[i], Interpolation.HOLD, tickThenSeconds[i + 1]));
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

    /** The map never runs backwards, stays inside its range, and going there and back lands in the same place */
    private static void assertSound(TimeMap map) {
        double total = map.totalRealSeconds();
        double last = map.startTick();
        for (int i = -10; i <= 2010; i++) {
            double tick = map.tickAt(total * i / 2000.0);
            assertTrue(tick >= last, "runs backwards at step " + i);
            assertTrue(tick >= map.startTick() && tick <= map.endTick());
            last = tick;
        }
        assertEquals(map.startTick(), map.tickAt(0.0));
        assertEquals(map.endTick(), map.tickAt(total));
        double lastSeconds = 0.0;
        for (double tick = map.startTick(); tick <= map.endTick(); tick += 0.37) {
            double seconds = map.realSecondsAt(tick);
            assertTrue(seconds >= lastSeconds, "time runs backwards at tick " + tick);
            assertEquals(tick, map.tickAt(seconds), CLOSE, "there and back at tick " + tick);
            lastSeconds = seconds;
        }
    }

    @Test
    void withoutKeyframesTwentyTicksPassEverySecond() {
        TimeMap map = new TimeMap(scene(), 0, 200);
        assertEquals(10.0, map.totalRealSeconds(), CLOSE);
        assertEquals(50.0, map.tickAt(2.5), CLOSE);
        assertEquals(50.025, map.tickAt(2.50125), CLOSE);
        assertEquals(5.0, map.realSecondsAt(100), CLOSE);
        assertEquals(1.0, map.realSecondsBetween(60, 80), CLOSE);
        assertEquals(-1.0, map.realSecondsBetween(80, 60), CLOSE);
        assertSound(map);
    }

    @Test
    void timeIsCountedFromTheStartOfTheRange() {
        TimeMap map = new TimeMap(scene(), 100, 300);
        assertEquals(100.0, map.tickAt(0));
        assertEquals(120.0, map.tickAt(1.0), CLOSE);
        assertEquals(10.0, map.totalRealSeconds(), CLOSE);
        // Outside the range everything counts as its nearest end
        assertEquals(100.0, map.tickAt(-3));
        assertEquals(300.0, map.tickAt(99));
        assertEquals(0.0, map.realSecondsAt(5));
        assertEquals(10.0, map.realSecondsAt(100000), CLOSE);
        assertSound(map);
        assertThrows(IllegalArgumentException.class, () -> new TimeMap(scene(), 10, 5));
    }

    @Test
    void aConstantSpeedScalesTime() {
        TimeMap slow = new TimeMap(scene(speed(Interpolation.LINEAR, 0, 10)), 0, 100);
        assertEquals(10.0, slow.totalRealSeconds(), CLOSE);
        assertEquals(30.0, slow.tickAt(3.0), CLOSE);
        TimeMap fast = new TimeMap(scene(speed(Interpolation.LINEAR, 0, 80)), 0, 100);
        assertEquals(1.25, fast.totalRealSeconds(), CLOSE);
        assertEquals(80.0, fast.tickAt(1.0), CLOSE);
        assertSound(slow);
        assertSound(fast);
    }

    @Test
    void aSpeedTrackOnlyCountsFromItsFirstKeyframe() {
        // 20 a second up to tick 100, 40 a second after
        TimeMap map = new TimeMap(scene(speed(Interpolation.HOLD, 100, 40)), 0, 200);
        assertEquals(7.5, map.totalRealSeconds(), CLOSE);
        assertEquals(5.0, map.realSecondsAt(100), CLOSE);
        assertEquals(140.0, map.tickAt(6.0), CLOSE);
        assertSound(map);
    }

    @Test
    void aSpeedRampIsBlended() {
        // From 20 a second down to 5 a second over 100 ticks, in a straight line: time is the integral of 1 / speed
        TimeMap map = new TimeMap(scene(speed(Interpolation.LINEAR, 0, 20, 100, 5)), 0, 100);
        double total = Math.log(20.0 / 5.0) / 0.15;
        assertEquals(total, map.totalRealSeconds(), CLOSE);
        assertEquals(Math.log(20.0 / 12.5) / 0.15, map.realSecondsAt(50), CLOSE);
        assertEquals(50.0, map.tickAt(Math.log(20.0 / 12.5) / 0.15), 1e-5);
        // Slower towards the end: the second half takes longer than the first
        assertTrue(map.realSecondsBetween(50, 100) > map.realSecondsBetween(0, 50) * 1.5);
        // After the ramp the last speed stays
        TimeMap longer = new TimeMap(scene(speed(Interpolation.LINEAR, 0, 20, 100, 5)), 0, 200);
        assertEquals(total + 20.0, longer.totalRealSeconds(), CLOSE);
        assertSound(map);
        assertSound(longer);
    }

    @Test
    void aSmoothSpeedRampStaysBetweenItsKeyframes() {
        TimeMap map = new TimeMap(scene(speed(Interpolation.SMOOTH, 0, 20, 1, 40, 1001, 41, 1002, 10)), 0, 1002);
        // Never faster than 41 nor slower than 10 anywhere, so the total lies between these bounds
        assertTrue(map.totalRealSeconds() > 1002 / 41.0 && map.totalRealSeconds() < 1002 / 10.0);
        assertEquals(1000 / 40.5, map.realSecondsBetween(1, 1001), 0.2);
        assertSound(map);
    }

    @Test
    void aFreezeHoldsTheTickWhileTimeGoesOn() {
        TimeMap map = new TimeMap(scene(freeze(40, 2.0)), 0, 100);
        assertEquals(7.0, map.totalRealSeconds(), CLOSE);
        assertEquals(38.0, map.tickAt(1.9), CLOSE);
        assertEquals(40.0, map.tickAt(2.0), CLOSE);
        assertEquals(40.0, map.tickAt(3.0));
        assertEquals(40.0, map.tickAt(3.999));
        assertEquals(50.0, map.tickAt(4.5), CLOSE);
        assertEquals(100.0, map.tickAt(7.0));

        assertEquals(2.0, map.realSecondsAt(40), CLOSE);
        assertEquals(4.05, map.realSecondsAt(41), CLOSE);
        assertEquals(0.05, map.realSecondsBetween(39, 40), CLOSE);
        assertEquals(2.05, map.realSecondsBetween(40, 41), CLOSE);
        assertEquals(2.0, map.holdSecondsAt(40));
        assertEquals(0.0, map.holdSecondsAt(41));
        assertTrue(map.isFrozenAt(3.0));
        assertFalse(map.isFrozenAt(1.0));
        assertFalse(map.isFrozenAt(4.5));
        assertSound(map);
    }

    @Test
    void aFreezeOnTheFirstOrLastTickCounts() {
        TimeMap map = new TimeMap(scene(freeze(0, 1.0, 100, 3.0)), 0, 100);
        assertEquals(9.0, map.totalRealSeconds(), CLOSE);
        assertEquals(0.0, map.tickAt(0.99));
        assertEquals(10.0, map.tickAt(1.5), CLOSE);
        assertEquals(100.0, map.tickAt(7.0));
        assertTrue(map.isFrozenAt(0.5));
        assertTrue(map.isFrozenAt(8.0));
        // Freezes outside the range do not count
        assertEquals(5.0, new TimeMap(scene(freeze(0, 1.0, 100, 3.0)), 1, 99).totalRealSeconds() + 0.1, CLOSE);
    }

    @Test
    void aTimelapsePairPlaysItsStretchInTheStatedTime() {
        // A thousand ticks (50 seconds as recorded) in 5 seconds
        TimeMap map = new TimeMap(scene(timelapse(100, 5.0, 1100, 77.0)), 0, 1200);
        assertEquals(15.0, map.totalRealSeconds(), CLOSE);
        assertEquals(5.0, map.realSecondsBetween(100, 1100), CLOSE);
        assertEquals(100.0, map.tickAt(5.0), CLOSE);
        assertEquals(600.0, map.tickAt(7.5), CLOSE);
        assertEquals(1100.0, map.tickAt(10.0), 1e-4);
        assertEquals(1120.0, map.tickAt(11.0), 1e-4);
        assertSound(map);
    }

    @Test
    void aTimelapseOverridesSpeedAndALoneKeyframeDoesNothing() {
        Scene scene = scene(speed(Interpolation.LINEAR, 0, 40), timelapse(100, 5.0, 1100, 1.0, 1150, 9.0));
        TimeMap map = new TimeMap(scene, 0, 1200);
        assertEquals(2.5 + 5.0 + 2.5, map.totalRealSeconds(), CLOSE);
        assertEquals(5.0, map.realSecondsBetween(100, 1100), CLOSE);
        assertEquals(1.25, map.realSecondsBetween(1150, 1200), CLOSE);
        // A range that takes in only part of the pair gets its share of the time
        assertEquals(2.5, new TimeMap(scene, 600, 1100).totalRealSeconds(), CLOSE);
        assertSound(map);
    }

    @Test
    void speedTimelapseAndFreezeCombine() {
        Scene scene = scene(speed(Interpolation.LINEAR, 0, 10), timelapse(200, 2.0, 400, 2.0), freeze(50, 0.5, 300, 1.0));
        TimeMap map = new TimeMap(scene, 0, 500);
        // 200 ticks at 10 a second, half a second frozen, the timelapse with a second frozen inside it, 100 ticks more
        assertEquals(20.0 + 0.5 + 2.0 + 1.0 + 10.0, map.totalRealSeconds(), CLOSE);
        assertEquals(50.0, map.tickAt(5.25), CLOSE);
        assertEquals(60.0, map.tickAt(6.5), CLOSE);
        assertEquals(200.0, map.tickAt(20.5), 1e-4);
        assertEquals(250.0, map.tickAt(21.0), 1e-4);
        assertEquals(300.0, map.tickAt(21.5), 1e-4);
        assertEquals(300.0, map.tickAt(22.4), 1e-4);
        assertEquals(350.0, map.tickAt(23.0), 1e-4);
        assertEquals(450.0, map.tickAt(28.5), 1e-4);
        assertEquals(3.0, map.realSecondsBetween(200, 400), CLOSE);
        assertSound(map);
    }

    @Test
    void upperTracksWinAndDisabledTracksAreIgnored() {
        Track upperSpeed = speed(Interpolation.LINEAR, 100, 40);
        Track lowerSpeed = speed(Interpolation.LINEAR, 0, 10);
        Scene scene = scene(upperSpeed, lowerSpeed);
        assertEquals(10.0 + 2.5, new TimeMap(scene, 0, 200).totalRealSeconds(), CLOSE);
        upperSpeed.setEnabled(false);
        assertEquals(20.0, new TimeMap(scene, 0, 200).totalRealSeconds(), CLOSE);

        Track upperLapse = timelapse(50, 1.0, 100, 1.0);
        Track lowerLapse = timelapse(0, 8.0, 200, 8.0);
        Track upperFreeze = freeze(10, 1.0);
        Track lowerFreeze = freeze(10, 5.0, 20, 2.0);
        scene = scene(upperLapse, lowerLapse, upperFreeze, lowerFreeze);
        // Lower timelapse: 0.04 seconds a tick over 150 ticks; upper: 1 second over its 50; freezes 1 + 2
        assertEquals(6.0 + 1.0 + 3.0, new TimeMap(scene, 0, 200).totalRealSeconds(), CLOSE);
        upperLapse.setEnabled(false);
        upperFreeze.setEnabled(false);
        assertEquals(8.0 + 7.0, new TimeMap(scene, 0, 200).totalRealSeconds(), CLOSE);
    }

    @Test
    void theSameSceneAlwaysGivesTheSameMap() {
        Scene scene = scene(speed(Interpolation.SMOOTH, 0, 20, 37, 3, 90, 55, 400, 20), timelapse(120, 1.5, 180, 1.0), freeze(60, 0.7, 150, 0.2));
        TimeMap one = new TimeMap(scene, 10, 450);
        TimeMap two = new TimeMap(scene.copy(), 10, 450);
        for (int i = 0; i <= 5000; i++) {
            double seconds = one.totalRealSeconds() * i / 5000.0;
            assertEquals(Double.doubleToLongBits(one.tickAt(seconds)), Double.doubleToLongBits(two.tickAt(seconds)));
        }
        assertSound(one);
    }

    @Test
    void framesLandOnExactPointsInTime() {
        TimeMap map = new TimeMap(scene(), 0, 200);
        assertEquals(600, map.frameCount(60));
        assertEquals(0.0, map.tickAtFrame(0, 60));
        assertEquals(10.0, map.tickAtFrame(30, 60), CLOSE);
        assertEquals(20.0 * 599 / 60.0, map.tickAtFrame(599, 60), CLOSE);
        assertEquals(300, map.frameCount(29.97));
        assertEquals(20.0 * 100 / 29.97, map.tickAtFrame(100, 29.97), CLOSE);
        // Slow motion makes more frames out of the same ticks; a single tick is still one frame
        assertEquals(2400, new TimeMap(scene(speed(Interpolation.LINEAR, 0, 5)), 0, 200).frameCount(60));
        assertEquals(1, new TimeMap(scene(), 50, 50).frameCount(60));
        assertEquals(50.0, new TimeMap(scene(), 50, 50).tickAtFrame(0, 60));
    }

    @Test
    void theExportMapUsesTheExportRange() {
        Scene scene = scene(speed(Interpolation.LINEAR, 40, 10, 240, 10));
        TimeMap map = TimeMap.forExport(scene);
        assertEquals(40, map.startTick());
        assertEquals(240, map.endTick());
        scene.setExportRange(100, 120);
        map = TimeMap.forExport(scene);
        assertEquals(100, map.startTick());
        assertEquals(120, map.endTick());
        assertEquals(2.0, map.totalRealSeconds(), CLOSE);
        map = TimeMap.forExport(scene());
        assertEquals(0, map.startTick());
        assertEquals(0, map.endTick());
        assertEquals(0.0, map.totalRealSeconds());
    }

    @Test
    void theCameraPoseKeepsChangingFrameByFrameThroughAFreeze() {
        // A camera moving one block a tick from tick 0 to tick 40, and a freeze of a second on tick 20: two seconds of
        // motion and one held, so the camera takes all three seconds over its forty blocks, 40 / 3 a second
        Track camera = new Track(KeyframeKind.CAMERA);
        camera.put(new Keyframe.Camera(0, Interpolation.LINEAR, 0, 64, 0, 0, 0, 0));
        camera.put(new Keyframe.Camera(40, Interpolation.LINEAR, 40, 64, 0, 0, 0, 0));
        TimeMap map = new TimeMap(scene(camera, freeze(20, 1.0)), 0, 40);
        assertEquals(3.0, map.totalRealSeconds(), CLOSE);
        double last = -1.0;
        for (long frame = 0; frame <= 30; frame++) {
            double seconds = frame / 10.0;
            double x = Evaluator.camera(camera, map.cameraTickAt(seconds, camera)).x();
            assertEquals(40.0 * seconds / 3.0, x, CLOSE, "frame " + frame);
            assertTrue(x > last, "frame " + frame);
            last = x;
        }
        // While the world stands still on tick 20
        assertEquals(20.0, map.tickAt(1.2), CLOSE);
        assertEquals(20.0, map.tickAt(1.8), CLOSE);
        assertEquals(16.0, Evaluator.camera(camera, map.cameraTickAt(1.2, camera)).x(), CLOSE);
        assertEquals(24.0, Evaluator.camera(camera, map.cameraTickAt(1.8, camera)).x(), CLOSE);
    }

    @Test
    void theCameraCarriesOnMovingThroughAFreeze() {
        Track camera = new Track(KeyframeKind.CAMERA);
        camera.put(new Keyframe.Camera(0, Interpolation.LINEAR, 0, 64, 0, 0, 0, 0));
        camera.put(new Keyframe.Camera(100, Interpolation.LINEAR, 100, 64, 0, 0, 0, 0));
        camera.put(new Keyframe.Camera(200, Interpolation.LINEAR, 100, 64, 100, 0, 0, 0));
        Scene scene = scene(camera, freeze(40, 2.0));
        TimeMap map = new TimeMap(scene, 0, 200);

        // The first stretch lasts 7 seconds with its freeze; the camera spreads its 100 ticks over all 7
        assertEquals(40.0, map.tickAt(3.0));
        assertEquals(100.0 * 3.0 / 7.0, map.cameraTickAt(3.0, camera), CLOSE);
        assertEquals(0.0, map.cameraTickAt(0.0, camera), CLOSE);
        assertEquals(50.0, map.cameraTickAt(3.5, camera), CLOSE);
        double last = 0.0;
        for (double seconds = 0.0; seconds <= 7.0; seconds += 0.01) {
            double tick = map.cameraTickAt(seconds, camera);
            assertTrue(tick >= last && tick - last < 0.2, "the camera stops or jumps at " + seconds);
            last = tick;
        }
        // It meets the world again at the next keyframe, and without a freeze in the stretch it is simply the tick
        assertEquals(100.0, map.cameraTickAt(7.0, camera), CLOSE);
        assertEquals(map.tickAt(9.3), map.cameraTickAt(9.3, camera), CLOSE);
        assertEquals(200.0, map.cameraTickAt(12.0, camera), CLOSE);
        // A track with nothing around the tick is sampled at the tick itself
        assertEquals(40.0, map.cameraTickAt(3.0, new Track(KeyframeKind.CAMERA)));
        assertEquals(map.tickAt(3.0), map.cameraTickAt(3.0, 100, 200));
    }

}
