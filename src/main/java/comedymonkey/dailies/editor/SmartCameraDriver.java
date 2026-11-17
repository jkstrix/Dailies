package comedymonkey.dailies.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import comedymonkey.dailies.edit.Evaluator;
import comedymonkey.dailies.edit.Interpolation;
import comedymonkey.dailies.edit.Keyframe;
import comedymonkey.dailies.edit.KeyframeKind;
import comedymonkey.dailies.edit.Scene;
import comedymonkey.dailies.edit.Track;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.Viewer;
import comedymonkey.dailies.smartcamera.PoseBlend;
import comedymonkey.dailies.smartcamera.SmartCameraHeading;
import comedymonkey.dailies.smartcamera.SmartCameraMode;
import comedymonkey.dailies.smartcamera.SmartCameraSettings;
import comedymonkey.dailies.smartcamera.SmartCameraSolver;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The link between the editor and the provided Smart Camera (specification chapter VIII). A Smart Camera keyframe is
 * stored as its settings in snake_case; here they become the solver's own settings, the solver is asked for a pose
 * every frame, and that pose is handed back for the viewer.
 */
public final class SmartCameraDriver {

    /** The height of the viewer's eyes above its feet: the solver gives the eye, the viewer is placed by its feet */
    private static final double VIEWER_EYE_HEIGHT = 1.62;

