package comedymonkey.dailies.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The editor's layout on the windows people use: nothing overlaps, nothing is off the screen, the view keeps its share */
class EditorLayoutTest {

    /** The window sizes the editor is judged at, with the GUI scale the game picks for each when left on its own */
    private static final int[][] WINDOWS = {{1280, 720, 3}, {1920, 1080, 4}, {2560, 1440, 4}};

    /** The viewer's controls, the list of one other player, three tracks and the moments shown */
    private static EditorLayout.Input open(int width, int height, int scale) {
        return new EditorLayout.Input(width, height, scale, false, false, false, false, 178, 34, 3, 40, 30, 36);
    }

    private static EditorLayout.Input folded(int width, int height, int scale) {
        return new EditorLayout.Input(width, height, scale, true, true, true, true, 178, 34, 3, 40, 30, 36);
    }

    private static void sound(EditorLayout.Layout layout, String what) {
        List<EditorLayout.Rect> all = new ArrayList<>(layout.docked());
        all.addAll(layout.cards());
        for (EditorLayout.Rect rect : all) {
            assertTrue(rect.within(layout.width(), layout.height()), what + ": " + rect + " is off a screen of " + layout.width() + "x" + layout.height());
        }
        List<EditorLayout.Rect> docked = layout.docked();
        for (int i = 0; i < docked.size(); i++) {
            for (int j = i + 1; j < docked.size(); j++) {
                assertFalse(docked.get(i).overlaps(docked.get(j)), what + ": " + docked.get(i) + " overlaps " + docked.get(j));
            }
        }
        for (EditorLayout.Rect card : layout.cards()) {
            for (EditorLayout.Rect panel : docked) {
                assertFalse(card.overlaps(panel), what + ": the card " + card + " overlaps " + panel);
            }
            // A card lies over the view, never past it
            assertTrue(card.left() >= layout.view().left() && card.right() <= layout.view().right()
                    && card.top() >= layout.view().top() && card.bottom() <= layout.view().bottom(), what + ": the card " + card + " leaves the view " + layout.view());
        }
        if (layout.cards().size() == 2) {
            assertFalse(layout.viewer().overlaps(layout.players()), what + ": the two cards overlap");
        }
        // The view is exactly what the docked panels leave
        assertEquals(layout.bar().bottom(), layout.view().top(), what + ": the view does not start under the bar");
        assertEquals(layout.timeline().top(), layout.view().bottom(), what + ": the view does not end at the timeline");
        assertEquals(layout.side().left(), layout.view().right(), what + ": the view does not end at the inspector");
        assertTrue(layout.frameClear().left() >= layout.view().left() && layout.frameClear().right() == layout.view().right(), what + ": the framed picture's room is wrong");
        assertTrue(layout.timeline().bottom() == layout.height() && layout.bar().top() == 0, what + ": a docked panel is not against its edge");
    }

    @Test
    void theEditorScaleLeavesAbout900By500() {
        assertEquals(1, EditorLayout.scaleFor(1280, 720, 3));
        assertEquals(2, EditorLayout.scaleFor(1920, 1080, 4));
        assertEquals(2, EditorLayout.scaleFor(2560, 1440, 4));
        // Never larger than the game's own
        assertEquals(1, EditorLayout.scaleFor(2560, 1440, 1));
        assertEquals(1, EditorLayout.scaleFor(800, 400, 2));
    }

    @Test
    void everyWindowHasNoOverlapsAndNothingOffScreen() {
        for (int[] window : WINDOWS) {
            int scale = EditorLayout.scaleFor(window[0], window[1], window[2]);
            String what = window[0] + "x" + window[1] + " at scale " + scale;
            sound(EditorLayout.of(open(window[0], window[1], scale)), what + ", open");
            sound(EditorLayout.of(folded(window[0], window[1], scale)), what + ", folded");
            for (int forced = 1; forced <= 3; forced++) {
                sound(EditorLayout.of(open(window[0], window[1], forced)), window[0] + "x" + window[1] + " forced to scale " + forced);
            }
        }
        // The game's smallest window, and a tall thin one
        sound(EditorLayout.of(open(854, 480, 1)), "854x480");
        sound(EditorLayout.of(open(640, 480, 1)), "640x480");
        sound(EditorLayout.of(open(1000, 1600, 1)), "1000x1600");
    }

