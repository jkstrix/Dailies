package comedymonkey.dailies.export;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When encoders side by side are tried (EXP-6): never on the asking thread, never while an export is under way, one
 * at a time, and a failure only kept when no export may have caused it
 */
class ParallelTrialsTest {

    private static final String CARD = "RTX 5090, 4.6";
    private static final String NVENC = "h264_nvenc";

    /** A worker that runs what it is handed only when told to, so a test can see that nothing ran on the asking thread */
    private static final class Worker implements java.util.concurrent.Executor {
        final List<Runnable> handed = new ArrayList<>();

        @Override
        public void execute(Runnable work) {
            this.handed.add(work);
        }

        void runAll() {
            while (!this.handed.isEmpty()) {
                this.handed.remove(0).run();
            }
        }
    }

    /** Remembers every trial asked of it, and answers as told */
    private static final class Trials implements ParallelTrials.Trial {
        final List<String> tried = new ArrayList<>();
        Boolean answer = true;
        Runnable during = () -> {
        };

        @Override
        public Boolean run(String encoder, int width, int height, int processes) {
            this.tried.add(width + "x" + height);
            this.during.run();
            return this.answer;
        }
    }

    /** A size not tried yet has no answer, and asking for it runs nothing: the export uses one encoder and goes on */
    @Test
    void anUntriedSizeIsNeverWaitedFor() {
        Trials trials = new Trials();
        Worker worker = new Worker();
        ParallelTrials scheduler = new ParallelTrials(trials, worker, () -> false);
        assertNull(scheduler.answer(CARD, NVENC, 3840, 2160, 3));
        assertTrue(trials.tried.isEmpty());
        assertTrue(worker.handed.isEmpty());
        assertEquals(1, scheduler.waiting());
    }

    /** Once no export is under way the size is tried on the worker, and the next export at it has its answer */
    @Test
    void theTrialRunsOnTheWorkerOnceTheExportIsDone() {
        Trials trials = new Trials();
        Worker worker = new Worker();
        AtomicBoolean exporting = new AtomicBoolean(true);
        ParallelTrials scheduler = new ParallelTrials(trials, worker, exporting::get);
        scheduler.exportStarting();
        assertNull(scheduler.answer(CARD, NVENC, 3840, 2160, 3));
        exporting.set(false);
        scheduler.idle();
        // Handed over, not run here
        assertTrue(trials.tried.isEmpty());
        assertEquals(1, worker.handed.size());
        worker.runAll();
        assertEquals(List.of("3840x2160"), trials.tried);
        assertEquals(Boolean.TRUE, scheduler.answer(CARD, NVENC, 3840, 2160, 3));
        assertEquals(0, scheduler.waiting());
    }

    /** No trial runs while an export is under way: what waits is tried after the next export */
    @Test
    void nothingIsTriedWhileAnExportRuns() {
        Trials trials = new Trials();
        AtomicBoolean exporting = new AtomicBoolean(true);
        ParallelTrials scheduler = new ParallelTrials(trials, Runnable::run, exporting::get);
        scheduler.want(CARD, NVENC, 1920, 1080, 3);
        scheduler.idle();
        assertTrue(trials.tried.isEmpty());
        assertEquals(1, scheduler.waiting());
        exporting.set(false);
        scheduler.idle();
        assertEquals(List.of("1920x1080"), trials.tried);
    }

    /** A size asked for again and again is tried once, and an answered one never again */
    @Test
    void eachSizeIsTriedOnce() {
        Trials trials = new Trials();
        Worker worker = new Worker();
        ParallelTrials scheduler = new ParallelTrials(trials, worker, () -> false);
        scheduler.answer(CARD, NVENC, 2560, 1440, 3);
        scheduler.answer(CARD, NVENC, 2560, 1440, 3);
        scheduler.want(CARD, NVENC, 2560, 1440, 3);
        scheduler.idle();
        // Handed to the worker once, however often idle is said
        scheduler.idle();
        assertEquals(1, worker.handed.size());
        worker.runAll();
        assertEquals(List.of("2560x1440"), trials.tried);
        scheduler.want(CARD, NVENC, 2560, 1440, 3);
        scheduler.idle();
        worker.runAll();
        assertEquals(1, trials.tried.size());
        // Another number of encoders, or another card, is another trial
        assertNull(scheduler.answer(CARD, NVENC, 2560, 1440, 2));
        assertNull(scheduler.answer("RTX 4090, 4.6", NVENC, 2560, 1440, 3));
        assertEquals(2, scheduler.waiting());
    }

    /** Trials run one after another, in the order they were asked for */
    @Test
    void trialsRunOneAtATimeInOrder() {
        Trials trials = new Trials();
        Worker worker = new Worker();
        ParallelTrials scheduler = new ParallelTrials(trials, worker, () -> false);
        scheduler.want(CARD, NVENC, 1920, 1080, 3);
        scheduler.want(CARD, NVENC, 3840, 2160, 3);
        scheduler.want(CARD, NVENC, 1280, 720, 3);
        scheduler.idle();
        assertEquals(1, worker.handed.size());
        worker.runAll();
        assertEquals(List.of("1920x1080", "3840x2160", "1280x720"), trials.tried);
    }

