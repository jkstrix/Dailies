package comedymonkey.dailies.smartcamera;

import com.google.common.collect.MapMaker;
import comedymonkey.dailies.Host;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Works out where the smart camera is and where it looks, once per frame.
 *
 * One solver exists per keyframe track and holds that track's smoothing state. The state is advanced by replay time
 * (the tick the keyframes are applied for), never by the wall clock, so an export gives the same camera as playback.
 */
public final class SmartCameraSolver {

    // Weak identity keys: the state goes away together with the track it belongs to
    private static final Map<Object, SmartCameraSolver> SOLVERS = new MapMaker().weakKeys().makeMap();

    public static SmartCameraSolver forTrack(Object stateKey) {
        return SOLVERS.computeIfAbsent(stateKey, key -> new SmartCameraSolver());
    }

    public static final String REASON_START = "start";
    public static final String REASON_SEEK = "seek";
    public static final String REASON_SUBJECT_JUMPED = "subject_jumped";
    public static final String REASON_SETTINGS_CHANGED = "settings_changed";
    public static final String REASON_MAX_DURATION = "max_duration";
    public static final String REASON_OBSTRUCTED = "obstructed";
    public static final String REASON_TOO_FAR = "too_far";
    public static final String REASON_SITUATION_CHANGED = "situation_changed";

    private static final double MAX_STEP_SECONDS = 0.5;
    private static final double BACKWARDS_TOLERANCE_SECONDS = 0.1;
    private static final double OBSTRUCTION_MARGIN = 0.35;
    private static final double MIN_CAMERA_DISTANCE = 1.0;
    // Far beyond any sensible shot. Keeps a tripod left behind somewhere from costing a long walk through the world every frame
    private static final double MAX_RAY_LENGTH = 256.0;
    private static final int MAX_SHOT_HISTORY = 32;

    /**
     * @param seconds how long the shot ran, or has run so far for the current one
     * @param startReason why the director cut to this shot
     * @param endReason why it cut away again, null for the current shot
     */
    public record ShotInfo(int index, SmartCameraMode mode, float startTick, double seconds, String startReason, @Nullable String endReason) {}

    /**
     * The outcome of one frame.
     *
     * @param mode the mode of the keyframe
     * @param shotMode what the camera is actually doing: the mode itself, or the director's current shot
     * @param cameraY the camera's eye height, not the feet of the viewer
     * @param applyFov whether the smart camera controls the FOV this frame (auto zoom or a fixed FOV)
     * @param screenX where the subject is on screen, -1 (left) to 1 (right)
     * @param screenY where the subject is on screen, -1 (bottom) to 1 (top)
     * @param transition progress of the blend after a settings change, 1 when there is none
     */
    public record Result(float tick, SmartCameraMode mode, SmartCameraMode shotMode, int shotIndex, float shotStartTick, double shotAgeSeconds,
                         @Nullable String shotReason, double subjectX, double subjectY, double subjectZ, double subjectSize,
                         double cameraX, double cameraY, double cameraZ, double yaw, double pitch, double fov, boolean applyFov,
                         double screenX, double screenY, boolean subjectInFront, double targetScreenX, double targetScreenY,
                         boolean obstructed, String framed, double transition, boolean applied) {}

    private record Pose(double x, double y, double z, double yaw, double pitch, double fov, boolean hasFov) {}

    private record Situation(boolean riding, boolean flying, boolean moving) {}

    private static class Shot {
        SmartCameraMode mode;
        int index;
        float startTick;
        String startReason;
        Situation situation;
        // Chase
        double distance;
        double height;
        double sideAngle;
        double positionSmoothing;
        SmartCameraHeading headingSource;
        // Tripod
        double x;
        double y;
        double z;
        double aimSmoothing;
        double maxDistance = Double.MAX_VALUE;
        // Orbit
        double radius;
        double degreesPerSecond;
        double startAngle;
    }

    private boolean initialised = false;
    private float lastTick;
    private UUID lastTarget;
    private WeakReference<ClientLevel> lastLevel = new WeakReference<>(null);
    private SmartCameraSettings lastKeyframe;
    private int lastKeyframeTick;

    private Vec3 lastSubjectPosition = Vec3.ZERO;
    private double velocityX;
    private double velocityY;
    private double velocityZ;
    private boolean moving;
    private double airborneSeconds;
    private double bodyHeading;
    private double movementHeading;
    private double aimOffsetX;
    private double aimOffsetY;
    private double aimOffsetZ;
    private double subjectSize;
    private double extraDistance;
    private WeakReference<Object> recentSubLevel = new WeakReference<>(null);
    private double secondsOffSubLevel;

