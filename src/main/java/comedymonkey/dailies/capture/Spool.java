package comedymonkey.dailies.capture;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.file.RecordingWriter;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The recording's own thread. The game thread hands it finished pieces of work and carries on; compression and
 * every write to the disk happen here. The amount waiting is limited, so a disk that cannot keep up never eats memory.
 */
final class Spool {

    /** How much may wait to be written before capture has to stop and say so */
    static final long LIMIT_BYTES = 192L * 1024 * 1024;

    interface Job {
        void run(RecordingWriter writer) throws Exception;

        default long bytes() {
            return 0;
        }

        /** The last job: the thread ends after it */
        default boolean last() {
            return false;
        }
    }

    private final RecordingWriter writer;
    private final LinkedBlockingQueue<Job> queue = new LinkedBlockingQueue<>();
    private final AtomicLong waiting = new AtomicLong();
    private volatile Throwable failure = null;
    private final Thread thread;

    Spool(RecordingWriter writer) {
        this.writer = writer;
        this.thread = new Thread(this::work, "Dailies-Recording-Writer");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    /** Whether there is room for more. When there is not, capture pauses rather than queue without limit */
    boolean hasRoom() {
        return this.waiting.get() < LIMIT_BYTES;
    }

    long waitingBytes() {
        return this.waiting.get();
    }

    /** What went wrong on the writer thread, or null */
    Throwable failure() {
        return this.failure;
    }

    void add(Job job) {
        this.waiting.addAndGet(job.bytes());
        this.queue.add(job);
    }

    private void work() {
        while (true) {
            Job job;
            try {
                job = this.queue.take();
            } catch (InterruptedException e) {
                return;
            }
            try {
                if (this.failure == null || job.last()) {
                    job.run(this.writer);
                }
            } catch (Throwable t) {
                if (this.failure == null) {
                    this.failure = t;
                    Host.LOGGER.error("Dailies: writing the recording failed", t);
                }
            } finally {
                this.waiting.addAndGet(-job.bytes());
            }
            if (job.last()) {
                return;
            }
        }
    }

}
