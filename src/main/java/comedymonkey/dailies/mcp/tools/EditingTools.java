package comedymonkey.dailies.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.edit.EditHistory;
import comedymonkey.dailies.edit.EditState;
import comedymonkey.dailies.edit.Interpolation;
import comedymonkey.dailies.edit.Keyframe;
import comedymonkey.dailies.edit.KeyframeKind;
import comedymonkey.dailies.edit.Scene;
import comedymonkey.dailies.edit.Track;
import comedymonkey.dailies.editor.EditorSession;
import comedymonkey.dailies.mcp.McpArgs;
import comedymonkey.dailies.mcp.McpProtocol;
import comedymonkey.dailies.mcp.McpSchema;
import comedymonkey.dailies.mcp.McpToolException;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.Viewer;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * The MCP tools for editing the open recording: its scenes, their tracks and keyframes, and undo and redo. Every
 * change goes through the editor's {@link EditHistory}, so an agent and the editor's screens share one undo stack.
 * The tool names and arguments are those of the specification's appendix A.
 */
final class EditingTools {

    private static final String INTERPOLATION_HELP = "How the keyframe blends towards the next one: " + Interpolation.allowed()
        + ". Left out, the 'defaultInterpolation' setting is used";

    /** What a tool does once its arguments are known to be its own and a recording is open and loaded */
    interface Body {
        JsonElement run(McpArgs args, EditHistory history);
    }

    private EditingTools() {
    }

