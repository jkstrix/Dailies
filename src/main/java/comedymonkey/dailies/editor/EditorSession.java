package comedymonkey.dailies.editor;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.edit.EditHistory;
import comedymonkey.dailies.edit.EditState;
import comedymonkey.dailies.edit.Evaluator;
import comedymonkey.dailies.edit.Keyframe;
import comedymonkey.dailies.edit.KeyframeKind;
import comedymonkey.dailies.edit.Scene;
import comedymonkey.dailies.edit.Track;
import comedymonkey.dailies.file.RecordingFile;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.Viewer;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The editor's state for the recording that is open (specification chapter V): its scenes, tracks and keyframes with
 * their undo history, saved into the recording as they change, and applied to the view every frame while the
 * recording plays or exports. The screens and the MCP tools both work through {@link #history()}, so they share one
 * undo stack.
 */
public final class EditorSession {

    private static final long SAVE_AFTER_MILLIS = 750;

    private static volatile EditHistory history = null;
    private static RecordingFile file = null;
    private static long savedRevision = -1;
    private static long changedAt = 0;
    private static volatile boolean followCamera = true;
    private static volatile boolean exporting = false;
    private static final ExecutorService SAVER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Dailies-Editor-Save");
        thread.setDaemon(true);
        return thread;
    });

    private EditorSession() {
    }

    /** A recording has been opened: take up the editor state stored in it, or start with one empty scene */
    public static void opened(RecordingFile recording) {
        EditState state = new EditState();
        try {
            byte[] stored = recording.editState();
            if (stored != null) {
                state = EditState.fromBytes(stored);
            }
        } catch (Exception e) {
            Host.LOGGER.warn("Dailies: the editor state stored in the recording could not be read, so editing starts afresh", e);
        }
        EditHistory fresh = new EditHistory(state);
        fresh.addListener((changed, description) -> changedAt = System.currentTimeMillis());
        file = recording;
        savedRevision = fresh.revision();
        followCamera = true;
        history = fresh;
        PointOfView.home(recording);
        PointOfView.prepare(fresh.scene());
    }

    /** The recording is about to be closed: nothing edited may be lost (EDIT-7) */
    public static void closing() {
        SmartCameraDriver.forget();
        CameraSteps.forget();
        EditHistory closingHistory = history;
        RecordingFile closingFile = file;
        history = null;
        file = null;
        // The background export game reads recordings and never writes to them (EXP-13)
        if (closingHistory != null && closingFile != null && closingHistory.revision() != savedRevision && !comedymonkey.dailies.export.Worker.active()) {
            byte[] bytes = closingHistory.state().toBytes();
            try {
                // After anything the saver thread still has in hand, and before the file is closed
                SAVER.submit(() -> {
                    closingFile.saveEditState(bytes);
                    return null;
                }).get(20, TimeUnit.SECONDS);
            } catch (Exception e) {
                Host.LOGGER.error("Dailies: the last editor changes could not be saved", e);
            }
        }
    }

    public static boolean open() {
        return history != null;
    }

    /** The open recording's editor state and undo history. Client thread */
    public static EditHistory history() {
        EditHistory current = history;
        if (current == null) {
            throw new IllegalStateException("No recording is open");
        }
        return current;
    }

    public static Scene scene() {
        return history().scene();
    }

    public static boolean followCamera() {
        return followCamera;
    }

    /** Whether the camera tracks may move the view (EDIT-5). The viewer flying off by hand switches it off */
    public static void setFollowCamera(boolean follow) {
        followCamera = follow;
    }

    public static void setExporting(boolean now) {
        exporting = now;
    }

    public static boolean exporting() {
        return exporting;
    }

    /** Once a frame: save what has changed, once it has been still for a moment */
    private static void autosave() {
        EditHistory current = history;
        RecordingFile target = file;
        if (current == null || target == null || current.revision() == savedRevision || System.currentTimeMillis() - changedAt < SAVE_AFTER_MILLIS
            || comedymonkey.dailies.export.Worker.active()) {
            return;
        }
        long revision = current.revision();
        byte[] bytes = current.state().toBytes();
        savedRevision = revision;
        SAVER.execute(() -> {
            try {
                target.saveEditState(bytes);
            } catch (Exception e) {
                Host.LOGGER.error("Dailies: the editor state could not be saved", e);
            }
        });
    }

    /**
     * Saves what has changed now, without waiting for things to be still, and waits for it to be on disk: the
     * background export game reads scenes from the file (EXP-13). Client thread
     */
    public static void saveNow() {
        EditHistory current = history;
        RecordingFile target = file;
        if (current == null || target == null || current.revision() == savedRevision || comedymonkey.dailies.export.Worker.active()) {
            return;
        }
        long revision = current.revision();
        byte[] bytes = current.state().toBytes();
        savedRevision = revision;
        try {
            SAVER.submit(() -> {
                target.saveEditState(bytes);
                return null;
            }).get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            Host.LOGGER.error("Dailies: the editor state could not be saved", e);
        }
    }

    /** The playback speed a speed track asks for at this moment, or 0 when none applies */
    public static double speedNow() {
        EditHistory current = history;
        if (current == null || exporting) {
            return 0.0;
        }
        try {
            Scene scene = current.scene();
            double tick = PointOfView.sceneTime(Playback.frameTime());
            return Evaluator.applyingTrack(scene, KeyframeKind.SPEED, tick) == null ? 0.0 : Evaluator.speed(scene, tick);
        } catch (RuntimeException e) {
            return 0.0;
        }
    }

    /**
     * Once per drawn frame, before the viewer is placed: lets the scene's tracks set the view.
     *
     * @return true when a camera track has placed the camera, so the keys must not move it
     */
    public static boolean drive(Minecraft minecraft) {
        EditHistory current = history;
        if (current == null) {
            return false;
        }
        autosave();
        Scene scene = current.scene();
        // Every keyframe is in the scene's own ticks, whichever point of view is shown (PLAY-19)
        double tick = PointOfView.sceneTime(Playback.frameTime());
        // Keyframes take effect while playing or exporting, never while the recording stands still (EDIT-5)
        if (Playback.paused() && !exporting) {
            PointOfView.frame(scene, tick, false);
            return false;
        }
        if (!exporting) {
            PointOfView.frame(scene, tick, true);
        }
        boolean placed = false;
        try {
            Double fov = Evaluator.fieldOfView(scene, tick);
            Viewer.setFov(fov != null ? fov : scene.looks().fov);
            Double time = Evaluator.timeOfDay(scene, tick);
            if (time != null && minecraft.level != null) {
                minecraft.level.getLevelData().setDayTime(Math.round(time));
            }
            if (followCamera || exporting) {
                placed = placeCamera(minecraft, scene, tick);
            }
        } catch (RuntimeException e) {
            Host.LOGGER.warn("Dailies: a keyframe could not be applied at tick {}", tick, e);
        }
        return placed;
    }

    private static boolean placeCamera(Minecraft minecraft, Scene scene, double tick) {
        Track driver = Evaluator.cameraDriver(scene, tick);
        if (driver == null) {
            return false;
        }
        Evaluator.CameraPose pose = null;
        switch (driver.kind()) {
            case CAMERA -> pose = Evaluator.camera(scene, tick);
            case ORBIT -> {
                Evaluator.OrbitValue orbit = Evaluator.orbit(scene, tick);
                if (orbit != null && orbit.onEntity()) {
                    // Centred on an entity: stepped with the recording's ticks, smoothed and kept clear of blocks
                    pose = CameraSteps.pose(driver, tick);
                } else {
                    pose = orbit == null ? null : orbit.pose();
                }
            }
            case FOLLOW_ENTITY -> {
                pose = CameraSteps.pose(driver, tick);
                if (pose == null) {
                    // Not stepped yet (the first frame of a stretch): straight from the entity until it is
                    pose = follow(minecraft, Evaluator.follow(scene, tick));
                }
            }
            case SMART_CAMERA -> pose = SmartCameraDriver.pose(driver, tick);
            default -> {
            }
        }
        if (pose == null) {
            return false;
        }
        double yaw = pose.yaw();
        double pitch = pose.pitch();
        Evaluator.ShakeValue shake = Evaluator.shake(scene, tick);
        if (shake != null && shake.strength() > 0.0) {
            // A hand-held wobble: two slow waves that never quite repeat, timed by the recording so exports match playback
            double seconds = tick / 20.0 * Math.max(0.1, shake.frequency());
            yaw += shake.strength() * (Math.sin(seconds * 6.1) * 0.6 + Math.sin(seconds * 13.7 + 1.3) * 0.4);
            pitch += shake.strength() * (Math.sin(seconds * 7.3 + 0.7) * 0.6 + Math.sin(seconds * 11.9 + 2.1) * 0.4);
        }
        Viewer.drive(pose.x(), pose.y(), pose.z(), (float) yaw, (float) Mth.clamp(pitch, -90.0, 90.0), (float) (pose.roll() + scene.looks().roll));
        return true;
    }

    private static @Nullable Evaluator.CameraPose follow(Minecraft minecraft, @Nullable Evaluator.FollowValue follow) {
        if (follow == null || minecraft.level == null) {
            return null;
        }
        Entity entity = minecraft.level.getEntity(follow.entityId());
        if (entity == null || (follow.entityUuid() != null && !entity.getUUID().equals(follow.entityUuid()))) {
            entity = null;
            for (Entity candidate : minecraft.level.entitiesForRendering()) {
                if (candidate.getUUID().equals(follow.entityUuid())) {
                    entity = candidate;
                }
            }
        }
        if (entity == null) {
            return null;
        }
        float partial = minecraft.getTimer().getGameTimeDeltaPartialTick(true);
        Vec3 feet = entity.getPosition(partial);
        double up = switch (follow.part()) {
            case FEET -> 0.0;
            case BODY -> entity.getBbHeight() * 0.5;
            case HEAD -> entity.getBbHeight() * 0.9;
            case EYES -> entity.getEyeHeight();
        };
        Vec3 target = feet.add(0.0, up, 0.0);
        Vec3 camera;
        if (follow.mode() == Keyframe.FollowMode.WATCH_FROM_FIXED) {
            // Stays where the viewer is and turns to watch
            camera = Viewer.position();
        } else {
            camera = target.add(follow.offsetX(), follow.offsetY(), follow.offsetZ());
        }
        if (follow.mode() == Keyframe.FollowMode.WATCH_FROM_FIXED) {
            // The offset names the point watched
            target = target.add(follow.offsetX(), follow.offsetY(), follow.offsetZ());
        }
        // Looked at from the viewer's eyes, not its feet
        Vec3 look = target.subtract(camera.add(0.0, 1.62, 0.0));
        if (look.lengthSqr() < 1.0e-6) {
            return new Evaluator.CameraPose(camera.x, camera.y, camera.z, entity.getViewYRot(partial), entity.getViewXRot(partial), 0.0);
        }
        double yaw = Math.toDegrees(Math.atan2(-look.x, look.z));
        double pitch = Math.toDegrees(-Math.atan2(look.y, Math.sqrt(look.x * look.x + look.z * look.z)));
        return new Evaluator.CameraPose(camera.x, camera.y, camera.z, yaw, pitch, 0.0);
    }

}