    private double cameraX;
    private double cameraY;
    private double cameraZ;
    private double cameraVelocityX;
    private double cameraVelocityY;
    private double cameraVelocityZ;
    private double obstructionScale = 1.0;
    private double obstructedSeconds;

    private double yaw;
    private double pitch;
    private double yawVelocity;
    private double pitchVelocity;
    private double leadX;
    private double leadY;
    private double fov;
    private boolean fovValid;

    private Pose lastPose;
    private Pose transitionFrom;
    private float transitionStartTick;

    private Shot directorShot;
    private double situationChangedSeconds;
    private final List<ShotInfo> shotHistory = new ArrayList<>();

    private Result lastResult;

    private SmartCameraSolver() {
    }

    /** The result of the most recent frame, or null when the camera had nothing to follow */
    public @Nullable Result lastResult() {
        return this.lastResult;
    }

    /** The director's shots so far, oldest first. The last one is the current shot. Empty in the other modes */
    public List<ShotInfo> shotHistory() {
        List<ShotInfo> history = new ArrayList<>(this.shotHistory);
        if (this.directorShot != null && this.lastResult != null) {
            history.add(new ShotInfo(this.directorShot.index, this.directorShot.mode, this.directorShot.startTick,
                this.lastResult.shotAgeSeconds(), this.directorShot.startReason, null));
        }
        return history;
    }

