package comedymonkey.dailies.export;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Whether several encoders start at once on this card at a size ({@link ParallelVideoSink}), tried away from the
 * game's thread and kept for the session (EXP-6). A trial runs a few encoders for a few frames, which can take up to
 * twenty seconds when a card will not have them, so an export never waits for one: an export whose size has not been
 * tried yet is made by a single encoder, and the size is tried once it is done. The sizes exports are usually made at
 * are tried when the game starts, so most exports find their answer waiting.
 * <p>
 * Trials run one at a time, on a worker of their own, and never while an export is under way: the export's own
 * encoder and a trial's would be counted against the same limit of encoders at once, and a trial could then take the
 * place the export's encoder needs when its first frame comes. A trial that fails while an export started meanwhile
 * says nothing sure about the card, so it is not kept and is tried again later; one that passes is kept.
 * <p>
 * No Minecraft here: the trial, the worker and what says an export is under way are handed in, so the scheduling is
 * tested without a game or an encoder.
 */
final class ParallelTrials {

    /** Runs one trial to its end: whether every encoder started, or null when the trial said nothing about the card */
    interface Trial {
        @Nullable Boolean run(String encoder, int width, int height, int processes);
    }

    /** A trial waiting its turn */
    private record Wanted(String key, String encoder, int width, int height, int processes) {
    }

    private final Trial trial;
    private final Executor worker;
    private final BooleanSupplier exporting;
    private final Map<String, Boolean> known = new ConcurrentHashMap<>();
    /** In the order they were asked for, each once; guarded by this */
    private final Map<String, Wanted> waiting = new LinkedHashMap<>();
    /** Whether the worker has been handed the waiting trials and has not finished with them; guarded by this */
    private boolean draining = false;
    private final AtomicLong exportsStarted = new AtomicLong();

    /**
     * @param exporting whether an export is under way now
     */
    ParallelTrials(Trial trial, Executor worker, BooleanSupplier exporting) {
        this.trial = trial;
        this.worker = worker;
        this.exporting = exporting;
    }

    /** What a trial is known by: the encoder, the size and how many at once, on this graphics card and driver */
    static String key(String machine, String encoder, int width, int height, int processes) {
        return encoder + " " + width + "x" + height + " x" + processes + " on " + machine;
    }

    /** Whether a trial's answer is kept: a pass always is, a failure only when no export began while it ran */
    static boolean keep(boolean passed, boolean exportMeanwhile) {
        return passed || !exportMeanwhile;
    }

    /**
     * The sizes tried when the game starts: those of the export presets first, as they are the ones exports are made
     * at, then the usual ones; each once, only those the encoders can take, and no more than {@code most}
     *
     * @param presets each a width and a height
     */
    static List<int[]> warmSizes(List<int[]> presets, int most) {
        List<int[]> all = new ArrayList<>(presets);
        all.addAll(List.of(new int[]{1920, 1080}, new int[]{3840, 2160}, new int[]{2560, 1440}, new int[]{1080, 1920}, new int[]{1280, 720}));
        List<int[]> sizes = new ArrayList<>();
        for (int[] size : all) {
            if (sizes.size() >= most) {
                break;
            }
            if (size.length == 2 && I420.fits(size[0], size[1]) && sizes.stream().noneMatch(one -> one[0] == size[0] && one[1] == size[1])) {
                sizes.add(new int[]{size[0], size[1]});
            }
        }
        return sizes;
    }

    /**
     * Whether this many encoders start at once at this size, if that has been tried; else null, and the trial is put
     * in line to run once no export is under way ({@link #idle}). Never waits
     */
    @Nullable Boolean answer(String machine, String encoder, int width, int height, int processes) {
        Boolean answer = this.known.get(key(machine, encoder, width, height, processes));
        if (answer == null) {
            this.want(machine, encoder, width, height, processes);
        }
        return answer;
    }

    /** Puts a trial in line, unless it has been answered or is in line already. Never waits */
    void want(String machine, String encoder, int width, int height, int processes) {
        String key = key(machine, encoder, width, height, processes);
        if (this.known.containsKey(key)) {
            return;
        }
        synchronized (this) {
            this.waiting.putIfAbsent(key, new Wanted(key, encoder, width, height, processes));
        }
    }

    /** An export is opening its encoder: a trial running now may be failed by it rather than by the card */
    void exportStarting() {
        this.exportsStarted.incrementAndGet();
    }

    /** No export is under way: the trials in line are handed to the worker, to run one after another. Never waits */
    void idle() {
        synchronized (this) {
            if (this.draining || this.waiting.isEmpty()) {
                return;
            }
            this.draining = true;
        }
        try {
            this.worker.execute(this::drain);
        } catch (RuntimeException refused) {
            synchronized (this) {
                this.draining = false;
            }
        }
    }

    /** How many trials are in line; for the tests */
    synchronized int waiting() {
        return this.waiting.size();
    }

    /** Runs the trials in line, one at a time, until none is left or an export begins. The worker */
    private void drain() {
        try {
            while (true) {
                Wanted next;
                synchronized (this) {
                    if (this.waiting.isEmpty() || this.exporting.getAsBoolean()) {
                        // What is left waits for the next export to end
                        this.draining = false;
                        return;
                    }
                    next = this.waiting.values().iterator().next();
                    this.waiting.remove(next.key());
                }
                long before = this.exportsStarted.get();
                Boolean passed = this.trial.run(next.encoder(), next.width(), next.height(), next.processes());
                if (passed == null) {
                    // Says nothing about the card: asked for again by the next export at this size
                    continue;
                }
                boolean meanwhile = this.exportsStarted.get() != before || this.exporting.getAsBoolean();
                if (keep(passed, meanwhile)) {
                    this.known.putIfAbsent(next.key(), passed);
                } else {
                    synchronized (this) {
                        this.waiting.putIfAbsent(next.key(), next);
                    }
                }
            }
        } catch (Throwable t) {
            synchronized (this) {
                this.draining = false;
            }
        }
    }

}
