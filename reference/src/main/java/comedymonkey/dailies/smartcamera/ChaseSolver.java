package comedymonkey.dailies.smartcamera;

/**
 * Follows behind the subject relative to the way it travels.
 * <ul>
 *     <li>The camera keeps to a point that follows the subject on critically damped springs (the height on a slower
 *     one than the level), led by the subject's velocity so the camera keeps its distance at any speed instead of
 *     trailing. Round that point it is placed by heading, distance and height, each smoothed on its own, so a turn
 *     swings it round the subject at its distance rather than cutting across close by.</li>
 *     <li>The heading comes through {@link HeadingFilter}, so jitter and small turns do not swing the camera.</li>
 *     <li>Yaw and pitch have springs of their own ({@link Aimer}), aimed a little ahead of a moving subject for lead
 *     room that grows with speed.</li>
 *     <li>The height follows the terrain softly: up over a rise behind the subject, down under a cave's roof.</li>
 *     <li>In flight and at speed it widens and lifts, and follows the velocity through climbs and dives.</li>
 *     <li>Where there is no room behind (a cave, a doorway, a wall), it follows the way the subject came instead,
 *     which is always open, round bends and through doors.</li>
 *     <li>{@link Clearance} keeps the line of sight: in before a wall comes between, back out after, never through.</li>
 * </ul>
 */
public final class ChaseSolver implements ShotSolver {

    /** Seconds the rotation takes to settle after the aim */
    private static final double AIM_TIME = 0.25;
    private static final double SHAPE_TIME = 0.6;
    private static final double LEAD_TIME = 0.6;
    private static final double TRAIL_TIME = 0.5;

    private final Spring.Point pivot = new Spring.Point();
    private final Spring distance = new Spring();
    private final Spring height = new Spring();
    private final Spring widen = new Spring();
    private final Spring flight = new Spring();
    private final Spring trail = new Spring();
    private final Spring.Point lead = new Spring.Point();
    private final Spring trailLift = new Spring();
    /** How far back along the way the camera goes: its distance, or less where the way bends out of sight (back out of a door) */
    private final Spring trailReach = new Spring();
    /** A last, short smoothing of the camera's wanted place, for the corners where heading, trail and roof meet */
    private final Spring.Point place = new Spring.Point();
    private static final double PLACE_TIME = 0.15;
    private final Clearance clearance = new Clearance();
    /** Turned further round, and raised, to see the rider of a craft past its own envelope and frame */
    private final Spring viewSide = new Spring();
    private final Spring viewLift = new Spring();
    private double viewSideGoal;
    private double viewLiftGoal;
    private double riderHidden;
    private double searchIn;
    private boolean mayCut = true;
    private final Aimer aimer = new Aimer();

    /** For the director, whose shots never cut by themselves: it cuts away from a shot that stops working */
    public ChaseSolver withoutCuts() {
        this.mayCut = false;
        this.clearance.neverJump();
        return this;
    }

    @Override
    public Pose start(Input input) {
        Motion motion = input.motion();
        this.pivot.reset(input.subject().aim(), motion.velocity());
        this.widen.reset(this.widenGoal(input));
        this.flight.reset(motion.flying() ? 1.0 : 0.0);
        this.distance.reset(this.wantedDistance(input));
        this.height.reset(this.wantedHeight(input));
        // A craft and its rider are seen from the side from the start, at whatever height its blocks let the rider
        // be seen: from straight behind the craft hides who rides it
        this.viewSideGoal = 0.0;
        this.viewLiftGoal = 0.0;
        if (riding(input)) {
            this.viewSideGoal = 45.0 * (input.settings().sideAngle < 0.0f ? -1.0 : 1.0);
            this.findView(input);
        }
        this.viewSide.reset(this.viewSideGoal);
        this.viewLift.reset(this.viewLiftGoal);
        this.riderHidden = 0.0;
        this.searchIn = 0.0;
        this.trail.reset(this.trailGoal(input, this.headingIdeal(input)));
        this.trailReach.reset(this.visibleReach(input));
        this.trailLift.reset(this.trailLiftGoal(input));
        this.clearance.reset();
        Vec ideal = this.ideal(input);
        this.place.reset(ideal, motion.velocity());
        Vec eye = this.clear(input, ideal);
        this.lead.reset(this.leadGoal(input, eye));
        SmartCameraMath.Aim aim = this.goal(input, eye);
        this.aimer.reset(aim);
        return new Pose(eye, aim.yaw(), aim.pitch(), this.clearance.blocked(), input.settings().screenX, input.settings().screenY, true);
    }

