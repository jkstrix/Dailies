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
import java.util.concurrent.atomic.AtomicReference;

/**
 * The editor's state for the recording that is open (specification chapter V): its scenes, tracks and keyframes with
 * their undo history, saved into the recording as they change, and applied to the view every frame while the
 * recording plays or exports. The screens and the MCP tools both work through {@link #history()}, so they share one
 * undo stack.
 */
public final class EditorSession {

    private static final long SAVE_AFTER_MILLIS = 750;
    /** After a save has failed, how long to wait before trying again */
    private static final long RETRY_AFTER_MILLIS = 5000;

    /** A revision of one history: the one on disk, or the one handed to the saver thread */
    private record Revision(@Nullable EditHistory history, long revision) {
        static final Revision NONE = new Revision(null, -1);

        boolean of(EditHistory current) {
            return this.history == current && this.revision == current.revision();
        }
    }

    private static volatile EditHistory history = null;
    /** The recording's own editing state while a plain one stands in for it (see {@link #standIn}), else null */
    private static volatile @Nullable EditHistory setAside = null;
    private static RecordingFile file = null;
    /** The revision known to be on disk, set by the saver thread only once a save has gone through */
    private static final AtomicReference<Revision> saved = new AtomicReference<>(Revision.NONE);
    /** The revision last handed to the saver thread, so the same one is not handed over every frame while it saves */
    private static volatile Revision queued = Revision.NONE;
    private static volatile long retryAt = 0;
    private static long changedAt = 0;
    /** Whether the view's roll is the scene looks' roll, set because no keyframe gave one */
    private static boolean rollFromLooks = false;
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
        saved.set(new Revision(fresh, fresh.revision()));
        queued = Revision.NONE;
        retryAt = 0;
        rollFromLooks = false;
        followCamera = true;
        setAside = null;
        history = fresh;
        PointOfView.home(recording);
        PointOfView.prepare(fresh.scene());
    }

    /** The recording is about to be closed: nothing edited may be lost (EDIT-7) */
    public static void closing() {
        SmartCameraDriver.forget();
        CameraSteps.forget();
        StretchPreview.forget();
        // What is saved is always the recording's own state, never a plain one standing in for it
        EditHistory closingHistory = own();
        RecordingFile closingFile = file;
        setAside = null;
        history = null;
        file = null;
        // The background export game reads recordings and never writes to them (EXP-13)
        if (closingHistory != null && closingFile != null && !saved.get().of(closingHistory) && !comedymonkey.dailies.export.Worker.active()) {
            long revision = closingHistory.revision();
            byte[] bytes = closingHistory.state().toBytes();
            try {
                // After anything the saver thread still has in hand, and before the file is closed
                SAVER.submit(() -> {
                    closingFile.saveEditState(bytes);
                    markSaved(closingHistory, revision);
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

    /**
     * Puts a plain editing state in place of the recording's own for a while: one scene with no keyframes and the
     * usual looks, never saved. A point of view export uses it (EXP-10, PLAY-18), so that the recording's scenes, their
     * cameras and looks play no part in a stretch seen through the recorder's eyes, and nothing of them is changed or
     * lost: the recording's own state is still the one saved meanwhile, and comes back with {@link #putBack}. The editor
     * shows the plain scene meanwhile, as it shows whichever scene an export of the queue has made current. Client thread
     */
    public static void standIn() {
        EditHistory current = history;
        if (current == null) {
            throw new IllegalStateException("No recording is open");
        }
        if (setAside != null) {
            return;
        }
        setAside = current;
        EditHistory plain = new EditHistory(new EditState());
        history = plain;
        PointOfView.prepare(plain.scene());
    }

    /** Puts the recording's own editing state back after {@link #standIn}. Nothing happens when none was set aside. Client thread */
    public static void putBack() {
        EditHistory own = setAside;
        if (own == null) {
            return;
        }
        setAside = null;
        history = own;
        PointOfView.prepare(own.scene());
    }

    /** The recording's own editing state, whether or not a plain one stands in for it; null when none is open */
    private static @Nullable EditHistory own() {
        EditHistory aside = setAside;
        return aside != null ? aside : history;
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

    /**
     * Notes that a revision of a history is on disk. Saver thread. A save of an older revision that finishes late, or
     * one of a recording closed since, changes nothing
     */
    private static void markSaved(EditHistory of, long revision) {
        saved.updateAndGet(known -> known.history() != of || known.revision() < revision ? new Revision(of, revision) : known);
    }

    /** Once a frame: save what has changed, once it has been still for a moment */
    private static void autosave() {
        EditHistory current = own();
        RecordingFile target = file;
        long now = System.currentTimeMillis();
        if (current == null || target == null || saved.get().of(current) || queued.of(current) || now - changedAt < SAVE_AFTER_MILLIS || now < retryAt
            || comedymonkey.dailies.export.Worker.active()) {
            return;
        }
        long revision = current.revision();
        byte[] bytes = current.state().toBytes();
        queued = new Revision(current, revision);
        SAVER.execute(() -> {
            try {
                target.saveEditState(bytes);
                markSaved(current, revision);
            } catch (Exception e) {
                // Not marked as saved, so it is tried again in a while, and closing the recording tries once more
                Host.LOGGER.error("Dailies: the editor state could not be saved; trying again in {} seconds", RETRY_AFTER_MILLIS / 1000, e);
                retryAt = System.currentTimeMillis() + RETRY_AFTER_MILLIS;
                queued = Revision.NONE;
            }
        });
    }

    /**
     * Saves what has changed now, without waiting for things to be still, and waits for it to be on disk: the
     * background export game reads scenes from the file (EXP-13). Client thread
     */
    public static void saveNow() {
        EditHistory current = own();
        RecordingFile target = file;
        if (current == null || target == null || saved.get().of(current) || comedymonkey.dailies.export.Worker.active()) {
            return;
        }
        long revision = current.revision();
        byte[] bytes = current.state().toBytes();
        try {
            SAVER.submit(() -> {
                target.saveEditState(bytes);
                markSaved(current, revision);
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
            // The field of view belongs to the camera, and carries on changing through a freeze as the camera does
            Track zoom = Evaluator.applyingTrack(scene, KeyframeKind.FIELD_OF_VIEW, tick);
            double[] fov = zoom == null ? null : Evaluator.blend(zoom, cameraTick(zoom, tick));
            Viewer.setFov(fov != null ? fov[0] : scene.looks().fov);
            Double time = Evaluator.timeOfDay(scene, tick);
            if (time != null && minecraft.level != null) {
                minecraft.level.getLevelData().setDayTime(Math.round(time));
            }
            if (followCamera || exporting) {
                CameraSteps.catchUp();
                placed = placeCamera(minecraft, scene, tick);
                if (!placed) {
                    looksRoll(scene);
                }
            }
        } catch (RuntimeException e) {
            Host.LOGGER.warn("Dailies: a keyframe could not be applied at tick {}", tick, e);
        }
        return placed;
    }

    /**
     * The tick at which to sample a track that moves the camera. In an export this carries on through a freeze, the
     * track spreading its way from one keyframe to the next over the frozen time too, while the world stands still
     * (see the freeze keyframe). Playback does not hold the world for a freeze, so there it is the recording's tick.
     */
    private static double cameraTick(Track track, double tick) {
        return exporting ? comedymonkey.dailies.export.Export.cameraTick(track, tick) : tick;
    }

    /** Where no track places the camera, the roll set in the scene's looks, if any, is the view's roll */
    private static void looksRoll(Scene scene) {
        float roll = (float) scene.looks().roll;
        if (roll != 0.0F) {
            Viewer.setRoll(roll);
            rollFromLooks = true;
        } else if (rollFromLooks) {
            // The looks' roll has been taken away again: so is the roll it gave
            Viewer.setRoll(0.0F);
            rollFromLooks = false;
        }
    }

    private static boolean placeCamera(Minecraft minecraft, Scene scene, double tick) {
        Track driver = Evaluator.cameraDriver(scene, tick);
        if (driver == null) {
            return false;
        }
        // The keyframed paths carry on through a freeze; the cameras that follow someone stay with the frozen world
        double moving = cameraTick(driver, tick);
        Evaluator.CameraPose pose = null;
        switch (driver.kind()) {
            case CAMERA -> pose = Evaluator.camera(driver, moving);
            case ORBIT -> {
                Evaluator.OrbitValue orbit = Evaluator.orbit(driver, tick);
                if (orbit != null && orbit.onEntity()) {
                    // Centred on an entity: stepped with the recording's ticks, smoothed and kept clear of blocks
                    pose = CameraSteps.pose(driver, tick);
                } else {
                    orbit = Evaluator.orbit(driver, moving);
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
        // The shake goes with the camera, so it carries on through a freeze when the camera does
        Track shakeTrack = Evaluator.applyingTrack(scene, KeyframeKind.CAMERA_SHAKE, moving);
        double[] shake = shakeTrack == null ? null : Evaluator.blend(shakeTrack, moving);
        if (shake != null && shake[0] > 0.0) {
            // A hand-held wobble: two slow waves that never quite repeat, timed by the recording so exports match playback
            double phase = Evaluator.shakePhase(shakeTrack, moving);
            yaw += shake[0] * (Math.sin(phase * 6.1) * 0.6 + Math.sin(phase * 13.7 + 1.3) * 0.4);
            pitch += shake[0] * (Math.sin(phase * 7.3 + 0.7) * 0.6 + Math.sin(phase * 11.9 + 2.1) * 0.4);
        }
        // A camera keyframe sets the roll itself; the looks' roll is for when none does (the other cameras have none)
        double roll = driver.kind() == KeyframeKind.CAMERA ? pose.roll() : scene.looks().roll;
        rollFromLooks = driver.kind() != KeyframeKind.CAMERA && roll != 0.0;
        Viewer.drive(pose.x(), pose.y(), pose.z(), (float) yaw, (float) Mth.clamp(pitch, -90.0, 90.0), (float) roll);
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
        float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true);
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
