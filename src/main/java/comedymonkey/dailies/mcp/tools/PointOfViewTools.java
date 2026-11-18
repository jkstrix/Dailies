package comedymonkey.dailies.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.edit.EditHistory;
import comedymonkey.dailies.edit.LinkedRecording;
import comedymonkey.dailies.edit.Scene;
import comedymonkey.dailies.editor.EditorSession;
import comedymonkey.dailies.editor.PointOfView;
import comedymonkey.dailies.export.ExportQueue;
import comedymonkey.dailies.export.QueueRunner;
import comedymonkey.dailies.export.Worker;
import comedymonkey.dailies.library.Library;
import comedymonkey.dailies.library.Links;
import comedymonkey.dailies.library.Sharing;
import comedymonkey.dailies.mcp.McpArgs;
import comedymonkey.dailies.mcp.McpProtocol;
import comedymonkey.dailies.mcp.McpSchema;
import comedymonkey.dailies.mcp.McpTool;
import comedymonkey.dailies.mcp.McpToolException;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Several points of view (FILE-15, PLAY-18, PLAY-19): sharing a session with a friend and taking in theirs, the
 * recordings linked to one, linking them to a scene, and the same stretch exported from every point of view.
 */
final class PointOfViewTools {

    private PointOfViewTools() {
    }

    static void register(McpProtocol.Registrar registrar) {
        registrar.add("replay_share",
            "Share session: pack a recording and the moments sessions it was made in into one .dailiesshare file to send to a friend. The friend imports it with replay_import "
                + "(or Import in the recordings list) and gets the recording in their library and the moments on their page, marked as yours. Only your own voice lines go in the "
                + "pack; what other players said stays on this computer. Returns the pack's path.",
            McpSchema.object()
                .string("recording", "The recording: a name, a file name, a path or a recording id. Default: the one open in playback")
                .string("output_path", "Where to write the pack (default: the 'shared' folder in the Dailies folder, named after the recording)"),
            McpTool.ThreadMode.HTTP, 600, PointOfViewTools::share);

        registrar.add("replay_import",
            "Import a friend's shared session (.dailiesshare): their recording goes into the recordings folder under shared/<their name> (unless it is there already) and their "
                + "moments sessions onto the moments page, marked as theirs. Their recording then links by itself with your recordings of the same server at the same time "
                + "(replay_linked).",
            McpSchema.object().string("path", "The .dailiesshare file").required("path"),
            McpTool.ThreadMode.HTTP, 600, PointOfViewTools::importPack);

        registrar.add("replay_linked",
            "The other players' recordings linked to a recording as points of view: made on the same server at the same time, lined up by the server's game time. Each with "
                + "the stretches of this recording it also holds (in this recording's ticks) and where this recording's first shared tick is in it. Also says which recording a "
                + "scene with point of view keyframes is showing right now.",
            McpSchema.object().string("recording", "The recording: a name, a file name, a path or a recording id. Default: the one open in playback"),
            McpTool.ThreadMode.HTTP, 60, PointOfViewTools::linked);

        registrar.add("scene_link_recording",
            "Let the current scene show another player's recording of the same stretch (PLAY-19): it appears as a lane on the timeline and can be named by point_of_view "
                + "keyframes (keyframe_set with type point_of_view and keyframe {tick, recording_id, label}). Without recording_id every linked recording is added. Can be undone.",
            McpSchema.object().string("recording_id", "The id of one linked recording, from replay_linked. Leave out to link them all"),
            McpTool.ThreadMode.HTTP, 60, PointOfViewTools::link);

        registrar.add("scene_unlink_recording",
            "Take a linked recording out of the current scene. Its point of view keyframes stay, and show the scene's own recording until it is linked again. Can be undone.",
            McpSchema.object().string("recording_id", "The id of the linked recording").required("recording_id"),
            McpTool.ThreadMode.CLIENT, 30, PointOfViewTools::unlink);

        registrar.add("export_points_of_view",
            "Export the same stretch from every point of view, as one batch: the open recording's stretch, and each linked recording's same stretch lined up by game time. "
                + "One job per point of view goes on the export queue, together: each names its recording, its own ticks and the file it will write, and is seen "
                + "through the eyes of whoever made that recording (the camera rides along with them, with their HUD; no scene's keyframes or looks apply). "
                + "export_queue_start runs them, in the background by default.",
            McpSchema.object()
                .integer("start_tick", "First tick, in the open recording (default: the current scene's export range, or the whole recording)")
                .integer("end_tick", "Last tick, in the open recording")
                .string("preset", "The export preset for every job, from export_presets_list (default: the first one)")
                .string("output_folder", "The folder the videos go to: an absolute path, or a folder inside dailies/exports (default: dailies/exports)"),
            McpTool.ThreadMode.HTTP, 120, PointOfViewTools::exportAll);
    }

    // --- Sharing ---

