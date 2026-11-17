package comedymonkey.dailies.playback;

import comedymonkey.dailies.Host;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * The invisible, free-flying camera a person controls while watching (specification PLAY-2). It is the playback
 * world's own player, a spectator, but it is moved here once per drawn frame and not by the game's ticks, so it
 * flies just as smoothly when the recording is paused, slowed to a crawl or being exported.
 */
public final class Viewer {

    private static double x, y, z;
    private static float yaw, pitch;
    private static float roll = 0.0F;
    private static double fov = 0.0;
    private static boolean placed = false;
    private static Vec3 velocity = Vec3.ZERO;
    private static long lastNanos = 0;
    private static double blocksPerSecond = 10.0;
    /** The height of the viewer's eyes above its feet */
    private static final double EYE_HEIGHT = 1.62;
    /** While true the camera is being driven (by a camera track, a tool) and the keys do not move it */
    private static boolean driven = false;
    /** The player the camera keeps with and keeps in view, by id: a jump puts new entities in the world */
    private static @Nullable UUID followed = null;
    /** Where that player was when the camera last moved with them */
    private static @Nullable Vec3 followedAt = null;

    private Viewer() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener((ViewportEvent.ComputeCameraAngles event) -> {
            if (Playback.loaded() && roll != 0.0F && watchingThroughOwnEyes()) {
                event.setRoll(roll);
            }
        });
        NeoForge.EVENT_BUS.addListener((ViewportEvent.ComputeFov event) -> {
            if (Playback.loaded() && fov > 0.0 && event.usedConfiguredFov()) {
                event.setFOV(fov);
            }
        });
    }

    static void reset() {
        placed = false;
        velocity = Vec3.ZERO;
        roll = 0.0F;
        fov = 0.0;
        driven = false;
        lastNanos = 0;
        followed = null;
        followedAt = null;
    }

    private static boolean watchingThroughOwnEyes() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null && minecraft.getCameraEntity() == minecraft.player;
    }

    /** Puts the camera a little behind and above the recorder, looking at them */
    static void startNear(Minecraft minecraft, String recorderId) {
        if (placed || minecraft.level == null || minecraft.player == null) {
            return;
        }
        Player recorder = null;
        try {
            recorder = minecraft.level.getPlayerByUUID(UUID.fromString(recorderId));
        } catch (IllegalArgumentException ignored) {
            // A recording without a recorder id still opens
        }
        if (recorder != null) {
            Vec3 back = Vec3.directionFromRotation(0.0F, recorder.getYRot()).scale(-4.0);
            set(recorder.getX() + back.x, recorder.getY() + 2.5, recorder.getZ() + back.z, recorder.getYRot(), 20.0F);
        } else {
            set(minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ(), minecraft.player.getYRot(), minecraft.player.getXRot());
        }
    }

    /** A new world has a new player object: put it where the camera was */
    static void place(Minecraft minecraft) {
        if (placed && minecraft.player != null) {
            apply(minecraft.player);
        }
    }

    public static void set(double newX, double newY, double newZ, float newYaw, float newPitch) {
        x = newX;
        y = newY;
        z = newZ;
        yaw = newYaw;
        pitch = Mth.clamp(newPitch, -90.0F, 90.0F);
        placed = true;
        velocity = Vec3.ZERO;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            apply(player);
        }
    }

    public static Vec3 position() {
        return new Vec3(x, y, z);
    }

    public static float yaw() {
        return yaw;
    }

    public static float pitch() {
        return pitch;
    }

    public static float roll() {
        return roll;
    }

    public static void setRoll(float degrees) {
        roll = degrees;
    }

    /** The field of view forced on the view, or 0 when the game's own setting is left alone */
    public static double fov() {
        return fov;
    }

    public static void setFov(double degrees) {
        fov = degrees <= 0.0 ? 0.0 : Mth.clamp(degrees, 1.0, 170.0);
    }

    /** Places the camera from a track. Unlike {@link #set} it leaves the flight speed alone */
    public static void drive(double newX, double newY, double newZ, float newYaw, float newPitch, float newRoll) {
        x = newX;
        y = newY;
        z = newZ;
        yaw = newYaw;
        pitch = Mth.clamp(newPitch, -90.0F, 90.0F);
        roll = newRoll;
        placed = true;
        velocity = Vec3.ZERO;
    }

    private static boolean movementWanted(Minecraft minecraft) {
        if (minecraft.screen != null || !minecraft.mouseHandler.isMouseGrabbed()) {
            return false;
        }
        Options options = minecraft.options;
        return options.keyUp.isDown() || options.keyDown.isDown() || options.keyLeft.isDown() || options.keyRight.isDown() || options.keyJump.isDown() || options.keyShift.isDown();
    }

    public static double blocksPerSecond() {
        return blocksPerSecond;
    }

    public static void setBlocksPerSecond(double speed) {
        blocksPerSecond = Mth.clamp(speed, 0.25, 400.0);
    }

    /** The entity whose eyes the view is through, or null when the camera flies free */
    public static @Nullable Entity riding() {
        Minecraft minecraft = Minecraft.getInstance();
        Entity camera = minecraft.getCameraEntity();
        return camera == null || camera == minecraft.player ? null : camera;
    }

    public static void ride(@Nullable Entity entity) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.setCameraEntity(entity == null ? minecraft.player : entity);
        }
        if (entity != null) {
            // Through their eyes there is nothing left to keep in view
            followed = null;
            followedAt = null;
        }
    }

    /** The player the camera is keeping in view, or null */
    public static @Nullable UUID following() {
        return followed;
    }

    /**
     * Keeps a player in view (specification PLAY-3): from now on the camera moves as they move, so it stays where it
     * is beside them, and turns to look at them. The keys still fly it round them. Null stops.
     */
    public static void follow(@Nullable UUID player) {
        followed = player;
        followedAt = null;
        if (player != null) {
            ride(null);
            followed = player;
        }
    }

    /** Once per drawn frame while the camera is its own: go where the followed player went and look at them */
    private static void keepInView(Minecraft minecraft) {
        UUID wanted = followed;
        if (wanted == null || minecraft.level == null || riding() != null) {
            return;
        }
        Player target = minecraft.level.getPlayerByUUID(wanted);
        if (target == null) {
            // Out of the world for now (a jump, or too far away): the camera waits and catches up when they are back
            return;
        }
        Vec3 now = target.getPosition(minecraft.getTimer().getGameTimeDeltaPartialTick(true));
        Vec3 before = followedAt;
        if (before != null) {
            x += now.x - before.x;
            y += now.y - before.y;
            z += now.z - before.z;
        }
        double away = Math.sqrt((x - now.x) * (x - now.x) + (y - now.y) * (y - now.y) + (z - now.z) * (z - now.z));
        if (before == null && (away > 24.0 || away < 1.0)) {
            // Begun from too far off to see them, or from inside them: a little behind and above, as playback opens
            Vec3 back = Vec3.directionFromRotation(0.0F, target.getYRot()).scale(-5.0);
            x = now.x + back.x;
            y = now.y + 2.0;
            z = now.z + back.z;
        }
        followedAt = now;
        double lookX = now.x - x;
        double lookY = now.y + target.getEyeHeight() - (y + EYE_HEIGHT);
        double lookZ = now.z - z;
        if (lookX * lookX + lookY * lookY + lookZ * lookZ > 1.0e-6) {
            yaw = (float) Math.toDegrees(Math.atan2(-lookX, lookZ));
            pitch = Mth.clamp((float) Math.toDegrees(-Math.atan2(lookY, Math.sqrt(lookX * lookX + lookZ * lookZ))), -90.0F, 90.0F);
        }
    }

    /** Once per drawn frame, after the game's ticks and before the picture */
    static void frame() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (!Playback.loaded() || player == null) {
            lastNanos = 0;
            return;
        }
        long now = System.nanoTime();
        double seconds = lastNanos == 0 ? 0.0 : Math.min(0.25, (now - lastNanos) / 1_000_000_000.0);
        lastNanos = now;
        if (!placed) {
            set(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        }
        boolean exporting = comedymonkey.dailies.export.Export.holdsClock();
        if (!exporting && movementWanted(minecraft) && comedymonkey.dailies.editor.EditorSession.followCamera() && !Playback.paused()) {
            // Flying off by hand takes the camera away from its track until following is switched back on (EDIT-5)
            comedymonkey.dailies.editor.EditorSession.setFollowCamera(false);
        }
        driven = comedymonkey.dailies.editor.EditorSession.drive(minecraft);
        if (!driven) {
            // The mouse turns the player as it always does; that is the camera's direction
            yaw = player.getYRot();
            pitch = Mth.clamp(player.getXRot(), -90.0F, 90.0F);
            if (!exporting) {
                fly(minecraft, seconds);
                keepInView(minecraft);
            }
        }
        Entity ridden = riding();
        if (ridden != null) {
            // The game draws the terrain around its own player, wherever the picture is taken from. A server moves a
            // spectator along with whoever they watch; here there is none, so the viewer is carried along, or the ground
            // would vanish as soon as the one ridden with had gone further than the eye can see from where the viewer was left
            Vec3 at = ridden.getPosition(minecraft.getTimer().getGameTimeDeltaPartialTick(true));
            x = at.x;
            y = at.y;
            z = at.z;
        }
        apply(player);
    }

    private static void fly(Minecraft minecraft, double seconds) {
        Vec3 wanted = Vec3.ZERO;
        if (minecraft.screen == null && minecraft.mouseHandler.isMouseGrabbed() && riding() == null) {
            Options options = minecraft.options;
            double forward = (options.keyUp.isDown() ? 1 : 0) - (options.keyDown.isDown() ? 1 : 0);
            double strafe = (options.keyLeft.isDown() ? 1 : 0) - (options.keyRight.isDown() ? 1 : 0);
            double lift = (options.keyJump.isDown() ? 1 : 0) - (options.keyShift.isDown() ? 1 : 0);
            boolean lookRelative = Host.get().setting("flightLookRelative", false);
            Vec3 ahead = Vec3.directionFromRotation(lookRelative ? pitch : 0.0F, yaw);
            Vec3 side = Vec3.directionFromRotation(0.0F, yaw - 90.0F);
            wanted = ahead.scale(forward).add(side.scale(strafe)).add(0.0, lift, 0.0);
            if (wanted.lengthSqr() > 1.0) {
                wanted = wanted.normalize();
            }
            wanted = wanted.scale(blocksPerSecond * (options.keySprint.isDown() ? 3.0 : 1.0));
        }
        if (Host.get().setting("flightMomentum", true)) {
            // Glide towards the wanted speed: about a fifth of a second to get there
            double blend = 1.0 - Math.exp(-seconds / 0.08);
            velocity = velocity.add(wanted.subtract(velocity).scale(blend));
            if (velocity.lengthSqr() < 1.0e-6) {
                velocity = Vec3.ZERO;
            }
        } else {
            velocity = wanted;
        }
        x += velocity.x * seconds;
        y += velocity.y * seconds;
        z += velocity.z * seconds;
    }

    private static void apply(LocalPlayer player) {
        player.setPos(x, y, z);
        player.xo = player.xOld = x;
        player.yo = player.yOld = y;
        player.zo = player.zOld = z;
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.yRotO = yaw;
        player.xRotO = pitch;
        player.yHeadRot = player.yHeadRotO = yaw;
        player.yBob = player.yBobO = yaw;
        player.xBob = player.xBobO = pitch;
        player.setDeltaMovement(Vec3.ZERO);
    }

}
