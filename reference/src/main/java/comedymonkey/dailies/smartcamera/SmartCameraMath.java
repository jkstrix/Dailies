package comedymonkey.dailies.smartcamera;

/**
 * The maths of the smart camera, free of Minecraft types so it can be checked on its own.
 *
 * Angles follow Minecraft's conventions: yaw 0 looks towards +Z and increases clockwise seen from above
 * (yaw 90 looks towards -X), positive pitch looks down. Screen positions are normalised: x and y run from -1 to 1,
 * +x is the right edge and +y is the top edge. FOV is the vertical field of view in degrees.
 */
public final class SmartCameraMath {

    private SmartCameraMath() {
    }

    /**
     * @param exact false when the target cannot be put at the requested screen position without rolling the camera
     *              (something almost straight above or below, asked to be far off to the side). The result is then the closest fit
     */
    public record Aim(double yaw, double pitch, boolean exact) {}

    public record ScreenPoint(double x, double y, boolean inFront) {}

    public record Spring(double value, double velocity) {}

    /**
     * The camera rotation (no roll) at which the target appears at the given screen position.
     */
    public static Aim aimAt(double cameraX, double cameraY, double cameraZ, double targetX, double targetY, double targetZ,
                            double screenX, double screenY, double fov, double aspect) {
        double dx = targetX - cameraX;
        double dy = targetY - cameraY;
        double dz = targetZ - cameraZ;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0e-9) {
            return new Aim(0.0, 0.0, false);
        }
        dx /= length;
        dy /= length;
        dz /= length;

        // Direction of the target in camera space: right, up, forward
        double tanVertical = Math.tan(Math.toRadians(fov) / 2.0);
        double right = screenX * tanVertical * aspect;
        double up = screenY * tanVertical;
        double forward = 1.0;
        double cameraLength = Math.sqrt(right * right + up * up + forward * forward);
        right /= cameraLength;
        up /= cameraLength;
        forward /= cameraLength;

        // Only the camera's elevation changes the height of that direction: dy = up * cos(e) + forward * sin(e)
        double verticalLength = Math.sqrt(up * up + forward * forward);
        double heightRatio = dy / verticalLength;
        double elevation = Math.asin(clamp(heightRatio, -1.0, 1.0)) - Math.atan2(up, forward);
        double maxElevation = Math.toRadians(89.9);
        boolean exact = Math.abs(heightRatio) <= 1.0 && Math.abs(elevation) <= maxElevation;
        elevation = clamp(elevation, -maxElevation, maxElevation);

        // What is left is a rotation around the vertical axis
        double alongHeading = forward * Math.cos(elevation) - up * Math.sin(elevation);
        double targetYaw = Math.atan2(-dx, dz);
        double yaw = targetYaw - Math.atan2(right, alongHeading);

