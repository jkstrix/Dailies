package comedymonkey.dailies.smartcamera;

import java.util.function.IntFunction;

/**
 * A hot air balloon for the camera tests: a basket with corner posts under a wide envelope, climbing away from a ledge
 * of snow, with someone standing in the basket. The craft moves, so its blocks are wherever the last step put it.
 * {@link #terrain} is the world as the Smart Camera sees it while it frames the craft (its own blocks left out);
 * {@link #seeing} has the craft's blocks in, as they hide the rider.
 */
final class CraftScene {

    final VoxelWorld ground;
    private Vec origin = Vec.ZERO;

    CraftScene() {
        // A snow ledge behind and below where the balloon sets off
        this.ground = new VoxelWorld(64).box(-8, 64, -9, 8, 67, -4);
    }

    /** The rider's feet stand on the basket's floor, at the craft's origin */
    private boolean craftBlock(double x, double y, double z) {
        double dx = x - this.origin.x();
        double dy = y - this.origin.y();
        double dz = z - this.origin.z();
        // Floor
        if (dy >= -1.0 && dy < 0.0 && Math.abs(dx) <= 1.5 && Math.abs(dz) <= 1.5) {
            return true;
        }
        // Corner posts
        if (dy >= 0.0 && dy < 3.0 && Math.abs(dx) >= 1.1 && Math.abs(dx) <= 1.5 && Math.abs(dz) >= 1.1 && Math.abs(dz) <= 1.5) {
            return true;
        }
        // Envelope
        return dy >= 3.0 && dy < 8.0 && Math.abs(dx) <= 2.5 && Math.abs(dz) <= 2.5;
    }

    final CameraWorld terrain = new CameraWorld() {
        @Override
        public double clip(Vec from, Vec to) {
            return CraftScene.this.ground.clip(from, to);
        }

        @Override
        public boolean solid(Vec point) {
            return CraftScene.this.ground.solid(point);
        }
    };

    final CameraWorld seeing = new CameraWorld() {
        @Override
        public double clip(Vec from, Vec to) {
            double first = CraftScene.this.ground.clip(from, to);
            double length = from.distanceTo(to);
            int steps = Math.max(1, (int) Math.ceil(length / 0.05));
            for (int i = 0; i <= steps; i++) {
                double t = i / (double) steps;
                if (t >= first) {
                    break;
                }
                Vec at = from.lerp(to, t);
                if (CraftScene.this.craftBlock(at.x(), at.y(), at.z())) {
                    return t;
                }
            }
            return first;
        }

        @Override
        public boolean solid(Vec point) {
            return CraftScene.this.ground.solid(point) || CraftScene.this.craftBlock(point.x(), point.y(), point.z());
        }
    };

    /** The rider, aboard the balloon framed with them; each call puts the craft where that tick has it */
    IntFunction<SubjectSample> path() {
        return tick -> {
            double seconds = tick / 20.0;
            // Lifting off slowly, then climbing steadily while it drifts
            double climb = seconds < 2.0 ? 0.25 * seconds * seconds : 1.0 + (seconds - 2.0) * 1.0;
            Vec feet = new Vec(0.5, 68.0 + climb, 0.5 + seconds * 0.8);
            this.origin = feet;
            return new SubjectSample(feet, feet.add(0.0, 3.0, 0.0), 9.0, 9.0, 3.5, 0.0, true, false, false, false, false, "physics_object");
        };
    }

    static Vec rider(SubjectSample subject) {
        return subject.feet().add(0.0, 1.35, 0.0);
    }

}