    @Override
    public Pose step(Input input) {
        double dt = input.dt();
        SmartCameraSettings settings = input.settings();
        Motion motion = input.motion();
        Vec aim = input.subject().aim();
        double smoothing = settings.positionSmoothing;
        if (smoothing <= 0.0) {
            this.pivot.reset(aim, motion.velocity());
        } else {
            // A spring chasing a moving goal trails it by velocity times its time; aiming that far ahead cancels the trail
            this.pivot.step(aim.add(motion.velocity().scale(smoothing)), smoothing, Math.max(smoothing, 0.45), dt);
        }
        this.widen.step(this.widenGoal(input), 0.8, dt);
        this.flight.step(motion.flying() ? 1.0 : 0.0, 0.6, dt);
        this.distance.step(this.wantedDistance(input), SHAPE_TIME, dt);
        this.height.step(this.wantedHeight(input), SHAPE_TIME, dt);
        if (riding(input)) {
            this.searchIn -= dt;
            if (this.searchIn <= 0.0 && this.riderHidden > 0.4) {
                this.findView(input);
                this.searchIn = 1.0;
            }
        }
        this.viewSide.step(this.viewSideGoal, 0.9, dt);
        this.viewLift.step(this.viewLiftGoal, 0.9, dt);
        this.trail.step(this.trailGoal(input, this.headingIdeal(input)), TRAIL_TIME, dt);
        this.trailReach.step(this.visibleReach(input), 0.5, dt);
        this.trailLift.step(this.trailLiftGoal(input), 0.4, dt);
        Vec wanted = this.place.step(this.ideal(input).add(motion.velocity().scale(PLACE_TIME)), PLACE_TIME, PLACE_TIME * 1.6, dt);
        Vec eye = this.clear(input, wanted);
        this.riderHidden = riding(input) && input.riderSeen(eye) < 0.5 ? this.riderHidden + dt : 0.0;
        this.lead.step(this.leadGoal(input, eye), LEAD_TIME, LEAD_TIME, dt);
        SmartCameraMath.Aim goal = this.goal(input, eye);
        // A fast subject swings across the view at every turn: a softer aim spreads the swing out
        double aimTime = AIM_TIME + 0.15 * SmartCameraMath.clamp((motion.speed() - 6.0) / 14.0, 0.0, 1.0);
        if (this.clearance.jumped()) {
            this.aimer.reset(goal);
        } else {
            this.aimer.step(goal.yaw(), goal.pitch(), aimTime, input.fov(), input.aspect(), dt);
        }
        return new Pose(eye, this.aimer.yaw(), this.aimer.pitch(), this.clearance.blocked(), settings.screenX, settings.screenY, this.clearance.jumped());
    }

    private double widenGoal(Input input) {
        Motion motion = input.motion();
        double fast = SmartCameraMath.clamp((motion.speed() - 6.0) / 14.0, 0.0, 1.0);
        return Math.max(fast, motion.flying() ? 1.0 : 0.0);
    }

    private double wantedDistance(Input input) {
        return input.settings().distance * (1.0 + this.widen.value * input.settings().flightWiden) + input.subject().extra();
    }

    /** The height above the aim point, raised over a rise behind the subject and lowered under a roof */
    private double wantedHeight(Input input) {
        SmartCameraSettings settings = input.settings();
        double height = settings.height * (1.0 + this.widen.value * settings.flightWiden * 0.75);
        Vec ideal = this.headingIdealAt(input, this.pivot.value(), this.distance.value, height);
        CameraWorld world = input.world();
        Vec aim = input.subject().aim();
        if (this.flight.value < 0.5) {
            double ground = CameraProbe.groundBelow(world, ideal.add(0.0, 2.0, 0.0), 4.0 + Math.abs(height) + 6.0);
            if (!Double.isNaN(ground)) {
                height = Math.max(height, ground + 1.2 - (ideal.y() - height));
            }
        }
        double ceiling = CameraProbe.ceilingAbove(world, aim, Math.max(0.0, height) + 1.0);
        if (!Double.isNaN(ceiling)) {
            height = Math.min(height, Math.max(0.0, ceiling - 0.6 - aim.y()));
        }
        return height;
    }

    private Vec headingIdeal(Input input) {
        return this.headingIdealAt(input, this.pivot.value(), this.distance.value, this.height.value + this.viewLift.value);
    }

    /** Whether the subject is someone aboard something larger that is framed with them */
    private static boolean riding(Input input) {
        return input.rider().distanceTo(input.subject().aim()) > 0.5;
    }

