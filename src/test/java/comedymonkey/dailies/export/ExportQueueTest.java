package comedymonkey.dailies.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The export queue and its file (specification EXP-10, EXP-13) */
class ExportQueueTest {

    private static ExportQueue.Job job(String output) {
        ExportQueue.Job job = new ExportQueue.Job();
        job.recording = "/recordings/a.dailies";
        job.scene = "Scene 1";
        job.preset = "YouTube 1080p60";
        job.output = output;
        return job;
    }

    @Test
    void jobsRunInOrderAndCanBeMovedPausedAndRemoved() {
        ExportQueue queue = new ExportQueue();
        ExportQueue.Job a = queue.add(job("a.mp4"), 1);
        ExportQueue.Job b = queue.add(job("b.mp4"), 2);
        ExportQueue.Job c = queue.add(job("c.mp4"), 3);
        assertNotEquals(a.id, b.id);
        assertEquals(a.id, queue.next().id);

        queue.move(c.id, 0);
        assertEquals(List.of(c.id, a.id, b.id), queue.jobs().stream().map(job -> job.id).toList());
        queue.setPaused(c.id, true);
        assertEquals(a.id, queue.next().id, "a paused job is passed over");
        queue.move(a.id, 99);
        assertEquals(b.id, queue.next().id);
        queue.remove(b.id);
        assertEquals(a.id, queue.next().id);
        queue.setPaused(c.id, false);
        assertEquals(c.id, queue.next().id);
        assertThrows(IllegalArgumentException.class, () -> queue.remove("job-nothing"));
    }

    @Test
    void aRunningJobCannotBeRemovedAndFailedOnesCanBeTriedAgain() {
        ExportQueue queue = new ExportQueue();
        ExportQueue.Job a = queue.add(job("a.mp4"), 1);
        queue.claim(a, 42, "game", 5);
        assertEquals(ExportQueue.State.RUNNING, a.state);
        assertNull(queue.next());
        assertThrows(IllegalStateException.class, () -> queue.remove(a.id));
        queue.progress(a.id, 10, 100);
        assertEquals(0.1, a.progress(), 1e-9);
        queue.finish(a.id, ExportQueue.State.FAILED, null, "the disk is full", null, 9);
        assertEquals("the disk is full", a.error);
        queue.setPaused(a.id, false);
        assertEquals(ExportQueue.State.WAITING, a.state);
        assertNull(a.error);
        assertEquals(0, queue.clearEnded(), "a job let go again has not ended");
        queue.finish(a.id, ExportQueue.State.DONE, "a.mp4", null, null, 12);
        assertEquals(1, queue.clearEnded());
    }

    @Test
    void whatADeadProcessLeftIsMarkedFailed() {
        ExportQueue queue = new ExportQueue();
        ExportQueue.Job a = queue.add(job("a.mp4"), 1);
        queue.claim(a, 77, "worker", 2);
        queue.startWorker(77, 2);
        assertTrue(queue.workerAlive(3, pid -> true));
        assertFalse(queue.workerAlive(3, pid -> false));
        assertTrue(queue.workerAlive(2 + ExportQueue.WORKER_SILENT_MILLIS + 1, pid -> true), "a worker still loading the game is given time");
        queue.worker(worker -> worker.state = "running");
        assertFalse(queue.workerAlive(2 + ExportQueue.WORKER_SILENT_MILLIS + 1, pid -> true), "a worker that has gone quiet is not alive");
        assertEquals(1, queue.recover(10, pid -> false, 1));
        assertEquals(ExportQueue.State.FAILED, a.state);
        assertTrue(a.error.contains("background"));
        assertEquals("failed", queue.worker().state);
    }

