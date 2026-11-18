package comedymonkey.dailies.edit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A scene put back from the bin (specification REC-9): a step that can be undone, under a name no other scene has */
class SceneRestoreTest {

    @Test
    void aSceneComesBackAfterTheOthersAndCanBeUndone() {
        EditHistory history = new EditHistory(new EditState());
        history.addScene("Opening");
        Scene kept = history.state().scene(1).copy();
        history.removeScene(1);
        assertEquals(1, history.state().scenes().size());
        int at = history.putBackScene(kept);
        assertEquals(1, at);
        assertEquals("Opening", history.state().scene(1).name());
        assertEquals(1, history.state().currentSceneIndex());
        assertTrue(history.canUndo());
        history.undo();
        assertEquals(1, history.state().scenes().size());
    }

    @Test
    void aTakenNameIsMarkedAsRestored() {
        EditState state = new EditState();
        String first = state.scene(0).name();
        assertEquals("Elsewhere", EditHistory.restoredName(state, "Elsewhere"));
        assertEquals(first + " (restored)", EditHistory.restoredName(state, first));
        state.addScene(1, new Scene(first + " (restored)"));
        assertEquals(first + " (restored 2)", EditHistory.restoredName(state, first));
    }

}
