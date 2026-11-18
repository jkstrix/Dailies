package comedymonkey.dailies.smartcamera;

import comedymonkey.dailies.Host;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * The level as the camera solvers see it: blocks with a collision shape are solid (leaves and glass among them, so a
 * camera never hides behind a hedge), fluids are not. With Sable installed the blocks of physics objects count too,
 * except those of the craft being framed, which does not block the view of itself.
 */
public final class LevelCameraWorld implements CameraWorld {

    /** Far beyond any sensible shot. Keeps a camera left far behind from costing a long walk through the world */
    private static final double MAX_RAY_LENGTH = 256.0;

    private final ClientLevel level;
    private final @Nullable Object ownSubLevel;

    /**
     * @param ownSubLevel the physics object that is being framed, or null
     */
    public LevelCameraWorld(ClientLevel level, @Nullable Object ownSubLevel) {
        this.level = level;
        this.ownSubLevel = ownSubLevel;
    }

    @Override
    public double clip(Vec from, Vec to) {
        Vec3 start = new Vec3(from.x(), from.y(), from.z());
        Vec3 end = new Vec3(to.x(), to.y(), to.z());
        double length = start.distanceTo(end);
        if (length < 1.0e-4) {
            return 1.0;
        }
        if (length > MAX_RAY_LENGTH) {
            end = start.add(end.subtract(start).scale(MAX_RAY_LENGTH / length));
        }
        Vec3 hit = clip(this.level, this.ownSubLevel, start, end);
        return hit == null ? 1.0 : Math.min(1.0, hit.distanceTo(start) / length);
    }

    @Override
    public boolean solid(Vec point) {
        BlockPos pos = BlockPos.containing(point.x(), point.y(), point.z());
        BlockState state = this.level.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        VoxelShape shape = state.getCollisionShape(this.level, pos, CollisionContext.empty());
        if (shape.isEmpty()) {
            return false;
        }
        double x = point.x() - pos.getX();
        double y = point.y() - pos.getY();
        double z = point.z() - pos.getZ();
        for (AABB box : shape.toAabbs()) {
            if (x >= box.minX && x <= box.maxX && y >= box.minY && y <= box.maxY && z >= box.minZ && z <= box.maxZ) {
                return true;
            }
        }
        return false;
    }

    /**
     * The first solid block between two points, ignoring fluids, or null when the way is clear.
     *
     * @param ownSubLevel the physics object that is being framed, or null. Its blocks are not in the way of its own camera
     */
    static @Nullable Vec3 clip(ClientLevel level, @Nullable Object ownSubLevel, Vec3 from, Vec3 to) {
        ClipContext context = new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty());
        BlockHitResult hit = level.clip(context);
        if (hit.getType() == HitResult.Type.MISS) {
            return null;
        }

        // With Sable installed this also finds the blocks of physics objects, and reports those hits where the
        // blocks are stored: in the plot region, millions of blocks away
        Vec3 location = hit.getLocation();
        if (!Host.crafts().isInPlot(level, location.x, location.z)) {
            return location;
        }
        if (ownSubLevel == null || Host.crafts().getSubLevelAt(level, location) != ownSubLevel) {
            return Host.crafts().projectOutOfSubLevel(level, location);
        }

        // The ray ended at the framed object itself. Look again at the terrain only, the way vanilla's clip does
        BlockHitResult terrain = BlockGetter.traverseBlocks(from, to, context, (clipContext, blockPos) -> {
            BlockState blockState = level.getBlockState(blockPos);
            VoxelShape shape = clipContext.getBlockShape(blockState, level, blockPos);
            return level.clipWithInteractionOverride(clipContext.getFrom(), clipContext.getTo(), blockPos, shape, blockState);
        }, clipContext -> null);
        if (terrain == null || Host.crafts().isInPlot(level, terrain.getLocation().x, terrain.getLocation().z)) {
            return null;
        }
        return terrain.getLocation();
    }

}
