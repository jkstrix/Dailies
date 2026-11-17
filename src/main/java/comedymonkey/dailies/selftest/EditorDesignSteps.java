package comedymonkey.dailies.selftest;

import comedymonkey.dailies.ui.Previews;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import org.lwjgl.glfw.GLFW;

import java.util.List;

import static comedymonkey.dailies.selftest.SelfTest.check;

/**
 * The editor's layout as the game lays it out: nothing overlaps or runs off the window, every control of the bar has
 * room in its one row, folding the panels gives the view more, the "..." menu holds the rarer things, and the Tab key
 * puts the ring on a control that Enter then presses, without the editor losing its own keys.
 */
final class EditorDesignSteps {

    private EditorDesignSteps() {
    }

    static void run(SelfTest test) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        test.onClientRun(() -> {
            if (minecraft.screen != null) {
                minecraft.setScreen(null);
            }
            Previews.editorFolds(false, false, false, false);
            Previews.editorTab(0);
            comedymonkey.dailies.ui.Controls.openTimeline();
        });
        test.waitFor(() -> minecraft.screen instanceof comedymonkey.dailies.ui.EditorScreen, 10, "the editor to open");
        test.sleepTicks(5);
        Screen editor = minecraft.screen;

        List<String> problems = test.onClient(Previews::editorLayoutProblems);
        check(problems.isEmpty(), "the editor's layout with every panel open: " + problems);
        double open = test.onClient(Previews::editorViewShare);
        check(open >= 0.5, "with every panel open the view has only " + Math.round(open * 100) + "% of the window");

        // Folded, the panels give the view nearly all of the window, and still nothing overlaps
        test.onClientRun(() -> Previews.editorFolds(true, true, true, true));
        test.sleepTicks(3);
        problems = test.onClient(Previews::editorLayoutProblems);
        check(problems.isEmpty(), "the editor's layout with every panel folded: " + problems);
        double folded = test.onClient(Previews::editorViewShare);
        check(folded > open && folded >= 0.8, "folding every panel left the view " + Math.round(folded * 100) + "% of the window, against " + Math.round(open * 100) + "% open");
        test.onClientRun(() -> Previews.editorFolds(false, false, false, false));
        test.sleepTicks(3);

        // The "..." menu: the scenes and forgetting the range, and Escape closes only the menu
        check(test.onClient(Previews::editorMore), "the editor's \"...\" menu did not open");
        List<String> lines = test.onClient(Previews::menuLines);
        check(lines.size() == 5 && lines.contains(I18n.get("dailies.editor.more.scene_add")) && lines.contains(I18n.get("dailies.editor.more.range_clear")),
                "the \"...\" menu holds " + lines);
        test.onClientRun(() -> editor.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0));
        check(!test.onClient(Previews::menuOpen) && minecraft.screen == editor, "Escape did not close the \"...\" menu and only the menu");

        // Tab puts the ring on the first control of the bar, the scene dropdown, and Enter opens it
        check(test.onClient(Previews::focusedControl).isEmpty(), "a control had the ring before Tab was pressed");
        test.onClientRun(() -> editor.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0));
        String first = test.onClient(Previews::focusedControl);
        check(first.equals("Dropdown"), "Tab put the ring on '" + first + "' rather than the scene dropdown");
        test.onClientRun(() -> editor.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0));
        check(test.onClient(Previews::menuOpen), "Enter did not open the scene dropdown the ring was on");
        test.onClientRun(() -> editor.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0));
        // Onwards, and back with Shift held
        test.onClientRun(() -> editor.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0));
        String second = test.onClient(Previews::focusedControl);
        check(!second.isEmpty() && !second.equals("Dropdown"), "a second Tab left the ring on '" + second + "'");
        test.onClientRun(() -> editor.keyPressed(GLFW.GLFW_KEY_TAB, 0, GLFW.GLFW_MOD_SHIFT));
        check(test.onClient(Previews::focusedControl).equals("Dropdown"), "Shift and Tab did not take the ring back to the scene dropdown");
        // Escape takes the ring away first and leaves the editor up; the next one would close it as it always has
        test.onClientRun(() -> editor.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0));
        check(test.onClient(Previews::focusedControl).isEmpty() && minecraft.screen == editor, "Escape did not take the ring away and only the ring");
        test.onClientRun(() -> editor.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0));
        test.sleepTicks(3);
        check(!(minecraft.screen instanceof comedymonkey.dailies.ui.EditorScreen), "Escape with no ring and no menu no longer closes the editor");
        test.log("editor design: laid out with no overlaps, view " + Math.round(open * 100) + "% open and " + Math.round(folded * 100)
                + "% folded, \"...\" menu and Tab order work");
    }

}
