package comedymonkey.dailies.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import comedymonkey.dailies.file.Spans;
import comedymonkey.dailies.library.Library;
import comedymonkey.dailies.library.LibraryJob;
import comedymonkey.dailies.mcp.McpArgs;
import comedymonkey.dailies.mcp.McpProtocol;
import comedymonkey.dailies.mcp.McpSchema;
import comedymonkey.dailies.mcp.McpTool;
import comedymonkey.dailies.mcp.McpToolException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The MCP tools that make new recordings out of others: combining and trimming (specification FILE-9 and FILE-10).
 * Both start a job and return; replay_job_status says how far it has got.
 */
final class LibraryTools {

    private LibraryTools() {
    }

    static void register(McpProtocol.Registrar registrar) {
        registrar.add("replay_combine",
            "Join recordings, in the order given, into a new recording. The sources are left untouched. Each keeps its own world: at a join the world is rebuilt from the next "
                + "source's opening snapshot, so recordings from different worlds or dimensions combine cleanly. A join is a marker on the timeline and adds no ticks. Recordings made "
                + "with different game versions are refused; different mods are allowed and listed (notes). Moments recorded in the sources still cue correctly in the result. "
                + "Starts a job and returns: poll replay_job_status with job_id, or pass wait_seconds to wait here for it.",
            McpSchema.object()
                .stringArray("sources", "The recordings to join, in order: each a name, a file name, a path or a recording id, as replay_list shows them")
                .string("name", "What to call the new recording")
                .number("wait_seconds", "Wait up to this long for the job to finish before answering (default 0, at most 240)")
                .required("sources", "name"),
            McpTool.ThreadMode.HTTP, 250, LibraryTools::combine);

        registrar.add("replay_trim",
            "Make a new recording from part of one, leaving the source untouched. Give 'keep' (ranges of ticks to keep) or 'cut' (ranges to take out), each a list of [from, to] pairs "
                + "with 'to' not included; 20 ticks to the second. A range that starts where a segment starts is copied. One that starts anywhere else needs the world described at that "
                + "tick, which is done by playing the recording in the game: the job then says through_game, the recording is opened (leaving a world needs leave_world), and whatever "
                + "was open is closed. Markers, the preview, scenes and keyframes come across with their ticks moved; moments still cue correctly, and one in a stretch that was cut "
                + "says so. Starts a job and returns: poll replay_job_status with job_id, or pass wait_seconds.",
            McpSchema.object()
                .string("source", "The recording to trim: a name, a file name, a path or a recording id")
                .any("keep", "Ranges of ticks to keep, eg. [[0, 1200], [4800, 9000]]")
                .any("cut", "Ranges of ticks to take out instead, eg. [[1200, 4800]]")
                .string("name", "What to call the new recording")
                .bool("leave_world", "Allow leaving the world being played right now, when the trim has to go through the game (default false)")
                .number("wait_seconds", "Wait up to this long for the job to finish before answering (default 0, at most 240)")
                .required("source", "name"),
            McpTool.ThreadMode.HTTP, 250, LibraryTools::trim);

        registrar.add("replay_job_status",
            "How a combine or trim is getting on: state (running, done, failed, cancelled), progress from 0 to 1, what it is doing, and once done the new recording's path, id and "
                + "length. Leave job_id out for every job since the game started.",
            McpSchema.object().string("job_id", "The job_id replay_combine or replay_trim returned"),
            McpTool.ThreadMode.HTTP, 30, LibraryTools::status);

        registrar.add("replay_job_cancel",
            "Stop a combine or trim that is running. Nothing is left behind and the sources are untouched.",
            McpSchema.object().string("job_id", "The job_id replay_combine or replay_trim returned").required("job_id"),
            McpTool.ThreadMode.HTTP, 30, args -> {
                CoreTools.allowOnly(args, "job_id");
                LibraryJob job = find(args.string("job_id"));
                job.cancel();
                return describe(job);
            });

        registrar.add("replay_delete",
            "Delete a recording by moving it to the bin, where it stays for binDays days (30 unless changed) and can be restored with replay_bin_restore. Nothing is deleted for good "
                + "until the bin is emptied or the days run out. While it is in the bin its moments are found only in recordings made from it. The open recording cannot be deleted.",
            McpSchema.object().string("recording", "A name, a file name, a path or a recording id, as replay_list shows them").required("recording"),
            McpTool.ThreadMode.HTTP, 60, args -> {
                CoreTools.allowOnly(args, "recording");
                // Only ever a recording in the recordings folder: find refuses any other path, and delete refuses it again
                Path file = recording(args.string("recording"));
                try {
                    return item(Library.delete(file));
                } catch (IllegalArgumentException | IllegalStateException e) {
                    throw new McpToolException(e.getMessage());
                }
            });

        registrar.add("moments_session_delete",
            "Delete a moments session by moving its folder to the bin, where it can be restored with replay_bin_restore until binDays days have passed. The session being written "
                + "now cannot be deleted.",
            McpSchema.object().string("session", "The session id, as moments_sessions_list shows it").required("session"),
            McpTool.ThreadMode.HTTP, 60, args -> {
                CoreTools.allowOnly(args, "session");
                try {
                    return item(Library.binSession(args.string("session")));
                } catch (IllegalArgumentException | IllegalStateException e) {
                    throw new McpToolException(e.getMessage());
                }
            });

        registrar.add("replay_bin_list",
            "What is in the bin: deleted recordings, scenes and moments sessions, the most recently deleted first, each with its id, kind, name, world, size, when it was deleted "
                + "and how many days are left before it is deleted for good.",
            McpSchema.object(), McpTool.ThreadMode.HTTP, 30, args -> {
                CoreTools.allowOnly(args);
                JsonArray items = new JsonArray();
                for (comedymonkey.dailies.library.Bin.Item item : Library.bin().list()) {
                    items.add(item(item));
                }
                JsonObject json = new JsonObject();
                json.add("items", items);
                json.addProperty("bin_days", Library.binDays());
                json.addProperty("size_bytes", Library.bin().size());
                return json;
            });

        registrar.add("replay_bin_restore",
            "Put something back from the bin where it came from: a recording into the recordings folder (under a free name if its old one was taken), a moments session into "
                + "the moments folder, a scene into its recording (into the editor when that recording is open).",
            McpSchema.object().string("id", "The item's id, from replay_bin_list").required("id"),
            McpTool.ThreadMode.HTTP, 60, args -> {
                CoreTools.allowOnly(args, "id");
                try {
                    JsonObject json = new JsonObject();
                    json.addProperty("message", Library.restore(args.string("id")));
                    return json;
                } catch (IllegalArgumentException | IllegalStateException | java.io.IOException e) {
                    throw new McpToolException(e.getMessage());
                }
            });

        registrar.add("replay_bin_empty",
            "Delete everything in the bin for good. This cannot be undone, so it is refused unless 'confirm' is true: ask the person first.",
            McpSchema.object().bool("confirm", "Must be true: the person has agreed that what is in the bin is gone for good").required("confirm"),
            McpTool.ThreadMode.HTTP, 120, args -> {
                CoreTools.allowOnly(args, "confirm");
                if (!args.optBool("confirm", false)) {
                    throw new McpToolException("Emptying the bin deletes everything in it for good. Ask the person, then call again with confirm = true");
                }
                JsonObject json = new JsonObject();
                json.addProperty("deleted", Library.emptyBin());
                return json;
            });

        registrar.add("replay_storage",
            "How much room Dailies takes, per world: recordings, moments audio, exports and the bin, and how much is free on the disk, with what the free space calls for "
                + "(fine, low or full by the diskWarnGigabytes and diskPauseGigabytes settings). Looks at every folder, so it can take a moment on a big library.",
            McpSchema.object(), McpTool.ThreadMode.HTTP, 120, args -> {
                CoreTools.allowOnly(args);
                comedymonkey.dailies.library.StorageReport report = Library.storage();
                JsonObject json = new JsonObject();
                json.addProperty("recordings_bytes", report.recordings);
                json.addProperty("audio_bytes", report.audio);
                json.addProperty("exports_bytes", report.exports);
                json.addProperty("bin_bytes", report.bin);
                json.addProperty("free_bytes", report.free);
                json.addProperty("disk", comedymonkey.dailies.library.DiskGuard.state(report.free).name().toLowerCase(java.util.Locale.ROOT));
                JsonArray worlds = new JsonArray();
                for (comedymonkey.dailies.library.StorageReport.World world : report.byWorld()) {
                    JsonObject one = new JsonObject();
                    one.addProperty("world", world.name);
                    one.addProperty("recordings", world.recordingCount);
                    one.addProperty("recordings_bytes", world.recordings);
                    one.addProperty("audio_bytes", world.audio);
                    one.addProperty("exports_bytes", world.exports);
                    one.addProperty("bin_bytes", world.bin);
                    worlds.add(one);
                }
                json.add("worlds", worlds);
                return json;
            });
    }

