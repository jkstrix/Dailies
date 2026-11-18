package comedymonkey.dailies.smartcamera;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Picks shots and cuts between them the way an editor would.
 * <ul>
 *     <li>A wide establishing shot whenever the subject comes into a new kind of place (the start, out of a cave,
 *     into one), then chase, over-the-shoulder, side tracking, tripod and orbit shots chosen from what the subject is
 *     doing (still, walking, running, flying, riding) and how much room there is.</li>
 *     <li>Shots last between the minimum and maximum length, shorter when the pace is high; cuts fall on action
 *     where there is some: a sharp turn, taking off, landing, getting on or off, going into or out of a tight space.</li>
 *     <li>Before cutting, each candidate shot is set up and checked: its view of the subject must be clear, it must
 *     look from at least 30 degrees away from the shot before (no jump cuts), and it must stay on the side of the
 *     subject's line of travel the edit has kept to so far (the 180-degree rule). A shot that cannot be checked
 *     good is not cut to.</li>
 *     <li>Never a shot under 1.5 seconds. Each shot's camera is one of the other solvers, smooth in its own right.</li>
 *     <li>All chance comes from the seed and the tick a cut falls on, so the same stretch always gives the same edit.</li>
 * </ul>
 */
public final class DirectorSolver implements ShotSolver {

    public enum Kind {
        ESTABLISHING, CHASE, OVER_SHOULDER, SIDE_TRACKING, TRIPOD, ORBIT;

        public String id() {
            return this.name().toLowerCase(Locale.ROOT);
        }
    }

    /** A shot of the edit so far; {@code endReason} is null for the current one */
    public record ShotRecord(int index, Kind kind, double startTick, double seconds, String startReason, String endReason) {
    }

    public static final String REASON_START = "start";
    public static final String REASON_MAX_DURATION = "max_duration";
    public static final String REASON_OBSTRUCTED = "obstructed";
    public static final String REASON_TOO_FAR = "too_far";
    public static final String REASON_SITUATION_CHANGED = "situation_changed";
    public static final String REASON_NEW_PLACE = "new_place";

    /** The shortest shot there may ever be, whatever the settings say */
    public static final double LEAST_SHOT_SECONDS = 1.5;
    /**
     * The shortest shot the director itself allows, in ticks: well above {@link #LEAST_SHOT_SECONDS}, because a cut is
     * seen from the first frame drawn after the tick before it, and the frames of an export need not fall on ticks
     */
    private static final int LEAST_SHOT_TICKS = 36;
    /** The smallest change of angle between two shots that is not a jump cut */
    public static final double LEAST_ANGLE_CHANGE = 30.0;
    private static final int MOST_HISTORY = 64;
    private static final int TRIES = 8;
    /** Seconds an action stays a reason to cut after it happened */
    private static final double ACTION_KEEPS = 1.0;
    /** Blocks either side of the line of action that count as on it */
    private static final double SIDE_NEUTRAL = 0.1;

    private ShotSolver solver;
    private SmartCameraSettings shotSettings;
    private Kind kind;
    private int index;
    private double startTick;
    private String startReason;
    private double age;
    private double planned;
    private Motion.Activity shotActivity;
    private double blockedSeconds;
    private double retryIn;
    private String pendingAction;
    private double pendingActionAge;
    private int lineSide;
    private double stillSeconds;
    private Pose lastPose;
    private double lastVisibility = 1.0;
    private final List<ShotRecord> history = new ArrayList<>();

    /** The camera before a jump forced this director to start again, so its first shot is no jump cut from it */
    private Pose before;

    public DirectorSolver after(Pose pose) {
        this.before = pose;
        return this;
    }

    @Override
    public Pose start(Input input) {
        this.history.clear();
        this.index = -1;
        this.lineSide = 0;
        this.stillSeconds = 0.0;
        this.pendingAction = null;
        Pose pose = this.cut(input, REASON_START, this.before, input.settings().establishingShots && this.before == null);
        if (pose == null && this.before != null) {
            pose = this.cut(input, REASON_START, null, false);
        }
        if (pose == null) {
            // Nothing could be checked good (the subject is walled in): a chase from behind, which pulls in as it must
            pose = this.begin(input, Kind.CHASE, chaseSettings(input.settings(), 1.0, 1.0, 0.0), new ChaseSolver().withoutCuts(), REASON_START);
        }
        return pose;
    }