    static void register(McpProtocol.Registrar registrar) {
        add(registrar, "editor_state_get",
            "Everything the editor holds for the open recording, as the JSON it is saved in: each scene with its tracks, keyframes, export range and looks. "
                + "Keyframes have the shape keyframe_set takes. Also says which scene is current, the revision (it goes up with every change) and whether undo and redo are possible.",
            McpSchema.object()
                .integer("scene", "Index of the one scene to return, from scene_list. Left out, all scenes are returned")
                .bool("include_block_overrides", "Whether to list the keyframes of block change tracks, which can be many. Default false: only their number is given"),
            EditingTools::state, "scene", "include_block_overrides");

        add(registrar, "scene_list",
            "The scenes of the open recording: index, name, whether it is the current one, its export range and how many tracks and keyframes it has. "
                + "Track, keyframe and looks tools always work on the current scene.",
            McpSchema.object(), (args, history) -> scenes(history));

        add(registrar, "scene_add", "Add an empty scene after the others and make it the current scene. Can be undone.",
            McpSchema.object().string("name", "Name of the scene. Left out, it is called 'Scene N'"),
            (args, history) -> {
                int index = history.addScene(args.optString("name"));
                JsonObject json = scene(history.state(), index);
                return changed(json, history);
            }, "name");

        add(registrar, "scene_select", "Make a scene the current one. The track, keyframe and looks tools then work on it. This is not a step to undo.",
            McpSchema.object().integer("index", "Index of the scene, from scene_list").required("index"),
            (args, history) -> {
                int index = sceneIndex(history, args.integer("index"));
                history.selectScene(index);
                return scenes(history);
            }, "index");

        add(registrar, "scene_rename", "Give a scene another name. Can be undone.",
            McpSchema.object()
                .integer("index", "Index of the scene, from scene_list. Left out, the current scene is renamed")
                .string("name", "The new name; it must not be empty")
                .required("name"),
            (args, history) -> {
                int index = sceneIndex(history, args.optInteger("index", history.state().currentSceneIndex()));
                history.renameScene(index, args.string("name"));
                return changed(scene(history.state(), index), history);
            }, "index", "name");

        add(registrar, "scene_remove",
            "Remove a scene with all its tracks, keyframes and looks. The only scene left cannot be removed, and a scene that still has tracks is only removed with 'force'. "
                + "Can be undone with editor_undo.",
            McpSchema.object()
                .integer("index", "Index of the scene, from scene_list. Left out, the current scene is removed")
                .bool("force", "Remove the scene even though it still has tracks. Default false"),
            (args, history) -> {
                int index = sceneIndex(history, args.optInteger("index", history.state().currentSceneIndex()));
                Scene doomed = history.state().scene(index);
                if (!doomed.tracks().isEmpty() && !args.optBool("force", false)) {
                    throw new McpToolException("Scene " + index + " \"" + doomed.name() + "\" still has " + doomed.tracks().size()
                        + (doomed.tracks().size() == 1 ? " track" : " tracks") + ". Pass force=true to remove it with them, or remove them first with track_remove");
                }
                history.removeScene(index);
                JsonObject json = scenes(history);
                json.addProperty("removed", doomed.name());
                return changed(json, history);
            }, "index", "force");

        add(registrar, "scene_set_export_range",
            "Set which ticks of the current scene are exported, both ends included, or clear the range so the whole scene is exported. "
                + "Give start_tick, end_tick or both (the one left out keeps its value, or is the start or end of the recording), or clear=true. Can be undone.",
            McpSchema.object()
                .integer("start_tick", "First tick to export")
                .integer("end_tick", "Last tick to export; not before start_tick")
                .bool("clear", "true removes the range. Do not give ticks with it"),
            (args, history) -> {
                boolean clear = args.optBool("clear", false);
                boolean ticks = args.has("start_tick") || args.has("end_tick");
                if (clear && ticks) {
                    throw new McpToolException("Give either clear=true or ticks, not both");
                }
                if (!clear && !ticks) {
                    throw new McpToolException("Give 'start_tick', 'end_tick' or both to set the range, or clear=true to remove it");
                }
                if (clear) {
                    history.clearExportRange();
                } else {
                    Scene scene = history.scene();
                    int start = args.has("start_tick") ? tick(args, "start_tick") : scene.hasExportRange() ? scene.exportStart() : 0;
                    int end = args.has("end_tick") ? tick(args, "end_tick") : scene.hasExportRange() ? scene.exportEnd() : Playback.total();
                    if (end < start) {
                        throw new McpToolException("'end_tick' (" + end + ") must not be before 'start_tick' (" + start + "); the nearest allowed value is " + start);
                    }
                    history.setExportRange(start, end);
                }
                return changed(scene(history.state(), history.state().currentSceneIndex()), history);
            }, "start_tick", "end_tick", "clear");

        add(registrar, "track_add", "Add an empty track for one kind of keyframe at the bottom of the current scene. Returns its index. Can be undone.",
            McpSchema.object().string("type", "Kind of keyframe the track holds: an id from keyframe_types, eg. camera. Case does not matter").required("type"),
            (args, history) -> {
                int index = history.addTrack(KeyframeKind.parse(args.string("type")));
                return changed(track(history.scene(), index, false), history);
            }, "type");

        add(registrar, "track_remove", "Remove a track and its keyframes from the current scene. The tracks below it move up by one index. Can be undone.",
            McpSchema.object().integer("track", "Index of the track, from keyframes_list").required("track"),
            (args, history) -> {
                int index = trackIndex(history, args.integer("track"));
                JsonObject removed = track(history.scene(), index, false);
                history.removeTrack(index);
                JsonObject json = new JsonObject();
                json.add("removed", removed);
                json.addProperty("tracks_left", history.scene().tracks().size());
                return changed(json, history);
            }, "track");

        add(registrar, "track_update",
            "Switch a track of the current scene on or off (a track that is off does nothing in playback or export), or name it. Can be undone. "
                + "Tracks have no colour of their own in Dailies: 'colour' is accepted and reported back as ignored.",
            McpSchema.object()
                .integer("track", "Index of the track, from keyframes_list")
                .bool("enabled", "Whether the track is applied")
                .string("name", "Name shown on the timeline. An empty string goes back to the name of the kind")
                .string("colour", "Ignored: tracks have no colour of their own")
                .required("track"),
            (args, history) -> {
                int index = trackIndex(history, args.integer("track"));
                String name = args.optString("name");
                Boolean enabled = args.has("enabled") ? args.bool("enabled") : null;
                boolean colour = args.has("colour");
                if (name == null && enabled == null && !colour) {
                    throw new McpToolException("Give 'enabled', 'name' or both: there is nothing to change otherwise");
                }
                if (name != null || enabled != null) {
                    history.updateTrack(index, name, enabled);
                }
                JsonObject json = track(history.scene(), index, false);
                if (colour) {
                    JsonArray ignored = new JsonArray();
                    ignored.add("colour: tracks have no colour of their own");
                    json.add("ignored", ignored);
                }
                return changed(json, history);
            }, "track", "enabled", "name", "colour");

        add(registrar, "keyframe_types",
            "Every kind of keyframe a track can hold: its id, its name, whether values blend between two keyframes of it, and an example of the JSON keyframe_set takes for it. "
                + "Also lists the ways of blending.",
            McpSchema.object(), (args, history) -> {
                JsonArray kinds = new JsonArray();
                for (KeyframeKind kind : KeyframeKind.values()) {
                    JsonObject json = new JsonObject();
                    json.addProperty("id", kind.id());
                    json.addProperty("title", kind.title());
                    json.addProperty("blends", kind.blends());
                    json.addProperty("places_camera", kind.drivesCamera());
                    json.add("example", Keyframe.exampleJson(kind));
                    kinds.add(json);
                }
                JsonArray blends = new JsonArray();
                for (Interpolation mode : Interpolation.values()) {
                    blends.add(mode.id());
                }
                JsonObject json = new JsonObject();
                json.add("types", kinds);
                json.add("interpolations", blends);
                return json;
            });

        add(registrar, "keyframes_list",
            "The tracks of the current scene from the top down, each with its index, kind, name, whether it is on, and its keyframes in tick order "
                + "(tick, interpolation and the values of its kind). Where two tracks of a kind both apply, the one with the lower index wins.",
            McpSchema.object(), (args, history) -> {
                Scene scene = history.scene();
                JsonObject json = new JsonObject();
                json.addProperty("scene", history.state().currentSceneIndex());
                json.addProperty("scene_name", scene.name());
                json.addProperty("total_ticks", Playback.total());
                JsonArray tracks = new JsonArray();
                for (int i = 0; i < scene.tracks().size(); i++) {
                    tracks.add(track(scene, i, true));
                }
                json.add("tracks", tracks);
                return json;
            });

        add(registrar, "keyframe_add_camera",
            "Put a camera keyframe (position and rotation at a tick) on a camera track of the current scene. What is left out of x, y, z, yaw, pitch and roll is taken from "
                + "where the viewer's camera is now, so a path can be built by moving the camera with camera_set and calling this at each stop. "
                + "Uses the first camera track, adding one when the scene has none. Replaces a keyframe already on the tick. Can be undone.",
            McpSchema.object()
                .integer("tick", "Tick of the recording the keyframe sits on, from 0 to the recording's length")
                .number("x", "East-west position. Default: the camera's").number("y", "Height. Default: the camera's").number("z", "North-south position. Default: the camera's")
                .number("yaw", "Heading in degrees. Default: the camera's").number("pitch", "Tilt in degrees, -90 straight up to 90 straight down. Default: the camera's")
                .number("roll", "Roll in degrees. Default: the camera's, usually 0")
                .integer("track", "Index of the camera track to use, from keyframes_list, when the scene has more than one")
                .string("interpolation", INTERPOLATION_HELP)
                .required("tick"),
            EditingTools::addCamera, "tick", "x", "y", "z", "yaw", "pitch", "roll", "track", "interpolation");

        add(registrar, "keyframe_add",
            "Put a keyframe of a kind that has one number on a track of the current scene, adding the track when the scene has none. 'type' and what 'value' means: "
                + "fov (field of view in degrees, 1 to 170), speed (a multiple of the recorded speed: 1 as recorded, 0.5 half, 2 double), "
                + "time_of_day (the sky's time in ticks of the day, 0 to 24000, 6000 is noon), freeze (seconds of video the world is held still on this tick), "
                + "timelapse (one end of a timelapse: keyframes pair up in order, and the stretch between a pair plays in the number of real seconds given on the first of the two). "
                + "Replaces a keyframe already on the tick. Can be undone.",
            McpSchema.object()
                .string("type", "fov, speed, time_of_day, freeze or timelapse")
                .integer("tick", "Tick of the recording the keyframe sits on, from 0 to the recording's length")
                .any("value", "A number; see the tool's description for what it means for each type")
                .integer("track", "Index of the track to use, from keyframes_list, when the scene has more than one of this kind")
                .string("interpolation", INTERPOLATION_HELP)
                .required("type", "tick", "value"),
            EditingTools::addSimple, "type", "tick", "value", "track", "interpolation");

        add(registrar, "keyframe_set",
            "Put a keyframe of any kind on a track of the current scene, in place of one already on the tick. Give 'track' (an index from keyframes_list) or 'type' "
                + "(an id from keyframe_types); with 'type' the first track of that kind is used, and one is added when the scene has none. "
                + "'keyframe' holds the values of the kind, as keyframe_types shows them. Can be undone.",
            McpSchema.object()
                .integer("track", "Index of the track in the current scene")
                .string("type", "Kind of keyframe: an id from keyframe_types, eg. camera, fov, speed, time_of_day. Case does not matter")
                .integer("tick", "Tick of the recording the keyframe sits on, from 0 to the recording's length")
                .obj("keyframe", "The values of the keyframe, eg. {\"fov\": 30} for a field of view track. A 'tick' inside it is overruled by the 'tick' argument")
                .string("interpolation", INTERPOLATION_HELP + ", unless 'keyframe' has an 'interpolation' of its own")
                .required("tick", "keyframe"),
            EditingTools::set, "track", "type", "tick", "keyframe", "interpolation");

        add(registrar, "keyframe_remove", "Remove the keyframe on a tick of a track of the current scene. Can be undone.",
            McpSchema.object()
                .integer("track", "Index of the track, from keyframes_list")
                .integer("tick", "Tick the keyframe sits on")
                .required("track", "tick"),
            (args, history) -> {
                int index = trackIndex(history, args.integer("track"));
                int tick = args.integer("tick");
                Keyframe removed = history.scene().track(index).at(tick);
                history.removeKeyframe(index, tick);
                JsonObject json = new JsonObject();
                json.addProperty("track", index);
                json.add("removed", removed == null ? JsonNull.INSTANCE : removed.toJson());
                return changed(json, history);
            }, "track", "tick");

        add(registrar, "keyframes_clear",
            "Remove every keyframe of one track of the current scene, or of all its tracks when 'track' is left out. The tracks themselves stay. Can be undone.",
            McpSchema.object().integer("track", "Index of the track, from keyframes_list. Left out, every track is cleared"),
            (args, history) -> {
                JsonObject json = new JsonObject();
                if (args.has("track")) {
                    int index = trackIndex(history, args.integer("track"));
                    json.addProperty("track", index);
                    json.addProperty("removed", history.scene().track(index).size());
                    history.clearTrack(index);
                } else {
                    int removed = 0;
                    for (Track track : history.scene().tracks()) {
                        removed += track.size();
                    }
                    json.addProperty("tracks", history.scene().tracks().size());
                    json.addProperty("removed", removed);
                    history.clearAllTracks();
                }
                return changed(json, history);
            }, "track");

        add(registrar, "keyframe_move", "Move a keyframe to another tick of the same track, in place of a keyframe already there. Can be undone.",
            McpSchema.object()
                .integer("track", "Index of the track, from keyframes_list")
                .integer("from_tick", "Tick the keyframe sits on now")
                .integer("to_tick", "Tick to move it to, from 0 to the recording's length")
                .required("track", "from_tick", "to_tick"),
            (args, history) -> {
                int index = trackIndex(history, args.integer("track"));
                int to = tick(args, "to_tick");
                history.moveKeyframe(index, args.integer("from_tick"), to);
                return changed(placed(history, index, to), history);
            }, "track", "from_tick", "to_tick");

        add(registrar, "keyframe_set_interpolation",
            "Change how one keyframe blends towards the next, or every keyframe of the track when 'tick' is left out. Can be undone.",
            McpSchema.object()
                .integer("track", "Index of the track, from keyframes_list")
                .integer("tick", "Tick the keyframe sits on. Left out, all keyframes of the track are changed")
                .string("interpolation", "One of " + Interpolation.allowed())
                .required("track", "interpolation"),
            (args, history) -> {
                int index = trackIndex(history, args.integer("track"));
                Integer tick = args.has("tick") ? Integer.valueOf(args.integer("tick")) : null;
                history.setInterpolation(index, tick, Interpolation.parse(args.string("interpolation")));
                return changed(tick == null ? track(history.scene(), index, true) : placed(history, index, tick), history);
            }, "track", "tick", "interpolation");

        add(registrar, "editor_undo",
            "Take back the latest change to the open recording's scenes, tracks, keyframes or looks, whether it was made in the editor or by a tool. Returns what was undone.",
            McpSchema.object(), (args, history) -> {
                String undone = history.undo();
                if (undone == null) {
                    throw new McpToolException("There is nothing to undo" + (history.canRedo() ? ". editor_redo would bring back: " + history.redoDescription() : ""));
                }
                JsonObject json = new JsonObject();
                json.addProperty("undone", undone);
                return steps(json, history);
            });

        add(registrar, "editor_redo", "Bring back the change that editor_undo took back last. Returns what was redone. A new change forgets what was undone.",
            McpSchema.object(), (args, history) -> {
                String redone = history.redo();
                if (redone == null) {
                    throw new McpToolException("There is nothing to redo" + (history.canUndo() ? ". editor_undo would take back: " + history.undoDescription() : ""));
                }
                JsonObject json = new JsonObject();
                json.addProperty("redone", redone);
                return steps(json, history);
            });
    }