    private static JsonObject item(comedymonkey.dailies.library.Bin.Item item) {
        JsonObject json = new JsonObject();
        json.addProperty("id", item.id);
        json.addProperty("kind", item.kind);
        json.addProperty("name", item.name);
        json.addProperty("world", item.world);
        json.addProperty("from", item.from);
        json.addProperty("recording_id", item.recordingId);
        json.addProperty("deleted_at", java.time.Instant.ofEpochMilli(item.binned).toString());
        json.addProperty("size_bytes", item.size);
        json.addProperty("days_left", comedymonkey.dailies.library.Retention.daysLeft(item, Library.binDays(), System.currentTimeMillis()));
        return json;
    }

    /** A recording in the recordings folder; a path to anywhere else, or to a file that is not a recording, is not found */
    private static Path recording(String text) {
        Path file = Library.find(text);
        if (file == null) {
            throw new McpToolException("There is no recording '" + text + "'. Call replay_list for the recordings there are");
        }
        return file;
    }

    private static JsonElement combine(McpArgs args) throws Exception {
        CoreTools.allowOnly(args, "sources", "name", "wait_seconds");
        List<String> wanted = args.optStringList("sources");
        if (wanted == null || wanted.size() < 2) {
            throw new McpToolException("'sources' must list at least two recordings");
        }
        List<Path> files = new ArrayList<>();
        for (String text : wanted) {
            files.add(recording(text));
        }
        LibraryJob job;
        try {
            job = Library.combine(files, args.string("name"), null);
        } catch (IllegalArgumentException e) {
            throw new McpToolException(e.getMessage());
        }
        return waited(job, args);
    }

