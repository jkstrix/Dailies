package comedymonkey.dailies.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Ways for the picture-taking scripts to put up screens and states a person reaches with the mouse: the small
 * dialogues, an open dropdown in the editor, the mouse resting on a control. Nothing in the mod itself calls these.
 */
public final class Previews {

    private Previews() {
    }

    /** The dialogue that asks for the name of a recording, with nothing behind its buttons */
    public static Screen name() {
        Minecraft minecraft = Minecraft.getInstance();
        return new NameScreen(Component.translatable("dailies.ui.finish.title"), Component.translatable("dailies.ui.finish.explanation"),
                "2026-11-17 05.53.38", Component.translatable("dailies.ui.finish.save"), Component.translatable("dailies.ui.finish.keep_recording"),
                name -> minecraft.setScreen(null), () -> minecraft.setScreen(null));
    }

    /** The offer to recover unfinished recordings, as it looks for two of them. Its buttons are not to be pressed */
    public static Screen recovery(Screen parent) {
        return new RecoveryScreen(List.of(Path.of("first.dailies.part"), Path.of("second.dailies.part")), parent);
    }

    /** The question asked before a recording is deleted, with nothing behind its buttons */
    public static Screen ask() {
        Minecraft minecraft = Minecraft.getInstance();
        return new AskScreen(yes -> minecraft.setScreen(null), Component.translatable("dailies.ui.delete.title"),
                Component.translatable("dailies.ui.delete.question", "A walk round the fire"), Component.translatable("dailies.ui.delete.yes"),
                Component.translatable("dailies.ui.delete.no"));
    }

    /** Has a Dailies screen draw as though the mouse were at this place, explanation included; -1 gives the mouse back */
    public static void pointAt(Screen screen, int x, int y) {
        if (screen instanceof KitScreen kit) {
            kit.pointAt(x, y);
        }
    }

    /** Shows one of the editor's side tabs: 0 keyframe, 1 looks, 2 export */
    public static void editorTab(int tab) {
        EditorState.tab = tab;
        EditorState.sideFolded = false;
    }

    /** Unfolds or folds the rarer settings of the editor's export tab */
    public static void exportMore(boolean unfolded) {
        ExportForms.showMore(unfolded);
    }

    /**
     * Opens a dropdown of the editor's side panel as a click on it would.
     *
     * @param which 0 for the first dropdown of the tab on show, 1 for the second, and so on
     * @return whether there was one to open
     */
    public static boolean editorDropdown(int which) {
        Screen screen = Minecraft.getInstance().screen;
        if (!(screen instanceof EditorScreen)) {
            return false;
        }
        try {
            Method side = EditorScreen.class.getDeclaredMethod("sideForm");
            side.setAccessible(true);
            Form form = (Form) side.invoke(screen);
            List<Gadget> order = new ArrayList<>();
            form.collect(order);
            int seen = 0;
            for (Gadget gadget : order) {
                if (gadget instanceof Gadget.Cycle<?> dropdown && seen++ == which) {
                    form.reveal(gadget);
                    dropdown.open();
                    return Menu.open != null;
                }
            }
            return false;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** What a dropdown of the editor's side panel shows while closed: the choice in force. Empty when there is no such dropdown */
    public static String editorDropdownShows(int which) {
        Screen screen = Minecraft.getInstance().screen;
        if (!(screen instanceof EditorScreen)) {
            return "";
        }
        try {
            Method side = EditorScreen.class.getDeclaredMethod("sideForm");
            side.setAccessible(true);
            Form form = (Form) side.invoke(screen);
            List<Gadget> order = new ArrayList<>();
            form.collect(order);
            int seen = 0;
            for (Gadget gadget : order) {
                if (gadget instanceof Gadget.Cycle<?> dropdown && seen++ == which) {
                    return dropdown.showing();
                }
            }
            return "";
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The lines the open menu shows now, after whatever has been typed into it */
    public static List<String> menuShown() {
        Menu menu = Menu.open;
        return menu == null ? List.of() : menu.shownLabels();
    }

    public static boolean menuOpen() {
        return Menu.open != null;
    }

    /** Types into the menu that is open, to show it narrowing; returns the lines left on show */
    public static List<String> menuFind(String words) {
        Menu menu = Menu.open;
        if (menu == null) {
            return List.of();
        }
        menu.find(words);
        return menu.shownLabels();
    }

    /** The lines of the menu that is open, or nothing when none is */
    public static List<String> menuLines() {
        Menu menu = Menu.open;
        return menu == null ? List.of() : menu.labels();
    }

    public static void closeMenu() {
        Menu.close();
    }

}
