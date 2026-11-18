package comedymonkey.dailies.ui;

import java.util.HashMap;
import java.util.Map;

/**
 * How many recordings each folder of the library holds, those in the folders inside it included, worked out in one
 * walk through the recordings when the list is built rather than once a frame for every folder shown. No game here,
 * so it can be tested on its own.
 */
final class FolderCounts {

    private FolderCounts() {
    }

    /**
     * @param folders the folder of each recording, relative to the recordings folder, with "/" between its parts;
     *                empty for one at the top
     * @return for every folder that holds any, how many: "a" counts a recording in "a" and one in "a/b", but not one
     * in "ab"
     */
    static Map<String, Integer> of(Iterable<String> folders) {
        Map<String, Integer> counts = new HashMap<>();
        for (String in : folders) {
            if (in == null || in.isEmpty()) {
                continue;
            }
            int from = 0;
            while (true) {
                int slash = in.indexOf('/', from);
                counts.merge(slash < 0 ? in : in.substring(0, slash), 1, Integer::sum);
                if (slash < 0) {
                    break;
                }
                from = slash + 1;
            }
        }
        return counts;
    }

}