    // ------------------------------------------------------------------ Shared with LooksTools

    /**
     * Registers a client-thread tool that refuses arguments other than the ones named, needs an open and loaded
     * recording, and turns what the editor model refuses into a refusal the agent can read.
     */
    static void add(McpProtocol.Registrar registrar, String name, String description, McpSchema schema, Body body, String... known) {
        registrar.add(name, description, schema, args -> {
            CoreTools.allowOnly(args, known);
            if (!Playback.loaded()) {
                throw new McpToolException(Playback.active() ? "The recording is still loading. Poll dailies_status until playback.loaded is true, then try again"
                    : "No recording is open. Open one with replay_open, then poll dailies_status until playback.loaded is true");
            }
            EditHistory history;
            try {
                history = EditorSession.history();
            } catch (IllegalStateException e) {
                throw new McpToolException(e.getMessage() + ". Open one with replay_open, then poll dailies_status until playback.loaded is true");
            }
            try {
                return body.run(args, history);
            } catch (IllegalArgumentException | IllegalStateException | IndexOutOfBoundsException e) {
                throw new McpToolException(e.getMessage() == null ? "The editor refused the change (" + e.getClass().getSimpleName() + ")" : e.getMessage());
            }
        });
    }

    /** Ends the reply of a tool that changed something: what editor_undo would now take back */
    static JsonObject changed(JsonObject json, EditHistory history) {
        String undo = history.undoDescription();
        if (undo == null) {
            json.add("undo", JsonNull.INSTANCE);
        } else {
            json.addProperty("undo", undo);
        }
        return json;
    }

