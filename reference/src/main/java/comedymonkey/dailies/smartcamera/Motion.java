package comedymonkey.dailies.smartcamera;

import java.util.ArrayList;
import java.util.List;

/**
 * What the subject is doing, worked out step by step from where it is: its smoothed velocity, the heading a chase
 * camera stays behind, whether it walks, runs, flies or rides, how enclosed the space around it is, and the moments
 * of action an editor cuts on (a sharp change of direction, taking off, landing, getting on or off something, going
 * into or out of a tight space).
 */
public final class Motion {

    public enum Activity {
        STILL, WALKING, RUNNING, FLYING, RIDING, SWIMMING;

        public String id() {
            return this.name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public static final String EVENT_TURN = "direction_change";
    public static final String EVENT_TAKE_OFF = "take_off";
    public static final String EVENT_LANDING = "landing";
    public static final String EVENT_MOUNT = "mount";
    public static final String EVENT_DISMOUNT = "dismount";
    public static final String EVENT_ENCLOSED = "enter_enclosed";
    public static final String EVENT_OPEN = "leave_enclosed";

    private static final int HISTORY = 20;
    /** Steps between looks at how enclosed the space is; it changes slowly and costs a dozen rays */
    private static final int ENCLOSURE_EVERY = 4;

    private Vec lastFeet = Vec.ZERO;
    private Vec velocity = Vec.ZERO;
    private boolean moving = false;
    private double airborneSeconds = 0.0;
    private boolean flightState = false;
    private boolean ridingState = false;
    private final HeadingFilter travel = new HeadingFilter();
    private final HeadingFilter body = new HeadingFilter();
    private final Spring climb = new Spring();
    private double rawTravelYaw = 0.0;
    private Activity activity = Activity.STILL;
    private Activity pendingActivity = Activity.STILL;
    private double pendingSeconds = 0.0;
    private double enclosure = 0.0;
    private boolean enclosed = false;
    private int steps = 0;
    private final double[] headingHistory = new double[HISTORY];
    private final boolean[] movingHistory = new boolean[HISTORY];
    private double sinceTurn = 99.0;
    private double sinceReversal = 99.0;
    private final List<String> events = new ArrayList<>();
    /** Where the aim point has been, newest last: the way the subject came, which a camera can follow through doors */
    private static final int TRAIL = 120;
    private final Vec[] trail = new Vec[TRAIL];
    private int trailCount = 0;
    private int trailNext = 0;

    public void reset(SubjectSample subject, Vec velocityHint, CameraWorld world) {
        this.lastFeet = subject.feet();
        // A hint measured across a teleport is no speed at all
        this.velocity = velocityHint.length() > 100.0 ? Vec.ZERO : velocityHint;
        double speed = this.velocity.length();
        this.moving = speed > 0.8;
        this.airborneSeconds = 0.0;
        this.flightState = subject.gliding() || subject.flying();
        this.ridingState = subject.riding();
        double heading = this.velocity.horizontalLength() > 1.5 ? SmartCameraMath.yawOf(this.velocity.x(), this.velocity.z()) : subject.bodyYaw();
        this.rawTravelYaw = heading;
        this.travel.reset(heading);
        this.body.reset(subject.bodyYaw());
        this.climb.reset(0.0);
        this.activity = this.classify(subject);
        this.pendingActivity = this.activity;
        this.pendingSeconds = 0.0;
        this.enclosure = 1.0 - CameraProbe.openness(world, subject.aim(), 10.0);
        this.enclosed = this.enclosure > 0.7;
        this.steps = 0;
        java.util.Arrays.fill(this.headingHistory, heading);
        java.util.Arrays.fill(this.movingHistory, false);
        this.sinceTurn = 99.0;
        this.events.clear();
        this.trailCount = 0;
        this.trailNext = 0;
        this.remember(subject.aim());
    }

    private void remember(Vec aim) {
        if (this.trailCount > 0 && this.trail[(this.trailNext + TRAIL - 1) % TRAIL].distanceTo(aim) < 0.05) {
            // Standing still adds nothing to the way
            return;
        }
        this.trail[this.trailNext] = aim;
        this.trailNext = (this.trailNext + 1) % TRAIL;
        this.trailCount = Math.min(TRAIL, this.trailCount + 1);
    }

    /**
     * The point on the subject's way {@code distance} blocks back from where it is now, measured along the way; the
     * furthest point remembered when the way is shorter. Turning right round starts the way afresh.
     */
    public Vec trailPoint(Vec now, double distance) {
        Vec here = now;
        double left = distance;
        for (int i = 1; i <= this.trailCount; i++) {
            Vec back = this.trail[(this.trailNext - i + TRAIL * 2) % TRAIL];
            double segment = here.distanceTo(back);
            if (segment >= left) {
                return here.lerp(back, segment < 1.0e-9 ? 0.0 : left / segment);
            }
            left -= segment;
            here = back;
        }
        return here;
    }

    /**
     * @param deadBand degrees of turn the chase heading ignores
     */
    public void step(SubjectSample subject, CameraWorld world, double deadBand, double dt) {
        this.events.clear();
        this.steps++;
        if (dt <= 0.0) {
            return;
        }
        this.remember(subject.aim());
        Vec feet = subject.feet();
        Vec measured = feet.sub(this.lastFeet).scale(1.0 / dt);
        this.lastFeet = feet;
        this.velocity = this.velocity.lerp(measured, SmartCameraMath.blendFactor(0.2, dt));
        double horizontal = this.velocity.horizontalLength();
        double speed = this.velocity.length();
        this.moving = this.moving ? speed > 0.4 : speed > 1.2;
        this.airborneSeconds = subject.airborne() ? this.airborneSeconds + dt : 0.0;

        // The way of travel only means something above a slow walk
        double confidence = SmartCameraMath.clamp((horizontal - 0.6) / 1.2, 0.0, 1.0);
        if (horizontal > 0.3) {
            this.rawTravelYaw = SmartCameraMath.yawOf(this.velocity.x(), this.velocity.z());
        }
        boolean flight = subject.gliding() || subject.flying() || this.airborneSeconds > 1.0;
        // Slow enough that a sharp turn swings the camera round over most of a second, never with a jolt
        this.travel.step(this.rawTravelYaw, confidence, deadBand, 0.6, dt);
        this.body.step(subject.bodyYaw(), 1.0, Math.max(5.0, deadBand * 0.5), 0.3, dt);
        double climbing = flight && speed > 2.0 ? Math.toDegrees(Math.atan2(this.velocity.y(), Math.max(horizontal, 1.0e-3))) : 0.0;
        this.climb.step(SmartCameraMath.clamp(climbing, -70.0, 70.0), 0.5, dt);

        // Moments of action
        if (flight != this.flightState) {
            this.events.add(flight ? EVENT_TAKE_OFF : EVENT_LANDING);
            this.flightState = flight;
        }
        if (subject.riding() != this.ridingState) {
            this.events.add(subject.riding() ? EVENT_MOUNT : EVENT_DISMOUNT);
            this.ridingState = subject.riding();
        }
        int slot = this.steps % HISTORY;
        double headingSecondAgo = this.headingHistory[slot];
        boolean movingSecondAgo = this.movingHistory[slot];
        this.headingHistory[slot] = this.rawTravelYaw;
        this.movingHistory[slot] = this.moving && horizontal > 1.5;
        this.sinceTurn += dt;
        double turned = Math.abs(SmartCameraMath.wrapDegrees(this.rawTravelYaw - headingSecondAgo));
        this.sinceReversal += dt;
        if (this.moving && horizontal > 1.5 && movingSecondAgo && turned > 120.0 && this.trailCount > 1 && this.sinceReversal > 1.2) {
            this.sinceReversal = 0.0;
            // Going back the way it came: behind it now is the way ahead of before, so the way starts again from
            // where it turned (the furthest point of the last second and a half)
            int recent = Math.min(this.trailCount, 30);
            Vec[] kept = new Vec[recent];
            Vec newest = this.trail[(this.trailNext + TRAIL - 1) % TRAIL];
            int apex = 0;
            double furthest = -1.0;
            for (int i = 0; i < recent; i++) {
                kept[i] = this.trail[(this.trailNext - 1 - i + TRAIL * 2) % TRAIL];
                double away = kept[i].distanceTo(newest);
                if (away > furthest) {
                    furthest = away;
                    apex = i;
                }
            }
            this.trailCount = 0;
            this.trailNext = 0;
            for (int i = apex; i >= 0; i--) {
                this.remember(kept[i]);
            }
        }
        if (this.moving && horizontal > 1.5 && movingSecondAgo && this.sinceTurn > 1.5 && turned > 60.0) {
            this.events.add(EVENT_TURN);
            this.sinceTurn = 0.0;
        }
        if (this.steps % ENCLOSURE_EVERY == 0) {
            double now = 1.0 - CameraProbe.openness(world, subject.aim(), 10.0);
            this.enclosure += (now - this.enclosure) * SmartCameraMath.blendFactor(0.6, dt * ENCLOSURE_EVERY);
            if (!this.enclosed && this.enclosure > 0.7) {
                this.enclosed = true;
                this.events.add(EVENT_ENCLOSED);
            } else if (this.enclosed && this.enclosure < 0.5) {
                this.enclosed = false;
                this.events.add(EVENT_OPEN);
            }
        }

        // A hop or a single slow step is not a new activity: it has to last
        Activity now = this.classify(subject);
        if (now == this.activity) {
            this.pendingSeconds = 0.0;
        } else if (now == this.pendingActivity) {
            this.pendingSeconds += dt;
            if (this.pendingSeconds > 0.5) {
                this.activity = now;
                this.pendingSeconds = 0.0;
            }
        } else {
            this.pendingActivity = now;
            this.pendingSeconds = dt;
        }
    }

    private Activity classify(SubjectSample subject) {
        double speed = this.velocity.length();
        if (subject.riding()) {
            return Activity.RIDING;
        }
        if (subject.gliding() || subject.flying() || this.airborneSeconds > 1.0) {
            return Activity.FLYING;
        }
        if (subject.swimming()) {
            return Activity.SWIMMING;
        }
        if (!this.moving) {
            return Activity.STILL;
        }
        return speed > 5.0 ? Activity.RUNNING : Activity.WALKING;
    }

    public Vec velocity() {
        return this.velocity;
    }

    public double speed() {
        return this.velocity.length();
    }

    public double horizontalSpeed() {
        return this.velocity.horizontalLength();
    }

    public boolean moving() {
        return this.moving;
    }

    /** The filtered way of travel (the body's way when nearly still), not wrapped */
    public double travelHeading() {
        return this.travel.value();
    }

    /** The filtered way the body faces, not wrapped */
    public double bodyHeading() {
        return this.body.value();
    }

    /** The way of travel this step, unfiltered */
    public double rawTravelYaw() {
        return this.rawTravelYaw;
    }

    /** Degrees above level the subject climbs (negative: dives), smoothed; 0 on the ground */
    public double climb() {
        return this.climb.value;
    }

    public boolean flying() {
        return this.flightState;
    }

    public Activity activity() {
        return this.activity;
    }

    /** 0 open air, 1 a tight tunnel */
    public double enclosure() {
        return this.enclosure;
    }

    public boolean enclosed() {
        return this.enclosed;
    }

    /** Faster than a run, riding or flying */
    public boolean fast() {
        return this.speed() > 6.0 || this.activity == Activity.RIDING || this.activity == Activity.FLYING;
    }

    /** The moments of action of the last step */
    public List<String> events() {
        return this.events;
    }

}