    /**
     * Looks round the craft for where to stand to see its rider: further round to either side, higher or lower, nearest to
     * where the camera is meant to be first, counting the craft's own blocks as in the way and needing room and a
     * clear view of the craft too. Leaves the goals as they are when nowhere is better.
     */
    private void findView(Input input) {
        double[] turns = {0.0, 30.0, -30.0, 60.0, -60.0, 90.0, -90.0, 120.0, -120.0};
        double[] lifts = {0.0, -2.0, 2.0, -4.0, 4.0, -6.0, 7.0, 11.0};
        Vec aim = input.subject().aim();
        double extra = input.subject().extra();
        for (double lift : lifts) {
            for (double turn : turns) {
                double side = this.viewSideGoal + turn;
                double height = this.height.value + this.viewLiftGoal + lift;
                Vec eye = this.idealAround(input, side, height);
                if (input.world().solid(eye) || Clearance.allowedShare(input.world(), aim, eye, extra) < 1.0 || input.riderSeen(eye) < 0.67) {
                    continue;
                }
                this.viewSideGoal = SmartCameraMath.clamp(side, -150.0, 150.0);
                this.viewLiftGoal = this.viewLiftGoal + lift;
                return;
            }
        }
    }

    private Vec idealAround(Input input, double extraSide, double height) {
        SmartCameraSettings settings = input.settings();
        Motion motion = input.motion();
        double heading = settings.headingSource == SmartCameraHeading.BODY ? motion.bodyHeading() : motion.travelHeading();
        return this.pivot.value().add(Vec.look(heading + settings.sideAngle + extraSide, 0.0).scale(-this.distance.value)).add(0.0, height, 0.0);
    }

    /** Behind the subject by its heading, at a distance and a height round a point */
    private Vec headingIdealAt(Input input, Vec centre, double distance, double height) {
        SmartCameraSettings settings = input.settings();
        Motion motion = input.motion();
        double heading = settings.headingSource == SmartCameraHeading.BODY ? motion.bodyHeading() : motion.travelHeading();
        // In flight the camera stays behind along the velocity, through climbs and dives, a little flatter than it so the horizon stays in view
        double climb = motion.climb() * 0.7 * this.flight.value;
        return centre.add(Vec.look(heading + settings.sideAngle + this.viewSide.value, -climb).scale(-distance)).add(0.0, height, 0.0);
    }

    /** Whether to follow the way the subject came: on the ground, nearly behind, and with no room behind by heading */
    private double trailGoal(Input input, Vec headingIdeal) {
        Motion motion = input.motion();
        if (motion.flying() || !motion.moving() || Math.abs(SmartCameraMath.wrapDegrees(input.settings().sideAngle)) > 60.0) {
            return 0.0;
        }
        if (motion.enclosure() > 0.6) {
            return 1.0;
        }
        return Clearance.allowedShare(input.world(), input.subject().aim(), headingIdeal, input.subject().extra()) < 0.75 ? 1.0 : 0.0;
    }

    /** Where the camera would be with nothing in the way */
    private Vec ideal(Input input) {
        Vec byHeading = this.headingIdeal(input);
        if (this.trail.value < 1.0e-3) {
            return byHeading;
        }
        Vec back = input.motion().trailPoint(input.subject().aim(), this.trailReach.value);
        Vec byTrail = back.add(0.0, this.trailLift.value, 0.0);
        return byHeading.lerp(byTrail, SmartCameraMath.clamp(this.trail.value, 0.0, 1.0));
    }

    /** The farthest back along the way, up to the camera's distance, from where the subject can still be seen */
    private double visibleReach(Input input) {
        Vec aim = input.subject().aim();
        double extra = input.subject().extra();
        for (double reach = this.distance.value; reach > 1.5; reach -= 0.5) {
            if (Clearance.allowedShare(input.world(), aim, input.motion().trailPoint(aim, reach), extra) >= 1.0) {
                return reach;
            }
        }
        return 1.5;
    }

    /** How far above the way behind the camera goes when it follows the way: a little, as far as the roof there allows */
    private double trailLiftGoal(Input input) {
        Vec back = input.motion().trailPoint(input.subject().aim(), this.trailReach.value);
        double lift = Math.max(0.0, this.height.value) * 0.6;
        double roof = CameraProbe.ceilingAbove(input.world(), back, lift + 0.6);
        return Double.isNaN(roof) ? lift : Math.max(0.0, roof - 0.6 - back.y());
    }

    private Vec clear(Input input, Vec wanted) {
        if (!input.settings().avoidObstructions) {
            return wanted;
        }
        return this.clearance.solve(input.world(), input.subject().aim(), wanted, input.subject().extra(), input.dt());
    }

    private Vec leadGoal(Input input, Vec eye) {
        return Aimer.lead(input.motion().velocity(), input.settings().leadRoom, eye.distanceTo(input.subject().aim()), input.fov(), input.aspect(),
            !input.motion().flying());
    }

    private SmartCameraMath.Aim goal(Input input, Vec eye) {
        SmartCameraSettings settings = input.settings();
        // A craft and its rider: aimed between the middle of the craft and the rider, so both are in the picture
        Vec look = input.subject().aim().lerp(input.rider(), 0.4).add(this.lead.value());
        return SmartCameraMath.aimAt(eye.x(), eye.y(), eye.z(), look.x(), look.y(), look.z(), settings.screenX, settings.screenY, input.fov(), input.aspect());
    }

}
