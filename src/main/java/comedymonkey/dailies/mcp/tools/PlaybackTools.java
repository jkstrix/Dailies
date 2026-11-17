package comedymonkey.dailies.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import comedymonkey.dailies.mcp.McpArgs;
import comedymonkey.dailies.mcp.McpProtocol;
import comedymonkey.dailies.mcp.McpSchema;
import comedymonkey.dailies.mcp.McpTool;
import comedymonkey.dailies.mcp.McpToolException;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.Recordings;
import comedymonkey.dailies.playback.Viewer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The MCP tools for watching a recording: listing and opening, the transport, the viewer's camera and riding along
 * with an entity. The tool names and arguments are those of the specification's appendix A.
 */
final class PlaybackTools {

    private PlaybackTools() {
    }

    static void register(McpProtocol.Registrar registrar) {
        registrar.add("replay_list",
            "List the recordings in the Dailies recordings folder, newest first: name, id, path, size, when it was made, and length in ticks and seconds.",
            McpSchema.object(), McpTool.ThreadMode.HTTP, 30, PlaybackTools::list);

        registrar.add("replay_open",
            "Open a recording for watching and editing. Give 'path' or 'name'. Returns as soon as opening has started: poll dailies_status until playback.loaded is true "
                + "before using the other replay tools. Leaves the world or recording that is open now.",
            McpSchema.object()
                .string("path", "Path of the recording file, absolute or relative to the recordings folder")
                .string("name", "File name (with or without its extension) or the recording's own name, as replay_list shows them"),
            McpTool.ThreadMode.HTTP, 30, PlaybackTools::open);

        registrar.add("replay_close", "Leave the open recording and go back to the title screen. Editor changes are already saved.",
            McpSchema.object(), args -> {
                CoreTools.allowOnly(args);
                if (!Playback.active()) {
                    throw new McpToolException("No recording is open");
                }
                Playback.close();
                return message("Closing the recording");
            });

        registrar.add("replay_play", "Play the open recording from the current tick, or from the start when it is at its end.",
            McpSchema.object(), args -> {
                CoreTools.allowOnly(args);
                act(Playback::play);
                return CoreTools.playbackState();
            });

        registrar.add("replay_pause", "Pause the open recording. The viewer can still fly around the stopped world.",
            McpSchema.object(), args -> {
                CoreTools.allowOnly(args);
                act(Playback::pause);
                return CoreTools.playbackState();
            });

        registrar.add("replay_seek",
            "Jump to a tick of the open recording (20 ticks to the second). The jump runs over the next frames: dailies_status shows playback.jumping false and the tick once it is there. "
                + "Does not change whether playback is paused.",
            McpSchema.object().integer("tick", "Tick to jump to. Clamped to 0..total_ticks").required("tick"),
            args -> {
                CoreTools.allowOnly(args, "tick");
                int tick = args.integer("tick");
                act(() -> Playback.jumpTo(tick, false));
                JsonObject json = CoreTools.playbackState();
                json.addProperty("target", Math.max(0, Math.min(Playback.total(), tick)));
                return json;
            });

        registrar.add("replay_set_speed", "Set how fast the open recording plays, as ticks per second. 20 is as recorded, 10 half speed, 40 double.",
            McpSchema.object().number("tick_rate", "Ticks per second, between 0.1 and 2000").required("tick_rate"),
            args -> {
                CoreTools.allowOnly(args, "tick_rate");
                double rate = args.number("tick_rate");
                if (!(rate >= 0.1 && rate <= 2000.0)) {
                    throw new McpToolException("'tick_rate' must be between 0.1 and 2000; the nearest allowed value is " + (rate < 0.1 ? "0.1" : "2000"));
                }
                requireLoaded();
                Playback.setTicksPerSecond(rate);
                return CoreTools.playbackState();
            });

        registrar.add("camera_get", "The viewer's camera in the open recording: position, yaw, pitch, roll, field of view, and the entity it is riding along with (null when it flies free).",
            McpSchema.object(), args -> {
                CoreTools.allowOnly(args);
                requireLoaded();
                return camera();
            });

        registrar.add("camera_set",
            "Move the viewer's camera. Give any of x, y, z, yaw, pitch, roll and fov; what is left out stays as it is. Stops riding along with an entity.",
            McpSchema.object()
                .number("x", "East-west position").number("y", "Height").number("z", "North-south position")
                .number("yaw", "Heading in degrees: 0 south, 90 west, 180 north, -90 east").number("pitch", "Tilt in degrees: -90 straight up to 90 straight down")
                .number("roll", "Roll in degrees").number("fov", "Field of view in degrees, 1 to 170. 0 hands it back to the game's own setting"),
            args -> {
                CoreTools.allowOnly(args, "x", "y", "z", "yaw", "pitch", "roll", "fov");
                requireLoaded();
                Vec3 at = Viewer.position();
                double pitch = args.optNumber("pitch", Viewer.pitch());
                if (pitch < -90.0 || pitch > 90.0) {
                    throw new McpToolException("'pitch' must be between -90 and 90; the nearest allowed value is " + (pitch < 0 ? "-90" : "90"));
                }
                double fov = args.optNumber("fov", Viewer.fov());
                if (fov != 0.0 && (fov < 1.0 || fov > 170.0)) {
                    throw new McpToolException("'fov' must be 0 or between 1 and 170; the nearest allowed value is " + (fov < 1.0 ? "1" : "170"));
                }
                Viewer.ride(null);
                Viewer.set(args.optNumber("x", at.x), args.optNumber("y", at.y), args.optNumber("z", at.z), (float) args.optNumber("yaw", Viewer.yaw()), (float) pitch);
                Viewer.setRoll((float) args.optNumber("roll", Viewer.roll()));
                Viewer.setFov(fov);
                return camera();
            });

        registrar.add("replay_list_entities", "Entities near the viewer in the open recording, nearest first: numeric id, uuid, type, name, position and distance.",
            McpSchema.object()
                .number("radius", "How far from the viewer to look, in blocks (default 64)")
                .string("type", "Only entities of this type, eg. minecraft:pig")
                .integer("limit", "Most entities to return (default 50)"),
            args -> {
                CoreTools.allowOnly(args, "radius", "type", "limit");
                requireLoaded();
                return entities(args.optNumber("radius", 64.0), args.optString("type"), args.optInteger("limit", 50));
            });

        registrar.add("camera_spectate", "Ride along with an entity in the open recording, seeing through its eyes. Give entity_id or uuid; give neither to go back to the free camera.",
            McpSchema.object()
                .integer("entity_id", "Numeric entity id from replay_list_entities")
                .string("uuid", "Entity uuid from replay_list_entities"),
            args -> {
                CoreTools.allowOnly(args, "entity_id", "uuid");
                requireLoaded();
                Minecraft minecraft = Minecraft.getInstance();
                Entity target = null;
                if (args.has("entity_id")) {
                    target = minecraft.level.getEntity(args.integer("entity_id"));
                } else if (args.has("uuid")) {
                    java.util.UUID uuid = args.uuid("uuid");
                    for (Entity entity : minecraft.level.entitiesForRendering()) {
                        if (entity.getUUID().equals(uuid)) {
                            target = entity;
                        }
                    }
                } else {
                    Viewer.ride(null);
                    return camera();
                }
                if (target == null || target == minecraft.player) {
                    throw new McpToolException("There is no such entity at this tick. Call replay_list_entities for the ones that are here");
                }
                Viewer.ride(target);
                return camera();
            });
    }

