package comedymonkey.dailies.selftest;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The groups of the scripted run that need others run before them, for a run of only some groups (-PdailiesOnly).
 * No game in here, so it is unit tested.
 */
final class StepGroups {

    /**
     * The groups each group needs run before it, because it works on what they leave: the scripted recording, the
     * recording open in playback, the pack recording. A group not listed starts from the title screen by itself
     */
    static final Map<String, List<String>> NEEDS = Map.ofEntries(
        Map.entry("recording", List.of("world")),
        Map.entry("watching", List.of("world", "recording")),
        Map.entry("moments", List.of("world", "recording")),
        Map.entry("packs", List.of("world")),
        Map.entry("playback", List.of("recording")),
        Map.entry("balloon", List.of("playback")),
        Map.entry("export", List.of("playback")),
        Map.entry("editor", List.of("export")),
        Map.entry("renderer", List.of("playback")),
        // The library cues the kill through its clip, which the moments steps make (OwnerFeatureSteps)
        Map.entry("library", List.of("recording", "packs", "moments")));

    private StepGroups() {
    }

    /** The groups named and every group they need, however deep */
    static Set<String> withNeeds(Collection<String> names) {
        Set<String> all = new LinkedHashSet<>();
        Deque<String> waiting = new ArrayDeque<>(names);
        while (!waiting.isEmpty()) {
            String group = waiting.pop();
            if (all.add(group)) {
                waiting.addAll(NEEDS.getOrDefault(group, List.of()));
            }
        }
        return all;
    }
}
