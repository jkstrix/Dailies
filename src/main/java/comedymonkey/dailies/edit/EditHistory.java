package comedymonkey.dailies.edit;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The one door through which the editor state is changed (specification EDIT-6). Every change is a step described in
 * words that can be undone and redone, without limit while the session lasts; a new change forgets the steps that
 * were undone. The editor's screens and the MCP tools share one of these per open recording.
 * <p>
 * A step is worked out on a copy of the state, so a change that fails with an {@link IllegalArgumentException}
 * changes nothing at all. {@link #state()} therefore returns a different object after every change: ask for it each
 * time and only read from it. The copy is made as the step goes ({@link EditState#draft}): the scenes, tracks and
 * looks the step does not touch are shared with the state before, so each step that is kept for undo holds only what
 * it changed. A track the step did not touch is therefore the very same object before and after it. Listeners hear of every change (for saving, EDIT-7), and {@link #revision()} goes up by
 * one with each.
 * <p>
 * Track and keyframe operations work on the current scene. Not thread safe: call from one thread.
 */
public final class EditHistory {

    /** Told after every change, including undo, redo and switching scene */
    public interface Listener {
        /**
         * @param description what happened, in words, for example "Add camera keyframe at tick 120" or "Undo: Clear
         *                    all tracks"
         */
        void changed(EditHistory history, String description);
    }

    private record Step(String description, EditState other) {
    }

    private EditState state;
    private long revision = 0;
    private final Deque<Step> undoSteps = new ArrayDeque<>();
    private final Deque<Step> redoSteps = new ArrayDeque<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private int groupDepth = 0;
    private String groupDescription = "";
    private @Nullable EditState groupBefore;

    /** Starts with a fresh state of one empty scene */
    public EditHistory() {
        this(new EditState());
    }

    /** Starts from a state that was loaded. The history keeps its own copy */
    public EditHistory(EditState state) {
        this.state = state.copy();
    }

    // ------------------------------------------------------------------ Reading

    /** The state as it is now. Read only; it is replaced, not altered, by the next change */
    public EditState state() {
        return this.state;
    }

    /** The current scene of the state as it is now */
    public Scene scene() {
        return this.state.currentScene();
    }

    /** Goes up by one with every change of any sort, never down */
    public long revision() {
        return this.revision;
    }

    public void addListener(Listener listener) {
        this.listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        this.listeners.remove(listener);
    }

    // ------------------------------------------------------------------ Undo and redo

    public boolean canUndo() {
        return !this.undoSteps.isEmpty() || this.groupDepth > 0 && this.groupBefore != this.state;
    }

    public boolean canRedo() {
        return !this.redoSteps.isEmpty();
    }

    /** What the next undo would take back, or null */
    public @Nullable String undoDescription() {
        return this.undoSteps.isEmpty() ? null : this.undoSteps.peek().description();
    }

    /** What the next redo would bring back, or null */
    public @Nullable String redoDescription() {
        return this.redoSteps.isEmpty() ? null : this.redoSteps.peek().description();
    }

    /** Every step that can be undone, the latest first */
    public List<String> undoDescriptions() {
        return this.undoSteps.stream().map(Step::description).toList();
    }

    /** Every step that can be redone, the next first */
    public List<String> redoDescriptions() {
        return this.redoSteps.stream().map(Step::description).toList();
    }

    /**
     * Takes back the latest step.
     *
     * @return the description of the step, or null when there was nothing to undo
     */
    public @Nullable String undo() {
        this.closeGroups();
        Step step = this.undoSteps.poll();
        if (step == null) {
            return null;
        }
        this.redoSteps.push(new Step(step.description(), this.state));
        this.state = step.other();
        this.announce("Undo: " + step.description());
        return step.description();
    }

    /**
     * Brings back the step undone last.
     *
     * @return the description of the step, or null when there was nothing to redo
     */
    public @Nullable String redo() {
        this.closeGroups();
        Step step = this.redoSteps.poll();
        if (step == null) {
            return null;
        }
        this.undoSteps.push(new Step(step.description(), this.state));
        this.state = step.other();
        this.announce("Redo: " + step.description());
        return step.description();
    }

    /**
     * From here until the matching {@link #endGroup}, all changes count as one step with this description: for a drag
     * that moves a keyframe a little on every frame. Groups inside a group join the outer one.
     */
    public void beginGroup(String description) {
        if (this.groupDepth++ == 0) {
            this.groupDescription = description;
            this.groupBefore = this.state;
        }
    }

    public void endGroup() {
        if (this.groupDepth == 0) {
            return;
        }
        if (--this.groupDepth == 0) {
            if (this.groupBefore != this.state) {
                this.undoSteps.push(new Step(this.groupDescription, this.groupBefore));
            }
            this.groupBefore = null;
        }
    }

    private void closeGroups() {
        if (this.groupDepth > 0) {
            this.groupDepth = 1;
            this.endGroup();
        }
    }

    // ------------------------------------------------------------------ Changing

    /**
     * Makes any change as one step. The change is given a copy of the state to alter; if it throws, nothing happens.
     * The operations below all come down to this one; use it for changes they do not cover.
     */
    public void edit(String description, Consumer<EditState> change) {
        this.step(next -> {
            change.accept(next);
            return description;
        });
    }

    /** Like {@link #edit}, for a change that only knows how to describe itself once it is made */
    private void step(Function<EditState, String> change) {
        EditState next = this.state.draft();
        String description = change.apply(next);
        next.seal();
        if (this.groupDepth == 0) {
            this.undoSteps.push(new Step(description, this.state));
        }
        this.redoSteps.clear();
        this.state = next;
        this.announce(description);
    }

    private void announce(String description) {
        this.revision++;
        for (Listener listener : this.listeners) {
            listener.changed(this, description);
        }
    }

    // ------------------------------------------------------------------ Scenes

    /**
     * Adds an empty scene after the others and makes it current.
     *
     * @param name null or blank for "Scene N"
     * @return the index of the new scene
     */
    public int addScene(@Nullable String name) {
        this.step(next -> {
            Scene scene = new Scene(name == null || name.isBlank() ? next.freeSceneName() : name.trim());
            next.addScene(next.sceneCount(), scene);
            next.selectScene(next.sceneCount() - 1);
            return "Add scene \"" + scene.name() + "\"";
        });
        return this.state.currentSceneIndex();
    }

    /**
     * Makes another scene the current one. This is not a step to undo, but undoing a step does return to the scene
     * that was current when the step was made.
     */
    public void selectScene(int index) {
        if (index == this.state.currentSceneIndex()) {
            this.state.scene(index);
            return;
        }
        EditState next = this.state.draft();
        next.selectScene(index);
        next.seal();
        if (this.groupBefore == this.state) {
            this.groupBefore = next;
        }
        this.state = next;
        this.announce("Select scene \"" + next.currentScene().name() + "\"");
    }

    public void renameScene(int index, String name) {
        this.step(next -> {
            String old = next.scene(index).name();
            next.scene(index).setName(name);
            return "Rename scene \"" + old + "\" to \"" + next.scene(index).name() + "\"";
        });
    }

    /**
     * Copies a scene with everything in it, puts the copy right after it and makes the copy current.
     *
     * @return the index of the copy
     */
    public int duplicateScene(int index) {
        this.step(next -> {
            Scene original = next.scene(index);
            Scene copy = original.copy();
            copy.setName(original.name() + " copy");
            next.addScene(index + 1, copy);
            next.selectScene(index + 1);
            return "Duplicate scene \"" + original.name() + "\"";
        });
        return index + 1;
    }

    /**
     * Puts back a scene that was taken out (from the bin, REC-9): a copy of it after the others, made current. When a
     * scene of the same name is there already, the one put back is called "Name (restored)", so neither is mistaken
     * for the other.
     *
     * @return the index it is at
     */
    public int putBackScene(Scene scene) {
        this.step(next -> {
            Scene copy = scene.copy();
            copy.setName(restoredName(next, scene.name()));
            next.addScene(next.sceneCount(), copy);
            next.selectScene(next.sceneCount() - 1);
            return "Restore scene \"" + copy.name() + "\"";
        });
        return this.state.currentSceneIndex();
    }

    /** The name a scene put back is given: its own, or one beside it that no scene has */
    public static String restoredName(EditState state, String name) {
        java.util.function.Predicate<String> taken = wanted -> state.sceneNames().contains(wanted);
        if (!taken.test(name)) {
            return name;
        }
        String restored = name + " (restored)";
        for (int number = 2; taken.test(restored); number++) {
            restored = name + " (restored " + number + ")";
        }
        return restored;
    }

    /** Removes a scene with everything in it. Refused for the last scene left */
    public void removeScene(int index) {
        this.step(next -> "Remove scene \"" + next.removeScene(index).name() + "\"");
    }

    /** Sets the ticks of the current scene that get exported, both included */
    public void setExportRange(int startTick, int endTick) {
        this.step(next -> {
            next.currentScene().setExportRange(startTick, endTick);
            return "Set export range to ticks " + startTick + " to " + endTick;
        });
    }

    public void clearExportRange() {
        this.edit("Clear export range", next -> next.currentScene().clearExportRange());
    }

    /** Lets the current scene show another recording of the same stretch (PLAY-19). Linking one already there renames it */
    public void linkRecording(LinkedRecording recording) {
        this.edit("Link the point of view of " + recording.label(), next -> next.currentScene().link(recording));
    }

    /**
     * Takes a linked recording out of the current scene. Its point of view keyframes stay, and show the scene's own
     * recording until it is linked again.
     */
    public void unlinkRecording(String recordingId) {
        this.step(next -> {
            LinkedRecording recording = next.currentScene().linked(recordingId);
            if (recording == null || !next.currentScene().unlink(recordingId)) {
                throw new IllegalArgumentException("The scene has no linked recording " + recordingId);
            }
            return "Unlink the point of view of " + recording.label();
        });
    }

    // ------------------------------------------------------------------ Tracks

    /**
     * Adds an empty track below the others.
     *
     * @return the index of the new track
     */
    public int addTrack(KeyframeKind kind) {
        this.step(next -> {
            next.currentScene().addTrack(new Track(kind));
            return "Add " + lower(kind) + " track";
        });
        return this.scene().tracks().size() - 1;
    }

    /** Removes a track and its keyframes; the tracks below move up by one */
    public void removeTrack(int track) {
        this.step(next -> "Remove track \"" + next.currentScene().removeTrack(track).title() + "\"");
    }

    /**
     * Renames a track and/or switches it on or off.
     *
     * @param name    null to leave the name alone, empty to go back to the name of the kind
     * @param enabled null to leave it alone
     */
    public void updateTrack(int track, @Nullable String name, @Nullable Boolean enabled) {
        this.step(next -> {
            Track changed = next.currentScene().track(track);
            if (name == null && enabled == null) {
                throw new IllegalArgumentException("Give 'name', 'enabled' or both: there is nothing to change otherwise");
            }
            String old = changed.title();
            if (name != null) {
                changed.setName(name);
            }
            if (enabled != null) {
                changed.setEnabled(enabled);
            }
            String switched = enabled == null ? "" : enabled ? "enable" : "disable";
            if (name == null) {
                return (enabled ? "Enable" : "Disable") + " track \"" + old + "\"";
            }
            return "Rename track \"" + old + "\" to \"" + changed.title() + "\"" + (enabled == null ? "" : " and " + switched + " it");
        });
    }

    /** Moves a track to another place in the order; index 0 is the top, which wins over the ones below */
    public void moveTrack(int from, int to) {
        this.step(next -> {
            Scene scene = next.currentScene();
            scene.track(from);
            if (to < 0 || to >= scene.tracks().size()) {
                throw new IllegalArgumentException("'to' must be a whole number from 0 to " + (scene.tracks().size() - 1) + ", got " + to);
            }
            Track moved = scene.removeTrack(from);
            scene.addTrack(to, moved);
            return "Move track \"" + moved.title() + "\" to position " + to;
        });
    }

    // ------------------------------------------------------------------ Keyframes

    /** Puts a keyframe on a track, in place of one already on its tick */
    public void setKeyframe(int track, Keyframe keyframe) {
        this.step(next -> put(next.currentScene().track(track), keyframe));
    }

    /**
     * Puts a keyframe on the uppermost track of its kind, adding a track when the scene has none.
     *
     * @return the index of the track it went on
     */
    public int setKeyframe(Keyframe keyframe) {
        int[] index = new int[1];
        this.step(next -> {
            Scene scene = next.currentScene();
            index[0] = scene.firstTrackOf(keyframe.kind());
            if (index[0] < 0) {
                index[0] = scene.addTrack(new Track(keyframe.kind()));
            }
            return put(scene.track(index[0]), keyframe);
        });
        return index[0];
    }

    private static String put(Track track, Keyframe keyframe) {
        boolean replaced = track.put(keyframe) != null;
        return (replaced ? "Replace " : "Add ") + lower(track.kind()) + " keyframe at tick " + keyframe.tick();
    }

    public void removeKeyframe(int track, int tick) {
        this.step(next -> {
            Track from = next.currentScene().track(track);
            if (from.remove(tick) == null) {
                throw missing(track, tick);
            }
            return "Remove " + lower(from.kind()) + " keyframe at tick " + tick;
        });
    }

    /** Removes a selection of keyframes in one step */
    public void removeKeyframes(Collection<KeyframeRef> selection) {
        Set<KeyframeRef> refs = new LinkedHashSet<>(selection);
        if (refs.size() == 1) {
            KeyframeRef only = refs.iterator().next();
            this.removeKeyframe(only.track(), only.tick());
            return;
        }
        this.step(next -> {
            for (KeyframeRef ref : refs) {
                if (next.currentScene().track(ref.track()).remove(ref.tick()) == null) {
                    throw missing(ref.track(), ref.tick());
                }
            }
            return "Remove " + count(refs.size());
        });
    }

    /** Moves a keyframe to another tick of its track, in place of one already there */
    public void moveKeyframe(int track, int fromTick, int toTick) {
        this.step(next -> {
            Track on = next.currentScene().track(track);
            Keyframe keyframe = on.at(fromTick);
            if (keyframe == null) {
                throw missing(track, fromTick);
            }
            Keyframe moved = moveTo(keyframe, toTick, "to_tick");
            on.remove(fromTick);
            on.put(moved);
            return "Move " + lower(on.kind()) + " keyframe from tick " + fromTick + " to tick " + toTick;
        });
    }

    /**
     * Moves a selection of keyframes by the same number of ticks (negative for earlier), each staying on its track.
     * Keyframes that are not part of the selection and sit where one lands are replaced.
     */
    public void moveKeyframes(Collection<KeyframeRef> selection, int tickOffset) {
        Set<KeyframeRef> refs = new LinkedHashSet<>(selection);
        this.step(next -> {
            Scene scene = next.currentScene();
            List<KeyframeClipboard.Entry> moved = new ArrayList<>();
            for (KeyframeRef ref : refs) {
                Keyframe keyframe = scene.track(ref.track()).at(ref.tick());
                if (keyframe == null) {
                    throw missing(ref.track(), ref.tick());
                }
                moved.add(new KeyframeClipboard.Entry(ref.track(), moveTo(keyframe, (long) ref.tick() + tickOffset, "offset")));
            }
            for (KeyframeRef ref : refs) {
                scene.track(ref.track()).remove(ref.tick());
            }
            for (KeyframeClipboard.Entry entry : moved) {
                scene.track(entry.track()).put(entry.keyframe());
            }
            return "Move " + count(refs.size()) + " by " + tickOffset + (Math.abs(tickOffset) == 1 ? " tick" : " ticks");
        });
    }

    /**
     * Sets how one keyframe blends towards the next.
     *
     * @param tick null for every keyframe of the track
     */
    public void setInterpolation(int track, @Nullable Integer tick, Interpolation interpolation) {
        if (interpolation == null) {
            throw new IllegalArgumentException("'interpolation' is required: one of " + Interpolation.allowed());
        }
        this.step(next -> {
            Track on = next.currentScene().track(track);
            if (tick == null) {
                for (Keyframe keyframe : List.copyOf(on.keyframes())) {
                    on.put(keyframe.withInterpolation(interpolation));
                }
                return "Set interpolation of all keyframes on track \"" + on.title() + "\" to " + interpolation.id();
            }
            Keyframe keyframe = on.at(tick);
            if (keyframe == null) {
                throw missing(track, tick);
            }
            on.put(keyframe.withInterpolation(interpolation));
            return "Set interpolation of " + lower(on.kind()) + " keyframe at tick " + tick + " to " + interpolation.id();
        });
    }

    /** Removes every keyframe of a track; the track stays */
    public void clearTrack(int track) {
        this.step(next -> {
            Track cleared = next.currentScene().track(track);
            cleared.clear();
            return "Clear track \"" + cleared.title() + "\"";
        });
    }

    /** Removes every keyframe of the current scene; the tracks stay */
    public void clearAllTracks() {
        this.edit("Clear all tracks", next -> {
            for (Track track : next.currentScene().tracks()) {
                track.clear();
            }
        });
    }

    /**
     * Copies a selection of keyframes. Changes nothing.
     *
     * @throws IllegalArgumentException when one of them does not exist
     */
    public KeyframeClipboard copy(Collection<KeyframeRef> selection) {
        List<KeyframeClipboard.Entry> entries = new ArrayList<>();
        for (KeyframeRef ref : new LinkedHashSet<>(selection)) {
            Keyframe keyframe = this.scene().track(ref.track()).at(ref.tick());
            if (keyframe == null) {
                throw missing(ref.track(), ref.tick());
            }
            entries.add(new KeyframeClipboard.Entry(ref.track(), keyframe));
        }
        return new KeyframeClipboard(entries);
    }

    /**
     * Pastes copied keyframes into the current scene, each moved by the same number of ticks from where it was
     * copied (to paste at a playhead, pass playhead minus {@link KeyframeClipboard#firstTick}). A keyframe goes back
     * on the track with the index it came from if that is still a track of its kind, else on the uppermost track of
     * its kind, else on a new track. Keyframes already on those ticks are replaced.
     */
    public void paste(KeyframeClipboard clipboard, int tickOffset) {
        if (clipboard.isEmpty()) {
            throw new IllegalArgumentException("There is nothing to paste: copy some keyframes first");
        }
        this.step(next -> {
            Scene scene = next.currentScene();
            for (KeyframeClipboard.Entry entry : clipboard.entries()) {
                Keyframe keyframe = moveTo(entry.keyframe(), (long) entry.keyframe().tick() + tickOffset, "offset");
                int index = entry.track();
                if (index >= scene.tracks().size() || scene.track(index).kind() != keyframe.kind()) {
                    index = scene.firstTrackOf(keyframe.kind());
                }
                if (index < 0) {
                    index = scene.addTrack(new Track(keyframe.kind()));
                }
                scene.track(index).put(keyframe);
            }
            return "Paste " + count(clipboard.size());
        });
    }

    // ------------------------------------------------------------------ Looks

    /**
     * Changes settings of the current scene's looks by name, see {@link Looks#setAll}. Nothing is changed if any name
     * or value is wrong.
     */
    public void setLooks(JsonObject values) {
        if (values == null || values.size() == 0) {
            throw new IllegalArgumentException("'values' is required: an object of setting name to new value, for example {\"night_vision\": true}");
        }
        this.step(next -> {
            next.currentScene().looks().setAll(values);
            return "Change " + String.join(", ", values.keySet());
        });
    }

    /** {@link #setLooks} for JSON text such as {"night_vision": true} */
    public void setLooksText(String values) {
        this.setLooks(JsonFields.object(values, "values"));
    }

    /** {@link #setEntityOverride} for JSON text such as {"hide": true} */
    public void setEntityOverrideText(UUID entity, String changes) {
        this.setEntityOverride(entity, JsonFields.object(changes, "changes"));
    }

    /** Changes the given fields of one entity's overrides in the current scene, see {@link EntityOverride#with} */
    public void setEntityOverride(UUID entity, JsonObject changes) {
        this.step(next -> {
            Looks looks = next.currentScene().looks();
            looks.setEntityOverride(entity, looks.entityOverride(entity).with(changes));
            return "Change entity " + entity + ": " + String.join(", ", changes.keySet());
        });
    }

    /**
     * Sets which kinds of entity and of particle are not drawn in the current scene. Each list given takes the place
     * of the old one; null leaves that one alone and an empty list shows everything again.
     */
    public void setRenderFilter(@Nullable List<String> entityTypes, @Nullable List<String> particleTypes) {
        JsonObject values = new JsonObject();
        if (entityTypes != null) {
            values.add("hidden_entity_types", JsonFields.array(entityTypes));
        }
        if (particleTypes != null) {
            values.add("hidden_particle_types", JsonFields.array(particleTypes));
        }
        this.setLooks(values);
    }

    // ------------------------------------------------------------------ Helpers

    private static Keyframe moveTo(Keyframe keyframe, long tick, String field) {
        if (tick < 0 || tick > Keyframe.MAX_TICK) {
            throw new IllegalArgumentException("'" + field + "' would put a keyframe on tick " + tick + "; ticks go from 0 to " + Keyframe.MAX_TICK);
        }
        return keyframe.withTick((int) tick);
    }

    private static IllegalArgumentException missing(int track, int tick) {
        return new IllegalArgumentException("There is no keyframe at tick " + tick + " on track " + track);
    }

    private static String lower(KeyframeKind kind) {
        return kind == KeyframeKind.SMART_CAMERA ? kind.title() : kind.title().toLowerCase(Locale.ROOT);
    }

    private static String count(int keyframes) {
        return keyframes + (keyframes == 1 ? " keyframe" : " keyframes");
    }

}
