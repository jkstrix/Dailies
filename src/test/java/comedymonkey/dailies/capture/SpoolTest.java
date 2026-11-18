package comedymonkey.dailies.capture;

import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.file.RecordingWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The recording's writer thread: order, the last job, what is waiting and the wait as the game closes (code review REC-1) */
class SpoolTest {

    @TempDir
    Path folder;

    private RecordingWriter writer;
    private final List<Throwable> reported = Collections.synchronizedList(new ArrayList<>());

    private Spool spool() throws IOException {
        RecordingManifest manifest = new RecordingManifest();
        manifest.id = "7c4e2a10-3b5d-4f6e-9a8b-1c2d3e4f5a6b";
        manifest.name = "Spool";
        this.writer = RecordingWriter.create(this.folder.resolve("spool.dailies.recording"), manifest);
        return new Spool(this.writer, this.reported::add);
    }

    @AfterEach
    void close() throws IOException {
        if (this.writer != null) {
            this.writer.close();
        }
    }

    private static Spool.Job last(Runnable action) {
        return new Spool.Job() {
            @Override
            public void run(RecordingWriter writer) {
                action.run();
            }

            @Override
            public boolean last() {
                return true;
            }
        };
    }

    private static Spool.Job holding(long bytes, CountDownLatch release) {
        return new Spool.Job() {
            @Override
            public void run(RecordingWriter writer) throws Exception {
                assertTrue(release.await(10, TimeUnit.SECONDS));
            }

            @Override
            public long bytes() {
                return bytes;
            }
        };
    }

    @Test
    void jobsRunInTheOrderTheyWereHandedOver() throws IOException {
        Spool spool = this.spool();
        List<Integer> done = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 500; i++) {
            int number = i;
            spool.add(writer -> done.add(number));
        }
        spool.add(last(() -> done.add(-1)));
        assertTrue(spool.awaitWritten(10_000));
        assertEquals(501, done.size());
        for (int i = 0; i < 500; i++) {
            assertEquals(i, done.get(i));
        }
        assertEquals(-1, done.get(500));
        assertNull(spool.failure());
        assertTrue(this.reported.isEmpty());
    }

    @Test
    void afterAFailureOnlyTheLastJobRuns() throws IOException {
        Spool spool = this.spool();
        List<String> done = Collections.synchronizedList(new ArrayList<>());
        IllegalStateException problem = new IllegalStateException("the disk has gone");
        spool.add(writer -> done.add("before"));
        spool.add(writer -> {
            throw problem;
        });
        spool.add(writer -> done.add("after"));
        spool.add(writer -> {
            throw new IOException("a second failure, not reported");
        });
        spool.add(last(() -> done.add("last")));
        assertTrue(spool.awaitWritten(10_000));
        assertEquals(List.of("before", "last"), done);
        assertSame(problem, spool.failure());
        assertEquals(List.of(problem), this.reported);
    }

    @Test
    void aReportThatFailsStillLetsTheLastJobRun() throws IOException {
        RecordingManifest manifest = new RecordingManifest();
        manifest.id = "0d9c8b7a-6f5e-4d3c-2b1a-0f9e8d7c6b5a";
        manifest.name = "Spool";
        this.writer = RecordingWriter.create(this.folder.resolve("reporting.dailies.recording"), manifest);
        Spool spool = new Spool(this.writer, t -> {
            throw new NoClassDefFoundError("no logger here");
        });
        AtomicReference<String> ran = new AtomicReference<>();
        spool.add(writer -> {
            throw new IOException("failed");
        });
        spool.add(last(() -> ran.set("last")));
        assertTrue(spool.awaitWritten(10_000));
        assertEquals("last", ran.get());
    }

    @Test
    void waitingBytesCountUntilEachJobHasRun() throws IOException {
        Spool spool = this.spool();
        CountDownLatch release = new CountDownLatch(1);
        spool.add(holding(100, release));
        spool.add(holding(50, release));
        // The first is running and the second waits behind it: both still count, until each has run
        assertEquals(150, spool.waitingBytes());
        assertTrue(spool.hasRoom());
        spool.add(holding(Spool.LIMIT_BYTES, release));
        assertFalse(spool.hasRoom());
        assertFalse(spool.awaitWritten(50), "nothing can finish while the first job is held");
        release.countDown();
        assertTrue(spool.awaitWritten(10_000));
        assertEquals(0, spool.waitingBytes());
        assertTrue(spool.hasRoom());
        spool.add(last(() -> {
        }));
        assertTrue(spool.awaitWritten(10_000));
    }

    @Test
    void aFailedJobStillGivesBackItsBytes() throws IOException {
        Spool spool = this.spool();
        spool.add(new Spool.Job() {
            @Override
            public void run(RecordingWriter writer) throws Exception {
                throw new IOException("failed");
            }

            @Override
            public long bytes() {
                return 4096;
            }
        });
        spool.add(new Spool.Job() {
            @Override
            public void run(RecordingWriter writer) {
                // Skipped: an earlier job failed
            }

            @Override
            public long bytes() {
                return 1024;
            }
        });
        assertTrue(spool.awaitWritten(10_000));
        assertEquals(0, spool.waitingBytes());
        spool.add(last(() -> {
        }));
        assertTrue(spool.awaitWritten(10_000));
    }

    @Test
    void theWaitEndsWhenTheThreadDoes() throws IOException, InterruptedException {
        Spool spool = this.spool();
        spool.add(last(() -> {
        }));
        assertTrue(spool.awaitWritten(10_000));
        // Handed over after the last: never run, and waiting for it does not hang
        spool.add(writer -> {
        });
        assertTrue(spool.awaitWritten(10_000));
    }

    @Test
    void theLastJobCanFinishTheFile() throws IOException {
        Spool spool = this.spool();
        Path file = this.writer.file();
        spool.add(writer -> writer.manifest().name = "Finished");
        spool.add(new Spool.Job() {
            @Override
            public void run(RecordingWriter writer) throws Exception {
                writer.finish();
            }

            @Override
            public boolean last() {
                return true;
            }
        });
        assertTrue(spool.awaitWritten(10_000));
        assertNull(spool.failure());
        assertEquals("Finished", comedymonkey.dailies.file.RecordingFile.readManifest(file).name);
        assertTrue(Files.size(file) > 0);
    }

}