    // ------------------------------------------------------------------ Handlers

    private static JsonElement state(McpArgs args, EditHistory history) {
        EditState state = history.state();
        boolean blockChanges = args.optBool("include_block_overrides", false);
        JsonObject json = state.toJson();
        JsonArray scenes = json.getAsJsonArray("scenes");
        for (JsonElement scene : scenes) {
            JsonArray tracks = scene.getAsJsonObject().getAsJsonArray("tracks");
            for (int i = 0; i < tracks.size(); i++) {
                JsonObject track = tracks.get(i).getAsJsonObject();
                track.addProperty("index", i);
                if (!blockChanges && KeyframeKind.BLOCK_CHANGE.id().equals(track.get("kind").getAsString())) {
                    track.addProperty("keyframes_left_out", track.getAsJsonArray("keyframes").size());
                    track.remove("keyframes");
                }
            }
        }
        if (args.has("scene")) {
            int index = sceneIndex(history, args.integer("scene"));
            JsonObject only = scenes.get(index).getAsJsonObject();
            only.addProperty("index", index);
            JsonArray one = new JsonArray();
            one.add(only);
            json.add("scenes", one);
        }
        json.addProperty("scene_count", state.scenes().size());
        json.addProperty("revision", history.revision());
        json.addProperty("total_ticks", Playback.total());
        return steps(json, history);
    }

