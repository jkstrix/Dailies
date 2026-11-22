package comedymonkey.dailies.capture;

import java.util.function.BooleanSupplier;

/**
 * Ticks recorded while the game stands paused (specification REC-36). In singleplayer the game pauses under a book
 * read or written, as under the pause menu: the world stops ticking, though the game's own clock and the screen go on.
 * The owner wanted that pause kept, and the book in the recording as well, so while the game stands paused under a
 * play screen the recording keeps time: a tick is recorded for every tick of the game's clock, with the screen's state
 * and nothing of the world, which has not moved, and each such tick says so with a {@link #ID} record. Playback does not
 * tick its world for those ticks either, so the world stands still and the book stays open for as long as it was
 * shown. Under any other screen that pauses the game, the pause menu above all, the recording's clock stops, as it
 * always has. On a server nothing pauses, so none of this arises.
 * <p>
 * Plain Java, so it can be tested without the game.
 */
public final class StillTicks {

    /**
     * The extension record, with nothing in it, of a tick the live game stood paused for. It is written among the records
     * that come before the tick's own count, where the recorder's own body would be, so playback reads it just before
     * the world would be ticked for that tick
     */
    public static final String ID = "dailies:still";

    /** What becomes of a tick of the game's clock while a recording is made */
    public enum Kind {
        /** The world ticked: recorded as always */
        MOVING,
        /** The game stood paused under a play screen the recording keeps: recorded, with the world standing still */
        STILL,
        /** The game stood paused under any other screen: not recorded, and the recording's clock stops */
        NONE
    }

    private StillTicks() {
    }

    /**
     * @param gamePaused whether the game stood paused for this tick, so its world was not ticked
     * @param playScreen whether the screen open is one the recording keeps; asked only when the game stood paused
     */
    public static Kind of(boolean gamePaused, BooleanSupplier playScreen) {
        if (!gamePaused) {
            return Kind.MOVING;
        }
        return playScreen.getAsBoolean() ? Kind.STILL : Kind.NONE;
    }

    /**
     * Playback: whether the world stands still for the tick about to run. A still record is read just before the count of
     * its tick, so it belongs to the tick about to run only when it was read at the tick the recording has just reached;
     * one read before that, where a snapshot begins, is of a tick the snapshot already holds
     *
     * @param readAt where the recording stood when a still record was last read, or -1 for none since the world was built
     * @param position where the recording stands now
     */
    public static boolean stands(int readAt, int position) {
        return readAt >= 0 && readAt == position;
    }

}
