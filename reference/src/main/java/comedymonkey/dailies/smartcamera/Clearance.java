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
            return this.settle(world, CameraProbe.pushOut(world, wanted, pivot.add(0.0, 0.5, 0.0)), pivot);
        }
        double now = allowedShare(world, pivot, wanted, start);
        double goal = now;
        for (double seconds : LOOK_AHEAD) {
            goal = Math.min(goal, allowedShare(world, pivot.add(pivotVelocity.scale(seconds)), wanted.add(wantedVelocity.scale(seconds)), start));
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
        Vec settled = this.settle(world, eye, pivot);
        boolean held = settled.distanceTo(eye) > 0.5;
        boolean unseen = CameraProbe.visibility(world, settled, pivot, 1.0) < 0.5;
        this.stuckSeconds = held && unseen ? this.stuckSeconds + dt : 0.0;
        if (this.mayJump && this.stuckSeconds > MOST_STUCK_SECONDS) {
            // Walled off from the subject for good: rather than stay looking at a wall, go to where it can see, at once
            this.lastEye = null;
            this.share.reset(now);
            settled = this.settle(world, pivot.add(wanted.sub(pivot).scale(now)), pivot);
            this.stuckSeconds = 0.0;
            this.jumped = true;
        }
        return settled;
    }

    /** Moves from the last place towards the new one only through open space, and never ends in a block */
    private Vec settle(CameraWorld world, Vec eye, Vec pivot) {
        Vec from = this.lastEye;
        if (from != null && CameraProbe.roomFor(world, from)) {
            Vec along = eye.sub(from);
            double length = along.length();
            if (length > 1.0e-4) {
                Vec direction = along.scale(1.0 / length);
                Vec beyond = eye.add(direction.scale(CameraProbe.MARGIN));
                double reach = world.clip(from, beyond) * (length + CameraProbe.MARGIN);
                if (reach < length + CameraProbe.MARGIN - 1.0e-6) {
                    eye = from.add(direction.scale(Math.max(0.0, reach - CameraProbe.MARGIN)));
                }
            }
        }
        eye = CameraProbe.pushOut(world, eye, pivot);
        this.lastEye = eye;
        return eye;
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
