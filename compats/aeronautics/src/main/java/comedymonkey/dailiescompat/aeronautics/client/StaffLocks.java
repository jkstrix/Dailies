package comedymonkey.dailiescompat.aeronautics.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import comedymonkey.dailiescompat.aeronautics.state.AeroStates;
import dev.simulated_team.simulated.SimulatedClient;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffItem;
import dev.simulated_team.simulated.index.SimRenderTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3dc;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The locks Simulated draws into the world while the player holds a physics staff: a padlock facing the camera on each
 * craft the staff has locked in place. Recorded as where each was drawn, at the end of each tick (the locked crafts come
 * from the server, and only the holder sees them); drawn in playback with Simulated's own lock texture at those places,
 * as the overlay's part in the world, so it follows the owner's rule like every other overlay. The block the staff points
 * at, which Simulated outlines through Catnip's outliner, is recorded by the Create compat with Create's outlines.
 */
final class StaffLocks {

    static final String NAME = "physics_staff_locks";

    private static AeroStates.@Nullable Points shown = null;
    private static long drawnAt = Long.MIN_VALUE;

    private StaffLocks() {
    }

    static byte @Nullable [] capture() {
        Minecraft minecraft = Minecraft.getInstance();
        // As PhysicsStaffRenderHandler.renderSelectionBox decides whether to draw them
        if (minecraft.options.hideGui || minecraft.player == null || minecraft.level == null || !PhysicsStaffItem.isHolding(minecraft.player)) {
            return null;
        }
        List<UUID> locks = Reflect.call(SimulatedClient.PHYSICS_STAFF_CLIENT_HANDLER, "getLocks", new Class<?>[]{Level.class}, minecraft.level);
        if (locks == null || locks.isEmpty()) {
            return null;
        }
        List<Vector3dc> places = new ArrayList<>();
        for (UUID lock : locks) {
            Vector3dc place = renderPlace(minecraft.level, lock);
            if (place != null) {
                places.add(place);
            }
        }
        if (places.isEmpty()) {
            return null;
        }
        double[] coords = new double[places.size() * 3];
        for (int i = 0; i < places.size(); i++) {
            coords[i * 3] = places.get(i).x();
            coords[i * 3 + 1] = places.get(i).y();
            coords[i * 3 + 2] = places.get(i).z();
        }
        return AeroStates.points(new AeroStates.Points(coords));
    }

    /**
     * Where a locked craft is drawn, by Sable's own container of crafts, as Simulated finds it; through Sable's classes
     * by name, which the compat does not need to compile against for this alone
     */
    private static @Nullable Vector3dc renderPlace(Level level, UUID craft) {
        try {
            Class<?> containers = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
            Object container = containers.getMethod("getContainer", Level.class).invoke(null, level);
            if (container == null) {
                return null;
            }
            Object subLevel = container.getClass().getMethod("getSubLevel", UUID.class).invoke(container, craft);
            if (subLevel == null || !subLevel.getClass().getName().equals("dev.ryanhcode.sable.sublevel.ClientSubLevel")) {
                return null;
            }
            Method renderPose = subLevel.getClass().getMethod("renderPose");
            Object pose = renderPose.invoke(subLevel);
            // The pose's own class may be one Sable keeps to itself; its position is asked through it all the same
            Method position = pose.getClass().getMethod("position");
            position.setAccessible(true);
            Object place = position.invoke(pose);
            return place instanceof Vector3dc at ? at : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError none) {
            return null;
        }
    }

    /** The overlay's drawing over the world: notes the locks for the world's next drawing, which is where they show */
    static void note(byte[] state) {
        shown = AeroStates.readPoints(state);
        drawnAt = InWorld.frame();
    }

    /** As PhysicsStaffRenderHandler.renderAllLocks draws them: a padlock facing the camera at each place */
    static void drawWorld(PoseStack ms, MultiBufferSource.BufferSource buffers, Vec3 camera) {
        AeroStates.Points points = shown;
        if (points == null || !InWorld.noted(drawnAt)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        RenderType type = SimRenderTypes.lock();
        double[] coords = points.coords();
        for (int i = 0; i < points.count(); i++) {
            ms.pushPose();
            ms.translate(coords[i * 3] - camera.x, coords[i * 3 + 1] - camera.y, coords[i * 3 + 2] - camera.z);
            ms.mulPose(minecraft.getEntityRenderDispatcher().cameraOrientation());
            VertexConsumer buffer = buffers.getBuffer(type);
            PoseStack.Pose pose = ms.last();
            buffer.addVertex(pose, -0.5F, -0.5F, 0.0F).setColor(-1).setUv(0.0F, 1.0F).setLight(15728880);
            buffer.addVertex(pose, -0.5F, 0.5F, 0.0F).setColor(-1).setUv(0.0F, 0.0F).setLight(15728880);
            buffer.addVertex(pose, 0.5F, 0.5F, 0.0F).setColor(-1).setUv(1.0F, 0.0F).setLight(15728880);
            buffer.addVertex(pose, 0.5F, -0.5F, 0.0F).setColor(-1).setUv(1.0F, 1.0F).setLight(15728880);
            ms.popPose();
        }
        buffers.endBatch(type);
    }

}
