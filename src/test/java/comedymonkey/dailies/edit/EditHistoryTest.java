package comedymonkey.dailies.edit;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Changing the editor state in steps that can be undone and redone (specification EDIT-6, and EDIT-1 to EDIT-3) */
class EditHistoryTest {

    private static final UUID ENTITY = UUID.fromString("0b1e6f0e-5a52-4c61-9a37-0c1d2e3f4a5b");

    private static Keyframe fov(int tick, double degrees) {
        return new Keyframe.FieldOfView(tick, Interpolation.LINEAR, degrees);
    }

    private static Keyframe camera(int tick) {
        return new Keyframe.Camera(tick, Interpolation.SMOOTH, tick, 64, 0, 0, 0, 0);
    }

    private static String saved(EditHistory history) {
        return new String(history.state().toBytes(), StandardCharsets.UTF_8);
    }

    private static List<Integer> ticks(EditHistory history, int track) {
        return history.scene().track(track).keyframes().stream().map(Keyframe::tick).toList();
    }

    @Test
    void everyStepIsDescribedAndCanBeUndoneAndRedone() {
        EditHistory history = new EditHistory();
        List<String> states = new ArrayList<>(List.of(saved(history)));
        List<String> descriptions = new ArrayList<>();

        history.addScene("Chase");
        history.renameScene(1, "The chase");
        history.setExportRange(100, 900);
        int cameraTrack = history.addTrack(KeyframeKind.CAMERA);
        history.setKeyframe(cameraTrack, camera(120));
        history.setKeyframe(cameraTrack, camera(120));
        history.setKeyframe(cameraTrack, camera(200));
        int fovTrack = history.setKeyframe(fov(50, 30));
        history.updateTrack(fovTrack, "Zoom", null);
        history.updateTrack(fovTrack, null, false);
        history.updateTrack(cameraTrack, "Main", true);
        history.moveTrack(1, 0);
        history.moveKeyframe(1, 120, 150);
        history.setInterpolation(1, 150, Interpolation.EASE_IN);
        history.setInterpolation(1, null, Interpolation.HOLD);
        history.removeKeyframe(1, 200);
        history.clearTrack(1);
        history.setLooksText("{\"night_vision\": true, \"weather\": \"clear\"}");
        history.setEntityOverrideText(ENTITY, "{\"hide\": true}");
        history.setRenderFilter(List.of("minecraft:bat"), null);
        history.clearAllTracks();
        history.removeTrack(0);
        history.clearExportRange();
        history.duplicateScene(1);
        history.removeScene(0);

        List<String> expected = List.of(
                "Add scene \"Chase\"",
                "Rename scene \"Chase\" to \"The chase\"",
                "Set export range to ticks 100 to 900",
                "Add camera track",
                "Add camera keyframe at tick 120",
                "Replace camera keyframe at tick 120",
                "Add camera keyframe at tick 200",
                "Add field of view keyframe at tick 50",
                "Rename track \"Field of view\" to \"Zoom\"",
                "Disable track \"Zoom\"",
                "Rename track \"Camera\" to \"Main\" and enable it",
                "Move track \"Zoom\" to position 0",
                "Move camera keyframe from tick 120 to tick 150",
                "Set interpolation of camera keyframe at tick 150 to ease_in",
                "Set interpolation of all keyframes on track \"Main\" to hold",
                "Remove camera keyframe at tick 200",
                "Clear track \"Main\"",
                "Change night_vision, weather",
                "Change entity " + ENTITY + ": hide",
                "Change hidden_entity_types",
                "Clear all tracks",
                "Remove track \"Zoom\"",
                "Clear export range",
                "Duplicate scene \"The chase\"",
                "Remove scene \"Scene 1\"");
        List<String> undoable = new ArrayList<>(history.undoDescriptions());
        java.util.Collections.reverse(undoable);
        assertEquals(expected, undoable);
        assertEquals(expected.size(), history.revision());

        // Walk all the way back, remembering every state, then all the way forward again
        List<String> back = new ArrayList<>(List.of(saved(history)));
        while (history.canUndo()) {
            descriptions.add(history.undo());
            back.add(saved(history));
        }
        assertNull(history.undo());
        java.util.Collections.reverse(descriptions);
        assertEquals(expected, descriptions);
        assertEquals(states.get(0), saved(history));
        assertEquals(expected.size(), history.redoDescriptions().size());
        assertEquals(expected.get(0), history.redoDescription());

        for (int i = back.size() - 2; i >= 0; i--) {
            assertEquals(expected.get(back.size() - 2 - i), history.redo());
            assertEquals(back.get(i), saved(history));
        }
        assertNull(history.redo());
        assertFalse(history.canRedo());
        assertEquals(2, history.state().scenes().size());
        assertEquals("The chase copy", history.scene().name());
    }