    private static JsonElement addCamera(McpArgs args, EditHistory history) {
        int tick = tick(args, "tick");
        Vec3 at = Viewer.position();
        Keyframe keyframe = new Keyframe.Camera(tick, interpolation(args),
            args.optNumber("x", at.x), args.optNumber("y", at.y), args.optNumber("z", at.z),
            args.optNumber("yaw", Viewer.yaw()), args.optNumber("pitch", Viewer.pitch()), args.optNumber("roll", Viewer.roll()));
        return put(args, history, keyframe);
    }

    private static JsonElement addSimple(McpArgs args, EditHistory history) {
        String type = args.string("type").trim().toLowerCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_');
        int tick = tick(args, "tick");
        JsonElement given = args.element("value");
        if (given == null) {
            throw new McpToolException("Missing required argument 'value' (a number)");
        }
        if (!given.isJsonPrimitive() || !given.getAsJsonPrimitive().isNumber()) {
            throw new McpToolException(type.equals("freeze")
                ? "'value' must be a number: the seconds of video the world is held still on this tick, from 0 to 3600. Remove the keyframe with keyframe_remove to stop freezing"
                : "'value' must be a number, got " + given);
        }
        double value = given.getAsDouble();
        KeyframeKind kind;
        String field;
        switch (type) {
            case "fov", "field_of_view" -> {
                kind = KeyframeKind.FIELD_OF_VIEW;
                field = "fov";
            }
            case "speed" -> {
                kind = KeyframeKind.SPEED;
                field = "ticks_per_second";
                double least = Keyframe.Speed.MIN / 20.0;
                double most = Keyframe.Speed.MAX / 20.0;
                if (!(value >= least && value <= most)) {
                    throw new McpToolException("'value' must be a speed from " + least + " to " + most + " times the recorded speed, got " + given
                        + "; the nearest allowed value is " + (value < least ? least : most));
                }
                value *= 20.0;
            }
            case "time_of_day" -> {
                kind = KeyframeKind.TIME_OF_DAY;
                field = "time";
            }
            case "freeze" -> {
                kind = KeyframeKind.FREEZE;
                field = "hold_seconds";
            }
            case "timelapse" -> {
                kind = KeyframeKind.TIMELAPSE;
                field = "real_seconds";
            }
            default -> throw new McpToolException("'type' must be one of fov, speed, time_of_day, freeze, timelapse, got \"" + args.string("type")
                + "\". Use keyframe_set for the other kinds; keyframe_types lists them");
        }
        JsonObject values = new JsonObject();
        values.addProperty(field, value);
        Keyframe keyframe;
        try {
            keyframe = Keyframe.fromJson(kind, values).withTick(tick).withInterpolation(interpolation(args));
        } catch (IllegalArgumentException e) {
            throw new McpToolException(e.getMessage().replace("'" + field + "'", "'value'"));
        }
        return put(args, history, keyframe);
    }

