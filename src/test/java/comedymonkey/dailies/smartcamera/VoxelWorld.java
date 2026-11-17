package comedymonkey.dailies.smartcamera;

import java.util.HashSet;
import java.util.Set;

/** A made-up world of whole blocks for the camera tests: flat ground below a height, and blocks put in by hand */
final class VoxelWorld implements CameraWorld {

    private final Set<Long> blocks = new HashSet<>();
    private final int ground;

    /** @param ground everything below this height is solid; use a very low number for open sky */
    VoxelWorld(int ground) {
        this.ground = ground;
    }

    VoxelWorld block(int x, int y, int z) {
        this.blocks.add(key(x, y, z));
        return this;
    }

    VoxelWorld clear(int x, int y, int z) {
        this.blocks.remove(key(x, y, z));
        return this;
    }

    /** Every block in the box, both corners included */
    VoxelWorld box(int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                    this.block(x, y, z);
                }
            }
        }
        return this;
    }

    VoxelWorld empty(int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                    this.clear(x, y, z);
                }
            }
        }
        return this;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFF) << 42) | ((long) (y & 0xFFFFF) << 22) | (z & 0x3FFFFF);
    }

    @Override
    public boolean solid(Vec point) {
        return this.solidBlock((int) Math.floor(point.x()), (int) Math.floor(point.y()), (int) Math.floor(point.z()));
    }

    private boolean solidBlock(int x, int y, int z) {
        return y < this.ground || this.blocks.contains(key(x, y, z));
    }

    /** Walks the blocks the segment passes through, in order, and stops at the first solid one */
    @Override
    public double clip(Vec from, Vec to) {
        double dx = to.x() - from.x();
        double dy = to.y() - from.y();
        double dz = to.z() - from.z();
        int x = (int) Math.floor(from.x());
        int y = (int) Math.floor(from.y());
        int z = (int) Math.floor(from.z());
        if (this.solidBlock(x, y, z)) {
            return 0.0;
        }
        int stepX = dx > 0 ? 1 : -1;
        int stepY = dy > 0 ? 1 : -1;
        int stepZ = dz > 0 ? 1 : -1;
        double tDeltaX = dx == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dx);
        double tDeltaY = dy == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dy);
        double tDeltaZ = dz == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dz);
        double tMaxX = dx == 0 ? Double.MAX_VALUE : ((dx > 0 ? x + 1 - from.x() : from.x() - x) * tDeltaX);
        double tMaxY = dy == 0 ? Double.MAX_VALUE : ((dy > 0 ? y + 1 - from.y() : from.y() - y) * tDeltaY);
        double tMaxZ = dz == 0 ? Double.MAX_VALUE : ((dz > 0 ? z + 1 - from.z() : from.z() - z) * tDeltaZ);
        while (true) {
            double t;
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                t = tMaxX;
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY <= tMaxZ) {
                t = tMaxY;
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                t = tMaxZ;
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
            if (t > 1.0) {
                return 1.0;
            }
            if (this.solidBlock(x, y, z)) {
                return t;
            }
        }
    }

}
