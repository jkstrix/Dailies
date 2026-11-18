package comedymonkey.dailies.smartcamera;

import comedymonkey.dailies.filming.FilmFrame;
import comedymonkey.dailies.filming.FilmMeasures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The players panel's Follow on the two faults the camera films found in it: a swing on the first frames of a film
 * that began on the tick the one before it ended (flying-creative after flying-elytra), and a jolt a few frames after
 * the camera turned to the player by a cut while it was moving fast (the forest).
 */
class PanelFollowTest {

    private static final int GROUND = 64;
    private static final double DT = 0.05;

    /** Flying the creative way in open sky, at twenty blocks a second, drifting up and to one side */
    private static IntFunction<SubjectSample> flight() {
        return tick -> SubjectSample.walking(new Vec(0.5 + tick * 0.15, 100.0 + tick * 0.02, 0.5 + tick * 1.0), -8.5).withFlight(false, true, true);
    }

    /** How far the view turned from one frame to the next, in degrees */
    private static double turnBetween(FilmFrame a, FilmFrame b) {
        double[] from = FilmMeasures.forward(a.yaw, a.pitch);
        double[] to = FilmMeasures.forward(b.yaw, b.pitch);
        double dot = from[0] * to[0] + from[1] * to[1] + from[2] * to[2];
        return Math.toDegrees(Math.acos(SmartCameraMath.clamp(dot, -1.0, 1.0)));
    }

    @Test
    void aFilmBegunOnTheTickTheLastEndedStartsFromItsOwnPlace() {
        CameraWorld sky = new VoxelWorld(-1000);
        IntFunction<SubjectSample> flight = flight();
        PanelFollow follow = new PanelFollow();
        Vec firstPlace = flight.apply(0).feet().add(-3.0, 3.6, -6.0);
        Filming.filmPanel(sky, flight, 0, 700, follow, firstPlace);

        // The next film: the follow begun again on the same player (as the players panel does), the camera put down
        // beside them, and stepped by the frame while the recording stands still at the tick the last film ended on
        follow.reset();
        Vec eyes = flight.apply(700).feet().add(0.0, 1.62, 0.0);
        Vec beside = eyes.add(6.0, 2.0, 0.0);
        for (int frame = 0; frame < 10; frame++) {
            follow.step(sky, eyes, follow.placed() == null ? beside : follow.placed(), -8.5, 1.62, 1.8, 0.6, Filming.FOV, Filming.ASPECT,
                frame == 0 ? -1.0 : 1.0 / 60.0);
        }
        List<FilmFrame> second = Filming.filmPanel(sky, flight, 700, 1400, follow, beside);

        double firstTurn = turnBetween(second.get(0), second.get(1));
        assertTrue(firstTurn < 2.0, String.format(Locale.ROOT, "the view swung %.1f degrees between the film's first two frames", firstTurn));
        assertTrue(FilmMeasures.screenError(second.get(0)) < 0.25, "the film's first frame did not look at the player: it was drawn from the film before");
        FilmMeasures.Analysis analysis = Filming.judge(second, false);
        assertEquals(0, analysis.pops, "the film popped: " + analysis.firstPop);
    }

    @Test
    void anExportStartsTheStepsByTheTickAfreshWithoutTheFollowBegunAgain() {
        CameraWorld sky = new VoxelWorld(-1000);
        IntFunction<SubjectSample> flight = flight();
        PanelFollow follow = new PanelFollow();
        Filming.filmPanel(sky, flight, 0, 300, follow, flight.apply(0).feet().add(-3.0, 3.6, -6.0));
        int cuts = follow.cuts();
        // What a new export does first: the last one's steps lead on to nothing, though the follow and its place are kept
        follow.forgetSteps();
        assertTrue(Double.isNaN(follow.steppedTick()));
        assertEquals(null, follow.poseAt(300.0), "a frame of the new export could still be drawn from the last one's steps");
        Vec eyes = flight.apply(301).feet().add(0.0, 1.62, 0.0);
        TetherSolver.Result first = follow.stepTick(301, sky, eyes, null, -8.5, 1.62, 1.8, 0.6, Filming.FOV, Filming.ASPECT);
        assertTrue(first.cut() && follow.cuts() == cuts + 1, "the new export's first step did not start a new shot");
        PoseBlend.Pose pose = follow.poseAt(300.0);
        assertEquals(first.eye().x(), pose.x(), 1.0e-9, "the new export's first frame was blended with the last export's camera");
        assertEquals(first.yaw(), pose.yaw(), 1.0e-9);
    }