    /**
     * A job left running in this very game by a failure (EXP-5): a recovery marks it failed unless it is the job
     * this game is working on, so it can be removed or run again without a restart
     */
    @Test
    void aJobThisGameLeftRunningIsMarkedFailedUnlessItIsUnderWay() {
        ExportQueue queue = new ExportQueue();
        ExportQueue.Job mine = queue.add(job("mine.mp4"), 1);
        ExportQueue.Job other = queue.add(job("other.mp4"), 2);
        queue.claim(mine, 42, "game", 3);
        queue.claim(other, 77, "worker", 3);

        assertEquals(0, queue.recover(4, pid -> true, 42, mine.id), "the job under way here stays running");
        assertEquals(ExportQueue.State.RUNNING, mine.state);
        assertEquals(ExportQueue.State.RUNNING, other.state);

        assertEquals(1, queue.recover(5, pid -> true, 42, null), "with nothing under way here, this game's running job was left by a failure");
        assertEquals(ExportQueue.State.FAILED, mine.state);
        assertEquals(0, mine.pid);
        assertEquals(5, mine.finishedAt);
        assertTrue(mine.error.contains("this game"), mine.error);
        assertEquals(ExportQueue.State.RUNNING, other.state, "a living process's job is left to it");

        // Once failed it can be let go again, or removed
        queue.setPaused(mine.id, false);
        assertEquals(ExportQueue.State.WAITING, mine.state);
        queue.remove(mine.id);
    }

    @Test
    void anotherJobOfThisGameIsNotSparedByTheOneUnderWay() {
        ExportQueue queue = new ExportQueue();
        ExportQueue.Job left = queue.add(job("left.mp4"), 1);
        ExportQueue.Job now = queue.add(job("now.mp4"), 2);
        queue.claim(left, 42, "game", 3);
        queue.claim(now, 42, "game", 4);
        assertEquals(1, queue.recover(5, pid -> true, 42, now.id));
        assertEquals(ExportQueue.State.FAILED, left.state);
        assertEquals(ExportQueue.State.RUNNING, now.state);
    }

    @Test
    void theFileKeepsEverything(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("export-queue.json");
        String id = ExportQueue.update(file, queue -> {
            ExportQueue.Job made = job("/out/clip.mp4");
            made.captionFiles = "both";
            made.burnCaptions = true;
            made.startTick = 100;
            made.endTick = 400;
            return queue.add(made, 12).id;
        });
        ExportQueue read = ExportQueue.read(file);
        ExportQueue.Job job = read.job(id);
        assertEquals("/out/clip.mp4", job.output);
        assertEquals("both", job.captionFiles);
        assertTrue(job.burnCaptions);
        assertEquals(100, job.startTick);
        assertEquals("YouTube 1080p60", job.preset);
        assertEquals(1, read.revision());
        // Reading changes nothing, so the revision stays
        ExportQueue.read(file);
        assertEquals(1, ExportQueue.read(file).revision());
    }

    @Test
    void anUnreadableFileIsSetAsideAndTheQueueStartsAgain(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("export-queue.json");
        Files.writeString(file, "{ not json");
        assertTrue(ExportQueue.read(file).jobs().isEmpty());
        assertTrue(Files.exists(folder.resolve("export-queue.json.unreadable")));
    }

    /** A file that is not UTF-8 at all (L-11) is set aside like any unreadable one, not left to break the queue for good */
    @Test
    void aFileInTheWrongEncodingIsSetAsideAndTheQueueStartsAgain(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("export-queue.json");
        // "{\"jobs\": [" then bytes that are no UTF-8 at all, as a file saved in another encoding would hold
        byte[] bytes = {'{', '"', 'j', 'o', 'b', 's', '"', ':', ' ', '[', (byte) 0xFF, (byte) 0xFE, (byte) 0xC3, (byte) 0x28, ']', '}'};
        Files.write(file, bytes);
        assertTrue(ExportQueue.read(file).jobs().isEmpty());
        Path unreadable = folder.resolve("export-queue.json.unreadable");
        assertTrue(Files.exists(unreadable));
        assertArrayEquals(bytes, Files.readAllBytes(unreadable), "set aside byte for byte");
        // And the queue works again: a change writes a good file over the bad one
        String id = ExportQueue.update(file, queue -> queue.add(job("again.mp4"), 1).id);
        assertEquals("again.mp4", ExportQueue.read(file).job(id).output);
    }

