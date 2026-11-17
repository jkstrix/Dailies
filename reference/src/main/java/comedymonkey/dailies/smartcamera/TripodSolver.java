package comedymonkey.dailies.smartcamera;

/**
 * Stands still and pans and tilts after the subject.
 * <p>
 * With {@link SmartCameraSettings#autoPlace} it picks its own place for the stretch: candidates around where the
 * subject is going are tried against its predicted path, for a clear view of all of it (no leaves or walls in the
 * way), a sensible distance, a little above eye level, off to the side of the path rather than in it, and a pan that
 * does not whip round. When the subject has left its view for good, it moves by a cut to a new place.
 * <p>
 * It turns on springs with a dead zone: while the subject stays within the zone round its place in the frame, the
 * camera does not move, so small steps and fidgets don't swing it.
 */
public final class TripodSolver implements ShotSolver {

    /** Seconds ahead along the subject's way the place is chosen for */
    private static final double[] PREDICT = {0.0, 0.75, 1.5, 2.5, 3.5};
    private static final double[] RINGS = {6.0, 9.0, 13.0};
    private static final int AROUND = 16;
    /** Seconds the subject may be out of view before a placed tripod gives up on its place */
    private static final double GIVE_UP_SECONDS = 0.6;
    private static final double LEAST_STAY_SECONDS = 1.0;

    // A tripod's pans start and stop gently: it stands still a lot, and a pan from rest must not start with a jolt
    private final Aimer aimer = new Aimer().turnChange(160.0);
    private Vec eye = Vec.ZERO;
    private double goalYaw;
    private double goalPitch;
    private double hiddenSeconds;
    private double stayedSeconds;
    private double range;
    private boolean placeItself = true;
    /** Which side of the subject's way a placed tripod must keep to (the director's 180-degree line): 1, -1, or 0 for either */
    private int keepSide = 0;
    private Vec sideAlong = Vec.ZERO;
    private double preferredDistance = 0.0;
    private double lastVisibility = 1.0;

    /** For the director: a placed tripod on one side of the subject's line of travel, or a wide one */
    public TripodSolver keepTo(int side, Vec along, double distance) {
        this.keepSide = side;
        this.sideAlong = along;
        this.preferredDistance = distance;
        return this;
    }

    /** For the director, whose tripods never move by themselves: it cuts away instead */
    public TripodSolver stayPut() {
        this.placeItself = false;
        return this;
    }

    public Vec eye() {
        return this.eye;
    }

    public double visibility() {
        return this.lastVisibility;
    }

    @Override
    public Pose start(Input input) {
        SmartCameraSettings settings = input.settings();
        this.eye = settings.autoPlace ? this.place(input) : settings.position();
        this.settle(input);
        return new Pose(this.eye, this.aimer.yaw(), this.aimer.pitch(), this.lastVisibility < 1.0, settings.screenX, settings.screenY, true);
    }

    private void settle(Input input) {
        Vec aim = input.subject().aim();
        this.range = Math.max(24.0, this.eye.distanceTo(aim) * 2.5) + input.subject().extra();
        SmartCameraMath.Aim goal = this.target(input);
        this.goalYaw = goal.yaw();
        this.goalPitch = goal.pitch();
        this.lastWantedYaw = goal.yaw();
        this.crossingRate = 0.0;
        this.aimer.reset(goal);
        this.hiddenSeconds = 0.0;
        this.stayedSeconds = 0.0;
        this.lastVisibility = CameraProbe.visibility(input.world(), this.eye, aim, input.subject().height());
    }