    @Test
    void theViewKeepsMostOfA1080pWindowWithEveryPanelOpen() {
        EditorLayout.Layout layout = EditorLayout.of(open(1920, 1080, EditorLayout.scaleFor(1920, 1080, 4)));
        assertEquals(960, layout.width());
        assertEquals(540, layout.height());
        assertTrue(layout.viewShare() >= 0.60, "the view is only " + layout.viewShare() + " of the window");
        // With many tracks the rows scroll rather than eat the view
        EditorLayout.Layout many = EditorLayout.of(new EditorLayout.Input(1920, 1080, 2, false, false, false, false, 178, 200, 12, 40, 30, 36));
        assertTrue(many.viewShare() >= 0.60, "twelve tracks leave the view only " + many.viewShare());
        assertTrue(many.lanes() >= EditorLayout.FEWEST_LANES && many.lanes() < 12);
        // And with the moments hidden and few tracks, every track has its row
        EditorLayout.Layout few = EditorLayout.of(new EditorLayout.Input(1920, 1080, 2, false, false, false, false, 178, 34, 3, 0, 30, 36));
        assertEquals(3, few.lanes());
    }

    @Test
    void theViewShareAtEachSize() {
        for (int[] window : WINDOWS) {
            int scale = EditorLayout.scaleFor(window[0], window[1], window[2]);
            EditorLayout.Layout open = EditorLayout.of(open(window[0], window[1], scale));
            EditorLayout.Layout folded = EditorLayout.of(folded(window[0], window[1], scale));
            System.out.printf("%dx%d scale %d (%dx%d): view %.1f%% open (%.1f%% clear of the cards too), %.1f%% folded%n", window[0], window[1], scale,
                    open.width(), open.height(), open.viewShare() * 100, open.clearShare() * 100, folded.viewShare() * 100);
            assertTrue(open.viewShare() >= 0.60, window[0] + "x" + window[1] + ": the view is only " + open.viewShare());
            assertTrue(folded.viewShare() > 0.85, window[0] + "x" + window[1] + " folded: the view is only " + folded.viewShare());
        }
    }

    @Test
    void foldedCardsShrinkToTheirTitles() {
        EditorLayout.Layout layout = EditorLayout.of(folded(1920, 1080, 2));
        assertEquals(EditorLayout.HEADER, layout.viewer().height());
        assertTrue(layout.viewer().width() < EditorLayout.cardWidth(960));
        assertEquals(EditorLayout.EDGE, layout.side().width());
        // With both cards folded, a framed picture may use the whole view
        assertEquals(layout.view(), layout.frameClear());
    }

    @Test
    void aRowKeepsItsRightHandGroupAndNeverOverlaps() {
        int[][] left = {{128}, {60, 44, 44, 52, 48}};
        int[][] right = {{16, 16}, {48, 16, 34}};
        int[] xs = EditorLayout.row(4, 956, 8, left, right);
        int[] widths = {128, 60, 44, 44, 52, 48, 16, 16, 48, 16, 34};
        for (int i = 0; i < xs.length; i++) {
            assertTrue(xs[i] >= 0, "control " + i + " had no room at 960 wide");
            for (int j = i + 1; j < xs.length; j++) {
                assertTrue(xs[i] + widths[i] <= xs[j] || xs[j] + widths[j] <= xs[i], "controls " + i + " and " + j + " overlap");
            }
        }
        assertEquals(956, xs[xs.length - 1] + 34);
        // Squeezed, the left side gives way and the way out stays
        int[] tight = EditorLayout.row(4, 300, 8, left, right);
        assertTrue(tight[tight.length - 1] >= 0 && tight[0] >= 0);
        assertTrue(tight[5] < 0, "a control that does not fit was still placed");
    }

}
