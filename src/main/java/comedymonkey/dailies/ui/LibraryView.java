package comedymonkey.dailies.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/**
 * One of the three views of the library's plate ({@link RecordingsScreen}): the recordings, the bin, or storage. The
 * screen holds what was read from the folder and the parts every view shares, the dropdown that chooses the view, the
 * list, the pane beside it and the way out; a view says what goes in them, which buttons stand along the foot, and what
 * its keys do.
 */
abstract class LibraryView {

    static final String KEYS = "dailies.ui.list.";
    /** The height of a recording's row and of a row of the bin */
    static final int ROW_HEIGHT = 26;

    final RecordingsScreen screen;

    LibraryView(RecordingsScreen screen) {
        this.screen = screen;
    }

    static Component t(String key, Object... arguments) {
        return Component.translatable(KEYS + key, arguments);
    }

    /** A button of the foot, its words and explanation taken from the library's keys */
    static Gadget.Push push(String key, Runnable action, BooleanSupplier usable) {
        Gadget.Push button = new Gadget.Push(t(key), action);
        button.when(usable).tip(t(key + ".tip"));
        return button;
    }

    /** Whether this is the view on show */
    boolean showing() {
        return this.screen.current() == this;
    }

    /** Makes its controls. Called whenever the screen is put up or the window changes size */
    abstract void build();

    /** The words under the title, or null for none */
    abstract @Nullable Component count();

    /** The words in the bar beside the dropdown, for a view whose bar holds nothing else */
    Component barWords() {
        return Component.empty();
    }

    /** The controls of the bar beside the dropdown */
    Gadget[] bar() {
        return new Gadget[]{this.screen.barWords};
    }

    /** Puts the controls of the bar in their places, starting at the left. Returns how many rows the bar took */
    int placeBar(Font font, int x, int y) {
        RecordingsScreen.put(font, this.screen.barWords, x, y, this.screen.right - 8 - x);
        return 1;
    }

    /** The buttons along the foot at the left: what is done to the thing chosen */
    abstract Gadget[] footLeft();

    /** The buttons along the foot at the right, the way out last */
    abstract Gadget[] footRight();

    /** Fills the list */
    abstract void fillRows(Form rows);

    /** A word for what the pane beside the list should hold now; when it changes the pane is built afresh */
    abstract String sideShape();

    /** Fills the pane beside the list */
    abstract void fillSide(Form side);

    /** Whether there is nothing in the list to show, so that words saying why are shown in its place */
    abstract boolean empty();

    /** The two lines shown in place of an empty list, once nothing is being looked for */
    String[] emptyWords() {
        if (this.screen.all.isEmpty()) {
            return new String[]{t("none").getString(), t("none_hint").getString()};
        }
        return new String[]{t("none_match").getString(), t("none_match_hint").getString()};
    }

    /** The folder the Open folder button opens. Read away from the client thread */
    abstract Path folder();

    /** A key nothing else wanted. Returns whether this view did */
    boolean key(int keyCode, int modifiers) {
        return false;
    }

    /** A letter typed while no box is being typed into */
    void typed(int codepoint) {
    }

}
