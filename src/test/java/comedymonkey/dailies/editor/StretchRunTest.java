package comedymonkey.dailies.editor;

import com.google.gson.JsonObject;
import comedymonkey.dailies.edit.Interpolation;
import comedymonkey.dailies.edit.Keyframe;
import comedymonkey.dailies.edit.KeyframeKind;
import comedymonkey.dailies.edit.KeyframeRef;
import comedymonkey.dailies.edit.Scene;
import comedymonkey.dailies.edit.Track;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The path preview's "preview" button (specification EDIT-8): which stretch it plays, how fast, and how it puts things back */
class StretchRunTest {

    private static final int TOTAL = 2000;

    private static Keyframe.SmartCamera smart(int tick) {
        JsonObject settings = new JsonObject();
        settings.addProperty("target", "00000000-0000-0000-0000-000000000001");
        settings.addProperty("mode", "chase");
        return new Keyframe.SmartCamera(tick, Interpolation.HOLD, settings);
    }

    private static Track smartTrack(int... ticks) {
        Track track = new Track(KeyframeKind.SMART_CAMERA);
        for (int tick : ticks) {
            track.put(smart(tick));
        }
        return track;
    }

    private static Track cameraTrack(int... ticks) {
        Track track = new Track(KeyframeKind.CAMERA);
        for (int tick : ticks) {
            track.put(new Keyframe.Camera(tick, Interpolation.SMOOTH, 0.0, 64.0, 0.0, 0.0, 0.0, 0.0));
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

    // ------------------------------------------------------------------ Which stretch

    @Test
    void theStretchUnderThePlayheadRunsToItsTracksNextKeyframe() {
        Scene scene = scene(smartTrack(100, 600));
        assertEquals(new StretchRun.Stretch(0, 100, 599), StretchRun.find(scene, Set.of(), 250, TOTAL));
        // The last one films to the end of the recording
        assertEquals(new StretchRun.Stretch(0, 600, TOTAL), StretchRun.find(scene, Set.of(), 1500, TOTAL));
    }

    @Test
    void nothingBeforeTheFirstKeyframeOrWhereAnotherCameraFilms() {
        assertNull(StretchRun.find(scene(smartTrack(100)), Set.of(), 50, TOTAL));
        // A camera track listed above takes over at its first keyframe and keeps the camera from then on
        Scene covered = scene(cameraTrack(400, 500), smartTrack(100));
        assertEquals(new StretchRun.Stretch(1, 100, 399), StretchRun.find(covered, Set.of(), 200, TOTAL));
        assertNull(StretchRun.find(covered, Set.of(), 450, TOTAL));
        // Listed below, it never takes the camera from the Smart Camera
        Scene under = scene(smartTrack(100), cameraTrack(400, 500));
        assertEquals(new StretchRun.Stretch(0, 100, TOTAL), StretchRun.find(under, Set.of(), 200, TOTAL));
    }

    @Test
    void aSelectedSmartCameraKeyframeIsPlayedWhereverThePlayheadIs() {
        Scene scene = scene(cameraTrack(0), smartTrack(100, 600));
        // The camera track above covers both, so the selection is played only once it moves below
        assertNull(StretchRun.find(scene, Set.of(new KeyframeRef(1, 600)), 0, TOTAL));
        Scene below = scene(smartTrack(100, 600), cameraTrack(0));
        assertEquals(new StretchRun.Stretch(0, 600, TOTAL), StretchRun.find(below, Set.of(new KeyframeRef(0, 600)), 0, TOTAL));
        assertEquals(new StretchRun.Stretch(0, 100, 599), StretchRun.find(below, Set.of(new KeyframeRef(0, 100), new KeyframeRef(1, 0)), 1500, TOTAL));
        // Two selected: neither is meant more than the other, and the playhead decides
        assertEquals(new StretchRun.Stretch(0, 600, TOTAL), StretchRun.find(below, Set.of(new KeyframeRef(0, 100), new KeyframeRef(0, 600)), 700, TOTAL));
    }

    @Test
    void aDisabledOrOneTickStretchIsNotPlayed() {
        Track off = smartTrack(100);
        off.setEnabled(false);
        assertNull(StretchRun.find(scene(off), Set.of(), 200, TOTAL));
        assertNull(StretchRun.find(scene(off), Set.of(new KeyframeRef(0, 100)), 200, TOTAL));
        assertNull(StretchRun.find(scene(smartTrack(100, 101)), Set.of(), 100, TOTAL));
        assertNull(StretchRun.find(scene(smartTrack(TOTAL)), Set.of(), TOTAL, TOTAL));
    }

    // ------------------------------------------------------------------ How fast

    @Test
    void neverFasterThanTwentyTimesAsRecorded() {
        assertEquals(1, StretchRun.due(0L, 0), "the first frame plays a tick at once");
        assertEquals(0, StretchRun.due(0L, 1));
        // A second in: 400 ticks and the one at the start
        assertEquals(401, StretchRun.due(1_000_000_000L, 0));
        assertEquals(1, StretchRun.due(1_000_000_000L, 400));
        assertEquals(0, StretchRun.due(1_000_000_000L, 401));
        assertEquals(0, StretchRun.due(-5L, 3));
        for (long nanos = 0; nanos < 3_000_000_000L; nanos += 16_666_667L) {
            assertTrue(StretchRun.due(nanos, 0) <= nanos * 400.0 / 1.0e9 + 1.0);
        }
    }

    // ------------------------------------------------------------------ The run

    /** A recording that lands jumps on the next frame and plays ticks at once, with a clock moved by hand */
    private static class Fake implements StretchRun.Player {
        int position;
        boolean paused;
        int jumpTarget = -1;
        boolean pauseAfterJump;
        long nanos = 0;
        long nanosPerTick = 0;
        int ticks = 0;
        final List<Integer> jumps = new java.util.ArrayList<>();

        Fake(int position, boolean paused) {
            this.position = position;
            this.paused = paused;
        }

        /** What playback does at the start of a frame: a jump asked for lands */
        void land() {
            if (this.jumpTarget >= 0) {
                this.position = this.jumpTarget;
                this.paused = this.pauseAfterJump;
                this.jumpTarget = -1;
            }
        }

        @Override
        public int position() {
            return this.position;
        }

        @Override
        public boolean jumping() {
            return this.jumpTarget >= 0;
        }

        @Override
        public boolean paused() {
            return this.paused;
        }

        @Override
        public void pause() {
            this.paused = true;
        }

        @Override
        public void play() {
            this.paused = false;
        }

        @Override
        public void jumpTo(int tick) {
            this.jumps.add(tick);
            this.jumpTarget = tick;
            this.pauseAfterJump = true;
        }

        @Override
        public void goBack(int tick) {
            this.jumpTo(tick);
        }

        @Override
        public boolean tick() {
            if (this.position >= TOTAL) {
                return false;
            }
            this.position++;
            this.ticks++;
            this.nanos += this.nanosPerTick;
            return true;
        }

        @Override
        public long nanos() {
            return this.nanos;
        }
    }

    /** One frame of the game: playback's own work, then the run, then a sixtieth of a second */
    private static void frame(Fake fake, StretchRun run) {
        fake.land();
        run.frame();
        fake.nanos += 16_666_667L;
    }

    private static int frames(Fake fake, StretchRun run, int most) {
        int count = 0;
        while (run.phase() != StretchRun.Phase.DONE && count < most) {
            frame(fake, run);
            count++;
        }
        return count;
    }

    @Test
    void playsTheWholeStretchThenGoesBackAndPlaysOnAsBefore() {
        Fake fake = new Fake(900, false);
        StretchRun run = new StretchRun(fake, new StretchRun.Stretch(0, 100, 500));
        assertTrue(fake.paused, "the recording is paused for the run");
        assertEquals(List.of(100), fake.jumps);
        frames(fake, run, 1000);
        assertEquals(StretchRun.Phase.DONE, run.phase());
        assertTrue(run.finished() && !run.cancelled() && !run.abandoned());
        assertEquals(400, fake.ticks, "every tick of the stretch is played once, and none past it");
        assertEquals(List.of(100, 900), fake.jumps);
        assertEquals(900, fake.position, "the playhead is back where it was");
        assertFalse(fake.paused, "it plays on, as it was playing before");
        assertEquals(1.0, run.share());
    }

    @Test
    void stopsWhereItWasWhenItWasStopped() {
        Fake fake = new Fake(42, true);
        StretchRun run = new StretchRun(fake, new StretchRun.Stretch(0, 100, 300));
        frames(fake, run, 1000);
        assertEquals(42, fake.position);
        assertTrue(fake.paused, "a recording that stood still stands still again");
    }

    @Test
    void keepsToTwentyTimesAsRecordedEvenWhenTicksCostNothing() {
        Fake fake = new Fake(0, true);
        StretchRun run = new StretchRun(fake, new StretchRun.Stretch(0, 0, 1200));
        int count = frames(fake, run, 10_000);
        // 1200 ticks at 400 a second take three seconds: 180 frames at 60 a second, give or take the frames to go and come back
        assertTrue(count >= 178 && count <= 184, count + " frames");
        assertTrue(run.speed() <= 20.0 + 0.2, "it went at " + run.speed() + " times as recorded");
    }

    @Test
    void slowTicksAreCutIntoSlicesSoTheGameStaysResponsive() {
        Fake fake = new Fake(0, true);
        fake.nanosPerTick = 4_000_000L;
        StretchRun run = new StretchRun(fake, new StretchRun.Stretch(0, 0, 400));
        frame(fake, run);
        frame(fake, run);
        int before = fake.ticks;
        frame(fake, run);
        int inOneFrame = fake.ticks - before;
        // A frame stops once its slice is spent: here eight ticks of four milliseconds, and the first is let through
        assertTrue(inOneFrame >= 1 && inOneFrame <= StretchRun.SLICE_NANOS / fake.nanosPerTick + 1, inOneFrame + " ticks in one frame");
    }

    @Test
    void cancelGoesBackAtOnce() {
        Fake fake = new Fake(700, false);
        StretchRun run = new StretchRun(fake, new StretchRun.Stretch(0, 100, 1900));
        for (int i = 0; i < 5; i++) {
            frame(fake, run);
        }
        double share = run.share();
        assertTrue(share > 0.0 && share < 1.0);
        run.cancel();
        assertEquals(StretchRun.Phase.RETURNING, run.phase());
        frames(fake, run, 10);
        assertEquals(StretchRun.Phase.DONE, run.phase());
        assertTrue(run.cancelled() && !run.finished());
        assertEquals(700, fake.position);
        assertFalse(fake.paused);
        // A second cancel, or one once it is over, changes nothing
        run.cancel();
        assertEquals(List.of(100, 700), fake.jumps);
    }

    @Test
    void cancelledBeforeItGotThereGoesBackToo() {
        Fake fake = new Fake(700, true);
        StretchRun run = new StretchRun(fake, new StretchRun.Stretch(0, 100, 1900));
        run.cancel();
        frames(fake, run, 10);
        assertEquals(0, fake.ticks);
        assertEquals(700, fake.position);
        assertTrue(fake.paused);
    }

    @Test
    void whoeverMovesTheRecordingMidRunKeepsIt() {
        Fake fake = new Fake(700, false);
        StretchRun run = new StretchRun(fake, new StretchRun.Stretch(0, 100, 1900));
        frame(fake, run);
        frame(fake, run);
        // Someone presses play
        fake.paused = false;
        frame(fake, run);
        assertEquals(StretchRun.Phase.DONE, run.phase());
        assertTrue(run.abandoned());
        assertEquals(List.of(100), fake.jumps, "no jump back over whoever took the recording");

        Fake jumped = new Fake(700, true);
        StretchRun other = new StretchRun(jumped, new StretchRun.Stretch(0, 100, 1900));
        frame(jumped, other);
        frame(jumped, other);
        // Someone jumps back
        jumped.position = 50;
        frame(jumped, other);
        assertTrue(other.abandoned());
        assertEquals(50, jumped.position);
    }

    @Test
    void aRecordingThatWillNotPlayOnIsGivenUpAndLeftAsItWas() {
        Fake fake = new Fake(10, true) {
            @Override
            public boolean tick() {
                return this.position < 150 && super.tick();
            }
        };
        StretchRun run = new StretchRun(fake, new StretchRun.Stretch(0, 100, 400));
        frames(fake, run, 1000);
        assertEquals(StretchRun.Phase.DONE, run.phase());
        assertFalse(run.finished() || run.cancelled() || run.abandoned());
        assertEquals(10, fake.position);
    }

}