    @Override
    public Pose step(Input input) {
        double dt = input.dt();
        SmartCameraSettings settings = input.settings();
        Vec aim = input.subject().aim();
        this.stayedSeconds += dt;
        this.lastVisibility = CameraProbe.visibility(input.world(), this.eye, aim, input.subject().height());
        SmartCameraMath.ScreenPoint seen = SmartCameraMath.project(this.eye.x(), this.eye.y(), this.eye.z(), this.aimer.yaw(), this.aimer.pitch(),
            aim.x(), aim.y(), aim.z(), input.fov(), input.aspect());
        Vec soon = aim.add(input.motion().velocity().scale(0.75));
        this.comingInto = input.motion().moving() && soon.distanceTo(this.eye) < 3.0 + input.subject().extra() && soon.distanceTo(this.eye) < this.eye.distanceTo(aim);
        boolean lost = this.lastVisibility < 0.5 || !seen.inFront() || Math.abs(seen.x()) > 1.0 || Math.abs(seen.y()) > 1.0;
        this.hiddenSeconds = lost ? this.hiddenSeconds + dt : 0.0;

        if (settings.autoPlace && this.placeItself && this.stayedSeconds >= LEAST_STAY_SECONDS && this.wantsCut()) {
            this.eye = this.place(input);
            this.settle(input);
            return new Pose(this.eye, this.aimer.yaw(), this.aimer.pitch(), this.lastVisibility < 1.0, settings.screenX, settings.screenY, true);
        }

        // The goal only moves once the subject has wandered out of the dead zone round its place, and then only by
        // as much as it is out: in between, the camera stands still
        SmartCameraMath.Aim wanted = this.target(input);
        double halfHeight = input.fov() / 2.0;
        double halfWidth = Math.toDegrees(Math.atan(Math.tan(Math.toRadians(halfHeight)) * input.aspect()));
        // The dead zone is for small moves: a subject crossing quickly is followed all the way, or the camera would
        // stand, then whip round to catch up
        double crossing = Math.abs(SmartCameraMath.wrapDegrees(wanted.yaw() - this.lastWantedYaw)) / Math.max(dt, 1.0e-6);
        this.lastWantedYaw = wanted.yaw();
        this.crossingRate += (crossing - this.crossingRate) * SmartCameraMath.blendFactor(0.3, dt);
        double zoneShare = SmartCameraMath.clamp(1.0 - this.crossingRate / 25.0, 0.0, 1.0);
        double zoneYaw = settings.deadZone * halfWidth * zoneShare;
        double zonePitch = settings.deadZone * halfHeight * zoneShare;
        // A soft edge: inside the zone the goal hardly moves, outside it follows by as much as the subject is out,
        // and in between it eases from one to the other, so a pan never starts or stops in a step
        this.goalYaw += softZone(SmartCameraMath.wrapDegrees(wanted.yaw() - this.goalYaw), zoneYaw);
        this.goalPitch += softZone(wanted.pitch() - this.goalPitch, zonePitch);
        this.aimer.step(this.goalYaw, this.goalPitch, Math.max(0.05, settings.aimSmoothing), input.fov(), input.aspect(), dt);
        return new Pose(this.eye, this.aimer.yaw(), this.aimer.pitch(), this.lastVisibility < 1.0, settings.screenX, settings.screenY, false);
    }

    /** How far the goal moves for the subject being {@code off} from it, with a dead zone of {@code zone} whose edge is soft */
    static double softZone(double off, double zone) {
        if (zone < 1.0e-6) {
            return off;
        }
        return off - zone * Math.tanh(off / zone);
    }

    /** Whether the subject has left this place's view for good: hidden or out of frame for a while, or too far off */
    @Override
    public boolean wantsCut() {
        return this.hiddenSeconds > GIVE_UP_SECONDS || this.distanceNow > this.range || this.comingInto;
    }

    /** Whether the subject is about to walk into the camera (in a tunnel there may be nowhere else to stand) */
    private boolean comingInto = false;

    private double distanceNow = 0.0;
    private double lastWantedYaw = 0.0;
    private double crossingRate = 0.0;

    private SmartCameraMath.Aim target(Input input) {
        SmartCameraSettings settings = input.settings();
        Vec aim = input.subject().aim();
        this.distanceNow = this.eye.distanceTo(aim);
        Vec look = aim.add(Aimer.lead(input.motion().velocity(), settings.leadRoom, this.distanceNow, input.fov(), input.aspect(), !input.motion().flying()));
        return SmartCameraMath.aimAt(this.eye.x(), this.eye.y(), this.eye.z(), look.x(), look.y(), look.z(), settings.screenX, settings.screenY, input.fov(),
            input.aspect());
    }

    // ------------------------------------------------------------------ Choosing a place

    /**
     * The best place to stand for the next few seconds of the subject's way. Deterministic: the same subject, way and
     * world always give the same place.
     */
    Vec place(Input input) {
        Motion motion = input.motion();
        return placeFor(input.world(), input.subject(), motion.velocity(), motion.travelHeading(), motion.moving(), this.preferredDistance, this.keepSide,
            this.sideAlong);
    }