    /**
     * @param keyframe the settings in effect
     * @param keyframeTick the tick of that keyframe
     * @param tick the replay tick, with partial tick, to solve for
     * @param applied whether the caller moves the real camera to the result
     * @return the camera for this frame, or null when the target is not in the level
     */
    public @Nullable Result solve(SmartCameraSettings keyframe, int keyframeTick, float tick, Minecraft minecraft, boolean applied) {
        ClientLevel level = minecraft.level;
        Entity entity = level == null ? null : level.getEntities().get(keyframe.target);
        if (entity == null || entity == minecraft.player) {
            this.initialised = false;
            this.lastResult = null;
            return null;
        }

        float partialTick = minecraft.getTimer().getGameTimeDeltaPartialTick(true);
        Object recentSubLevel = this.initialised && this.secondsOffSubLevel < 1.5 ? this.recentSubLevel.get() : null;
        SmartCameraSubject subject = SmartCameraSubject.of(level, entity, partialTick, keyframe.frameVehicle, recentSubLevel);
        Vec3 position = subject.position();

        // --- Time ---

        double dt = (tick - this.lastTick) / 20.0;
        String resetReason = null;
        if (!this.initialised) {
            resetReason = REASON_START;
        } else if (dt < -BACKWARDS_TOLERANCE_SECONDS || dt > MAX_STEP_SECONDS || !keyframe.target.equals(this.lastTarget) || this.lastLevel.get() != level) {
            resetReason = REASON_SEEK;
        } else if (dt <= 0.0) {
            // Pausing and resuming moves the replay's clock back by a fraction of a tick. Hold still instead of starting over
            dt = 0.0;
        } else {
            // A teleport, not something a camera could follow
            double moved = position.distanceTo(this.lastSubjectPosition);
            if (moved > 3.0 && moved / dt > 100.0) {
                resetReason = REASON_SUBJECT_JUMPED;
            }
        }
        boolean reset = resetReason != null;
        if (reset) {
            dt = 0.0;
        }

        if (subject.subLevel() != null && !subject.subLevelRemembered()) {
            if (this.recentSubLevel.get() != subject.subLevel()) {
                this.recentSubLevel = new WeakReference<>(subject.subLevel());
            }
            this.secondsOffSubLevel = 0.0;
        } else {
            this.secondsOffSubLevel += dt;
        }

        boolean settingsChanged = !reset && (keyframe != this.lastKeyframe || keyframeTick != this.lastKeyframeTick);
        if (reset) {
            this.transitionFrom = null;
        } else if (settingsChanged) {
            if (keyframe.transitionSeconds > 0.0f && this.lastPose != null) {
                this.transitionFrom = this.lastPose;
                this.transitionStartTick = tick;
            } else {
                this.transitionFrom = null;
            }
        }
        boolean snap = reset || settingsChanged;

        // --- Subject ---

        if (reset) {
            Vec3 tickVelocity = subject.tickVelocity();
            this.velocityX = tickVelocity.x;
            this.velocityY = tickVelocity.y;
            this.velocityZ = tickVelocity.z;
            this.airborneSeconds = 0.0;
        } else if (dt > 0.0) {
            double blend = SmartCameraMath.blendFactor(0.25, dt);
            this.velocityX += ((position.x - this.lastSubjectPosition.x) / dt - this.velocityX) * blend;
            this.velocityY += ((position.y - this.lastSubjectPosition.y) / dt - this.velocityY) * blend;
            this.velocityZ += ((position.z - this.lastSubjectPosition.z) / dt - this.velocityZ) * blend;
            this.airborneSeconds = subject.airborne() ? this.airborneSeconds + dt : 0.0;
        }
        double horizontalSpeed = Math.sqrt(this.velocityX * this.velocityX + this.velocityZ * this.velocityZ);
        double speed = Math.sqrt(horizontalSpeed * horizontalSpeed + this.velocityY * this.velocityY);
        if (reset) {
            this.moving = speed > 0.8;
        } else {
            this.moving = this.moving ? speed > 0.4 : speed > 1.2;
        }

        // The direction of travel only means something above walking pace, below that the body decides
        double movementWeight = SmartCameraMath.clamp((horizontalSpeed - 0.5) / 1.0, 0.0, 1.0);
        double movementYaw = subject.bodyYaw();
        if (movementWeight > 0.0) {
            movementYaw = SmartCameraMath.lerpDegrees(subject.bodyYaw(), SmartCameraMath.yawOf(this.velocityX, this.velocityZ), movementWeight);
        }
        if (snap) {
            this.bodyHeading = subject.bodyYaw();
            this.movementHeading = movementYaw;
        } else {
            this.bodyHeading = SmartCameraMath.lerpDegrees(this.bodyHeading, subject.bodyYaw(), SmartCameraMath.blendFactor(0.15, dt));
            this.movementHeading = SmartCameraMath.lerpDegrees(this.movementHeading, movementYaw, SmartCameraMath.blendFactor(0.35, dt));
        }

        // Vehicles and physics objects update their bounds once per tick, and what is framed changes at once when
        // the player gets on or off something. So the offset from the (smooth) player to the middle of the bounds
        // is filtered instead of used directly, and so is the size
        Vec3 rawAim = subject.aimPoint();
        double rawSize = subject.size();
        // When more than the player is framed, camera distances are measured from the edge of it
        double rawExtraDistance = subject.framesMore() ? rawSize * 0.5 : 0.0;
        if (snap) {
            this.aimOffsetX = rawAim.x - position.x;
            this.aimOffsetY = rawAim.y - position.y;
            this.aimOffsetZ = rawAim.z - position.z;
            this.subjectSize = rawSize;
            this.extraDistance = rawExtraDistance;
        } else {
            double blend = SmartCameraMath.blendFactor(0.3, dt);
            this.aimOffsetX += (rawAim.x - position.x - this.aimOffsetX) * blend;
            this.aimOffsetY += (rawAim.y - position.y - this.aimOffsetY) * blend;
            this.aimOffsetZ += (rawAim.z - position.z - this.aimOffsetZ) * blend;
            this.subjectSize += (rawSize - this.subjectSize) * blend;
            this.extraDistance += (rawExtraDistance - this.extraDistance) * blend;
        }
        double aimX = position.x + this.aimOffsetX;
        double aimY = position.y + this.aimOffsetY;
        double aimZ = position.z + this.aimOffsetZ;
        double extraDistance = this.extraDistance;
        // The craft that is being framed does not block the view of itself
        Object ownSubLevel = keyframe.frameVehicle ? subject.subLevel() : null;

        // --- Shot ---

        Shot shot;
        if (keyframe.mode == SmartCameraMode.DIRECTOR) {
            Situation situation = new Situation(subject.riding(), subject.gliding() || this.airborneSeconds > 1.0, this.moving);
            String cutReason = null;
            if (this.directorShot == null || snap) {
                cutReason = reset ? resetReason : REASON_SETTINGS_CHANGED;
            } else {
                // A single step or a hop is not a new situation, it has to last
                this.situationChangedSeconds = situation.equals(this.directorShot.situation) ? 0.0 : this.situationChangedSeconds + dt;
                double age = (tick - this.directorShot.startTick) / 20.0;
                if (age >= keyframe.maxShotSeconds) {
                    cutReason = REASON_MAX_DURATION;
                } else if (age >= keyframe.minShotSeconds) {
                    if (keyframe.avoidObstructions && this.obstructedSeconds > 0.3) {
                        cutReason = REASON_OBSTRUCTED;
                    } else if (this.directorShot.mode == SmartCameraMode.TRIPOD
                            && distance(this.directorShot.x, this.directorShot.y, this.directorShot.z, aimX, aimY, aimZ) > this.directorShot.maxDistance) {
                        cutReason = REASON_TOO_FAR;
                    } else if (this.situationChangedSeconds > 0.5) {
                        cutReason = REASON_SITUATION_CHANGED;
                    }
                }
            }
            if (cutReason != null) {
                this.situationChangedSeconds = 0.0;
                this.startDirectorShot(keyframe, tick, cutReason, situation, level, ownSubLevel, aimX, aimY, aimZ, speed, extraDistance);
                // Cuts are instant
                snap = true;
                this.transitionFrom = settingsChanged ? this.transitionFrom : null;
            }
            shot = this.directorShot;
        } else {
            this.directorShot = null;
            this.shotHistory.clear();
            shot = shotFromKeyframe(keyframe, keyframeTick);
        }

        // --- Camera position ---

        double idealX;
        double idealY;
        double idealZ;
        switch (shot.mode) {
            case TRIPOD -> {
                idealX = shot.x;
                idealY = shot.y;
                idealZ = shot.z;
            }
            case ORBIT -> {
                double angle = Math.toRadians(shot.startAngle + shot.degreesPerSecond * (tick - shot.startTick) / 20.0);
                double radius = shot.radius + extraDistance;
                idealX = aimX + Math.sin(angle) * radius;
                idealY = aimY + shot.height;
                idealZ = aimZ - Math.cos(angle) * radius;
            }
            default -> {
                double heading = shot.headingSource == SmartCameraHeading.BODY ? this.bodyHeading : this.movementHeading;
                double angle = Math.toRadians(heading + shot.sideAngle);
                double chaseDistance = shot.distance + extraDistance;
                idealX = aimX + Math.sin(angle) * chaseDistance;
                idealY = aimY + shot.height;
                idealZ = aimZ - Math.cos(angle) * chaseDistance;
            }
        }

        if (shot.mode == SmartCameraMode.CHASE && !snap) {
            // A spring following a moving goal trails behind it by velocity * smoothing. Aiming that far ahead
            // cancels the lag, so the camera keeps its distance at speed and the spring only softens changes
            double smoothing = shot.positionSmoothing;
            SmartCameraMath.Spring x = SmartCameraMath.damp(this.cameraX, this.cameraVelocityX, idealX + this.velocityX * smoothing, smoothing, dt);
            SmartCameraMath.Spring y = SmartCameraMath.damp(this.cameraY, this.cameraVelocityY, idealY + this.velocityY * smoothing, smoothing, dt);
            SmartCameraMath.Spring z = SmartCameraMath.damp(this.cameraZ, this.cameraVelocityZ, idealZ + this.velocityZ * smoothing, smoothing, dt);
            this.cameraX = x.value();
            this.cameraY = y.value();
            this.cameraZ = z.value();
            this.cameraVelocityX = x.velocity();
            this.cameraVelocityY = y.velocity();
            this.cameraVelocityZ = z.velocity();
        } else {
            this.cameraX = idealX;
            this.cameraY = idealY;
            this.cameraZ = idealZ;
            this.cameraVelocityX = this.velocityX;
            this.cameraVelocityY = this.velocityY;
            this.cameraVelocityZ = this.velocityZ;
        }

        // --- Obstructions ---

        double cameraX = this.cameraX;
        double cameraY = this.cameraY;
        double cameraZ = this.cameraZ;
        boolean obstructed = false;
        if (keyframe.avoidObstructions) {
            double fullDistance = distance(aimX, aimY, aimZ, cameraX, cameraY, cameraZ);
            double allowedScale = 1.0;
            if (fullDistance > 1.0e-3) {
                double directionX = (cameraX - aimX) / fullDistance;
                double directionY = (cameraY - aimY) / fullDistance;
                double directionZ = (cameraZ - aimZ) / fullDistance;
                // Start outside whatever is framed, so a vehicle's own blocks don't count
                double startDistance = Math.min(extraDistance, fullDistance * 0.5);
                Vec3 from = new Vec3(aimX + directionX * startDistance, aimY + directionY * startDistance, aimZ + directionZ * startDistance);
                Vec3 hit = clip(level, ownSubLevel, from, new Vec3(cameraX, cameraY, cameraZ));
                if (hit != null) {
                    obstructed = true;
                    double hitDistance = startDistance + hit.distanceTo(from);
                    double allowed = Math.max(Math.min(MIN_CAMERA_DISTANCE + startDistance, fullDistance), hitDistance - OBSTRUCTION_MARGIN);
                    allowedScale = Math.min(1.0, allowed / fullDistance);
                }
            }

            if (shot.mode == SmartCameraMode.TRIPOD) {
                // A tripod stays where it is. The director cuts away from a blocked one
                this.obstructionScale = 1.0;
                this.obstructedSeconds = obstructed ? this.obstructedSeconds + dt : 0.0;
            } else {
                // Move in at once, ease back out
                if (snap || allowedScale < this.obstructionScale) {
                    this.obstructionScale = allowedScale;
                } else {
                    this.obstructionScale += (allowedScale - this.obstructionScale) * SmartCameraMath.blendFactor(0.4, dt);
                }
                cameraX = aimX + (cameraX - aimX) * this.obstructionScale;
                cameraY = aimY + (cameraY - aimY) * this.obstructionScale;
                cameraZ = aimZ + (cameraZ - aimZ) * this.obstructionScale;
                // A camera that had to come in a little is still a fine shot
                this.obstructedSeconds = obstructed && this.obstructionScale < 0.6 ? this.obstructedSeconds + dt : 0.0;
            }
        } else {
            this.obstructionScale = 1.0;
            this.obstructedSeconds = 0.0;
        }
        if (snap) {
            this.obstructedSeconds = 0.0;
        }

        // --- FOV ---

        double cameraDistance = distance(aimX, aimY, aimZ, cameraX, cameraY, cameraZ);
        boolean applyFov = true;
        double wantedFov;
        if (keyframe.autoZoom) {
            wantedFov = SmartCameraMath.autoZoomFov(this.subjectSize, cameraDistance, keyframe.subjectScreenHeight, keyframe.minFov, keyframe.maxFov);
        } else if (keyframe.fixedFov > 0.0f) {
            wantedFov = keyframe.fixedFov;
        } else {
            wantedFov = currentFov(minecraft);
            applyFov = false;
        }
        if (snap || !this.fovValid || !applyFov) {
            this.fov = wantedFov;
        } else {
            this.fov += (wantedFov - this.fov) * SmartCameraMath.blendFactor(0.35, dt);
        }
        this.fovValid = applyFov;

        // --- Composition ---

        double aspect = aspectRatio(minecraft);

        // Lead room: move the subject back in the frame, away from where it is heading, so there is space ahead of it
        double leadTargetX = 0.0;
        double leadTargetY = 0.0;
        if (keyframe.leadRoom > 0.0f && speed > 0.3) {
            SmartCameraMath.Aim unled = SmartCameraMath.aimAt(cameraX, cameraY, cameraZ, aimX, aimY, aimZ, keyframe.screenX, keyframe.screenY, this.fov, aspect);
            SmartCameraMath.ScreenPoint now = SmartCameraMath.project(cameraX, cameraY, cameraZ, unled.yaw(), unled.pitch(), aimX, aimY, aimZ, this.fov, aspect);
            double step = 0.1 / Math.max(speed, 1.0);
            SmartCameraMath.ScreenPoint soon = SmartCameraMath.project(cameraX, cameraY, cameraZ, unled.yaw(), unled.pitch(),
                aimX + this.velocityX * step, aimY + this.velocityY * step, aimZ + this.velocityZ * step, this.fov, aspect);
            double screenDirectionX = soon.x() - now.x();
            double screenDirectionY = soon.y() - now.y();
            double screenLength = Math.sqrt(screenDirectionX * screenDirectionX + screenDirectionY * screenDirectionY);
            if (now.inFront() && soon.inFront() && screenLength > 1.0e-5) {
                double strength = keyframe.leadRoom * 0.5 * SmartCameraMath.clamp(speed / 4.0, 0.0, 1.0);
                leadTargetX = -screenDirectionX / screenLength * strength;
                leadTargetY = -screenDirectionY / screenLength * strength;
            }
        }
        if (snap) {
            this.leadX = leadTargetX;
            this.leadY = leadTargetY;
        } else {
            double blend = SmartCameraMath.blendFactor(0.5, dt);
            this.leadX += (leadTargetX - this.leadX) * blend;
            this.leadY += (leadTargetY - this.leadY) * blend;
        }
        double targetScreenX = SmartCameraMath.clamp(keyframe.screenX + this.leadX, -0.95, 0.95);
        double targetScreenY = SmartCameraMath.clamp(keyframe.screenY + this.leadY, -0.95, 0.95);

        SmartCameraMath.Aim aim = SmartCameraMath.aimAt(cameraX, cameraY, cameraZ, aimX, aimY, aimZ, targetScreenX, targetScreenY, this.fov, aspect);
        if (shot.mode == SmartCameraMode.TRIPOD && !snap) {
            SmartCameraMath.Spring yawSpring = SmartCameraMath.dampAngle(this.yaw, this.yawVelocity, aim.yaw(), shot.aimSmoothing, dt);
            SmartCameraMath.Spring pitchSpring = SmartCameraMath.damp(this.pitch, this.pitchVelocity, aim.pitch(), shot.aimSmoothing, dt);
            this.yaw = yawSpring.value();
            this.yawVelocity = yawSpring.velocity();
            this.pitch = pitchSpring.value();
            this.pitchVelocity = pitchSpring.velocity();
        } else {
            // Keep the yaw continuous, a camera path that jumps from 179 to -179 is harder to work with
            this.yaw = snap ? aim.yaw() : this.yaw + SmartCameraMath.wrapDegrees(aim.yaw() - this.yaw);
            this.pitch = aim.pitch();
            this.yawVelocity = 0.0;
            this.pitchVelocity = 0.0;
        }

        // --- Transition after a settings change ---

        Pose pose = new Pose(cameraX, cameraY, cameraZ, SmartCameraMath.wrapDegrees(this.yaw), this.pitch, this.fov, applyFov);
        double transition = 1.0;
        if (this.transitionFrom != null) {
            double progress = keyframe.transitionSeconds <= 0.0f ? 1.0 : (tick - this.transitionStartTick) / 20.0 / keyframe.transitionSeconds;
            if (progress >= 1.0 || progress < 0.0) {
                this.transitionFrom = null;
            } else {
                Pose from = this.transitionFrom;
                double amount = SmartCameraMath.smoothstep(progress);
                double fromFov = from.hasFov() || pose.hasFov() ? from.fov() : pose.fov();
                pose = new Pose(
                    from.x() + (pose.x() - from.x()) * amount,
                    from.y() + (pose.y() - from.y()) * amount,
                    from.z() + (pose.z() - from.z()) * amount,
                    SmartCameraMath.wrapDegrees(SmartCameraMath.lerpDegrees(from.yaw(), pose.yaw(), amount)),
                    from.pitch() + (pose.pitch() - from.pitch()) * amount,
                    fromFov + (pose.fov() - fromFov) * amount,
                    pose.hasFov()
                );
                transition = progress;
            }
        }

        // --- Bookkeeping ---

        this.initialised = true;
        this.lastTick = tick;
        this.lastTarget = keyframe.target;
        if (this.lastLevel.get() != level) {
            this.lastLevel = new WeakReference<>(level);
        }
        this.lastKeyframe = keyframe;
        this.lastKeyframeTick = keyframeTick;
        this.lastSubjectPosition = position;
        this.lastPose = pose;

        SmartCameraMath.ScreenPoint screen = SmartCameraMath.project(pose.x(), pose.y(), pose.z(), pose.yaw(), pose.pitch(), aimX, aimY, aimZ, pose.fov(), aspect);
        boolean director = keyframe.mode == SmartCameraMode.DIRECTOR;
        this.lastResult = new Result(tick, keyframe.mode, shot.mode, director ? shot.index : 0, shot.startTick, (tick - shot.startTick) / 20.0,
            director ? shot.startReason : null, aimX, aimY, aimZ, this.subjectSize,
            pose.x(), pose.y(), pose.z(), pose.yaw(), pose.pitch(), pose.fov(), pose.hasFov(),
            screen.x(), screen.y(), screen.inFront(), targetScreenX, targetScreenY,
            obstructed, subject.framed(), transition, applied);
        return this.lastResult;
    }