    private static JsonElement set(McpArgs args, EditHistory history) {
        int tick = tick(args, "tick");
        JsonObject values = args.object("keyframe");
        KeyframeKind kind;
        if (args.has("track")) {
            kind = history.scene().track(trackIndex(history, args.integer("track"))).kind();
            if (args.has("type") && KeyframeKind.parse(args.string("type")) != kind) {
                throw new McpToolException("Track " + args.integer("track") + " holds " + kind.id() + " keyframes, not " + KeyframeKind.parse(args.string("type")).id()
                    + ". Leave out 'track' to use the first track of that kind, or call keyframes_list for the tracks");
            }
        } else if (args.has("type")) {
            kind = KeyframeKind.parse(args.string("type"));
        } else {
            throw new McpToolException("Give 'track' (an index from keyframes_list) or 'type' (an id from keyframe_types)");
        }
        Keyframe keyframe = Keyframe.fromJson(kind, values).withTick(tick);
        if (args.has("interpolation") || !values.has("interpolation")) {
            keyframe = keyframe.withInterpolation(interpolation(args));
        }
        return put(args, history, keyframe);
    }

    /** Puts a keyframe on the track the 'track' argument names, or else on the first track of its kind */
    private static JsonElement put(McpArgs args, EditHistory history, Keyframe keyframe) {
        int index;
        if (args.has("track")) {
            index = trackIndex(history, args.integer("track"));
            KeyframeKind holds = history.scene().track(index).kind();
            if (holds != keyframe.kind()) {
                throw new McpToolException("Track " + index + " holds " + holds.id() + " keyframes, not " + keyframe.kind().id()
                    + ". Call keyframes_list for the tracks, or leave out 'track' to use the first " + keyframe.kind().id() + " track");
            }
            history.setKeyframe(index, keyframe);
        } else {
            index = history.setKeyframe(keyframe);
        }
        JsonObject json = placed(history, index, keyframe.tick());
        if (keyframe.kind().drivesCamera()) {
            // An agent that lays a camera path expects playback to follow it
            EditorSession.setFollowCamera(true);
            json.addProperty("follow_camera", true);
        }
        return changed(json, history);
    }

    // ------------------------------------------------------------------ Helpers

