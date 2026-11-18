package comedymonkey.dailies.smartcamera;

/**
 * A point or direction in the world, free of Minecraft types so the camera solvers can be tested on their own.
 * Angles follow {@link SmartCameraMath}: yaw 0 looks towards +Z and grows clockwise seen from above, positive pitch
 * looks down.
 */
public record Vec(double x, double y, double z) {

    public static final Vec ZERO = new Vec(0.0, 0.0, 0.0);
    public static final Vec UP = new Vec(0.0, 1.0, 0.0);

    public Vec add(Vec other) {
        return new Vec(this.x + other.x, this.y + other.y, this.z + other.z);
    }

    public Vec add(double dx, double dy, double dz) {
        return new Vec(this.x + dx, this.y + dy, this.z + dz);
    }

    public Vec sub(Vec other) {
        return new Vec(this.x - other.x, this.y - other.y, this.z - other.z);
    }

    public Vec scale(double factor) {
        return new Vec(this.x * factor, this.y * factor, this.z * factor);
    }

    public double dot(Vec other) {
        return this.x * other.x + this.y * other.y + this.z * other.z;
    }

    public double length() {
        return Math.sqrt(this.x * this.x + this.y * this.y + this.z * this.z);
    }

    public double horizontalLength() {
        return Math.sqrt(this.x * this.x + this.z * this.z);
    }

    public double distanceTo(Vec other) {
        return this.sub(other).length();
    }

    /** The same direction one long, or zero for a vector too short to have a direction */
    public Vec normalize() {
        double length = this.length();
        return length < 1.0e-9 ? ZERO : this.scale(1.0 / length);
    }

    public Vec withY(double newY) {
        return new Vec(this.x, newY, this.z);
    }

    public Vec lerp(Vec to, double amount) {
        return new Vec(this.x + (to.x - this.x) * amount, this.y + (to.y - this.y) * amount, this.z + (to.z - this.z) * amount);
    }

    public boolean finite() {
        return Double.isFinite(this.x) && Double.isFinite(this.y) && Double.isFinite(this.z);
    }

    /** The way a camera with this yaw and pitch looks, one long */
    public static Vec look(double yaw, double pitch) {
        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);
        double cos = Math.cos(pitchRadians);
        return new Vec(-Math.sin(yawRadians) * cos, -Math.sin(pitchRadians), Math.cos(yawRadians) * cos);
    }

    /** The level direction of a yaw, one long */
    public static Vec heading(double yaw) {
        double yawRadians = Math.toRadians(yaw);
        return new Vec(-Math.sin(yawRadians), 0.0, Math.cos(yawRadians));
    }

    /**
     * Which side of a line through the origin along {@code along} this offset lies on, seen from above: 1 or -1, or 0
     * when it is within {@code neutral} (a sine, 0 to 1) of lying on the line, or the line has no level direction.
     */
    public int side(Vec along, double neutral) {
        double alongLength = along.horizontalLength();
        double length = this.horizontalLength();
        if (alongLength < 1.0e-6 || length < 1.0e-6) {
            return 0;
        }
        double sine = (along.x * this.z - along.z * this.x) / (alongLength * length);
        return Math.abs(sine) < neutral ? 0 : sine > 0 ? 1 : -1;
    }

    /** The angle between two directions, in degrees, 0 to 180 */
    public static double angleBetween(Vec a, Vec b) {
        double lengths = a.length() * b.length();
        if (lengths < 1.0e-12) {
            return 0.0;
        }
        return Math.toDegrees(Math.acos(SmartCameraMath.clamp(a.dot(b) / lengths, -1.0, 1.0)));
    }

}