    private static Shot shotFromKeyframe(SmartCameraSettings keyframe, int keyframeTick) {
        Shot shot = new Shot();
        shot.mode = keyframe.mode;
        shot.startTick = keyframeTick;
        shot.distance = keyframe.distance;
        shot.height = keyframe.height;
        shot.sideAngle = keyframe.sideAngle;
        shot.positionSmoothing = keyframe.positionSmoothing;
        shot.headingSource = keyframe.headingSource;
        shot.x = keyframe.position.x;
        shot.y = keyframe.position.y;
        shot.z = keyframe.position.z;
        shot.aimSmoothing = keyframe.aimSmoothing;
        shot.radius = keyframe.radius;
        shot.degreesPerSecond = keyframe.degreesPerSecond;
        shot.startAngle = keyframe.startAngle;
        return shot;
    }

    // --- Director ---

    private void startDirectorShot(SmartCameraSettings keyframe, float tick, String reason, Situation situation, ClientLevel level,
                                   @Nullable Object ownSubLevel, double aimX, double aimY, double aimZ, double speed, double extraDistance) {
        Shot previous = this.directorShot;
        boolean restart = previous == null || reason.equals(REASON_START) || reason.equals(REASON_SEEK) || reason.equals(REASON_SETTINGS_CHANGED);
        if (restart) {
            this.shotHistory.clear();
        } else {
            this.shotHistory.add(new ShotInfo(previous.index, previous.mode, previous.startTick, (tick - previous.startTick) / 20.0, previous.startReason, reason));
            while (this.shotHistory.size() > MAX_SHOT_HISTORY) {
                this.shotHistory.remove(0);
            }
        }

        // Seeded by where the shot starts, so playing the same stretch again gives the same shots
        SmartCameraMath.Random random = new SmartCameraMath.Random(keyframe.seed, (long) Math.floor(tick));

        boolean fast = speed > 6.0 || situation.riding() || situation.flying();
        boolean still = !situation.moving() && !fast;

        // Chase, tripod, orbit
        double[] weights;
        if (fast) {
            weights = new double[]{3.0, 3.0, 0.5};
        } else if (still) {
            weights = new double[]{2.0, 1.0, 3.0};
        } else {
            weights = new double[]{3.0, 2.0, 1.0};
        }
        if (!keyframe.allowChase) weights[0] = 0.0;
        if (!keyframe.allowTripod) weights[1] = 0.0;
        if (!keyframe.allowOrbit) weights[2] = 0.0;
        if (!restart) {
            // Prefer a different kind of shot than the last one
            int previousIndex = previous.mode == SmartCameraMode.CHASE ? 0 : previous.mode == SmartCameraMode.TRIPOD ? 1 : 2;
            weights[previousIndex] *= 0.25;
        }
        int picked = random.pickWeighted(weights);

        Shot shot = new Shot();
        shot.index = restart ? 0 : previous.index + 1;
        shot.startTick = tick;
        shot.startReason = reason;
        shot.situation = situation;
        shot.headingSource = SmartCameraHeading.MOVEMENT;
        shot.positionSmoothing = keyframe.positionSmoothing;
        shot.aimSmoothing = keyframe.aimSmoothing;

        if (picked == 1) {
            shot.mode = SmartCameraMode.TRIPOD;
            this.placeTripod(shot, random, level, ownSubLevel, aimX, aimY, aimZ, speed, extraDistance);
        } else if (picked == 2) {
            shot.mode = SmartCameraMode.ORBIT;
            shot.radius = keyframe.radius * (still ? random.range(0.6, 1.0) : random.range(1.0, 1.4));
            shot.height = keyframe.height * random.range(0.5, 1.25);
            shot.degreesPerSecond = Math.abs(keyframe.degreesPerSecond) * (random.nextBoolean() ? 1.0 : -1.0);
            shot.startAngle = this.movementHeading + random.range(-180.0, 180.0);
        } else {
            shot.mode = SmartCameraMode.CHASE;
            if (fast) {
                // Wider, and from a side that changes from shot to shot
                shot.distance = keyframe.distance * random.range(1.3, 1.9);
                shot.sideAngle = random.range(-70.0, 70.0);
            } else if (still) {
                shot.distance = keyframe.distance * random.range(0.55, 0.85);
                shot.sideAngle = random.range(-160.0, 160.0);
            } else {
                shot.distance = keyframe.distance * random.range(0.9, 1.2);
                shot.sideAngle = random.range(-45.0, 45.0);
            }
            shot.height = keyframe.height * random.range(0.5, 1.5);
        }

        this.directorShot = shot;
    }