    /** The interpolation argument, or the setting for new keyframes when it is left out */
    private static Interpolation interpolation(McpArgs args) {
        String given = args.optString("interpolation");
        if (given != null) {
            return Interpolation.parse(given);
        }
        Object setting = Host.get().<Object>setting("defaultInterpolation", "SMOOTH");
        try {
            return Interpolation.parse(String.valueOf(setting));
        } catch (IllegalArgumentException e) {
            return Interpolation.SMOOTH;
        }
    }

    /** A tick argument that must lie inside the open recording */
    private static int tick(McpArgs args, String name) {
        int tick = args.integer(name);
        int total = Playback.total();
        if (tick < 0 || tick > total) {
            throw new McpToolException("'" + name + "' must be a tick of the open recording, from 0 to " + total + ", got " + tick
                + "; the nearest allowed value is " + (tick < 0 ? 0 : total));
        }
        return tick;
    }

    private static int trackIndex(EditHistory history, int index) {
        int count = history.scene().tracks().size();
        if (index < 0 || index >= count) {
            throw new McpToolException("There is no track " + index + " in the current scene: "
                + (count == 0 ? "it has no tracks yet. Add one with track_add" : "its tracks go from 0 to " + (count - 1) + ". Call keyframes_list to see them"));
        }
        return index;
    }

    private static int sceneIndex(EditHistory history, int index) {
        int count = history.state().scenes().size();
        if (index < 0 || index >= count) {
            throw new McpToolException("There is no scene " + index + ": scenes go from 0 to " + (count - 1) + ". Call scene_list to see them");
        }
        return index;
    }

    private static JsonObject steps(JsonObject json, EditHistory history) {
        json.addProperty("current_scene", history.state().currentSceneIndex());
        json.addProperty("can_undo", history.canUndo());
        json.addProperty("can_redo", history.canRedo());
        if (history.undoDescription() != null) {
            json.addProperty("undo", history.undoDescription());
        }
        if (history.redoDescription() != null) {
            json.addProperty("redo", history.redoDescription());
        }
        return json;
    }

    private static JsonObject scenes(EditHistory history) {
        EditState state = history.state();
        JsonArray list = new JsonArray();
        for (int i = 0; i < state.scenes().size(); i++) {
            list.add(scene(state, i));
        }
        JsonObject json = new JsonObject();
        json.addProperty("current_scene", state.currentSceneIndex());
        json.add("scenes", list);
        return json;
    }

    private static JsonObject scene(EditState state, int index) {
        Scene scene = state.scene(index);
        JsonObject json = new JsonObject();
        json.addProperty("index", index);
        json.addProperty("name", scene.name());
        json.addProperty("current", index == state.currentSceneIndex());
        List<Track> tracks = scene.tracks();
        int keyframes = 0;
        for (Track track : tracks) {
            keyframes += track.size();
        }
        json.addProperty("tracks", tracks.size());
        json.addProperty("keyframes", keyframes);
        if (scene.hasExportRange()) {
            json.addProperty("export_start", scene.exportStart());
            json.addProperty("export_end", scene.exportEnd());
        } else {
            json.add("export_start", JsonNull.INSTANCE);
            json.add("export_end", JsonNull.INSTANCE);
        }
        return json;
    }

    private static JsonObject track(Scene scene, int index, boolean withKeyframes) {
        Track track = scene.track(index);
        JsonObject json = new JsonObject();
        json.addProperty("index", index);
        json.addProperty("type", track.kind().id());
        json.addProperty("name", track.title());
        json.addProperty("enabled", track.enabled());
        if (withKeyframes) {
            JsonArray keyframes = new JsonArray();
            for (Keyframe keyframe : track.keyframes()) {
                keyframes.add(keyframe.toJson());
            }
            json.add("keyframes", keyframes);
        } else {
            json.addProperty("keyframes", track.size());
        }
        return json;
    }

    /** The reply for a keyframe that now sits on a tick of a track */
    private static JsonObject placed(EditHistory history, int index, int tick) {
        Track track = history.scene().track(index);
        Keyframe keyframe = track.at(tick);
        JsonObject json = new JsonObject();
        json.addProperty("track", index);
        json.addProperty("type", track.kind().id());
        json.add("keyframe", keyframe == null ? JsonNull.INSTANCE : keyframe.toJson());
        return json;
    }

}
