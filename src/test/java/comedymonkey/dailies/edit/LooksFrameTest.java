package comedymonkey.dailies.edit;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The looks added to chapter V: the scoreboard, action bar and hotbar; blocks, entities, players and beacon beams as
 * a whole; and a frame shape that is the picture itself, not only a guide.
 */
class LooksFrameTest {

    private static final String[] WHOLE_KINDS = {"show_scoreboard", "show_action_bar", "show_hotbar", "show_blocks", "show_entities", "show_players", "show_beacon_beams"};

    @Test
    void everythingIsDrawnUntilSwitchedOff() {
        Looks looks = new Looks();
        assertTrue(looks.showScoreboard && looks.showActionBar && looks.showHotbar && looks.showBlocks && looks.showEntities && looks.showPlayers
                && looks.showBeaconBeams);
        assertFalse(looks.letterbox);
        for (String name : WHOLE_KINDS) {
            assertTrue(looks.get(name).getAsBoolean(), name);
            assertEquals("boolean", looks.describe().getAsJsonObject(name).get("type").getAsString(), name);
            assertFalse(looks.describe().getAsJsonObject(name).get("description").getAsString().isBlank(), name);
        }
    }

    @Test
    void eachIsSetByItsOwnNameAndNoOther() {
        for (String name : WHOLE_KINDS) {
            Looks looks = new Looks();
            looks.setAllText("{\"" + name + "\": false}");
            for (String other : WHOLE_KINDS) {
                assertEquals(!other.equals(name), looks.get(other).getAsBoolean(), name + " changed " + other);
            }
        }
        Looks looks = new Looks();
        looks.setAllText("{\"show_blocks\": false, \"show_players\": false, \"show_hotbar\": false}");
        assertFalse(looks.showBlocks || looks.showPlayers || looks.showHotbar);
        assertTrue(looks.showEntities && looks.showBeaconBeams && looks.showScoreboard && looks.showActionBar);
        String refused = assertThrows(IllegalArgumentException.class, () -> looks.setAllText("{\"show_entities\": \"no\"}")).getMessage();
        assertTrue(refused.contains("show_entities"), refused);
    }

    @Test
    void theNewLooksSurviveSavingLoadingAndCopying() {
        Looks looks = new Looks();
        looks.setAllText("{\"show_scoreboard\": false, \"show_action_bar\": false, \"show_entities\": false, \"show_beacon_beams\": false, \"letterbox\": true,"
                + " \"aspect_guide\": \"21:9\"}");
        Looks loaded = Looks.fromJsonText(looks.toJsonText());
        assertFalse(loaded.showScoreboard || loaded.showActionBar || loaded.showEntities || loaded.showBeaconBeams);
        assertTrue(loaded.showHotbar && loaded.showBlocks && loaded.showPlayers && loaded.letterbox);
        assertEquals(looks.toJson(), loaded.toJson());
        assertEquals(looks.toJson(), looks.copy().toJson());
    }

    @Test
    void aSceneSavedBeforeTheseLooksExistedDrawsEverything() {
        JsonObject old = new Looks().toJson();
        for (String name : WHOLE_KINDS) {
            old.remove(name);
        }
        old.remove("letterbox");
        Looks loaded = Looks.fromJson(old);
        assertTrue(loaded.showScoreboard && loaded.showActionBar && loaded.showHotbar && loaded.showBlocks && loaded.showEntities && loaded.showPlayers
                && loaded.showBeaconBeams);
        assertFalse(loaded.letterbox);
    }

    @Test
    void aFrameShapeIsOnlyAGuideUntilThePictureIsHeldToIt() {
        Looks looks = new Looks();
        looks.setAllText("{\"aspect_guide\": \"21:9\"}");
        assertEquals(21.0 / 9.0, looks.aspectRatio(), 1.0e-9);
        assertEquals(0.0, looks.frameRatio());
        assertArrayEquals(new int[]{1920, 1080}, looks.frameSize(1920, 1080));

        looks.setAllText("{\"letterbox\": true}");
        assertEquals(21.0 / 9.0, looks.frameRatio(), 1.0e-9);
        // Held to a shape with no shape chosen, there is nothing to hold to
        looks.setAllText("{\"aspect_guide\": \"off\"}");
        assertEquals(0.0, looks.frameRatio());
        assertArrayEquals(new int[]{1920, 1080}, looks.frameSize(1920, 1080));
    }