    @Override
    public Pose step(Input input) {
        double dt = input.dt();
        // Counted in the recording's ticks, never summed from steps, so no step skipped or repeated shortens a shot
        this.age = (input.tick() - this.startTick) / 20.0;
        this.retryIn -= dt;
        Pose pose = this.solver.step(input.with(this.shotSettings, this.age));
        SubjectSample subject = input.subject();
        this.lastVisibility = this.seen(input, pose.eye());
        // Squeezed up against the subject (a small room) is as bad as blocked: nothing reads from half a block away
        boolean squeezed = pose.eye().distanceTo(subject.aim()) < 1.2 + subject.extra();
        this.blockedSeconds = this.lastVisibility < 0.5 || squeezed ? this.blockedSeconds + dt : 0.0;
        Motion motion = input.motion();
        this.stillSeconds = motion.moving() ? 0.0 : this.stillSeconds + dt;
        if (this.stillSeconds > 2.0) {
            // Standing still for a while: there is no line of travel to keep to
            this.lineSide = 0;
        }
        for (String event : motion.events()) {
            if (event.equals(Motion.EVENT_TURN)) {
                // A new direction draws a new line
                this.lineSide = 0;
            }
            this.pendingAction = event;
            this.pendingActionAge = 0.0;
        }
        if (this.pendingAction != null) {
            this.pendingActionAge += dt;
            if (this.pendingActionAge > ACTION_KEEPS) {
                this.pendingAction = null;
            }
        }

        String reason = this.decide(input);
        boolean mustGoNow = REASON_OBSTRUCTED.equals(reason) || REASON_TOO_FAR.equals(reason);
        if (reason != null && !mustGoNow && !motion.lineSteady() && this.age < Math.max(LEAST_SHOT_TICKS / 20.0, input.settings().maxShotSeconds) + 1.0) {
            // The subject is swinging round: wait the moment it takes for the line of action to settle
            reason = null;
        }
        if (reason != null && this.retryIn <= 0.0) {
            boolean mustGo = reason.equals(REASON_OBSTRUCTED) || reason.equals(REASON_TOO_FAR);
            boolean establishing = reason.equals(REASON_NEW_PLACE);
            Pose cut = this.cut(input, reason, pose, establishing);
            if (cut == null && mustGo) {
                cut = this.escape(input, reason, pose);
            }
            if (cut != null) {
                // Whatever happened has been cut on
                this.pendingAction = null;
                return cut;
            }
            // Nothing good to cut to now: stay, and look again in a moment
            this.retryIn = 0.25;
        }
        this.lastPose = pose;
        return pose;
    }

    private String decide(Input input) {
        SmartCameraSettings settings = input.settings();
        double least = Math.max(LEAST_SHOT_TICKS / 20.0, settings.minShotSeconds);
        if (this.age < LEAST_SHOT_TICKS / 20.0) {
            return null;
        }
        if (this.blockedSeconds > 0.3) {
            return REASON_OBSTRUCTED;
        }
        if (this.solver.wantsCut()) {
            return REASON_TOO_FAR;
        }
        if (this.age < least) {
            return null;
        }
        if (this.pendingAction != null) {
            if ((this.pendingAction.equals(Motion.EVENT_ENCLOSED) || this.pendingAction.equals(Motion.EVENT_OPEN))) {
                return settings.establishingShots ? REASON_NEW_PLACE : this.pendingAction;
            }
            return this.pendingAction;
        }
        if (input.motion().activity() != this.shotActivity) {
            return REASON_SITUATION_CHANGED;
        }
        if (this.age >= this.planned) {
            return REASON_MAX_DURATION;
        }
        return null;
    }