    private static void act(Runnable action) {
        try {
            action.run();
        } catch (IllegalStateException e) {
            throw new McpToolException(e.getMessage() + ". Call dailies_status to see what is open");
        }
    }

    private static void requireLoaded() {
        if (!Playback.loaded()) {
            throw new McpToolException(Playback.active() ? "The recording is still loading. Poll dailies_status until playback.loaded is true"
                : "No recording is open. Open one with replay_open");
        }
    }

    private static JsonObject message(String text) {
        JsonObject json = new JsonObject();
        json.addProperty("message", text);
        return json;
    }

    private static JsonElement list(McpArgs args) {
        CoreTools.allowOnly(args);
        JsonArray recordings = new JsonArray();
        for (Recordings.Entry entry : Recordings.list()) {
            JsonObject json = new JsonObject();
            json.addProperty("name", entry.manifest().name);
            json.addProperty("id", entry.manifest().id);
            json.addProperty("file", entry.file().getFileName().toString());
            json.addProperty("path", entry.file().toAbsolutePath().toString());
            json.addProperty("size_bytes", entry.size());
            json.addProperty("created", Instant.ofEpochMilli(entry.manifest().created).toString());
            json.addProperty("total_ticks", entry.manifest().totalTicks);
            json.addProperty("seconds", entry.manifest().totalTicks / 20.0);
            json.addProperty("world", entry.manifest().worldName);
            json.addProperty("recorder", entry.manifest().recorderName);
            json.addProperty("markers", entry.manifest().markers.size());
            if (entry.manifest().recovered) {
                json.addProperty("recovered", true);
            }
            recordings.add(json);
        }
        JsonObject json = new JsonObject();
        json.addProperty("folder", comedymonkey.dailies.capture.Recorder.folder().toAbsolutePath().toString());
        json.add("recordings", recordings);
        return json;
    }

