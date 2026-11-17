package comedymonkey.dailies.ui;

import comedymonkey.dailies.playback.Viewer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Finds what is under the mouse in the world behind the editor: the entity or the block a click in the view points
 * at. A line is drawn from the camera through the point of the picture under the mouse, using the field of view the
 * game last drew with, and the first thing it meets is the answer.
 */
final class ViewPick {

    private static final double REACH = 160.0;

    /** The field of view, in degrees up and down, the world was last drawn with */
    private static volatile double fieldOfView = 70.0;

    /** A line of sight */
    record Ray(Vec3 from, Vec3 direction) {
        Vec3 at(double distance) {
            return this.from.add(this.direction.scale(distance));
        }
    }

    private ViewPick() {
    }

    static void init() {
        // Last of all, so that this is the field of view really used
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (ViewportEvent.ComputeFov event) -> {
            if (event.usedConfiguredFov()) {
                fieldOfView = event.getFOV();
            }
        });
    }

    static double fieldOfView() {
        return fieldOfView;
    }

    /** The line of sight through a point of the screen, in the screen's own measure; null when there is no world */
    static @Nullable Ray through(double mouseX, double mouseY, int screenWidth, int screenHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || screenWidth <= 0 || screenHeight <= 0) {
            return null;
        }
        Camera camera = minecraft.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) {
            return null;
        }
        double across = 2.0 * mouseX / screenWidth - 1.0;
        double up = 1.0 - 2.0 * mouseY / screenHeight;
        // A picture held to its frame is shown smaller and to one side, clear of the panels
        double[] shift = comedymonkey.dailies.looks.LooksRenderer.viewShift();
        if (shift != null) {
            across = (across - shift[1]) / shift[0];
            up = (up - shift[2]) / shift[0];
        }
        double tangent = Math.tan(Math.toRadians(Math.max(1.0, Math.min(170.0, fieldOfView)) / 2.0));
        double aspect = (double) minecraft.getWindow().getWidth() / Math.max(1, minecraft.getWindow().getHeight());
        Vector3f look = camera.getLookVector();
        Vector3f above = camera.getUpVector();
        Vector3f left = camera.getLeftVector();
        double sideways = -across * tangent * aspect;
        double upwards = up * tangent;
        Vec3 direction = new Vec3(
                look.x() + left.x() * sideways + above.x() * upwards,
                look.y() + left.y() * sideways + above.y() * upwards,
                look.z() + left.z() * sideways + above.z() * upwards).normalize();
        return new Ray(camera.getPosition(), direction);
    }

    /** The line of sight straight ahead of the viewer */
    static @Nullable Ray ahead() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return null;
        }
        Camera camera = minecraft.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) {
            return null;
        }
        Vector3f look = camera.getLookVector();
        return new Ray(camera.getPosition(), new Vec3(look.x(), look.y(), look.z()).normalize());
    }

    /** The first block the line of sight meets, or null when it meets none within reach */
    static @Nullable BlockHitResult block(@Nullable Ray ray) {
        Minecraft minecraft = Minecraft.getInstance();
        if (ray == null || minecraft.level == null || minecraft.player == null) {
            return null;
        }
        BlockHitResult hit = minecraft.level.clip(new ClipContext(ray.from(), ray.at(REACH), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, minecraft.player));
        return hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    /** The first entity the line of sight meets before it meets a block, or null. Never the viewer itself */
    static @Nullable Entity entity(@Nullable Ray ray, boolean playersOnly) {
        Minecraft minecraft = Minecraft.getInstance();
        if (ray == null || minecraft.level == null) {
            return null;
        }
        BlockHitResult wall = block(ray);
        double limit = wall == null ? REACH : wall.getLocation().distanceTo(ray.from()) + 0.5;
        Vec3 end = ray.at(limit);
        Entity nearest = null;
        double best = Double.MAX_VALUE;
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity == minecraft.player || entity == Viewer.riding() || (playersOnly && !(entity instanceof Player))) {
                continue;
            }
            AABB box = entity.getBoundingBox().inflate(0.2);
            double distance;
            if (box.contains(ray.from())) {
                distance = 0.0;
            } else {
                Optional<Vec3> met = box.clip(ray.from(), end);
                if (met.isEmpty()) {
                    continue;
                }
                distance = met.get().distanceTo(ray.from());
            }
            if (distance < best) {
                best = distance;
                nearest = entity;
            }
        }
        return nearest;
    }

    /** The entities around the viewer, nearest first. Never the viewer itself */
    static List<Entity> nearby(boolean playersOnly, int most) {
        Minecraft minecraft = Minecraft.getInstance();
        List<Entity> found = new ArrayList<>();
        if (minecraft.level == null) {
            return found;
        }
        Vec3 from = Viewer.position();
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity != minecraft.player && (!playersOnly || entity instanceof Player)) {
                found.add(entity);
            }
        }
        found.sort(Comparator.comparingDouble(entity -> entity.distanceToSqr(from)));
        return found.size() > most ? new ArrayList<>(found.subList(0, most)) : found;
    }

    /** A name for an entity that a person can tell it by: its name, and its kind when that says more */
    static String describe(Entity entity) {
        String name = entity.getName().getString();
        String kind = entity.getType().getDescription().getString();
        long blocks = Math.round(Math.sqrt(entity.distanceToSqr(Viewer.position())));
        return name.equals(kind) ? name + "  " + blocks + " m" : name + " (" + kind + ")  " + blocks + " m";
    }

}
