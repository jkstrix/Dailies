package comedymonkey.dailies.mcp.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.mcp.McpArgs;
import comedymonkey.dailies.mcp.McpProtocol;
import comedymonkey.dailies.mcp.McpSchema;
import comedymonkey.dailies.mcp.McpTool;
import comedymonkey.dailies.mcp.McpToolException;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The MCP tools that make recordings: the same five actions as the buttons and keys, and dropping a marker.
 */
final class RecordingTools {

    private RecordingTools() {
    }

    static void register(McpProtocol.Registrar registrar) {
        registrar.add("recording_start",
            "Start recording the world or server the player is in. Fails when no world is open, when a recording is being watched, or when one is already being made.",
            McpSchema.object(), args -> {
                CoreTools.allowOnly(args);
                act(() -> Recorder.get().start());
                return state("Recording started");
            });

        registrar.add("recording_finish",
            "Finish the recording being made and save it in the recordings folder at once, without asking for a name on screen. Returns the saved file.",
            McpSchema.object().string("name", "What to call the recording, also used for the file. Left out, the date and time it started are used"),
            McpTool.ThreadMode.HTTP, 60, RecordingTools::finish);

        registrar.add("recording_pause",
            "Pause the recording being made. Nothing is captured and its clock stands still until recording_resume.",
            McpSchema.object(), args -> {
                CoreTools.allowOnly(args);
                act(() -> Recorder.get().pause());
                return state("Recording paused");
            });

        registrar.add("recording_resume",
            "Carry on with a paused recording. Playback shows the world as it is now, not as it was when the recording was paused.",
            McpSchema.object(), args -> {
                CoreTools.allowOnly(args);
                act(() -> Recorder.get().resume());
                return state("Recording resumed");
            });

        registrar.add("recording_cancel",
            "Stop the recording being made and delete everything captured. This cannot be undone.",
            McpSchema.object(), args -> {
                CoreTools.allowOnly(args);
                act(() -> Recorder.get().cancel());
                return state("Recording discarded");
            });

        registrar.add("recording_add_marker",
            "Drop a marker at this moment of the recording being made. Markers show on the timeline in playback and are logged as moments.",
            McpSchema.object()
                .string("color", "Marker colour as hex RGB, eg. #FF5555 (the default)")
                .string("description", "Text to keep with the marker")
                .bool("save_position", "Keep where the player is standing with the marker (default true)"),
            args -> {
                CoreTools.allowOnly(args, "color", "description", "save_position");
                String colour = args.optString("color");
                int rgb = colour == null ? 0xFF5555 : McpArgs.parseHexColour("color", colour);
                int tick = Recorder.get().recordingTick();
                act(() -> Recorder.get().addMarker(rgb, args.optString("description"), args.optBool("save_position", true)));
                JsonObject json = state("Marker added");
                json.addProperty("tick", tick);
                return json;
            });
    }

    private interface Action {
        void run() throws Exception;
    }

    /** Runs an action whose refusals are meant for the caller to read */
    private static void act(Action action) throws Exception {
        try {
            action.run();
        } catch (IllegalStateException e) {
            throw new McpToolException(e.getMessage());
        }
    }

    private static JsonObject state(String message) {
        Recorder recorder = Recorder.get();
        JsonObject json = new JsonObject();
        json.addProperty("message", message);
        json.addProperty("active", recorder.state() != Recorder.State.IDLE);
        json.addProperty("paused", recorder.state() == Recorder.State.PAUSED);
        if (recorder.recordingId() != null) {
            json.addProperty("recording_id", recorder.recordingId());
            json.addProperty("tick", recorder.recordingTick());
        }
        return json;
    }

    private static JsonElement finish(McpArgs args) throws Exception {
        CoreTools.allowOnly(args, "name");
        String name = args.optString("name");
        CompletableFuture<Path> saved;
        try {
            saved = Minecraft.getInstance().submit(() -> Recorder.get().finish(name)).get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IllegalStateException refused) {
                throw new McpToolException(refused.getMessage());
            }
            throw e;
        }
        try {
            Path file = saved.get(45, TimeUnit.SECONDS);
            JsonObject json = new JsonObject();
            json.addProperty("message", "Recording saved");
            json.addProperty("path", file.toAbsolutePath().toString());
            json.addProperty("file", file.getFileName().toString());
            return json;
        } catch (TimeoutException e) {
            throw new McpToolException("The recording is still being written. Call replay_list in a moment to find it");
        } catch (ExecutionException e) {
            throw new McpToolException("The recording could not be saved: " + e.getCause().getMessage() + ". What was written is kept and can be recovered");
        }
    }

}