    // Held firmly: the solver takes a new settings object to mean the keyframe was edited, and starts its shots again
    private static final Map<Keyframe.SmartCamera, SmartCameraSettings> PARSED = new java.util.LinkedHashMap<>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Keyframe.SmartCamera, SmartCameraSettings> eldest) {
            return this.size() > 256;
        }
    };
    private static volatile SmartCameraSolver lastSolver = null;
    private static volatile Track lastTrack = null;

    private SmartCameraDriver() {
    }

    /**
     * One step of the Smart Camera of a track, after a tick of the recording has been played (see {@link CameraSteps}).
     *
     * @param tick the scene tick the recording stands on
     */
    static boolean step(Minecraft minecraft, Track track, @Nullable Keyframe.SmartCamera keyframe, double tick, boolean applied) {
        if (keyframe == null || minecraft.level == null) {
            return false;
        }
        SmartCameraSettings settings = PARSED.computeIfAbsent(keyframe, key -> settings(minecraft, key.settings()));
        SmartCameraSolver solver = SmartCameraSolver.forTrack(track);
        lastSolver = solver;
        lastTrack = track;
        if (settings.target == null) {
            return false;
        }
        SmartCameraSolver.Result result = solver.step(settings, keyframe.tick(), tick, minecraft, applied);
        if (result != null) {
            remember(track, keyframe, tick, new Evaluator.CameraPose(result.cameraX(), result.cameraY() - VIEWER_EYE_HEIGHT, result.cameraZ(), result.yaw(),
                result.pitch(), 0.0));
        }
        return result != null;
    }

    /** Where the Smart Camera of a track puts the view for a frame drawn at this tick, or null when it has nothing to film */
    static @Nullable Evaluator.CameraPose pose(Track track, double tick) {
        PoseBlend.Pose pose = SmartCameraSolver.forTrack(track).poseAt(tick);
        if (pose == null) {
            return null;
        }
        if (pose.hasFov()) {
            Viewer.setFov(pose.fov());
        }
        return new Evaluator.CameraPose(pose.x(), pose.y() - VIEWER_EYE_HEIGHT, pose.z(), pose.yaw(), pose.pitch(), 0.0);
    }

    // ------------------------------------------------------------------ Where the camera went, for the path preview

    /**
     * Where a Smart Camera keyframe put the camera the last time its stretch was played (specification EDIT-8). The
     * Smart Camera works its shots out from the recording as it plays, so its path is only known once it has been
     * watched.
     *
     * @param ticks the moments of the recording the poses belong to, in order
     * @param stale whether the keyframe's settings have changed since, so that the path would no longer come out so
     */
    public record Recorded(List<Double> ticks, List<Evaluator.CameraPose> poses, boolean stale) {
    }

    private record StretchKey(int scene, int track, int keyframeTick) {
    }

    private static final class Stretch {
        final JsonObject settings;
        /** By quarter ticks, so a stretch played many times over does not grow without end */
        final TreeMap<Integer, Evaluator.CameraPose> poses = new TreeMap<>();

        Stretch(JsonObject settings) {
            this.settings = settings;
        }
    }

    private static final int MOST_POSES = 40_000;
    private static final Map<StretchKey, Stretch> STRETCHES = new HashMap<>();
    private static volatile long remembered = 0;

    private static void remember(Track track, Keyframe.SmartCamera keyframe, double tick, Evaluator.CameraPose pose) {
        try {
            if (!EditorSession.open()) {
                return;
            }
            var history = EditorSession.history();
            int index = history.scene().tracks().indexOf(track);
            if (index < 0) {
                return;
            }
            StretchKey key = new StretchKey(history.state().currentSceneIndex(), index, keyframe.tick());
            Stretch stretch = STRETCHES.get(key);
            if (stretch == null || !stretch.settings.equals(keyframe.settings())) {
                // Played afresh with other settings: what the old ones gave is of no more use
                stretch = new Stretch(keyframe.settings().deepCopy());
                STRETCHES.put(key, stretch);
            }
            if (stretch.poses.size() < MOST_POSES) {
                stretch.poses.put((int) Math.round(tick * 4.0), pose);
                remembered++;
            }
        } catch (RuntimeException e) {
            // Remembering the path is a help for the editor and never worth a frame of the camera
        }
    }

    /**
     * @param scene the index of the scene, {@code track} of the track in it
     * @return where this keyframe last put the camera, or null when its stretch has not been played
     */
    public static @Nullable Recorded recorded(int scene, int track, Keyframe.SmartCamera keyframe) {
        Stretch stretch = STRETCHES.get(new StretchKey(scene, track, keyframe.tick()));
        if (stretch == null || stretch.poses.isEmpty()) {
            return null;
        }
        List<Double> ticks = new ArrayList<>(stretch.poses.size());
        for (Integer quarter : stretch.poses.keySet()) {
            ticks.add(quarter / 4.0);
        }
        return new Recorded(ticks, new ArrayList<>(stretch.poses.values()), !stretch.settings.equals(keyframe.settings()));
    }

    /** Goes up whenever a pose is remembered, so that whoever draws the paths knows to look again */
    public static long rememberedCount() {
        return remembered;
    }

    /** What the Smart Camera last did, for the status tool. Null when none has run in this recording */
    public static @Nullable SmartCameraSolver lastSolver() {
        return lastSolver;
    }

    static void forget() {
        lastSolver = null;
        lastTrack = null;
        PARSED.clear();
        STRETCHES.clear();
        remembered++;
    }

    /**
     * Adds a Smart Camera keyframe from the arguments of the smart_camera_add tool, as one undoable step.
     *
     * @return the index of the track it went on
     */
    public static int add(JsonObject arguments) {
        if (!Playback.loaded()) {
            throw new IllegalStateException("No recording is open");
        }
        Minecraft minecraft = Minecraft.getInstance();
        JsonObject settings = arguments.deepCopy();
        if (!settings.has("tick")) {
            throw new IllegalArgumentException("'tick' is required");
        }
        int tick = settings.remove("tick").getAsInt();
        if (tick < 0 || tick > Playback.total()) {
            throw new IllegalArgumentException("'tick' must be between 0 and " + Playback.total() + "; the nearest allowed value is " + Math.max(0, Math.min(Playback.total(), tick)));
        }
        Integer wantedTrack = settings.has("track") ? settings.remove("track").getAsInt() : null;
        if (!settings.has("target") || settings.get("target").getAsString().isBlank()) {
            settings.addProperty("target", Playback.manifest().recorderId);
        }
        if (!settings.has("mode")) {
            settings.addProperty("mode", "chase");
        }
        String mode = settings.get("mode").getAsString().toLowerCase(Locale.ROOT);
        settings.addProperty("mode", mode);
        if (mode.equals("tripod") && !settings.has("position") && settings.has("tripod_placement")
            && settings.get("tripod_placement").getAsString().equalsIgnoreCase("fixed")) {
            // A fixed tripod stands where the viewer is when the keyframe is made; otherwise it picks its own places
            Vec3 at = Viewer.position();
            JsonObject position = new JsonObject();
            position.addProperty("x", at.x);
            position.addProperty("y", at.y + VIEWER_EYE_HEIGHT);
            position.addProperty("z", at.z);
            settings.add("position", position);
        }
        if (mode.equals("orbit") && !settings.has("start_angle")) {
            settings.addProperty("start_angle", Viewer.yaw());
        }
        // Reject what the solver could not use before anything is stored
        settings(minecraft, settings);

        Keyframe.SmartCamera keyframe = new Keyframe.SmartCamera(tick, Interpolation.HOLD, settings);
        var history = EditorSession.history();
        int track;
        if (wantedTrack != null) {
            Scene scene = history.scene();
            if (wantedTrack < 0 || wantedTrack >= scene.tracks().size() || scene.track(wantedTrack).kind() != KeyframeKind.SMART_CAMERA) {
                throw new IllegalArgumentException("'track' " + wantedTrack + " is not a Smart Camera track of this scene. Call keyframes_list for the tracks there are");
            }
            history.setKeyframe(wantedTrack, keyframe);
            track = wantedTrack;
        } else {
            track = history.setKeyframe(keyframe);
        }
        EditorSession.setFollowCamera(true);
        return track;
    }

    private static SmartCameraSettings settings(Minecraft minecraft, JsonObject json) {
        SmartCameraSettings settings = new SmartCameraSettings(target(minecraft, json.has("target") ? json.get("target").getAsString() : ""));
        String mode = json.has("mode") ? json.get("mode").getAsString() : "chase";
        try {
            settings.mode = SmartCameraMode.valueOf(mode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'mode' must be chase, tripod, orbit or director");
        }
        settings.distance = number(json, "distance", settings.distance, 1, 64);
        settings.height = number(json, "height", settings.height, -32, 64);
        settings.sideAngle = number(json, "side_angle", settings.sideAngle, -360, 360);
        settings.positionSmoothing = number(json, "position_smoothing", settings.positionSmoothing, 0, 5);
        if (json.has("heading_source")) {
            try {
                settings.headingSource = SmartCameraHeading.valueOf(json.get("heading_source").getAsString().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("'heading_source' must be movement or body");
            }
        }
        settings.headingDeadBand = number(json, "heading_dead_band", settings.headingDeadBand, 0, 90);
        settings.flightWiden = number(json, "flight_widen", settings.flightWiden, 0, 2);
        boolean hasPosition = json.has("position");
        if (hasPosition) {
            JsonElement position = json.get("position");
            try {
                if (position.isJsonArray()) {
                    settings.positionX = position.getAsJsonArray().get(0).getAsDouble();
                    settings.positionY = position.getAsJsonArray().get(1).getAsDouble();
                    settings.positionZ = position.getAsJsonArray().get(2).getAsDouble();
                } else {
                    JsonObject object = position.getAsJsonObject();
                    settings.positionX = object.get("x").getAsDouble();
                    settings.positionY = object.get("y").getAsDouble();
                    settings.positionZ = object.get("z").getAsDouble();
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("'position' must be [x, y, z] or {x, y, z}");
            }
        }
        // A tripod given a place (every one made before tripods could choose) keeps to it unless told otherwise
        String placement = json.has("tripod_placement") ? json.get("tripod_placement").getAsString().toLowerCase(Locale.ROOT) : hasPosition ? "fixed" : "auto";
        if (!placement.equals("auto") && !placement.equals("fixed")) {
            throw new IllegalArgumentException("'tripod_placement' must be auto or fixed");
        }
        if (placement.equals("fixed") && !hasPosition && settings.mode == SmartCameraMode.TRIPOD) {
            throw new IllegalArgumentException("A fixed tripod needs a 'position', [x, y, z]");
        }
        settings.autoPlace = placement.equals("auto");
        settings.deadZone = number(json, "dead_zone", settings.deadZone, 0, 0.8F);
        settings.aimSmoothing = number(json, "aim_smoothing", settings.aimSmoothing, 0, 5);
        settings.radius = number(json, "radius", settings.radius, 1, 128);
        settings.degreesPerSecond = number(json, "degrees_per_second", settings.degreesPerSecond, -360, 360);
        settings.startAngle = number(json, "start_angle", settings.startAngle, -100000, 100000);
        settings.minShotSeconds = number(json, "min_shot_seconds", settings.minShotSeconds, 0.5F, 60);
        settings.maxShotSeconds = Math.max(settings.minShotSeconds, number(json, "max_shot_seconds", settings.maxShotSeconds, 0.5F, 120));
        settings.allowChase = flag(json, "allow_chase", settings.allowChase);
        settings.allowTripod = flag(json, "allow_tripod", settings.allowTripod);
        settings.allowOrbit = flag(json, "allow_orbit", settings.allowOrbit);
        settings.allowOverShoulder = flag(json, "allow_over_shoulder", settings.allowOverShoulder);
        settings.allowSideTracking = flag(json, "allow_side_tracking", settings.allowSideTracking);
        settings.establishingShots = flag(json, "establishing_shots", settings.establishingShots);
        settings.seed = json.has("seed") ? json.get("seed").getAsInt() : 0;
        settings.screenX = number(json, "screen_x", settings.screenX, -1, 1);
        settings.screenY = number(json, "screen_y", settings.screenY, -1, 1);
        settings.leadRoom = number(json, "lead_room", settings.leadRoom, 0, 1);
        settings.autoZoom = flag(json, "auto_zoom", settings.autoZoom);
        settings.subjectScreenHeight = number(json, "subject_screen_height", settings.subjectScreenHeight, 0.05F, 1);
        settings.minFov = number(json, "min_fov", settings.minFov, 1, 170);
        settings.maxFov = Math.max(settings.minFov, number(json, "max_fov", settings.maxFov, 1, 170));
        settings.fixedFov = number(json, "fixed_fov", settings.fixedFov, 0, 170);
        settings.frameVehicle = flag(json, "frame_vehicle", settings.frameVehicle);
        settings.avoidObstructions = flag(json, "avoid_obstructions", settings.avoidObstructions);
        settings.transitionSeconds = number(json, "transition_seconds", settings.transitionSeconds, 0, 30);
        return settings;
    }

    /** A player by uuid, or by name among the players in the world now. The recorder when nothing is given */
    private static @Nullable UUID target(Minecraft minecraft, String text) {
        String wanted = text.isBlank() && Playback.manifest() != null ? Playback.manifest().recorderId : text.trim();
        try {
            return UUID.fromString(wanted);
        } catch (IllegalArgumentException notAnId) {
            if (minecraft.level != null) {
                for (Player player : minecraft.level.players()) {
                    if (player.getGameProfile().getName().equalsIgnoreCase(wanted)) {
                        return player.getUUID();
                    }
                }
            }
            if (Playback.manifest() != null && Playback.manifest().recorderName.equalsIgnoreCase(wanted)) {
                return UUID.fromString(Playback.manifest().recorderId);
            }
            throw new IllegalArgumentException("There is no player called '" + wanted + "' in the recording at this tick. Give a name from replay_list_entities, or a uuid (any entity's)");
        }
    }

    private static float number(JsonObject json, String name, float fallback, float min, float max) {
        if (!json.has(name)) {
            return fallback;
        }
        try {
            float value = json.get(name).getAsFloat();
            if (Float.isNaN(value)) {
                throw new NumberFormatException();
            }
            return Mth.clamp(value, min, max);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("'" + name + "' must be a number");
        }
    }

    private static boolean flag(JsonObject json, String name, boolean fallback) {
        if (!json.has(name)) {
            return fallback;
        }
        try {
            return json.get(name).getAsBoolean();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("'" + name + "' must be true or false");
        }
    }

}
