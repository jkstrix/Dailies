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
    /** The follow itself: the same whether someone watches or an export runs, stepped by the frame or by the tick */
    private static final comedymonkey.dailies.smartcamera.PanelFollow PANEL = new comedymonkey.dailies.smartcamera.PanelFollow();
    /** Whoever the view is through, by UUID and entity id, never by the entity object: a jump puts new entities in the world */
    private static final RideTarget RIDDEN = new RideTarget();

    /** The players panel's Follow, for the export's per-tick steps. Client thread */
    public static comedymonkey.dailies.smartcamera.PanelFollow panel() {
        return PANEL;
    }

    /**
     * How many times the players panel's Follow has cut (a teleport or a portal, or out from behind a wall it was stuck
     * behind): when it changes, the frame shows a new shot and is not to be judged as joined to the one before
     */
    public static int followCuts() {
        return PANEL.cuts();
    }

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
        RIDDEN.clear();
        stopFollowing();
    }

    private static void stopFollowing() {
        followed = null;
        PANEL.reset();
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
        if (minecraft.gui.screen() != null || !minecraft.mouseHandler.isMouseGrabbed()) {
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

    /**
     * The UUID of whoever the view is through, or null. It stays while they are out of the world for a moment (a jump
     * under way, a drag of the playhead), when {@link #riding()} is null, and the view goes back to them when they return
     */
    public static @Nullable UUID ridingId() {
        return RIDDEN.uuid();
    }

    public static void ride(@Nullable Entity entity) {
        Minecraft minecraft = Minecraft.getInstance();
        RIDDEN.ride(entity == null ? null : entity.getUUID(), entity == null ? -1 : entity.getId());
        if (minecraft.player != null) {
            minecraft.setCameraEntity(entity == null ? minecraft.player : entity);
        }
        if (entity != null) {
            // Through their eyes there is nothing left to keep in view
            stopFollowing();
        }
    }

    /**
     * Keeps the view through the eyes of whoever it rides with. A jump takes every entity out of the world and puts new
     * ones back, in the same world or a new one, so the game's own camera entity is then one that has been taken out:
     * left alone, the picture would stand frozen where they were when the jump began, however the playhead moves. Once
     * a frame, and as each jump lands, the view is put back on whoever has the same UUID now. While they are not in
     * the world the view is the viewer's own, from where they were last, and nothing of the ride is forgotten. Client thread
     */
    private static void keepRiding(Minecraft minecraft) {
        UUID wanted = RIDDEN.uuid();
        if (wanted == null || minecraft.player == null || minecraft.level == null) {
            return;
        }
        Entity camera = minecraft.getCameraEntity();
        if (camera != null && camera != minecraft.player && !camera.isRemoved() && camera.level() == minecraft.level && wanted.equals(camera.getUUID())) {
            RIDDEN.seen(camera.getId());
            return;
        }
        Entity found = find(minecraft.level, wanted, RIDDEN.id());
        if (found != null) {
            RIDDEN.seen(found.getId());
            minecraft.setCameraEntity(found);
            return;
        }
        // Never drawn from the one taken out, which stands frozen where it was
        if (camera != minecraft.player) {
            minecraft.setCameraEntity(minecraft.player);
        }
        if (RIDDEN.missing(Playback.position(), Playback.jumping() || Playback.rough())) {
            leaveRide();
        }
    }

    /** Whoever has this UUID in the world now: by the entity id they last had if that is still them, else by the UUID */
    private static @Nullable Entity find(net.minecraft.client.multiplayer.ClientLevel level, UUID uuid, int id) {
        Entity byId = id >= 0 ? level.getEntity(id) : null;
        if (byId != null && !byId.isRemoved() && uuid.equals(byId.getUUID())) {
            return byId;
        }
        Player player = level.getPlayerByUUID(uuid);
        if (player != null && !player.isRemoved()) {
            return player;
        }
        for (Entity entity : level.entitiesForRendering()) {
            if (!entity.isRemoved() && uuid.equals(entity.getUUID())) {
                return entity;
            }
        }
        return null;
    }

    /** Gives up riding with someone who is not in the world, and says so */
    private static void leaveRide() {
        RIDDEN.clear();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.setCameraEntity(minecraft.player);
        }
        comedymonkey.dailies.ui.EditorScreen.say(net.minecraft.network.chat.Component.translatable("dailies.editor.ride.gone"));
    }

    /**
     * A jump has landed. The view goes back through the eyes of whoever it rode with, and the players panel's Follow goes
     * on after the same player; when either is not in the world at this tick the camera flies free from here, with a
     * notice. A rough landing, one of the many made while the playhead is dragged, gives up neither: the exact landing
     * that follows the drag decides. Client thread
     */
    static void arrived(Minecraft minecraft, boolean rough) {
        keepRiding(minecraft);
        if (rough || minecraft.level == null) {
            return;
        }
        if (RIDDEN.landed(riding() != null, false)) {
            leaveRide();
        }
        UUID wanted = followed;
        if (wanted != null && minecraft.level.getPlayerByUUID(wanted) == null) {
            stopFollowing();
            comedymonkey.dailies.ui.EditorScreen.say(net.minecraft.network.chat.Component.translatable("dailies.editor.follow.gone"));
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
        stopFollowing();
        followed = player;
        if (player != null) {
            ride(null);
            followed = player;
        }
    }

    /**
     * Once per drawn frame while the camera is its own: go where the followed player went and look at them. The player
     * is followed on a spring led by their velocity, so the camera keeps the place the person flew it to beside them
     * while hops and bumps are smoothed away, and it is pulled in before blocks come between them and eased back out,
     * as the chase camera is. Timed by the recording during an export, so the film is the same every time.
     */
    private static void keepInView(Minecraft minecraft, double seconds, boolean exporting) {
        UUID wanted = followed;
        if (wanted == null || minecraft.level == null || riding() != null) {
            return;
        }
        if (exporting) {
            // In an export the follow is stepped once per tick of the recording with the others, and drawn between
            comedymonkey.dailies.smartcamera.PoseBlend.Pose pose = comedymonkey.dailies.editor.CameraSteps.panelPose(Playback.frameTime());
            if (pose != null) {
                x = pose.x();
                y = pose.y() - EYE_HEIGHT;
                z = pose.z();
                yaw = (float) pose.yaw();
                pitch = Mth.clamp((float) pose.pitch(), -90.0F, 90.0F);
            }
            return;
        }
        Player target = minecraft.level.getPlayerByUUID(wanted);
        if (target == null) {
            // Out of the world for now (a jump, or too far away): the camera waits and catches up when they are back
            return;
        }
        Vec3 feet = target.getPosition(minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true));
        Vec3 eyes = feet.add(0.0, target.getEyeHeight(), 0.0);
        double fov = Viewer.fov > 0.0 ? Viewer.fov : minecraft.options.fov().get();
        double aspect = minecraft.getWindow().getWidth() / (double) Math.max(1, minecraft.getWindow().getHeight());
        comedymonkey.dailies.smartcamera.TetherSolver.Result result = PANEL.step(new comedymonkey.dailies.smartcamera.LevelCameraWorld(minecraft.level, null),
            new comedymonkey.dailies.smartcamera.Vec(eyes.x, eyes.y, eyes.z), new comedymonkey.dailies.smartcamera.Vec(x, y + EYE_HEIGHT, z), target.getYRot(),
            target.getEyeHeight(), target.getBbHeight(), target.getBbWidth(), fov, aspect, seconds);
        x = result.eye().x();
        y = result.eye().y() - EYE_HEIGHT;
        z = result.eye().z();
        yaw = (float) result.yaw();
        pitch = Mth.clamp((float) result.pitch(), -90.0F, 90.0F);
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
        keepRiding(minecraft);
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
            }
            keepInView(minecraft, seconds, exporting);
        }
        Entity ridden = riding();
        if (ridden != null) {
            // The game draws the terrain around its own player, wherever the picture is taken from. A server moves a
            // spectator along with whoever they watch; here there is none, so the viewer is carried along, or the ground
            // would vanish as soon as the one ridden with had gone further than the eye can see from where the viewer was left
            Vec3 at = ridden.getPosition(minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true));
            x = at.x;
            y = at.y;
            z = at.z;
        }
        apply(player);
    }

    private static void fly(Minecraft minecraft, double seconds) {
        Vec3 wanted = Vec3.ZERO;
        if (minecraft.gui.screen() == null && minecraft.mouseHandler.isMouseGrabbed() && riding() == null) {
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