    /** A card that will not have them all is remembered, and the export uses one encoder without trying again */
    @Test
    void aFailureWithNoExportMeanwhileIsKept() {
        Trials trials = new Trials();
        trials.answer = false;
        ParallelTrials scheduler = new ParallelTrials(trials, Runnable::run, () -> false);
        scheduler.want(CARD, NVENC, 3840, 2160, 3);
        scheduler.idle();
        assertEquals(Boolean.FALSE, scheduler.answer(CARD, NVENC, 3840, 2160, 3));
        assertEquals(0, scheduler.waiting());
    }

    /** A failure while an export began may be the export's own encoder taking a place: tried again after that export */
    @Test
    void aFailureWhileAnExportBeganIsTriedAgainLater() {
        Trials trials = new Trials();
        trials.answer = false;
        AtomicBoolean exporting = new AtomicBoolean(false);
        Worker worker = new Worker();
        ParallelTrials scheduler = new ParallelTrials(trials, worker, exporting::get);
        trials.during = () -> {
            scheduler.exportStarting();
            exporting.set(true);
        };
        scheduler.want(CARD, NVENC, 3840, 2160, 3);
        scheduler.idle();
        worker.runAll();
        assertEquals(1, trials.tried.size());
        assertNull(scheduler.answer(CARD, NVENC, 3840, 2160, 3));
        assertEquals(1, scheduler.waiting());
        // The export ends; this time the card is left alone, and the answer stands
        exporting.set(false);
        trials.during = () -> {
        };
        trials.answer = true;
        scheduler.idle();
        worker.runAll();
        assertEquals(2, trials.tried.size());
        assertEquals(Boolean.TRUE, scheduler.answer(CARD, NVENC, 3840, 2160, 3));
    }

    /** A pass is kept even when an export began meanwhile: they all started, with the export's own encoder too */
    @Test
    void aPassWhileAnExportBeganIsKept() {
        Trials trials = new Trials();
        AtomicBoolean exporting = new AtomicBoolean(false);
        ParallelTrials scheduler = new ParallelTrials(trials, Runnable::run, exporting::get);
        trials.during = scheduler::exportStarting;
        scheduler.want(CARD, NVENC, 1920, 1080, 3);
        scheduler.idle();
        assertEquals(Boolean.TRUE, scheduler.answer(CARD, NVENC, 1920, 1080, 3));
    }

    /** A trial that says nothing about the card (interrupted, say) is not kept, and is asked for again by the next export */
    @Test
    void aTrialThatSaysNothingIsAskedForAgain() {
        Trials trials = new Trials();
        trials.answer = null;
        ParallelTrials scheduler = new ParallelTrials(trials, Runnable::run, () -> false);
        scheduler.want(CARD, NVENC, 1920, 1080, 3);
        scheduler.idle();
        assertEquals(1, trials.tried.size());
        assertEquals(0, scheduler.waiting());
        assertNull(scheduler.answer(CARD, NVENC, 1920, 1080, 3));
        assertEquals(1, scheduler.waiting());
    }

    /** A worker that will not take the work leaves the trials waiting, and a later idle hands them over again */
    @Test
    void aRefusingWorkerLeavesTheTrialsWaiting() {
        Trials trials = new Trials();
        AtomicBoolean refuse = new AtomicBoolean(true);
        ParallelTrials scheduler = new ParallelTrials(trials, work -> {
            if (refuse.get()) {
                throw new java.util.concurrent.RejectedExecutionException("shut down");
            }
            work.run();
        }, () -> false);
        scheduler.want(CARD, NVENC, 1920, 1080, 3);
        scheduler.idle();
        assertEquals(1, scheduler.waiting());
        refuse.set(false);
        scheduler.idle();
        assertEquals(List.of("1920x1080"), trials.tried);
    }

    @Test
    void onlyAPassedOrUndisturbedTrialIsKept() {
        assertTrue(ParallelTrials.keep(true, false));
        assertTrue(ParallelTrials.keep(true, true));
        assertTrue(ParallelTrials.keep(false, false));
        assertFalse(ParallelTrials.keep(false, true));
    }

    /** The presets' sizes come first, then the usual ones, each once, only sizes the encoders take, and no more than asked */
    @Test
    void warmSizesPutThePresetsFirst() {
        List<int[]> sizes = ParallelTrials.warmSizes(List.of(new int[]{1080, 1920}, new int[]{1920, 1080}, new int[]{1919, 1080}, new int[]{3440, 1440}), 8);
        List<String> named = sizes.stream().map(size -> size[0] + "x" + size[1]).toList();
        assertEquals(List.of("1080x1920", "1920x1080", "3440x1440", "3840x2160", "2560x1440", "1280x720"), named);
        assertEquals(2, ParallelTrials.warmSizes(List.of(), 2).size());
        assertEquals(List.of("1920x1080", "3840x2160"), ParallelTrials.warmSizes(List.of(), 2).stream().map(size -> size[0] + "x" + size[1]).toList());
    }

}