    @Test
    void anExportComesOutInTheFrameShapeFittedInsideTheSizeAskedFor() {
        Looks looks = new Looks();
        looks.setAllText("{\"letterbox\": true, \"aspect_guide\": \"21:9\"}");
        assertArrayEquals(new int[]{1920, 822}, looks.frameSize(1920, 1080));
        assertArrayEquals(new int[]{640, 274}, looks.frameSize(640, 360));

        looks.setAllText("{\"aspect_guide\": \"9:16\"}");
        assertArrayEquals(new int[]{608, 1080}, looks.frameSize(1920, 1080));
        looks.setAllText("{\"aspect_guide\": \"1:1\"}");
        assertArrayEquals(new int[]{1080, 1080}, looks.frameSize(1920, 1080));
        assertArrayEquals(new int[]{720, 720}, looks.frameSize(720, 1280));
        looks.setAllText("{\"aspect_guide\": \"4:3\"}");
        assertArrayEquals(new int[]{1440, 1080}, looks.frameSize(1920, 1080));
        looks.setAllText("{\"aspect_guide\": \"16:9\"}");
        assertArrayEquals(new int[]{1920, 1080}, looks.frameSize(1920, 1080));
        looks.setAllText("{\"aspect_guide\": \"custom\", \"custom_aspect\": 2.39}");
        assertArrayEquals(new int[]{1920, 804}, looks.frameSize(1920, 1080));

        // Never larger than was asked for, never an odd side, never nothing
        for (String shape : new String[]{"16:9", "9:16", "4:3", "1:1", "21:9"}) {
            looks.setAllText("{\"aspect_guide\": \"" + shape + "\"}");
            for (int[] asked : new int[][]{{1920, 1080}, {1280, 720}, {854, 480}, {1080, 1920}, {16, 16}, {3840, 2160}, {17, 4001}}) {
                int[] size = looks.frameSize(asked[0], asked[1]);
                assertTrue(size[0] <= asked[0] && size[1] <= asked[1] && size[0] >= 2 && size[1] >= 2, shape + " in " + asked[0] + "x" + asked[1]);
                assertTrue(size[0] == asked[0] || size[0] % 2 == 0, shape + " width " + size[0]);
                assertTrue(size[1] == asked[1] || size[1] % 2 == 0, shape + " height " + size[1]);
            }
        }
    }

    @Test
    void theBarsLeaveTheFrameClearInTheMiddleOfTheView() {
        double window = 16.0 / 9.0;
        assertArrayEquals(new double[]{0.0, 0.0, 1.0, 1.0}, Looks.clearPart(0.0, window), 1.0e-9);
        assertArrayEquals(new double[]{0.0, 0.0, 1.0, 1.0}, Looks.clearPart(window, window), 1.0e-9);

        double[] wide = Looks.clearPart(21.0 / 9.0, window);
        assertEquals(0.0, wide[0], 1.0e-9);
        assertEquals(1.0, wide[2], 1.0e-9);
        assertEquals(1.0, wide[1] + wide[3], 1.0e-9);
        assertEquals(window / (21.0 / 9.0), wide[3] - wide[1], 1.0e-9);

        double[] tall = Looks.clearPart(9.0 / 16.0, window);
        assertEquals(0.0, tall[1], 1.0e-9);
        assertEquals(1.0, tall[3], 1.0e-9);
        assertEquals(1.0, tall[0] + tall[2], 1.0e-9);
        assertEquals((9.0 / 16.0) / window, tall[2] - tall[0], 1.0e-9);
    }

    @Test
    void whatShowsBetweenTheBarsIsWhatTheExportHolds() {
        double window = 16.0 / 9.0;
        double frame = 21.0 / 9.0;
        // Bars down the sides cut the picture and change nothing else
        assertEquals(70.0, Looks.fovBehindBars(70.0, 1.0, window), 1.0e-9);
        assertEquals(70.0, Looks.fovBehindBars(70.0, window, window), 1.0e-9);
        assertEquals(70.0, Looks.fovBehindBars(70.0, 0.0, window), 1.0e-9);

        // Bars above and below: the view is drawn taller, so that the clear part spans the wanted angle exactly
        double drawn = Looks.fovBehindBars(70.0, frame, window);
        assertTrue(drawn > 70.0 && drawn < 179.0);
        double clearShare = Looks.clearPart(frame, window)[3] - Looks.clearPart(frame, window)[1];
        double seen = Math.toDegrees(2.0 * Math.atan(Math.tan(Math.toRadians(drawn) / 2.0) * clearShare));
        assertEquals(70.0, seen, 1.0e-6);
        // And the clear part is as wide, in degrees, as a picture of the frame's own shape would be
        double acrossInEditor = Math.tan(Math.toRadians(drawn) / 2.0) * window;
        double acrossInExport = Math.tan(Math.toRadians(70.0) / 2.0) * frame;
        assertEquals(acrossInExport, acrossInEditor, 1.0e-9);

        assertTrue(Looks.fovBehindBars(170.0, 10.0, 0.5) <= 179.0);
    }

}
