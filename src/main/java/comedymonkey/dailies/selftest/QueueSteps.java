package comedymonkey.dailies.selftest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.editor.EditorSession;
import comedymonkey.dailies.export.CaptionBurner;
import comedymonkey.dailies.export.Encoder;
import comedymonkey.dailies.export.Export;
import comedymonkey.dailies.export.ExportQueue;
import comedymonkey.dailies.export.QueueRunner;
import comedymonkey.dailies.export.WorkerLaunch;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static comedymonkey.dailies.selftest.SelfTest.args;
import static comedymonkey.dailies.selftest.SelfTest.check;

/**
 * The export queue (EXP-10), the background worker as far as a run can see it (EXP-13), Host.queueExport, and the
 * captions of an export (EXP-11, EXP-15). Runs while the recording of the earlier steps is open in playback, after
 * the export presets' steps, and leaves the queue empty and the recording as it found it.
 */
final class QueueSteps {

    private QueueSteps() {
    }

    static void run(SelfTest test) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        Path exports = minecraft.gameDirectory.toPath().resolve("dailies").resolve("exports");
        // A queue left from an earlier run would make the counts below wrong
        ExportQueue.update(QueueRunner.file(), queue -> {
            for (ExportQueue.Job job : queue.jobs()) {
                if (job.state != ExportQueue.State.RUNNING) {
                    queue.remove(job.id);
                }
            }
            queue.forgetWorker();
            return null;
        });

        RecordingSteps.Note middle = RecordingSteps.notes.get(RecordingSteps.notes.size() / 2);
        int from = middle.tick() - 20;
        int to = middle.tick() + 20;
        String recordingBefore = Playback.recordingId();
        int sceneBefore = test.onClient(() -> EditorSession.history().state().currentSceneIndex());

        theList(test, from, to);
        hostAddsAJob(test);
        if (Encoder.bundled()) {
            runInThisGame(test, exports, from, to, recordingBefore, sceneBefore);
        } else {
            test.skip("this build has no video encoder for " + Encoder.platform() + ": the queue was not run (EXP-10)");
        }
        theWorkerCommand(test);
        captionPreview(test);
        theTab(test);