    private static JsonElement trim(McpArgs args) throws Exception {
        CoreTools.allowOnly(args, "source", "keep", "cut", "name", "leave_world", "wait_seconds");
        Path file = recording(args.string("source"));
        boolean hasKeep = args.has("keep");
        boolean hasCut = args.has("cut");
        if (hasKeep == hasCut) {
            throw new McpToolException("Give exactly one of 'keep' or 'cut': a list of [from, to] pairs of ticks");
        }
        List<Spans.Range> ranges = ranges(hasKeep ? "keep" : "cut", args.element(hasKeep ? "keep" : "cut"));
        List<Spans.Range> keep = ranges;
        if (hasCut) {
            int total;
            try {
                total = comedymonkey.dailies.file.RecordingFile.readManifest(file).totalTicks;
            } catch (java.io.IOException e) {
                throw new McpToolException("The recording cannot be read: " + e.getMessage());
            }
            keep = Spans.invert(ranges, total);
        }
        LibraryJob job;
        try {
            job = Library.trim(file, keep, args.string("name"), args.optBool("leave_world", false), null);
        } catch (IllegalArgumentException e) {
            throw new McpToolException(e.getMessage());
        }
        return waited(job, args);
    }

    private static List<Spans.Range> ranges(String name, JsonElement element) {
        String shape = "'" + name + "' must be a list of [from, to] pairs of ticks, eg. [[0, 1200], [4800, 9000]]";
        if (element == null || !element.isJsonArray()) {
            throw new McpToolException(shape);
        }
        JsonArray array = element.getAsJsonArray();
        // One pair given bare, [from, to], is taken as meant
        if (array.size() == 2 && array.get(0).isJsonPrimitive() && array.get(1).isJsonPrimitive()) {
            JsonArray wrapped = new JsonArray();
            wrapped.add(array);
            array = wrapped;
        }
        List<Spans.Range> ranges = new ArrayList<>();
        for (JsonElement pair : array) {
            try {
                JsonArray both = pair.getAsJsonArray();
                if (both.size() != 2) {
                    throw new McpToolException(shape);
                }
                int from = both.get(0).getAsInt();
                int to = both.get(1).getAsInt();
                if (from < 0 || to <= from) {
                    throw new McpToolException("In '" + name + "', [" + from + ", " + to + "] is not a range: 'from' must be 0 or more and 'to' must be greater than 'from'");
                }
                ranges.add(new Spans.Range(from, to));
            } catch (IllegalStateException | NumberFormatException | UnsupportedOperationException e) {
                throw new McpToolException(shape);
            }
        }
        if (ranges.isEmpty()) {
            throw new McpToolException(shape);
        }
        return ranges;
    }

