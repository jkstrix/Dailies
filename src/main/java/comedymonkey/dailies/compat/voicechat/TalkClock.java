package comedymonkey.dailies.compat.voicechat;

/**
 * The clock Simple Voice Chat's talking icons are timed by while a recording is shown. Voice chat lights a player's
 * icon for a quarter of a second after the last piece of their voice, by its own clock. In playback the voice comes
 * from the recording, and the recording's time is what counts: an export draws frames far slower than the recording
 * runs, and a paused recording should keep showing who was talking at that tick. So voice chat is given this clock,
 * which follows the recording's tick: it moves on 50 milliseconds a tick as the recording plays, stands still while it
 * stands still, and leaps ahead when the recording goes back, so that nobody's icon stays lit from a moment that is no
 * longer being shown. It never goes backwards, as voice chat expects of a clock.
 * <p>
 * Plain Java, so it can be tested without the game.
 */
public final class TalkClock {

    /** Milliseconds a tick of recording takes at its own speed */
    public static final long TICK_MILLIS = 50L;
    /** How far the clock leaps when the recording goes back: far past the time voice chat keeps an icon lit */
    public static final long LEAP_MILLIS = 10_000L;

    private int tick = Integer.MIN_VALUE;
    private long millis = 0L;

    /** The clock's time with the recording at this tick */
    public synchronized long at(int recordingTick) {
        if (this.tick == Integer.MIN_VALUE) {
            this.tick = recordingTick;
        } else if (recordingTick > this.tick) {
            this.millis += (recordingTick - this.tick) * TICK_MILLIS;
            this.tick = recordingTick;
        } else if (recordingTick < this.tick) {
            this.millis += LEAP_MILLIS;
            this.tick = recordingTick;
        }
        return this.millis;
    }

    /** Puts every icon lit so far out: the next reading is well past them, whatever tick it is at */
    public synchronized void leap() {
        this.millis += LEAP_MILLIS;
    }

}
