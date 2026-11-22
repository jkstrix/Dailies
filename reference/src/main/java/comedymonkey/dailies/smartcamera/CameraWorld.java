package comedymonkey.dailies.smartcamera;

/**
 * The two questions the camera solvers ask of the world. The game answers them from the level; the unit tests answer
 * them from a few made-up blocks. Fluids never count as solid: a camera may look through water.
 */
public interface CameraWorld {

    /**
     * How far along the segment the first solid block is met, from 0 at {@code from} to 1 at {@code to}; 1 when the
     * way is clear. A segment that starts inside a block is blocked at 0.
     */
    double clip(Vec from, Vec to);

    /** Whether the point is inside the solid part of a block */
    boolean solid(Vec point);

    /** A world with nothing in it */
    CameraWorld OPEN = new CameraWorld() {
        @Override
        public double clip(Vec from, Vec to) {
            return 1.0;
        }

        @Override
        public boolean solid(Vec point) {
            return false;
        }
    };

}