    /**
     * Sets up candidate shots and cuts to the first that checks good.
     *
     * @param current the pose of the shot being cut from, null at the start
     * @return the first pose of the new shot, or null when no candidate was good
     */
    private Pose cut(Input input, String reason, Pose current, boolean establishing) {
        SmartCameraSettings settings = input.settings();
        SmartCameraMath.Random random = new SmartCameraMath.Random(settings.seed, Math.round(input.tick()));
        Motion motion = input.motion();
        boolean enclosed = motion.enclosure() > 0.6;
        // The 180-degree rule: the new shot looks from the side of the line the camera is on now
        this.lineSide = current == null || current == this.before ? 0 : this.sideOf(input, current.eye());
        if (current != null && current != this.before && this.lastPose != null) {
            // Seen from the frames between this tick and the last: the camera must be on one side in both
            int earlier = this.sideOf(input, this.lastPose.eye());
            if (earlier != 0 && this.lineSide != 0 && earlier != this.lineSide) {
                // The camera is crossing the line itself right now: not the moment to cut
                return null;
            }
            if (this.lineSide == 0) {
                this.lineSide = earlier;
            }
            // Close to the line, which side the camera is on depends on just how the line is drawn: wait until it
            // is clearly on one side, unless the shot has to go
            if (!reason.equals(REASON_OBSTRUCTED) && !reason.equals(REASON_TOO_FAR) && Math.abs(this.lateral(input, current.eye())) < 1.0
                && this.age < input.settings().maxShotSeconds + 1.0) {
                return null;
            }
        }
        if (establishing) {
            Kind kind = Kind.ESTABLISHING;
            for (int attempt = 0; attempt < 3; attempt++) {
                Plan plan = enclosed ? this.wideTripod(input, random) : this.wideOrbit(input, random, current);
                Pose pose = this.tryPlan(input, plan, current, reason, kind);
                if (pose != null) {
                    return pose;
                }
            }
        }
        double[] weights = weights(settings, motion, enclosed);
        if (this.kind != null && this.kind != Kind.ESTABLISHING) {
            weights[this.kind.ordinal() - 1] *= 0.25;
        }
        for (int attempt = 0; attempt < TRIES; attempt++) {
            int picked = random.pickWeighted(weights);
            if (picked < 0) {
                picked = 0;
            }
            Kind kind = Kind.values()[picked + 1];
            Pose pose = this.tryPlan(input, this.plan(input, kind, random, current), current, reason, kind);
            if (pose != null) {
                return pose;
            }
        }
        return null;
    }

    /** When the shot has to go and nothing checks good: the candidate with the clearest view, whatever its angle */
    private Pose escape(Input input, String reason, Pose current) {
        SmartCameraMath.Random random = new SmartCameraMath.Random(input.settings().seed ^ 0x5EED, Math.round(input.tick()));
        Plan best = null;
        Pose bestPose = null;
        double bestSeen = 0.4;
        Vec aim = input.subject().aim();
        Kind[] kinds = {Kind.TRIPOD, Kind.ORBIT, Kind.SIDE_TRACKING, Kind.OVER_SHOULDER, Kind.CHASE};
        for (int attempt = 0; attempt < 2; attempt++) {
            for (Kind kind : kinds) {
                Plan plan = this.plan(input, kind, random, current);
                Pose pose = plan.solver.start(input.with(plan.settings, 0.0));
                double seen = this.seen(input, pose.eye());
                // Even in a hurry, never a jump cut, and never into the subject's face
                boolean turned = this.turnsFrom(current, pose, aim, LEAST_ANGLE_CHANGE + 2.0) && this.turnsFrom(this.lastPose, pose, aim, LEAST_ANGLE_CHANGE + 2.0);
                int side = this.sideOf(input, pose.eye());
                boolean sameSide = side == 0 || (current == null || this.sideOf(input, current.eye()) != -side)
                    && (this.lastPose == null || this.sideOf(input, this.lastPose.eye()) != -side);
                if (seen > bestSeen && turned && sameSide && pose.eye().distanceTo(aim) >= 1.2 + input.subject().extra() && CameraProbe.roomFor(input.world(), pose.eye())) {
                    best = plan;
                    bestPose = pose;
                    bestSeen = seen;
                }
            }
        }
        if (best == null) {
            return null;
        }
        this.finish(input, reason);
        return this.adopt(input, best, bestPose, reason);
    }