    /**
     * A fixed camera for the director. For a moving subject it is a fly-by: ahead on the subject's path and off to
     * one side, so the subject comes towards the camera and passes it.
     */
    private void placeTripod(Shot shot, SmartCameraMath.Random random, ClientLevel level, @Nullable Object ownSubLevel,
                             double aimX, double aimY, double aimZ, double speed, double extraDistance) {
        Vec3 aim = new Vec3(aimX, aimY, aimZ);
        double horizontalSpeed = Math.sqrt(this.velocityX * this.velocityX + this.velocityZ * this.velocityZ);
        boolean flyBy = speed > 1.0 && horizontalSpeed > 0.5;

        // A few tries to find a spot with a clear view. If there is none, the one with the best view has to do,
        // and the director will cut away from it soon
        Vec3 best = null;
        double leastBlocked = Double.MAX_VALUE;
        for (int attempt = 0; attempt < 6; attempt++) {
            double x;
            double z;
            if (flyBy && attempt < 3) {
                double forwardX = this.velocityX / horizontalSpeed;
                double forwardZ = this.velocityZ / horizontalSpeed;
                double ahead = SmartCameraMath.clamp(horizontalSpeed * random.range(1.0, 2.0), 5.0, 48.0) + extraDistance;
                double side = (random.range(3.0, 7.0) + extraDistance) * (random.nextBoolean() ? 1.0 : -1.0);
                x = aimX + forwardX * ahead + forwardZ * side;
                z = aimZ + forwardZ * ahead - forwardX * side;
            } else {
                double angle = Math.toRadians(random.range(-180.0, 180.0));
                double away = random.range(4.0, 8.0) + extraDistance;
                x = aimX + Math.sin(angle) * away;
                z = aimZ - Math.cos(angle) * away;
            }

            // A little above the ground when there is ground nearby, otherwise around the subject's own height
            double y;
            Vec3 ground = clip(level, ownSubLevel, new Vec3(x, aimY + 1.0, z), new Vec3(x, aimY - 16.0, z));
            if (ground != null) {
                y = ground.y + random.range(1.2, 2.6) + attempt * 0.5;
            } else {
                y = aimY + random.range(-1.0, 2.0);
            }
            Vec3 candidate = new Vec3(x, y, z);

            // Looking back from the camera: how far from the subject does the view end? A camera inside a block
            // sees nothing at all. Blocks right at the subject are what it sits in or leans on, those are fine
            Vec3 hit = clip(level, ownSubLevel, candidate, aim);
            double blocked = hit == null ? 0.0 : hit.distanceTo(aim);
            if (blocked < leastBlocked) {
                leastBlocked = blocked;
                best = candidate;
            }
            if (blocked <= 1.5 + extraDistance) {
                break;
            }
        }

        shot.x = best.x;
        shot.y = best.y;
        shot.z = best.z;
        shot.maxDistance = Math.max(40.0, best.distanceTo(aim) * 2.0 + 16.0) + extraDistance;
    }

