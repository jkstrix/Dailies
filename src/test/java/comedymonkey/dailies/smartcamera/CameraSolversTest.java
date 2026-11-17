package comedymonkey.dailies.smartcamera;

import comedymonkey.dailies.filming.FilmFrame;
import comedymonkey.dailies.filming.FilmMeasures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every Smart Camera solver filmed on made-up paths through made-up blocks, and judged by the same measures as the
 * scripted camera films: no pops, never inside a block, the subject never out of frame or half hidden for over two
 * seconds, bounded jerk, and for the director no shot under 1.5 seconds and no jump cut.
 */
class CameraSolversTest {

    private static final int GROUND = 64;
    /** The 99th percentile of the camera's linear jerk, blocks a second cubed, beyond which it would not look smooth */
    private static final double MOST_LINEAR_JERK = 30000.0;
    private static final double MOST_ANGULAR_JERK = 150000.0;

    private static final SmartCameraMode[] MODES = {SmartCameraMode.CHASE, SmartCameraMode.ORBIT, SmartCameraMode.TRIPOD, SmartCameraMode.DIRECTOR};

    // ------------------------------------------------------------------ The places and paths

    static VoxelWorld field() {
        return new VoxelWorld(GROUND);
    }

    private static IntFunction<SubjectSample> straightWalk() {
        return Filming.walk(new Vec(0.5, GROUND, 0.5), 0.0, 4.3);
    }

    private static IntFunction<SubjectSample> sharpTurns() {
        return Filming.route(5.6, new Vec(0.5, GROUND, 0.5), new Vec(0.5, GROUND, 40.5), new Vec(40.5, GROUND, 40.5), new Vec(40.5, GROUND, 0.5),
            new Vec(10.5, GROUND, 10.5));
    }

    /** A wall across the way: walked up to, stood at, then walked away from with the camera on the wall's side */
    private static VoxelWorld wallWorld() {
        return field().box(-12, GROUND, 20, 12, GROUND + 5, 21);
    }

    private static IntFunction<SubjectSample> intoWall() {
        IntFunction<SubjectSample> there = Filming.route(4.3, new Vec(0.5, GROUND, 0.5), new Vec(0.5, GROUND, 19.4));
        IntFunction<SubjectSample> back = Filming.route(4.3, new Vec(0.5, GROUND, 19.4), new Vec(0.5, GROUND, 0.5), new Vec(-8.5, GROUND, -6.5));
        int arrive = (int) Math.ceil(18.9 / (4.3 / 20.0));
        return tick -> {
            if (tick <= arrive) {
                return there.apply(tick);
            }
            if (tick <= arrive + 40) {
                // Stands at the wall and turns round
                double turn = Math.min(1.0, (tick - arrive) / 20.0) * 180.0;
                return there.apply(arrive).facing(turn);
            }
            return back.apply(tick - arrive - 40);
        };
    }

    /** A house with a door in its south wall */
    private static VoxelWorld houseWorld() {
        VoxelWorld world = field().box(5, GROUND, 20, 15, GROUND + 3, 30).empty(6, GROUND, 21, 14, GROUND + 2, 29);
        world.box(5, GROUND + 3, 20, 15, GROUND + 3, 30);
        return world.empty(10, GROUND, 20, 10, GROUND + 1, 20);
    }

    private static IntFunction<SubjectSample> throughDoorway() {
        return Filming.route(4.3, new Vec(10.5, GROUND, 4.5), new Vec(10.5, GROUND, 25.5), new Vec(7.5, GROUND, 27.5), new Vec(13.5, GROUND, 27.5),
            new Vec(10.5, GROUND, 24.5), new Vec(10.5, GROUND, 10.5));
    }

    /** A tunnel three blocks wide and high through rock, with a bend: a cave */
    static VoxelWorld caveWorld() {
        VoxelWorld world = field().box(-20, GROUND, -10, 45, GROUND + 8, 60);
        world.empty(-1, GROUND, -10, 1, GROUND + 2, 30);
        return world.empty(-1, GROUND, 28, 30, GROUND + 2, 30);
    }

    private static IntFunction<SubjectSample> throughCave() {
        return Filming.route(4.3, new Vec(0.5, GROUND, -9.5), new Vec(0.5, GROUND, 29.0), new Vec(29.0, GROUND, 29.0));
    }

    /** Gliding with an elytra: level, a long climb, then a dive */
    private static IntFunction<SubjectSample> flyingClimbAndDive() {
        IntFunction<SubjectSample> route = Filming.route(20.0, new Vec(0.5, 100, 0.5), new Vec(0.5, 100, 60.5), new Vec(0.5, 135, 120.5),
            new Vec(0.5, 85, 170.5), new Vec(40.5, 80, 230.5), new Vec(40.5, 80, 900.5));
        return tick -> route.apply(tick).withFlight(true, false, true);
    }

