package comedymonkey.dailies.library;

import comedymonkey.dailies.edit.EditState;
import comedymonkey.dailies.edit.Keyframe;
import comedymonkey.dailies.edit.Scene;
import comedymonkey.dailies.edit.Track;
import comedymonkey.dailies.file.Spans;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntUnaryOperator;

/**
 * Carries the editor's work across when a recording is trimmed or combined: every scene comes along, with its
 * keyframes and its export range moved to where their ticks are in the new recording. A keyframe on a tick that was
 * cut is dropped, since there is nowhere for it to sit; the scene itself always stays, looks and all.
 */
public final class EditRemap {

    private EditRemap() {
    }

    /** The editor state of a recording made by keeping these ranges of another */
    public static EditState trimmed(EditState state, List<Spans.Range> keep) {
        EditState result = state.copy();
        for (int index = 0; index < result.scenes().size(); index++) {
            Scene scene = result.scene(index);
            moveKeyframes(scene, tick -> Spans.remap(keep, tick));
            if (scene.hasExportRange()) {
                // The range shrinks to what is left of it: from the first kept tick at or after its start to the last at or before its end
                int start = -1;
                int end = -1;
                int running = 0;
                for (Spans.Range range : keep) {
                    int from = Math.max(range.from(), scene.exportStart());
                    int to = Math.min(range.to() - 1, scene.exportEnd());
                    if (to >= from) {
                        if (start < 0) {
                            start = running + (from - range.from());
                        }
                        end = running + (to - range.from());
                    }
                    running += range.length();
                }
                if (start >= 0) {
                    scene.setExportRange(start, end);
                } else {
                    scene.clearExportRange();
                }
            }
        }
        return result;
    }

    /**
     * The editor state of a recording made by playing several one after another: every scene of every one of them,
     * with its ticks moved along by the length of what comes before. A source that was never edited brings nothing.
     *
     * @param states  each source's editor state, or null where it has none
     * @param lengths each source's length in ticks
     * @param names   each source's name, used to tell scenes of the same name apart
     * @return null when no source had any editor state
     */
    public static @Nullable EditState joined(List<EditState> states, List<Integer> lengths, List<String> names) {
        EditState result = null;
        Set<String> used = new HashSet<>();
        int offset = 0;
        for (int i = 0; i < states.size(); i++) {
            EditState state = states.get(i);
            int shift = offset;
            offset += lengths.get(i);
            if (state == null) {
                continue;
            }
            for (Scene original : state.scenes()) {
                Scene scene = original.copy();
                moveKeyframes(scene, tick -> tick + shift);
                if (scene.hasExportRange()) {
                    scene.setExportRange(scene.exportStart() + shift, scene.exportEnd() + shift);
                }
                if (!used.add(scene.name())) {
                    String name = scene.name() + " (" + names.get(i) + ")";
                    for (int number = 2; !used.add(name); number++) {
                        name = scene.name() + " (" + names.get(i) + " " + number + ")";
                    }
                    scene.setName(name);
                }
                if (result == null) {
                    result = new EditState();
                    result.addScene(1, scene);
                    result.removeScene(0);
                } else {
                    result.addScene(result.scenes().size(), scene);
                }
            }
        }
        if (result != null) {
            result.selectScene(0);
        }
        return result;
    }

    /** Moves every keyframe of a scene to the tick the mapping gives; one mapped to a negative tick is dropped */
    private static void moveKeyframes(Scene scene, IntUnaryOperator mapping) {
        for (int index = 0; index < scene.tracks().size(); index++) {
            Track old = scene.track(index);
            Track moved = new Track(old.kind());
            moved.setName(old.name());
            moved.setEnabled(old.enabled());
            for (Keyframe keyframe : old.keyframes()) {
                int tick = mapping.applyAsInt(keyframe.tick());
                if (tick >= 0 && tick <= Keyframe.MAX_TICK) {
                    moved.put(keyframe.withTick(tick));
                }
            }
            scene.removeTrack(index);
            scene.addTrack(index, moved);
        }
    }

}
