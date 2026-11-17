package comedymonkey.dailies.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import comedymonkey.dailies.export.Captions;
import comedymonkey.dailies.export.Export;
import comedymonkey.dailies.export.ExportQueue;
import comedymonkey.dailies.export.QueueRunner;
import comedymonkey.dailies.export.Worker;
import comedymonkey.dailies.mcp.McpArgs;
import comedymonkey.dailies.mcp.McpProtocol;
import comedymonkey.dailies.mcp.McpSchema;
import comedymonkey.dailies.mcp.McpTool;
import comedymonkey.dailies.mcp.McpToolException;
import net.minecraft.client.Minecraft;

import java.util.Locale;
import java.util.concurrent.ExecutionException;

/**
 * The MCP tools of the export queue (specification EXP-10, EXP-13): jobs added from the open recording's scenes, run
 * one after another in this game or in a hidden background one, listed with their progress, reordered, held, removed.
 */
final class ExportQueueTools {

    private ExportQueueTools() {
    }

    static void register(McpProtocol.Registrar registrar) {
        registrar.add("export_queue_add",
            "Put an export of a scene of the open recording on the export queue. It runs when the queue is started (export_queue_start), after the jobs before it, "
                + "in this game or in a background one, so it can go on overnight. The queue is kept in dailies/export-queue.json and outlives the game.",
            McpSchema.object()
                .integer("scene", "Index of the scene (scene_list). Default: the current scene")
                .string("preset", "The export preset to use (export_presets_list). Default: the first one. The preset is read when the job runs")
                .string("output_path", "The file to write: a name for dailies/exports, or an absolute path. The preset's ending is put on; a name already taken gets a number. "
                    + "Default: the defaultFileName pattern and the scene's name")
                .integer("start_tick", "First tick (default: the scene's export range)")
                .integer("end_tick", "Last tick (default: the scene's export range)")
                .string("caption_files", "Also write what was said as captions beside the video: none (default), srt, vtt or both")
                .bool("caption_speakers", "Begin each caption with the speaker's name (default: the captionSpeakerNames setting)")
                .bool("burn_captions", "Draw what was said into the picture (default false)")
                .string("caption_style", "lines or word_by_word, for captions drawn into the picture (default: the captionStyle setting)"),
            ExportQueueTools::add);

        registrar.add("export_queue_list",
            "The export queue in its order: each job's id, file, recording, scene, preset, state (waiting, paused, running, done, failed, cancelled) and progress, "
                + "and whether it is running in this game or in a background one.",
            McpSchema.object(), McpTool.ThreadMode.HTTP, 30, args -> {
                CoreTools.allowOnly(args);
                return list(QueueRunner.refresh());
            });

        registrar.add("export_queue_remove",
            "Take a job off the export queue, or with ended every job that is done, failed or was cancelled. A running job has to be stopped first (export_queue_stop).",
            McpSchema.object()
                .string("job_id", "The job's id from export_queue_list")
                .bool("ended", "Take off every job that has ended instead"),
            McpTool.ThreadMode.HTTP, 30, ExportQueueTools::remove);

        registrar.add("export_queue_change",
            "Hold a job back or let it go again, or move it to another place in the queue. A failed or cancelled job that is let go again runs from the start.",
            McpSchema.object()
                .string("job_id", "The job's id from export_queue_list")
                .bool("paused", "true holds the job back, false lets it go")
                .integer("position", "Move the job to this place, counted from 0")
                .required("job_id"),
            McpTool.ThreadMode.HTTP, 30, ExportQueueTools::change);

        registrar.add("export_queue_start",
            "Start working through the export queue. With background (the default) a second, hidden game is started with the same game folder, mods and settings; "
                + "it renders the queue and closes, and this game stays playable. When a second game cannot start (memory, or a launcher that forbids it) the queue "
                + "runs in this game instead, and the reply says why. Running in this game opens each job's recording, so it refuses to leave a world unless leave_world is true. "
                + "Poll export_queue_list for progress. A notification on the desktop says when the queue is done.",
            McpSchema.object()
                .bool("background", "Run in a hidden second game (default true). false runs it in this game")
                .bool("leave_world", "Allow leaving the world being played to run the queue in this game (default false)"),
            McpTool.ThreadMode.HTTP, 90, ExportQueueTools::start);

        registrar.add("export_queue_stop",
            "Stop the export queue: the job under way is cancelled (what it wrote is kept as a file that plays) and stays on the queue as cancelled. "
                + "A background game is asked to stop and closes within a few seconds.",
            McpSchema.object(), McpTool.ThreadMode.HTTP, 30, args -> {
                CoreTools.allowOnly(args);
                Minecraft.getInstance().submit(QueueRunner::stop).get();
                JsonObject json = list(QueueRunner.refresh());
                json.addProperty("stopping", true);
                return json;
            });
    }

