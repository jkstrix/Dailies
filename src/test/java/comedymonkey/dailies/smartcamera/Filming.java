package comedymonkey.dailies.smartcamera;

import comedymonkey.dailies.filming.FilmFrame;
import comedymonkey.dailies.filming.FilmMeasures;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * Films a made-up subject with the Smart Camera the way the game does: the rig stepped once per tick, frames at 60 a
 * second blended from the last two steps, each frame noted as the scripted camera films note it, and the whole film
 * judged by the same {@link FilmMeasures} the scripted films are judged by.
 */
final class Filming {

    static final double FPS = 60.0;
    static final double ASPECT = 16.0 / 9.0;
    static final double FOV = 70.0;

    private Filming() {
    }

    /** One step per tick of the path, ticks 0 to {@code ticks} */
    static List<FilmFrame> film(SmartCameraSettings settings, CameraWorld world, IntFunction<SubjectSample> path, int ticks) {
        return film(settings, world, path, ticks, null, world);
    }

    /**
     * As the other, for someone aboard a craft framed with them: {@code rider} says where they are, {@code seeing} is
     * the world with the craft's blocks in, which is also what the frames are judged against
     */
    static List<FilmFrame> film(SmartCameraSettings settings, CameraWorld world, IntFunction<SubjectSample> path, int ticks,
                                java.util.function.Function<SubjectSample, Vec> rider, CameraWorld seeing) {
        SmartCameraRig rig = new SmartCameraRig();
        PoseBlend blend = new PoseBlend();
        List<FilmFrame> frames = new ArrayList<>();
        SubjectSample before = null;
        SmartCameraRig.Step previous = null;
        int frame = 0;
        for (int tick = 0; tick <= ticks; tick++) {
            SubjectSample subject = path.apply(tick);
            Vec hint = before == null ? Vec.ZERO : subject.feet().sub(before.feet()).scale(20.0);
            SmartCameraRig.Step step = rig.step(settings, 0, tick, subject, hint, world, FOV, ASPECT, rider == null ? null : rider.apply(subject), seeing);
            blend.push(new PoseBlend.Pose(tick, step.eye().x(), step.eye().y(), step.eye().z(), step.yaw(), step.pitch(), step.fov(), step.applyFov(), step.cut()));
            // The frames that fall after the last tick and up to this one
            while (tick > 0 && frame * 20.0 / FPS <= tick + 1.0e-9) {
                double time = frame * 20.0 / FPS;
                double amount = time - (tick - 1);
                PoseBlend.Pose pose = blend.at(time);
                FilmFrame noted = new FilmFrame();
                noted.frame = frame;
                noted.seconds = frame / FPS;
                noted.tick = time;
                noted.camX = pose.x();
                noted.camY = pose.y();
                noted.camZ = pose.z();
                noted.yaw = pose.yaw();
                noted.pitch = pose.pitch();
                noted.fov = pose.hasFov() ? pose.fov() : FOV;
                noted.aspect = ASPECT;
                noted.cameraInBlock = world.solid(new Vec(pose.x(), pose.y(), pose.z()));
                Vec feet = before.feet().lerp(subject.feet(), amount);
                CameraWorld judged = seeing;
                Vec aim = previous.aim().lerp(step.aim(), amount);
                noted.subjectPresent = true;
                noted.eyeX = feet.x();
                noted.eyeY = feet.y() + 1.62;
                noted.eyeZ = feet.z();
                noted.subjectYaw = subject.bodyYaw();
                noted.minX = feet.x() - 0.3;
                noted.minY = feet.y();
                noted.minZ = feet.z() - 0.3;
                noted.maxX = feet.x() + 0.3;
                noted.maxY = feet.y() + 1.8;
                noted.maxZ = feet.z() + 0.3;
                noted.aimX = aim.x();
                noted.aimY = aim.y();
                noted.aimZ = aim.z();
                Vec eye = new Vec(pose.x(), pose.y(), pose.z());
                double[][] points = noted.points();
                for (int i = 0; i < points.length; i++) {
                    Vec point = new Vec(points[i][0], points[i][1], points[i][2]);
                    double length = eye.distanceTo(point);
                    if (judged.clip(eye, point) * length < length - 0.05) {
                        noted.occludedMask |= 1 << i;
                    }
                }
                noted.shot = step.shotIndex();
                noted.shotKind = step.shotKind();
                noted.targetX = step.targetScreenX();
                noted.targetY = step.targetScreenY();
                frames.add(noted);
                frame++;
            }
            before = subject;
            previous = step;
        }
        return frames;
    }

