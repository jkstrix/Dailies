package comedymonkey.dailies.smartcamera;

/**
 * A critically damped spring holding one value: it follows a goal with no overshoot and its speed never jumps, so
 * the camera it moves never pops. Exact for any step length (see {@link SmartCameraMath#damp}).
 */
public final class Spring {

    public double value;
    public double velocity;

    public Spring() {
    }

    public Spring(double value) {
        this.value = value;
    }

    public void reset(double to) {
        this.value = to;
        this.velocity = 0.0;
    }

    public double step(double goal, double smoothTime, double dt) {
        SmartCameraMath.Spring next = SmartCameraMath.damp(this.value, this.velocity, goal, smoothTime, dt);
        this.value = next.value();
        this.velocity = next.velocity();
        return this.value;
    }

    /** As {@link #step}, for an angle in degrees: the goal is taken the short way round and the value is never wrapped */
    public double stepAngle(double goal, double smoothTime, double dt) {
        return this.step(this.value + SmartCameraMath.wrapDegrees(goal - this.value), smoothTime, dt);
    }

    /** Three springs for a point, the height with its own time */
    public static final class Point {
        public final Spring x = new Spring();
        public final Spring y = new Spring();
        public final Spring z = new Spring();

        public void reset(Vec to) {
            this.x.reset(to.x());
            this.y.reset(to.y());
            this.z.reset(to.z());
        }

        public void reset(Vec to, Vec velocity) {
            this.reset(to);
            this.x.velocity = velocity.x();
            this.y.velocity = velocity.y();
            this.z.velocity = velocity.z();
        }

        public Vec step(Vec goal, double levelTime, double heightTime, double dt) {
            return new Vec(this.x.step(goal.x(), levelTime, dt), this.y.step(goal.y(), heightTime, dt), this.z.step(goal.z(), levelTime, dt));
        }

        public Vec value() {
            return new Vec(this.x.value, this.y.value, this.z.value);
        }

        public Vec velocity() {
            return new Vec(this.x.velocity, this.y.velocity, this.z.velocity);
        }
    }

}
