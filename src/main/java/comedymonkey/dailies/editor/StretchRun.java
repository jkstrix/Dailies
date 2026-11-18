package comedymonkey.dailies.editor;

import comedymonkey.dailies.edit.Evaluator;
import comedymonkey.dailies.edit.Keyframe;
import comedymonkey.dailies.edit.KeyframeKind;
import comedymonkey.dailies.edit.KeyframeRef;
import comedymonkey.dailies.edit.Scene;
import comedymonkey.dailies.edit.Track;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.TreeSet;

/**
 * One run of the path preview's "preview" button (specification EDIT-8): a Smart Camera stretch played from its first
 * tick to its last as fast as the game can, but never faster than twenty times as recorded, so that the Smart Camera
 * films it as it always does and the path preview has the path to draw. Then the playhead goes back to where it was,
 * playing or not as it was. The Smart Camera itself is not touched: it is stepped by the ticks played, as ever.
 * <p>
 * Which stretch, how fast, and the steps of the run are worked out here, with the recording reached through
 * {@link Player}, so that all of it can be tested without a game. {@link StretchPreview} runs it in the game.
 */
public final class StretchRun {

    /** Ticks of recording a second at most: twenty times as recorded */
    public static final double FASTEST = 400.0;
    /** How long the ticks of a run may hold up one frame, so the game stays responsive, as a jump does (PLAY-15) */
    public static final long SLICE_NANOS = 30_000_000L;
    /** Frames in a row in which the recording would not move on before the run gives up and goes back */
    private static final int MOST_STALLS = 120;

    /**
     * A Smart Camera stretch: the keyframe on the track at {@code first}, filming until {@code last}, both in the
     * scene's ticks. It ends where its track's next keyframe begins, where a camera track listed above it takes over,
     * or at the end of the recording.
     */
    public record Stretch(int track, int first, int last) {
        public int length() {
            return this.last - this.first;
        }
    }

    /**
     * The stretch the button plays: the one Smart Camera keyframe that is selected, or else the Smart Camera that
     * moves the camera at the playhead.
     *
     * @param total the length of the recording, in the scene's ticks
     * @return null when there is none, or it films nothing (another camera track covers it, or it is a tick long)
     */
    public static @Nullable Stretch find(Scene scene, Collection<KeyframeRef> selected, int playhead, int total) {
        KeyframeRef chosen = null;
        for (KeyframeRef ref : selected) {
            if (ref.track() >= 0 && ref.track() < scene.tracks().size() && scene.track(ref.track()).kind() == KeyframeKind.SMART_CAMERA
                && scene.track(ref.track()).at(ref.tick()) != null) {
                if (chosen != null) {
                    // Several are selected: none of them is meant more than another, so the playhead decides
                    chosen = null;
                    break;
                }
                chosen = ref;
            }
        }
        if (chosen != null) {
            return of(scene, chosen.track(), chosen.tick(), total);
        }
        Track driver = Evaluator.cameraDriver(scene, playhead);
        if (driver == null || driver.kind() != KeyframeKind.SMART_CAMERA) {
            return null;
        }
        Keyframe keyframe = driver.atOrBefore(playhead);
        return keyframe == null ? null : of(scene, scene.tracks().indexOf(driver), keyframe.tick(), total);
    }

    private static @Nullable Stretch of(Scene scene, int index, int first, int total) {
        Track track = scene.track(index);
        if (first >= total || Evaluator.cameraDriver(scene, first) != track) {
            // A camera track listed above it has started already, and goes on for good: this one never films
            return null;
        }
        // Which track moves the camera can only change where some camera track has a keyframe
        TreeSet<Integer> changes = new TreeSet<>();
        for (Track other : scene.tracks()) {
            if (other.kind().drivesCamera() && other.enabled()) {
                for (Keyframe keyframe : other.keyframes()) {
                    if (keyframe.tick() > first && keyframe.tick() <= total) {
                        changes.add(keyframe.tick());
                    }
                }
            }
        }
        int last = total;
        for (int tick : changes) {
            Keyframe at = track.atOrBefore(tick);
            if (Evaluator.cameraDriver(scene, tick) != track || at == null || at.tick() != first) {
                last = tick - 1;
                break;
            }
        }
        return last > first ? new Stretch(index, first, last) : null;
    }

