package comedymonkey.dailies.smartcamera;

import com.google.common.collect.MapMaker;
import comedymonkey.dailies.Host;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Smart Camera of one keyframe track, in the game: reads the subject from the level, steps the
 * {@link SmartCameraRig} once per tick of the recording with the world as that tick left it, and draws each frame
 * from a blend of the last two steps ({@link PoseBlend}). So the camera, and every choice the director makes, depends
 * only on the recording and the ticks played, never on when frames were drawn: playback at any speed and export agree.
 */
public final class SmartCameraSolver {

    // Weak identity keys: the state goes away together with the key it belongs to. The editor keys it by the track's
    // id, which every copy of the track keeps, so an edit that copies the track does not start the shots again
    private static final Map<Object, SmartCameraSolver> SOLVERS = new MapMaker().weakKeys().makeMap();

    public static SmartCameraSolver forTrack(Object stateKey) {
        return SOLVERS.computeIfAbsent(stateKey, key -> new SmartCameraSolver());
    }

    /**
     * @param kind        the kind of shot (chase, over_shoulder, side_tracking, tripod, orbit, establishing)
     * @param seconds     how long the shot ran, or has run so far for the current one
     * @param startReason why the director cut to this shot
     * @param endReason   why it cut away again, null for the current shot
     */
    public record ShotInfo(int index, String kind, float startTick, double seconds, String startReason, @Nullable String endReason) {}

    /**
     * The outcome of one step.
     *
     * @param mode       the mode of the keyframe
     * @param shotKind   what the camera is actually doing: the mode itself, or the director's current kind of shot
     * @param cameraY    the camera's eye height, not the feet of the viewer
     * @param applyFov   whether the smart camera controls the FOV (auto zoom or a fixed FOV)
     * @param screenX    where the subject is on screen, -1 (left) to 1 (right)
     * @param screenY    where the subject is on screen, -1 (bottom) to 1 (top)
     * @param visibility how much of the subject the camera sees, 0 to 1
     * @param activity   what the subject is doing: still, walking, running, flying, riding or swimming
     * @param transition progress of the blend after a settings change, 1 when there is none
     */
    public record Result(float tick, SmartCameraMode mode, String shotKind, int shotIndex, float shotStartTick, double shotAgeSeconds,
                         @Nullable String shotReason, double subjectX, double subjectY, double subjectZ, double subjectSize,
                         double cameraX, double cameraY, double cameraZ, double yaw, double pitch, double fov, boolean applyFov,
                         double screenX, double screenY, boolean subjectInFront, double targetScreenX, double targetScreenY,
                         boolean obstructed, double visibility, String framed, String activity, double transition, boolean applied) {}

    private final SmartCameraRig rig = new SmartCameraRig();
    private final PoseBlend blend = new PoseBlend();
    private boolean initialised = false;
    private double lastTick;
    private UUID lastTarget;
    private WeakReference<ClientLevel> lastLevel = new WeakReference<>(null);
    private WeakReference<Object> recentSubLevel = new WeakReference<>(null);
    private double secondsOffSubLevel;
    private Result lastResult;

    private SmartCameraSolver() {
    }

    /** The result of the most recent step, or null when the camera had nothing to follow */
    public @Nullable Result lastResult() {
        return this.lastResult;
    }

    /** The director's shots so far, oldest first. The last one is the current shot. Empty in the other modes */
    public List<ShotInfo> shotHistory() {
        List<ShotInfo> history = new ArrayList<>();
        for (DirectorSolver.ShotRecord shot : this.rig.shots()) {
            history.add(new ShotInfo(shot.index(), shot.kind().id(), (float) shot.startTick(), shot.seconds(), shot.startReason(), shot.endReason()));
        }
        return history;
    }

    /**
     * The camera for a frame drawn at this tick (with its fraction): a blend of the last two steps. Null when the
     * solver has not been stepped near this tick, or had nothing to film.
     */
    public @Nullable PoseBlend.Pose poseAt(double tick) {
        return this.blend.at(tick);
    }