    private static Path recording(McpArgs args) {
        String text = args.optString("recording");
        if (text == null || text.isBlank()) {
            String open = Playback.recordingId();
            Path file = open == null ? null : Library.index().findById(open);
            if (file == null) {
                throw new McpToolException("No recording is open: give 'recording'. Call replay_list for the recordings there are");
            }
            return file;
        }
        Path file = Library.find(text);
        if (file == null) {
            throw new McpToolException("There is no recording '" + text + "'. Call replay_list for the recordings there are");
        }
        return file;
    }

    private static JsonElement share(McpArgs args) throws Exception {
        CoreTools.allowOnly(args, "recording", "output_path");
        Path file = recording(args);
        String output = args.optString("output_path");
        Path pack = Sharing.share(file, output == null || output.isBlank() ? null : Path.of(output));
        JsonObject json = new JsonObject();
        json.addProperty("path", pack.toAbsolutePath().toString());
        json.addProperty("size_bytes", Files.size(pack));
        json.add("info", comedymonkey.dailies.library.SharePack.info(pack));
        return json;
    }

    private static JsonElement importPack(McpArgs args) throws Exception {
        CoreTools.allowOnly(args, "path");
        Path pack = Path.of(args.string("path"));
        if (!Files.isRegularFile(pack)) {
            throw new McpToolException("There is no file at " + pack);
        }
        Sharing.Imported imported;
        try {
            imported = Sharing.importPack(pack);
        } catch (java.io.IOException e) {
            throw new McpToolException("The shared session could not be imported: " + e.getMessage());
        }
        JsonObject json = new JsonObject();
        json.addProperty("path", imported.recording().toAbsolutePath().toString());
        json.addProperty("recording_id", imported.recordingId());
        json.addProperty("recorder", imported.recorder());
        json.addProperty("already_here", imported.alreadyHere());
        JsonArray sessions = new JsonArray();
        imported.sessions().forEach(sessions::add);
        json.add("moments_sessions", sessions);
        return json;
    }

    // --- Links ---

    private static JsonElement linked(McpArgs args) {
        CoreTools.allowOnly(args, "recording");
        Path file = recording(args);
        Links.Link own = Links.describe(file);
        if (own == null) {
            throw new McpToolException("The recording cannot be read");
        }
        JsonObject json = new JsonObject();
        json.addProperty("recording_id", own.id());
        json.addProperty("has_time_index", !own.alignment().isEmpty());
        JsonArray list = new JsonArray();
        for (Links.Link other : Links.linked(own)) {
            JsonObject entry = new JsonObject();
            entry.addProperty("recording_id", other.id());
            entry.addProperty("name", other.manifest().name);
            entry.addProperty("recorder", other.manifest().recorderName);
            entry.addProperty("path", other.file().toAbsolutePath().toString());
            entry.addProperty("total_ticks", other.manifest().totalTicks);
            JsonArray stretches = new JsonArray();
            List<int[]> shared = own.alignment().shared(other.alignment());
            for (int[] stretch : shared) {
                JsonArray pair = new JsonArray();
                pair.add(stretch[0]);
                pair.add(stretch[1]);
                stretches.add(pair);
            }
            entry.add("shared", stretches);
            entry.addProperty("shared_ticks", own.alignment().sharedTicks(other.alignment()));
            if (!shared.isEmpty()) {
                entry.addProperty("first_shared_tick", shared.get(0)[0]);
                entry.addProperty("first_shared_tick_there", own.alignment().map(shared.get(0)[0], other.alignment()));
            }
            list.add(entry);
        }
        json.add("linked", list);
        JsonObject view = new JsonObject();
        view.addProperty("scene_recording_id", PointOfView.homeId());
        view.addProperty("shown_recording_id", PointOfView.shownId());
        view.addProperty("away", PointOfView.away());
        view.addProperty("switching", PointOfView.switching());
        json.add("point_of_view", view);
        return json;
    }

    private static JsonElement link(McpArgs args) throws Exception {
        CoreTools.allowOnly(args, "recording_id");
        String own = Playback.loaded() ? PointOfView.homeId() : null;
        if (own == null) {
            throw new McpToolException("No recording is open in the editor. Open one with replay_open first");
        }
        String wanted = args.optString("recording_id");
        List<LinkedRecording> found = new ArrayList<>();
        for (Links.Link other : Links.linked(own)) {
            if (wanted == null || wanted.isBlank() || other.id().equals(wanted)) {
                found.add(new LinkedRecording(other.id(), other.manifest().name, other.manifest().recorderName));
            }
        }
        if (found.isEmpty()) {
            throw new McpToolException(wanted == null || wanted.isBlank()
                ? "No other player's recording is linked to this one. Import a friend's shared session (replay_import), then try again"
                : "The recording " + wanted + " is not linked to this one. Call replay_linked for those that are");
        }
        return Minecraft.getInstance().submit(() -> {
            if (!EditorSession.open() || !own.equals(PointOfView.homeId())) {
                throw new McpToolException("The recording was closed in the meantime");
            }
            EditHistory history = EditorSession.history();
            history.beginGroup(found.size() == 1 ? "Link the point of view of " + found.get(0).label() : "Link " + found.size() + " points of view");
            try {
                for (LinkedRecording recording : found) {
                    history.linkRecording(recording);
                }
            } finally {
                history.endGroup();
            }
            PointOfView.prepare(history.scene());
            return describe(history.scene());
        }).get(30, TimeUnit.SECONDS);
    }