        return new Aim(wrapDegrees(Math.toDegrees(yaw)), Math.toDegrees(-elevation), exact);
    }

    /**
     * Where a point appears on screen for a camera without roll.
     */
    public static ScreenPoint project(double cameraX, double cameraY, double cameraZ, double yaw, double pitch,
                                      double targetX, double targetY, double targetZ, double fov, double aspect) {
        double dx = targetX - cameraX;
        double dy = targetY - cameraY;
        double dz = targetZ - cameraZ;

        double yawRadians = Math.toRadians(yaw);
        double elevation = Math.toRadians(-pitch);
        double headingX = -Math.sin(yawRadians);
        double headingZ = Math.cos(yawRadians);
        double cosElevation = Math.cos(elevation);
        double sinElevation = Math.sin(elevation);

        double right = dx * -headingZ + dz * headingX;
        double up = dx * (-sinElevation * headingX) + dy * cosElevation + dz * (-sinElevation * headingZ);
        double forward = dx * (cosElevation * headingX) + dy * sinElevation + dz * (cosElevation * headingZ);

        if (forward <= 1.0e-9) {
            return new ScreenPoint(0.0, 0.0, false);
        }

        double tanVertical = Math.tan(Math.toRadians(fov) / 2.0);
        return new ScreenPoint(right / (forward * tanVertical * aspect), up / (forward * tanVertical), true);
    }

    /**
     * The FOV at which something of the given size fills the given fraction of the frame's height.
     */
    public static double autoZoomFov(double subjectSize, double distance, double screenFraction, double minFov, double maxFov) {
        double low = Math.min(minFov, maxFov);
        double high = Math.max(minFov, maxFov);
        if (distance < 1.0e-6 || screenFraction < 1.0e-6) {
            return high;
        }
        double fov = Math.toDegrees(2.0 * Math.atan(subjectSize / (2.0 * distance * screenFraction)));
        return clamp(fov, low, high);
    }

    /**
     * {@link #autoZoomFov(double, double, double, double, double)} for a frame of a given shape. In a frame taller
     * than it is wide (a vertical video) the width is what the subject has to fit, so the fraction is taken of the
     * width; in any other frame nothing changes.
     *
     * @param aspect the frame's width divided by its height
     */
    public static double autoZoomFov(double subjectSize, double distance, double screenFraction, double minFov, double maxFov, double aspect) {
        if (aspect <= 0.0 || aspect >= 1.0) {
            return autoZoomFov(subjectSize, distance, screenFraction, minFov, maxFov);
        }
        return autoZoomFov(subjectSize, distance, screenFraction * aspect, minFov, maxFov);
    }

    /**
     * One step of a critically damped spring towards the target. Exact for any step size, so the result does not
     * depend on the frame rate. smoothTime is roughly how long the value takes to catch up.
     */
    public static Spring damp(double value, double velocity, double target, double smoothTime, double dt) {
        if (smoothTime < 1.0e-4) {
            return new Spring(target, 0.0);
        }
        if (dt <= 0.0) {
            return new Spring(value, velocity);
        }
        double omega = 2.0 / smoothTime;
        double decay = Math.exp(-omega * dt);
        double offset = value - target;
        double temp = (velocity + omega * offset) * dt;
        return new Spring(target + (offset + temp) * decay, (velocity - omega * temp) * decay);
    }

    /** Same as {@link #damp}, for angles in degrees. The result is not wrapped, so it stays continuous */
    public static Spring dampAngle(double value, double velocity, double target, double smoothTime, double dt) {
        return damp(value, velocity, value + wrapDegrees(target - value), smoothTime, dt);
    }

    /** How far an exponential filter with the given time constant moves towards its target in one step, 0 to 1 */
    public static double blendFactor(double timeConstant, double dt) {
        if (timeConstant < 1.0e-4) {
            return 1.0;
        }
        if (dt <= 0.0) {
            return 0.0;
        }
        return 1.0 - Math.exp(-dt / timeConstant);
    }

    public static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0;
        if (wrapped >= 180.0) {
            wrapped -= 360.0;
        } else if (wrapped < -180.0) {
            wrapped += 360.0;
        }
        return wrapped;
    }

    public static double lerpDegrees(double from, double to, double amount) {
        return from + wrapDegrees(to - from) * amount;
    }

    public static double smoothstep(double amount) {
        amount = clamp(amount, 0.0, 1.0);
        return amount * amount * (3.0 - 2.0 * amount);
    }

    public static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }

    /** The yaw of a horizontal direction */
    public static double yawOf(double x, double z) {
        return Math.toDegrees(Math.atan2(-x, z));
    }

    /**
     * Small deterministic generator (SplitMix64) for the director, so the same seed and shot start always give the same shot.
     */
    public static final class Random {
        private long state;

        public Random(long seed, long shotStartTick) {
            this.state = seed * 0x9E3779B97F4A7C15L + shotStartTick * 0xD1B54A32D192ED03L + 0x2545F4914F6CDD1DL;
            this.nextLong();
        }

        public long nextLong() {
            this.state += 0x9E3779B97F4A7C15L;
            long z = this.state;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }

        /** Uniform in [0, 1) */
        public double nextDouble() {
            return (this.nextLong() >>> 11) * 0x1.0p-53;
        }

        public double range(double min, double max) {
            return min + (max - min) * this.nextDouble();
        }

        public boolean nextBoolean() {
            return (this.nextLong() & 1L) != 0L;
        }

        /** Index picked with probability proportional to its weight, or -1 when every weight is zero */
        public int pickWeighted(double[] weights) {
            double total = 0.0;
            for (double weight : weights) {
                total += Math.max(0.0, weight);
            }
            if (total <= 0.0) {
                return -1;
            }
            double roll = this.nextDouble() * total;
            for (int i = 0; i < weights.length; i++) {
                roll -= Math.max(0.0, weights[i]);
                if (roll < 0.0) {
                    return i;
                }
            }
            return weights.length - 1;
        }
    }

}
