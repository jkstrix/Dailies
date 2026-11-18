package comedymonkey.dailies.playback;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A map that holds at most so many entries, letting go of the one used longest ago to make room for a new one. For
 * what is worked out from data a recording stores under a name: that data never changes, so neither does the result,
 * and the only question is how much of it to keep.
 */
final class RecentlyUsed<K, V> extends LinkedHashMap<K, V> {

    private final int most;

    RecentlyUsed(int most) {
        super(16, 0.75F, true);
        if (most < 1) {
            throw new IllegalArgumentException("A map that holds nothing is no use: " + most);
        }
        this.most = most;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return this.size() > this.most;
    }

}