    private static JsonElement unlink(McpArgs args) {
        CoreTools.allowOnly(args, "recording_id");
        if (!EditorSession.open()) {
            throw new McpToolException("No recording is open in the editor");
        }
        EditHistory history = EditorSession.history();
        try {
            history.unlinkRecording(args.string("recording_id"));
        } catch (IllegalArgumentException e) {
            throw new McpToolException(e.getMessage());
        }
        return describe(history.scene());
    }

    private static JsonObject describe(Scene scene) {
        JsonObject json = new JsonObject();
        json.addProperty("scene", scene.name());
        JsonArray linked = new JsonArray();
        for (LinkedRecording recording : scene.linked()) {
            linked.add(recording.toJson());
        }
        json.add("linked", linked);
        return json;
    }

    // --- The same stretch from every point of view ---

    private static JsonElement exportAll(McpArgs args) throws Exception {
        CoreTools.allowOnly(args, "start_tick", "end_tick", "preset", "output_folder");
        if (Worker.active()) {
            throw new McpToolException("This is the background export game");
        }
        String own = Playback.loaded() ? PointOfView.homeId() : null;
        if (own == null) {
            throw new McpToolException("No recording is open. Open the recording whose stretch is wanted with replay_open first");
        }
        int[] range = Minecraft.getInstance().submit(() -> {
            Scene scene = EditorSession.open() ? EditorSession.scene() : null;
            int total = PointOfView.total();
            return scene != null && scene.hasExportRange() ? new int[]{scene.exportStart(), scene.exportEnd()} : new int[]{0, total};
        }).get(30, TimeUnit.SECONDS);
        int start = args.has("start_tick") ? args.integer("start_tick") : range[0];
        int end = args.has("end_tick") ? args.integer("end_tick") : range[1];
        if (start < 0 || end < start) {
            throw new McpToolException("'end_tick' (" + end + ") must not be before 'start_tick' (" + start + "), and both must be 0 or more");
        }
        String preset = args.optString("preset");
        String folder = args.optString("output_folder");
        Path ownFile = Library.index().findById(own);
        List<Job> jobs = new ArrayList<>();
        if (ownFile != null) {
            jobs.add(new Job(ownFile, own, comedymonkey.dailies.file.RecordingFile.readManifest(ownFile).recorderName, start, end));
        }
        for (Host.PointOfView view : Links.pointsOfView(own, start, end)) {
            jobs.add(new Job(view.file(), view.recordingId(), view.recorder(), view.startTick(), view.endTick()));
        }
        if (jobs.isEmpty()) {
            throw new McpToolException("The open recording is not in the recordings folder, and no other player's recording holds ticks " + start + " to " + end);
        }
        // Every job is made before any goes on the queue, so a preset or a recording that is refused leaves no half batch
        List<ExportQueue.Job> made = new ArrayList<>();
        try {
            for (Job job : jobs) {
                String name = "Point of view - " + (job.recorder().isBlank() ? job.recordingId() : job.recorder()) + " - ticks " + job.startTick() + " to " + job.endTick();
                made.add(QueueRunner.jobForRange(job.file(), job.startTick(), job.endTick(), preset, folder, name));
            }
        } catch (IllegalArgumentException e) {
            throw new McpToolException(e.getMessage());
        }
        List<ExportQueue.Job> added = QueueRunner.addAll(made);
        JsonArray list = new JsonArray();
        for (ExportQueue.Job job : added) {
            JsonObject entry = new JsonObject();
            entry.addProperty("job_id", job.id);
            entry.addProperty("recording_id", job.recordingId);
            entry.addProperty("recorder", job.recorder);
            entry.addProperty("path", job.recording);
            entry.addProperty("start_tick", job.startTick);
            entry.addProperty("end_tick", job.endTick);
            entry.addProperty("file", job.output);
            if (job.preset != null) {
                entry.addProperty("preset", job.preset);
            }
            entry.addProperty("queued", true);
            list.add(entry);
        }
        JsonObject json = new JsonObject();
        json.add("jobs", list);
        json.addProperty("queued", true);
        json.addProperty("note", "On the export queue, one job per point of view, each seen through its recorder's eyes. export_queue_start runs them; "
            + "export_queue_list shows their progress");
        return json;
    }

    private record Job(Path file, String recordingId, String recorder, int startTick, int endTick) {
    }

}