    private static JsonElement waited(LibraryJob job, McpArgs args) throws InterruptedException {
        double seconds = Math.max(0.0, Math.min(240.0, args.optNumber("wait_seconds", 0.0)));
        if (seconds > 0.0) {
            try {
                job.done().get((long) (seconds * 1000), TimeUnit.MILLISECONDS);
            } catch (ExecutionException | TimeoutException e) {
                // What happened is in the job
            }
        }
        if (job.state() == LibraryJob.State.FAILED) {
            throw new McpToolException(job.error());
        }
        return describe(job);
    }

    private static LibraryJob find(String id) {
        LibraryJob job = Library.job(id);
        if (job == null) {
            throw new McpToolException("There is no job '" + id + "'. Call replay_job_status without a job_id for the jobs there are");
        }
        return job;
    }

    private static JsonElement status(McpArgs args) {
        CoreTools.allowOnly(args, "job_id");
        String id = args.optString("job_id");
        if (id != null && !id.isBlank()) {
            return describe(find(id));
        }
        JsonArray jobs = new JsonArray();
        for (LibraryJob job : Library.jobs()) {
            jobs.add(describe(job));
        }
        JsonObject json = new JsonObject();
        json.add("jobs", jobs);
        return json;
    }

    private static JsonObject describe(LibraryJob job) {
        JsonObject json = new JsonObject();
        json.addProperty("job_id", job.id);
        json.addProperty("kind", job.kind);
        json.addProperty("name", job.name);
        json.addProperty("state", job.state().name().toLowerCase(java.util.Locale.ROOT));
        json.addProperty("progress", Math.round(job.progress() * 1000.0) / 1000.0);
        json.addProperty("stage", job.stage());
        json.addProperty("through_game", job.throughGame());
        if (job.result() != null) {
            json.addProperty("path", job.result().toAbsolutePath().toString());
            json.addProperty("file", job.result().getFileName().toString());
            json.addProperty("recording_id", job.resultId());
            json.addProperty("total_ticks", job.resultTicks());
        }
        if (job.error() != null) {
            json.addProperty("error", job.error());
        }
        JsonArray notes = new JsonArray();
        job.notes().forEach(notes::add);
        json.add("notes", notes);
        return json;
    }

}
