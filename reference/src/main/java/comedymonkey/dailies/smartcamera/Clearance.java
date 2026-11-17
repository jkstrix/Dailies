package comedymonkey.dailies.smartcamera;

/**
 * Keeps a camera clear of blocks between it and what it films.
 * <ul>
 *     <li>It looks ahead in time as well as along the present line of sight (how the subject and the camera's wanted
 *     place are moving), so a wall coming between them is seen before it arrives and the camera eases in early instead
 *     of jumping when it is hit, then eases back out once the way is clear.</li>
 *     <li>The camera never passes through a block: from one step to the next it moves only as far as it can go
 *     through open space, stopping short of a face. A pillar sweeping across the view is waited out rather than
 *     flown through.</li>
 *     <li>As a last guard it is never left inside a block, nor so close to one that the near plane cuts into it.</li>
 * </ul>
 */
public final class Clearance {

    private static final double[] LOOK_AHEAD = {0.3, 0.6, 0.9, 1.2};
    /** Seconds to move in, and to ease back out */
    private static final double IN_TIME = 0.3;
    private static final double OUT_TIME = 1.0;
    private static final double NEAREST = 0.6;
    /** The longest step a sliding camera takes at once: shorter than the room it keeps round itself, so it cannot skip a block */
    private static final double SLIDE_STEP = 0.2;
    /** How closely the camera follows where it should be, and how fast its speed may change, blocks a second each second */
    private static final double FOLLOW_TIME = 0.06;
    private static final double MOST_ACCELERATION = 8.0;
    private final Spring.Point follow = new Spring.Point();
    private Vec eyeVelocity = Vec.ZERO;
    private Vec targetVelocity = Vec.ZERO;
    private Vec lastTarget = Vec.ZERO;
    private boolean blockedMove = false;

    private final Spring share = new Spring(1.0);
    private boolean started = false;
    private boolean blocked = false;
    private Vec lastPivot = Vec.ZERO;
    private Vec lastWanted = Vec.ZERO;
    private Vec lastEye = null;
    private Vec pivotVelocity = Vec.ZERO;
    private Vec wantedVelocity = Vec.ZERO;
    /** Seconds the camera has been held back by a block from where it should be, while it cannot see the subject */
    private double stuckSeconds = 0.0;
    private boolean jumped = false;
    private boolean mayJump = true;
    /** Seconds stuck behind a wall before the camera gives up and moves by a cut */
    private static final double MOST_STUCK_SECONDS = 1.0;

    public void reset() {
        this.started = false;
        this.blocked = false;
        this.lastEye = null;
        this.stuckSeconds = 0.0;
        this.jumped = false;
    }

    /** For the director's shots: never jump by itself; the director cuts away from a blocked shot instead */
    public void neverJump() {
        this.mayJump = false;
    }

    /** Whether the last step had to jump (by a cut) out from behind a wall it was stuck behind */
    public boolean jumped() {
        return this.jumped;
    }

    /** Whether the present line of sight was cut short in the last step */
    public boolean blocked() {
        return this.blocked;
    }

    /** How much of the wanted distance the camera was allowed in the last step, 0 to 1 */
    public double share() {
        return this.share.value;
    }

    /** As the other solve, the velocities measured from step to step */
    public Vec solve(CameraWorld world, Vec pivot, Vec wanted, double start, double dt) {
        if (this.started && dt > 0.0) {
            double blend = SmartCameraMath.blendFactor(0.2, dt);
            this.pivotVelocity = this.pivotVelocity.lerp(pivot.sub(this.lastPivot).scale(1.0 / dt), blend);
            this.wantedVelocity = this.wantedVelocity.lerp(wanted.sub(this.lastWanted).scale(1.0 / dt), blend);
        } else if (!this.started) {
            this.pivotVelocity = Vec.ZERO;
            this.wantedVelocity = Vec.ZERO;
        }
        this.lastPivot = pivot;
        this.lastWanted = wanted;
        return this.solve(world, pivot, wanted, start, this.pivotVelocity, this.wantedVelocity, dt);
    }

    /**
     * @param pivot          the point filmed, from which the line of sight is measured
     * @param wanted         where the camera would be with nothing in the way
     * @param start          how far from the pivot blocks begin to count (the edge of a framed vehicle, whose own
     *                       blocks are not in its way)
     * @param pivotVelocity  how the pivot moves, blocks a second
     * @param wantedVelocity how the wanted place moves
     * @return where the camera may be
     */
    public Vec solve(CameraWorld world, Vec pivot, Vec wanted, double start, Vec pivotVelocity, Vec wantedVelocity, double dt) {
        double full = pivot.distanceTo(wanted);
        if (full < 1.0e-3) {
            this.blocked = false;
            return this.settle(world, CameraProbe.pushOut(world, wanted, pivot.add(0.0, 0.5, 0.0)), pivot, dt);
        }
        double now = allowedShare(world, pivot, wanted, start);
        double goal = now;
        for (double seconds : LOOK_AHEAD) {
            Vec futurePivot = pivot.add(pivotVelocity.scale(seconds));
            Vec futureWanted = wanted.add(wantedVelocity.scale(seconds));
            double share = allowedShare(world, futurePivot, futureWanted, start);
            // A block right at where the subject will be is what it carries with it (the craft it rides), which the
            // world here shows where it is now: it is no reason to pull in early
            if (share * futurePivot.distanceTo(futureWanted) > Math.min(start, 1.0) + 1.5) {
                goal = Math.min(goal, share);
            }
        }
        if (!this.started || this.lastEye == null) {
            this.share.reset(now);
            this.started = true;
            this.lastEye = null;
        } else {
            this.share.step(goal, goal < this.share.value ? IN_TIME : OUT_TIME, dt);
        }
        this.blocked = now < 0.999;
        Vec eye = pivot.add(wanted.sub(pivot).scale(this.share.value));
        this.jumped = false;
        Vec settled = this.settle(world, eye, pivot, dt);
        // Held back by a block, not just still catching up
        boolean held = this.blockedMove && settled.distanceTo(eye) > 0.5;
        boolean unseen = CameraProbe.visibility(world, settled, pivot, 1.0) < 0.5;
        // Held back from a place that would see the subject well (round a post it cannot slide past) is stuck too
        boolean wantedIsBetter = now >= 0.999 && settled.distanceTo(eye) > 1.5;
        this.stuckSeconds = held && (unseen || wantedIsBetter) ? this.stuckSeconds + dt : 0.0;
        if (this.mayJump && this.stuckSeconds > MOST_STUCK_SECONDS) {
            // Walled off from the subject for good: rather than stay looking at a wall, go to where it can see, at once
            this.lastEye = null;
            this.share.reset(now);
            settled = this.settle(world, pivot.add(wanted.sub(pivot).scale(now)), pivot, dt);
            this.stuckSeconds = 0.0;
            this.jumped = true;
        }
        return settled;
    }

