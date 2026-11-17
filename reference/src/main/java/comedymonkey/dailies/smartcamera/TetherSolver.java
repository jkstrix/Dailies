package comedymonkey.dailies.smartcamera;

/**
 * A camera tied to a moving point by an offset: the follow entity keyframe ("keep place"), an orbit keyframe centred
 * on an entity, and the players panel's Follow. The point is followed on a critically damped spring led by its own
 * velocity, so the offset is kept exactly at a steady speed while bumps, hops and vehicle jitter are smoothed away;
 * the line of sight is kept clear of blocks as for the chase camera. "Stay and watch" turns a still camera after the
 * point on springs.
 */
public final class TetherSolver {

    /**
     * @param eye     where the camera's eye is
     * @param blocked whether blocks pulled the camera in from where it wanted to be
     * @param cut     whether the camera jumped this step (out from behind a wall it was stuck behind, or after a teleport)
     */
    public record Result(Vec eye, double yaw, double pitch, boolean blocked, boolean cut) {
    }

    private final Spring.Point pivot = new Spring.Point();
    private final Clearance clearance = new Clearance();
    private final Aimer aimer = new Aimer();
    private Vec lastPoint = Vec.ZERO;
    private Vec velocity = Vec.ZERO;
    private boolean started = false;
    private boolean aimed = false;

    public void reset() {
        this.started = false;
        this.aimed = false;
    }

    /** The smoothed point, as the camera keeps to it */
    public Vec pivot() {
        return this.pivot.value();
    }

    public Vec velocity() {
        return this.velocity;
    }

    /**
     * Keeps the camera at an offset from a moving point.
     *
     * @param point      the point followed, this step
     * @param offset     from the point to where the eye should be
     * @param look       what to look at
     * @param smoothTime seconds the camera takes to settle after the point; 0 is rigid
     * @param aimTime    seconds the rotation takes to settle; 0 aims exactly
     * @param avoid      whether to keep the line of sight clear of blocks
     */
    public Result keep(CameraWorld world, Vec point, Vec offset, Vec look, double smoothTime, double aimTime, double fov, double aspect, double dt,
                       boolean avoid) {
        dt = this.follow(point, dt);
        Vec followed = dt > 0.0 && smoothTime > 0.0
            // Led by the velocity, less the half step the goal is ahead by when it is taken at the end of each step
            ? this.pivot.step(point.add(this.velocity.scale(Math.max(0.0, smoothTime - dt * 0.5))), smoothTime, smoothTime * 1.6, dt)
            : point;
        if (dt <= 0.0 || smoothTime <= 0.0) {
            this.pivot.reset(point, this.velocity);
        }
        Vec wanted = followed.add(offset);
        Vec eye = avoid ? this.clearance.solve(world, point, wanted, 0.0, this.velocity, this.velocity, dt) : wanted;
        return this.aim(eye, look, aimTime, fov, aspect, dt, avoid && this.clearance.blocked(), dt <= 0.0 || avoid && this.clearance.jumped());
    }

    /** Stays where it is and turns to watch a moving point */
    public Result watch(Vec eye, Vec point, Vec look, double aimTime, double fov, double aspect, double dt) {
        dt = this.follow(point, dt);
        return this.aim(eye, look, aimTime, fov, aspect, dt, false, dt <= 0.0);
    }

    /** Takes in where the point is; a jump (backwards in time, a long gap or a teleport) starts afresh */
    private double follow(Vec point, double dt) {
        boolean jumped = !this.started || dt < 0.0 || dt > 0.5;
        if (!jumped && dt > 0.0) {
            double moved = point.distanceTo(this.lastPoint);
            jumped = moved > 3.0 && moved / dt > 100.0;
        }
        if (jumped) {
            this.started = true;
            this.aimed = false;
            this.lastPoint = point;
            this.velocity = Vec.ZERO;
            this.pivot.reset(point);
            this.clearance.reset();
            return 0.0;
        }
        if (dt > 0.0) {
            this.velocity = this.velocity.lerp(point.sub(this.lastPoint).scale(1.0 / dt), SmartCameraMath.blendFactor(0.15, dt));
            this.lastPoint = point;
        }
        return dt;
    }

    private Result aim(Vec eye, Vec look, double aimTime, double fov, double aspect, double dt, boolean blocked, boolean cut) {
        SmartCameraMath.Aim goal = SmartCameraMath.aimAt(eye.x(), eye.y(), eye.z(), look.x(), look.y(), look.z(), 0.0, 0.0, fov, aspect);
        if (eye.distanceTo(look) < 1.0e-3) {
            goal = new SmartCameraMath.Aim(this.aimer.yaw(), this.aimer.pitch(), false);
        }
        if (aimTime <= 0.0 || !this.aimed || dt <= 0.0) {
            this.aimer.reset(goal);
            this.aimed = true;
        } else {
            this.aimer.step(goal.yaw(), goal.pitch(), aimTime, fov, aspect, dt);
        }
        return new Result(eye, SmartCameraMath.wrapDegrees(this.aimer.yaw()), this.aimer.pitch(), blocked, cut);
    }

}
