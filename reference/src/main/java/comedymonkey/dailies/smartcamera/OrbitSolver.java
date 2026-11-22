package comedymonkey.dailies.smartcamera;

/**
 * Circles the subject at a steady rate around a centre that follows the subject on a spring, so the circle does not
 * shake with every step. The radius and height are kept clear of the terrain by looking along the arc ahead for a
 * size of circle that fits, and easing to it, never jumping; near obstacles the rate slows down. {@link Clearance}
 * guards the line of sight on top of that, so the camera never passes through a block.
 */
public final class OrbitSolver implements ShotSolver {

    private static final double CENTRE_TIME = 0.6;
    private static final double SHAPE_TIME = 1.0;
    private static final double AIM_TIME = 0.25;
    private static final double[] RADII = {1.0, 0.8, 0.6, 0.45, 0.3, 0.2};
    private static final double[] LIFTS = {0.0, 1.5, 3.0};
    /** Steps between tries to get back to the full circle once it had to shrink */
    private static final int RETRY_EVERY = 10;

    private final Spring.Point centre = new Spring.Point();
    private final Spring radius = new Spring();
    private final Spring height = new Spring();
    private final Spring rate = new Spring();
    private final Spring widen = new Spring();
    /** Raised to see the rider of a craft past its own envelope and frame */
    private final Spring riderLift = new Spring();
    private double riderLiftGoal;
    private double riderHidden;
    private double liftIn;
    private final Clearance clearance = new Clearance();
    private boolean mayCut = true;
    private final Aimer aimer = new Aimer();
    private double angle;
    private int radiusChoice;
    private int liftChoice;
    private boolean ahead;
    private int steps;
    private double sinceHop = 99.0;
    private Vec lastEye = Vec.ZERO;

    /** Whether the line of sight from where the circle puts the camera now is cut to under half its length */
    private boolean squeezed(Input input) {
        double radians = Math.toRadians(this.angle);
        Vec wanted = this.centre.value().add(Math.sin(radians) * this.radius.value, this.height.value, -Math.cos(radians) * this.radius.value);
        Vec aim = input.subject().aim();
        double flat = Math.hypot(this.lastEye.x() - aim.x(), this.lastEye.z() - aim.z());
        return flat < 0.5 * this.radius.value || Clearance.allowedShare(input.world(), aim, wanted, input.subject().extra()) < 0.5;
    }

    /** Moves the circle's angle to the nearest one where some size of circle fits; false when there is none */
    private boolean hop(Input input) {
        double from = this.angle;
        for (int k = 1; k <= 12; k++) {
            double turn = ((k + 1) / 2) * 30.0 * (k % 2 == 1 ? 1.0 : -1.0) * Math.signum(input.settings().degreesPerSecond == 0 ? 1 : input.settings().degreesPerSecond);
            this.angle = from + turn;
            for (int radiusIndex = 0; radiusIndex < RADII.length; radiusIndex++) {
                for (int liftIndex = 0; liftIndex < LIFTS.length; liftIndex++) {
                    if (this.fits(input, radiusIndex, liftIndex, 0)) {
                        this.radiusChoice = radiusIndex;
                        this.liftChoice = liftIndex;
                        this.radius.reset(this.wantedRadius(input.settings(), input.subject()));
                        this.height.reset(this.wantedHeight(input.settings()));
                        this.clearance.reset();
                        this.ahead = this.clearAhead(input, radiusIndex, liftIndex);
                        this.sinceHop = 0.0;
                        return true;
                    }
                }
            }
        }
        this.angle = from;
        return false;
    }

    /** For the director, whose shots never cut by themselves: it cuts away from a shot that stops working */
    public OrbitSolver withoutCuts() {
        this.mayCut = false;
        this.clearance.neverJump();
        return this;
    }

    @Override
    public Pose start(Input input) {
        SmartCameraSettings settings = input.settings();
        this.centre.reset(input.subject().aim(), input.motion().velocity());
        this.angle = settings.startAngle + settings.degreesPerSecond * input.elapsed();
        this.radiusChoice = 0;
        this.liftChoice = 0;
        this.steps = 0;
        this.sinceHop = 99.0;
        this.widen.reset(this.widenGoal(input));
        // Level with the rider, not with the middle of their craft
        this.riderLiftGoal = input.rider().distanceTo(input.subject().aim()) > 0.5 ? input.rider().y() - input.subject().aim().y() : 0.0;
        this.riderLift.reset(this.riderLiftGoal);
        this.riderHidden = 0.0;
        this.liftIn = 0.0;
        this.choose(input, true);
        this.radius.reset(this.wantedRadius(settings, input.subject()));
        this.height.reset(this.wantedHeight(settings));
        this.rate.reset(this.ahead ? 1.0 : 0.25);
        this.clearance.reset();
        Vec eye = this.place(input);
        SmartCameraMath.Aim aim = this.goal(input, eye);
        this.aimer.reset(aim);
        return new Pose(eye, aim.yaw(), aim.pitch(), this.clearance.blocked(), settings.screenX, settings.screenY, true);
    }