    /** Flying the creative way: slow, rising and sinking straight up and down too */
    private static IntFunction<SubjectSample> creativeFlight() {
        IntFunction<SubjectSample> route = Filming.route(10.9, new Vec(0.5, 80, 0.5), new Vec(0.5, 80, 40.5), new Vec(0.5, 95, 40.5), new Vec(30.5, 95, 70.5),
            new Vec(30.5, 82, 70.5), new Vec(30.5, 82, 140.5));
        return tick -> route.apply(tick).withFlight(false, true, true);
    }

    /** A fast vehicle, framed with its rider */
    private static IntFunction<SubjectSample> fastVehicle() {
        IntFunction<SubjectSample> route = Filming.route(25.0, new Vec(0.5, GROUND, 0.5), new Vec(0.5, GROUND, 150.5), new Vec(100.5, GROUND, 150.5),
            new Vec(100.5, GROUND, 400.5));
        return tick -> route.apply(tick).riding(1.6, "vehicle");
    }

    /** Trunks a block across, six high, scattered over the ground, with leaves on some: a forest */
    static VoxelWorld forestWorld() {
        VoxelWorld world = field();
        long state = 12345L;
        for (int i = 0; i < 160; i++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            int x = (int) ((state >>> 33) % 60) - 30;
            state = state * 6364136223846793005L + 1442695040888963407L;
            int z = (int) ((state >>> 33) % 90) - 10;
            if (Math.abs(x) <= 1 && z > -5) {
                // The way through stays open
                continue;
            }
            world.box(x, GROUND, z, x, GROUND + 5, z);
            if (i % 3 == 0) {
                world.box(x - 1, GROUND + 4, z - 1, x + 1, GROUND + 5, z + 1);
            }
        }
        return world;
    }

    /** Walking along a winding way through the trees */
    static IntFunction<SubjectSample> throughForest() {
        return Filming.route(4.3, new Vec(0.5, GROUND, -4.5), new Vec(0.5, GROUND, 20.5), new Vec(0.5, GROUND, 40.5), new Vec(0.5, GROUND, 75.5));
    }

    /** Walking, then through a portal: in an instant somewhere else, then walking on */
    static IntFunction<SubjectSample> teleport() {
        IntFunction<SubjectSample> before = Filming.walk(new Vec(0.5, GROUND, 0.5), 0.0, 4.3);
        IntFunction<SubjectSample> after = Filming.walk(new Vec(300.5, GROUND + 10, -200.5), 90.0, 4.3);
        return tick -> tick < 140 ? before.apply(tick) : after.apply(tick - 140);
    }

    /** Standing still, then getting on a boat beside them (a block over, sitting lower), and off again later */
    static IntFunction<SubjectSample> mounting() {
        return tick -> {
            if (tick < 100) {
                return SubjectSample.walking(new Vec(0.5, GROUND, 0.5), 0.0);
            }
            if (tick < 103) {
                double share = (tick - 99) / 3.0;
                return SubjectSample.walking(new Vec(0.5 + share, GROUND - 0.3 * share, 0.5), 0.0);
            }
            if (tick < 300) {
                // Rowing away
                Vec feet = new Vec(1.5, GROUND - 0.3, 0.5 + (tick - 103) * 0.2);
                return SubjectSample.walking(feet, 0.0).riding(1.4, "vehicle");
            }
            Vec stop = new Vec(1.5, GROUND - 0.3, 0.5 + 197 * 0.2);
            if (tick < 303) {
                double share = (tick - 299) / 3.0;
                return SubjectSample.walking(stop.add(share, 0.3 * share, 0.0), 0.0);
            }
            return SubjectSample.walking(stop.add(1.0, 0.3, 0.0), 0.0);
        };
    }

    /** Walking a few steps, stopping, and on again, in a cave: a tripod's dead zone is crossed again and again */
    static IntFunction<SubjectSample> stopAndGo() {
        return tick -> {
            int cycle = tick / 40;
            int within = tick % 40;
            double walked = cycle * 20 * 0.2 + Math.min(within, 20) * 0.2;
            return SubjectSample.walking(new Vec(0.5, GROUND, -9.5 + walked), 0.0);
        };
    }

    private record Case(String name, CameraWorld world, IntFunction<SubjectSample> path, int ticks) {
    }

