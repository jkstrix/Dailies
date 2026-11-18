package comedymonkey.dailies.ui;

/**
 * Where the Tab key takes the keys next among the controls of a screen, in the order it visits them. No game here, so
 * it can be tested on its own.
 */
final class FocusStep {

    private FocusStep() {
    }

    /**
     * @param size how many controls there are to visit
     * @param at   the place of the control the keys are on now; -1 when they are on none of them
     * @param back whether Tab was pressed with Shift, which goes the other way
     * @return the place of the control to move to: the first, or with Shift the last, when the keys were on none; round
     * from the last to the first and back; -1 when there is nothing to visit
     */
    static int next(int size, int at, boolean back) {
        if (size <= 0) {
            return -1;
        }
        if (at < 0 || at >= size) {
            return back ? size - 1 : 0;
        }
        return Math.floorMod(at + (back ? -1 : 1), size);
    }

}
