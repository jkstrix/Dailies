package comedymonkey.dailies.editor;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.edit.KeyframeRef;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * The path preview's "preview" button at work in the game (specification EDIT-8): a {@link StretchRun} driven once a
 * frame from the start of the frame, where an export plays its ticks too.
 * <p>
 * The recording stays paused throughout, as far as the game and the editor know, and the ticks are played by hand
 * with {@link Playback#runTo}, as an export plays them: so the game's own clock never runs the recording on, the
 * keyframes do not take the view (they only do while playing), and the playback speed and speed keyframes do not
 * matter. Every tick played is a whole game tick, after which the following cameras are stepped as in normal play,
 * the Smart Camera with them, and it remembers where it put the camera for the path preview. Nothing of the world is
 * drawn meanwhile, nor heard: the frames show only the progress display, which is what lets the ticks go by at twenty
 * times their speed.
 */
public final class StretchPreview {

    private static @Nullable StretchRun run = null;

    private StretchPreview() {
    }

    /** The recording, as the run asks for it */
    private static final StretchRun.Player PLAYER = new StretchRun.Player() {
        @Override
        public int position() {
            return Playback.position();
        }

        @Override
        public boolean jumping() {
            return Playback.jumping();
        }

        @Override
        public boolean paused() {
            return Playback.paused();
        }

        @Override
        public void pause() {
            Playback.pause();
        }

        @Override
        public void play() {
            Playback.play();
        }

        @Override
        public void jumpTo(int tick) {
            // As exact as an export's jump: the path drawn should be the path exported
            Playback.jumpTo(tick, true);
        }

        @Override
        public void goBack(int tick) {
            Playback.viewTo(tick, true);
        }

        @Override
        public boolean tick() {
            // The tick the run stands on is stepped first, should no game tick have come since the jump landed on it
            CameraSteps.catchUp();
            return Playback.runTo(Minecraft.getInstance(), Playback.position() + 1);
        }

        @Override
        public long nanos() {
            return System.nanoTime();
        }
    };

    /**
     * The stretch the button would play now: the one Smart Camera keyframe selected, or else the Smart Camera filming
     * at the playhead. Null when there is none, or the recording cannot be run just now.
     */
    public static @Nullable StretchRun.Stretch stretchNow(Collection<KeyframeRef> selected) {
        if (!EditorSession.open() || !Playback.loaded() || PointOfView.away()) {
            return null;
        }
        return StretchRun.find(EditorSession.scene(), selected, PointOfView.position(), PointOfView.total());
    }

    /**
     * Plays the stretch the button would play.
     *
     * @throws IllegalStateException with words for the person when it cannot be done now
     */
    public static StretchRun.Stretch start(Collection<KeyframeRef> selected) {
        if (run != null) {
            throw new IllegalStateException("A Smart Camera stretch is being previewed already");
        }
        if (!EditorSession.open() || !Playback.loaded()) {
            throw new IllegalStateException("No recording is open");
        }
        if (comedymonkey.dailies.export.Export.active()) {
            throw new IllegalStateException("An export is under way");
        }
        if (Playback.jumping()) {
            throw new IllegalStateException("The recording is in the middle of a jump");
        }
        if (PointOfView.away()) {
            // The stretch is played in the scene's own recording: another point of view is in other ticks
            throw new IllegalStateException("Another player's recording is shown: go back to the scene's own to preview");
        }
        StretchRun.Stretch stretch = stretchNow(selected);
        if (stretch == null) {
            throw new IllegalArgumentException("There is no Smart Camera stretch at the playhead, and no Smart Camera keyframe is selected");
        }
        run = new StretchRun(PLAYER, stretch);
        return stretch;
    }

    /** Stops the run and goes back to where the playhead was */
    public static void cancel() {
        StretchRun now = run;
        if (now != null) {
            now.cancel();
        }
    }

    /** Whether a run is under way, from the button being pressed until the playhead is back */
    public static boolean running() {
        return run != null;
    }

    /** The run under way, for the progress display; null when there is none */
    public static @Nullable StretchRun current() {
        return run;
    }

    private static @Nullable StretchRun last = null;

    /** The run that ended last, for whoever wants to know how it went; null before the first */
    public static @Nullable StretchRun last() {
        return last;
    }

    /** The recording is closing: a run of it has nothing left to go back to */
    static void forget() {
        StretchRun ended = run;
        if (ended != null) {
            end(ended);
        }
    }

    /** At the start of every frame, outside any game tick, once playback has done its own work for the frame */
    public static void frameStart() {
        StretchRun now = run;
        if (now == null) {
            return;
        }
        if (!Playback.loaded() || !EditorSession.open()) {
            // The recording closed under it
            end(now);
            return;
        }
        try {
            now.frame();
        } catch (RuntimeException e) {
            Host.LOGGER.warn("Dailies: the Smart Camera path preview failed at tick {}", Playback.position(), e);
            // Back to where the playhead was if that can still be done; a failure on the way back is the end of it
            boolean back = now.phase() == StretchRun.Phase.RETURNING;
            try {
                now.cancel();
            } catch (RuntimeException again) {
                back = true;
            }
            if (back) {
                end(now);
                return;
            }
        }
        if (now.phase() == StretchRun.Phase.DONE) {
            end(now);
        }
    }

    private static void end(StretchRun ended) {
        last = ended;
        run = null;
        if (EditorSession.open() && Playback.loaded()) {
            // A cancel needs no words, and a whole run shows its path; a run cut short says why
            if (ended.abandoned()) {
                comedymonkey.dailies.ui.EditorScreen.say(net.minecraft.network.chat.Component.translatable("dailies.editor.preview.let_go"));
            } else if (!ended.finished() && !ended.cancelled()) {
                comedymonkey.dailies.ui.EditorScreen.say(net.minecraft.network.chat.Component.translatable("dailies.editor.preview.stopped"));
            }
        }
        if (!Boolean.getBoolean("dailies.playback.quietJumps")) {
            Host.LOGGER.info(String.format(java.util.Locale.ROOT, "Dailies: previewed the Smart Camera stretch from tick %d to %d%s",
                ended.stretch().first(), ended.stretch().last(),
                ended.finished() ? " in full" : ended.cancelled() ? ", cancelled" : ended.abandoned() ? ", let go when the recording was moved" : ", stopped early"));
        }
    }

}