    /** How much a camera there sees of the subject, and of the rider past their own craft when a craft is framed */
    private double seen(Input input, Vec eye) {
        SubjectSample subject = input.subject();
        double seen = CameraProbe.visibility(input.world(), eye, subject.aim(), subject.height());
        if (input.rider().distanceTo(subject.aim()) > 0.5) {
            // The rider counts double: a balloon seen without who rides it is not the shot
            seen = Math.min(seen, input.riderSeen(eye) + 0.25);
        }
        return seen;
    }

    /**
     * Whether a cut from one pose to another turns the view far enough not to be a jump cut, both in the way the camera
     * looks and in the way it sees the subject. Checked against the camera of this tick and of the tick before, since a
     * frame between them is what the cut is seen from.
     */
    private boolean turnsFrom(Pose from, Pose to, Vec aim, double degrees) {
        if (from == null) {
            return true;
        }
        return Vec.angleBetween(aim.sub(from.eye()), aim.sub(to.eye())) >= degrees
            && Vec.angleBetween(Vec.look(from.yaw(), from.pitch()), Vec.look(to.yaw(), to.pitch())) >= degrees;
    }

    private Pose tryPlan(Input input, Plan plan, Pose current, String reason, Kind kind) {
        Pose pose = plan.solver.start(input.with(plan.settings, 0.0));
        SubjectSample subject = input.subject();
        Vec aim = subject.aim();
        CameraWorld world = input.world();
        if (!CameraProbe.roomFor(world, pose.eye()) || this.seen(input, pose.eye()) < 0.75
            || pose.eye().distanceTo(aim) < 1.5 + subject.extra()) {
            return null;
        }
        // No jump cut: the view must turn well past the least change, both the way it looks and the way it sees the subject
        if (!this.turnsFrom(current, pose, aim, LEAST_ANGLE_CHANGE + 5.0) || current != this.before && !this.turnsFrom(this.lastPose, pose, aim, LEAST_ANGLE_CHANGE + 5.0)) {
            return null;
        }
        int side = this.sideOf(input, pose.eye());
        if (this.lineSide != 0 && side == -this.lineSide) {
            return null;
        }
        if (current != null) {
            this.finish(input, reason);
        }
        return this.adopt(input, plan, pose, reason);
    }

    private Pose adopt(Input input, Plan plan, Pose pose, String reason) {
        int side = this.sideOf(input, pose.eye());
        if (this.lineSide == 0 && side != 0) {
            this.lineSide = side;
        }
        this.solver = plan.solver;
        this.shotSettings = plan.settings;
        this.kind = plan.kind;
        this.index++;
        this.startTick = input.tick();
        this.startReason = reason;
        this.age = 0.0;
        this.planned = this.length(input, plan.kind);
        this.shotActivity = input.motion().activity();
        this.blockedSeconds = 0.0;
        this.retryIn = 0.0;
        this.lastVisibility = this.seen(input, pose.eye());
        Pose cut = new Pose(pose.eye(), pose.yaw(), pose.pitch(), pose.obstructed(), pose.targetScreenX(), pose.targetScreenY(), true);
        this.lastPose = cut;
        return cut;
    }

    private Pose begin(Input input, Kind kind, SmartCameraSettings settings, ShotSolver solver, String reason) {
        Plan plan = new Plan(kind, settings, solver);
        return this.adopt(input, plan, solver.start(input.with(settings, 0.0)), reason);
    }

    private void finish(Input input, String reason) {
        if (this.kind == null) {
            return;
        }
        this.history.add(new ShotRecord(this.index, this.kind, this.startTick, this.age, this.startReason, reason));
        while (this.history.size() > MOST_HISTORY) {
            this.history.remove(0);
        }
    }

    /**
     * Which side of the subject's line of action a camera there is on: 1, -1, or 0 within a quarter of a block of the
     * line. The line runs the way the subject travels, or the way it faces when nearly still.
     */
    private double lateral(Input input, Vec eye) {
        Vec along = lineAlong(input);
        Vec offset = eye.sub(input.subject().aim());
        return along.x() * offset.z() - along.z() * offset.x();
    }

    private int sideOf(Input input, Vec eye) {
        double lateral = this.lateral(input, eye);
        return Math.abs(lateral) <= SIDE_NEUTRAL ? 0 : lateral > 0 ? 1 : -1;
    }

    /** The line of action as the films judge it: where the subject went over the last half second, or the way it faces */
    private static Vec lineAlong(Input input) {
        return input.motion().lineOfAction(input.subject().bodyYaw());
    }