    @Test
    void aNewEditForgetsWhatWasUndone() {
        EditHistory history = new EditHistory();
        history.setKeyframe(fov(10, 30));
        history.setKeyframe(fov(20, 40));
        history.undo();
        assertTrue(history.canRedo());
        assertEquals("Add field of view keyframe at tick 20", history.redoDescription());
        history.setKeyframe(fov(30, 50));
        assertFalse(history.canRedo());
        assertNull(history.redoDescription());
        assertEquals(List.of(10, 30), ticks(history, 0));
        assertEquals("Add field of view keyframe at tick 30", history.undoDescription());
    }

    @Test
    void undoHasNoLimit() {
        EditHistory history = new EditHistory();
        for (int tick = 0; tick < 3000; tick++) {
            history.setKeyframe(fov(tick, 70));
        }
        assertEquals(3000, history.scene().track(0).size());
        int undone = 0;
        while (history.undo() != null) {
            undone++;
        }
        assertEquals(3000, undone);
        assertTrue(history.scene().tracks().isEmpty());
    }

    @Test
    void aFailedChangeChangesNothing() {
        EditHistory history = new EditHistory();
        history.setKeyframe(fov(10, 30));
        String before = saved(history);
        long revision = history.revision();
        List<String> heard = new ArrayList<>();
        history.addListener((changed, description) -> heard.add(description));

        assertEquals("'track' must be the index of a track, from 0 to 0, got 3",
                assertThrows(IllegalArgumentException.class, () -> history.setKeyframe(3, fov(1, 30))).getMessage());
        assertEquals("A camera keyframe cannot go on a field_of_view track",
                assertThrows(IllegalArgumentException.class, () -> history.setKeyframe(0, camera(1))).getMessage());
        assertEquals("There is no keyframe at tick 11 on track 0",
                assertThrows(IllegalArgumentException.class, () -> history.removeKeyframe(0, 11)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> history.moveKeyframe(0, 11, 12));
        assertThrows(IllegalArgumentException.class, () -> history.moveKeyframe(0, 10, -1));
        assertThrows(IllegalArgumentException.class, () -> history.moveKeyframes(List.of(new KeyframeRef(0, 10)), -11));
        assertThrows(IllegalArgumentException.class, () -> history.removeKeyframes(List.of(new KeyframeRef(0, 10), new KeyframeRef(0, 11))));
        assertThrows(IllegalArgumentException.class, () -> history.setInterpolation(0, 11, Interpolation.HOLD));
        assertThrows(IllegalArgumentException.class, () -> history.removeScene(0));
        assertThrows(IllegalArgumentException.class, () -> history.removeScene(4));
        assertThrows(IllegalArgumentException.class, () -> history.selectScene(4));
        assertThrows(IllegalArgumentException.class, () -> history.renameScene(0, " "));
        assertThrows(IllegalArgumentException.class, () -> history.setExportRange(50, 10));
        assertThrows(IllegalArgumentException.class, () -> history.removeTrack(1));
        assertThrows(IllegalArgumentException.class, () -> history.moveTrack(0, 1));
        assertThrows(IllegalArgumentException.class, () -> history.updateTrack(0, null, null));
        assertThrows(IllegalArgumentException.class, () -> history.clearTrack(-1));
        assertThrows(IllegalArgumentException.class, () -> history.setLooksText("{\"sky\": false, \"fov\": 999}"));
        assertThrows(IllegalArgumentException.class, () -> history.setLooksText("{}"));
        assertThrows(IllegalArgumentException.class, () -> history.setEntityOverrideText(ENTITY, "{\"hide\": true, \"glowing\": \"pink\"}"));
        assertThrows(IllegalStateException.class, () -> history.edit("Break", state -> {
            state.currentScene().track(0).clear();
            throw new IllegalStateException("half way");
        }));

        assertEquals(before, saved(history));
        assertEquals(revision, history.revision());
        assertEquals(List.of("Add field of view keyframe at tick 10"), history.undoDescriptions());
        assertTrue(heard.isEmpty());
    }

    @Test
    void listenersHearEveryChangeAndTheRevisionOnlyGoesUp() {
        EditHistory history = new EditHistory();
        List<String> heard = new ArrayList<>();
        List<Long> revisions = new ArrayList<>();
        EditHistory.Listener listener = (changed, description) -> {
            heard.add(description);
            revisions.add(changed.revision());
        };
        history.addListener(listener);
        assertEquals(0, history.revision());

        history.setKeyframe(fov(10, 30));
        history.addScene(null);
        history.selectScene(0);
        history.selectScene(0);
        history.undo();
        history.undo();
        history.redo();
        assertEquals(List.of("Add field of view keyframe at tick 10", "Add scene \"Scene 2\"", "Select scene \"Scene 1\"", "Undo: Add scene \"Scene 2\"",
                "Undo: Add field of view keyframe at tick 10", "Redo: Add field of view keyframe at tick 10"), heard);
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), revisions);

