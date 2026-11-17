package comedymonkey.dailies.smartcamera;

/**
 * Questions about the space around the subject and the camera, built from the two of {@link CameraWorld}.
 */
public final class CameraProbe {

    /** How close the camera's eye may come to a block face: the near plane must never reach into a block */
    public static final double MARGIN = 0.3;

    private static final double[][] AROUND = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0.7071, 0, 0.7071}, {-0.7071, 0, 0.7071}, {0.7071, 0, -0.7071},
        {-0.7071, 0, -0.7071}};

    private CameraProbe() {
    }

    /** How far one can go from {@code from} towards {@code to} before meeting a block, at most the whole way */
    public static double free(CameraWorld world, Vec from, Vec to) {
        double length = from.distanceTo(to);
        if (length < 1.0e-6) {
            return 0.0;
        }
        return SmartCameraMath.clamp(world.clip(from, to), 0.0, 1.0) * length;
    }

    public static boolean clear(CameraWorld world, Vec from, Vec to) {
        return world.clip(from, to) >= 1.0;
    }

    /**
     * {@link #free} for a camera rather than a point: four more rays fan out from {@code from} to points
     * {@code radius} to each side of {@code to}, so that a corner the camera would graze counts too.
     */
    public static double freeWide(CameraWorld world, Vec from, Vec to, double radius) {
        double best = free(world, from, to);
        Vec along = to.sub(from);
        double length = along.length();
        if (length < 1.0e-6 || radius <= 0.0) {
            return best;
        }
        Vec forward = along.scale(1.0 / length);
        Vec side = new Vec(forward.z(), 0.0, -forward.x()).normalize();
        if (side == Vec.ZERO) {
            side = new Vec(1.0, 0.0, 0.0);
        }
        Vec up = new Vec(side.y() * forward.z() - side.z() * forward.y(), side.z() * forward.x() - side.x() * forward.z(),
            side.x() * forward.y() - side.y() * forward.x()).normalize();
        for (Vec offset : new Vec[]{side.scale(radius), side.scale(-radius), up.scale(radius), up.scale(-radius)}) {
            double reached = free(world, from, to.add(offset));
            // Measured along the middle ray
            best = Math.min(best, reached * length / Math.max(1.0e-6, to.add(offset).distanceTo(from)));
        }
        return best;
    }

    /** Whether a camera's eye can stand here: not in a block and not so close to one that the near plane cuts into it */
    public static boolean roomFor(CameraWorld world, Vec eye) {
        if (world.solid(eye)) {
            return false;
        }
        double r = MARGIN * 0.8;
        return !world.solid(eye.add(r, 0, 0)) && !world.solid(eye.add(-r, 0, 0)) && !world.solid(eye.add(0, 0, r)) && !world.solid(eye.add(0, 0, -r))
            && !world.solid(eye.add(0, r, 0)) && !world.solid(eye.add(0, -r, 0));
    }

    /**
     * How much of the subject a camera there sees, 0 to 1: rays to its head, its middle and near its feet (or the top,
     * middle and bottom of what is framed). Blocks within a little of the subject do not count; it may lean on them.
     */
    public static double visibility(CameraWorld world, Vec eye, Vec aim, double height) {
        double half = Math.max(0.3, height * 0.4);
        Vec[] points = {aim, aim.add(0.0, half, 0.0), aim.add(0.0, -half, 0.0)};
        int seen = 0;
        for (Vec point : points) {
            double length = eye.distanceTo(point);
            if (length < 0.4 || free(world, eye, point) >= length - 0.4) {
                seen++;
            }
        }
        return seen / (double) points.length;
    }

    /**
     * How much of a standing figure a camera there sees, 0 to 1, judged as the camera films judge it: rays to the
     * eight corners of its box (a tenth of a block inside) and its middle.
     *
     * @param feet   the middle of the bottom of the box
     * @param width  the box's width
     */
    public static double boxSeen(CameraWorld world, Vec eye, Vec feet, double height, double width) {
        double half = Math.max(0.0, width / 2.0 - 0.1);
        double low = feet.y() + Math.min(0.1, height / 2.0);
        double high = feet.y() + Math.max(height - 0.1, height / 2.0);
        int seen = 0;
        int count = 0;
        for (double dx : new double[]{-half, half}) {
            for (double y : new double[]{low, high}) {
                for (double dz : new double[]{-half, half}) {
                    count++;
                    Vec point = new Vec(feet.x() + dx, y, feet.z() + dz);
                    double length = eye.distanceTo(point);
                    if (world.clip(eye, point) * length >= length - 0.05) {
                        seen++;
                    }
                }
            }
        }
        Vec middle = feet.add(0.0, height / 2.0, 0.0);
        double length = eye.distanceTo(middle);
        count++;
        if (world.clip(eye, middle) * length >= length - 0.05) {
            seen++;
        }
        return seen / (double) count;
    }

    /** The height of the first solid surface below the point within {@code depth}, or NaN */
    public static double groundBelow(CameraWorld world, Vec point, double depth) {
        double fraction = world.clip(point, point.add(0.0, -depth, 0.0));
        return fraction >= 1.0 ? Double.NaN : point.y() - fraction * depth;
    }

    /** The height of the first solid surface above the point within {@code height}, or NaN */
    public static double ceilingAbove(CameraWorld world, Vec point, double height) {
        double fraction = world.clip(point, point.add(0.0, height, 0.0));
        return fraction >= 1.0 ? Double.NaN : point.y() + fraction * height;
    }

    /**
     * How open the space around a point is, 0 (a tight tunnel) to 1 (open air): the mean free distance of rays out to
     * the sides and up, as a share of {@code reach}.
     */
    public static double openness(CameraWorld world, Vec point, double reach) {
        double total = 0.0;
        for (double[] direction : AROUND) {
            total += free(world, point, point.add(direction[0] * reach, direction[1] * reach, direction[2] * reach)) / reach;
        }
        double up = free(world, point, point.add(0.0, reach, 0.0)) / reach;
        return (total + up * 2.0) / (AROUND.length + 2.0);
    }

    /**
     * The nearest point to {@code eye} on the way to {@code towards} where a camera has room. Used as a last guard so
     * the camera is never left inside a block, whatever the smoothing did.
     */
    public static Vec pushOut(CameraWorld world, Vec eye, Vec towards) {
        if (roomFor(world, eye)) {
            return eye;
        }
        Vec along = towards.sub(eye);
        double length = along.length();
        for (double travelled = 0.1; travelled < length; travelled += 0.1) {
            Vec candidate = eye.add(along.scale(travelled / length));
            if (roomFor(world, candidate)) {
                return candidate;
            }
        }
        for (double travelled = 0.1; travelled < length; travelled += 0.1) {
            Vec candidate = eye.add(along.scale(travelled / length));
            if (!world.solid(candidate)) {
                return candidate;
            }
        }
        return towards;
    }

}