    // --- Helpers ---

    private static double distance(double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * The first solid block between two points, ignoring fluids, or null when the way is clear.
     *
     * @param ownSubLevel the physics object that is being framed, or null. Its blocks are not in the way of its own camera
     */
    private static @Nullable Vec3 clip(ClientLevel level, @Nullable Object ownSubLevel, Vec3 from, Vec3 to) {
        double length = from.distanceTo(to);
        if (length < 1.0e-4) {
            return null;
        }
        if (length > MAX_RAY_LENGTH) {
            to = from.add(to.subtract(from).scale(MAX_RAY_LENGTH / length));
        }

        ClipContext context = new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty());
        BlockHitResult hit = level.clip(context);
        if (hit.getType() == HitResult.Type.MISS) {
            return null;
        }

        // With Sable installed this also finds the blocks of physics objects, and reports those hits where the
        // blocks are stored: in the plot region, millions of blocks away
        Vec3 location = hit.getLocation();
        if (!Host.crafts().isInPlot(level, location.x, location.z)) {
            return location;
        }
        if (ownSubLevel == null || Host.crafts().getSubLevelAt(level, location) != ownSubLevel) {
            return Host.crafts().projectOutOfSubLevel(level, location);
        }

        // The ray ended at the framed object itself. Look again at the terrain only, the way vanilla's clip does
        BlockHitResult terrain = BlockGetter.traverseBlocks(from, to, context, (clipContext, blockPos) -> {
            BlockState blockState = level.getBlockState(blockPos);
            VoxelShape shape = clipContext.getBlockShape(blockState, level, blockPos);
            return level.clipWithInteractionOverride(clipContext.getFrom(), clipContext.getTo(), blockPos, shape, blockState);
        }, clipContext -> null);
        if (terrain == null || Host.crafts().isInPlot(level, terrain.getLocation().x, terrain.getLocation().z)) {
            return null;
        }
        return terrain.getLocation();
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