    /**
     * One step, with the world as the tick just played left it.
     *
     * @param settings     the settings in effect
     * @param keyframeTick the tick of that keyframe
     * @param tick         the scene tick being stepped to
     * @param applied      whether the caller moves the real camera to the result
     * @return the camera for this tick, or null when the subject is not in the level
     */
    public @Nullable Result step(SmartCameraSettings settings, int keyframeTick, double tick, Minecraft minecraft, boolean applied) {
        ClientLevel level = minecraft.level;
        Entity entity = level == null || settings.target == null ? null : level.getEntities().get(settings.target);
        if (entity == null || entity == minecraft.player) {
            this.initialised = false;
            this.lastResult = null;
            this.blend.clear();
            this.rig.restart();
            return null;
        }
        if (!this.initialised || !settings.target.equals(this.lastTarget) || this.lastLevel.get() != level) {
            this.rig.restart();
            this.secondsOffSubLevel = 99.0;
        }
        double dt = this.initialised ? Math.max(0.0, (tick - this.lastTick) / 20.0) : 0.0;

        Object recent = this.initialised && this.secondsOffSubLevel < 1.5 ? this.recentSubLevel.get() : null;
        // The world as the tick left it: the frames between ticks are blends of the steps
        SmartCameraSubject subject = SmartCameraSubject.of(level, entity, 1.0F, settings.frameVehicle, recent);
        if (subject.subLevel() != null && !subject.subLevelRemembered()) {
            if (this.recentSubLevel.get() != subject.subLevel()) {
                this.recentSubLevel = new WeakReference<>(subject.subLevel());
            }
            this.secondsOffSubLevel = 0.0;
        } else {
            this.secondsOffSubLevel += dt;
        }
        // The craft that is being framed does not block the view of itself
        Object ownSubLevel = settings.frameVehicle ? subject.subLevel() : null;
        CameraWorld world = new LevelCameraWorld(level, ownSubLevel);

        // The rider is seen, or not, with the craft's own blocks in the way
        CameraWorld seeing = ownSubLevel != null ? new LevelCameraWorld(level, null) : world;
        SmartCameraRig.Step step = this.rig.step(settings, keyframeTick, tick, subject.sample(), subject.velocityHint(), world, currentFov(minecraft),
            aspectRatio(minecraft), subject.rider(), seeing);
        this.blend.push(new PoseBlend.Pose(tick, step.eye().x(), step.eye().y(), step.eye().z(), step.yaw(), step.pitch(), step.fov(), step.applyFov(), step.cut()));

        this.initialised = true;
        this.lastTick = tick;
        this.lastTarget = settings.target;
        if (this.lastLevel.get() != level) {
            this.lastLevel = new WeakReference<>(level);
        }
        Vec aim = step.aim();
        this.lastResult = new Result((float) tick, settings.mode, step.shotKind(), step.shotIndex(), (float) step.shotStartTick(), step.shotAgeSeconds(),
            step.shotReason(), aim.x(), aim.y(), aim.z(), step.subjectSize(), step.eye().x(), step.eye().y(), step.eye().z(),
            SmartCameraMath.wrapDegrees(step.yaw()), step.pitch(), step.fov(), step.applyFov(), step.screenX(), step.screenY(), step.inFront(),
            step.targetScreenX(), step.targetScreenY(), step.obstructed(), step.visibility(), subject.framed(), step.activity(), step.transition(), applied);
        return this.lastResult;
    }

    /** The FOV the game renders with while the smart camera leaves it alone */
    private static double currentFov(Minecraft minecraft) {
        // The scene may override the game's FOV setting
        Double sceneFov = Host.get().sceneFov();
        return sceneFov != null ? sceneFov : minecraft.options.fov().get();
    }

    private static double aspectRatio(Minecraft minecraft) {
        // An export renders at its own resolution, whatever the window's shape
        Double exportAspect = Host.get().exportAspectRatio();
        if (exportAspect != null) {
            return exportAspect;
        }
        // The same ratio the game's projection matrix is built with
        return minecraft.getWindow().getWidth() / (double) Math.max(1, minecraft.getWindow().getHeight());
    }

}