    /** How long a shot is meant to last: shorter at a higher pace, varied a little */
    private double length(Input input, Kind kind) {
        SmartCameraSettings settings = input.settings();
        double least = Math.max(LEAST_SHOT_TICKS / 20.0, settings.minShotSeconds);
        double most = Math.max(least, settings.maxShotSeconds);
        Motion motion = input.motion();
        double pace = SmartCameraMath.clamp(motion.speed() / 10.0, 0.0, 1.0);
        if (motion.activity() == Motion.Activity.FLYING || motion.activity() == Motion.Activity.RIDING) {
            pace = Math.max(pace, 0.6);
        }
        SmartCameraMath.Random random = new SmartCameraMath.Random(settings.seed + 17, Math.round(input.tick()));
        double length = (most - (most - least) * pace) * random.range(0.85, 1.15);
        if (kind == Kind.ESTABLISHING) {
            length = Math.max(length, 4.0);
        }
        return SmartCameraMath.clamp(length, least, most);
    }

    // ------------------------------------------------------------------ The kinds of shot

    private record Plan(Kind kind, SmartCameraSettings settings, ShotSolver solver) {
    }

    /** Chase, over the shoulder, side tracking, tripod, orbit */
    private static double[] weights(SmartCameraSettings settings, Motion motion, boolean enclosed) {
        double[] weights;
        if (enclosed) {
            // No room to the side or round about in a tight space: those would only look at rock
            weights = new double[]{3.0, 2.5, 0.0, 1.5, 0.0};
        } else {
            weights = switch (motion.activity()) {
                case FLYING -> new double[]{3.0, 0.3, 2.0, 2.0, 0.5};
                case RIDING -> new double[]{3.0, 0.8, 3.0, 2.0, 1.0};
                case RUNNING -> new double[]{3.0, 1.5, 2.5, 2.0, 0.5};
                case STILL -> new double[]{1.0, 2.0, 0.5, 2.0, 3.0};
                default -> new double[]{2.5, 2.0, 1.5, 2.0, 1.0};
            };
        }
        if (!settings.allowChase) weights[0] = 0.0;
        if (!settings.allowOverShoulder) weights[1] = 0.0;
        if (!settings.allowSideTracking) weights[2] = 0.0;
        if (!settings.allowTripod) weights[3] = 0.0;
        if (!settings.allowOrbit) weights[4] = 0.0;
        double total = 0.0;
        for (double weight : weights) {
            total += weight;
        }
        if (total <= 0.0) {
            weights[0] = 1.0;
        }
        return weights;
    }

    /** A side angle that puts a chase-like camera on the side of the line the edit keeps to (or a random one) */
    private int wantedSign(SmartCameraMath.Random random) {
        // A positive side angle puts the camera on the negative side of the line of travel (see Vec.side)
        if (this.lineSide != 0) {
            return -this.lineSide;
        }
        return random.nextBoolean() ? 1 : -1;
    }

