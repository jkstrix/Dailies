package comedymonkey.dailies.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.config.DailiesConfig;
import comedymonkey.dailies.config.Setting;
import comedymonkey.dailies.mcp.McpArgs;
import comedymonkey.dailies.mcp.McpProtocol;
import comedymonkey.dailies.mcp.McpSchema;
import comedymonkey.dailies.mcp.McpTool;
import comedymonkey.dailies.mcp.McpToolException;
import comedymonkey.dailies.playback.Playback;

import java.util.Set;
import java.util.TreeSet;

/**
 * The core's own MCP tools (specification appendix A). The groups for recording, playback, editing, looks, export
 * and the Smart Camera join this class as those parts of the mod are built.
 */
public final class CoreTools {

    private CoreTools() {
    }

    public static void register(McpProtocol.Registrar registrar) {
        registrar.add("dailies_status",
            "Overall state of Dailies: whether a recording is being made or is paused, whether a recording is open in playback "
                + "(its tick, length, speed and whether it has finished loading), whether an export is running, and the mod version. "
                + "Call this first, and poll it after opening a recording or starting an export.",
            McpSchema.object(), McpTool.ThreadMode.CLIENT, McpTool.DEFAULT_TIMEOUT_SECONDS, CoreTools::status);

        registrar.add("config_get",
            "Read Dailies' settings. Returns every setting with its group, type, range, default and current value, or just one when 'key' is given.",
            McpSchema.object().string("key", "Name of one setting, eg. 'autoStart'. Leave out for all of them"),
            McpTool.ThreadMode.HTTP, McpTool.DEFAULT_TIMEOUT_SECONDS, CoreTools::configGet);

        registrar.add("config_set",
            "Change one of Dailies' settings and save the settings file. Use config_get for the names, types and ranges. "
                + "Changing mcpPort or mcpEnabled restarts or stops this server after the reply.",
            McpSchema.object()
                .string("key", "Name of the setting, from config_get")
                .any("value", "The new value: true or false, a number, text, or the name of a choice")
                .required("key", "value"),
            McpTool.ThreadMode.HTTP, McpTool.DEFAULT_TIMEOUT_SECONDS, CoreTools::configSet);

        RecordingTools.register(registrar);
        PlaybackTools.register(registrar);
        LibraryTools.register(registrar);
        ExportTools.register(registrar);
        SmartCameraTools.register(registrar);
        EditingTools.register(registrar);
        LooksTools.register(registrar);
        PointOfViewTools.register(registrar);
    }

    /** Refuses arguments the tool does not have, naming the ones it does */
    static void allowOnly(McpArgs args, String... known) {
        Set<String> allowed = new TreeSet<>(Set.of(known));
        for (String name : args.names()) {
            if (!allowed.contains(name)) {
                throw new McpToolException("Unknown argument '" + name + "'. " + (allowed.isEmpty() ? "This tool takes no arguments" : "Valid arguments: " + String.join(", ", allowed)));
            }
        }
    }

    private static JsonElement status(McpArgs args) {
        allowOnly(args);
        Host.Mod mod = Host.get();
        JsonObject json = new JsonObject();
        json.addProperty("version", mod.modVersion());

        JsonObject recording = new JsonObject();
        Recorder recorder = Recorder.get();
        recording.addProperty("active", recorder.state() != Recorder.State.IDLE);
        recording.addProperty("paused", recorder.state() == Recorder.State.PAUSED);
        if (recorder.recordingId() != null) {
            recording.addProperty("recording_id", recorder.recordingId());
            recording.addProperty("tick", recorder.recordingTick());
        }
        json.add("recording", recording);

        json.add("playback", playbackState());

        json.add("export", ExportTools.status());
        return json;
    }

    /** What dailies_status and the transport tools say about the open recording */
    static JsonObject playbackState() {
        JsonObject playback = new JsonObject();
        playback.addProperty("open", Playback.active());
        playback.addProperty("loaded", Playback.loaded());
        if (Playback.loaded()) {
            playback.addProperty("recording_id", Playback.recordingId());
            playback.addProperty("tick", Playback.position());
            playback.addProperty("total_ticks", Playback.total());
            playback.addProperty("paused", Playback.paused());
            playback.addProperty("tick_rate", Playback.ticksPerSecond());
            playback.addProperty("jumping", Playback.jumping());
            playback.addProperty("server_packs_applied", comedymonkey.dailies.playback.PackPlayback.applied());
        }
        if (Playback.lastError() != null) {
            playback.addProperty("error", Playback.lastError());
        }
        JsonObject differences = comedymonkey.dailies.extras.ModDifferences.summary();
        if (differences != null) {
            playback.add("mods_that_differ", differences);
        }
        return playback;
    }

    private static JsonElement configGet(McpArgs args) {
        allowOnly(args, "key");
        String key = args.optString("key");
        if (key != null) {
            return describe(find(key));
        }
        JsonArray settings = new JsonArray();
        for (Setting<?> setting : DailiesConfig.all()) {
            settings.add(describe(setting));
        }
        JsonObject json = new JsonObject();
        json.addProperty("loaded", DailiesConfig.loaded());
        json.add("settings", settings);
        return json;
    }

    private static JsonElement configSet(McpArgs args) {
        allowOnly(args, "key", "value");
        Setting<?> setting = find(args.string("key"));
        if (!DailiesConfig.loaded()) {
            throw new McpToolException("The settings file has not loaded yet. Try again in a moment");
        }
        JsonElement value = args.element("value");
        if (value == null || !value.isJsonPrimitive()) {
            throw new McpToolException("Argument 'value' must be true or false, a number or text");
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        Object raw = primitive.isBoolean() ? (Object) primitive.getAsBoolean() : primitive.isNumber() ? (Object) primitive.getAsDouble() : primitive.getAsString();
        try {
            Host.get().saveSetting(setting.name(), raw);
        } catch (IllegalArgumentException e) {
            throw new McpToolException(e.getMessage());
        }
        return describe(setting);
    }

    private static Setting<?> find(String key) {
        Setting<?> setting = DailiesConfig.find(key);
        if (setting == null) {
            throw new McpToolException("There is no setting called '" + key + "'. Call config_get without a key to list them all");
        }
        return setting;
    }

    private static JsonObject describe(Setting<?> setting) {
        JsonObject json = new JsonObject();
        json.addProperty("key", setting.name());
        json.addProperty("group", setting.group());
        json.addProperty("type", switch (setting.kind()) {
            case BOOLEAN -> "boolean";
            case INTEGER -> "integer";
            case DECIMAL -> "number";
            case TEXT -> "string";
            case CHOICE -> "choice";
        });
        addValue(json, "value", setting.get());
        addValue(json, "default", setting.fallback());
        if (setting.min() != null) {
            json.addProperty("min", setting.min());
        }
        if (setting.max() != null) {
            json.addProperty("max", setting.max());
        }
        String[] choices = setting.choices();
        if (choices != null) {
            JsonArray names = new JsonArray();
            for (String choice : choices) {
                names.add(choice);
            }
            json.add("choices", names);
        }
        json.addProperty("description", setting.comment());
        return json;
    }

    private static void addValue(JsonObject json, String name, Object value) {
        if (value instanceof Boolean flag) {
            json.addProperty(name, flag);
        } else if (value instanceof Number number) {
            json.addProperty(name, number);
        } else if (value instanceof Enum<?> constant) {
            json.addProperty(name, constant.name());
        } else {
            json.addProperty(name, String.valueOf(value));
        }
    }

}
