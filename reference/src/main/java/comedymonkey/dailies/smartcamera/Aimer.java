package comedymonkey.dailies.smartcamera;

/**
 * Turns a camera after a moving aim on two springs, one for yaw and one for pitch. The springs are led by how fast
 * the aim itself turns, so a subject crossing the frame at a steady rate stays where it is meant to be rather than
 * trailing behind by the smoothing, and a subject that has drifted too far off its place is reeled in harder so it
 * never leaves the frame.
 */
public final class Aimer {

    /** How far (a share of half the frame) the subject drifts from its place before it is reeled in harder */
    private static final double MOST_DRIFT = 0.4;

    private final Spring yaw = new Spring();
    private final Spring pitch = new Spring();
    private double lastGoalYaw;
    private double lastGoalPitch;
    private double yawRate;
    private double pitchRate;
    /** How fast the camera turns, degrees a second, as it last did */
    private double turnYaw;
    private double turnPitch;
    /** Degrees a second by which the speed of turning may change in a second */
    private double mostTurnChange = 40.0;
    /** How much more the speed of turning may grow for each degree a second it already turns: growth in proportion */
    private static final double TURN_GROWTH = 1.6;

    /**
     * A gentler or brisker limit on how fast the speed of turning grows from rest, degrees a second each second; on top
     * of it the speed may grow in proportion to itself, so a turn never jumps to five times its speed within a moment
     */
    public Aimer turnChange(double degreesPerSecondSquared) {
        this.mostTurnChange = degreesPerSecondSquared;
        return this;
    }

    public void reset(double toYaw, double toPitch) {
        this.yaw.reset(toYaw);
        this.pitch.reset(toPitch);
        this.lastGoalYaw = toYaw;
        this.lastGoalPitch = toPitch;
        this.yawRate = 0.0;
        this.pitchRate = 0.0;
        this.turnYaw = 0.0;
        this.turnPitch = 0.0;
    }

    public void reset(SmartCameraMath.Aim aim) {
        this.reset(aim.yaw(), aim.pitch());
    }

    public double yaw() {
        return this.yaw.value;
    }

    public double pitch() {
        return this.pitch.value;
    }

    /**
     * @param fov    vertical field of view, for how far a drift may go
     * @param aspect width over height
     */
    public void step(double goalYaw, double goalPitch, double smoothTime, double fov, double aspect, double dt) {
        if (dt <= 0.0) {
            return;
        }
        double unwrappedGoal = this.lastGoalYaw + SmartCameraMath.wrapDegrees(goalYaw - this.lastGoalYaw);
        // Measured over a third of a second, so a subject that changes direction at once is not followed with a jolt
        double blend = SmartCameraMath.blendFactor(0.35, dt);
        this.yawRate += ((unwrappedGoal - this.lastGoalYaw) / dt - this.yawRate) * blend;
        this.pitchRate += ((goalPitch - this.lastGoalPitch) / dt - this.pitchRate) * blend;
        this.lastGoalYaw = unwrappedGoal;
        this.lastGoalPitch = goalPitch;

        // Drifting far from its place, the subject is reeled in harder: tighter springs as it nears the edge, and
        // only at the very edge a hard stop, so it never leaves the frame
        double halfHeight = fov / 2.0;
        double halfWidth = Math.toDegrees(Math.atan(Math.tan(Math.toRadians(halfHeight)) * aspect));
        double yawLimit = halfWidth * MOST_DRIFT;
        double pitchLimit = halfHeight * MOST_DRIFT;
        double drift = Math.max(Math.abs(SmartCameraMath.wrapDegrees(this.yaw.value - unwrappedGoal)) / yawLimit, Math.abs(this.pitch.value - goalPitch) / pitchLimit);
        double time = smoothTime * SmartCameraMath.clamp(1.6 - drift, 0.3, 1.0);

        double leadYaw = SmartCameraMath.clamp(this.yawRate * time, -30.0, 30.0);
        double leadPitch = SmartCameraMath.clamp(this.pitchRate * time, -20.0, 20.0);
        double yawBefore = this.yaw.value;
        double yawSpeedBefore = this.turnYaw;
        double pitchBefore = this.pitch.value;
        double pitchSpeedBefore = this.turnPitch;
        this.yaw.stepAngle(unwrappedGoal + leadYaw, time, dt);
        this.pitch.step(SmartCameraMath.clamp(goalPitch + leadPitch, -89.9, 89.9), time, dt);

        // However the aim moves, the camera never bursts into a turn: how fast it turns grows by no more than a set
        // amount a second (more as the subject nears the edge of the frame). Slowing down and changing direction are
        // free, they never jolt. The springs carry on from the turn actually made
        double most = (this.mostTurnChange + TURN_GROWTH * Math.hypot(yawSpeedBefore, pitchSpeedBefore)) * SmartCameraMath.clamp(drift - 1.0, 1.0, 1.5) * dt;
        double yawSpeed = (this.yaw.value - yawBefore) / dt;
        double pitchSpeed = (this.pitch.value - pitchBefore) / dt;
        double speed = Math.hypot(yawSpeed, pitchSpeed);
        double speedBefore = Math.hypot(yawSpeedBefore, pitchSpeedBefore);
        if (speed > speedBefore + most) {
            double scale = (speedBefore + most) / speed;
            yawSpeed *= scale;
            pitchSpeed *= scale;
            this.yaw.value = yawBefore + yawSpeed * dt;
            this.pitch.value = pitchBefore + pitchSpeed * dt;
            this.yaw.velocity = yawSpeed;
            this.pitch.velocity = pitchSpeed;
        }
        this.turnYaw = yawSpeed;
        this.turnPitch = pitchSpeed;

        this.pitch.value = SmartCameraMath.clamp(this.pitch.value, -89.9, 89.9);
    }

    /**
     * A point ahead of the subject to aim at instead of the subject itself, which leaves room in the frame in front of
     * a moving subject. It grows with speed, up to {@code leadRoom} of half the frame's width at that distance.
     */
    public static Vec lead(Vec velocity, double leadRoom, double distance, double fov, double aspect, boolean level) {
        Vec direction = level ? velocity.withY(0.0) : velocity;
        double speed = direction.length();
        if (leadRoom <= 0.0 || speed < 0.3) {
            return Vec.ZERO;
        }
        double halfWidth = Math.max(distance, 1.0) * Math.tan(Math.toRadians(fov) / 2.0) * aspect;
        double amount = halfWidth * leadRoom * 0.8 * SmartCameraMath.clamp((speed - 0.3) / 6.0, 0.0, 1.0);
        return direction.scale(amount / speed);
    }

}