    private static List<Case> cases() {
        return List.of(
            new Case("straight walk", field(), straightWalk(), 600),
            new Case("sharp turns", field(), sharpTurns(), 640),
            new Case("into a wall", wallWorld(), intoWall(), 400),
            new Case("through a doorway", houseWorld(), throughDoorway(), 480),
            new Case("through a cave", caveWorld(), throughCave(), 400),
            new Case("flying climb and dive", new VoxelWorld(-1000), flyingClimbAndDive(), 600),
            new Case("creative flight", new VoxelWorld(-1000), creativeFlight(), 600),
            new Case("fast vehicle", field(), fastVehicle(), 600),
            new Case("through a forest", forestWorld(), throughForest(), 600),
            new Case("through a portal", field(), teleport(), 400),
            new Case("getting on a boat", field(), mounting(), 400),
            new Case("stop and go in a cave", caveWorld(), stopAndGo(), 400));
    }

    // ------------------------------------------------------------------ The checks

    private static List<String> faults(SmartCameraMode mode, Case film, StringBuilder report) {
        List<FilmFrame> frames = Filming.film(Filming.settings(mode), film.world(), film.path(), film.ticks());
        FilmMeasures.Analysis analysis = Filming.judge(frames, mode == SmartCameraMode.DIRECTOR);
        report.append(String.format(Locale.ROOT, "%-9s %-22s %s%n", mode.name().toLowerCase(Locale.ROOT), film.name(), FilmMeasures.describe(analysis)));
        List<String> faults = new ArrayList<>(analysis.faults);
        if (analysis.linearJerk[1] > MOST_LINEAR_JERK) {
            faults.add(String.format(Locale.ROOT, "linear jerk %.0f b/s3 at the 99th percentile", analysis.linearJerk[1]));
        }
        if (analysis.angularJerk[1] > MOST_ANGULAR_JERK) {
            faults.add(String.format(Locale.ROOT, "angular jerk %.0f deg/s3 at the 99th percentile", analysis.angularJerk[1]));
        }
        if (analysis.longestLostSeconds > 1.5) {
            faults.add(String.format(Locale.ROOT, "the subject was lost for %.2f s at a stretch", analysis.longestLostSeconds));
        }
        if (analysis.inFrameShare < 0.9) {
            faults.add(String.format(Locale.ROOT, "the subject was in frame only %.1f%% of the time", analysis.inFrameShare * 100.0));
        }
        if (mode == SmartCameraMode.DIRECTOR && analysis.lineCrossings > 0) {
            faults.add(analysis.lineCrossings + " cuts crossed the line");
        }
        List<String> named = new ArrayList<>();
        for (String fault : faults) {
            named.add(mode.name().toLowerCase(Locale.ROOT) + " / " + film.name() + ": " + fault);
        }
        return named;
    }

    @Test
    void everySolverFilmsEveryPathWithoutFaults() {
        List<String> faults = new ArrayList<>();
        StringBuilder report = new StringBuilder();
        for (Case film : cases()) {
            for (SmartCameraMode mode : MODES) {
                faults.addAll(faults(mode, film, report));
            }
        }
        System.out.println(report);
        assertTrue(faults.isEmpty(), String.join("\n", faults));
    }

    @Test
    void theDirectorCutsAndMakesTheSameEditEveryTime() {
        Case film = cases().get(1);
        List<FilmFrame> first = Filming.film(Filming.settings(SmartCameraMode.DIRECTOR), film.world(), film.path(), film.ticks());
        List<FilmFrame> second = Filming.film(Filming.settings(SmartCameraMode.DIRECTOR), film.world(), film.path(), film.ticks());
        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).camX, second.get(i).camX, 0.0, "frame " + i);
            assertEquals(first.get(i).yaw, second.get(i).yaw, 0.0, "frame " + i);
            assertEquals(first.get(i).shot, second.get(i).shot, "frame " + i);
        }
        FilmMeasures.Analysis analysis = Filming.judge(first, true);
        assertTrue(analysis.cuts >= 3, "the director cut only " + analysis.cuts + " times in 32 seconds");
        assertTrue(analysis.shotKinds.size() >= 3, "the director used only " + analysis.shotKinds);
        assertTrue(analysis.longestShot <= 10.5, "a shot lasted " + analysis.longestShot + " s");
    }

    @Test
    void theDirectorOpensWideAndPicksAnotherSeedsEdit() {
        Case film = cases().get(0);
        SmartCameraSettings other = Filming.settings(SmartCameraMode.DIRECTOR);
        other.seed = 99;
        List<FilmFrame> seven = Filming.film(Filming.settings(SmartCameraMode.DIRECTOR), film.world(), film.path(), film.ticks());
        List<FilmFrame> ninetyNine = Filming.film(other, film.world(), film.path(), film.ticks());
        assertEquals("establishing", seven.get(0).shotKind);
        boolean differ = false;
        for (int i = 0; i < seven.size() && !differ; i++) {
            differ = seven.get(i).shot != ninetyNine.get(i).shot || !seven.get(i).shotKind.equals(ninetyNine.get(i).shotKind);
        }
        assertTrue(differ, "two seeds made the same edit");
    }

}
