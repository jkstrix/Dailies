package comedymonkey.dailies.ui;

import java.util.Arrays;

/**
 * A word for what the editor's pick panel offers: which pick it is for, and which things near the viewer it lists, by
 * their ids. The panel is built afresh only when the word changes, so a control the keys are on keeps its ring while
 * the same things stay near, however they move about among themselves. No game here, so it can be tested on its own.
 */
final class PickShape {

    private PickShape() {
    }

    /**
     * @param pick an identity of the pick being made; 0 for none
     * @param ids  the ids of the things listed, in any order
     */
    static String of(int pick, int[] ids) {
        int[] sorted = ids.clone();
        Arrays.sort(sorted);
        StringBuilder shape = new StringBuilder().append(pick).append(':');
        for (int id : sorted) {
            shape.append(id).append(',');
        }
        return shape.toString();
    }

}