    private static JsonElement open(McpArgs args) throws Exception {
        CoreTools.allowOnly(args, "path", "name");
        String path = args.optString("path");
        String name = args.optString("name");
        if ((path == null || path.isBlank()) && (name == null || name.isBlank())) {
            throw new McpToolException("Give 'path' or 'name'. Call replay_list for the recordings there are");
        }
        Path file = Recordings.find(path, name);
        if (file == null) {
            throw new McpToolException("There is no such recording. Call replay_list for the recordings there are");
        }
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.submit(() -> {
            if (comedymonkey.dailies.capture.Recorder.get().state() != comedymonkey.dailies.capture.Recorder.State.IDLE) {
                throw new McpToolException("A recording is being made. Finish it with recording_finish first");
            }
            Playback.open(file);
        }).get();
        JsonObject json = message("Opening. Poll dailies_status until playback.loaded is true");
        json.addProperty("path", file.toAbsolutePath().toString());
        json.addProperty("opening", true);
        return json;
    }

    private static JsonObject camera() {
        Vec3 at = Viewer.position();
        JsonObject json = new JsonObject();
        json.addProperty("x", at.x);
        json.addProperty("y", at.y);
        json.addProperty("z", at.z);
        json.addProperty("yaw", Viewer.yaw());
        json.addProperty("pitch", Viewer.pitch());
        json.addProperty("roll", Viewer.roll());
        json.addProperty("fov", Viewer.fov() > 0.0 ? Viewer.fov() : Minecraft.getInstance().options.fov().get());
        json.addProperty("fov_forced", Viewer.fov() > 0.0);
        Entity riding = Viewer.riding();
        if (riding == null) {
            json.add("spectating", com.google.gson.JsonNull.INSTANCE);
        } else {
            json.add("spectating", describe(riding, at));
        }
        return json;
    }

    private static JsonObject entities(double radius, String type, int limit) {
        Minecraft minecraft = Minecraft.getInstance();
        Vec3 at = Viewer.position();
        List<Entity> found = new ArrayList<>();
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity == minecraft.player || entity.position().distanceTo(at) > radius) {
                continue;
            }
            if (type != null && !BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().equals(type)) {
                continue;
            }
            found.add(entity);
        }
        found.sort(Comparator.comparingDouble(entity -> entity.position().distanceToSqr(at)));
        JsonArray list = new JsonArray();
        for (Entity entity : found.subList(0, Math.min(found.size(), Math.max(1, limit)))) {
            list.add(describe(entity, at));
        }
        JsonObject json = new JsonObject();
        json.addProperty("tick", Playback.position());
        json.addProperty("found", found.size());
        json.add("entities", list);
        return json;
    }

    private static JsonObject describe(Entity entity, Vec3 from) {
        JsonObject json = new JsonObject();
        json.addProperty("entity_id", entity.getId());
        json.addProperty("uuid", entity.getUUID().toString());
        json.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        json.addProperty("name", entity.getName().getString());
        json.addProperty("x", entity.getX());
        json.addProperty("y", entity.getY());
        json.addProperty("z", entity.getZ());
        json.addProperty("distance", Math.round(entity.position().distanceTo(from) * 100.0) / 100.0);
        return json;
    }

}