    /**
     * How many ticks may be played now so that the run never goes faster than {@link #FASTEST}: as many as that allows
     * since it began, less those played. One at once, so the first frame does not wait.
     *
     * @param nanosRunning how long the run has been playing
     * @param played       ticks played so far
     */
    public static int due(long nanosRunning, int played) {
        long allowed = (long) Math.floor(Math.max(0L, nanosRunning) * FASTEST / 1.0e9) + 1L;
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, allowed - played));
    }

    // ------------------------------------------------------------------ The run

    /** The recording, as the run needs it. All on the client thread */
    public interface Player {
        /** The tick the recording stands on, in the scene's ticks */
        int position();

        /** Whether a jump is under way, or the world is being rebuilt */
        boolean jumping();

        boolean paused();

        void pause();

        void play();

        /** A jump to the tick that lands on it exactly as an export would, and stays paused there */
        void jumpTo(int tick);

        /** A jump back to where the person was, as their own jumps go, staying paused there */
        void goBack(int tick);

        /**
         * Plays one tick at once, with the cameras stepped for it as in normal play.
         *
         * @return false when it could not: the stretch ahead needs its world rebuilt first, or the recording ended
         */
        boolean tick();

        long nanos();
    }

    public enum Phase {
        /** On the way to the stretch's first tick */
        GOING,
        /** Playing the stretch */
        RUNNING,
        /** On the way back to where the playhead was */
        RETURNING,
        /** Over: back where it was, or let go because something else moved the recording */
        DONE
    }

    private final Player player;
    private final Stretch stretch;
    private final int backTo;
    private final boolean playOn;
    private Phase phase = Phase.GOING;
    private long startedAt;
    private long lastNanos;
    private int played;
    private int expected;
    private int stalls;
    private boolean finished;
    private boolean cancelled;
    private boolean abandoned;

    /** Starts a run: the recording is paused where it stands, which is where it comes back to, and taken to the stretch */
    public StretchRun(Player player, Stretch stretch) {
        this.player = player;
        this.stretch = stretch;
        this.backTo = player.position();
        this.playOn = !player.paused();
        if (this.playOn) {
            player.pause();
        }
        player.jumpTo(stretch.first());
    }

    public Stretch stretch() {
        return this.stretch;
    }

    public Phase phase() {
        return this.phase;
    }

    /** Whether the whole stretch was played, so its path is all there */
    public boolean finished() {
        return this.finished;
    }

    public boolean cancelled() {
        return this.cancelled;
    }

    /** Whether something else took the recording away mid-run (a jump, play pressed), so it was left where that put it */
    public boolean abandoned() {
        return this.abandoned;
    }

    /** How much of the stretch has been played, from 0 to 1 */
    public double share() {
        return switch (this.phase) {
            case GOING -> 0.0;
            case RUNNING -> Math.max(0.0, Math.min(1.0, (double) (this.player.position() - this.stretch.first()) / Math.max(1, this.stretch.length())));
            case RETURNING, DONE -> this.finished ? 1.0 : Math.max(0.0, Math.min(1.0, (double) (this.expected - this.stretch.first()) / Math.max(1, this.stretch.length())));
        };
    }

    /** How fast it has gone so far, as so many times as recorded; 0 before it has begun */
    public double speed() {
        double seconds = (this.lastNanos - this.startedAt) / 1.0e9;
        return this.phase == Phase.GOING || seconds <= 0.0 ? 0.0 : this.played / seconds / 20.0;
    }

    /** Stops playing and goes back. Nothing more to do once it is on its way back */
    public void cancel() {
        if (this.phase == Phase.GOING || this.phase == Phase.RUNNING) {
            this.cancelled = true;
            this.goBack();
        }
    }

    /** Once a frame, outside any game tick */
    public void frame() {
        switch (this.phase) {
            case GOING -> this.going();
            case RUNNING -> this.running();
            case RETURNING -> this.returning();
            case DONE -> {
            }
        }
    }

    private void going() {
        if (this.player.jumping()) {
            return;
        }
        if (this.player.position() != this.stretch.first() || !this.player.paused()) {
            // Another jump replaced ours, or play was pressed: whoever did that has the recording now
            this.abandon();
            return;
        }
        this.phase = Phase.RUNNING;
        this.startedAt = this.player.nanos();
        this.lastNanos = this.startedAt;
        this.expected = this.stretch.first();
    }

    private void running() {
        if (this.player.jumping()) {
            // The stretch ahead starts from a world of its own, which playback builds between frames, as in normal play
            return;
        }
        if (!this.player.paused() || this.player.position() < this.expected) {
            this.abandon();
            return;
        }
        long now = this.player.nanos();
        long deadline = now + SLICE_NANOS;
        int allowed = due(now - this.startedAt, this.played);
        int before = this.player.position();
        boolean stopped = false;
        while (allowed > 0 && this.player.position() < this.stretch.last()) {
            if (!this.player.tick()) {
                stopped = true;
                break;
            }
            this.played++;
            allowed--;
            if (this.player.jumping() || this.player.nanos() > deadline) {
                break;
            }
        }
        this.lastNanos = this.player.nanos();
        int position = this.player.position();
        this.expected = position;
        if (position >= this.stretch.last()) {
            this.finished = true;
            this.goBack();
            return;
        }
        // Played nothing although it could: once is the world being rebuilt ahead, every frame is a recording that ended
        // early or will not be read, and then there is nothing more to wait for
        this.stalls = position == before && (stopped || allowed > 0) && !this.player.jumping() ? this.stalls + 1 : 0;
        if (this.stalls > MOST_STALLS) {
            this.goBack();
        }
    }

    private void returning() {
        if (this.player.jumping()) {
            return;
        }
        if (this.player.position() == this.backTo && this.player.paused() && this.playOn) {
            this.player.play();
        }
        this.phase = Phase.DONE;
    }

    private void goBack() {
        this.phase = Phase.RETURNING;
        this.player.goBack(this.backTo);
    }

    private void abandon() {
        this.abandoned = true;
        this.phase = Phase.DONE;
    }

}