    @Test
    void aCameraThatTurnsToThePlayerByACutSetsOffFromStanding() {
        // The camera's place flips to the far side of a player standing still, so it races over their head and has to
        // turn to them by a cut as it passes
        TetherSolver tether = new TetherSolver();
        Vec point = new Vec(0.5, GROUND + 1.62, 0.5);
        Vec previous = null;
        double before = 0.0;
        boolean turned = false;
        for (int step = 0; step < 120; step++) {
            Vec offset = step < 40 ? new Vec(0.0, 1.0, -7.0) : new Vec(0.0, 1.0, 7.0);
            TetherSolver.Result result = tether.keep(CameraWorld.OPEN, point, offset, point, 0.15, 0.12, 70.0, 16.0 / 9.0, step == 0 ? -1.0 : DT, true);
            double moved = previous == null ? 0.0 : result.eye().distanceTo(previous);
            if (turned) {
                // The step after the cut: from standing, as fast as a camera may set off, not at the speed it raced at
                assertTrue(moved < 0.05, String.format(Locale.ROOT, "after turning by a cut at %.2f blocks a step, the camera went on %.2f blocks in the next",
                    before, moved));
                return;
            }
            if (step > 40 && result.cut()) {
                assertTrue(before > 0.2, "the test meant the camera to be racing when it turned by a cut, but it moved only " + before);
                turned = true;
            }
            before = moved;
            previous = result.eye();
        }
        assertTrue(turned, "the camera never turned to the player by a cut");
    }

    @Test
    void aCameraThatTurnsByACutWhileRacingFilmsWithoutAPop() {
        // As above, filmed: the new shot after the cut must not stand still for a tick and then set off at full speed
        Vec point = new Vec(0.5, GROUND + 1.62, 0.5);
        IntFunction<SubjectSample> standing = tick -> SubjectSample.walking(new Vec(0.5, GROUND, 0.5), 0.0);
        int[] step = {0};
        List<FilmFrame> frames = Filming.filmTether(new VoxelWorld(GROUND), standing, 160, (solver, subject, dt) -> {
            Vec offset = step[0]++ < 40 ? new Vec(0.0, 1.0, -7.0) : new Vec(0.0, 1.0, 7.0);
            return solver.keep(CameraWorld.OPEN, point, offset, point, 0.15, 0.12, 70.0, 16.0 / 9.0, dt, true);
        });
        FilmMeasures.Analysis analysis = Filming.judge(frames, false);
        assertTrue(frames.get(frames.size() - 1).shot > 0, "the camera never turned by a cut");
        assertEquals(0, analysis.pops, "the film popped: " + analysis.firstPop);
    }

    // ------------------------------------------------------------------ A wood, walked as the camera route walks it

    /** Oaks a few blocks apart: trunks, and leaves from a little above head height up */
    static VoxelWorld wood(long seed) {
        VoxelWorld world = new VoxelWorld(GROUND);
        Random random = new Random(seed);
        for (int i = 0; i < 150; i++) {
            int x = random.nextInt(90) - 45;
            int z = random.nextInt(90) - 45;
            if (Math.abs(x) <= 1 && Math.abs(z) <= 1) {
                continue;
            }
            int height = 5 + random.nextInt(3);
            world.box(x - 2, GROUND + height - 3, z - 2, x + 2, GROUND + height - 2, z + 2);
            world.box(x - 1, GROUND + height - 1, z - 1, x + 1, GROUND + height, z + 1);
            world.box(x, GROUND, z, x, GROUND + height - 1, z);
        }
        return world;
    }

