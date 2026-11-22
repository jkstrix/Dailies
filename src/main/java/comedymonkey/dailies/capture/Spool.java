package comedymonkey.dailies.capture;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.file.RecordingWriter;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The recording's own thread. The game thread hands it finished pieces of work and carries on; compression and
 * every write to the disk happen here. The amount waiting is limited, so a disk that cannot keep up never eats memory.
 * <p>
 * Closing the game from inside a world ends the recording on the way out and then ends the program at once. So that
 * what is still waiting (the end of the recording, its name, the players) is not lost with it, the program waits for
 * every open writer to get through its work before it ends, for {@link #SHUTDOWN_WAIT_MILLIS} at most.
 */
final class Spool {

    /** How much may wait to be written before capture has to stop and say so */
    static final long LIMIT_BYTES = 192L * 1024 * 1024;
    /** How long the program waits, as it ends, for the writers to get through what is waiting */
    static final long SHUTDOWN_WAIT_MILLIS = 30_000;

    interface Job {
        void run(RecordingWriter writer) throws Exception;

        /**
         * How many bytes the job holds until it has run, for judging how much is waiting. Zero for one that holds next to
         * nothing, such as one that reads a file from the disk a piece at a time
         */
        default long bytes() {
            return 0;
        }

        /** The last job: the thread ends after it. It runs even after an earlier job failed, so the file is closed */
        default boolean last() {
            return false;
        }
    }

    /** Every writer that has not ended yet, for the wait as the program ends */
    private static final Set<Spool> OPEN = ConcurrentHashMap.newKeySet();

    /** Asks the program to wait for the writers as it ends. Made once, the first time a writer starts */
    private static final class ShutdownWait {
        static {
            Runtime.getRuntime().addShutdownHook(new Thread(ShutdownWait::run, "Dailies-Recording-Shutdown"));
        }

        static void ensure() {
            // Loading the class is what adds the hook
        }

        private static void run() {
            long deadline = System.currentTimeMillis() + SHUTDOWN_WAIT_MILLIS;
            for (Spool spool : OPEN) {
                if (!spool.awaitWritten(Math.max(1, deadline - System.currentTimeMillis()))) {
                    Host.LOGGER.warn("Dailies: the game closed before the recording was all written. What was written is kept and can be recovered");
                }
            }
        }
    }

    private final RecordingWriter writer;
    private final Consumer<Throwable> onFailure;
    private final LinkedBlockingQueue<Job> queue = new LinkedBlockingQueue<>();
    private final AtomicLong waiting = new AtomicLong();
    private volatile Throwable failure = null;
    private final Thread thread;
    /** Jobs handed over and not yet done; guarded by this */
    private int unfinished = 0;
    /** Whether the thread has ended; guarded by this */
    private boolean ended = false;

    Spool(RecordingWriter writer) {
        this(writer, t -> Host.LOGGER.error("Dailies: writing the recording failed", t));
    }

    /** @param onFailure told of the first job that fails, on the writer's thread */
    Spool(RecordingWriter writer, Consumer<Throwable> onFailure) {
        this.writer = writer;
        this.onFailure = onFailure;
        this.thread = new Thread(this::work, "Dailies-Recording-Writer");
        // A daemon, so that a writer stuck on a disk that has gone never keeps the game from closing: the shutdown wait,
        // which has a limit, is what lets it finish
        this.thread.setDaemon(true);
        ShutdownWait.ensure();
        OPEN.add(this);
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
        synchronized (this) {
            this.unfinished++;
        }
        this.waiting.addAndGet(job.bytes());
        this.queue.add(job);
    }

    /**
     * Waits until every job handed over so far has been done, or the thread has ended, or the time is up.
     *
     * @return whether nothing is left to do
     */
    boolean awaitWritten(long millis) {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        synchronized (this) {
            while (this.unfinished > 0 && !this.ended) {
                long left = (deadline - System.nanoTime()) / 1_000_000L;
                if (left <= 0) {
                    return false;
                }
                try {
                    this.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    private void work() {
        try {
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
                        try {
                            this.onFailure.accept(t);
                        } catch (Throwable ignored) {
                            // Saying so must never stop the last job from closing the file
                        }
                    }
                } finally {
                    this.waiting.addAndGet(-job.bytes());
                    synchronized (this) {
                        this.unfinished--;
                        this.notifyAll();
                    }
                }
                if (job.last()) {
                    return;
                }
            }
        } finally {
            OPEN.remove(this);
            synchronized (this) {
                this.ended = true;
                this.notifyAll();
            }
        }
    }

}
