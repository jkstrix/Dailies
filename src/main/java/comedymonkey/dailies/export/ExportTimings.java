package comedymonkey.dailies.export;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Where an export's time goes, stage by stage, gathered while it runs and said in one line of the log at the end, so
 * a slow export can be read rather than guessed at. Stages on the game's thread add up to the time of a frame; the
 * writer's run beside them. No Minecraft here.
 */
final class ExportTimings {

    enum Stage {
        /** Playing the recording on to the frame's moment */
        TICKS("ticks"),
        /** The game drawing the frame */
        DRAW("draw"),
        /** Converting the picture on the graphics card and asking for it to be copied back */
        CONVERT("convert"),
        /** Waiting for the graphics card to hand over a picture asked for earlier */
        READBACK("readback wait"),
        /** Copying it out of the card's buffer into a frame buffer */
        COPY("copy"),
        /** Waiting for the writer to give a frame buffer back: the encoder is behind */
        QUEUE("queue wait"),
        /** The sound that goes with the frame */
        SOUND("sound"),
        /** The rest of the game's loop: the window, the progress panel, the swap */
        LOOP("rest of loop"),
        /** Pushing frames down the pipe to the encoder, on the writer's thread: long means the encoder is the limit */
        PIPE("pipe write"),
        /** The writer waiting for a frame: long means the game is the limit */
        WRITER_IDLE("writer idle");

        final String words;

        Stage(String words) {
            this.words = words;
        }
    }

    private final AtomicLongArray nanos = new AtomicLongArray(Stage.values().length);
    private volatile long frames = 0;
    private volatile long startNanos = System.nanoTime();

    void reset() {
        for (int i = 0; i < this.nanos.length(); i++) {
            this.nanos.set(i, 0L);
        }
        this.frames = 0;
        this.startNanos = System.nanoTime();
    }

    void add(Stage stage, long elapsedNanos) {
        if (elapsedNanos > 0) {
            this.nanos.addAndGet(stage.ordinal(), elapsedNanos);
        }
    }

    void frames(long count) {
        this.frames = count;
    }

    long total(Stage stage) {
        return this.nanos.get(stage.ordinal());
    }

    /** "export timings over 600 frames at 212.4 a second, milliseconds a frame: ticks 0.41, draw 2.10, ..." */
    String line(String what) {
        return this.line(what, System.nanoTime() - this.startNanos);
    }

    String line(String what, long wallNanos) {
        long count = Math.max(1, this.frames);
        StringBuilder said = new StringBuilder(String.format(Locale.ROOT, "export timings (%s) over %d frames at %.1f a second, milliseconds a frame:",
            what, this.frames, wallNanos > 0 ? this.frames * 1.0e9 / wallNanos : 0.0));
        String separator = " ";
        for (Stage stage : Stage.values()) {
            said.append(separator).append(stage.words).append(' ').append(String.format(Locale.ROOT, "%.2f", this.total(stage) / 1.0e6 / count));
            separator = ", ";
        }
        return said.toString();
    }

}
