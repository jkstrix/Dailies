package comedymonkey.dailies.editor;

import com.google.common.collect.MapMaker;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.edit.Evaluator;
import comedymonkey.dailies.edit.Keyframe;
import comedymonkey.dailies.edit.KeyframeKind;
import comedymonkey.dailies.edit.Scene;
import comedymonkey.dailies.edit.Track;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.Viewer;
import comedymonkey.dailies.smartcamera.LevelCameraWorld;
import comedymonkey.dailies.smartcamera.PoseBlend;
import comedymonkey.dailies.smartcamera.TetherSolver;
import comedymonkey.dailies.smartcamera.Vec;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;

/**
 * Steps the cameras that follow something once per tick of the recording: the Smart Camera, the follow entity
 * keyframe and an orbit keyframe centred on an entity. Each step reads the world as the tick just played left it;
 * frames are drawn from a blend of the last two steps. So these cameras depend only on the recording and the ticks
 * played, and look the same live, in an export and on every replay of a stretch (EXP-9, CAM-7).
 */
public final class CameraSteps {

    /** The height of the viewer's eyes above its feet */
    private static final double EYE_HEIGHT = 1.62;
    /** Seconds the follow camera takes to settle after its entity, and its rotation after the aim */
    private static final double FOLLOW_TIME = 0.25;
    private static final double FOLLOW_AIM_TIME = 0.1;
    private static final double ORBIT_TIME = 0.4;
    private static final double ORBIT_AIM_TIME = 0.12;

    private static final class Tether {
        final TetherSolver solver = new TetherSolver();
        final PoseBlend blend = new PoseBlend();
        double lastTick = Double.NaN;
    }

    // By the track's id, which every copy of it keeps, so an edit (which copies the tracks) does not start the springs
    // again in the middle of a shot. Weak identity keys: the state goes away together with the last copy of the track
    private static final Map<Track.Id, Tether> TETHERS = new MapMaker().weakKeys().makeMap();
    private static Track.Id steppedTrack = null;
    private static int steppedPosition = Integer.MIN_VALUE;
    /** Whether that step found what it films; when not (a world being swapped through a portal) it is tried again */
    private static boolean steppedWithPose = false;
    /** Every cut a follow entity or entity orbit camera has made (a teleport, a move to a new watching place) */
    private static volatile int cuts = 0;

