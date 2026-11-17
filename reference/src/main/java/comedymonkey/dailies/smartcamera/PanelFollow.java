package comedymonkey.dailies.smartcamera;

/**
 * The players panel's Follow: the camera keeps the place the person flew it to beside a player, and follows them as
 * the follow entity keyframe does ({@link TetherSolver}): smoothed, kept clear of blocks, in through doors and down
 * shafts after them along the way they went, round to a place that sees them when they are hidden, and out by a cut
 * from behind what it cannot slide past. Free of Minecraft, so the same code is stepped while someone watches, while
 * an export runs, and in the tests.
 */
public final class PanelFollow {

    /** Seconds the camera takes to settle after the player, and its turn after the aim */
    private static final double SMOOTHING = 0.15;
    private static final double AIM = 0.12;

    private final TetherSolver tether = new TetherSolver();
    private Vec offset = null;
    private Vec placed = null;
    private int cuts = 0;

    public void reset() {
        this.offset = null;
        this.placed = null;
        this.tether.reset();
    }

    /** How many times this camera has cut: when it changes, a new shot begins */
    public int cuts() {
        return this.cuts;
    }

    /** Where the last step put the camera's eye, or null before the first */
    public Vec placed() {
        return this.placed;
    }

    /**
     * One step.
     *
     * @param eyes      the player's eyes
     * @param camera    where the camera's eye is now: moved by hand since the last step, the move is kept; null when
     *                  nothing but this follow has moved it
     * @param bodyYaw   the way the player faces, for a first place behind them
     * @param eyeHeight how far the eyes are above the feet; {@code height} and {@code width} the player's size
     * @param dt        seconds since the last step; a jump (or the first step) starts afresh
     */
    public TetherSolver.Result step(CameraWorld world, Vec eyes, Vec camera, double bodyYaw, double eyeHeight, double height, double width, double fov,
                                    double aspect, double dt) {
        if (this.offset == null) {
            Vec start = camera != null ? camera : eyes;
            double away = start.distanceTo(eyes);
            if (away > 24.0 || away < 1.0) {
                // Begun from too far off to see them, or from inside them: a little behind and above
                start = eyes.add(Vec.heading(bodyYaw).scale(-5.0)).add(0.0, 2.0 - eyeHeight + 1.62, 0.0);
            }
            this.offset = start.sub(eyes);
            this.tether.reset();
        } else if (camera != null && this.placed != null) {
            // Flown by hand since the last step: the person moves the camera's place beside the player
            this.offset = this.offset.add(camera.sub(this.placed));
        }
        this.tether.box(eyeHeight, height, width);
        TetherSolver.Result result = this.tether.keep(world, eyes, this.offset, eyes, SMOOTHING, AIM, fov, aspect, dt, true);
        if (result.cut()) {
            this.cuts++;
        }
        this.placed = result.eye();
        return result;
    }

}
