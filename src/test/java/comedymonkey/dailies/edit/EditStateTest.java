package comedymonkey.dailies.edit;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Scenes, tracks and how the editor state is saved (specification EDIT-1 to EDIT-3, EDIT-7, FILE-8) */
class EditStateTest {

    private static final UUID ENTITY = UUID.fromString("0b1e6f0e-5a52-4c61-9a37-0c1d2e3f4a5b");

    /** Two scenes; the first holds a track of every kind with its example keyframe, and changed looks */
    static EditState everything() {
        EditState state = new EditState();
        Scene scene = state.currentScene();
        scene.setName("Opening");
        scene.setExportRange(20, 400);
        for (KeyframeKind kind : KeyframeKind.values()) {
            Track track = new Track(kind);
            track.put(Keyframe.example(kind));
            track.put(Keyframe.example(kind).withTick(300).withInterpolation(Interpolation.EASE_OUT));
            scene.addTrack(track);
        }
        scene.track(1).setName("Wide circle");
        scene.track(2).setEnabled(false);
        Looks looks = scene.looks();
        looks.showHud = false;
        looks.weather = Looks.Weather.THUNDER;
        looks.fogMode = Looks.FogMode.CUSTOM;
        looks.fogStart = 8;
        looks.fogEnd = 64;
        looks.fogColour = 0x102030;
        looks.sky = false;
        looks.backgroundColour = 0xFF00FF;
        looks.fov = 55;
        looks.aspectGuide = Looks.AspectGuide.CINEMA_21_9;
        looks.hiddenEntityTypes = List.of("minecraft:bat", "minecraft:item");
        looks.setEntityOverride(ENTITY, new EntityOverride(true, "The Stranger", EntityOverride.Glow.FORCE_GLOW, true, false, true));
        state.addScene(1, new Scene("Ending"));
        state.selectScene(1);
        return state;
    }