    private static JsonElement add(McpArgs args) throws Exception {
        CoreTools.allowOnly(args, "scene", "preset", "output_path", "start_tick", "end_tick", "caption_files", "caption_speakers", "burn_captions", "caption_style");
        if (Worker.active()) {
            throw new McpToolException("This is the background export game");
        }
        ExportQueue.Job job;
        try {
            job = QueueRunner.jobForOpen(args.optInteger("scene", -1), args.optString("preset"), args.has("output_path") ? args.string("output_path") : "");
            job.startTick = args.optInteger("start_tick", -1);
            job.endTick = args.optInteger("end_tick", -1);
            if (job.startTick >= 0 && job.endTick >= 0 && job.endTick <= job.startTick) {
                throw new IllegalArgumentException("'end_tick' must be after 'start_tick'");
            }
            String files = args.has("caption_files") ? args.string("caption_files").toLowerCase(Locale.ROOT) : "none";
            Captions.files(files);
            job.captionFiles = files;
            job.captionSpeakers = args.optBool("caption_speakers", job.captionSpeakers);
            job.burnCaptions = args.optBool("burn_captions", false);
            if (args.has("caption_style")) {
                String style = args.string("caption_style").toLowerCase(Locale.ROOT);
                if (!style.equals("lines") && !style.equals("word_by_word")) {
                    throw new IllegalArgumentException("'caption_style' must be lines or word_by_word");
                }
                job.captionStyle = style;
            }
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new McpToolException(e.getMessage());
        }
        ExportQueue.Job added = QueueRunner.add(job);
        JsonObject json = describe(added);
        json.addProperty("position", QueueRunner.view().indexOf(added.id));
        json.addProperty("note", "On the queue. export_queue_start runs it");
        return json;
    }

    private static JsonElement remove(McpArgs args) throws Exception {
        CoreTools.allowOnly(args, "job_id", "ended");
        boolean ended = args.optBool("ended", false);
        if (!ended && !args.has("job_id")) {
            throw new McpToolException("Give job_id, or ended true");
        }
        String id = args.optString("job_id");
        Object result = ExportQueue.update(QueueRunner.file(), queue -> {
            try {
                if (ended) {
                    return queue.clearEnded();
                }
                return queue.remove(id).id;
            } catch (IllegalStateException | IllegalArgumentException e) {
                return e;
            }
        });
        if (result instanceof RuntimeException refused) {
            throw new McpToolException(refused.getMessage() + ". Call export_queue_list for the jobs");
        }
        JsonObject json = list(QueueRunner.refresh());
        if (ended) {
            json.addProperty("removed_count", (Integer) result);
        } else {
            json.addProperty("removed", (String) result);
        }
        return json;
    }

    private static JsonElement change(McpArgs args) throws Exception {
        CoreTools.allowOnly(args, "job_id", "paused", "position");
        String id = args.string("job_id");
        if (!args.has("paused") && !args.has("position")) {
            throw new McpToolException("Give paused, position or both");
        }
        Boolean paused = args.has("paused") ? args.bool("paused") : null;
        Integer position = args.has("position") ? args.integer("position") : null;
        RuntimeException refused = ExportQueue.update(QueueRunner.file(), queue -> {
            try {
                if (paused != null) {
                    queue.setPaused(id, paused);
                }
                if (position != null) {
                    queue.move(id, position);
                }
                return null;
            } catch (IllegalStateException | IllegalArgumentException e) {
                return e;
            }
        });
        if (refused != null) {
            throw new McpToolException(refused.getMessage());
        }
        return list(QueueRunner.refresh());
    }

