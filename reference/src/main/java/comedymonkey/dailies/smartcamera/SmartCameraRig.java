package comedymonkey.dailies.smartcamera;

import java.util.List;

/**
 * The whole Smart Camera for one keyframe track, free of Minecraft: it keeps the subject's {@link Motion}, runs the
 * solver of the keyframe's mode, blends over when a keyframe takes effect, and works out the field of view. Stepped
 * once per tick of the recording by {@link SmartCameraSolver}, or by the tests with made-up subjects and worlds.
 */
public final class SmartCameraRig {

    public static final String REASON_START = "start";
    public static final String REASON_SEEK = "seek";
    public static final String REASON_SUBJECT_JUMPED = "subject_jumped";
    public static final String REASON_SETTINGS_CHANGED = "settings_changed";

    private static final double MAX_STEP_SECONDS = 0.5;
    private static final double BACKWARDS_TOLERANCE_SECONDS = 0.1;

    /**
     * One step's outcome.
     *
     * @param shotKind      what the camera is doing: the mode, or the director's current kind of shot
     * @param eye           the camera's eye
     * @param screenX       where the subject is on screen, -1 (left) to 1 (right)
     * @param visibility    how much of the subject the camera sees, 0 to 1
     * @param transition    progress of the blend after a keyframe took effect, 1 when there is none
     * @param cut           whether the camera jumped this step, so frames are not blended across it
     */
    public record Step(double tick, SmartCameraMode mode, String shotKind, int shotIndex, double shotStartTick, double shotAgeSeconds, String shotReason,
                       Vec aim, double subjectSize, Vec eye, double yaw, double pitch, double fov, boolean applyFov, double screenX, double screenY,
                       boolean inFront, double targetScreenX, double targetScreenY, boolean obstructed, double visibility, double transition, boolean cut,
                       String activity) {
    }

    private final Motion motion = new Motion();
    private ShotSolver solver;
    private DirectorSolver director;
    private boolean initialised = false;
    private double lastTick;
    private SmartCameraSettings lastSettings;
    private int lastKeyframeTick;
    private SmartCameraMode lastMode;
    private Vec lastFeet = Vec.ZERO;
    private double fov;
    private boolean fovValid = false;
    private Step lastStep;
    private Step transitionFrom;
    private double transitionStartTick;
    private String startReason = REASON_START;
    private double startTick;
    /** Outside the director: how many times the solver itself cut (a tripod moving to a new place) */
    private int ownCuts = 0;
    /** Every cut since the rig was made (starts, jumps, the director's cuts, a tripod moving): the shot number the films split on */
    private int cuts = -1;
    /** What is framed, smoothed: getting on or off something changes it at once, which the camera must not copy */
    private final Spring.Point aimOffset = new Spring.Point();
    private final Spring size = new Spring();
    private final Spring extra = new Spring();
    private final Spring height = new Spring();
    private static final double FRAMING_TIME = 0.4;

    /**
     * The subject with what is framed eased over: its aim point (as an offset from the feet), size, height and the
     * distance to its edge follow springs, so mounting a boat or a horse, or stepping off one, moves the camera over
     * in a moment instead of at once. The feet are as they are.
     */
    private SubjectSample smoothed(SubjectSample raw, boolean reset, double dt) {
        Vec offset = raw.aim().sub(raw.feet());
        if (reset || !this.initialised) {
            this.aimOffset.reset(offset);
            this.size.reset(raw.size());
            this.extra.reset(raw.extra());
            this.height.reset(raw.height());
        } else {
            this.aimOffset.step(offset, FRAMING_TIME, FRAMING_TIME, dt);
            this.size.step(raw.size(), FRAMING_TIME, dt);
            this.extra.step(raw.extra(), FRAMING_TIME, dt);
            this.height.step(raw.height(), FRAMING_TIME, dt);
        }
        return new SubjectSample(raw.feet(), raw.feet().add(this.aimOffset.value()), this.height.value, this.size.value, Math.max(0.0, this.extra.value),
            raw.bodyYaw(), raw.riding(), raw.gliding(), raw.flying(), raw.airborne(), raw.swimming(), raw.framed());
    }

