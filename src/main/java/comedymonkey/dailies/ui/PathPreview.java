package comedymonkey.dailies.ui;

import comedymonkey.dailies.edit.EditHistory;
import comedymonkey.dailies.edit.Evaluator;
import comedymonkey.dailies.edit.Interpolation;
import comedymonkey.dailies.edit.Keyframe;
import comedymonkey.dailies.edit.KeyframeKind;
import comedymonkey.dailies.edit.KeyframeRef;
import comedymonkey.dailies.edit.Scene;
import comedymonkey.dailies.edit.Track;
import comedymonkey.dailies.editor.EditorSession;
import comedymonkey.dailies.editor.SmartCameraDriver;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * The camera's path drawn through the world while editing (specification EDIT-8): a line along where the scene's
 * camera and orbit tracks take the camera, a diamond at each keyframe (brighter when selected; a click selects it),
 * and a small arrow now and then for where the camera looks. The line is worked out by {@link Evaluator}, the same
 * code that moves the camera, sampled several times a tick, so the curve drawn is the curve flown.
 * <p>
 * A Smart Camera stretch has no path until it has been played, since it films what the recording does. What it did
 * the last time it was played is drawn in a paler line, and broken into dashes once its keyframe has been changed
 * since. Only while the editor is on screen, never in an export, and with a switch of its own (the "showCameraPath"
 * setting, in the editor's Viewer panel).
 */
public final class PathPreview {

    /** The height of the viewer's eyes above its feet: keyframes hold where the viewer stands, the lens is here */
    static final double EYE = 1.62;
    private static final double STEP = 0.25;
    private static final int MOST_SAMPLES = 4000;
    private static final int PATH = 0xF0C860;
    private static final int SMART = 0xE8F0FF;
    private static final int ARROW = 0xEFE6D6;
    private static final int MARK = 0xD4B25A;
    private static final int MARK_SELECTED = 0xFFFFFF;
    private static final float MARK_PIXELS = 5.0F;
    private static final double ARROW_LENGTH = 0.7;

    /**
     * A sampled moment of the path.
     *
     * @param x where the viewer stands; the lens is {@link #EYE} above
     */
    public record Point(double tick, double x, double y, double z, double yaw, double pitch) {
        Vec3 lens() {
            return new Vec3(this.x, this.y + EYE, this.z);
        }
    }

    /** An unbroken run of the path. A Smart Camera's is as it was last played, and stale when its keyframe has changed since */
    public record Stretch(List<Point> points, boolean smartCamera, boolean stale) {
    }

    /** The mark of one keyframe */
    public record Mark(int track, int tick, KeyframeKind kind, double x, double y, double z) {
        Vec3 lens() {
            return new Vec3(this.x, this.y + EYE, this.z);
        }
    }

    private static List<Stretch> stretches = List.of();
    private static List<Mark> marks = List.of();
    private static long builtRevision = -1;
    private static int builtScene = -1;
    private static long builtRemembered = -1;
    private static long builtAt = 0;
    private static @Nullable EditHistory builtFor = null;
    private static volatile boolean drawnLast = false;

    private PathPreview() {
    }

    static boolean shown() {
        return EditorState.setting("showCameraPath", true);
    }

    static void nothingDrawn() {
        drawnLast = false;
    }

    /** Whether the path was drawn in the last frame */
    public static boolean drawn() {
        return drawnLast;
    }

    // ------------------------------------------------------------------ Working the path out

    /** Brings the path up to date with the scene. Client thread */
    private static void refresh() {
        if (!EditorSession.open()) {
            stretches = List.of();
            marks = List.of();
            builtFor = null;
            return;
        }
        EditHistory history = EditorSession.history();
        int scene = history.state().currentSceneIndex();
        long remembered = SmartCameraDriver.rememberedCount();
        long now = System.currentTimeMillis();
        boolean edited = history != builtFor || history.revision() != builtRevision || scene != builtScene;
        // While a Smart Camera plays it adds to its path every frame; a few times a second is often enough to draw it
        boolean smartMoved = remembered != builtRemembered && now - builtAt > 150;
        if (!edited && !smartMoved) {
            return;
        }
        builtFor = history;
        builtRevision = history.revision();
        builtScene = scene;
        builtRemembered = remembered;
        builtAt = now;
        build(history.scene(), scene);
    }

    private static void build(Scene scene, int sceneIndex) {
        List<Stretch> found = new ArrayList<>();
        List<Mark> keyframes = new ArrayList<>();
        TreeSet<Double> moments = new TreeSet<>();
        int first = Integer.MAX_VALUE;
        int last = Integer.MIN_VALUE;
        for (int index = 0; index < scene.tracks().size(); index++) {
            Track track = scene.track(index);
            if (!track.enabled() || track.isEmpty()) {
                continue;
            }
            if (track.kind() == KeyframeKind.CAMERA || track.kind() == KeyframeKind.ORBIT) {
                first = Math.min(first, track.firstTick());
                last = Math.max(last, track.lastTick());
                for (Keyframe keyframe : track.keyframes()) {
                    // Every keyframe is a sample, so its mark sits on the line and a sharp corner is not rounded off
                    moments.add((double) keyframe.tick());
                    if (keyframe instanceof Keyframe.Camera camera) {
                        keyframes.add(new Mark(index, camera.tick(), KeyframeKind.CAMERA, camera.x(), camera.y(), camera.z()));
                    } else if (keyframe instanceof Keyframe.Orbit orbit) {
                        Evaluator.CameraPose pose = new Evaluator.OrbitValue(orbit.centreX(), orbit.centreY(), orbit.centreZ(), orbit.distance(), orbit.height(), orbit.angle()).pose();
                        keyframes.add(new Mark(index, orbit.tick(), KeyframeKind.ORBIT, pose.x(), pose.y(), pose.z()));
                    }
                }
            } else if (track.kind() == KeyframeKind.SMART_CAMERA) {
                for (Keyframe keyframe : track.keyframes()) {
                    SmartCameraDriver.Recorded recorded = SmartCameraDriver.recorded(sceneIndex, index, (Keyframe.SmartCamera) keyframe);
                    if (recorded == null) {
                        continue;
                    }
                    List<Point> points = new ArrayList<>(recorded.poses().size());
                    for (int i = 0; i < recorded.poses().size(); i++) {
                        Evaluator.CameraPose pose = recorded.poses().get(i);
                        points.add(new Point(recorded.ticks().get(i), pose.x(), pose.y(), pose.z(), pose.yaw(), pose.pitch()));
                    }
                    found.add(new Stretch(thin(points), true, recorded.stale()));
                    Point start = points.get(0);
                    keyframes.add(new Mark(index, keyframe.tick(), KeyframeKind.SMART_CAMERA, start.x(), start.y(), start.z()));
                }
            }
        }
        if (first <= last) {
            double step = Math.max(STEP, (double) (last - first) / MOST_SAMPLES);
            for (double tick = first; tick < last; tick += step) {
                moments.add(tick);
            }
            moments.add((double) last);
            List<Point> run = new ArrayList<>();
            Track runTrack = null;
            Keyframe runKeyframe = null;
            for (double tick : moments) {
                Track driver = Evaluator.cameraDriver(scene, tick);
                Evaluator.CameraPose pose = null;
                if (driver != null && driver.kind() == KeyframeKind.CAMERA) {
                    pose = Evaluator.camera(scene, tick);
                } else if (driver != null && driver.kind() == KeyframeKind.ORBIT) {
                    Evaluator.OrbitValue orbit = Evaluator.orbit(scene, tick);
                    pose = orbit == null ? null : orbit.pose();
                }
                Keyframe keyframe = pose == null ? null : driver.atOrBefore(tick);
                // A new run where another track takes over, where no track that has a path drives, and after a keyframe
                // that holds: the camera does not travel from a held keyframe to the next, it is simply there
                boolean jumped = runKeyframe != null && keyframe != runKeyframe && runKeyframe.interpolation() == Interpolation.HOLD;
                if (pose == null || driver != runTrack || jumped) {
                    if (run.size() > 1) {
                        found.add(new Stretch(List.copyOf(run), false, false));
                    }
                    run = new ArrayList<>();
                }
                runTrack = pose == null ? null : driver;
                runKeyframe = keyframe;
                if (pose != null) {
                    run.add(new Point(tick, pose.x(), pose.y(), pose.z(), pose.yaw(), pose.pitch()));
                }
            }
            if (run.size() > 1) {
                found.add(new Stretch(List.copyOf(run), false, false));
            }
        }
        stretches = List.copyOf(found);
        marks = List.copyOf(keyframes);
    }

    /** A Smart Camera's path is kept by the quarter tick; that many points are not all needed to draw it */
    private static List<Point> thin(List<Point> points) {
        if (points.size() <= MOST_SAMPLES) {
            return List.copyOf(points);
        }
        List<Point> fewer = new ArrayList<>(MOST_SAMPLES + 1);
        double step = (double) (points.size() - 1) / MOST_SAMPLES;
        for (int i = 0; i <= MOST_SAMPLES; i++) {
            fewer.add(points.get((int) Math.round(i * step)));
        }
        return fewer;
    }

    // ------------------------------------------------------------------ Drawing

    static void draw(WorldLines.Batch batch) {
        if (!(Minecraft.getInstance().screen instanceof EditorScreen) || !shown()) {
            drawnLast = false;
            return;
        }
        refresh();
        for (Stretch stretch : stretches) {
            List<Vec3> line = new ArrayList<>(stretch.points().size());
            for (Point point : stretch.points()) {
                line.add(point.lens());
            }
            if (stretch.smartCamera()) {
                batch.line(stretch.stale() ? dashes(line) : line, 2.5F, SMART, stretch.stale() ? 0.75F : 0.9F, stretch.stale());
            } else {
                batch.line(line, 2.5F, PATH, 1.0F, false);
            }
            arrows(batch, stretch);
        }
        for (Mark mark : marks) {
            boolean selected = EditorState.selection.contains(new KeyframeRef(mark.track(), mark.tick()));
            batch.diamond(mark.lens(), selected ? MARK_PIXELS + 2.0F : MARK_PIXELS, selected ? MARK_SELECTED : MARK, 1.0F);
        }
        drawnLast = true;
    }

    /** Every other stretch of a dashed line is left out, so the points are spread until each dash is long enough to see */
    private static List<Vec3> dashes(List<Vec3> line) {
        List<Vec3> spread = new ArrayList<>();
        Vec3 last = null;
        for (Vec3 point : line) {
            if (last == null || point.distanceTo(last) >= 0.6) {
                spread.add(point);
                last = point;
            }
        }
        return spread;
    }

    /** An arrow for where the camera looks, about once a second of recording and never so many that they hide the path */
    private static void arrows(WorldLines.Batch batch, Stretch stretch) {
        List<Point> points = stretch.points();
        if (points.size() < 2) {
            return;
        }
        double span = points.get(points.size() - 1).tick() - points.get(0).tick();
        double every = Math.max(20.0, span / 60.0);
        double next = points.get(0).tick();
        float alpha = stretch.smartCamera() ? 0.7F : 0.95F;
        for (Point point : points) {
            if (point.tick() < next) {
                continue;
            }
            next = point.tick() + every;
            Vec3 from = point.lens();
            Vec3 look = Vec3.directionFromRotation((float) point.pitch(), (float) point.yaw());
            Vec3 tip = from.add(look.scale(ARROW_LENGTH));
            Vec3 across = look.cross(new Vec3(0.0, 1.0, 0.0));
            across = across.lengthSqr() < 1.0e-6 ? new Vec3(1.0, 0.0, 0.0) : across.normalize();
            Vec3 back = tip.subtract(look.scale(ARROW_LENGTH * 0.3));
            batch.segment(from, tip, 1.5F, ARROW, alpha);
            batch.segment(tip, back.add(across.scale(ARROW_LENGTH * 0.14)), 1.5F, ARROW, alpha);
            batch.segment(tip, back.subtract(across.scale(ARROW_LENGTH * 0.14)), 1.5F, ARROW, alpha);
        }
    }

    // ------------------------------------------------------------------ Asking

    /** The runs of the path as they stand now, worked out afresh if the scene has changed. Client thread */
    public static List<Stretch> stretches() {
        refresh();
        return stretches;
    }

    /** One mark for each keyframe of the scene's enabled camera and orbit tracks, and of each Smart Camera stretch that has been played. Client thread */
    public static List<Mark> marks() {
        refresh();
        return marks;
    }

    /** The marks whose keyframes are selected in the editor. Client thread */
    public static List<Mark> selectedMarks() {
        refresh();
        List<Mark> selected = new ArrayList<>();
        for (Mark mark : marks) {
            if (EditorState.selection.contains(new KeyframeRef(mark.track(), mark.tick()))) {
                selected.add(mark);
            }
        }
        return selected;
    }

    /** Where a mark is on the screen that is open, in that screen's own measure: {x, y}, or null when it is behind the camera */
    public static double @Nullable [] onScreen(Mark mark) {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.screen == null ? null : WorldLines.onScreen(mark.lens(), minecraft.screen.width, minecraft.screen.height);
    }

    /** The keyframe mark under a line of sight, or null */
    static @Nullable Mark at(@Nullable ViewPick.Ray ray) {
        if (ray == null || !shown()) {
            return null;
        }
        Mark nearest = null;
        double best = Double.MAX_VALUE;
        double perPixel = WorldLines.blocksPerPixel() * WorldLines.pixelScale();
        for (Mark mark : marks) {
            Vec3 lens = mark.lens();
            double along = lens.subtract(ray.from()).dot(ray.direction());
            if (along <= 0.05) {
                continue;
            }
            // A little more than the diamond itself, so it is not fiddly to hit
            double reach = (MARK_PIXELS + 4.0) * perPixel * along;
            if (ray.at(along).distanceTo(lens) <= reach && along < best) {
                best = along;
                nearest = mark;
            }
        }
        return nearest;
    }

    static Component describe(Mark mark) {
        return Kit.t("path.mark.tip", Component.translatable("dailies.editor.kind." + mark.kind().name().toLowerCase(java.util.Locale.ROOT)), Controls.time(mark.tick()));
    }

}