    /** What a tether camera does with the subject each step, given its solver, the subject's point, the step and dt */
    interface Tethered {
        TetherSolver.Result step(TetherSolver solver, SubjectSample subject, double dt);
    }

    /** Films a follow entity, entity orbit or panel Follow camera the way the game steps it: once a tick, frames blended, each cut a new shot */
    static List<FilmFrame> filmTether(CameraWorld world, IntFunction<SubjectSample> path, int ticks, Tethered camera) {
        TetherSolver solver = new TetherSolver();
        PoseBlend blend = new PoseBlend();
        List<FilmFrame> frames = new ArrayList<>();
        SubjectSample before = null;
        int cuts = 0;
        int frame = 0;
        for (int tick = 0; tick <= ticks; tick++) {
            SubjectSample subject = path.apply(tick);
            TetherSolver.Result result = camera.step(solver, subject, tick == 0 ? -1.0 : 0.05);
            if (result.cut()) {
                cuts++;
            }
            Vec eye = result.eye();
            blend.push(new PoseBlend.Pose(tick, eye.x(), eye.y(), eye.z(), result.yaw(), result.pitch(), FOV, false, result.cut()));
            while (tick > 0 && frame * 20.0 / FPS <= tick + 1.0e-9) {
                double time = frame * 20.0 / FPS;
                double amount = time - (tick - 1);
                PoseBlend.Pose pose = blend.at(time);
                Vec feet = before.feet().lerp(subject.feet(), amount);
                FilmFrame noted = note(world, frame, time, pose, feet, subject.bodyYaw());
                noted.aimX = feet.x();
                noted.aimY = feet.y() + 1.62;
                noted.aimZ = feet.z();
                noted.shot = cuts;
                frames.add(noted);
                frame++;
            }
            before = subject;
        }
        return frames;
    }

    /**
     * Films the players panel's Follow from tick {@code start} to {@code end} exactly as an export does: each frame
     * brings the recording to the tick after the time it shows, the follow is stepped by the tick through its own steps
     * ({@link PanelFollow#stepTick}), and the frame is drawn from {@link PanelFollow#poseAt} at its time; each cut is a
     * new shot. The follow is left as the film leaves it, so a next film can begin where this one ended.
     *
     * @param left where the person left the camera, for a follow that has not placed it yet
     */
    static List<FilmFrame> filmPanel(CameraWorld world, IntFunction<SubjectSample> path, int start, int end, PanelFollow follow, Vec left) {
        List<FilmFrame> frames = new ArrayList<>();
        int count = (int) Math.round((end - start) / 20.0 * FPS);
        for (int frame = 0; frame < count; frame++) {
            double time = start + frame * 20.0 / FPS;
            int whole = (int) Math.floor(time + 1.0e-9);
            int position = whole + 1;
            if (follow.steppedTick() != position) {
                SubjectSample subject = path.apply(position);
                Vec eyes = subject.feet().add(0.0, 1.62, 0.0);
                follow.stepTick(position, world, eyes, follow.placed() == null ? left : null, subject.bodyYaw(), 1.62, 1.8, 0.6, FOV, ASPECT);
            }
            PoseBlend.Pose pose = follow.poseAt(time);
            Vec feet = path.apply(whole).feet().lerp(path.apply(position).feet(), time - whole);
            FilmFrame noted = note(world, frame, time, pose, feet, path.apply(position).bodyYaw());
            noted.aimX = feet.x();
            noted.aimY = feet.y() + 1.62;
            noted.aimZ = feet.z();
            noted.shot = follow.cuts();
            frames.add(noted);
        }
        return frames;
    }

