package comedymonkey.dailies.smartcamera;

/**
 * The direction a chase camera stays behind. Jitter and small turns are ignored: the goal only moves once the subject
 * has turned further than the dead band for a moment (or sharply), and then follows until it has caught up. The
 * output follows the goal on a spring, so the camera swings round smoothly.
 */
public final class HeadingFilter {

    /** Seconds a turn past the dead band has to last before the camera follows it */
    private static final double HOLD_SECONDS = 0.35;
    /** A turn this sharp is followed at once */
    private static final double SHARP_DEGREES = 65.0;

    private final Spring output = new Spring();
    private double goal;
    private boolean following = false;
    private double pendingSeconds = 0.0;

    public void reset(double heading) {
        this.goal = heading;
        this.output.reset(heading);
        this.following = false;
        this.pendingSeconds = 0.0;
    }

    /** The heading the camera works from, not wrapped */
    public double value() {
        return this.output.value;
    }

    /** How fast the output turns, degrees a second */
    public double rate() {
        return this.output.velocity;
    }

    /**
     * @param raw        the subject's heading now
     * @param confidence 0 to 1: how much the raw heading means (little for a subject that is nearly still)
     * @param deadBand   degrees of turn that are ignored
     * @param smoothTime seconds the output takes to swing round to the goal
     */
    public double step(double raw, double confidence, double deadBand, double smoothTime, double dt) {
        if (confidence > 0.25 && dt > 0.0) {
            double difference = SmartCameraMath.wrapDegrees(raw - this.goal);
            if (!this.following) {
                if (Math.abs(difference) > deadBand) {
                    this.pendingSeconds += dt;
                    if (this.pendingSeconds >= HOLD_SECONDS || Math.abs(difference) > SHARP_DEGREES) {
                        this.following = true;
                    }
                } else {
                    this.pendingSeconds = 0.0;
                }
            }
            if (this.following) {
                this.goal += difference * SmartCameraMath.blendFactor(0.2, dt) * confidence;
                if (Math.abs(SmartCameraMath.wrapDegrees(raw - this.goal)) < 2.0) {
                    this.following = false;
                    this.pendingSeconds = 0.0;
                }
            }
        }
        return this.output.stepAngle(this.goal, smoothTime, dt);
    }

}
