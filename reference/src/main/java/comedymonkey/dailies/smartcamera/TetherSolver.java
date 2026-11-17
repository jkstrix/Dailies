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
        this.watchPlace = null;
        this.hiddenSeconds = 0.0;
        this.sinceMoved = 0.0;
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
        SubjectSample sample = new SubjectSample(point, point, 1.8, 1.8, 0.0, 0.0, false, false, false, false, false, SubjectSample.FRAMED_NONE);
        if (this.jumpedNow) {
            this.motion.reset(sample, Vec.ZERO, world);
            this.trailWeight.reset(0.0);
            this.shiftYaw.reset(0.0);
            this.shiftLift.reset(0.0);
            this.shiftYawGoal = 0.0;
            this.shiftLiftGoal = 0.0;
            this.hidden = 0.0;
            this.searchIn = 0.0;
        } else if (dt > 0.0) {
            this.motion.step(sample, world, 15.0, dt);
        }
        Vec followed;
        if (this.jumpedNow || smoothTime <= 0.0) {
            this.pivot.reset(point, this.velocity);
            followed = point;
        } else if (dt > 0.0) {
            // Led by the velocity, less the half step the goal is ahead by when it is taken at the end of each step
            followed = this.pivot.step(point.add(this.velocity.scale(Math.max(0.0, smoothTime - dt * 0.5))), smoothTime, smoothTime * 1.6, dt);
        } else {
            followed = this.pivot.value();
        }
        this.shiftYaw.step(this.shiftYawGoal, 0.9, dt);
        this.shiftLift.step(this.shiftLiftGoal, 0.9, dt);
        Vec wanted = followed.add(turned(offset, this.shiftYaw.value)).add(0.0, this.shiftLift.value, 0.0);
        if (!avoid) {
            return this.aim(wanted, look, aimTime, fov, aspect, dt, false, this.jumpedNow);
        }

        // Its place cannot see the subject (they went in through a door, round a corner): follow the way they went,
        // as far back along it as they can still be seen from, which leads in through the door after them
        boolean placeSees = Clearance.allowedShare(world, point, wanted, 0.0) >= 1.0;
        // Only for someone going along: the way a climbing craft came is straight down through its floor
        boolean along = this.velocity.horizontalLength() > 1.0 && Math.abs(this.velocity.y()) < this.velocity.horizontalLength();
        this.trailWeight.step(placeSees || !along ? 0.0 : 1.0, 0.5, dt);
        if (this.trailWeight.value > 1.0e-3) {
            double reach = offset.length();
            Vec back = this.motion.trailPoint(point, reach);
            while (reach > 1.5 && Clearance.allowedShare(world, point, back, 0.0) < 1.0) {
                reach -= 0.5;
                back = this.motion.trailPoint(point, reach);
            }
            this.trailReach.step(reach, 0.5, dt);
            back = this.motion.trailPoint(point, this.trailReach.value);
            double roof = CameraProbe.ceilingAbove(world, back, 1.2);
            Vec onTrail = back.add(0.0, Double.isNaN(roof) ? 0.6 : Math.max(0.0, Math.min(0.6, roof - 0.5 - back.y())), 0.0);
            wanted = wanted.lerp(onTrail, SmartCameraMath.clamp(this.trailWeight.value, 0.0, 1.0));
        } else {
            this.trailReach.reset(offset.length());
        }
        Vec eye = this.clearance.solve(world, point, wanted, 0.0, this.velocity, this.velocity, dt);
        if (this.clearance.jumped()) {
            // A cut: the camera looks straight at the subject from its new place, it does not swing round to it
            this.aimed = false;
        }

        // Still hidden for a while: look round for a place at the same distance, further round or higher, that sees
        // the subject, and ease over to it (and stay there while it works)
        // Pulled right in on the subject is as bad as not seeing them: nothing of them fits the frame from there
        boolean squeezed = eye.distanceTo(point) < Math.min(1.5, 0.5 * offset.length());
        boolean seen = !squeezed && this.seenFrom(world, eye, point) >= 0.6;
        this.hidden = seen ? 0.0 : this.hidden + Math.max(dt, 0.0);
        this.searchIn -= Math.max(dt, 0.0);
        Vec goalPlace = followed.add(turned(offset, this.shiftYawGoal)).add(0.0, this.shiftLiftGoal, 0.0);
        boolean goalWorks = !world.solid(goalPlace) && Clearance.allowedShare(world, point, goalPlace, 0.0) >= 1.0 && this.seenFrom(world, goalPlace, point) >= 0.6;
        if (this.hidden > 0.6 && this.searchIn <= 0.0 && !goalWorks) {
            this.searchIn = 1.0;
            search:
            for (double lift : new double[]{0.0, -2.0, 2.0, -4.0, 4.0}) {
                for (double turn : new double[]{30.0, -30.0, 60.0, -60.0, 90.0, -90.0, 135.0, -135.0, 180.0}) {
                    Vec place = followed.add(turned(offset, this.shiftYawGoal + turn)).add(0.0, this.shiftLiftGoal + lift, 0.0);
                    if (!world.solid(place) && Clearance.allowedShare(world, point, place, 0.0) >= 1.0 && this.seenFrom(world, place, point) >= 0.6) {
                        this.shiftYawGoal += turn;
                        this.shiftLiftGoal += lift;
                        break search;
                    }
                }
            }
        }
        return this.aim(eye, look, aimTime, fov, aspect, dt, this.clearance.blocked() || !seen, this.jumpedNow || this.clearance.jumped());
    }

    /** An offset turned round the vertical by some degrees */
    private static Vec turned(Vec offset, double degrees) {
        if (Math.abs(degrees) < 1.0e-9) {
            return offset;
        }
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new Vec(offset.x() * cos - offset.z() * sin, offset.y(), offset.x() * sin + offset.z() * cos);
    }

    /** How far below the followed point the subject's feet are, and how tall and wide it is, for how much of it is seen */
    private double feetBelow = 1.62;
    private double boxHeight = 1.8;
    private double boxWidth = 0.6;

    /** Says where the followed point is on the subject's body: how far above its feet, and its size */
    public TetherSolver box(double pointAboveFeet, double height, double width) {
        this.feetBelow = pointAboveFeet;
        this.boxHeight = height;
        this.boxWidth = width;
        return this;
    }

    /** How much of the subject a camera there sees, by the rays the camera films use */
    private double seenFrom(CameraWorld world, Vec eye, Vec point) {
        return CameraProbe.boxSeen(world, eye, point.add(0.0, -this.feetBelow, 0.0), this.boxHeight, this.boxWidth);
    }

    private final Motion motion = new Motion();
    private final Spring trailWeight = new Spring();
    private final Spring trailReach = new Spring();
    private final Spring shiftYaw = new Spring();
    private final Spring shiftLift = new Spring();
    private double shiftYawGoal;
    private double shiftLiftGoal;
    private double hidden;
    private double searchIn;

    /** Stays where it is and turns to watch a moving point, in open air: it never needs to move */
    public Result watch(Vec eye, Vec point, Vec look, double aimTime, double fov, double aspect, double dt) {
        return this.watch(CameraWorld.OPEN, eye, point, look, 1.8, aimTime, fov, aspect, dt);
    }

    /**
     * Stays where it is and turns to watch a moving point. When the point has been out of its sight for over a second
     * (behind trees, round a bend of a cave, through a portal), it moves by a cut to a new place to watch from, chosen
     * as a tripod chooses one; the place it was given is only the first.
     *
     * @param given  the place it was given to watch from
     * @param height the height of what is watched, for how much of it is seen
     */
    public Result watch(CameraWorld world, Vec given, Vec point, Vec look, double height, double aimTime, double fov, double aspect, double dt) {
        boolean wasStarted = this.started;
        dt = this.follow(point, dt);
        boolean jumped = wasStarted && this.jumpedNow;
        Vec eye = this.watchPlace != null ? this.watchPlace : given;
        boolean seen = this.seenFrom(world, eye, point) >= 0.6;
        this.hiddenSeconds = seen ? 0.0 : this.hiddenSeconds + Math.max(dt, 0.0);
        this.sinceMoved += Math.max(dt, 0.0);
        boolean cut = false;
        if ((jumped && !seen) || (this.hiddenSeconds > MOST_HIDDEN_SECONDS && this.sinceMoved > MOST_HIDDEN_SECONDS)) {
            double heading = this.velocity.horizontalLength() > 0.5 ? SmartCameraMath.yawOf(this.velocity.x(), this.velocity.z()) : 0.0;
            SubjectSample subject = new SubjectSample(point.add(0.0, -height * 0.75, 0.0), point, height, height, 0.0, heading, false, false, false, false, false,
                SubjectSample.FRAMED_NONE);
            // The place must see the whole of the subject, as the films judge it, not just the point watched
            Vec chest = point.add(0.0, 1.35 - this.feetBelow, 0.0);
            eye = TripodSolver.placeFor(world, subject, this.velocity, heading, this.velocity.length() > 1.0, 0.0, 0, Vec.ZERO, world, chest);
            this.watchPlace = eye;
            this.hiddenSeconds = 0.0;
            this.sinceMoved = 0.0;
            this.aimed = false;
            cut = true;
        }
        return this.aim(eye, look, aimTime, fov, aspect, dt, false, cut || this.jumpedNow);
    }

    /** Seconds the watched point may be out of sight before a watching camera moves */
    private static final double MOST_HIDDEN_SECONDS = 1.0;
    private Vec watchPlace = null;
    /** Whether the last step started afresh: the first, or after a jump in time or a teleport */
    private boolean jumpedNow = false;
    private double hiddenSeconds = 0.0;
    private double sinceMoved = 0.0;

    /** Takes in where the point is; a jump (backwards in time, a long gap or a teleport) starts afresh */
    private double follow(Vec point, double dt) {
        boolean jumped = !this.started || dt < 0.0 || dt > 0.5;
        if (!jumped && dt > 0.0) {
            double moved = point.distanceTo(this.lastPoint);
            jumped = moved > 3.0 && moved / dt > 100.0;
        }
        this.jumpedNow = jumped;
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
        if (aimTime <= 0.0 || !this.aimed) {
            this.aimer.reset(goal);
            this.aimed = true;
        } else if (dt > 0.0) {
            this.aimer.step(goal.yaw(), goal.pitch(), aimTime, fov, aspect, dt);
        }
        return new Result(eye, SmartCameraMath.wrapDegrees(this.aimer.yaw()), this.aimer.pitch(), blocked, cut);
    }

}
