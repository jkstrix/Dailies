package comedymonkey.dailies.smartcamera;

import comedymonkey.dailies.filming.FilmFrame;
import comedymonkey.dailies.filming.FilmMeasures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The follow entity keyframe (keeping its place, and staying to watch), the orbit keyframe round an entity and the
 * players panel's Follow, filmed on the hard paths and judged by the film measures' hard faults.
 */
class TetherFilmsTest {

    private static final int GROUND = 64;

    private record Place(String name, CameraWorld world, IntFunction<SubjectSample> path, int ticks) {
    }

    private static List<Place> places() {
        return List.of(
            new Place("forest", CameraSolversTest.forestWorld(), CameraSolversTest.throughForest(), 600),
            new Place("portal", new VoxelWorld(GROUND), CameraSolversTest.teleport(), 400),
            new Place("boat", new VoxelWorld(GROUND), CameraSolversTest.mounting(), 400),
            new Place("cave", new VoxelWorld(GROUND).box(-20, GROUND, -10, 45, GROUND + 8, 60).empty(-1, GROUND, -10, 1, GROUND + 2, 30)
                .empty(-1, GROUND, 28, 30, GROUND + 2, 30), CameraSolversTest.stopAndGo(), 400));
    }

    private static List<String> faults(String camera, Place place, Filming.Tethered tethered) {
        List<FilmFrame> frames = Filming.filmTether(place.world(), place.path(), place.ticks(), tethered);
        FilmMeasures.Analysis analysis = FilmMeasures.analyse(frames, Filming.FPS, false);
        System.out.printf(Locale.ROOT, "%-8s %-7s %s%n", camera, place.name(), FilmMeasures.describe(analysis));
        List<String> faults = new ArrayList<>();
        for (String fault : analysis.faults) {
            faults.add(camera + " / " + place.name() + ": " + fault);
        }
        return faults;
    }

    @Test
    void theTetheredCamerasFilmTheHardPathsWithoutFaults() {
        List<String> faults = new ArrayList<>();
        for (Place place : places()) {
            CameraWorld world = place.world();
            // The follow entity keyframe keeping its place behind and above the eyes
            faults.addAll(faults("follow", place, (solver, subject, dt) -> {
                Vec eyes = subject.feet().add(0.0, 1.62, 0.0);
                return solver.keep(world, eyes, new Vec(0.0, 3.6, -5.0), eyes, 0.25, 0.1, 70.0, 16.0 / 9.0, dt, true);
            }));
            // The players panel's Follow, as the viewer steps it
            faults.addAll(faults("panel", place, (solver, subject, dt) -> {
                Vec eyes = subject.feet().add(0.0, 1.62, 0.0);
                return solver.keep(world, eyes, new Vec(-3.0, 2.0, -4.0), eyes, 0.15, 0.12, 70.0, 16.0 / 9.0, dt, true);
            }));
            // An orbit keyframe round the subject's middle
            faults.addAll(faults("orbit", place, (solver, subject, dt) -> {
                Vec middle = subject.feet().add(0.0, 0.9, 0.0);
                return solver.keep(world, middle, new Vec(6.0, 3.6, 0.0), middle, 0.4, 0.12, 70.0, 16.0 / 9.0, dt, true);
            }));
            // Staying to watch from where it was first put
            Vec start = place.path().apply(0).feet().add(4.0, 2.5, -6.0);
            faults.addAll(faults("watch", place, (solver, subject, dt) -> {
                Vec eyes = subject.feet().add(0.0, 1.62, 0.0);
                return solver.watch(world, start, eyes, eyes, 1.8, 0.25, 70.0, 16.0 / 9.0, dt);
            }));
        }
        assertTrue(faults.isEmpty(), String.join("\n", faults));
    }

}