    private Plan plan(Input input, Kind kind, SmartCameraMath.Random random, Pose current) {
        SmartCameraSettings base = input.settings();
        Motion motion = input.motion();
        switch (kind) {
            case OVER_SHOULDER -> {
                double angle = random.range(15.0, 25.0) * this.wantedSign(random);
                SmartCameraSettings settings = chaseSettings(base, 1.0, 1.0, angle);
                settings.distance = (float) Math.min(base.distance, 2.2);
                settings.height = 0.45f;
                // The subject sits on the side it is seen from, with the way ahead open beside it
                settings.screenX = (float) (0.3 * Math.signum(angle));
                settings.leadRoom = Math.max(base.leadRoom, 0.35f);
                return new Plan(kind, settings, new ChaseSolver().withoutCuts());
            }
            case SIDE_TRACKING -> {
                double angle = random.range(75.0, 100.0) * this.wantedSign(random);
                SmartCameraSettings settings = chaseSettings(base, random.range(1.0, 1.4), 0.5, angle);
                settings.headingSource = SmartCameraHeading.MOVEMENT;
                return new Plan(kind, settings, new ChaseSolver().withoutCuts());
            }
            case TRIPOD -> {
                SmartCameraSettings settings = base.copy();
                settings.mode = SmartCameraMode.TRIPOD;
                settings.autoPlace = true;
                TripodSolver tripod = new TripodSolver().stayPut().keepTo(this.lineSide, lineAlong(input), random.range(7.0, 11.0));
                return new Plan(kind, settings, tripod);
            }
            case ORBIT -> {
                SmartCameraSettings settings = base.copy();
                settings.mode = SmartCameraMode.ORBIT;
                settings.radius = (float) (base.radius * random.range(0.8, 1.3));
                settings.height = (float) (base.height * random.range(0.6, 1.2));
                settings.degreesPerSecond = (float) (random.range(10.0, 18.0) * (random.nextBoolean() ? 1.0 : -1.0));
                settings.startAngle = (float) this.awayFrom(input, current, random);
                return new Plan(kind, settings, new OrbitSolver().withoutCuts());
            }
            default -> {
                // A little off the line, so that which side of it the camera is on is never in doubt
                double angle = random.range(12.0, 28.0) * this.wantedSign(random);
                SmartCameraSettings settings = chaseSettings(base, random.range(0.9, 1.3), random.range(0.7, 1.3), angle);
                return new Plan(Kind.CHASE, settings, new ChaseSolver().withoutCuts());
            }
        }
    }

    private Plan wideOrbit(Input input, SmartCameraMath.Random random, Pose current) {
        SmartCameraSettings base = input.settings();
        SmartCameraSettings settings = base.copy();
        settings.mode = SmartCameraMode.ORBIT;
        settings.radius = (float) Math.max(16.0, base.radius * 2.2);
        settings.height = (float) Math.max(6.0, base.height * 3.0);
        settings.degreesPerSecond = (float) (6.0 * (random.nextBoolean() ? 1.0 : -1.0));
        settings.startAngle = (float) this.awayFrom(input, current, random);
        settings.leadRoom = 0.0f;
        return new Plan(Kind.ESTABLISHING, settings, new OrbitSolver().withoutCuts());
    }

    private Plan wideTripod(Input input, SmartCameraMath.Random random) {
        SmartCameraSettings settings = input.settings().copy();
        settings.mode = SmartCameraMode.TRIPOD;
        settings.autoPlace = true;
        TripodSolver tripod = new TripodSolver().stayPut().keepTo(this.lineSide, lineAlong(input), random.range(11.0, 14.0));
        return new Plan(Kind.ESTABLISHING, settings, tripod);
    }

    /** An orbit's start angle well away from where the camera is now, so the cut is not a jump cut */
    private double awayFrom(Input input, Pose current, SmartCameraMath.Random random) {
        Vec aim = input.subject().aim();
        double now;
        if (current != null) {
            Vec offset = current.eye().sub(aim);
            now = Math.toDegrees(Math.atan2(offset.x(), -offset.z()));
        } else {
            now = input.motion().travelHeading();
        }
        return now + random.range(50.0, 140.0) * (random.nextBoolean() ? 1.0 : -1.0);
    }

    private static SmartCameraSettings chaseSettings(SmartCameraSettings base, double distanceScale, double heightScale, double sideAngle) {
        SmartCameraSettings settings = base.copy();
        settings.mode = SmartCameraMode.CHASE;
        settings.distance = (float) (base.distance * distanceScale);
        settings.height = (float) (base.height * heightScale);
        settings.sideAngle = (float) sideAngle;
        settings.headingSource = SmartCameraHeading.MOVEMENT;
        return settings;
    }

    // ------------------------------------------------------------------ What it did

    public Kind kind() {
        return this.kind;
    }

    public int index() {
        return this.index;
    }

    public double startTick() {
        return this.startTick;
    }

    public String startReason() {
        return this.startReason;
    }

    public double age() {
        return this.age;
    }

    /** How much of the subject the current shot sees, 0 to 1 */
    public double visibility() {
        return this.lastVisibility;
    }

    /** The edit so far, oldest first, the current shot last */
    public List<ShotRecord> history() {
        List<ShotRecord> all = new ArrayList<>(this.history);
        if (this.kind != null) {
            all.add(new ShotRecord(this.index, this.kind, this.startTick, this.age, this.startReason, null));
        }
        return all;
    }

}