    /**
     * The best place to stand to watch a subject for the next few seconds of its way, as {@link #place}; also used by
     * the follow entity keyframe's "stay and watch" when its subject has gone out of its sight.
     *
     * @param preferredDistance blocks away, or 0 for the usual
     * @param keepSide          the side of the line of travel to keep to (1, -1), or 0 for either
     */
    public static Vec placeFor(CameraWorld world, SubjectSample subject, Vec velocity, double travelHeading, boolean moving, double preferredDistance,
                               int keepSide, Vec sideAlong) {
        Vec aim = subject.aim();
        double speed = velocity.length();
        // No further ahead than about thirty blocks, however fast
        double reach = speed * PREDICT[PREDICT.length - 1];
        double scale = reach > 30.0 ? 30.0 / reach : 1.0;
        Vec[] path = new Vec[PREDICT.length];
        for (int i = 0; i < PREDICT.length; i++) {
            path[i] = aim.add(velocity.scale(PREDICT[i] * scale));
        }
        Vec middle = path[2];
        double grow = 1.0 + SmartCameraMath.clamp(speed / 12.0, 0.0, 1.0) + subject.extra() * 0.15;
        double ideal = (preferredDistance > 0.0 ? preferredDistance : 8.5) * grow + subject.extra();
        double eyeLevel = subject.feet().y() + 1.62;

        Vec best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        Vec fallback = null;
        double fallbackSeen = -1.0;
        for (double ring : RINGS) {
            double away = ring * grow + subject.extra();
            for (int i = 0; i < AROUND; i++) {
                double angle = i * 360.0 / AROUND + (ring == RINGS[1] ? 360.0 / AROUND / 2.0 : 0.0);
                Vec level = middle.add(Vec.heading(angle).scale(away));
                // A little above eye level: above the ground where it stands, or above the subject in the air. The
                // ground is looked for from open air at the subject's eye level (or a little above, on a rise)
                Vec probe = level.withY(eyeLevel);
                for (int up = 0; up < 6 && world.solid(probe); up++) {
                    probe = probe.add(0.0, 1.0, 0.0);
                }
                if (world.solid(probe)) {
                    continue;
                }
                double ground = CameraProbe.groundBelow(world, probe, 14.0);
                double y = Double.isNaN(ground) ? Math.max(aim.y(), eyeLevel) + 1.0 : Math.max(ground + 1.62 + 0.7, Math.min(eyeLevel + 0.8, ground + 6.0));
                Vec candidate = level.withY(y);
                double ceiling = CameraProbe.ceilingAbove(world, probe, Math.max(0.0, y - probe.y()) + 0.6);
                if (!Double.isNaN(ceiling)) {
                    candidate = candidate.withY(Math.max(probe.y(), ceiling - 0.6));
                }
                if (!CameraProbe.roomFor(world, candidate)) {
                    continue;
                }
                int seen = 0;
                boolean seesNow = false;
                for (int k = 0; k < path.length; k++) {
                    double length = candidate.distanceTo(path[k]);
                    if (CameraProbe.freeWide(world, candidate, path[k], 0.15) >= length - 0.5) {
                        seen++;
                        if (k == 0) {
                            seesNow = true;
                        }
                    }
                }
                double seenShare = seen / (double) path.length;
                if (seesNow && seenShare > fallbackSeen) {
                    fallbackSeen = seenShare;
                    fallback = candidate;
                }
                if (!seesNow || seenShare < 0.6) {
                    continue;
                }
                double score = seenShare * 4.0;
                // A sensible distance over the whole stretch, not just now
                double distance = 0.0;
                for (Vec point : path) {
                    distance += candidate.distanceTo(point) / path.length;
                }
                score -= Math.abs(distance - ideal) / ideal * 2.0;
                // Standing in the subject's way makes it walk into the lens
                // Nor so close to it that a fast subject whips past: the pan stays under about a hundred degrees a second
                double offPath = distanceToPath(candidate, path);
                if (offPath < Math.max(2.5, speed * 0.6) + subject.extra()) {
                    continue;
                }
                // A pan of more than about a right angle over the stretch whips round
                double sweep = Vec.angleBetween(path[0].sub(candidate).withY(0.0), path[path.length - 1].sub(candidate).withY(0.0));
                score -= Math.max(0.0, sweep - 80.0) / 40.0;
                // Seeing the subject come towards the camera and pass reads better than watching it walk away
                if (speed > 1.0) {
                    double towards = velocity.withY(0.0).normalize().dot(candidate.sub(path[0]).withY(0.0).normalize());
                    score += towards * 0.6;
                }
                score -= Math.abs(candidate.y() - (eyeLevel + 0.8)) * 0.15;
                if (keepSide != 0) {
                    // Clearly on the side of the line the edit keeps to
                    Vec offset = candidate.sub(path[0]);
                    double lateral = sideAlong.x() * offset.z() - sideAlong.z() * offset.x();
                    if (lateral * keepSide < 0.5) {
                        continue;
                    }
                }
                // Ties are broken by where in the ring the place is, which is the same every time
                score -= i * 1.0e-6;
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
        }
        if (best != null) {
            return best;
        }
        if (fallback != null) {
            return fallback;
        }
        // Nowhere sees the subject: ahead on its way and a little above, as far as there is room, so it comes towards the camera
        Vec back = aim.add(Vec.heading(travelHeading).scale((moving ? 8.0 : -5.0) - subject.extra())).add(0.0, 1.0, 0.0);
        double share = Clearance.allowedShare(world, aim, back, subject.extra());
        return CameraProbe.pushOut(world, aim.add(back.sub(aim).scale(share)), aim);
    }

    private static double distanceToPath(Vec point, Vec[] path) {
        double nearest = Double.MAX_VALUE;
        for (int i = 0; i + 1 < path.length; i++) {
            Vec a = path[i].withY(0.0);
            Vec b = path[i + 1].withY(0.0);
            Vec p = point.withY(0.0);
            Vec ab = b.sub(a);
            double lengthSquared = ab.dot(ab);
            double t = lengthSquared < 1.0e-9 ? 0.0 : SmartCameraMath.clamp(p.sub(a).dot(ab) / lengthSquared, 0.0, 1.0);
            nearest = Math.min(nearest, p.distanceTo(a.add(ab.scale(t))));
        }
        return nearest;
    }

}
