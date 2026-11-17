package comedymonkey.dailies.filming;

/**
 * What one exported frame of a camera test showed: where the camera was and how it looked, and where the player it
 * films was. Filled in by the scripted test while the frame is taken; read by {@link FilmMeasures}. No game types, so
 * the measures can be tested on their own.
 * <p>
 * Directions follow the game: yaw 0 looks along +z, yaw 90 along -x, pitch 90 straight down; the field of view is the
 * vertical one, in degrees.
 */
public final class FilmFrame {

    /** The sample points on the subject's box: its eight corners, a little inside, then its centre */
    public static final int POINTS = 9;
    public static final int CENTRE = 8;

    public int frame;
    public double seconds;
    public double tick;

    public double camX;
    public double camY;
    public double camZ;
    public double yaw;
    public double pitch;
    public double roll;
    public double fov = 70.0;
    /** Width over height of the picture */
    public double aspect = 16.0 / 9.0;

    /** Whether the subject was in the world at all; when not, nothing about it below means anything */
    public boolean subjectPresent;
    public double eyeX;
    public double eyeY;
    public double eyeZ;
    /** Which way the subject's body faced, in degrees */
    public double subjectYaw;
    public double minX;
    public double minY;
    public double minZ;
    public double maxX;
    public double maxY;
    public double maxZ;
    /** The point of the subject the camera means to place on the screen */
    public double aimX;
    public double aimY;
    public double aimZ;
    /** Bit i set when sample point i was hidden from the camera by blocks */
    public int occludedMask;

    /** What the subject rides, by entity id; -1 when on their own feet */
    public int vehicle = -1;
    /** The world the subject is in, such as minecraft:the_nether */
    public String dimension = "";

    public boolean cameraInBlock;
    /** The director's shot, counted from 0; -1 for a camera that has no shots */
    public int shot = -1;
    public String shotKind = "";
    /** Where the camera means to put the aim point on the screen, -1 to 1 across and up */
    public double targetX;
    public double targetY;

    /** The sample points, as x, y, z triples, in the order of {@link #occludedMask}'s bits */
    public double[][] points() {
        // A tenth of a block inside the box, so the feet are not taken for hidden by the ground they stand on
        double inset = 0.1;
        double x0 = Math.min(this.minX + inset, (this.minX + this.maxX) / 2);
        double y0 = Math.min(this.minY + inset, (this.minY + this.maxY) / 2);
        double z0 = Math.min(this.minZ + inset, (this.minZ + this.maxZ) / 2);
        double x1 = Math.max(this.maxX - inset, (this.minX + this.maxX) / 2);
        double y1 = Math.max(this.maxY - inset, (this.minY + this.maxY) / 2);
        double z1 = Math.max(this.maxZ - inset, (this.minZ + this.maxZ) / 2);
        double[][] points = new double[POINTS][];
        int i = 0;
        for (double x : new double[]{x0, x1}) {
            for (double y : new double[]{y0, y1}) {
                for (double z : new double[]{z0, z1}) {
                    points[i++] = new double[]{x, y, z};
                }
            }
        }
        points[CENTRE] = new double[]{(this.minX + this.maxX) / 2, (this.minY + this.maxY) / 2, (this.minZ + this.maxZ) / 2};
        return points;
    }

}