    @Test
    void changesFromManyThreadsAreNotLost(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("export-queue.json");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<String>> added = new java.util.ArrayList<>();
            for (int i = 0; i < 20; i++) {
                int number = i;
                added.add(pool.submit(() -> ExportQueue.update(file, queue -> queue.add(job(number + ".mp4"), number).id)));
            }
            for (Future<String> future : added) {
                future.get();
            }
        } finally {
            pool.shutdown();
        }
        assertEquals(20, ExportQueue.read(file).jobs().size());
    }

    private static ExportQueue.Job pointOfView(String recording, int start, int end, String output) {
        ExportQueue.Job job = new ExportQueue.Job();
        job.kind = ExportQueue.Kind.POINT_OF_VIEW;
        job.recording = recording;
        job.recorder = "Alex";
        job.startTick = start;
        job.endTick = end;
        job.preset = "YouTube 1080p60";
        job.output = output;
        return job;
    }

    @Test
    void aPointOfViewJobKeepsItsRecordingStretchAndRecorderInTheFile(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("export-queue.json");
        String id = ExportQueue.update(file, queue -> queue.add(pointOfView("/recordings/alex.dailies", 120, 480, "/out/Point of view - Alex"), 3).id);
        ExportQueue.Job job = ExportQueue.read(file).job(id);
        assertEquals(ExportQueue.Kind.POINT_OF_VIEW, job.kind);
        assertEquals("/recordings/alex.dailies", job.recording);
        assertEquals("Alex", job.recorder);
        assertEquals(120, job.startTick);
        assertEquals(480, job.endTick);
        assertEquals("", job.scene, "a point of view has no scene");
        assertTrue(Files.readString(file).contains("\"kind\": \"point_of_view\""));
    }

    @Test
    void aJobWithoutAKindIsASceneJob() {
        com.google.gson.JsonObject json = job("a.mp4").toJson();
        json.remove("kind");
        assertEquals(ExportQueue.Kind.SCENE, ExportQueue.Job.fromJson(json).kind);
        json.addProperty("kind", "something else");
        assertEquals(ExportQueue.Kind.SCENE, ExportQueue.Job.fromJson(json).kind);
        assertEquals("scene", job("a.mp4").toJson().get("kind").getAsString());
    }

    @Test
    void aPointOfViewJobWithoutARecordingOrAStretchIsRefused() {
        ExportQueue queue = new ExportQueue();
        assertThrows(IllegalArgumentException.class, () -> queue.add(pointOfView("", 0, 100, "a"), 1), "no recording");
        assertThrows(IllegalArgumentException.class, () -> queue.add(pointOfView("/r.dailies", -1, -1, "a"), 1), "no stretch");
        assertThrows(IllegalArgumentException.class, () -> queue.add(pointOfView("/r.dailies", 100, 100, "a"), 1), "an empty stretch");
        assertThrows(IllegalArgumentException.class, () -> queue.add(pointOfView("/r.dailies", 200, 100, "a"), 1), "a stretch backwards");
        assertTrue(queue.jobs().isEmpty());
        // A scene job may leave its range to the scene
        queue.add(job("scene.mp4"), 1);
        assertEquals(1, queue.jobs().size());
    }

    @Test
    void aBatchGoesOnTogetherInItsOrderOrNotAtAll(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("export-queue.json");
        String before = ExportQueue.update(file, queue -> queue.add(job("before.mp4"), 1).id);
        List<String> batch = ExportQueue.update(file, queue -> queue.addAll(List.of(
            pointOfView("/recordings/mine.dailies", 20, 80, "mine"),
            pointOfView("/recordings/alex.dailies", 1020, 1080, "alex")), 2).stream().map(job -> job.id).toList());
        ExportQueue read = ExportQueue.read(file);
        assertEquals(List.of(before, batch.get(0), batch.get(1)), read.jobs().stream().map(job -> job.id).toList());
        assertEquals(before, read.next().id, "the batch waits behind the jobs before it");
        assertEquals(ExportQueue.State.WAITING, read.job(batch.get(1)).state);

        // One job of the batch refused: none of it goes on, and the file is not changed
        long revision = read.revision();
        assertThrows(IllegalArgumentException.class, () -> ExportQueue.update(file, queue -> queue.addAll(List.of(
            pointOfView("/recordings/mine.dailies", 20, 80, "mine again"),
            pointOfView("/recordings/alex.dailies", 80, 20, "alex backwards")), 3)));
        ExportQueue after = ExportQueue.read(file);
        assertEquals(3, after.jobs().size());
        assertEquals(revision, after.revision());
    }

}