    /**
     * Moves from the last place towards the new one the way a small ball would: in short steps, one axis at a time,
     * stopping at a face and sliding along it rather than going in. So the camera never enters a block or passes
     * through one, never freezes against an obstacle it can slide round, and never jumps. Only a camera with no last
     * place (a start or a cut) is put down, at the nearest place on the way to the pivot that has room.
     */
    private Vec settle(CameraWorld world, Vec eye, Vec pivot, double dt) {
        Vec from = this.lastEye;
        Vec target = eye;
        if (from == null || !CameraProbe.roomFor(world, from)) {
            eye = CameraProbe.pushOut(world, eye, pivot);
            this.eyeVelocity = Vec.ZERO;
            this.targetVelocity = Vec.ZERO;
        } else if (dt <= 0.0) {
            eye = from;
        } else {
            // The camera itself follows where it should be closely, but its speed never changes with a jolt: after
            // sliding along a face it catches up smoothly instead of springing free
            this.targetVelocity = this.targetVelocity.lerp(target.sub(this.lastTarget).scale(1.0 / dt), SmartCameraMath.blendFactor(0.1, dt));
            this.follow.reset(from, this.eyeVelocity);
            Vec next = this.follow.step(target.add(this.targetVelocity.scale(Math.max(0.0, FOLLOW_TIME - dt * 0.5))), FOLLOW_TIME, FOLLOW_TIME, dt);
            // Only speeding up is held back, in proportion to how fast it goes: a camera at walking pace never bursts
            // into a run. Slowing down and turning are free, so it never overshoots where it should be
            Vec velocity = next.sub(from).scale(1.0 / dt);
            double before = this.eyeVelocity.length();
            double most = before + (MOST_ACCELERATION + 1.5 * before) * dt;
            if (velocity.length() > most) {
                velocity = velocity.scale(most / velocity.length());
            }
            Vec intended = from.add(velocity.scale(dt));
            eye = slide(world, from, intended);
            this.blockedMove = eye.distanceTo(intended) > 0.01;
            this.eyeVelocity = eye.sub(from).scale(1.0 / dt);
        }
        this.lastTarget = target;
        this.lastEye = eye;
        return eye;
    }

    /** The farthest a camera with room at {@code from} gets towards {@code to}, sliding along faces */
    static Vec slide(CameraWorld world, Vec from, Vec to) {
        Vec delta = to.sub(from);
        double length = delta.length();
        if (length < 1.0e-6) {
            return from;
        }
        int steps = Math.max(1, (int) Math.ceil(length / SLIDE_STEP));
        Vec step = delta.scale(1.0 / steps);
        Vec at = from;
        for (int i = 0; i < steps; i++) {
            at = slideAxis(world, at, new Vec(step.x(), 0.0, 0.0));
            at = slideAxis(world, at, new Vec(0.0, 0.0, step.z()));
            at = slideAxis(world, at, new Vec(0.0, step.y(), 0.0));
        }
        return at;
    }

    private static Vec slideAxis(CameraWorld world, Vec at, Vec move) {
        if (move.length() < 1.0e-9) {
            return at;
        }
        if (CameraProbe.roomFor(world, at.add(move))) {
            return at.add(move);
        }
        // Up to the face, as near as an eighth of a step allows
        double low = 0.0;
        double high = 1.0;
        for (int i = 0; i < 4; i++) {
            double middle = (low + high) / 2.0;
            if (CameraProbe.roomFor(world, at.add(move.scale(middle)))) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return at.add(move.scale(low));
    }

    /** The share of the way from pivot to camera that is clear, less the margin a camera keeps from a wall */
    static double allowedShare(CameraWorld world, Vec pivot, Vec wanted, double start) {
        Vec along = wanted.sub(pivot);
        double full = along.length();
        if (full < 1.0e-3) {
            return 1.0;
        }
        Vec direction = along.scale(1.0 / full);
        double skip = Math.min(start, full * 0.5);
        Vec from = pivot.add(direction.scale(skip));
        Vec to = wanted.add(direction.scale(CameraProbe.MARGIN));
        double length = from.distanceTo(to);
        double free = CameraProbe.freeWide(world, from, to, 0.2);
        if (free >= length - 1.0e-6) {
            return 1.0;
        }
        double allowed = Math.max(Math.min(NEAREST + skip, full), skip + free - CameraProbe.MARGIN);
        return SmartCameraMath.clamp(allowed / full, 0.0, 1.0);
    }

}
