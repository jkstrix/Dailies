package comedymonkey.dailies.export;

import com.google.gson.JsonObject;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.edit.EditHistory;
import comedymonkey.dailies.edit.Scene;
import comedymonkey.dailies.editor.EditorSession;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Works through the export queue (specification EXP-10) in this game: in the game being played when the queue is
 * started here, or in the hidden worker game (EXP-13), which starts on it by itself. One job after another: open its
 * recording, make its scene current, export through {@link Export} as the editor would, and write how far it has got
 * to the queue file. A point of view job (PLAY-18's batch) has no scene: a plain one stands in for the recording's
 * scenes, and the camera rides along with whoever made the recording, so the stretch is seen as they saw it.
 * <p>
 * The client thread only decides; reading and writing the queue file happens on a thread of its own, since the other
 * game may be holding the file's lock for a moment.
 */
public final class QueueRunner {

    private enum Step {
        IDLE, OPENING, EXPORTING
    }

    /** How long a recording may take to open before its job is given up */
    private static final long OPEN_MILLIS = 5 * 60_000L;
    private static final long PROGRESS_MILLIS = 1000L;
    private static final long HEARTBEAT_MILLIS = 5000L;
    private static final long VIEW_MILLIS = 1000L;

    private static final ExecutorService FILE = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Dailies-Export-Queue");
        thread.setDaemon(true);
        return thread;
    });
    private static final long PID = ProcessHandle.current().pid();
    /** How many times a change to the queue file is tried before it is given up, a moment apart */
    private static final int FILE_TRIES = 4;

    // --- Working through the queue in this game (client thread) ---
    private static volatile boolean running = false;
    private static volatile boolean stopping = false;
    private static volatile @Nullable String hereBecause = null;
    private static Step step = Step.IDLE;
    private static boolean waitingForFile = false;
    private static @Nullable ExportQueue.Job current = null;
    private static long stepSince = 0;
    /** The video encoder being made ready away from this thread while the job's recording opens; null when there is none */
    private static @Nullable CompletableFuture<List<Codecs.Choice>> encoderReady = null;
    private static long progressAt = 0;
    private static long heartbeatAt = 0;
    private static int done = 0;
    private static int failed = 0;
    /**
     * The job this game has claimed in the file and not yet written the end of, or null. Set and cleared only while
     * the file is locked, so a recovery never takes the job under way for one a failure left behind (EXP-5)
     */
    private static volatile @Nullable String workingOn = null;
    /** What the game was showing before the queue took over, to go back to afterwards */
    private static @Nullable Path returnTo = null;
    private static int returnScene = -1;
    /** For a point of view job: the tick its playhead was sent to, or -1 before that; and whether the camera rides with the recorder */
    private static int landingAt = -1;
    private static boolean riding = false;

    // --- What the file says, for the tab and the tools (any thread) ---
    private static volatile ExportQueue view = new ExportQueue();
    private static volatile long viewAt = 0;
    private static volatile boolean viewReading = false;
    private static volatile boolean workerWasAlive = false;
    private static volatile @Nullable String lastWords = null;

    private QueueRunner() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre event) -> {
            try {
                frame();
            } catch (Throwable t) {
                Host.LOGGER.error("Dailies: the export queue failed", t);
                abandon(t);
            }
        });
        // A game that closed in the middle of a job, or a worker that died, left it marked as running, and left the
        // export's temporary files beside its output
        FILE.execute(() -> {
            List<Path> folders = new ArrayList<>();
            try {
                folders.add(Host.get().dataDirectory().resolve("exports"));
                ExportQueue queue = ExportQueue.update(file(), fresh -> {
                    fresh.recover(System.currentTimeMillis(), QueueRunner::alive, PID, workingOn);
                    return fresh;
                });
                for (ExportQueue.Job job : queue.jobs()) {
                    try {
                        Path output = Path.of(job.output);
                        if (output.isAbsolute() && output.getParent() != null) {
                            folders.add(output.getParent());
                        }
                    } catch (RuntimeException unreadable) {
                        // A path this computer cannot read holds nothing of ours
                    }
                }
            } catch (Exception e) {
                Host.LOGGER.warn("Dailies: the export queue could not be checked at start-up", e);
            }
            int removed = TemporaryFiles.sweep(folders, QueueRunner::alive, System.currentTimeMillis());
            if (removed > 0) {
                Host.LOGGER.info("Dailies: took away {} temporary files left by exports that did not finish", removed);
            }
        });
    }

    public static Path file() {
        Path told = Worker.queueFile();
        return told != null ? told : Host.get().dataDirectory().resolve("export-queue.json");
    }

    static boolean alive(long pid) {
        return pid > 0 && (pid == PID || ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    // ------------------------------------------------------------------ What others may ask

    /** The queue as last read from its file, at most a second or two old. Any thread */
    public static ExportQueue view() {
        return view;
    }

    /** Whether this game is working through the queue */
    public static boolean runningHere() {
        return running;
    }

    /** Why the queue runs in this game and not in the background, or null */
    public static @Nullable String hereBecause() {
        return hereBecause;
    }

    /** Whether a worker game is at work, as far as the file last said */
    public static boolean workerAlive() {
        return view.workerAlive(System.currentTimeMillis(), QueueRunner::alive);
    }

    /** What happened last, in words: a worker that could not start, the queue finishing; or null */
    public static @Nullable String lastWords() {
        return lastWords;
    }

    /** The job being exported in this game, or null */
    public static @Nullable ExportQueue.Job current() {
        return running ? current : null;
    }

    /** Reads the file again now, away from the client thread, and waits for it. Not for the client thread */
    public static ExportQueue refresh() throws Exception {
        ExportQueue fresh = ExportQueue.update(file(), queue -> {
            queue.recover(System.currentTimeMillis(), QueueRunner::alive, PID, workingOn);
            return queue;
        });
        view = fresh;
        viewAt = System.currentTimeMillis();
        return fresh;
    }

    // ------------------------------------------------------------------ Starting and stopping

    /**
     * Starts working through the queue in this game. Client thread.
     *
     * @param because why it runs here and not in the background, or null when that was asked for
     */
    public static void startHere(@Nullable String because) {
        if (Worker.active()) {
            throw new IllegalStateException("The background export game works through the queue by itself");
        }
        if (running) {
            throw new IllegalStateException("The queue is already running in this game");
        }
        if (workerAlive()) {
            throw new IllegalStateException("A background game is already working through the queue");
        }
        if (Export.active()) {
            throw new IllegalStateException("An export is running. Wait for it to finish, or cancel it");
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && !Playback.active()) {
            throw new IllegalStateException("You are in a world. The queue opens recordings, so leave the world first");
        }
        returnTo = Playback.loaded() && Playback.file() != null ? Playback.file().file().toAbsolutePath() : null;
        returnScene = returnTo != null && EditorSession.open() ? EditorSession.history().state().currentSceneIndex() : -1;
        hereBecause = because;
        beginRun();
        Host.LOGGER.info("Dailies: working through the export queue in this game{}", because == null ? "" : " (" + because + ")");
    }

    private static void beginRun() {
        running = true;
        stopping = false;
        step = Step.IDLE;
        current = null;
        done = 0;
        failed = 0;
        lastWords = null;
    }

    /**
     * Stops the queue: the job under way is cancelled and kept in the list as cancelled, to be let go again later.
     * In this game at once; a worker is asked through the file and stops within a few seconds. Client thread
     */
    public static void stop() {
        if (running) {
            stopping = true;
            if (Export.active()) {
                Export.cancel();
            }
        }
        FILE.execute(() -> {
            try {
                ExportQueue.update(file(), queue -> {
                    queue.worker(worker -> worker.stopRequested = true);
                    return null;
                });
            } catch (Exception e) {
                Host.LOGGER.warn("Dailies: the background export could not be asked to stop", e);
            }
        });
    }

    /**
     * Starts the queue in a background game, or, when that cannot be done, in this one and says why. Not for the
     * client thread: starting a game takes a few seconds.
     *
     * @param leaveWorld whether running in this game may take the person out of the world they are in
     * @return what happened, in words
     */
    public static String start(boolean background, boolean leaveWorld) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        ExportQueue queue = refresh();
        if (queue.next() == null) {
            throw new IllegalStateException("Nothing on the export queue is waiting. Add a job first, or let a paused one go");
        }
        if (queue.workerAlive(System.currentTimeMillis(), QueueRunner::alive)) {
            return "A background game is already working through the queue";
        }
        if (running) {
            return "The queue is already running in this game";
        }
        // The worker reads scenes from the recordings' files: what was edited a moment ago has to be in them
        minecraft.submit(EditorSession::saveNow).get();
        String because = null;
        if (background) {
            Worker.Started started = Worker.launch(file());
            if (started.started()) {
                ExportQueue.update(file(), fresh -> {
                    fresh.startWorker(started.pid(), System.currentTimeMillis());
                    return null;
                });
                refresh();
                lastWords = started.words();
                return started.words();
            }
            because = started.words();
            Host.LOGGER.warn("Dailies: the background export could not start, so the queue runs in this game: {}", because);
        }
        String reason = because;
        boolean inWorld = minecraft.submit(() -> minecraft.level != null && !Playback.active()).get();
        if (inWorld && !leaveWorld) {
            throw new IllegalStateException((reason == null ? "" : reason + ". ") + "Running the queue in this game opens recordings, which leaves the world you are in. "
                + "Leave it first, or start the queue with leave_world");
        }
        try {
            minecraft.submit(() -> {
                if (minecraft.level != null && !Playback.active()) {
                    // Opening the first job's recording leaves the world, as opening any recording does
                    leaveWorldThenStart(reason);
                } else {
                    startHere(reason);
                }
            }).get();
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
        lastWords = reason == null ? "The queue is running in this game" : "The queue is running in this game, because: " + reason;
        return lastWords;
    }

    private static void leaveWorldThenStart(@Nullable String because) {
        if (running || Export.active()) {
            throw new IllegalStateException(running ? "The queue is already running in this game" : "An export is running");
        }
        hereBecause = because;
        returnTo = null;
        returnScene = -1;
        beginRun();
    }

    // ------------------------------------------------------------------ Each frame

    private static void frame() {
        Minecraft minecraft = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        if (Worker.active()) {
            if (!Worker.setUpOnce(minecraft)) {
                return;
            }
            if (!workerBegun) {
                // The worker starts on the queue by itself, once, and closes when it is done
                workerBegun = true;
                beginRun();
                hereBecause = null;
                announceWorker();
            }
            if (running && now - heartbeatAt > HEARTBEAT_MILLIS) {
                heartbeatAt = now;
                heartbeat();
            }
        }
        if (!Worker.active() && now - viewAt > VIEW_MILLIS && !viewReading) {
            readView();
        }
        if (!running || waitingForFile) {
            return;
        }
        switch (step) {
            case IDLE -> takeNext(minecraft);
            case OPENING -> opening(minecraft, now);
            case EXPORTING -> exporting(minecraft, now);
        }
    }

    private static void readView() {
        viewReading = true;
        FILE.execute(() -> {
            try {
                if (Files.exists(file())) {
                    ExportQueue fresh = ExportQueue.read(file());
                    boolean alive = fresh.workerAlive(System.currentTimeMillis(), QueueRunner::alive);
                    ExportQueue.Worker worker = fresh.worker();
                    if (workerWasAlive && !alive && worker != null && worker.finished()) {
                        // The worker has told the desktop itself; the game says so too
                        String words = worker.note != null ? worker.note : "The background export is done: " + (worker.done - worker.failed) + " written, " + worker.failed + " failed";
                        lastWords = words;
                        Minecraft.getInstance().execute(() -> comedymonkey.dailies.ui.Notices.show(net.minecraft.network.chat.Component.literal(words)));
                    }
                    workerWasAlive = alive;
                    view = fresh;
                }
            } catch (Exception e) {
                Host.LOGGER.debug("Dailies: the export queue could not be read", e);
            } finally {
                viewAt = System.currentTimeMillis();
                viewReading = false;
            }
        });
    }

    /** Runs file work away from the client thread, and what follows back on it */
    private static <T> void withFile(java.util.function.Function<ExportQueue, T> change, java.util.function.Consumer<T> then) {
        waitingForFile = true;
        Minecraft minecraft = Minecraft.getInstance();
        FILE.execute(() -> {
            T result = null;
            Exception problem = null;
            try {
                result = updateWithTries(queue -> {
                    T answer = change.apply(queue);
                    view = queue;
                    return answer;
                });
            } catch (Exception e) {
                problem = e;
                // Whatever this game had claimed is left marked as running; the next recovery marks it failed
                workingOn = null;
            }
            T answer = result;
            Exception failure = problem;
            minecraft.execute(() -> {
                waitingForFile = false;
                if (failure != null) {
                    Host.LOGGER.error("Dailies: the export queue file could not be written", failure);
                    endRun("The export queue file could not be written: " + failure.getMessage());
                    return;
                }
                then.accept(answer);
            });
        });
    }

    /**
     * Changes the queue file, tried again a moment later when the file cannot be written: another program (a virus
     * scanner, a backup) can hold it for a moment. Not for the client thread
     */
    private static <T> T updateWithTries(java.util.function.Function<ExportQueue, T> change) throws Exception {
        IOException last = null;
        for (int attempt = 0; attempt < FILE_TRIES; attempt++) {
            if (attempt > 0) {
                Thread.sleep(250L * attempt);
            }
            try {
                return ExportQueue.update(file(), change);
            } catch (IOException e) {
                last = e;
                Host.LOGGER.warn("Dailies: the export queue file could not be written (try {} of {}): {}", attempt + 1, FILE_TRIES, e.getMessage());
            }
        }
        throw last;
    }

    /**
     * The queue failed in a way nothing else caught. The job under way is marked failed in the file, tried a few
     * times, the export is cancelled and the queue stops, so no job is left marked as running in this game, where it
     * could be neither removed nor run again (EXP-5). Client thread; never throws
     */
    private static void abandon(Throwable problem) {
        String why = "The export queue failed: " + (problem.getMessage() == null ? problem.toString() : problem.getMessage());
        ExportQueue.Job job = current;
        String id = job != null ? job.id : workingOn;
        if (!running && id == null) {
            // Nothing under way to stop or mark; the caller has logged what went wrong
            return;
        }
        try {
            if (Export.active()) {
                Export.cancel();
            }
        } catch (Throwable ignored) {
            // Ended meanwhile
        }
        if (id != null) {
            long now = System.currentTimeMillis();
            FILE.execute(() -> {
                try {
                    updateWithTries(queue -> {
                        ExportQueue.Job there = queue.job(id);
                        // Only one still running: an end written a moment ago stays
                        if (there != null && there.state == ExportQueue.State.RUNNING) {
                            queue.finish(id, ExportQueue.State.FAILED, null, why, null, now);
                        }
                        if (id.equals(workingOn)) {
                            workingOn = null;
                        }
                        view = queue;
                        return null;
                    });
                } catch (Exception e) {
                    workingOn = null;
                    Host.LOGGER.error("Dailies: the failed job could not be marked so in the export queue file", e);
                }
            });
        }
        if (job != null) {
            done++;
            failed++;
        }
        try {
            putBack();
        } catch (Throwable again) {
            Host.LOGGER.error("Dailies: the recording's own scenes could not be put back", again);
        }
        waitingForFile = false;
        // Not back to what was being looked at: an export may still be finishing
        returnTo = null;
        returnScene = -1;
        try {
            endRun(why);
        } catch (Throwable again) {
            Host.LOGGER.error("Dailies: the export queue could not be stopped cleanly", again);
            running = false;
            stopping = false;
            step = Step.IDLE;
            current = null;
            lastWords = why;
        }
    }

    private static void takeNext(Minecraft minecraft) {
        if (stopping) {
            endRun("The queue was stopped");
            return;
        }
        String where = Worker.active() ? "worker" : "game";
        withFile(queue -> {
            if (Worker.active()) {
                ExportQueue.Worker worker = queue.worker();
                if (worker != null && worker.stopRequested) {
                    return null;
                }
            }
            // Nothing is under way here between jobs: a job of this game's still marked as running was left by a failure
            queue.recover(System.currentTimeMillis(), QueueRunner::alive, PID, null);
            ExportQueue.Job next = queue.next();
            if (next == null) {
                return null;
            }
            queue.claim(next, PID, where, System.currentTimeMillis());
            workingOn = next.id;
            return next.copy();
        }, job -> {
            if (job == null) {
                endRun(null);
                return;
            }
            current = job;
            open(minecraft, job);
        });
    }

    private static void open(Minecraft minecraft, ExportQueue.Job job) {
        Path recording;
        try {
            recording = Path.of(job.recording).toAbsolutePath();
        } catch (RuntimeException e) {
            fail(job, "The recording's path cannot be read: " + job.recording);
            return;
        }
        if (!Files.isRegularFile(recording)) {
            fail(job, "The recording is no longer there: " + recording);
            return;
        }
        stepSince = System.currentTimeMillis();
        step = Step.OPENING;
        landingAt = -1;
        // Asked beside the opening of the recording, so the export never waits for it on this thread
        encoderReady = Encoder.bundled() ? Encoder.ready() : null;
        // Already open, unless it is shown as another's point of view in a scene of theirs: the editor then holds theirs
        if (Playback.loaded() && Playback.file() != null && Playback.file().file().toAbsolutePath().normalize().equals(recording.normalize())
            && !comedymonkey.dailies.editor.PointOfView.away()) {
            return;
        }
        if (Worker.active()) {
            minecraft.setScreen(null);
        }
        Playback.open(recording);
    }

    private static void opening(Minecraft minecraft, long now) {
        ExportQueue.Job job = current;
        if (job == null) {
            step = Step.IDLE;
            return;
        }
        if (stopping) {
            finish(job, ExportQueue.State.CANCELLED, null, "The queue was stopped before this job began", null);
            return;
        }
        if (!Playback.active() && Playback.lastError() != null) {
            fail(job, "The recording could not be opened: " + Playback.lastError());
            return;
        }
        if (now - stepSince > OPEN_MILLIS) {
            fail(job, "The recording took too long to open");
            return;
        }
        if (!Playback.loaded() || Playback.jumping() || !EditorSession.open()) {
            return;
        }
        CompletableFuture<List<Codecs.Choice>> encoder = encoderReady;
        if (encoder != null && !encoder.isDone()) {
            return;
        }
        if (job.kind == ExportQueue.Kind.POINT_OF_VIEW && !throughTheirEyes(minecraft, job)) {
            return;
        }
        try {
            Export.Settings settings = prepare(job);
            if (Worker.active()) {
                minecraft.setScreen(null);
                Worker.mute(minecraft, !settings.audio || settings.still);
            }
            Export.start(settings);
            current.written = settings.output.toString();
            step = Step.EXPORTING;
            progressAt = 0;
        } catch (Exception | LinkageError e) {
            Worker.mute(minecraft, true);
            fail(job, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /**
     * Readies a point of view job, a frame at a time: a plain scene stands in for the recording's own, the playhead goes
     * to the stretch's first tick, and once it has landed the camera rides along with the recorder, seeing through
     * their eyes with their HUD, as they saw it while playing. The ride is held by the recorder's UUID, so it carries
     * through the jumps the export makes. A recorder who is not in the world there leaves the camera where the
     * recording put it, and the job says so. Client thread
     *
     * @return whether the job is ready to export
     */
    private static boolean throughTheirEyes(Minecraft minecraft, ExportQueue.Job job) {
        if (landingAt < 0) {
            EditorSession.standIn();
            landingAt = Math.max(0, Math.min(Playback.total(), job.startTick));
            Playback.jumpTo(landingAt, true);
            return false;
        }
        if (Playback.jumping() || Playback.position() != landingAt) {
            return false;
        }
        comedymonkey.dailies.file.RecordingManifest manifest = Playback.manifest();
        net.minecraft.world.entity.player.Player recorder = null;
        try {
            if (manifest != null && minecraft.level != null && manifest.recorderId != null && !manifest.recorderId.isBlank()) {
                recorder = minecraft.level.getPlayerByUUID(java.util.UUID.fromString(manifest.recorderId));
            }
        } catch (IllegalArgumentException unreadable) {
            // A recording without a proper recorder id is filmed from where the camera is
        }
        if (recorder != null && recorder != minecraft.player) {
            comedymonkey.dailies.playback.Viewer.ride(recorder);
            riding = true;
        } else {
            job.note = "The recorder is not in the world at tick " + landingAt + ", so the camera stays where the recording put it";
            Host.LOGGER.warn("Dailies: the queued export '{}': {}", job.title(), job.note);
        }
        return true;
    }

    /** After a point of view job: the recording's own scenes back, and the camera its own again. Client thread */
    private static void putBack() {
        landingAt = -1;
        if (riding) {
            riding = false;
            try {
                comedymonkey.dailies.playback.Viewer.ride(null);
            } catch (RuntimeException e) {
                Host.LOGGER.debug("Dailies: the camera could not leave the recorder", e);
            }
        }
        EditorSession.putBack();
    }

    private static void exporting(Minecraft minecraft, long now) {
        ExportQueue.Job job = current;
        if (job == null) {
            step = Step.IDLE;
            return;
        }
        if (Export.active()) {
            if (stopping) {
                Export.cancel();
            }
            if (now - progressAt > PROGRESS_MILLIS) {
                progressAt = now;
                long frames = Export.frame();
                long total = Export.totalFrames();
                job.framesDone = frames;
                job.framesTotal = total;
                FILE.execute(() -> {
                    try {
                        ExportQueue.update(file(), queue -> {
                            queue.progress(job.id, frames, total);
                            view = queue;
                            return null;
                        });
                    } catch (Exception e) {
                        Host.LOGGER.debug("Dailies: the export's progress could not be written", e);
                    }
                });
            }
            return;
        }
        Worker.mute(minecraft, true);
        String error = Export.lastError();
        Path written = Export.lastOutput();
        // What readying a point of view had to say comes first (see throughTheirEyes)
        String note = joined(job.kind == ExportQueue.Kind.POINT_OF_VIEW ? job.note : null, joined(Export.lastNote(), Export.captionNote()));
        if (error != null) {
            finish(job, ExportQueue.State.FAILED, null, error, note);
        } else if (Export.lastCancelled()) {
            finish(job, ExportQueue.State.CANCELLED, written == null ? null : written.toString(), stopping ? "The queue was stopped" : "The export was cancelled", note);
        } else {
            finish(job, ExportQueue.State.DONE, written == null ? job.written : written.toString(), null, note);
        }
    }

    private static @Nullable String joined(@Nullable String first, @Nullable String second) {
        if (first == null) {
            return second;
        }
        return second == null ? first : first + ". " + second;
    }

    private static void fail(ExportQueue.Job job, String why) {
        Host.LOGGER.warn("Dailies: the queued export '{}' failed: {}", job.title(), why);
        finish(job, ExportQueue.State.FAILED, null, why, null);
    }

    private static void finish(ExportQueue.Job job, ExportQueue.State state, @Nullable String written, @Nullable String error, @Nullable String note) {
        putBack();
        done++;
        if (state != ExportQueue.State.DONE) {
            failed++;
        }
        step = Step.IDLE;
        current = null;
        int doneNow = done;
        int failedNow = failed;
        withFile(queue -> {
            queue.finish(job.id, state, written, error, note, System.currentTimeMillis());
            if (job.id.equals(workingOn)) {
                workingOn = null;
            }
            if (Worker.active()) {
                queue.worker(worker -> {
                    worker.done = doneNow;
                    worker.failed = failedNow;
                    worker.heartbeat = System.currentTimeMillis();
                });
            }
            return null;
        }, nothing -> {
        });
    }

    private static boolean workerBegun = false;

    /** No job is left, or the queue was stopped: say so and put things back */
    private static void endRun(@Nullable String why) {
        boolean worked = done > 0;
        running = false;
        stopping = false;
        step = Step.IDLE;
        current = null;
        String words = why != null ? why : done == 0 ? "Nothing on the export queue was waiting" : "The export queue is done: " + (done - failed) + " written, " + failed + " failed";
        lastWords = words;
        Host.LOGGER.info("Dailies: {}", words);
        if (worked && why == null) {
            Notifier.queueDone(done, failed);
        } else if (!Worker.active()) {
            Minecraft.getInstance().execute(() -> comedymonkey.dailies.ui.Notices.show(net.minecraft.network.chat.Component.literal(words)));
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (Worker.active()) {
            int doneNow = done;
            int failedNow = failed;
            FILE.execute(() -> {
                try {
                    ExportQueue.update(file(), queue -> {
                        queue.worker(worker -> {
                            worker.state = "finished";
                            worker.finishedAt = System.currentTimeMillis();
                            worker.done = doneNow;
                            worker.failed = failedNow;
                            worker.note = words;
                        });
                        return null;
                    });
                } catch (Exception e) {
                    Host.LOGGER.warn("Dailies worker: could not say that it has finished", e);
                }
                // A moment for the notification to be shown before the game goes
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                minecraft.execute(minecraft::stop);
            });
            return;
        }
        // Back to what the person was looking at
        Path back = returnTo;
        int scene = returnScene;
        returnTo = null;
        returnScene = -1;
        if (back != null && Files.isRegularFile(back)) {
            boolean same = Playback.loaded() && Playback.file() != null && Playback.file().file().toAbsolutePath().normalize().equals(back.normalize());
            Runnable reselect = () -> {
                if (scene >= 0 && EditorSession.open() && scene < EditorSession.history().state().scenes().size()) {
                    EditorSession.history().selectScene(scene);
                }
            };
            if (same) {
                reselect.run();
            } else {
                Playback.open(back);
                Playback.whenLoaded(reselect);
            }
        }
    }

    /** The worker says it is alive, and hears whether it is to stop */
    private static void heartbeat() {
        FILE.execute(() -> {
            try {
                boolean stop = ExportQueue.update(file(), queue -> {
                    if (queue.worker() == null || queue.worker().pid != PID) {
                        queue.startWorker(PID, System.currentTimeMillis());
                    }
                    queue.worker(worker -> {
                        worker.heartbeat = System.currentTimeMillis();
                        // Only the worker says it has finished; one the game gave up on while it loaded is at work after all
                        worker.state = "running";
                        worker.note = null;
                        worker.finishedAt = 0;
                    });
                    return queue.worker().stopRequested;
                });
                if (stop) {
                    Minecraft.getInstance().execute(() -> {
                        if (running && !stopping) {
                            Host.LOGGER.info("Dailies worker: asked to stop");
                            stopping = true;
                            if (Export.active()) {
                                Export.cancel();
                            }
                        }
                    });
                }
            } catch (Exception e) {
                Host.LOGGER.warn("Dailies worker: could not report to the queue file", e);
            }
        });
    }

    private static void announceWorker() {
        heartbeatAt = System.currentTimeMillis();
        heartbeat();
    }

    // ------------------------------------------------------------------ Adding

    /**
     * A job for a scene of the open recording, not yet on the queue. Client thread.
     *
     * @param scene  the scene's index, or -1 for the current one
     * @param preset a preset's name, or null for the first
     * @param output a file name for the exports folder, or an absolute path; empty for the default name pattern
     */
    public static ExportQueue.Job jobForOpen(int scene, @Nullable String preset, String output) {
        if (!Playback.loaded() || Playback.file() == null || !EditorSession.open()) {
            throw new IllegalStateException(Playback.active() ? "The recording is still loading" : "No recording is open. Open the recording whose scene is to be exported");
        }
        List<Scene> scenes = EditorSession.history().state().scenes();
        int index = scene < 0 ? EditorSession.history().state().currentSceneIndex() : scene;
        if (index >= scenes.size()) {
            throw new IllegalArgumentException("There is no scene " + scene + ": the recording has " + scenes.size());
        }
        String presetName = presetName(preset);
        comedymonkey.dailies.file.RecordingManifest manifest = Playback.manifest();
        ExportQueue.Job job = new ExportQueue.Job();
        job.recording = Playback.file().file().toAbsolutePath().toString();
        job.recordingId = Playback.recordingId() == null ? "" : Playback.recordingId();
        job.recordingName = manifest == null || manifest.name == null ? "" : manifest.name;
        job.scene = scenes.get(index).name();
        job.sceneIndex = index;
        job.preset = presetName;
        String name = output == null || output.isBlank() ? defaultName(presetName, scenes.get(index).name(), job.recordingName) : output.trim();
        Path path = Path.of(name);
        if (!path.isAbsolute()) {
            // A name only, so nothing in it can lead out of the exports folder
            path = exportsFolder().resolve(NamePattern.safe(path.getFileName().toString()));
        }
        job.output = path.toAbsolutePath().toString();
        job.captionSpeakers = Host.get().setting("captionSpeakerNames", true);
        return job;
    }

    /**
     * A job for a stretch of any recording seen through its recorder's eyes (PLAY-18's batch), not yet on the queue.
     * The recording need not be open. Reads the recording's description: not for the client thread.
     *
     * @param recording the recording's file
     * @param startTick the first tick, in the recording's own ticks
     * @param endTick   the last tick; past the recording's end is its end
     * @param preset    a preset's name, or null for the first
     * @param folder    the folder to write to: absolute, or else inside the exports folder; null or empty for the exports folder
     * @param name      the file's name, its ending put on when it runs; empty for one made of the recorder and the ticks
     */
    public static ExportQueue.Job jobForRange(Path recording, int startTick, int endTick, @Nullable String preset, @Nullable String folder, @Nullable String name)
        throws IOException {
        Path file = recording.toAbsolutePath();
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("There is no recording at " + file);
        }
        if (startTick < 0 || endTick <= startTick) {
            throw new IllegalArgumentException("The stretch must start at tick 0 or later and end after it starts: it was ticks " + startTick + " to " + endTick);
        }
        comedymonkey.dailies.file.RecordingManifest manifest = comedymonkey.dailies.file.RecordingFile.readManifest(file);
        if (manifest.totalTicks > 0 && startTick >= manifest.totalTicks) {
            throw new IllegalArgumentException("The recording is " + manifest.totalTicks + " ticks long, so it holds nothing from tick " + startTick);
        }
        ExportQueue.Job job = new ExportQueue.Job();
        job.kind = ExportQueue.Kind.POINT_OF_VIEW;
        job.recording = file.toString();
        job.recordingId = manifest.id == null ? "" : manifest.id;
        job.recordingName = manifest.name == null ? "" : manifest.name;
        job.recorder = manifest.recorderName == null ? "" : manifest.recorderName;
        job.startTick = startTick;
        job.endTick = manifest.totalTicks > 0 ? Math.min(endTick, manifest.totalTicks) : endTick;
        job.preset = presetName(preset);
        Path place = exportsFolder();
        if (folder != null && !folder.isBlank()) {
            Path given = Path.of(folder.trim());
            place = given.isAbsolute() ? given : place.resolve(given);
        }
        String wanted = name == null || name.isBlank()
            ? "Point of view - " + (job.recorder.isBlank() ? job.recordingName : job.recorder) + " - ticks " + job.startTick + " to " + job.endTick
            : name.trim();
        // A name only, so nothing in it can lead out of the folder
        job.output = place.resolve(NamePattern.safe(Path.of(wanted).getFileName().toString())).toAbsolutePath().toString();
        job.captionSpeakers = Host.get().setting("captionSpeakerNames", true);
        return job;
    }

    /** A preset's name as the presets spell it, or the first preset's when none is given; a name no preset has is refused */
    private static @Nullable String presetName(@Nullable String preset) {
        if (preset == null || preset.isBlank()) {
            List<ExportPreset> all = Export.presets().all();
            return all.isEmpty() ? null : all.get(0).name();
        }
        ExportPreset found = Export.presets().find(preset);
        if (found == null) {
            throw new IllegalArgumentException("There is no export preset called '" + preset + "'. There are: "
                + String.join(", ", Export.presets().all().stream().map(ExportPreset::name).toList()));
        }
        return found.name();
    }

    private static Path exportsFolder() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("dailies").resolve("exports");
    }

    private static String defaultName(@Nullable String preset, String scene, String recording) {
        java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
        values.put("preset", preset == null ? "" : preset);
        values.put("scene", scene);
        values.put("recording", recording);
        return NamePattern.fill(Host.get().setting("defaultFileName", "{date}_{time}") + " {scene}", java.time.LocalDateTime.now(), values);
    }

    /** Puts a job on the queue and says where it went. Any thread; it writes the queue file */
    public static ExportQueue.Job add(ExportQueue.Job job) throws Exception {
        ExportQueue.Job added = ExportQueue.update(file(), queue -> {
            ExportQueue.Job made = queue.add(job, System.currentTimeMillis());
            view = queue;
            return made.copy();
        });
        viewAt = System.currentTimeMillis();
        return added;
    }

    /**
     * Puts several jobs on the queue in one change of its file, so a batch stays together in its order even while the
     * other game adds jobs too, and is never left half added. Any thread; it writes the queue file
     *
     * @return the jobs as they went on, with their ids
     */
    public static List<ExportQueue.Job> addAll(List<ExportQueue.Job> jobs) throws Exception {
        List<ExportQueue.Job> added = ExportQueue.update(file(), queue -> {
            List<ExportQueue.Job> made = queue.addAll(jobs, System.currentTimeMillis()).stream().map(ExportQueue.Job::copy).toList();
            view = queue;
            return made;
        });
        viewAt = System.currentTimeMillis();
        return added;
    }

    // ------------------------------------------------------------------ A job's settings

    /**
     * Makes the job's scene current and works out its export settings: what the tab held when the job was added, or
     * else its preset as it is now, fitted to what this computer can encode. Client thread, with its recording open.
     */
    static Export.Settings prepare(ExportQueue.Job job) throws Exception {
        // A point of view has no scene to choose: the plain one standing in is all there is (see throughTheirEyes)
        if (job.kind == ExportQueue.Kind.SCENE) {
            EditHistory history = EditorSession.history();
            List<Scene> scenes = history.state().scenes();
            int index = -1;
            for (int i = 0; i < scenes.size(); i++) {
                if (scenes.get(i).name().equalsIgnoreCase(job.scene)) {
                    index = i;
                    break;
                }
            }
            if (index < 0 && job.sceneIndex >= 0 && job.sceneIndex < scenes.size() && job.scene.isEmpty()) {
                index = job.sceneIndex;
            }
            if (index < 0) {
                throw new IllegalArgumentException("The recording has no scene called '" + job.scene + "' any more");
            }
            history.selectScene(index);
        }

        Export.Settings settings = new Export.Settings();
        settings.startTick = job.startTick;
        settings.endTick = job.endTick;
        JsonObject given = job.settings;
        if (given != null) {
            fromSnapshot(settings, given);
        } else {
            fromPreset(settings, job.preset);
        }
        boolean pictures = settings.container.equals("png_sequence");
        if (!pictures) {
            settings.width += settings.width & 1;
            settings.height += settings.height & 1;
        }
        boolean[] files = Captions.files(job.captionFiles);
        settings.captionSrt = files[0];
        settings.captionVtt = files[1];
        settings.captionSpeakers = job.captionSpeakers;
        settings.burnCaptions = job.burnCaptions;
        settings.captionStyle = job.captionStyle == null || job.captionStyle.isBlank() ? null : Captions.style(job.captionStyle);
        settings.output = output(job, settings.container);
        return settings;
    }

    private static void fromPreset(Export.Settings settings, @Nullable String name) throws Exception {
        ExportPreset preset = name == null ? null : Export.presets().find(name);
        if (preset == null) {
            List<ExportPreset> all = Export.presets().all();
            if (name != null || all.isEmpty()) {
                throw new IllegalArgumentException("There is no export preset called '" + name + "' any more");
            }
            preset = all.get(0);
        }
        settings.width = preset.width();
        settings.height = preset.height();
        settings.framesPerSecond = preset.framesPerSecond();
        if (!Encoder.bundled()) {
            throw new IllegalStateException(Encoder.noEncoderWords());
        }
        // Worked out while the recording opened (see opening): never waited for on this thread
        List<Codecs.Choice> working = Encoder.choicesNow();
        Codecs.Pick pick = Codecs.pick(preset.codecs(), preset.container(), working);
        if (pick.codec() == null) {
            throw new IllegalStateException(pick.words(working));
        }
        settings.container = pick.container();
        settings.videoCodec = pick.codec();
        settings.note = pick.words(working);
        settings.bitrate = preset.bitrateFor(pick.codec());
        settings.audio = preset.audio();
        settings.stereo = preset.stereo();
        settings.audioCodec = preset.audioCodec();
        settings.stemFiles = preset.audio() && preset.stemFiles();
        settings.stemTracks = preset.audio() && preset.stemTracks();
    }

    /** What the export tab held, as {@link #snapshot} wrote it down */
    private static void fromSnapshot(Export.Settings settings, JsonObject json) {
        settings.width = json.has("width") ? json.get("width").getAsInt() : settings.width;
        settings.height = json.has("height") ? json.get("height").getAsInt() : settings.height;
        settings.framesPerSecond = json.has("framerate") ? json.get("framerate").getAsDouble() : settings.framesPerSecond;
        settings.container = json.has("container") ? json.get("container").getAsString() : settings.container;
        settings.videoCodec = json.has("video_codec") ? json.get("video_codec").getAsString() : settings.videoCodec;
        settings.encoder = json.has("encoder") && !json.get("encoder").getAsString().isEmpty() ? json.get("encoder").getAsString() : null;
        settings.bitrate = json.has("bitrate") ? json.get("bitrate").getAsLong() : settings.bitrate;
        settings.audio = json.has("record_audio") && json.get("record_audio").getAsBoolean();
        settings.stereo = !json.has("stereo_audio") || json.get("stereo_audio").getAsBoolean();
        settings.audioCodec = json.has("audio_codec") ? json.get("audio_codec").getAsString() : settings.audioCodec;
        settings.stemFiles = json.has("separate_files") && json.get("separate_files").getAsBoolean();
        settings.stemTracks = json.has("separate_tracks") && json.get("separate_tracks").getAsBoolean();
        settings.hideGui = json.has("no_gui") && json.get("no_gui").getAsBoolean();
        settings.supersample = json.has("ssaa") && json.get("ssaa").getAsBoolean();
        settings.transparent = json.has("transparent") && json.get("transparent").getAsBoolean();
        settings.note = json.has("note") ? json.get("note").getAsString() : null;
        if (!settings.container.equals("png_sequence") && !Encoder.bundled()) {
            throw new IllegalStateException(Encoder.noEncoderWords());
        }
    }

    /** Export settings written down so a job can be run later exactly as the tab held them */
    public static JsonObject snapshot(Export.Settings settings) {
        JsonObject json = new JsonObject();
        json.addProperty("width", settings.width);
        json.addProperty("height", settings.height);
        json.addProperty("framerate", settings.framesPerSecond);
        json.addProperty("container", settings.container);
        json.addProperty("video_codec", settings.videoCodec);
        json.addProperty("encoder", settings.encoder == null ? "" : settings.encoder);
        json.addProperty("bitrate", settings.bitrate);
        json.addProperty("record_audio", settings.audio);
        json.addProperty("stereo_audio", settings.stereo);
        json.addProperty("audio_codec", settings.audioCodec);
        json.addProperty("separate_files", settings.stemFiles);
        json.addProperty("separate_tracks", settings.stemTracks);
        json.addProperty("no_gui", settings.hideGui);
        json.addProperty("ssaa", settings.supersample);
        json.addProperty("transparent", settings.transparent);
        if (settings.note != null) {
            json.addProperty("note", settings.note);
        }
        return json;
    }

    /** The file a job writes: its ending put right for the kind of file, and a name not yet taken */
    static Path output(ExportQueue.Job job, String container) {
        Path wanted = Path.of(job.output.isBlank() ? "export" : job.output);
        if (!wanted.isAbsolute()) {
            wanted = Minecraft.getInstance().gameDirectory.toPath().resolve("dailies").resolve("exports").resolve(wanted);
        }
        Path folder = wanted.toAbsolutePath().getParent();
        String name = wanted.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot > 0 && Codecs.CONTAINERS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT))) {
            name = name.substring(0, dot);
        }
        String full = container.equals("png_sequence") ? name : name + "." + container;
        try {
            Files.createDirectories(folder);
        } catch (Exception e) {
            throw new IllegalStateException("The folder " + folder + " could not be made: " + e.getMessage());
        }
        return folder.resolve(NamePattern.unused(full, taken -> Files.exists(folder.resolve(taken))));
    }

}
