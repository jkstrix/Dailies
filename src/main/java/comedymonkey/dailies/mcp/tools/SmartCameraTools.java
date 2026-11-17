package comedymonkey.dailies.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import comedymonkey.dailies.editor.EditorSession;
import comedymonkey.dailies.editor.SmartCameraDriver;
import comedymonkey.dailies.mcp.McpProtocol;
import comedymonkey.dailies.mcp.McpSchema;
import comedymonkey.dailies.mcp.McpToolException;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.smartcamera.SmartCameraSolver;

/**
 * The two Smart Camera tools: adding a keyframe that films a player by itself, and asking what the camera is doing.
 */
final class SmartCameraTools {

    private static final String[] SETTINGS = {"tick", "target", "mode", "track", "distance", "height", "side_angle", "position_smoothing", "heading_source", "position",
        "aim_smoothing", "radius", "degrees_per_second", "start_angle", "min_shot_seconds", "max_shot_seconds", "allow_chase", "allow_tripod", "allow_orbit", "seed",
        "screen_x", "screen_y", "lead_room", "auto_zoom", "subject_screen_height", "min_fov", "max_fov", "fixed_fov", "frame_vehicle", "avoid_obstructions", "transition_seconds"};

    private SmartCameraTools() {
    }

    static void register(McpProtocol.Registrar registrar) {
        registrar.add("smart_camera_add",
            "Add a Smart Camera keyframe to the current scene: from that tick until the next keyframe on its track the camera places and aims itself to film one player. "
                + "Modes: chase (follows from a set distance and angle), tripod (stands still and turns), orbit (circles), director (picks and cuts between the other three). Undoable.",
            McpSchema.object()
                .integer("tick", "Tick of the keyframe")
                .string("target", "The player to film, by name or uuid (default: whoever made the recording)")
                .string("mode", "chase (default), tripod, orbit or director")
                .integer("track", "A Smart Camera track of the scene, when it has more than one")
                .number("distance", "Chase: blocks behind the player (default 6). Director: the base distance of its chase shots")
                .number("height", "Chase and orbit: blocks above the point aimed at (default 2)")
                .number("side_angle", "Chase: degrees round the player from their heading. 0 behind, 90 to their right, 180 in front")
                .number("position_smoothing", "Chase: seconds the camera takes to catch up (default 0.35; 0 is rigid)")
                .string("heading_source", "Chase: movement (way of travel, body when nearly still) or body")
                .any("position", "Tripod: where it stands, as [x, y, z] or {x, y, z} (default: where the camera is now)")
                .number("aim_smoothing", "Tripod: seconds it takes to turn after the player (default 0.25)")
                .number("radius", "Orbit: distance from the player along the ground (default 8)")
                .number("degrees_per_second", "Orbit: how fast it circles; the sign is the direction (default 20)")
                .number("start_angle", "Orbit: the camera's heading at the keyframe's tick")
                .number("min_shot_seconds", "Director: shortest shot (default 3)")
                .number("max_shot_seconds", "Director: longest shot (default 8)")
                .bool("allow_chase", "Director: may use chase shots")
                .bool("allow_tripod", "Director: may use tripod shots")
                .bool("allow_orbit", "Director: may use orbit shots")
                .integer("seed", "Director: the same seed gives the same shots")
                .number("screen_x", "Where the player sits across the frame, -1 left edge to 1 right edge; 0 centre, 0.333 a third")
                .number("screen_y", "Where the player sits up the frame, -1 bottom to 1 top")
                .number("lead_room", "0 to 1: room left in front of a moving player (default 0.25)")
                .bool("auto_zoom", "Change the field of view so the player stays the same size in the frame")
                .number("subject_screen_height", "Auto zoom: how much of the frame's height the player fills (default 0.3)")
                .number("min_fov", "Auto zoom: narrowest field of view (default 30)")
                .number("max_fov", "Auto zoom: widest field of view (default 90)")
                .number("fixed_fov", "Field of view while auto zoom is off. 0 leaves it alone (default)")
                .bool("frame_vehicle", "Also fit what the player rides in the frame: a vehicle, a contraption or a craft (default true)")
                .bool("avoid_obstructions", "Move in when blocks come between camera and player (default true)")
                .number("transition_seconds", "Blend from the camera before over this long when the keyframe takes effect; 0 cuts (default 0.5)")
                .required("tick"),
            args -> {
                CoreTools.allowOnly(args, SETTINGS);
                JsonObject arguments = new JsonObject();
                for (String name : SETTINGS) {
                    if (args.has(name)) {
                        arguments.add(name, args.element(name));
                    }
                }
                try {
                    int track = SmartCameraDriver.add(arguments);
                    JsonObject json = new JsonObject();
                    json.addProperty("track", track);
                    json.addProperty("tick", args.integer("tick"));
                    json.addProperty("undo", EditorSession.history().undoDescription());
                    json.addProperty("note", "The Smart Camera moves the view while the recording plays or exports, not while it is paused");
                    return json;
                } catch (IllegalStateException | IllegalArgumentException e) {
                    throw new McpToolException(e.getMessage());
                }
            });

        registrar.add("smart_camera_status",
            "What the Smart Camera is doing: its mode, the director's shots so far with the reason for each cut, where the player and the camera are, where the player sits in the frame, "
                + "and whether the view is blocked or a vehicle is being framed.",
            McpSchema.object(), args -> {
                CoreTools.allowOnly(args);
                if (!Playback.loaded()) {
                    throw new McpToolException("No recording is open. Open one with replay_open");
                }
                JsonObject json = new JsonObject();
                SmartCameraSolver solver = SmartCameraDriver.lastSolver();
                SmartCameraSolver.Result result = solver == null ? null : solver.lastResult();
                json.addProperty("active", result != null && !Playback.paused());
                json.addProperty("follow_camera", EditorSession.followCamera());
                if (result == null) {
                    json.addProperty("note", "No Smart Camera has run yet. Add one with smart_camera_add and play the recording");
                    return json;
                }
                json.addProperty("tick", result.tick());
                json.addProperty("mode", result.mode().name().toLowerCase());
                json.addProperty("shot_mode", result.shotMode().name().toLowerCase());
                json.addProperty("shot_index", result.shotIndex());
                json.addProperty("shot_start_tick", result.shotStartTick());
                json.addProperty("shot_age_seconds", result.shotAgeSeconds());
                if (result.shotReason() != null) {
                    json.addProperty("shot_reason", result.shotReason());
                }
                JsonObject subject = new JsonObject();
                subject.addProperty("x", result.subjectX());
                subject.addProperty("y", result.subjectY());
                subject.addProperty("z", result.subjectZ());
                subject.addProperty("size", result.subjectSize());
                subject.addProperty("framed", result.framed());
                subject.addProperty("screen_x", result.screenX());
                subject.addProperty("screen_y", result.screenY());
                subject.addProperty("in_front", result.subjectInFront());
                json.add("subject", subject);
                JsonObject camera = new JsonObject();
                camera.addProperty("x", result.cameraX());
                camera.addProperty("y", result.cameraY());
                camera.addProperty("z", result.cameraZ());
                camera.addProperty("yaw", result.yaw());
                camera.addProperty("pitch", result.pitch());
                camera.addProperty("fov", result.fov());
                camera.addProperty("controls_fov", result.applyFov());
                json.add("camera", camera);
                json.addProperty("target_screen_x", result.targetScreenX());
                json.addProperty("target_screen_y", result.targetScreenY());
                json.addProperty("obstructed", result.obstructed());
                json.addProperty("transition", result.transition());
                JsonArray shots = new JsonArray();
                for (SmartCameraSolver.ShotInfo shot : solver.shotHistory()) {
                    JsonObject one = new JsonObject();
                    one.addProperty("index", shot.index());
                    one.addProperty("mode", shot.mode().name().toLowerCase());
                    one.addProperty("start_tick", shot.startTick());
                    one.addProperty("seconds", shot.seconds());
                    one.addProperty("start_reason", shot.startReason());
                    if (shot.endReason() != null) {
                        one.addProperty("end_reason", shot.endReason());
                    }
                    shots.add(one);
                }
                json.add("shots", shots);
                return json;
            });
    }

}
