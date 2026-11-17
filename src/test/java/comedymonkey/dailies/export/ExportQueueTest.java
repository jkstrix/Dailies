package comedymonkey.dailies.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
        assertFalse(queue.workerAlive(2 + ExportQueue.WORKER_SILENT_MILLIS + 1, pid -> true), "a worker that has gone quiet is not alive");
        assertEquals(1, queue.recover(10, pid -> false, 1));
        assertEquals(ExportQueue.State.FAILED, a.state);
        assertTrue(a.error.contains("background"));
        assertEquals("failed", queue.worker().state);
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

}
