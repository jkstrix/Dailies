package comedymonkey.dailies.export;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.LongPredicate;

/**
 * The export queue (specification EXP-10): exports waiting to run one after another, kept in
 * {@code dailies/export-queue.json} so that they outlive the game. The same file is how the game and the hidden
 * worker game (EXP-13) talk: the game adds, removes, reorders and pauses jobs, the worker claims them and writes its
 * progress back.
 * <p>
 * Two processes write the file, so every change is made by {@link #update}: it takes a lock on a file beside it,
 * reads the queue afresh, changes it and writes it back in one go, written beside and moved into place. No Minecraft
 * here, so it can be tested without a game.
 */
public final class ExportQueue {

    public enum State {
        WAITING, PAUSED, RUNNING, DONE, FAILED, CANCELLED;

        public String word() {
            return this.name().toLowerCase(Locale.ROOT);
        }

        static State of(String word) {
            try {
                return State.valueOf(word.toUpperCase(Locale.ROOT));
            } catch (RuntimeException e) {
                return WAITING;
            }
        }

        /** Whether a job in this state is over, for good or ill */
        public boolean ended() {
            return this == DONE || this == FAILED || this == CANCELLED;
        }
    }

    /** One export. Plain fields, as the tools and the tab fill them in */
    public static final class Job {
        public String id = "";
        /** The recording, as an absolute path, and its id and name for showing */
        public String recording = "";
        public String recordingId = "";
        public String recordingName = "";
        /** The scene by name; by index only for a job added without a name */
        public String scene = "";
        public int sceneIndex = -1;
        /** -1 for the scene's own export range */
        public int startTick = -1;
        public int endTick = -1;
        /** The preset the job was made with; null when its settings were set by hand */
        public @Nullable String preset = null;
        /** What the export tab held when the job was added, or null to take everything from the preset when it runs */
        public @Nullable JsonObject settings = null;
        /** The file to write: absolute, its ending put right for the kind of file when it runs */
        public String output = "";
        /** none, srt, vtt or both (EXP-11) */
        public String captionFiles = "none";
        public boolean captionSpeakers = true;
        /** Draw what is said into the picture (EXP-15) */
        public boolean burnCaptions = false;
        /** lines or word_by_word; empty for the setting */
        public String captionStyle = "";
        public State state = State.WAITING;
        public long framesDone = 0;
        public long framesTotal = 0;
        public @Nullable String error = null;
        public @Nullable String note = null;
        /** The file the job wrote, once it has */
        public @Nullable String written = null;
        /** Which process is running it, while it runs */
        public long pid = 0;
        /** game or worker */
        public String ranIn = "";
        public long addedAt = 0;
        public long startedAt = 0;
        public long finishedAt = 0;

        /** How far it has got, 0 to 1 */
        public double progress() {
            if (this.state == State.DONE) {
                return 1.0;
            }
            return this.framesTotal <= 0 ? 0.0 : Math.min(1.0, (double) this.framesDone / this.framesTotal);
        }

        /** What a person would call the job: its file's name */
        public String title() {
            String name = Path.of(this.output.isEmpty() ? "export" : this.output).getFileName().toString();
            return name.isEmpty() ? this.id : name;
        }

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("id", this.id);
            json.addProperty("recording", this.recording);
            json.addProperty("recording_id", this.recordingId);
            json.addProperty("recording_name", this.recordingName);
            json.addProperty("scene", this.scene);
            json.addProperty("scene_index", this.sceneIndex);
            json.addProperty("start_tick", this.startTick);
            json.addProperty("end_tick", this.endTick);
            if (this.preset != null) {
                json.addProperty("preset", this.preset);
            }
            if (this.settings != null) {
                json.add("settings", this.settings.deepCopy());
            }
            json.addProperty("output", this.output);
            json.addProperty("caption_files", this.captionFiles);
            json.addProperty("caption_speakers", this.captionSpeakers);
            json.addProperty("burn_captions", this.burnCaptions);
            json.addProperty("caption_style", this.captionStyle);
            json.addProperty("state", this.state.word());
            json.addProperty("frames_done", this.framesDone);
            json.addProperty("frames_total", this.framesTotal);
            if (this.error != null) {
                json.addProperty("error", this.error);
            }
            if (this.note != null) {
                json.addProperty("note", this.note);
            }
            if (this.written != null) {
                json.addProperty("written", this.written);
            }
            json.addProperty("pid", this.pid);
            json.addProperty("ran_in", this.ranIn);
            json.addProperty("added_at", this.addedAt);
            json.addProperty("started_at", this.startedAt);
            json.addProperty("finished_at", this.finishedAt);
            return json;
        }