    /** Forgets everything: the next step starts afresh (after a jump, a change of subject or of world) */
    public void restart() {
        this.initialised = false;
    }

    public Motion motion() {
        return this.motion;
    }

    public Step lastStep() {
        return this.lastStep;
    }

    /** The director's edit so far, empty in the other modes */
    public List<DirectorSolver.ShotRecord> shots() {
        return this.director == null ? List.of() : this.director.history();
    }

    /**
     * @param settings      the settings in effect; a different object means the keyframe changed
     * @param keyframeTick  the tick of that keyframe
     * @param tick          the recording tick being stepped to
     * @param velocityHint  the subject's velocity over the last tick, for a first guess when starting afresh
     * @param baseFov       the field of view the game draws with when the Smart Camera leaves it alone
     * @param aspect        the frame's width over its height
     */
    public Step step(SmartCameraSettings settings, int keyframeTick, double tick, SubjectSample subject, Vec velocityHint, CameraWorld world, double baseFov,
                     double aspect) {
        double dt = (tick - this.lastTick) / 20.0;
        String resetReason = null;
        if (!this.initialised) {
            resetReason = REASON_START;
        } else if (dt < -BACKWARDS_TOLERANCE_SECONDS || dt > MAX_STEP_SECONDS) {
            resetReason = REASON_SEEK;
        } else if (dt <= 0.0) {
            dt = 0.0;
        } else {
            double moved = subject.feet().distanceTo(this.lastFeet);
            if (moved > 3.0 && moved / dt > 100.0) {
                // A teleport, not something a camera could follow
                resetReason = REASON_SUBJECT_JUMPED;
            }
        }
        boolean reset = resetReason != null;
        boolean changed = !reset && (settings != this.lastSettings || keyframeTick != this.lastKeyframeTick || settings.mode != this.lastMode);
        Vec rawFeet = subject.feet();
        subject = this.smoothed(subject, reset, dt);
        if (reset) {
            dt = 0.0;
            this.motion.reset(subject, velocityHint, world);
            this.transitionFrom = null;
        } else {
            this.motion.step(subject, world, settings.headingDeadBand, dt);
        }
        if (changed) {
            this.transitionFrom = settings.transitionSeconds > 0.0f && this.lastStep != null ? this.lastStep : null;
            this.transitionStartTick = tick;
        }

        double wantedFixed = settings.fixedFov > 0.0f ? settings.fixedFov : baseFov;
        double fovNow = this.fovValid && !reset ? this.fov : wantedFixed;
        double elapsed = Math.max(0.0, (tick - keyframeTick) / 20.0);
        ShotSolver.Input input = new ShotSolver.Input(subject, this.motion, world, settings, dt, fovNow, aspect, tick, elapsed);

        ShotSolver.Pose pose;
        if (reset || changed || this.solver == null) {
            this.director = settings.mode == SmartCameraMode.DIRECTOR ? new DirectorSolver() : null;
            if (this.director != null && REASON_SUBJECT_JUMPED.equals(resetReason) && this.lastStep != null) {
                // The cut a jump forces is still a cut: the new shot looks from well away from the last one
                Step last = this.lastStep;
                this.director.after(new ShotSolver.Pose(last.eye(), last.yaw(), last.pitch(), false, 0.0, 0.0, false));
            }
            this.solver = switch (settings.mode) {
                case ORBIT -> new OrbitSolver();
                case TRIPOD -> new TripodSolver();
                case DIRECTOR -> this.director;
                default -> new ChaseSolver();
            };
            pose = this.solver.start(input);
            this.startReason = reset ? resetReason : REASON_SETTINGS_CHANGED;
            this.startTick = tick;
            this.ownCuts = 0;
        } else {
            pose = this.solver.step(input);
            if (pose.cut() && this.director == null) {
                this.ownCuts++;
                this.startTick = tick;
                this.startReason = "moved";
            }
        }
        boolean cut = reset || pose.cut() && !(changed && this.transitionFrom != null);
        if (cut) {
            this.cuts++;
        }
        if (pose.cut() && !changed) {
            // A cut ends any blend still going on
            this.transitionFrom = null;
        }

        // The field of view
        Vec aim = subject.aim();
        double distance = pose.eye().distanceTo(aim);
        boolean applyFov = true;
        double wantedFov;
        if (settings.autoZoom) {
            wantedFov = SmartCameraMath.autoZoomFov(subject.size(), distance, settings.subjectScreenHeight, settings.minFov, settings.maxFov);
        } else if (settings.fixedFov > 0.0f) {
            wantedFov = settings.fixedFov;
        } else {
            wantedFov = baseFov;
            applyFov = false;
        }
        if (cut || changed || !this.fovValid || !applyFov) {
            this.fov = wantedFov;
        } else {
            this.fov += (wantedFov - this.fov) * SmartCameraMath.blendFactor(0.35, dt);
        }
        this.fovValid = applyFov;

        Vec eye = pose.eye();
        double yaw = pose.yaw();
        double pitch = pose.pitch();
        double fov = this.fov;
        double transition = 1.0;
        if (this.transitionFrom != null) {
            double progress = settings.transitionSeconds <= 0.0f ? 1.0 : (tick - this.transitionStartTick) / 20.0 / settings.transitionSeconds;
            if (progress >= 1.0 || progress < 0.0) {
                this.transitionFrom = null;
            } else {
                Step from = this.transitionFrom;
                double amount = SmartCameraMath.smoothstep(progress);
                eye = from.eye().lerp(eye, amount);
                yaw = SmartCameraMath.lerpDegrees(from.yaw(), yaw, amount);
                pitch = from.pitch() + (pitch - from.pitch()) * amount;
                double fromFov = from.applyFov() || applyFov ? from.fov() : fov;
                fov = fromFov + (fov - fromFov) * amount;
                transition = progress;
            }
        }
        // Keep the yaw continuous from step to step, so that blending between steps never goes the long way round
        if (this.lastStep != null && !cut) {
            yaw = this.lastStep.yaw() + SmartCameraMath.wrapDegrees(yaw - this.lastStep.yaw());
        }

        SmartCameraMath.ScreenPoint screen = SmartCameraMath.project(eye.x(), eye.y(), eye.z(), yaw, pitch, aim.x(), aim.y(), aim.z(), fov, aspect);
        double visibility = CameraProbe.visibility(world, eye, aim, subject.height());

        String shotKind;
        int shotIndex = Math.max(0, this.cuts);
        double shotStart = this.startTick;
        String shotReason = this.startReason;
        if (this.director != null && this.director.kind() != null) {
            shotKind = this.director.kind().id();
            shotStart = this.director.startTick();
            shotReason = this.director.startReason();
        } else {
            shotKind = settings.mode.name().toLowerCase(java.util.Locale.ROOT);
        }

        this.initialised = true;
        this.lastTick = tick;
        this.lastSettings = settings;
        this.lastKeyframeTick = keyframeTick;
        this.lastMode = settings.mode;
        this.lastFeet = rawFeet;
        this.lastStep = new Step(tick, settings.mode, shotKind, shotIndex, shotStart, (tick - shotStart) / 20.0, shotReason, aim, subject.size(), eye, yaw, pitch,
            fov, applyFov, screen.x(), screen.y(), screen.inFront(), pose.targetScreenX(), pose.targetScreenY(), pose.obstructed(), visibility, transition, cut,
            this.motion.activity().id());
        return this.lastStep;
    }

}
