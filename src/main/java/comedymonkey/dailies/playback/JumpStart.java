package comedymonkey.dailies.playback;

import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

/**
 * Where a jump starts: from a segment's snapshot, with the world built again from it, or from where the recording is,
 * played on to the tick asked for. Worked out afresh for every jump from where the recording stands, so a jump that
 * replaces one not yet carried out never inherits its choice. Nothing here touches the game.
 */
final class JumpStart {

    private JumpStart() {
    }

    /**
     * The segment whose snapshot a jump starts from, or -1 to play on from where the recording is
     *
     * @param target          the tick to land on
     * @param position        the tick the recording is at now
     * @param quick           a jump made while the playhead is dragged: no run-up, it lands sooner and may be a few ticks out
     * @param again           the world must be built again even if playing on would reach the tick (it was reached roughly,
     *                        or kept with mod machines in it for a jump that has to be exact)
     * @param runUp           how many ticks a jump that is not quick plays through after a snapshot before it lands
     * @param segmentAt       the index of the segment a tick is in
     * @param segmentStart    the first tick of a segment, by index
     * @param continues       whether a segment follows on from the one before, so play can run from one into the other
     */
    static int segment(int target, int position, boolean quick, boolean again, int runUp,
                       IntUnaryOperator segmentAt, IntUnaryOperator segmentStart, IntPredicate continues) {
        int index = segmentAt.applyAsInt(target);
        if (!quick && target - segmentStart.applyAsInt(index) < runUp && continues.test(index)) {
            // Landing just after a snapshot: start from the one before, so that everything a game eases into place over a
            // few ticks (a bearing's angle, a craft's smoothing, an entity's last steps) has settled, as it has in plain play
            index--;
        }
        if (again) {
            return index;
        }
        if (target < position || segmentStart.applyAsInt(index) > position) {
            // Backwards, or far enough ahead that a later snapshot is the nearer start
            return index;
        }
        return -1;
    }

}