        public static Job fromJson(JsonObject json) {
            Job job = new Job();
            job.id = text(json, "id", "");
            job.recording = text(json, "recording", "");
            job.recordingId = text(json, "recording_id", "");
            job.recordingName = text(json, "recording_name", "");
            job.scene = text(json, "scene", "");
            job.sceneIndex = (int) number(json, "scene_index", -1);
            job.startTick = (int) number(json, "start_tick", -1);
            job.endTick = (int) number(json, "end_tick", -1);
            job.preset = text(json, "preset", null);
            job.settings = json.has("settings") && json.get("settings").isJsonObject() ? json.getAsJsonObject("settings").deepCopy() : null;
            job.output = text(json, "output", "");
            job.captionFiles = text(json, "caption_files", "none");
            job.captionSpeakers = flag(json, "caption_speakers", true);
            job.burnCaptions = flag(json, "burn_captions", false);
            job.captionStyle = text(json, "caption_style", "");
            job.state = State.of(text(json, "state", "waiting"));
            job.framesDone = number(json, "frames_done", 0);
            job.framesTotal = number(json, "frames_total", 0);
            job.error = text(json, "error", null);
            job.note = text(json, "note", null);
            job.written = text(json, "written", null);
            job.pid = number(json, "pid", 0);
            job.ranIn = text(json, "ran_in", "");
            job.addedAt = number(json, "added_at", 0);
            job.startedAt = number(json, "started_at", 0);
            job.finishedAt = number(json, "finished_at", 0);
            return job;
        }