    private static boolean roomForPlayer(VoxelWorld world, double x, double y, double z) {
        for (double dx : new double[]{-0.3, 0.3}) {
            for (double dz : new double[]{-0.3, 0.3}) {
                for (double dy : new double[]{0.0, 1.0, 1.79}) {
                    if (world.solid(new Vec(x + dx, y + dy, z + dz))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * A walk through the wood as the camera route's forest walk goes: sprinting and jumping, then walking, drifting to
     * one side or the other now and then, turning back when it strays, and stopped by trunks until, having got nowhere
     * for a second and a half, it turns away and walks on
     */
    static List<SubjectSample> wander(VoxelWorld world, long seed, int ticks) {
        Random random = new Random(seed * 31 + 7);
        List<SubjectSample> path = new ArrayList<>();
        double x = 0.5;
        double y = GROUND;
        double z = 0.5;
        double rise = 0.0;
        double yaw = random.nextInt(360);
        double wanted = yaw;
        Vec lastAt = new Vec(x, y, z);
        int lastAtTick = 0;
        for (int tick = 0; tick <= ticks; tick++) {
            if (tick % 80 == 0 && tick > 0) {
                wanted += (random.nextBoolean() ? 1 : -1) * (30 + random.nextInt(40));
            }
            if (Math.hypot(x, z) > 30.0) {
                wanted = SmartCameraMath.yawOf(-x, -z);
            }
            if (tick - lastAtTick >= 30) {
                Vec at = new Vec(x, y, z);
                if (at.distanceTo(lastAt) < 0.8) {
                    wanted += 100 + random.nextInt(80);
                }
                lastAt = at;
                lastAtTick = tick;
            }
            yaw += SmartCameraMath.clamp(SmartCameraMath.wrapDegrees(wanted - yaw), -8.0, 8.0);
            boolean sprint = (tick / 180) % 2 == 0;
            Vec ahead = Vec.heading(yaw).scale((sprint ? 5.6 : 4.3) / 20.0);
            if (roomForPlayer(world, x + ahead.x(), y, z)) {
                x += ahead.x();
            }
            if (roomForPlayer(world, x, y, z + ahead.z())) {
                z += ahead.z();
            }
            boolean onGround = y <= GROUND + 1.0e-6;
            if (sprint && onGround && tick % 50 < 2) {
                rise = 0.42;
            }
            if (!onGround || rise > 0.0) {
                double next = y + rise;
                if (next < GROUND) {
                    next = GROUND;
                    rise = 0.0;
                } else if (!roomForPlayer(world, x, next, z)) {
                    next = y;
                    rise = 0.0;
                } else {
                    rise = (rise - 0.08) * 0.98;
                }
                y = next;
            }
            path.add(SubjectSample.walking(new Vec(x, y, z), yaw));
        }
        return path;
    }

    @Test
    void thePanelFollowFilmsMinutesInAWoodWithoutPops() {
        List<String> faults = new ArrayList<>();
        for (long seed = 1; seed <= 80; seed++) {
            VoxelWorld world = wood(seed);
            List<SubjectSample> path = wander(world, seed, 1200);
            Vec left = path.get(0).feet().add(4.0, 4.1, -6.0);
            PanelFollow follow = new PanelFollow();
            List<FilmFrame> frames = Filming.filmTether(world, path::get, 1200, (solver, subject, dt) -> {
                Vec eyes = subject.feet().add(0.0, 1.62, 0.0);
                return follow.step(world, eyes, follow.placed() == null ? left : null, subject.bodyYaw(), 1.62, 1.8, 0.6, Filming.FOV, Filming.ASPECT, dt);
            });
            FilmMeasures.Analysis analysis = Filming.judge(frames, false);
            if (analysis.pops > 0) {
                faults.add("wood " + seed + ": " + analysis.pops + " pops, the first " + analysis.firstPop);
            }
        }
        assertTrue(faults.isEmpty(), faults.size() + " of 80 films popped:\n" + String.join("\n", faults));
    }

}
