package comedymonkey.dailies.playback;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Works out which number stands for which sound, particle, block and the rest while a recording plays (specification
 * PLAY-25). Packets name registry entries by number, and a server numbers them its own way: the recording keeps the
 * numbering its game used (REC-37), and this compares it with this game's own and says what to put in force. Names are
 * plain text here and nothing touches the game, so it is unit tested; RegistryNumbering puts the result in force.
 */
public final class Numbering {

    /** What a recording keeps for a number that stood for nothing */
    public static final String NOTHING = "dailies:missing";

    /**
     * What playing a recording comes to.
     *
     * @param apply   by registry, the numbering to put in force, number to name, for each registry whose numbering differs
     *                from this game's; a registry left out keeps this game's own
     * @param missing by registry, the names the recording numbers that this game does not have
     */
    public record Plan(Map<String, SortedMap<Integer, String>> apply, Map<String, List<String>> missing) {
        public boolean changesNothing() {
            return this.apply.isEmpty();
        }
    }

    private Numbering() {
    }

    /**
     * A numbering as a recording keeps it, read back: the recording's world data lists each registry's entries in number
     * order up to the registry's size, with {@link #NOTHING} for a number that stands for nothing, which on a server
     * includes the numbers past those the server sent. Here number to name, without the numbers that stand for nothing.
     * A name listed twice keeps its first number; the game never gives one entry two
     */
    public static <N> SortedMap<Integer, N> numbering(List<N> list, N nothing) {
        SortedMap<Integer, N> numbered = new TreeMap<>();
        Set<N> seen = new HashSet<>();
        for (int number = 0; number < list.size(); number++) {
            N name = list.get(number);
            if (name != null && !name.equals(nothing) && seen.add(name)) {
                numbered.put(number, name);
            }
        }
        return numbered;
    }

    /**
     * What to put in force for a recording.
     *
     * @param recorded by registry, the numbering the recording's game used, as its world data lists it
     * @param own      by registry, this game's own numbering, for the registries a server sends its numbering for
     * @param fillers  by registry, the entry that stands for nothing (a silent sound, air), for the numbers whose entry this
     *                 game does not have; a registry without one, or whose one this game does not have, uses its last entry
     */
    public static Plan plan(Map<String, List<String>> recorded, Map<String, ? extends Map<Integer, String>> own, Map<String, String> fillers) {
        Map<String, SortedMap<Integer, String>> apply = new LinkedHashMap<>();
        Map<String, List<String>> missing = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends Map<Integer, String>> registry : own.entrySet()) {
            List<String> names = recorded.get(registry.getKey());
            if (names == null) {
                // A registry the recording's game did not have, a mod's added since: nothing in the recording names its entries
                continue;
            }
            Map<String, Integer> ownNumbers = new HashMap<>();
            registry.getValue().forEach((number, name) -> ownNumbers.put(name, number));

            SortedMap<Integer, String> kept = new TreeMap<>();
            List<String> lacking = new ArrayList<>();
            boolean differs = false;
            for (Map.Entry<Integer, String> entry : numbering(names, NOTHING).entrySet()) {
                Integer here = ownNumbers.get(entry.getValue());
                if (here == null) {
                    lacking.add(entry.getValue());
                } else {
                    kept.put(entry.getKey(), entry.getValue());
                    differs |= here.intValue() != entry.getKey();
                }
            }
            if (!lacking.isEmpty()) {
                missing.put(registry.getKey(), lacking);
            }
            // Every entry both games have has the same number here, as in singleplayer, where the server is this game:
            // nothing to change
            if (!differs) {
                continue;
            }

            SortedMap<Integer, String> numbering = new TreeMap<>(kept);
            int top = kept.lastKey();
            // A number whose entry this game lacks stands for the entry that stands for nothing, so that every number below
            // the top stands for something: the game walks a registry by number, and a gap there stops it. A packet that names
            // the missing entry then plays silence, or air, rather than whatever this game numbers so
            String filler = fillers.get(registry.getKey());
            if (filler == null || !ownNumbers.containsKey(filler)) {
                filler = kept.get(top);
            }
            for (int number = 0; number < top; number++) {
                numbering.putIfAbsent(number, filler);
            }
            // What only this game has (a mod the recording's server lacked) goes after the recording's, in this game's
            // order: the recording never names it, and it keeps a number of its own for anything this game asks
            Set<String> placed = new HashSet<>(kept.values());
            int next = top + 1;
            for (String name : new TreeMap<>(registry.getValue()).values()) {
                if (!placed.contains(name)) {
                    numbering.put(next++, name);
                }
            }
            apply.put(registry.getKey(), numbering);
        }
        return new Plan(apply, missing);
    }

}
