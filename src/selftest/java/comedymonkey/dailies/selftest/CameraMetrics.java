package comedymonkey.dailies.selftest;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.editor.SmartCameraDriver;
import comedymonkey.dailies.export.Export;
import comedymonkey.dailies.filming.FilmFrame;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.Viewer;
import comedymonkey.dailies.smartcamera.SmartCameraSolver;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Notes, for every frame a camera test exports, where the camera was and what it saw of its player (specification
 * FILM-4). It listens after the export has taken the frame's picture, so what it notes is what is in that picture. The
 * measures themselves are worked out by {@link comedymonkey.dailies.filming.FilmMeasures}, which has no game types.
 */
final class CameraMetrics {

    private static boolean hooked = false;
    private static volatile boolean collecting = false;
    private static volatile UUID subject = null;
    private static volatile boolean smart = false;
    private static volatile double framesPerSecond = 60.0;
    private static long lastFrame = 0;
    private static final List<FilmFrame> frames = new ArrayList<>();

    private CameraMetrics() {
    }

    /** Starts noting frames for the export about to begin. Any thread */
    static synchronized void begin(UUID filmed, boolean smartCamera, double fps) {
        if (!hooked) {
            hooked = true;
            // Lowest, so the export has taken its picture of this frame before it is noted
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, RenderFrameEvent.Post.class, event -> sample());
        }
        synchronized (frames) {
            frames.clear();
        }
        subject = filmed;
        smart = smartCamera;
        framesPerSecond = fps;
        lastFrame = 0;
        collecting = true;
    }

    /** Stops, and hands back every frame noted, in order */
    static List<FilmFrame> end() {
        collecting = false;
        synchronized (frames) {
            return new ArrayList<>(frames);
        }
    }

    private static void sample() {
        if (!collecting || !Export.active()) {
            return;
        }
        long taken = Export.frame();
        if (taken <= lastFrame) {
            return;
        }
        lastFrame = taken;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        try {
            FilmFrame frame = new FilmFrame();
            frame.frame = (int) (taken - 1);
            frame.seconds = frame.frame / framesPerSecond;
            frame.tick = Playback.frameTime();

            Camera camera = minecraft.gameRenderer.mainCamera();
            Vec3 eye = camera.position();
            frame.camX = eye.x;
            frame.camY = eye.y;
            frame.camZ = eye.z;
            frame.yaw = camera.yRot();
            frame.pitch = camera.xRot();
            frame.roll = Viewer.roll();
            frame.fov = Viewer.fov() > 0.0 ? Viewer.fov() : minecraft.options.fov().get();
            frame.aspect = minecraft.getWindow().getHeight() > 0 ? minecraft.getWindow().getWidth() / (double) minecraft.getWindow().getHeight() : 16.0 / 9.0;
            frame.cameraInBlock = insideBlock(minecraft, eye);

            UUID wanted = subject;
            Entity entity = wanted == null ? null : minecraft.level.getEntities().get(wanted);
            if (entity != null && entity != minecraft.player) {
                float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true);
                Vec3 feet = where(minecraft, entity, partial);
                double half = entity.getBbWidth() / 2.0;
                AABB box = new AABB(feet.x - half, feet.y, feet.z - half, feet.x + half, feet.y + entity.getBbHeight(), feet.z + half);
                frame.subjectPresent = true;
                frame.eyeX = feet.x;
                frame.eyeY = feet.y + entity.getEyeHeight();
                frame.eyeZ = feet.z;
                frame.subjectYaw = entity.getVisualRotationYInDegrees();
                frame.vehicle = entity.getVehicle() == null ? -1 : entity.getVehicle().getId();
                frame.dimension = minecraft.level.dimension().identifier().toString();
                frame.minX = box.minX;
                frame.minY = box.minY;
                frame.minZ = box.minZ;
                frame.maxX = box.maxX;
                frame.maxY = box.maxY;
                frame.maxZ = box.maxZ;
                // A camera tied to the player looks at their eyes and means to keep them in the middle of the frame
                frame.aimX = frame.eyeX;
                frame.aimY = frame.eyeY;
                frame.aimZ = frame.eyeZ;
                double[][] points = frame.points();
                for (int i = 0; i < points.length; i++) {
                    Vec3 point = new Vec3(points[i][0], points[i][1], points[i][2]);
                    BlockHitResult hit = minecraft.level.clip(new ClipContext(eye, point, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
                    if (hit.getType() == HitResult.Type.BLOCK && hit.getLocation().distanceTo(eye) < eye.distanceTo(point) - 0.05) {
                        frame.occludedMask |= 1 << i;
                    }
                }
            }
            if (!smart) {
                // The keyframe cameras and the players panel's Follow cut too (a teleport, a move to a new watching
                // place): each cut is a new shot, so the film is split there rather than judged as one swing
                frame.shot = comedymonkey.dailies.editor.CameraSteps.cuts() + Viewer.followCuts();
            }
            if (smart) {
                SmartCameraSolver solver = SmartCameraDriver.lastSolver();
                SmartCameraSolver.Result result = solver == null ? null : solver.lastResult();
                if (result != null) {
                    frame.shot = result.shotIndex();
                    frame.shotKind = result.shotKind();
                    frame.targetX = result.targetScreenX();
                    frame.targetY = result.targetScreenY();
                    // The Smart Camera places the point it calls the subject, not the eyes
                    frame.aimX = result.subjectX();
                    frame.aimY = result.subjectY();
                    frame.aimZ = result.subjectZ();
                }
            }
            synchronized (frames) {
                frames.add(frame);
            }
        } catch (RuntimeException e) {
            // A frame that cannot be noted is a gap in the measures, never a broken export
            Host.LOGGER.warn("Dailies self test: frame {} of a camera film could not be noted", taken - 1, e);
        }
    }

    /** Where the player's feet are seen, brought out of a physics craft's hidden region when they ride one */
    private static Vec3 where(Minecraft minecraft, Entity entity, float partial) {
        boolean inPlot = Host.crafts().isInPlot(minecraft.level, entity.getX(), entity.getZ());
        Vec3 local = entity.getPosition(partial);
        if (inPlot != Host.crafts().isInPlot(minecraft.level, entity.xo, entity.zo)) {
            local = entity.position();
        }
        return inPlot ? Host.crafts().projectOutOfSubLevel(minecraft.level, local, partial) : local;
    }

    private static boolean insideBlock(Minecraft minecraft, Vec3 eye) {
        BlockPos pos = BlockPos.containing(eye);
        BlockState state = minecraft.level.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        for (AABB part : state.getCollisionShape(minecraft.level, pos).toAabbs()) {
            if (part.move(pos).contains(eye)) {
                return true;
            }
        }
        return false;
    }

}