    private static JsonElement start(McpArgs args) throws Exception {
        CoreTools.allowOnly(args, "background", "leave_world");
        if (Worker.active()) {
            throw new McpToolException("This is the background export game; it works through the queue by itself");
        }
        String said;
        try {
            said = QueueRunner.start(args.optBool("background", true), args.optBool("leave_world", false));
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new McpToolException(e.getMessage());
        } catch (ExecutionException e) {
            throw new McpToolException(e.getCause() == null ? e.toString() : e.getCause().getMessage());
        }
        JsonObject json = list(QueueRunner.refresh());
        json.addProperty("started", said);
        return json;
    }

    // ------------------------------------------------------------------ As JSON

    static JsonObject list(ExportQueue queue) {
        JsonObject json = new JsonObject();
        JsonArray jobs = new JsonArray();
        for (ExportQueue.Job job : queue.jobs()) {
            jobs.add(describe(job));
        }
        json.add("jobs", jobs);
        json.addProperty("waiting", queue.count(ExportQueue.State.WAITING));
        json.addProperty("running_here", QueueRunner.runningHere());
        if (QueueRunner.runningHere() && QueueRunner.hereBecause() != null) {
            json.addProperty("here_because", QueueRunner.hereBecause());
        }
        boolean alive = queue.workerAlive(System.currentTimeMillis(), pid -> ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        json.addProperty("background_running", alive);
        ExportQueue.Worker worker = queue.worker();
        if (worker != null) {
            JsonObject background = new JsonObject();
            background.addProperty("pid", worker.pid);
            background.addProperty("state", alive || worker.finished() ? worker.state : "gone");
            background.addProperty("jobs_done", worker.done);
            background.addProperty("jobs_failed", worker.failed);
            if (worker.note != null) {
                background.addProperty("note", worker.note);
            }
            background.addProperty("log", Worker.log(QueueRunner.file()).toString());
            json.add("background", background);
        }
        if (QueueRunner.lastWords() != null) {
            json.addProperty("last", QueueRunner.lastWords());
        }
        return json;
    }

    private static JsonObject describe(ExportQueue.Job job) {
        JsonObject json = new JsonObject();
        json.addProperty("job_id", job.id);
        json.addProperty("file", job.output);
        json.addProperty("recording", job.recordingName.isEmpty() ? job.recording : job.recordingName);
        json.addProperty("recording_path", job.recording);
        json.addProperty("scene", job.scene);
        if (job.preset != null) {
            json.addProperty("preset", job.preset);
        }
        json.addProperty("settings", job.settings != null ? "as the export tab held them" : "from the preset");
        if (job.startTick >= 0 || job.endTick >= 0) {
            json.addProperty("start_tick", job.startTick);
            json.addProperty("end_tick", job.endTick);
        }
        json.addProperty("state", job.state.word());
        // While this game runs the job, its frames are counted here and not only in the file
        ExportQueue.Job here = QueueRunner.current();
        long done = job.framesDone;
        long total = job.framesTotal;
        if (here != null && here.id.equals(job.id) && Export.active()) {
            done = Export.frame();
            total = Export.totalFrames();
        }
        json.addProperty("frames_done", done);
        json.addProperty("frames_total", total);
        json.addProperty("progress", total <= 0 ? (job.state == ExportQueue.State.DONE ? 1.0 : 0.0) : Math.round(1000.0 * done / total) / 1000.0);
        if (!job.ranIn.isEmpty()) {
            json.addProperty("ran_in", job.ranIn);
        }
        if (!job.captionFiles.equals("none")) {
            json.addProperty("caption_files", job.captionFiles);
        }
        if (job.burnCaptions) {
            json.addProperty("burn_captions", true);
        }
        if (job.written != null) {
            json.addProperty("written", job.written);
        }
        if (job.error != null) {
            json.addProperty("error", job.error);
        }
        if (job.note != null) {
            json.addProperty("note", job.note);
        }
        return json;
    }

}