    private CameraSteps() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick());
    }

    static void forget() {
        TETHERS.clear();
        Viewer.panel().forgetSteps();
        panelInExport = false;
        steppedTrack = null;
        steppedPosition = Integer.MIN_VALUE;
    }

    /**
     * How many times the follow entity and entity orbit cameras have cut since the game started: when it changes, the
     * frame shows a new shot, and must not be judged as joined to the one before
     */
    public static int cuts() {
        return cuts;
    }

    /**
     * Before a frame is drawn: makes sure the cameras have been stepped for the tick the recording stands on. The tick
     * hook may have found nothing to film (the world swapped through a portal, its entities not yet in), and then the
     * frame would show the camera of a tick before while the subject is already somewhere else.
     */
    static void catchUp() {
        tick();
    }

    /** After every game tick: when the recording stands on a tick the cameras have not been stepped for, step them */
    private static void tick() {
        boolean exporting = comedymonkey.dailies.export.Export.holdsClock();
        if (exporting != panelInExport) {
            // Each export follows afresh: the steps of the last one (or of a follow begun before it) do not lead on
            // to this one's first, even when it begins on the very tick the last one ended
            panelInExport = exporting;
            Viewer.panel().forgetSteps();
        }
        if (!EditorSession.open() || !Playback.loaded() || Playback.jumping() || Playback.skipping()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        try {
            int position = Playback.position();
            Scene scene = EditorSession.scene();
            double tick = PointOfView.sceneTime(position);
            Track track = Evaluator.cameraDriver(scene, tick);
            if (track == null) {
                steppedTrack = null;
                if (Viewer.following() != null && exporting && Viewer.panel().steppedTick() != tick) {
                    panel(minecraft, tick);
                }
                return;
            }
            if (track.id() == steppedTrack && position == steppedPosition && steppedWithPose) {
                return;
            }
            steppedTrack = track.id();
            steppedPosition = position;
            boolean applied = EditorSession.followCamera() || EditorSession.exporting();
            steppedWithPose = switch (track.kind()) {
                case SMART_CAMERA -> SmartCameraDriver.step(minecraft, track, (Keyframe.SmartCamera) track.atOrBefore(tick), tick, applied);
                case FOLLOW_ENTITY -> follow(minecraft, scene, track, tick);
                case ORBIT -> orbit(minecraft, scene, track, tick);
                default -> true;
            };
        } catch (RuntimeException e) {
            Host.LOGGER.warn("Dailies: a following camera could not be worked out at tick {}", Playback.position(), e);
        }
    }

    // ------------------------------------------------------------------ The players panel's Follow, in an export

    /** Whether the last tick looked at was in an export, so the first tick of the next export is known */
    private static boolean panelInExport = false;

    private static void panel(Minecraft minecraft, double tick) {
        Entity entity = minecraft.level.getPlayerByUUID(Viewer.following());
        if (entity == null || entity == minecraft.player) {
            // Tried again on the next tick or frame, until the player is back in the world
            return;
        }
        Vec3 feet = entity.position();
        Vec eyes = new Vec(feet.x, feet.y + entity.getEyeHeight(), feet.z);
        double fov = Viewer.fov() > 0.0 ? Viewer.fov() : minecraft.options.fov().get();
        double aspect = minecraft.getWindow().getWidth() / (double) Math.max(1, minecraft.getWindow().getHeight());
        // Where the person left the camera, the first time; after that only the follow moves it
        Vec3 viewer = Viewer.position();
        Vec camera = Viewer.panel().placed() == null ? new Vec(viewer.x, viewer.y + EYE_HEIGHT, viewer.z) : null;
        Viewer.panel().stepTick(tick, new LevelCameraWorld(minecraft.level, null), eyes, camera, entity.getYRot(), entity.getEyeHeight(), entity.getBbHeight(),
            entity.getBbWidth(), fov, aspect);
    }

    /** Where the players panel's Follow puts the view for an export frame at this tick, or null when not stepped near it */
    public static @Nullable PoseBlend.Pose panelPose(double frameTick) {
        return Viewer.panel().poseAt(PointOfView.sceneTime(frameTick));
    }

    // ------------------------------------------------------------------ Follow entity

    private static boolean follow(Minecraft minecraft, Scene scene, Track track, double tick) {
        Tether tether = TETHERS.computeIfAbsent(track.id(), key -> new Tether());
        Evaluator.FollowValue follow = Evaluator.follow(scene, tick);
        Entity entity = follow == null ? null : find(minecraft, follow.entityUuid(), follow.entityId());
        if (entity == null) {
            tether.blend.clear();
            tether.solver.reset();
            tether.lastTick = Double.NaN;
            return false;
        }
        double dt = Double.isNaN(tether.lastTick) ? -1.0 : (tick - tether.lastTick) / 20.0;
        tether.lastTick = tick;
        Vec target = part(entity, follow.part());
        tether.solver.box(target.y() - entity.position().y, entity.getBbHeight(), entity.getBbWidth());
        Vec offset = new Vec(follow.offsetX(), follow.offsetY(), follow.offsetZ());
        double fov = Viewer.fov() > 0.0 ? Viewer.fov() : minecraft.options.fov().get();
        double aspect = minecraft.getWindow().getWidth() / (double) Math.max(1, minecraft.getWindow().getHeight());
        TetherSolver.Result result;
        if (follow.mode() == Keyframe.FollowMode.WATCH_FROM_FIXED) {
            // Stays where the viewer is and turns to watch the point the offset names, moving to a new place when it
            // has lost sight of the entity for good
            Vec3 feet = Viewer.position();
            Vec eye = new Vec(feet.x, feet.y + EYE_HEIGHT, feet.z);
            LevelCameraWorld world = new LevelCameraWorld(minecraft.level, null);
            result = tether.solver.watch(world, eye, target, target.add(offset), entity.getBbHeight(), FOLLOW_AIM_TIME * 2.5, fov, aspect, dt);
        } else {
            // The offset is from the body part to where the viewer stands
            LevelCameraWorld world = new LevelCameraWorld(minecraft.level, null);
            result = tether.solver.keep(world, target, offset.add(0.0, EYE_HEIGHT, 0.0), target, FOLLOW_TIME, FOLLOW_AIM_TIME, fov, aspect, dt, true);
        }
        push(tether, tick, result);
        return true;
    }

    // ------------------------------------------------------------------ Orbit round an entity

    private static boolean orbit(Minecraft minecraft, Scene scene, Track track, double tick) {
        Evaluator.OrbitValue orbit = Evaluator.orbit(scene, tick);
        Tether tether = TETHERS.computeIfAbsent(track.id(), key -> new Tether());
        Entity entity = orbit == null || !orbit.onEntity() ? null : find(minecraft, orbit.entityUuid(), orbit.entityId());
        if (entity == null) {
            // Round a fixed point the keyframes say exactly where the camera is, frame by frame
            tether.blend.clear();
            tether.solver.reset();
            tether.lastTick = Double.NaN;
            return orbit == null || !orbit.onEntity();
        }
        double dt = Double.isNaN(tether.lastTick) ? -1.0 : (tick - tether.lastTick) / 20.0;
        tether.lastTick = tick;
        Vec centre = part(entity, Keyframe.BodyPart.BODY).add(orbit.centreX(), orbit.centreY(), orbit.centreZ());
        tether.solver.box(centre.y() - entity.position().y, entity.getBbHeight(), entity.getBbWidth());
        double radians = Math.toRadians(orbit.angle());
        // As for an orbit round a point, the height is that of the viewer's feet above the centre
        Vec offset = new Vec(Math.sin(radians) * orbit.distance(), orbit.height() + EYE_HEIGHT, -Math.cos(radians) * orbit.distance());
        double fov = Viewer.fov() > 0.0 ? Viewer.fov() : minecraft.options.fov().get();
        double aspect = minecraft.getWindow().getWidth() / (double) Math.max(1, minecraft.getWindow().getHeight());
        LevelCameraWorld world = new LevelCameraWorld(minecraft.level, null);
        push(tether, tick, tether.solver.keep(world, centre, offset, centre, ORBIT_TIME, ORBIT_AIM_TIME, fov, aspect, dt, true));
        return true;
    }

    private static void push(Tether tether, double tick, TetherSolver.Result result) {
        if (result.cut()) {
            cuts++;
        }
        Vec eye = result.eye();
        tether.blend.push(new PoseBlend.Pose(tick, eye.x(), eye.y(), eye.z(), result.yaw(), result.pitch(), 0.0, false, result.cut()));
    }

    /**
     * Where a follow entity or entity orbit track puts the view for a frame drawn at this tick, or null when it has not
     * been stepped near it (then the caller works the pose out directly).
     */
    static @Nullable Evaluator.CameraPose pose(Track track, double tick) {
        Tether tether = TETHERS.get(track.id());
        PoseBlend.Pose pose = tether == null ? null : tether.blend.at(tick);
        return pose == null ? null : new Evaluator.CameraPose(pose.x(), pose.y() - EYE_HEIGHT, pose.z(), pose.yaw(), pose.pitch(), 0.0);
    }

    // ------------------------------------------------------------------ Entities

    /** An entity by its number in the recording, checked against its UUID, or by UUID alone */
    static @Nullable Entity find(Minecraft minecraft, @Nullable UUID uuid, int id) {
        if (minecraft.level == null) {
            return null;
        }
        Entity entity = id >= 0 ? minecraft.level.getEntity(id) : null;
        if (entity != null && (uuid == null || entity.getUUID().equals(uuid))) {
            return entity == minecraft.player ? null : entity;
        }
        if (uuid == null) {
            return null;
        }
        Entity byUuid = minecraft.level.getEntities().get(uuid);
        return byUuid == minecraft.player ? null : byUuid;
    }

    /** A body part of an entity as the tick left it */
    static Vec part(Entity entity, Keyframe.BodyPart part) {
        Vec3 feet = entity.position();
        double up = switch (part) {
            case FEET -> 0.0;
            case BODY -> entity.getBbHeight() * 0.5;
            case HEAD -> entity.getBbHeight() * 0.9;
            case EYES -> entity.getEyeHeight();
        };
        return new Vec(feet.x, feet.y + up, feet.z);
    }

}
