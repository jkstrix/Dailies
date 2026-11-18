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

    private record Place(String name, CameraWorld world, IntFunction<SubjectSample> path, int ticks, Vec watchFrom) {
        Place(String name, CameraWorld world, IntFunction<SubjectSample> path, int ticks) {
            this(name, world, path, ticks, new Vec(4.0, 2.5, -6.0));
        }
    }

    /** Rock with a tunnel a block wide and two high, with bends, that rails run along */
    private static VoxelWorld minecartWorld() {
        VoxelWorld world = new VoxelWorld(GROUND).box(-30, GROUND, -10, 40, GROUND + 6, 80);
        world.empty(0, GROUND, -10, 0, GROUND + 1, 30);
        world.empty(0, GROUND, 30, 25, GROUND + 1, 30);
        return world.empty(25, GROUND, 30, 25, GROUND + 1, 70);
    }

    /** Riding a minecart along it, eyes low, at eight blocks a second */
    private static IntFunction<SubjectSample> minecart() {
        IntFunction<SubjectSample> route = Filming.route(8.0, new Vec(0.5, GROUND, -9.5), new Vec(0.5, GROUND, 30.5), new Vec(25.5, GROUND, 30.5),
            new Vec(25.5, GROUND, 69.5));
        return tick -> route.apply(tick).riding(0.9, "vehicle");
    }

    /** Walking across the ground to a shaft and dropping down it, straight past where a watching camera was put */
    private static IntFunction<SubjectSample> fall() {
        return tick -> {
            double walked = Math.min(11.0, tick * 0.2);
            if (walked < 11.0) {
                return SubjectSample.walking(new Vec(-10.5 + walked, 98.0, 0.5), -90.0);
            }
            double t = (tick - 55) / 20.0;
            double drop = Math.min(32.0, 0.5 * 25.0 * t * t);
            return SubjectSample.walking(new Vec(0.5, 98.0 - drop, 0.5), -90.0);
        };
    }

    private static List<Place> places() {
        return List.of(
            new Place("forest", CameraSolversTest.forestWorld(), CameraSolversTest.throughForest(), 600),
            new Place("portal", new VoxelWorld(GROUND), CameraSolversTest.teleport(), 400),
            new Place("boat", new VoxelWorld(GROUND), CameraSolversTest.mounting(), 400),
            new Place("cave", new VoxelWorld(GROUND).box(-20, GROUND, -10, 45, GROUND + 8, 60).empty(-1, GROUND, -10, 1, GROUND + 2, 30)
                .empty(-1, GROUND, 28, 30, GROUND + 2, 30), CameraSolversTest.stopAndGo(), 400),
            new Place("house", CameraSolversTest.houseWorld(), CameraSolversTest.throughDoorway(), 480),
            new Place("gallop", new VoxelWorld(GROUND), CameraSolversTest.gallop(), 300),
            new Place("shaft", new VoxelWorld(GROUND).box(-20, 64, -20, 20, 97, 20).empty(-1, 66, -1, 1, 97, 1).empty(-1, 66, -1, 20, 67, 1),
                fall(), 260, new Vec(11.1, 10.0, -0.1)),
            new Place("balloon", new CraftScene().seeing, null, 600),
            new Place("minecart", minecartWorld(), minecart(), 500));
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
        for (Place given : places()) {
            Place place = given;
            if (given.path() == null) {
                // The balloon: the tethered cameras see the craft's blocks, as the game's do
                CraftScene scene = new CraftScene();
                place = new Place(given.name(), scene.seeing, scene.path(), given.ticks());
            }
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
            // The players panel's Follow itself (what the viewer steps), begun from where a person left the camera:
            // on the surface beside a shaft, among the trees
            Vec left = place.path().apply(0).feet().add(4.0, 4.1, -6.0);
            PanelFollow panelFollow = new PanelFollow();
            faults.addAll(faults("follow", place, (solver, subject, dt) -> {
                Vec eyes = subject.feet().add(0.0, 1.62, 0.0);
                return panelFollow.step(world, eyes, panelFollow.placed() == null ? left : null, subject.bodyYaw(), 1.62, 1.8, 0.6, 70.0, 16.0 / 9.0, dt);
            }).stream().map(fault -> fault.replaceFirst("^follow", "panelFollow")).toList());
            // Staying to watch from where it was first put
            Vec start = place.path().apply(0).feet().add(place.watchFrom());
            faults.addAll(faults("watch", place, (solver, subject, dt) -> {
                Vec eyes = subject.feet().add(0.0, 1.62, 0.0);
                return solver.watch(world, start, eyes, eyes, 1.8, 0.25, 70.0, 16.0 / 9.0, dt);
            }));
        }
        assertTrue(faults.isEmpty(), String.join("\n", faults));
    }

}
