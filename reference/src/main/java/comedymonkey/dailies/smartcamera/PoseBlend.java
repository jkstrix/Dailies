package comedymonkey.dailies.smartcamera;

/**
 * The camera between steps. Solvers are stepped once per tick of the recording; a frame drawn part way through a tick
 * is drawn from a blend of the last two steps, the same way the game draws entities between their ticks. So the
 * camera depends only on the recording and the ticks played, never on when frames happened to be drawn, and live
 * playback and export agree. With three steps in a row the frames run along the curve through all three rather than
 * straight between the last two, so the camera's speed does not change with a jolt at every tick. Across a cut the
 * new pose is shown at once.
 */
public final class PoseBlend {

    /**
     * The camera at one moment.
     *
     * @param tick the recording tick it is for
     * @param cut  whether it does not join on to the pose before
     */
    public record Pose(double tick, double x, double y, double z, double yaw, double pitch, double fov, boolean hasFov, boolean cut) {
    }

    private Pose earlier;
    private Pose before;
    private Pose last;

    public void clear() {
        this.earlier = null;
        this.before = null;
        this.last = null;
    }

    /** Takes in the pose of a new step */
    public void push(Pose pose) {
        // A step that follows on from the last by one tick blends with it; anything else stands alone
        boolean follows = this.last != null && !pose.cut() && pose.tick() > this.last.tick() && pose.tick() - this.last.tick() <= 1.0001;
        boolean followed = follows && this.before != null && this.last.tick() - this.before.tick() > 0.9999;
        this.earlier = followed ? this.before : null;
        this.before = follows ? this.last : null;
        this.last = pose;
    }

    public Pose last() {
        return this.last;
    }

    /**
     * The pose for a frame at this tick, with its fraction: between the last two steps, or the last step held. Null
     * when there is no step near enough to the tick to stand for it.
     */
    public Pose at(double tick) {
        Pose last = this.last;
        if (last == null || Math.abs(tick - last.tick()) > 1.5) {
            return null;
        }
        Pose before = this.before;
        if (before == null || tick >= last.tick()) {
            return last;
        }
        double amount = SmartCameraMath.clamp((tick - before.tick()) / (last.tick() - before.tick()), 0.0, 1.0);
        Pose earlier = this.earlier;
        // The weights of the three steps for the curve through them (a parabola), or of the two for a straight line
        double w0 = 0.0;
        double w1 = 1.0 - amount;
        double w2 = amount;
        if (earlier != null && last.tick() - before.tick() > 0.9999) {
            w0 = amount * (amount - 1.0) / 2.0;
            w1 = 1.0 - amount * amount;
            w2 = amount * (amount + 1.0) / 2.0;
        }
        double yaw0 = earlier == null ? 0.0 : before.yaw() - SmartCameraMath.wrapDegrees(before.yaw() - earlier.yaw());
        double yaw2 = before.yaw() + SmartCameraMath.wrapDegrees(last.yaw() - before.yaw());
        double e0x = earlier == null ? 0.0 : earlier.x();
        double e0y = earlier == null ? 0.0 : earlier.y();
        double e0z = earlier == null ? 0.0 : earlier.z();
        double e0p = earlier == null ? 0.0 : earlier.pitch();
        double e0f = earlier == null ? 0.0 : earlier.fov();
        return new Pose(tick,
            w0 * e0x + w1 * before.x() + w2 * last.x(),
            w0 * e0y + w1 * before.y() + w2 * last.y(),
            w0 * e0z + w1 * before.z() + w2 * last.z(),
            SmartCameraMath.wrapDegrees(w0 * yaw0 + w1 * before.yaw() + w2 * yaw2),
            SmartCameraMath.clamp(w0 * e0p + w1 * before.pitch() + w2 * last.pitch(), -90.0, 90.0),
            w0 * e0f + w1 * before.fov() + w2 * last.fov(),
            last.hasFov(), false);
    }

}