    private static String text(EditState state) {
        return new String(state.toBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void aFreshStateHasOneEmptyScene() {
        EditState state = new EditState();
        assertEquals(1, state.scenes().size());
        assertEquals("Scene 1", state.currentScene().name());
        assertTrue(state.currentScene().tracks().isEmpty());
        assertFalse(state.currentScene().hasExportRange());
    }

    @Test
    void theLastSceneCannotBeRemoved() {
        EditState state = new EditState();
        String message = assertThrows(IllegalArgumentException.class, () -> state.removeScene(0)).getMessage();
        assertTrue(message.contains("last scene"), message);
        state.addScene(1, new Scene("Second"));
        state.removeScene(0);
        assertEquals("Second", state.currentScene().name());
        assertEquals(0, state.currentSceneIndex());
    }

    @Test
    void removingAndAddingScenesKeepsTheCurrentOneCurrent() {
        EditState state = new EditState();
        state.addScene(1, new Scene("B"));
        state.addScene(2, new Scene("C"));
        state.selectScene(1);
        state.addScene(0, new Scene("Before"));
        assertEquals("B", state.currentScene().name());
        state.removeScene(0);
        assertEquals("B", state.currentScene().name());
        state.removeScene(2);
        assertEquals("B", state.currentScene().name());
        state.removeScene(1);
        assertEquals("Scene 1", state.currentScene().name());
    }

    @Test
    void aTrackKeepsItsKeyframesInTickOrderOneToATick() {
        Track track = new Track(KeyframeKind.FIELD_OF_VIEW);
        track.put(new Keyframe.FieldOfView(50, Interpolation.LINEAR, 50));
        track.put(new Keyframe.FieldOfView(10, Interpolation.LINEAR, 10));
        track.put(new Keyframe.FieldOfView(30, Interpolation.LINEAR, 30));
        assertEquals(List.of(10, 30, 50), track.keyframes().stream().map(Keyframe::tick).toList());

        Keyframe replaced = track.put(new Keyframe.FieldOfView(30, Interpolation.HOLD, 99));
        assertEquals(new Keyframe.FieldOfView(30, Interpolation.LINEAR, 30), replaced);
        assertEquals(3, track.size());
        assertEquals(new Keyframe.FieldOfView(30, Interpolation.HOLD, 99), track.at(30));

        assertNull(track.at(31));
        assertNull(track.atOrBefore(9.9));
        assertEquals(10, track.atOrBefore(29.9).tick());
        assertEquals(30, track.atOrBefore(30).tick());
        assertEquals(50, track.atOrBefore(1e9).tick());
        assertEquals(10, track.firstTick());
        assertEquals(50, track.lastTick());

        assertEquals(30, track.remove(30).tick());
        assertNull(track.remove(30));
        assertThrows(UnsupportedOperationException.class, () -> track.keyframes().clear());
    }

    @Test
    void aTrackHoldsOnlyItsOwnKind() {
        Track track = new Track(KeyframeKind.SPEED);
        String message = assertThrows(IllegalArgumentException.class, () -> track.put(new Keyframe.FieldOfView(0, Interpolation.LINEAR, 70))).getMessage();
        assertEquals("A field_of_view keyframe cannot go on a speed track", message);
    }

    @Test
    void exportRangeIsChecked() {
        Scene scene = new Scene("S");
        assertThrows(IllegalArgumentException.class, () -> scene.setExportRange(-1, 10));
        String message = assertThrows(IllegalArgumentException.class, () -> scene.setExportRange(50, 10)).getMessage();
        assertTrue(message.contains("'end_tick'"), message);
        assertFalse(scene.hasExportRange());
        scene.setExportRange(10, 10);
        assertEquals(10, scene.exportStart());
        scene.clearExportRange();
        assertEquals(-1, scene.exportEnd());
    }

    @Test
    void everythingSurvivesSavingAndLoading() {
        EditState state = everything();
        byte[] bytes = state.toBytes();
        EditState loaded = EditState.fromBytes(bytes);
        assertArrayEquals(bytes, loaded.toBytes());

        assertEquals(2, loaded.scenes().size());
        assertEquals(1, loaded.currentSceneIndex());
        Scene scene = loaded.scene(0);
        assertEquals("Opening", scene.name());
        assertEquals(20, scene.exportStart());
        assertEquals(400, scene.exportEnd());
        assertEquals(KeyframeKind.values().length, scene.tracks().size());
        for (int i = 0; i < KeyframeKind.values().length; i++) {
            KeyframeKind kind = KeyframeKind.values()[i];
            Track track = scene.track(i);
            assertEquals(kind, track.kind());
            assertEquals(List.of(Keyframe.example(kind), Keyframe.example(kind).withTick(300).withInterpolation(Interpolation.EASE_OUT)), track.keyframes());
        }
        assertEquals("Wide circle", scene.track(1).name());
        assertFalse(scene.track(2).enabled());
        assertTrue(scene.track(3).enabled());
        Looks looks = scene.looks();
        assertFalse(looks.showHud);
        assertTrue(looks.showChat);
        assertEquals(Looks.Weather.THUNDER, looks.weather);
        assertEquals(Looks.FogMode.CUSTOM, looks.fogMode);
        assertEquals(64.0, looks.fogEnd);
        assertEquals(0x102030, looks.fogColour);
        assertEquals(0xFF00FF, looks.backgroundColour);
        assertEquals(Looks.AspectGuide.CINEMA_21_9, looks.aspectGuide);
        assertEquals(List.of("minecraft:bat", "minecraft:item"), looks.hiddenEntityTypes);
        assertEquals(new EntityOverride(true, "The Stranger", EntityOverride.Glow.FORCE_GLOW, true, false, true), looks.entityOverride(ENTITY));
    }

    @Test
    void theSavedFormHasAVersionAndPlainKeyframes() {
        String text = text(everything());
        assertTrue(text.startsWith("{\"version\":" + EditState.VERSION + ",\"current_scene\":1,\"scenes\":[{\"name\":\"Opening\",\"export_start\":20,"
                + "\"export_end\":400,\"tracks\":[{\"kind\":\"camera\",\"name\":\"\",\"enabled\":true,\"keyframes\":[{\"tick\":120,"), text);
        // Keyframes are saved in exactly the shape the tools show and accept
        assertTrue(text.contains("{\"kind\":\"field_of_view\",\"name\":\"\",\"enabled\":true,\"keyframes\":["
                + Keyframe.exampleJsonText(KeyframeKind.FIELD_OF_VIEW) + ","), text);
        assertTrue(text.contains("\"looks\":{\"show_hud\":false,\"show_chat\":true,"), text);
        assertTrue(text.contains("\"entity_overrides\":{\"" + ENTITY + "\":{\"hide\":true,\"name_override\":\"The Stranger\","), text);
        // A scene without an export range says nothing about one
        assertTrue(text.contains("{\"name\":\"Ending\",\"tracks\":[],"), text);
    }

    @Test
    void unknownFieldsAndUnknownKindsAreSkipped() {
        String text = "{\"version\": 99, \"invented_later\": {\"a\": [1, 2]}, \"current_scene\": 0, \"scenes\": [{"
                + "\"name\": \"From the future\", \"mood\": \"tense\", \"export_start\": 5, \"export_end\": 50, \"tracks\": ["
                + "{\"kind\": \"hologram\", \"name\": \"Ghost\", \"enabled\": true, \"keyframes\": [{\"tick\": 1, \"glow\": 2}]},"
                + "{\"kind\": \"field_of_view\", \"name\": \"Zoom\", \"enabled\": false, \"lens\": \"anamorphic\", \"keyframes\": ["
                + "{\"tick\": 120, \"interpolation\": \"smooth\", \"fov\": 30},"
                + "{\"tick\": 350, \"fov\": 9000},"
                + "{\"tick\": 360, \"fov\": 40, \"interpolation\": \"teleport\"},"
                + "{\"tick\": 370, \"fov\": 45, \"bokeh\": true},"
                + "{\"tick\": 380},"
                + "\"nonsense\"]},"
                + "\"more nonsense\","
                + "{\"kind\": \"speed\", \"keyframes\": [{\"tick\": 0, \"ticks_per_second\": 10}]}],"
                + "\"looks\": {\"show_hud\": false, \"lens_flare\": 3, \"weather\": \"meteor_shower\", \"fov\": 55, \"fog_start\": 500,"
                + " \"entity_overrides\": {\"" + ENTITY + "\": {\"hide\": true, \"hide_hat\": true}, \"not-a-uuid\": {\"hide\": true}}}}]}";

        EditState loaded = EditState.fromBytes(text.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, loaded.scenes().size());
        Scene read = loaded.scene(0);
        assertEquals("From the future", read.name());
        assertEquals(5, read.exportStart());
        assertEquals(List.of(KeyframeKind.FIELD_OF_VIEW, KeyframeKind.SPEED), read.tracks().stream().map(Track::kind).toList());
        assertEquals("Zoom", read.track(0).name());
        assertFalse(read.track(0).enabled());
        assertEquals(List.of(new Keyframe.FieldOfView(120, Interpolation.SMOOTH, 30), new Keyframe.FieldOfView(370, Interpolation.LINEAR, 45)),
                read.track(0).keyframes());
        assertTrue(read.track(1).enabled());
        assertEquals(1, read.track(1).size());
        // A value that cannot be read keeps its default; the rest of the looks are read
        assertEquals(Looks.Weather.AS_RECORDED, read.looks().weather);
        assertFalse(read.looks().showHud);
        assertEquals(55.0, read.looks().fov);
        assertTrue(read.looks().fogEnd >= read.looks().fogStart);
        assertEquals(java.util.Set.of(ENTITY), read.looks().hiddenEntities());
        // And what was read saves and loads again
        assertArrayEquals(loaded.toBytes(), EditState.fromBytes(loaded.toBytes()).toBytes());
    }

    @Test
    void anEmptyObjectLoadsAsAFreshStateAndRubbishIsRefused() {
        EditState loaded = EditState.fromBytes("{}".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, loaded.scenes().size());
        loaded = EditState.fromBytes("{\"scenes\": [], \"current_scene\": 7}".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, loaded.scenes().size());
        assertEquals(0, loaded.currentSceneIndex());
        assertThrows(IllegalArgumentException.class, () -> EditState.fromBytes("{not json".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> EditState.fromBytes("[1, 2]".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aCopySharesNothingThatCanChange() {
        EditState state = everything();
        EditState copy = state.copy();
        assertArrayEquals(state.toBytes(), copy.toBytes());
        assertNotSame(state.scene(0), copy.scene(0));
        copy.scene(0).track(0).clear();
        copy.scene(0).looks().showChat = false;
        copy.scene(0).looks().setEntityOverride(ENTITY, EntityOverride.NONE);
        copy.scene(0).setName("Changed");
        assertEquals(2, state.scene(0).track(0).size());
        assertTrue(state.scene(0).looks().showChat);
        assertTrue(state.scene(0).looks().entityOverride(ENTITY).hide());
        assertEquals("Opening", state.scene(0).name());
    }

}