    private static FilmFrame note(CameraWorld world, int frame, double time, PoseBlend.Pose pose, Vec feet, double bodyYaw) {
        FilmFrame noted = new FilmFrame();
        noted.frame = frame;
        noted.seconds = frame / FPS;
        noted.tick = time;
        noted.camX = pose.x();
        noted.camY = pose.y();
        noted.camZ = pose.z();
        noted.yaw = pose.yaw();
        noted.pitch = pose.pitch();
        noted.fov = FOV;
        noted.aspect = ASPECT;
        noted.cameraInBlock = world.solid(new Vec(pose.x(), pose.y(), pose.z()));
        noted.subjectPresent = true;
        noted.eyeX = feet.x();
        noted.eyeY = feet.y() + 1.62;
        noted.eyeZ = feet.z();
        noted.subjectYaw = bodyYaw;
        noted.minX = feet.x() - 0.3;
        noted.minY = feet.y();
        noted.minZ = feet.z() - 0.3;
        noted.maxX = feet.x() + 0.3;
        noted.maxY = feet.y() + 1.8;
        noted.maxZ = feet.z() + 0.3;
        Vec eye = new Vec(pose.x(), pose.y(), pose.z());
        double[][] points = noted.points();
        for (int i = 0; i < points.length; i++) {
            Vec point = new Vec(points[i][0], points[i][1], points[i][2]);
            double length = eye.distanceTo(point);
            if (world.clip(eye, point) * length < length - 0.05) {
                noted.occludedMask |= 1 << i;
            }
        }
        return noted;
    }

    static FilmMeasures.Analysis judge(List<FilmFrame> frames, boolean director) {
        return FilmMeasures.analyse(frames, FPS, director);
    }

    static SmartCameraSettings settings(SmartCameraMode mode) {
        SmartCameraSettings settings = new SmartCameraSettings(java.util.UUID.randomUUID());
        settings.mode = mode;
        settings.transitionSeconds = 0.0f;
        settings.seed = 7;
        return settings;
    }

    // ------------------------------------------------------------------ Made-up paths

    /** Walking at a steady pace along a heading, on the ground at height {@code y} */
    static IntFunction<SubjectSample> walk(Vec start, double yaw, double speed) {
        Vec step = Vec.heading(yaw).scale(speed / 20.0);
        return tick -> SubjectSample.walking(start.add(step.scale(tick)), yaw);
    }

    /** A path through points, at a steady speed, turning to face the way it goes; it stops at the last point */
    static IntFunction<SubjectSample> route(double speed, Vec... points) {
        List<Vec> at = new ArrayList<>();
        List<Double> yaws = new ArrayList<>();
        Vec here = points[0];
        double yaw = SmartCameraMath.yawOf(points[1].x() - points[0].x(), points[1].z() - points[0].z());
        at.add(here);
        yaws.add(yaw);
        for (int i = 1; i < points.length; i++) {
            Vec to = points[i];
            Vec along = to.sub(here);
            double length = along.length();
            if (along.horizontalLength() > 1.0e-6) {
                yaw = SmartCameraMath.yawOf(along.x(), along.z());
            }
            int steps = (int) Math.ceil(length / (speed / 20.0));
            for (int s = 1; s <= steps; s++) {
                at.add(here.add(along.scale(s / (double) steps)));
                yaws.add(yaw);
            }
            here = to;
        }
        return tick -> {
            int index = Math.min(tick, at.size() - 1);
            return SubjectSample.walking(at.get(index), yaws.get(index));
        };
    }

    static int length(IntFunction<SubjectSample> path, int most) {
        return most;
    }

}