    @Override
    public Pose step(Input input) {
        double dt = input.dt();
        SmartCameraSettings settings = input.settings();
        this.steps++;
        // A fast subject is kept closer to the middle, or it would run out past the camera at every turn
        double centreTime = CENTRE_TIME / (1.0 + input.motion().speed() / 6.0);
        this.centre.step(input.subject().aim().add(input.motion().velocity().scale(centreTime)), centreTime, centreTime, dt);
        this.widen.step(this.widenGoal(input), 0.8, dt);
        this.choose(input, false);
        this.rate.step(this.ahead ? 1.0 : 0.25, 0.8, dt);
        this.angle += settings.degreesPerSecond * this.rate.value * dt;
        this.radius.step(this.wantedRadius(settings, input.subject()), SHAPE_TIME, dt);
        // A rider hidden by their own craft: the circle moves up or down to the height from which they are seen
        this.liftIn -= dt;
        if (this.riderHidden > 0.4 && this.liftIn <= 0.0) {
            this.liftIn = 1.0;
            double radians = Math.toRadians(this.angle);
            for (double change : new double[]{-2.0, 2.0, -4.0, 4.0, -6.0, 7.0}) {
                double lift = this.riderLiftGoal + change;
                Vec eye = this.centre.value().add(Math.sin(radians) * this.radius.value, settings.height + LIFTS[this.liftChoice] + lift,
                    -Math.cos(radians) * this.radius.value);
                if (!input.world().solid(eye) && input.riderSeen(eye) >= 0.67) {
                    this.riderLiftGoal = lift;
                    break;
                }
            }
        }
        this.riderLift.step(this.riderLiftGoal, 0.8, dt);
        this.height.step(this.wantedHeight(settings), SHAPE_TIME, dt);
        this.sinceHop += dt;
        if (this.mayCut && settings.avoidObstructions && this.sinceHop > 1.5 && this.squeezed(input) && this.hop(input)) {
            // Walled in where it is (the subject went through a door, into a corner): rather than squeeze in on the
            // subject and whip round it, the circle carries on from the nearest place round it that has room, by a cut
            Vec eye = this.place(input);
            SmartCameraMath.Aim aim = this.goal(input, eye);
            this.aimer.reset(aim);
            return new Pose(eye, aim.yaw(), aim.pitch(), this.clearance.blocked(), settings.screenX, settings.screenY, true);
        }
        Vec eye = this.place(input);
        boolean riding = input.rider().distanceTo(input.subject().aim()) > 0.5;
        this.riderHidden = riding && input.riderSeen(eye) < 0.5 ? this.riderHidden + dt : 0.0;
        SmartCameraMath.Aim aim = this.goal(input, eye);
        if (this.clearance.jumped()) {
            this.aimer.reset(aim);
        } else {
            this.aimer.step(aim.yaw(), aim.pitch(), AIM_TIME, input.fov(), input.aspect(), dt);
        }
        return new Pose(eye, this.aimer.yaw(), this.aimer.pitch(), this.clearance.blocked(), settings.screenX, settings.screenY, this.clearance.jumped());
    }

    /** The camera's angle round the subject now, degrees: the same as the yaw it would look along at the centre */
    public double angle() {
        return this.angle;
    }

    private double wantedRadius(SmartCameraSettings settings, SubjectSample subject) {
        return this.radiusOf(settings, subject, this.radiusChoice);
    }

    /** In flight and at speed the circle widens, as the chase camera does */
    private double radiusOf(SmartCameraSettings settings, SubjectSample subject, int index) {
        return Math.max(1.5, settings.radius * (1.0 + this.widen.value * settings.flightWiden) * RADII[index]) + subject.extra();
    }

    private double widenGoal(Input input) {
        Motion motion = input.motion();
        double fast = SmartCameraMath.clamp((motion.speed() - 6.0) / 14.0, 0.0, 1.0);
        return Math.max(fast, motion.flying() ? 1.0 : 0.0);
    }

    private double wantedHeight(SmartCameraSettings settings) {
        return settings.height + LIFTS[this.liftChoice] + this.riderLift.value;
    }

