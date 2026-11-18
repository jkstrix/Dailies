package comedymonkey.dailies.mcp;

import org.jetbrains.annotations.Nullable;

import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Shares the MCP server's few worker threads out between the addresses that connect. One address may hold only so
 * many of them at once, so a client that is slow on purpose, or a page that asks for a lot at the same time, always
 * leaves a worker for everyone else. A connection over its address's share is not turned away: it waits, in the
 * order it came, until one of that address's own connections is finished. Only when too many are waiting is the next
 * one closed.
 */
final class ClientShare {

    /** A connection to serve, and what to do with it when it cannot be */
    private record Job(Runnable work, Runnable turnAway) {
    }

    private static final class Share {
        int running = 0;
        final ArrayDeque<Job> waiting = new ArrayDeque<>();
    }

    private final int perAddress;
    private final int waitingPerAddress;
    private final Executor workers;
    private final Map<InetAddress, Share> shares = new HashMap<>();

    /**
     * @param perAddress        how many connections of one address may be with the workers at once
     * @param waitingPerAddress how many more of its connections may wait for their turn
     * @param workers           the threads that serve connections
     */
    ClientShare(int perAddress, int waitingPerAddress, Executor workers) {
        this.perAddress = perAddress;
        this.waitingPerAddress = waitingPerAddress;
        this.workers = workers;
    }

    /**
     * Serves a connection now, or once its address has a worker free.
     *
     * @param turnAway run instead of the work when the workers will not take it after all (too busy, or stopping)
     * @return false when the address already has as many connections waiting as it may; nothing is run then, and the
     *     caller closes the connection
     */
    boolean submit(InetAddress address, Runnable work, Runnable turnAway) {
        Job job = new Job(work, turnAway);
        synchronized (this) {
            Share share = this.shares.computeIfAbsent(address, key -> new Share());
            if (share.running >= this.perAddress) {
                if (share.waiting.size() >= this.waitingPerAddress) {
                    return false;
                }
                share.waiting.addLast(job);
                return true;
            }
            share.running += 1;
        }
        this.launch(address, job);
        return true;
    }

    /** How many connections of an address are with the workers */
    synchronized int running(InetAddress address) {
        Share share = this.shares.get(address);
        return share == null ? 0 : share.running;
    }

    /** How many connections of an address wait for a worker */
    synchronized int waiting(InetAddress address) {
        Share share = this.shares.get(address);
        return share == null ? 0 : share.waiting.size();
    }

    /** Hands a job that already counts as running to the workers, and the next ones after it if they refuse */
    private void launch(InetAddress address, Job job) {
        Job current = job;
        while (current != null) {
            Job running = current;
            try {
                this.workers.execute(() -> {
                    try {
                        running.work().run();
                    } finally {
                        this.finished(address);
                    }
                });
                return;
            } catch (RuntimeException refused) { // RejectedExecutionException: too busy or shutting down
                running.turnAway().run();
                current = this.next(address);
            }
        }
    }

    private void finished(InetAddress address) {
        Job next = this.next(address);
        if (next != null) {
            this.launch(address, next);
        }
    }

    /** The address's next waiting job, which takes the place of the one that finished; null when one fewer is running */
    private synchronized @Nullable Job next(InetAddress address) {
        Share share = this.shares.get(address);
        if (share == null) {
            return null;
        }
        Job next = share.waiting.pollFirst();
        if (next == null) {
            share.running -= 1;
            if (share.running <= 0) {
                this.shares.remove(address);
            }
        }
        return next;
    }

}
