package comedymonkey.dailies.edit;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A scene with several points of view (PLAY-19, EDIT-11): linked recordings, and keyframes that cut between them */
class PointOfViewTest {

    private static final String OWN = "own-id";
    private static final String ALEX = "alex-id";
    private static final String SAM = "sam-id";

    private static Scene scene() {
        Scene scene = new Scene("Raid");
        scene.link(new LinkedRecording(ALEX, "Alex evening", "Alex"));
        scene.link(new LinkedRecording(SAM, "", "Sam"));
        Track views = new Track(KeyframeKind.POINT_OF_VIEW);
        views.put(new Keyframe.PointOfView(100, Interpolation.HOLD, ALEX, "Alex"));
        views.put(new Keyframe.PointOfView(300, Interpolation.HOLD, OWN, ""));
        views.put(new Keyframe.PointOfView(400, Interpolation.HOLD, SAM, "Sam"));
        views.put(new Keyframe.PointOfView(450, Interpolation.HOLD, SAM, "Sam"));
        scene.addTrack(views);
        return scene;
    }

    @Test
    void theKeyframeInForceSaysWhichRecordingIsShown() {
        Scene scene = scene();
        assertNull(Evaluator.pointOfView(scene, 99.9, OWN), "before the first keyframe: the scene's own");
        assertEquals(ALEX, Evaluator.pointOfView(scene, 100, OWN));
        assertEquals(ALEX, Evaluator.pointOfView(scene, 299.5, OWN), "a cut, never a blend");
        assertNull(Evaluator.pointOfView(scene, 300, OWN), "naming the scene's own recording");
        assertEquals(SAM, Evaluator.pointOfView(scene, 10_000, OWN));
    }

    @Test
    void aKeyframeForARecordingNoLongerLinkedShowsTheScenesOwn() {
        Scene scene = scene();
        assertTrue(scene.unlink(ALEX));
        assertFalse(scene.unlink(ALEX));
        assertNull(Evaluator.pointOfView(scene, 150, OWN));
    }

    @Test
    void switchesAreListedOnlyWhereTheRecordingChanges() {
        List<Evaluator.Switch> switches = Evaluator.pointOfViewSwitches(scene(), 0, 1000, OWN);
        assertEquals(List.of(new Evaluator.Switch(100, ALEX), new Evaluator.Switch(300, null), new Evaluator.Switch(400, SAM)), switches,
            "the keyframe at 450 names the same recording again");
        assertEquals(List.of(new Evaluator.Switch(400, SAM)), Evaluator.pointOfViewSwitches(scene(), 301, 1000, OWN));
        assertEquals(List.of(), Evaluator.pointOfViewSwitches(scene(), 120, 290, OWN), "already showing Alex's");
    }

    @Test
    void aDisabledTrackSwitchesNothing() {
        Scene scene = scene();
        scene.track(0).setEnabled(false);
        assertNull(Evaluator.pointOfView(scene, 150, OWN));
        assertTrue(Evaluator.pointOfViewSwitches(scene, 0, 1000, OWN).isEmpty());
    }

    @Test
    void theKeyframeReadsAndWritesTheUsualShape() {
        Keyframe keyframe = Keyframe.fromJsonText(KeyframeKind.POINT_OF_VIEW, "{\"tick\":40,\"recording_id\":\" alex-id \",\"label\":\"Alex\"}");
        assertEquals(new Keyframe.PointOfView(40, Interpolation.LINEAR, ALEX, "Alex"), keyframe);
        JsonObject json = keyframe.toJson();
        assertEquals(ALEX, json.get("recording_id").getAsString());
        assertEquals(keyframe, Keyframe.fromJson(KeyframeKind.POINT_OF_VIEW, json));
        assertEquals(KeyframeKind.POINT_OF_VIEW, KeyframeKind.find("pov"));
        assertFalse(KeyframeKind.POINT_OF_VIEW.blends());
        assertFalse(KeyframeKind.POINT_OF_VIEW.drivesCamera(), "the camera carries across a switch");
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> Keyframe.fromJsonText(KeyframeKind.POINT_OF_VIEW, "{\"tick\":1}"));
        assertTrue(refused.getMessage().contains("recording_id"), refused.getMessage());
        assertEquals("", Keyframe.fromJsonText(KeyframeKind.POINT_OF_VIEW, "{\"recording_id\":\"x\"}").toJson().get("label").getAsString());
    }

    @Test
    void linkedRecordingsAreSavedWithTheSceneAndCopied() {
        Scene scene = scene();
        Scene read = Scene.fromJson(scene.toJson(), "fallback");
        assertEquals(scene.linked(), read.linked());
        assertEquals("Sam", read.linked(SAM).label());
        assertEquals("Alex", read.linked(ALEX).label());
        Scene copy = scene.copy();
        copy.unlink(SAM);
        assertEquals(2, scene.linked().size(), "a copy is a separate scene");
        assertFalse(scene.link(new LinkedRecording(ALEX, "Renamed", "Alex")), "linking again renames");
        assertEquals("Renamed", scene.linked(ALEX).name());

        JsonObject old = new Scene("Old").toJson();
        assertFalse(old.has("linked"), "a scene without links is saved as before");
        JsonObject broken = scene.toJson();
        broken.getAsJsonArray("linked").add("not an object");
        assertEquals(2, Scene.fromJson(broken, "x").linked().size(), "a bad entry costs only itself");
    }

    @Test
    void linkingAndUnlinkingCanBeUndone() {
        EditHistory history = new EditHistory();
        history.linkRecording(new LinkedRecording(ALEX, "", "Alex"));
        assertEquals(1, history.scene().linked().size());
        assertEquals("Link the point of view of Alex", history.undoDescription());
        history.unlinkRecording(ALEX);
        assertTrue(history.scene().linked().isEmpty());
        history.undo();
        assertEquals(1, history.scene().linked().size());
        history.undo();
        assertTrue(history.scene().linked().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> history.unlinkRecording(SAM));
        assertFalse(history.canRedo() && history.redoDescription() == null);
    }

}