        public Job copy() {
            return fromJson(this.toJson());
        }
    }

    /**
     * The hidden game working through the queue, as it reports itself.
     *
     * @param state starting, running, finished or failed
     */
    public static final class Worker {
        public long pid = 0;
        public String state = "starting";
        public long startedAt = 0;
        /** When it last said it was alive; a worker that has gone quiet for long is taken for dead */
        public long heartbeat = 0;
        public long finishedAt = 0;
        public @Nullable String note = null;
        /** Set by the game to have the worker stop: it cancels what it is doing and closes */
        public boolean stopRequested = false;
        /** How many jobs it has finished, and how many of those failed */
        public int done = 0;
        public int failed = 0;

        public boolean finished() {
            return this.state.equals("finished") || this.state.equals("failed");
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("pid", this.pid);
            json.addProperty("state", this.state);
            json.addProperty("started_at", this.startedAt);
            json.addProperty("heartbeat", this.heartbeat);
            json.addProperty("finished_at", this.finishedAt);
            if (this.note != null) {
                json.addProperty("note", this.note);
            }
            json.addProperty("stop_requested", this.stopRequested);
            json.addProperty("done", this.done);
            json.addProperty("failed", this.failed);
            return json;
        }

        static Worker fromJson(JsonObject json) {
            Worker worker = new Worker();
            worker.pid = number(json, "pid", 0);
            worker.state = text(json, "state", "starting");
            worker.startedAt = number(json, "started_at", 0);
            worker.heartbeat = number(json, "heartbeat", 0);
            worker.finishedAt = number(json, "finished_at", 0);
            worker.note = text(json, "note", null);
            worker.stopRequested = flag(json, "stop_requested", false);
            worker.done = (int) number(json, "done", 0);
            worker.failed = (int) number(json, "failed", 0);
            return worker;
        }
    }

    /** A worker at work that has said nothing for this long is taken to have died, whatever its process says */
    public static final long WORKER_SILENT_MILLIS = 60_000;
    /** A game with many mods can take minutes to load before the worker says anything at all */
    public static final long WORKER_LOADING_MILLIS = 15 * 60_000;

    private final List<Job> jobs = new ArrayList<>();
    private @Nullable Worker worker = null;
    private long revision = 0;
    private boolean changed = false;

    public ExportQueue() {
    }

    // ------------------------------------------------------------------ Reading

    public List<Job> jobs() {
        return List.copyOf(this.jobs);
    }

    public @Nullable Job job(String id) {
        for (Job job : this.jobs) {
            if (job.id.equals(id)) {
                return job;
            }
        }
        return null;
    }

    public int indexOf(String id) {
        for (int i = 0; i < this.jobs.size(); i++) {
            if (this.jobs.get(i).id.equals(id)) {
                return i;
            }
        }
        return -1;
    }

    public @Nullable Worker worker() {
        return this.worker;
    }

    /** Goes up with every change written, so a reader can tell the queue has changed */
    public long revision() {
        return this.revision;
    }

    /** The job to run next: the first that is waiting */
    public @Nullable Job next() {
        for (Job job : this.jobs) {
            if (job.state == State.WAITING) {
                return job;
            }
        }
        return null;
    }

    public @Nullable Job running() {
        for (Job job : this.jobs) {
            if (job.state == State.RUNNING) {
                return job;
            }
        }
        return null;
    }

    public long count(State state) {
        return this.jobs.stream().filter(job -> job.state == state).count();
    }

    /**
     * Whether the worker is at work now: it has not finished, has spoken lately and its process is alive.
     *
     * @param alive whether a process with this id is running
     */
    public boolean workerAlive(long now, LongPredicate alive) {
        Worker known = this.worker;
        long silence = known == null || known.state.equals("starting") ? WORKER_LOADING_MILLIS : WORKER_SILENT_MILLIS;
        return known != null && !known.finished() && now - Math.max(known.heartbeat, known.startedAt) < silence && alive.test(known.pid);
    }

    // ------------------------------------------------------------------ Changing (inside update)

    private void touched() {
        this.changed = true;
    }

    /** Adds a job at the end, waiting. Gives it an id when it has none; returns it */
    public Job add(Job job, long now) {
        if (job.id.isEmpty() || this.job(job.id) != null) {
            String id;
            do {
                id = String.format(Locale.ROOT, "job-%06x", ThreadLocalRandom.current().nextInt(0x1000000));
            } while (this.job(id) != null);
            job.id = id;
        }
        job.state = State.WAITING;
        job.addedAt = now;
        this.jobs.add(job);
        this.touched();
        return job;
    }

    /** Removes a job. A running one has to be stopped first */
    public Job remove(String id) {
        Job job = this.require(id);
        if (job.state == State.RUNNING) {
            throw new IllegalStateException("The job '" + job.title() + "' is running. Stop the queue first, then remove it");
        }
        this.jobs.remove(job);
        this.touched();
        return job;
    }

    /** Takes away every job that has ended; returns how many went */
    public int clearEnded() {
        int before = this.jobs.size();
        this.jobs.removeIf(job -> job.state.ended());
        if (this.jobs.size() != before) {
            this.touched();
        }
        return before - this.jobs.size();
    }

    /** Moves a job to a place in the list, counted from 0; a place past either end is that end */
    public void move(String id, int place) {
        Job job = this.require(id);
        int from = this.jobs.indexOf(job);
        int to = Math.max(0, Math.min(this.jobs.size() - 1, place));
        if (from == to) {
            return;
        }
        this.jobs.remove(from);
        this.jobs.add(to, job);
        this.touched();
    }

    /**
     * Holds a waiting job back, or lets a held one go again. A job that failed or was cancelled is let go from the
     * start, so it can be tried again.
     */
    public void setPaused(String id, boolean paused) {
        Job job = this.require(id);
        if (paused) {
            if (job.state == State.WAITING) {
                job.state = State.PAUSED;
                this.touched();
            } else if (job.state != State.PAUSED) {
                throw new IllegalStateException("Only a waiting job can be paused; '" + job.title() + "' is " + job.state.word());
            }
            return;
        }
        if (job.state == State.PAUSED || job.state == State.FAILED || job.state == State.CANCELLED) {
            job.state = State.WAITING;
            job.framesDone = 0;
            job.error = null;
            job.finishedAt = 0;
            this.touched();
        } else if (job.state == State.DONE) {
            throw new IllegalStateException("The job '" + job.title() + "' is done. Add it again to export it again");
        }
    }

    /** A job is taken up by a process */
    public void claim(Job job, long pid, String where, long now) {
        job.state = State.RUNNING;
        job.pid = pid;
        job.ranIn = where;
        job.startedAt = now;
        job.framesDone = 0;
        job.framesTotal = 0;
        job.error = null;
        job.note = null;
        this.touched();
    }

    public void progress(String id, long done, long total) {
        Job job = this.job(id);
        if (job != null && (job.framesDone != done || job.framesTotal != total)) {
            job.framesDone = done;
            job.framesTotal = total;
            this.touched();
        }
    }

    /** A job has ended. A job removed while it ran is not brought back */
    public void finish(String id, State state, @Nullable String written, @Nullable String error, @Nullable String note, long now) {
        Job job = this.job(id);
        if (job == null) {
            return;
        }
        job.state = state;
        job.written = written;
        job.error = error;
        job.note = note;
        job.pid = 0;
        job.finishedAt = now;
        if (state == State.DONE && job.framesTotal > 0) {
            job.framesDone = job.framesTotal;
        }
        this.touched();
    }

    /**
     * Puts right what a process that stopped without saying so left behind: a job it was running is marked failed,
     * and a worker that died is marked so.
     *
     * @param alive whether a process with this id is running
     * @return how many jobs were marked failed
     */
    public int recover(long now, LongPredicate alive, long ownPid) {
        int failed = 0;
        for (Job job : this.jobs) {
            if (job.state == State.RUNNING && job.pid != ownPid && !alive.test(job.pid)) {
                job.state = State.FAILED;
                job.error = job.ranIn.equals("worker") ? "The background export stopped before this job was done" : "The game closed before this job was done";
                job.finishedAt = now;
                job.pid = 0;
                failed++;
                this.touched();
            }
        }
        Worker known = this.worker;
        if (known != null && !known.finished() && known.pid != ownPid && !this.workerAlive(now, alive)) {
            known.state = "failed";
            known.note = "The background export stopped without finishing";
            known.finishedAt = now;
            this.touched();
        }
        return failed;
    }

    public Worker startWorker(long pid, long now) {
        Worker started = new Worker();
        started.pid = pid;
        started.startedAt = now;
        started.heartbeat = now;
        this.worker = started;
        this.touched();
        return started;
    }

    /** Changes the worker's own report. Nothing happens when there is none */
    public void worker(java.util.function.Consumer<Worker> change) {
        if (this.worker != null) {
            change.accept(this.worker);
            this.touched();
        }
    }

    public void forgetWorker() {
        if (this.worker != null) {
            this.worker = null;
            this.touched();
        }
    }

    private Job require(String id) {
        Job job = this.job(id);
        if (job == null) {
            throw new IllegalArgumentException("There is no job '" + id + "' on the export queue");
        }
        return job;
    }

    // ------------------------------------------------------------------ As JSON

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("version", 1);
        json.addProperty("revision", this.revision);
        JsonArray list = new JsonArray();
        this.jobs.forEach(job -> list.add(job.toJson()));
        json.add("jobs", list);
        if (this.worker != null) {
            json.add("worker", this.worker.toJson());
        }
        return json;
    }

    public static ExportQueue fromJson(JsonObject json) {
        ExportQueue queue = new ExportQueue();
        queue.revision = number(json, "revision", 0);
        if (json.has("jobs") && json.get("jobs").isJsonArray()) {
            for (JsonElement element : json.getAsJsonArray("jobs")) {
                if (element.isJsonObject()) {
                    try {
                        Job job = Job.fromJson(element.getAsJsonObject());
                        if (!job.id.isEmpty() && queue.job(job.id) == null) {
                            queue.jobs.add(job);
                        }
                    } catch (RuntimeException e) {
                        // One job that cannot be read must not cost the others
                    }
                }
            }
        }
        if (json.has("worker") && json.get("worker").isJsonObject()) {
            queue.worker = Worker.fromJson(json.getAsJsonObject("worker"));
        }
        return queue;
    }

    // ------------------------------------------------------------------ The file

    /** One lock per file inside this process, since a file lock cannot be taken twice by the same one */
    private static final Object IN_PROCESS = new Object();

    /** The queue as it is on disk now; an empty one when there is no file or it cannot be read */
    public static ExportQueue read(Path file) throws IOException {
        return update(file, queue -> queue);
    }

    /**
     * Reads the queue, lets {@code change} look at it or change it, and writes it back when it was changed, all while
     * holding the lock, so the game and the worker never undo each other's changes. Not for the client thread: it
     * waits for the other process when that is writing.
     */
    public static <T> T update(Path file, Function<ExportQueue, T> change) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path lockFile = file.resolveSibling(file.getFileName() + ".lock");
        synchronized (IN_PROCESS) {
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                ExportQueue queue = load(file);
                T result = change.apply(queue);
                if (queue.changed) {
                    queue.revision++;
                    queue.changed = false;
                    save(file, queue);
                }
                return result;
            }
        }
    }

    private static ExportQueue load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return new ExportQueue();
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        try {
            return fromJson(JsonParser.parseString(text).getAsJsonObject());
        } catch (RuntimeException e) {
            // Kept beside for whoever wants to look, and the queue starts again empty rather than never working again
            Files.writeString(file.resolveSibling(file.getFileName() + ".unreadable"), text, StandardCharsets.UTF_8);
            return new ExportQueue();
        }
    }

    private static void save(Path file, ExportQueue queue) throws IOException {
        Path beside = file.resolveSibling(file.getFileName() + ".new");
        Files.writeString(beside, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(queue.toJson()), StandardCharsets.UTF_8);
        try {
            Files.move(beside, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(beside, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ------------------------------------------------------------------ Small helpers

    private static @Nullable String text(JsonObject json, String key, @Nullable String fallback) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsString();
    }

    private static long number(JsonObject json, String key, long fallback) {
        JsonElement value = json.get(key);
        try {
            return value == null || value.isJsonNull() ? fallback : value.getAsLong();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static boolean flag(JsonObject json, String key, boolean fallback) {
        JsonElement value = json.get(key);
        try {
            return value == null || value.isJsonNull() ? fallback : value.getAsBoolean();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

}
