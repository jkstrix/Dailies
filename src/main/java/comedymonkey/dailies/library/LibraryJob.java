package comedymonkey.dailies.library;

import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A combine or a trim under way, or finished: how far it has got, what it is doing, and what came of it. Everything
 * can be read from any thread. A screen or a tool shows it by asking, or by listening for changes.
 */
public final class LibraryJob {

    public enum State {
        RUNNING, DONE, FAILED, CANCELLED
    }

    public final String id;
    /** "combine" or "trim" */
    public final String kind;
    /** What the new recording is to be called */
    public final String name;

    private volatile State state = State.RUNNING;
    private volatile double progress = 0.0;
    private volatile String stage = "Starting";
    private volatile boolean throughGame = false;
    private volatile Path result = null;
    private volatile String resultId = null;
    private volatile int resultTicks = 0;
    private volatile String error = null;
    private volatile boolean cancelAsked = false;
    private final List<String> notes = new CopyOnWriteArrayList<>();
    private final List<Consumer<LibraryJob>> listeners = new CopyOnWriteArrayList<>();
    private final CompletableFuture<Path> done = new CompletableFuture<>();

    LibraryJob(String id, String kind, String name) {
        this.id = id;
        this.kind = kind;
        this.name = name;
    }

    public State state() {
        return this.state;
    }

    /** From 0 to 1 */
    public double progress() {
        return this.progress;
    }

    /** What is being done right now, in words for a person */
    public String stage() {
        return this.stage;
    }

    /** Whether the work has to play the recording in the game, which takes the game over while it lasts */
    public boolean throughGame() {
        return this.throughGame;
    }

    /** The new recording's file, once done */
    public @Nullable Path result() {
        return this.result;
    }

    public @Nullable String resultId() {
        return this.resultId;
    }

    public int resultTicks() {
        return this.resultTicks;
    }

    /** Why it failed, when it did */
    public @Nullable String error() {
        return this.error;
    }

    /** Things worth telling the person that did not stop the work: mods that differ between the sources, say */
    public List<String> notes() {
        return List.copyOf(this.notes);
    }

    /** Completes with the new file, or exceptionally when the work failed or was cancelled */
    public CompletableFuture<Path> done() {
        return this.done;
    }

    /** Asks the work to stop. Nothing is left behind; the sources were never touched */
    public void cancel() {
        this.cancelAsked = true;
    }

    public boolean cancelAsked() {
        return this.cancelAsked;
    }

    /** Called on whichever thread the change happens on, each time anything here changes */
    public void listen(Consumer<LibraryJob> listener) {
        this.listeners.add(listener);
    }

    private void changed() {
        for (Consumer<LibraryJob> listener : this.listeners) {
            try {
                listener.accept(this);
            } catch (Throwable ignored) {
                // A listener's trouble is not the job's
            }
        }
    }

    void at(double progress, String stage) {
        this.progress = Math.max(0.0, Math.min(1.0, progress));
        this.stage = stage;
        this.changed();
    }

    void usesGame() {
        this.throughGame = true;
        this.changed();
    }

    void note(String note) {
        this.notes.add(note);
    }

    void finished(Path file, String recordingId, int ticks) {
        this.result = file;
        this.resultId = recordingId;
        this.resultTicks = ticks;
        this.progress = 1.0;
        this.stage = "Done";
        this.state = State.DONE;
        this.changed();
        this.done.complete(file);
    }

    void failed(Throwable problem, boolean cancelled) {
        this.error = problem.getMessage() == null ? problem.toString() : problem.getMessage();
        this.stage = cancelled ? "Cancelled" : "Failed";
        this.state = cancelled ? State.CANCELLED : State.FAILED;
        this.changed();
        this.done.completeExceptionally(problem);
    }

}