        // Nothing of this run is left on the queue for a person to find
        ExportQueue.update(QueueRunner.file(), queue -> {
            queue.clearEnded();
            for (ExportQueue.Job job : queue.jobs()) {
                if (job.state != ExportQueue.State.RUNNING) {
                    queue.remove(job.id);
                }
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ EXP-10: the list

    /** Jobs are added, listed in order, held back, moved and removed, and the file keeps them */
    private static void theList(SelfTest test, int from, int to) throws Exception {
        JsonObject first = test.tool("export_queue_add", args("preset", "Smallest", "output_path", "self-test queue one", "start_tick", from, "end_tick", to));
        JsonObject second = test.tool("export_queue_add", args("preset", "Smallest", "output_path", "self-test queue two", "start_tick", from, "end_tick", to,
            "caption_files", "both"));
        String one = first.get("job_id").getAsString();
        String two = second.get("job_id").getAsString();
        check(first.get("state").getAsString().equals("waiting"), "a new job is not waiting: " + first);
        check(first.get("file").getAsString().endsWith("self-test queue one"), "the job's file is not the one asked for: " + first);
        check(!test.toolError("export_queue_add", args("preset", "No such preset")).isEmpty(), "a job with a preset that does not exist was taken");
        check(!test.toolError("export_queue_add", args("nonsense", 1)).isEmpty(), "export_queue_add took an argument it does not know");

        JsonObject listed = test.tool("export_queue_list", new JsonObject());
        List<String> order = ids(listed);
        check(order.indexOf(one) >= 0 && order.indexOf(one) < order.indexOf(two), "the queue is not in the order the jobs were added: " + listed);

        test.tool("export_queue_change", args("job_id", two, "position", 0));
        order = ids(test.tool("export_queue_list", new JsonObject()));
        check(order.indexOf(two) < order.indexOf(one), "a job moved to the front is not first");
        JsonObject held = test.tool("export_queue_change", args("job_id", two, "paused", true));
        check(stateOf(held, two).equals("paused"), "a held job is not paused: " + held);
        check(ExportQueue.read(QueueRunner.file()).next() != null && !ExportQueue.read(QueueRunner.file()).next().id.equals(two),
            "the next job to run is one that is held back");
        test.tool("export_queue_change", args("job_id", two, "paused", false));

        // Kept on disk: it outlives the game
        String file = Files.readString(QueueRunner.file(), StandardCharsets.UTF_8);
        check(file.contains(one) && file.contains(two) && file.contains("\"caption_files\": \"both\""), "the queue file does not hold the jobs: " + file);

        test.tool("export_queue_remove", args("job_id", one));
        test.tool("export_queue_remove", args("job_id", two));
        check(ids(test.tool("export_queue_list", new JsonObject())).isEmpty(), "removed jobs are still on the queue");
        check(!test.toolError("export_queue_remove", args("job_id", one)).isEmpty(), "removing a job twice was not refused");
    }

    // ------------------------------------------------------------------ Host.queueExport

    /** The moments page reaches the queue through Host.queueExport (plan 2.2) */
    private static void hostAddsAJob(SelfTest test) throws Exception {
        String id = test.onClient(() -> Host.get().queueExport(EditorSession.history().state().currentSceneIndex(), null, "self-test host"));
        ExportQueue queue = ExportQueue.read(QueueRunner.file());
        ExportQueue.Job job = queue.job(id);
        check(job != null, "Host.queueExport did not put a job on the queue");
        check(job.recordingId.equals(Playback.recordingId()), "the job is not of the open recording: " + job.recordingId);
        check(job.preset != null && !job.preset.isEmpty(), "a job without a preset given did not take the first one");
        check(Path.of(job.output).getParent().endsWith(Path.of("dailies", "exports")), "a job given a name only does not go to the exports folder: " + job.output);
        test.tool("export_queue_remove", args("job_id", id));
    }

    // ------------------------------------------------------------------ EXP-10 run here, EXP-11 files

    /** Two short jobs run one after another in this game, with caption files, and the game is put back as it was */
    private static void runInThisGame(SelfTest test, Path exports, int from, int to, String recordingBefore, int sceneBefore) throws Exception {
        for (String name : List.of("self-test queue a", "self-test queue b")) {
            for (String ending : List.of(".mp4", ".srt", ".vtt", ".webm", ".mkv")) {
                Files.deleteIfExists(exports.resolve(name + ending));
            }
        }
        String a = test.tool("export_queue_add", args("preset", "Smallest", "output_path", "self-test queue a", "start_tick", from, "end_tick", to)).get("job_id").getAsString();
        String b = test.tool("export_queue_add", args("preset", "Smallest", "output_path", "self-test queue b", "start_tick", from, "end_tick", to,
            "caption_files", "both", "burn_captions", true)).get("job_id").getAsString();

        // In a world the queue would have to leave it; here a recording is open, so it runs at once
        JsonObject started = test.tool("export_queue_start", args("background", false));
        check(started.get("running_here").getAsBoolean(), "the queue did not start in this game: " + started);
        test.waitFor(() -> {
            try {
                ExportQueue queue = ExportQueue.read(QueueRunner.file());
                return queue.job(a) != null && queue.job(b) != null && queue.job(a).state.ended() && queue.job(b).state.ended();
            } catch (Exception e) {
                return false;
            }
        }, 600, "the two queued exports to finish");
        test.waitFor(() -> !QueueRunner.runningHere() && !Export.active(), 60, "the queue to stop running");

        JsonObject listed = test.tool("export_queue_list", new JsonObject());
        check(stateOf(listed, a).equals("done"), "the first queued export did not finish: " + listed);
        check(stateOf(listed, b).equals("done"), "the second queued export did not finish: " + listed);
        ExportQueue queue = ExportQueue.read(QueueRunner.file());
        Path videoA = Path.of(queue.job(a).written);
        Path videoB = Path.of(queue.job(b).written);
        check(Files.size(videoA) > 0 && Files.size(videoB) > 0, "a queued export wrote nothing: " + videoA + ", " + videoB);
        check(queue.job(a).startedAt <= queue.job(b).startedAt && queue.job(a).finishedAt <= queue.job(b).startedAt + 1000,
            "the queued exports did not run one after another");
        check(queue.job(b).framesTotal > 0 && queue.job(b).framesDone == queue.job(b).framesTotal, "the queue did not keep the job's progress: "
            + queue.job(b).framesDone + " of " + queue.job(b).framesTotal);

        // EXP-11: the caption files are beside the video and are proper files, empty or not
        String stem = videoB.getFileName().toString().replaceAll("\\.[^.]+$", "");
        Path srt = videoB.resolveSibling(stem + ".srt");
        Path vtt = videoB.resolveSibling(stem + ".vtt");
        test.waitFor(() -> Files.exists(srt) && Files.exists(vtt), 30, "the caption files to be written");
        check(Files.readString(vtt, StandardCharsets.UTF_8).startsWith("WEBVTT"), "the WebVTT file does not begin as one must");
        String srtText = Files.readString(srt, StandardCharsets.UTF_8);
        check(srtText.isEmpty() || srtText.startsWith("1\n"), "the SRT file is not numbered from 1: " + srtText);
        if (srtText.isEmpty()) {
            test.skip("nobody's voice was written down in the scripted run (no transcription server), so the captions were empty and nothing was drawn into the picture (EXP-11, EXP-15)");
        }

        // EXP-7 for the queue: the recording and the scene are as they were
        test.waitFor(() -> Playback.loaded() && !Playback.jumping(), 120, "playback to be put back after the queue");
        check(java.util.Objects.equals(recordingBefore, Playback.recordingId()), "the queue left another recording open");
        int sceneAfter = test.onClient(() -> EditorSession.history().state().currentSceneIndex());
        check(sceneAfter == sceneBefore, "the queue left another scene current: " + sceneAfter + " and not " + sceneBefore);

        // Clearing the finished jobs empties the queue
        JsonObject cleared = test.tool("export_queue_remove", args("ended", true));
        check(cleared.get("removed_count").getAsInt() >= 2 && ids(cleared).isEmpty(), "clearing the finished jobs left some: " + cleared);
        Files.deleteIfExists(videoA);
        Files.deleteIfExists(videoB);
        Files.deleteIfExists(srt);
        Files.deleteIfExists(vtt);
    }

    // ------------------------------------------------------------------ EXP-13

    /**
     * The command a worker would be started with is worked out from this very game: it is this game's Java with the
     * worker property, and it does not join a world. Starting the worker itself opens a second game on the same
     * computer, so it is left to a run that asks for it.
     */
    private static void theWorkerCommand(SelfTest test) throws Exception {
        WorkerLaunch.Plan plan = WorkerLaunch.plan(workerLaunched(), QueueRunner.file().toAbsolutePath().toString(), 640, 360);
        List<String> command = plan.command();
        check(command.contains("-D" + WorkerLaunch.PROPERTY + "=" + QueueRunner.file().toAbsolutePath()), "the worker's command has no worker property: " + command);
        check(command.stream().noneMatch(argument -> argument.startsWith("-Ddailies.test")), "the worker's command would run the self test");
        check(command.stream().noneMatch(argument -> argument.toLowerCase(java.util.Locale.ROOT).startsWith("--quickplay")), "the worker's command would join a world");
        test.log("a worker would be started with " + command.size() + " arguments, found from the " + plan.how());
        if (!Boolean.getBoolean("dailies.test.worker") || !Encoder.bundled()) {
            test.skip("the background worker was not started (a second game on this computer): -PdailiesFlags=dailies.test.worker starts one (EXP-13)");
            return;
        }
        RecordingSteps.Note middle = RecordingSteps.notes.get(RecordingSteps.notes.size() / 2);
        String job = test.tool("export_queue_add", args("preset", "Smallest", "output_path", "self-test worker", "start_tick", middle.tick() - 20, "end_tick", middle.tick() + 20))
            .get("job_id").getAsString();
        JsonObject started = test.tool("export_queue_start", args("background", true));
        check(started.get("background_running").getAsBoolean(), "the background worker did not start: " + started);
        test.waitFor(() -> {
            try {
                ExportQueue queue = ExportQueue.read(QueueRunner.file());
                return queue.job(job) != null && queue.job(job).state.ended() && queue.worker() != null && queue.worker().finished();
            } catch (Exception e) {
                return false;
            }
        }, 900, "the background worker to export its job and close");
        ExportQueue queue = ExportQueue.read(QueueRunner.file());
        check(queue.job(job).state == ExportQueue.State.DONE, "the background worker's job did not finish: " + queue.job(job).error);
        check(queue.job(job).ranIn.equals("worker"), "the job was not run by the worker");
        check(Playback.loaded() && !Export.active(), "this game was disturbed while the worker exported");
        Files.deleteIfExists(Path.of(queue.job(job).written));
    }

    /** The same as Worker.launched, which is not open to the test */
    private static WorkerLaunch.Launched workerLaunched() throws Exception {
        java.lang.reflect.Method launched = Class.forName("comedymonkey.dailies.export.Worker").getDeclaredMethod("launched");
        launched.setAccessible(true);
        return (WorkerLaunch.Launched) launched.invoke(null);
    }

    // ------------------------------------------------------------------ EXP-15 preview

    /** The preview works the open recording's captions out when it is switched on, and only then */
    private static void captionPreview(SelfTest test) throws Exception {
        test.tool("config_set", args("key", "captionPreview", "value", true));
        test.waitFor(CaptionBurner::previewReady, 30, "the caption preview to work out the recording's captions");
        test.tool("config_set", args("key", "captionPreview", "value", false));
        check(!Host.get().setting("captionPreview", true), "the caption preview setting did not go back off");
    }

    // ------------------------------------------------------------------ The tab

    /** "Add to queue" puts what the tab holds on the queue, settings and all */
    private static void theTab(SelfTest test) throws Exception {
        // The tab only offers what the encoder said it can make, and it has to have been asked
        java.lang.reflect.Method describe = Class.forName("comedymonkey.dailies.ui.ExportForms").getDeclaredMethod("describe");
        describe.setAccessible(true);
        test.waitFor(() -> {
            try {
                return test.onClient(() -> describe.invoke(null)) != null;
            } catch (Exception e) {
                return false;
            }
        }, 120, "the export tab to know what this computer can encode");
        if (!Encoder.bundled()) {
            test.skip("without a video encoder the tab's Add to queue was not tried (EXP-10)");
            return;
        }
        int before = ExportQueue.read(QueueRunner.file()).jobs().size();
        test.onClientRun(() -> {
            try {
                java.lang.reflect.Method add = Class.forName("comedymonkey.dailies.ui.ExportForms").getDeclaredMethod("addToQueue");
                add.setAccessible(true);
                add.invoke(null);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
        test.waitFor(() -> {
            try {
                return ExportQueue.read(QueueRunner.file()).jobs().size() == before + 1;
            } catch (Exception e) {
                return false;
            }
        }, 30, "the tab's job to reach the queue");
        List<ExportQueue.Job> jobs = ExportQueue.read(QueueRunner.file()).jobs();
        ExportQueue.Job added = jobs.get(jobs.size() - 1);
        check(added.settings != null && added.settings.has("width") && added.settings.has("container"), "the tab's job does not hold the tab's settings: " + added.toJson());
    }

    // ------------------------------------------------------------------ Small helpers

    private static List<String> ids(JsonObject listed) {
        List<String> ids = new java.util.ArrayList<>();
        JsonArray jobs = listed.getAsJsonArray("jobs");
        for (JsonElement job : jobs) {
            ids.add(job.getAsJsonObject().get("job_id").getAsString());
        }
        return ids;
    }

    private static String stateOf(JsonObject listed, String id) {
        for (JsonElement job : listed.getAsJsonArray("jobs")) {
            if (job.getAsJsonObject().get("job_id").getAsString().equals(id)) {
                return job.getAsJsonObject().get("state").getAsString();
            }
        }
        return "missing";
    }

}