        history.removeListener(listener);
        history.undo();
        assertEquals(6, heard.size());
        assertEquals(7, history.revision());
    }

    @Test
    void theStateHandedOutIsNeverAlteredByLaterChanges() {
        EditHistory history = new EditHistory();
        history.setKeyframe(fov(10, 30));
        EditState before = history.state();
        String text = new String(before.toBytes(), StandardCharsets.UTF_8);
        history.setKeyframe(fov(20, 30));
        history.setLooksText("{\"sky\": false}");
        assertNotSame(before, history.state());
        assertEquals(text, new String(before.toBytes(), StandardCharsets.UTF_8));

        // And the history does not follow changes to the state it was made from
        EditState loaded = EditStateTest.everything();
        EditHistory other = new EditHistory(loaded);
        loaded.scene(0).setName("Changed behind its back");
        assertEquals("Opening", other.state().scene(0).name());
        assertEquals(1, other.state().currentSceneIndex());
    }

    @Test
    void scenesAreAddedSelectedDuplicatedAndRemoved() {
        EditHistory history = new EditHistory();
        history.setKeyframe(fov(10, 30));
        history.setExportRange(0, 50);
        history.setLooksText("{\"night_vision\": true}");

        assertEquals(1, history.addScene(null));
        assertEquals("Scene 2", history.scene().name());
        assertTrue(history.scene().tracks().isEmpty());
        assertEquals(2, history.addScene("  Finale "));
        assertEquals("Finale", history.scene().name());
        assertEquals(2, history.state().currentSceneIndex());

        assertEquals(1, history.duplicateScene(0));
        assertEquals(List.of("Scene 1", "Scene 1 copy", "Scene 2", "Finale"), history.state().scenes().stream().map(Scene::name).toList());
        assertEquals(1, history.state().currentSceneIndex());
        assertEquals(List.of(10), ticks(history, 0));
        assertEquals(50, history.scene().exportEnd());
        assertTrue(history.scene().looks().nightVision);
        // The copy is its own scene
        history.setKeyframe(fov(99, 30));
        assertEquals(1, history.state().scene(0).track(0).size());

        history.selectScene(3);
        assertEquals("Finale", history.scene().name());
        history.removeScene(3);
        assertEquals("Scene 2", history.scene().name());
        history.removeScene(0);
        history.removeScene(0);
        assertEquals(List.of("Scene 2"), history.state().scenes().stream().map(Scene::name).toList());
        assertThrows(IllegalArgumentException.class, () -> history.removeScene(0));

        // Undoing a step goes back to the scene it was made in
        history.undo();
        history.undo();
        history.undo();
        assertEquals("Finale", history.scene().name());
        assertEquals(4, history.state().scenes().size());
    }

    @Test
    void tracksAreAddedMovedUpdatedAndRemoved() {
        EditHistory history = new EditHistory();
        assertEquals(0, history.addTrack(KeyframeKind.CAMERA));
        assertEquals(1, history.addTrack(KeyframeKind.CAMERA));
        assertEquals(2, history.addTrack(KeyframeKind.SPEED));
        history.updateTrack(1, "Close-ups", null);
        // A keyframe given without a track goes on the uppermost track of its kind
        assertEquals(0, history.setKeyframe(camera(5)));
        assertEquals(2, history.setKeyframe(new Keyframe.Speed(5, Interpolation.LINEAR, 10)));
        assertEquals(3, history.setKeyframe(fov(5, 30)));

        history.moveTrack(1, 0);
        assertEquals(List.of("Close-ups", "Camera", "Speed", "Field of view"), history.scene().tracks().stream().map(Track::title).toList());
        assertEquals(1, history.scene().track(0).size() + history.scene().track(1).size());
        history.moveTrack(0, 3);
        assertEquals(List.of("Camera", "Speed", "Field of view", "Close-ups"), history.scene().tracks().stream().map(Track::title).toList());

        history.updateTrack(3, "", false);
        assertEquals("Camera", history.scene().track(3).title());
        assertFalse(history.scene().track(3).enabled());
        assertEquals("Rename track \"Close-ups\" to \"Camera\" and disable it", history.undoDescription());

        history.removeTrack(0);
        assertEquals(List.of(KeyframeKind.SPEED, KeyframeKind.FIELD_OF_VIEW, KeyframeKind.CAMERA), history.scene().tracks().stream().map(Track::kind).toList());
        history.undo();
        assertEquals(List.of(5), ticks(history, 0));
    }

    @Test
    void keyframesAreMovedReplacingWhatIsThere() {
        EditHistory history = new EditHistory();
        history.setKeyframe(fov(10, 10));
        history.setKeyframe(fov(20, 20));
        history.setKeyframe(fov(30, 30));
        history.setKeyframe(fov(40, 40));

        history.moveKeyframe(0, 10, 30);
        assertEquals(List.of(20, 30, 40), ticks(history, 0));
        assertEquals(fov(30, 10), history.scene().track(0).at(30));
        history.undo();
        assertEquals(List.of(10, 20, 30, 40), ticks(history, 0));
        assertEquals(fov(30, 30), history.scene().track(0).at(30));

        // A selection moves as one: 10 and 20 go to 20 and 30, and neither is lost on the way
        history.moveKeyframes(List.of(new KeyframeRef(0, 10), new KeyframeRef(0, 20)), 10);
        assertEquals(List.of(20, 30, 40), ticks(history, 0));
        assertEquals(fov(20, 10), history.scene().track(0).at(20));
        assertEquals(fov(30, 20), history.scene().track(0).at(30));
        assertEquals("Move 2 keyframes by 10 ticks", history.undoDescription());
        history.moveKeyframes(List.of(new KeyframeRef(0, 20), new KeyframeRef(0, 30)), -20);
        assertEquals(List.of(0, 10, 40), ticks(history, 0));

        history.removeKeyframes(List.of(new KeyframeRef(0, 0), new KeyframeRef(0, 40)));
        assertEquals(List.of(10), ticks(history, 0));
        assertEquals("Remove 2 keyframes", history.undoDescription());
        history.undo();
        assertEquals(List.of(0, 10, 40), ticks(history, 0));
    }

    @Test
    void interpolationIsSetOnOneKeyframeOrAllOfATrack() {
        EditHistory history = new EditHistory();
        history.setKeyframe(fov(10, 10));
        history.setKeyframe(fov(20, 20));
        history.setInterpolation(0, 20, Interpolation.SMOOTH);
        assertEquals(Interpolation.LINEAR, history.scene().track(0).at(10).interpolation());
        assertEquals(Interpolation.SMOOTH, history.scene().track(0).at(20).interpolation());
        history.setInterpolation(0, null, Interpolation.EASE_OUT);
        assertTrue(history.scene().track(0).keyframes().stream().allMatch(keyframe -> keyframe.interpolation() == Interpolation.EASE_OUT));
        assertEquals(20.0, ((Keyframe.FieldOfView) history.scene().track(0).at(20)).fov());
    }

    @Test
    void clearingKeepsTheTracks() {
        EditHistory history = new EditHistory();
        history.setKeyframe(fov(10, 10));
        history.setKeyframe(camera(10));
        history.setKeyframe(camera(20));
        history.clearTrack(1);
        assertEquals(List.of(), ticks(history, 1));
        assertEquals(List.of(10), ticks(history, 0));
        history.undo();
        history.clearAllTracks();
        assertEquals(2, history.scene().tracks().size());
        assertTrue(history.scene().tracks().stream().allMatch(Track::isEmpty));
        history.undo();
        assertEquals(List.of(10, 20), ticks(history, 1));
    }

    @Test
    void keyframesAreCopiedAndPastedWithAnOffset() {
        EditHistory history = new EditHistory();
        history.setKeyframe(fov(10, 10));
        history.setKeyframe(fov(20, 20));
        history.setKeyframe(camera(15));
        history.setKeyframe(fov(120, 99));

        KeyframeClipboard clipboard = history.copy(List.of(new KeyframeRef(0, 10), new KeyframeRef(0, 20), new KeyframeRef(1, 15)));
        assertEquals(3, clipboard.size());
        assertEquals(10, clipboard.firstTick());
        assertEquals(20, clipboard.lastTick());
        long revision = history.revision();
        assertThrows(IllegalArgumentException.class, () -> history.copy(List.of(new KeyframeRef(0, 11))));
        assertEquals(revision, history.revision());

        history.paste(clipboard, 100);
        assertEquals("Paste 3 keyframes", history.undoDescription());
        assertEquals(List.of(10, 20, 110, 120), ticks(history, 0));
        assertEquals(List.of(15, 115), ticks(history, 1));
        assertEquals(fov(110, 10), history.scene().track(0).at(110));
        // What was on the tick is replaced
        assertEquals(fov(120, 20), history.scene().track(0).at(120));

        // The clipboard can be used again, also in another scene, where the tracks are made as needed
        history.addScene("Other");
        history.addTrack(KeyframeKind.SPEED);
        history.paste(clipboard, -10);
        assertEquals(List.of(KeyframeKind.SPEED, KeyframeKind.FIELD_OF_VIEW, KeyframeKind.CAMERA), history.scene().tracks().stream().map(Track::kind).toList());
        assertEquals(List.of(0, 10), ticks(history, 1));
        assertEquals(List.of(5), ticks(history, 2));

        assertThrows(IllegalArgumentException.class, () -> history.paste(clipboard, -11));
        assertThrows(IllegalArgumentException.class, () -> history.paste(history.copy(List.of()), 0));
        history.undo();
        assertEquals(1, history.scene().tracks().size());
    }

    @Test
    void looksChangesAreSteps() {
        EditHistory history = new EditHistory();
        history.setLooksText("{\"fog_mode\": \"custom\", \"fog_end\": 48, \"fog_colour\": [0, 0, 0]}");
        history.setEntityOverrideText(ENTITY, "{\"name_override\": \"Hero\", \"hide_cape\": true}");
        history.setEntityOverrideText(ENTITY, "{\"glowing\": \"force_glow\"}");
        history.setRenderFilter(List.of("minecraft:bat", "minecraft:item"), List.of("minecraft:rain"));

        Looks looks = history.scene().looks();
        assertEquals(Looks.FogMode.CUSTOM, looks.fogMode);
        assertEquals(48.0, looks.fogEnd);
        assertEquals(0x000000, looks.fogColour);
        assertEquals(new EntityOverride(false, "Hero", EntityOverride.Glow.FORCE_GLOW, false, true, false), looks.entityOverride(ENTITY));
        assertEquals(List.of("minecraft:bat", "minecraft:item"), looks.hiddenEntityTypes);
        assertEquals(List.of("minecraft:rain"), looks.hiddenParticleTypes);

        history.setRenderFilter(List.of(), null);
        assertTrue(history.scene().looks().hiddenEntityTypes.isEmpty());
        assertEquals(List.of("minecraft:rain"), history.scene().looks().hiddenParticleTypes);

        history.undo();
        history.undo();
        history.undo();
        assertEquals(new EntityOverride(false, "Hero", EntityOverride.Glow.DEFAULT, false, true, false), history.scene().looks().entityOverride(ENTITY));
        assertTrue(history.scene().looks().hiddenEntityTypes.isEmpty());
        history.undo();
        history.undo();
        assertEquals(Looks.FogMode.AS_RECORDED, history.scene().looks().fogMode);
        // Looks belong to their scene
        history.redo();
        history.addScene(null);
        assertEquals(Looks.FogMode.AS_RECORDED, history.scene().looks().fogMode);
    }

    @Test
    void aGroupOfChangesIsOneStep() {
        EditHistory history = new EditHistory();
        history.setKeyframe(fov(10, 30));
        List<String> heard = new ArrayList<>();
        history.addListener((changed, description) -> heard.add(description));

        history.beginGroup("Drag field of view keyframe");
        history.moveKeyframe(0, 10, 11);
        history.moveKeyframe(0, 11, 12);
        history.moveKeyframe(0, 12, 25);
        history.endGroup();
        assertEquals(List.of(25), ticks(history, 0));
        assertEquals(3, heard.size());
        assertEquals(List.of("Drag field of view keyframe", "Add field of view keyframe at tick 10"), history.undoDescriptions());

        assertEquals("Drag field of view keyframe", history.undo());
        assertEquals(List.of(10), ticks(history, 0));
        assertEquals("Drag field of view keyframe", history.redo());
        assertEquals(List.of(25), ticks(history, 0));

        // A group in which nothing happened leaves no step, and undo during a group closes it first
        history.beginGroup("Nothing");
        history.endGroup();
        assertEquals(2, history.undoDescriptions().size());
        history.beginGroup("Drag again");
        history.moveKeyframe(0, 25, 30);
        assertTrue(history.canUndo());
        assertEquals("Drag again", history.undo());
        assertEquals(List.of(25), ticks(history, 0));
        history.setKeyframe(fov(1, 30));
        assertEquals("Add field of view keyframe at tick 1", history.undoDescription());
    }

    @Test
    void anyOtherChangeCanBeMadeAsAStep() {
        EditHistory history = new EditHistory();
        history.edit("Add three tracks", state -> {
            for (int i = 0; i < 3; i++) {
                state.currentScene().addTrack(new Track(KeyframeKind.AUDIO));
            }
        });
        assertEquals(3, history.scene().tracks().size());
        assertEquals("Add three tracks", history.undo());
        assertTrue(history.scene().tracks().isEmpty());
    }

}
