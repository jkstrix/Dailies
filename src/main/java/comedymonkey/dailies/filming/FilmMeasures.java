package comedymonkey.dailies.filming;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The measures of one camera test film (specification FILM-5 to FILM-8): how well the camera kept its player in view,
 * how smoothly it moved, and for the director how it cut. Everything is worked out from the frames alone, so a film's
 * measures can be worked out again from its CSV.
 * <p>
 * A "cut" is a deliberate change of shot by the director. A "subject jump" is the player going somewhere no camera could
 * follow (a teleport, a portal, getting on a vehicle from afar): more than {@value #JUMP_BLOCKS} blocks between two frames
 * at more than {@value #JUMP_SPEED} blocks a second, the same rule the Smart Camera uses for starting again. Both break
 * the film into stretches, and the smoothness is measured only inside a stretch.
 */
public final class FilmMeasures {

    public static final double JUMP_BLOCKS = 3.0;
    public static final double JUMP_SPEED = 100.0;
    /** Longest the player may be out of frame or mostly hidden at a stretch, in seconds */
    public static final double MOST_LOST_SECONDS = 2.0;
    /** Shortest shot the director may hold, in seconds */
    public static final double SHORTEST_SHOT_SECONDS = 1.5;
    /** A cut that turns the view less than this is a jump cut */
    public static final double JUMP_CUT_DEGREES = 30.0;
    /** A step this many times the usual step around it is a pop */
    public static final double POP_FACTOR = 5.0;
    /** Below these a step is never a pop, however still the camera was around it: a turn of a degree, a quarter of a block */
    public static final double POP_FLOOR_DEGREES = 1.0;
    public static final double POP_FLOOR_BLOCKS = 0.25;

    private FilmMeasures() {
    }

    /** What a film measured, and the hard faults it has */
    public static final class Analysis {
        public int frames;
        public int present;
        public double inFrameShare;
        public double halfHiddenShare;
        public double longestLostSeconds;
        public double longestLostFrom;
        public double meanScreenError;
        public double worstScreenError;

        public double[] angularVelocity = new double[2];
        public double[] angularAcceleration = new double[2];
        public double[] angularJerk = new double[2];
        public double[] linearVelocity = new double[2];
        public double[] linearAcceleration = new double[2];
        public double[] linearJerk = new double[2];
        public int pops;
        public String firstPop = "";
        public int insideBlock;
        public double firstInsideBlock = -1;
        public int subjectJumps;

        public int cuts;
        public double cutsPerMinute;
        public double shortestShot;
        public double longestShot;
        public Set<String> shotKinds = new LinkedHashSet<>();
        public int jumpCuts;
        public int lineCrossings;

        public final List<String> faults = new ArrayList<>();
    }

    // ------------------------------------------------------------------ Where things fall on the screen

    /** The direction the camera looks, as a unit vector */
    public static double[] forward(double yaw, double pitch) {
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        return new double[]{-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p)};
    }

    /**
     * @return where a point falls on the frame: x from -1 (left edge) to 1 (right edge), y from -1 (bottom) to 1
     * (top), and its depth in front of the camera, which is not above 0 for a point behind it
     */
    public static double[] screen(FilmFrame frame, double x, double y, double z) {
        double[] f = forward(frame.yaw, frame.pitch);
        double yaw = Math.toRadians(frame.yaw);
        double[] right = {-Math.cos(yaw), 0.0, -Math.sin(yaw)};
        double[] up = cross(right, f);
        double roll = Math.toRadians(frame.roll);
        if (roll != 0.0) {
            double[] turnedRight = add(scale(right, Math.cos(roll)), scale(up, Math.sin(roll)));
            double[] turnedUp = add(scale(up, Math.cos(roll)), scale(right, -Math.sin(roll)));
            right = turnedRight;
            up = turnedUp;
        }
        double[] v = {x - frame.camX, y - frame.camY, z - frame.camZ};
        double depth = dot(v, f);
        if (depth <= 1.0e-6) {
            return new double[]{0.0, 0.0, depth};
        }
        double half = Math.tan(Math.toRadians(Math.max(1.0, frame.fov)) / 2.0);
        return new double[]{dot(v, right) / depth / (half * frame.aspect), dot(v, up) / depth / half, depth};
    }

    static boolean onScreen(double[] at) {
        return at[2] > 1.0e-6 && Math.abs(at[0]) <= 1.0 && Math.abs(at[1]) <= 1.0;
    }

    public static boolean inFrame(FilmFrame frame) {
        double[] centre = frame.points()[FilmFrame.CENTRE];
        return frame.subjectPresent && onScreen(screen(frame, centre[0], centre[1], centre[2]));
    }

    public static double hiddenFraction(FilmFrame frame) {
        return Integer.bitCount(frame.occludedMask & ((1 << FilmFrame.POINTS) - 1)) / (double) FilmFrame.POINTS;
    }

    /** How much of the subject's box the camera saw: sample points in the frame and not hidden, of all of them */
    public static double visibleFraction(FilmFrame frame) {
        if (!frame.subjectPresent) {
            return 0.0;
        }
        double[][] points = frame.points();
        int seen = 0;
        for (int i = 0; i < points.length; i++) {
            if ((frame.occludedMask & (1 << i)) == 0 && onScreen(screen(frame, points[i][0], points[i][1], points[i][2]))) {
                seen++;
            }
        }
        return seen / (double) points.length;
    }

    /** How far the aim point is from where the camera means it to be, in half frame heights; 2 when it is behind the camera */
    public static double screenError(FilmFrame frame) {
        double[] at = screen(frame, frame.aimX, frame.aimY, frame.aimZ);
        if (at[2] <= 1.0e-6) {
            return 2.0;
        }
        return Math.hypot(at[0] - frame.targetX, at[1] - frame.targetY);
    }

    // ------------------------------------------------------------------ The measures

    /**
     * @param framesPerSecond of the film
     * @param director whether the film is of the director, whose cuts are judged as well
     */
    public static Analysis analyse(List<FilmFrame> frames, double framesPerSecond, boolean director) {
        Analysis result = new Analysis();
        result.frames = frames.size();
        int n = frames.size();
        if (n == 0) {
            result.faults.add("the film has no frames");
            return result;
        }
        double fps = framesPerSecond;

        // Where the film breaks: cuts and subject jumps
        boolean[] cut = new boolean[n];
        boolean[] jump = new boolean[n];
        int[] stretch = new int[n];
        int current = 0;
        for (int i = 0; i < n; i++) {
            FilmFrame now = frames.get(i);
            if (i > 0) {
                FilmFrame before = frames.get(i - 1);
                cut[i] = now.shot >= 0 && before.shot >= 0 && now.shot != before.shot;
                if (now.subjectPresent != before.subjectPresent) {
                    jump[i] = true;
                } else if (now.subjectPresent) {
                    double moved = distance(now.eyeX, now.eyeY, now.eyeZ, before.eyeX, before.eyeY, before.eyeZ);
                    jump[i] = moved > JUMP_BLOCKS && moved * fps > JUMP_SPEED;
                }
                if (cut[i] || jump[i] || now.frame != before.frame + 1) {
                    current++;
                }
                if (jump[i]) {
                    result.subjectJumps++;
                }
            }
            stretch[i] = current;
        }

        // Tracking
        int inFrame = 0;
        int halfHidden = 0;
        double errorSum = 0.0;
        int lostRun = 0;
        int lostStart = 0;
        int longest = 0;
        int longestStart = 0;
        for (int i = 0; i < n; i++) {
            FilmFrame frame = frames.get(i);
            if (frame.cameraInBlock) {
                if (result.insideBlock == 0) {
                    result.firstInsideBlock = frame.seconds;
                }
                result.insideBlock++;
            }
            if (!frame.subjectPresent) {
                // Out of the world for a moment (a portal): it neither counts against the camera nor for it
                continue;
            }
            result.present++;
            boolean seen = inFrame(frame);
            boolean hidden = hiddenFraction(frame) > 0.5;
            if (seen) {
                inFrame++;
            }
            if (hidden) {
                halfHidden++;
            }
            double error = screenError(frame);
            errorSum += error;
            result.worstScreenError = Math.max(result.worstScreenError, error);
            if (!seen || hidden) {
                if (lostRun == 0) {
                    lostStart = i;
                }
                lostRun++;
                if (lostRun > longest) {
                    longest = lostRun;
                    longestStart = lostStart;
                }
            } else {
                lostRun = 0;
            }
        }
        if (result.present > 0) {
            result.inFrameShare = inFrame / (double) result.present;
            result.halfHiddenShare = halfHidden / (double) result.present;
            result.meanScreenError = errorSum / result.present;
        }
        result.longestLostSeconds = longest / fps;
        result.longestLostFrom = longest > 0 ? frames.get(longestStart).seconds : 0.0;

        // Smoothness, inside each stretch
        double[][] directions = new double[n][];
        for (int i = 0; i < n; i++) {
            directions[i] = forward(frames.get(i).yaw, frames.get(i).pitch);
        }
        double[][] angular = new double[n][];
        double[][] linear = new double[n][];
        double[] angleStep = new double[n];
        double[] blockStep = new double[n];
        Arrays.fill(angleStep, Double.NaN);
        Arrays.fill(blockStep, Double.NaN);
        for (int i = 1; i < n; i++) {
            if (stretch[i] != stretch[i - 1]) {
                continue;
            }
            double angle = angleBetween(directions[i - 1], directions[i]);
            double[] axis = cross(directions[i - 1], directions[i]);
            double length = Math.sqrt(dot(axis, axis));
            angular[i] = length < 1.0e-12 ? new double[3] : scale(axis, angle * fps / length);
            angleStep[i] = angle;
            FilmFrame a = frames.get(i - 1);
            FilmFrame b = frames.get(i);
            linear[i] = new double[]{(b.camX - a.camX) * fps, (b.camY - a.camY) * fps, (b.camZ - a.camZ) * fps};
            blockStep[i] = distance(a.camX, a.camY, a.camZ, b.camX, b.camY, b.camZ);
        }
        double[][] angularAcc = derivative(angular, fps);
        double[][] angularJerk = derivative(angularAcc, fps);
        double[][] linearAcc = derivative(linear, fps);
        double[][] linearJerk = derivative(linearAcc, fps);
        result.angularVelocity = rmsAndTop(angular);
        result.angularAcceleration = rmsAndTop(angularAcc);
        result.angularJerk = rmsAndTop(angularJerk);
        result.linearVelocity = rmsAndTop(linear);
        result.linearAcceleration = rmsAndTop(linearAcc);
        result.linearJerk = rmsAndTop(linearJerk);

        // Pops: a step far beyond the usual step of the second around it
        int window = (int) Math.round(fps / 2.0);
        for (int i = 1; i < n; i++) {
            String pop = pop(angleStep, i, window, POP_FLOOR_DEGREES, "degrees");
            if (pop == null) {
                pop = pop(blockStep, i, window, POP_FLOOR_BLOCKS, "blocks");
            }
            if (pop != null) {
                if (result.pops == 0) {
                    result.firstPop = String.format(Locale.ROOT, "at %.2f s %s", frames.get(i).seconds, pop);
                }
                result.pops++;
            }
        }

        // The director's cuts
        List<int[]> shots = new ArrayList<>();
        int shotStart = -1;
        for (int i = 0; i < n; i++) {
            FilmFrame frame = frames.get(i);
            if (frame.shot < 0) {
                continue;
            }
            if (!frame.shotKind.isEmpty()) {
                result.shotKinds.add(frame.shotKind);
            }
            if (shotStart < 0) {
                shotStart = i;
            } else if (cut[i]) {
                result.cuts++;
                shots.add(new int[]{shotStart, i, jump[i] ? 1 : 0});
                shotStart = i;
                if (!jump[i]) {
                    if (angleBetween(directions[i - 1], directions[i]) < JUMP_CUT_DEGREES) {
                        result.jumpCuts++;
                    }
                    if (crossesLine(frames, i, fps)) {
                        result.lineCrossings++;
                    }
                }
            }
        }
        if (shotStart >= 0) {
            shots.add(new int[]{shotStart, n, -1});
        }
        double minutes = n / fps / 60.0;
        result.cutsPerMinute = minutes > 0 ? result.cuts / minutes : 0.0;
        result.shortestShot = Double.MAX_VALUE;
        for (int s = 0; s < shots.size(); s++) {
            int[] shot = shots.get(s);
            double seconds = (shot[1] - shot[0]) / fps;
            result.longestShot = Math.max(result.longestShot, seconds);
            // The last shot is cut short by the end of the film, and a shot ended by a subject jump was not the director's choice
            boolean judged = shots.size() == 1 || (shot[2] == 0);
            if (judged) {
                result.shortestShot = Math.min(result.shortestShot, seconds);
            }
        }
        if (result.shortestShot == Double.MAX_VALUE) {
            result.shortestShot = shots.isEmpty() ? 0.0 : result.longestShot;
        }

        // The hard faults
        if (result.longestLostSeconds > MOST_LOST_SECONDS) {
            result.faults.add(String.format(Locale.ROOT, "the player was out of frame or more than half hidden for %.1f s at a stretch, from %.1f s",
                result.longestLostSeconds, result.longestLostFrom));
        }
        if (result.insideBlock > 0) {
            result.faults.add(String.format(Locale.ROOT, "the camera was inside a block on %d frames, first at %.2f s", result.insideBlock, result.firstInsideBlock));
        }
        if (result.pops > 0) {
            result.faults.add(result.pops + " pops, the first " + result.firstPop);
        }
        if (director && result.cuts > 0) {
            if (result.shortestShot < SHORTEST_SHOT_SECONDS) {
                result.faults.add(String.format(Locale.ROOT, "the director held a shot for only %.2f s", result.shortestShot));
            }
            if (result.jumpCuts > 0) {
                result.faults.add(result.jumpCuts + " jump cuts (the view turned less than " + (int) JUMP_CUT_DEGREES + " degrees across the cut)");
            }
        }
        return result;
    }

    private static String pop(double[] steps, int i, int window, double floor, String unit) {
        if (Double.isNaN(steps[i])) {
            return null;
        }
        double[] around = new double[2 * window + 1];
        int count = 0;
        for (int j = Math.max(1, i - window); j <= Math.min(steps.length - 1, i + window); j++) {
            if (j != i && !Double.isNaN(steps[j])) {
                around[count++] = steps[j];
            }
        }
        if (count == 0) {
            return null;
        }
        double median = median(Arrays.copyOf(around, count));
        double limit = Math.max(POP_FACTOR * median, floor);
        if (steps[i] > limit) {
            return String.format(Locale.ROOT, "a step of %.3f %s against a usual %.3f", steps[i], unit, median);
        }
        return null;
    }

    /**
     * Whether the camera went from one side of the line of action to the other across the cut at frame i. The line runs
     * the way the player was moving, or the way they faced when nearly still.
     */
    static boolean crossesLine(List<FilmFrame> frames, int i, double fps) {
        FilmFrame after = frames.get(i);
        FilmFrame before = frames.get(i - 1);
        if (!before.subjectPresent || !after.subjectPresent) {
            return false;
        }
        int back = Math.max(0, i - 1 - (int) Math.round(fps / 2.0));
        FilmFrame earlier = frames.get(back);
        double dx = before.eyeX - earlier.eyeX;
        double dz = before.eyeZ - earlier.eyeZ;
        double seconds = Math.max(1.0e-6, (i - 1 - back) / fps);
        double lineX;
        double lineZ;
        if (earlier.subjectPresent && Math.hypot(dx, dz) / seconds > 1.0) {
            lineX = dx;
            lineZ = dz;
        } else {
            double yaw = Math.toRadians(before.subjectYaw);
            lineX = -Math.sin(yaw);
            lineZ = Math.cos(yaw);
        }
        double length = Math.hypot(lineX, lineZ);
        lineX /= length;
        lineZ /= length;
        double sideBefore = lineX * (before.camZ - before.eyeZ) - lineZ * (before.camX - before.eyeX);
        double sideAfter = lineX * (after.camZ - after.eyeZ) - lineZ * (after.camX - after.eyeX);
        // Close to the line either way is no crossing: a camera straight behind may drift a little to either side
        return Math.abs(sideBefore) > 0.25 && Math.abs(sideAfter) > 0.25 && Math.signum(sideBefore) != Math.signum(sideAfter);
    }

    // ------------------------------------------------------------------ Writing it down

    public static final String CSV_HEADER = "frame,seconds,tick,cam_x,cam_y,cam_z,yaw,pitch,roll,fov,subject_present,eye_x,eye_y,eye_z,box_min_x,box_min_y,box_min_z,"
        + "box_max_x,box_max_y,box_max_z,in_frame,visible_fraction,occluded_fraction,camera_in_block,shot,shot_kind,screen_x,screen_y,target_x,target_y";

    public static String csv(List<FilmFrame> frames) {
        StringBuilder out = new StringBuilder(CSV_HEADER).append('\n');
        for (FilmFrame f : frames) {
            double[] at = screen(f, f.aimX, f.aimY, f.aimZ);
            out.append(String.format(Locale.ROOT, "%d,%.4f,%.3f,%.4f,%.4f,%.4f,%.3f,%.3f,%.3f,%.2f,%d,%.4f,%.4f,%.4f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%d,%.3f,%.3f,%d,%d,%s,%.4f,%.4f,%.3f,%.3f",
                f.frame, f.seconds, f.tick, f.camX, f.camY, f.camZ, f.yaw, f.pitch, f.roll, f.fov, f.subjectPresent ? 1 : 0, f.eyeX, f.eyeY, f.eyeZ,
                f.minX, f.minY, f.minZ, f.maxX, f.maxY, f.maxZ, inFrame(f) ? 1 : 0, visibleFraction(f), f.subjectPresent ? hiddenFraction(f) : 0.0,
                f.cameraInBlock ? 1 : 0, f.shot, f.shotKind.replace(',', ' '), at[0], at[1], f.targetX, f.targetY)).append('\n');
        }
        return out.toString();
    }

    public static String summaryHeader() {
        return String.format(Locale.ROOT, "%-16s %-10s %6s %6s %6s %6s %6s %8s %8s %8s %8s %5s %5s %5s %6s %s",
            "camera", "place", "frame%", "hid%", "lost s", "err", "worst", "ang v", "ang v99", "lin v", "lin a99", "pops", "block", "cuts", "short", "faults");
    }

    public static String summaryRow(String camera, String place, Analysis a) {
        return String.format(Locale.ROOT, "%-16s %-10s %6.1f %6.1f %6.2f %6.3f %6.3f %8.1f %8.1f %8.2f %8.2f %5d %5d %5d %6.2f %d",
            camera, place, a.inFrameShare * 100, a.halfHiddenShare * 100, a.longestLostSeconds, a.meanScreenError, a.worstScreenError,
            a.angularVelocity[0], a.angularVelocity[1], a.linearVelocity[0], a.linearAcceleration[1], a.pops, a.insideBlock, a.cuts, a.cuts > 0 ? a.shortestShot : 0.0,
            a.faults.size());
    }

    /** Every measure of a film on one line, for the log */
    public static String describe(Analysis a) {
        return String.format(Locale.ROOT, "tracking: in frame %.1f %%, more than half hidden %.1f %%, longest lost %.2f s, screen error mean %.3f worst %.3f; "
                + "smoothness (RMS / 99th percentile): angular velocity %.1f / %.1f deg/s, acceleration %.0f / %.0f deg/s2, jerk %.0f / %.0f deg/s3; "
                + "linear velocity %.2f / %.2f b/s, acceleration %.1f / %.1f b/s2, jerk %.0f / %.0f b/s3; pops %d; inside a block %d frames; subject jumps %d"
                + "%s",
            a.inFrameShare * 100, a.halfHiddenShare * 100, a.longestLostSeconds, a.meanScreenError, a.worstScreenError,
            a.angularVelocity[0], a.angularVelocity[1], a.angularAcceleration[0], a.angularAcceleration[1], a.angularJerk[0], a.angularJerk[1],
            a.linearVelocity[0], a.linearVelocity[1], a.linearAcceleration[0], a.linearAcceleration[1], a.linearJerk[0], a.linearJerk[1],
            a.pops, a.insideBlock, a.subjectJumps,
            a.shotKinds.isEmpty() && a.cuts == 0 ? "" : String.format(Locale.ROOT, "; cinematic: %d cuts (%.1f a minute), shots %.2f to %.2f s, kinds %s, jump cuts %d, line crossings %d",
                a.cuts, a.cutsPerMinute, a.cuts > 0 ? a.shortestShot : 0.0, a.longestShot, a.shotKinds, a.jumpCuts, a.lineCrossings));
    }

    // ------------------------------------------------------------------ Small arithmetic

    private static double[][] derivative(double[][] series, double fps) {
        double[][] out = new double[series.length][];
        for (int i = 1; i < series.length; i++) {
            if (series[i] != null && series[i - 1] != null) {
                out[i] = scale(add(series[i], scale(series[i - 1], -1.0)), fps);
            }
        }
        return out;
    }

    /** The root mean square and the 99th percentile of the lengths of a series of vectors; gaps are left out */
    static double[] rmsAndTop(double[][] series) {
        List<Double> lengths = new ArrayList<>();
        for (double[] v : series) {
            if (v != null) {
                lengths.add(Math.sqrt(dot(v, v)));
            }
        }
        if (lengths.isEmpty()) {
            return new double[2];
        }
        double squares = 0.0;
        for (double length : lengths) {
            squares += length * length;
        }
        double[] sorted = lengths.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        int at = Math.max(0, (int) Math.ceil(0.99 * sorted.length) - 1);
        return new double[]{Math.sqrt(squares / sorted.length), sorted[at]};
    }

    static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int middle = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2.0;
    }

    /** In degrees */
    static double angleBetween(double[] a, double[] b) {
        return Math.toDegrees(Math.atan2(Math.sqrt(dot(cross(a, b), cross(a, b))), dot(a, b)));
    }

    private static double distance(double ax, double ay, double az, double bx, double by, double bz) {
        return Math.sqrt((ax - bx) * (ax - bx) + (ay - by) * (ay - by) + (az - bz) * (az - bz));
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double[] add(double[] a, double[] b) {
        return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    private static double[] scale(double[] a, double by) {
        return new double[]{a[0] * by, a[1] * by, a[2] * by};
    }

}