    private Vec place(Input input) {
        double radians = Math.toRadians(this.angle);
        Vec wanted = this.centre.value().add(Math.sin(radians) * this.radius.value, this.height.value, -Math.cos(radians) * this.radius.value);
        this.lastEye = input.settings().avoidObstructions
            ? this.clearance.solve(input.world(), input.subject().aim(), wanted, input.subject().extra(), input.dt())
            : wanted;
        return this.lastEye;
    }

    private SmartCameraMath.Aim goal(Input input, Vec eye) {
        SmartCameraSettings settings = input.settings();
        // A craft and its rider: aimed between the middle of the craft and the rider
        Vec aim = input.subject().aim().lerp(input.rider(), 0.4);
        return SmartCameraMath.aimAt(eye.x(), eye.y(), eye.z(), aim.x(), aim.y(), aim.z(), settings.screenX, settings.screenY, input.fov(), input.aspect());
    }

    /**
     * Picks the largest circle, lowest first, that is clear here and along the arc ahead. The choice is kept while it
     * still fits, so the circle does not flicker between sizes; a bigger one is tried again now and then.
     */
    private void choose(Input input, boolean fresh) {
        SmartCameraSettings settings = input.settings();
        if (!settings.avoidObstructions) {
            this.radiusChoice = 0;
            this.liftChoice = 0;
            this.ahead = true;
            return;
        }
        if (!fresh && this.fits(input, this.radiusChoice, this.liftChoice, 0)) {
            // Still fits: keep it, only slowing down while the arc ahead is blocked, and now and then see whether a
            // bigger circle that is clear ahead would do
            this.ahead = this.clearAhead(input, this.radiusChoice, this.liftChoice);
            if (this.radiusChoice == 0 && this.liftChoice == 0 || this.steps % RETRY_EVERY != 0) {
                return;
            }
            for (int radiusIndex = 0; radiusIndex <= this.radiusChoice; radiusIndex++) {
                for (int liftIndex = 0; liftIndex < LIFTS.length; liftIndex++) {
                    if ((radiusIndex < this.radiusChoice || liftIndex < this.liftChoice) && this.fits(input, radiusIndex, liftIndex, 0)
                        && this.clearAhead(input, radiusIndex, liftIndex)) {
                        this.radiusChoice = radiusIndex;
                        this.liftChoice = liftIndex;
                        this.ahead = true;
                        return;
                    }
                }
            }
            return;
        }
        int firstRadius = -1;
        int firstLift = -1;
        for (int radiusIndex = 0; radiusIndex < RADII.length; radiusIndex++) {
            for (int liftIndex = 0; liftIndex < LIFTS.length; liftIndex++) {
                if (!this.fits(input, radiusIndex, liftIndex, 0)) {
                    continue;
                }
                if (this.clearAhead(input, radiusIndex, liftIndex)) {
                    this.radiusChoice = radiusIndex;
                    this.liftChoice = liftIndex;
                    this.ahead = true;
                    return;
                }
                if (firstRadius < 0) {
                    firstRadius = radiusIndex;
                    firstLift = liftIndex;
                }
            }
        }
        this.ahead = false;
        if (firstRadius >= 0) {
            this.radiusChoice = firstRadius;
            this.liftChoice = firstLift;
        } else if (fresh) {
            this.radiusChoice = RADII.length - 1;
            this.liftChoice = 0;
        }
        // Otherwise nothing fits at all: keep going as it is, and leave the line of sight to the clearance
    }

    private boolean clearAhead(Input input, int radiusIndex, int liftIndex) {
        return this.fits(input, radiusIndex, liftIndex, 1) && this.fits(input, radiusIndex, liftIndex, 3);
    }

    /** Whether the camera would have room and a clear view a few steps of arc ahead with this circle */
    private boolean fits(Input input, int radiusIndex, int liftIndex, int stepsAhead) {
        SmartCameraSettings settings = input.settings();
        double arcStep = SmartCameraMath.clamp(Math.abs(settings.degreesPerSecond) * 0.5, 5.0, 25.0) * Math.signum(settings.degreesPerSecond);
        double radians = Math.toRadians(this.angle + arcStep * stepsAhead);
        double r = this.radiusOf(settings, input.subject(), radiusIndex);
        Vec centre = this.centre.value();
        Vec eye = centre.add(Math.sin(radians) * r, settings.height + LIFTS[liftIndex], -Math.cos(radians) * r);
        if (input.world().solid(eye)) {
            return false;
        }
        // Clear from the middle of the circle, from the subject now and from where it will be in a moment
        Vec aim = input.subject().aim();
        for (Vec from : new Vec[]{centre, aim, aim.add(input.motion().velocity().scale(0.6))}) {
            if (Clearance.allowedShare(input.world(), from, eye, input.subject().extra()) < 1.0) {
                return false;
            }
        }
        return true;
    }

}
