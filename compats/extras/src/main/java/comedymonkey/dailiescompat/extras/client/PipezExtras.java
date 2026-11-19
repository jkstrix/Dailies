package comedymonkey.dailiescompat.extras.client;

import comedymonkey.dailies.api.compat.Claims;
import comedymonkey.dailiescompat.extras.state.ExtrasStates;
import de.maxhenkel.pipez.Filter;
import de.maxhenkel.pipez.gui.ExtractScreen;
import de.maxhenkel.pipez.gui.FilterContainer;
import de.maxhenkel.pipez.gui.FilterScreen;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.jetbrains.annotations.Nullable;

/**
 * Extras for Pipez's two windows, which the server opens and Dailies draws itself:
 * <ul>
 * <li>a pipe's settings ({@link #EXTRACT}): the tab of the kind of pipe shown (a universal pipe has one each for items,
 * fluids and energy), which the screen keeps on the player's game, and the filter picked in its list and how far the list
 * is scrolled; the tab is turned as clicking it turns it, which lays the screen out again;</li>
 * <li>a filter being edited ({@link #FILTER}): the item and the data typed into its boxes and its two switches, which the
 * server hears of only when the filter is saved.</li>
 * </ul>
 * The pipe's own settings (its redstone mode, how it shares out, its filters) the screen reads from the pipe in the world,
 * as playback's world holds it.
 */
final class PipezExtras {

    static final String EXTRACT = "pipe_settings";
    static final String FILTER = "pipe_filter";
    private static final String EXTRACT_MENU = "pipez:extract";
    private static final String FILTER_MENU = "pipez:filter";
    /** Typing is written at most five times a second */
    private static final int TYPING_TICKS = 4;

    private PipezExtras() {
    }

    static void claim(Claims claims) {
        claims.windowExtras(EXTRACT, EXTRACT_MENU, Parts.extras(PipezExtras::captureExtract, PipezExtras::applyExtract, 1));
        claims.windowExtras(FILTER, FILTER_MENU, Parts.extras(PipezExtras::captureFilter, PipezExtras::applyFilter, TYPING_TICKS));
    }

    // --- A pipe's settings ---

    static byte @Nullable [] captureExtract(AbstractContainerScreen<?> screen) {
        if (!(screen instanceof ExtractScreen)) {
            return null;
        }
        int tab = Reflect.get(screen, "currentindex");
        Object list = Reflect.get(screen, "filterList");
        int selected = list == null ? -1 : Reflect.<Integer>get(list, "selected");
        int offset = list == null ? 0 : Reflect.<Integer>get(list, "offset");
        return ExtrasStates.pipeSettings(new ExtrasStates.PipeSettings(tab, selected, offset));
    }

    static void applyExtract(AbstractContainerScreen<?> screen, byte[] state) {
        if (!(screen instanceof ExtractScreen)) {
            return;
        }
        ExtrasStates.PipeSettings recorded = ExtrasStates.readPipeSettings(state);
        int tab = Reflect.get(screen, "currentindex");
        Object[] kinds = Reflect.get(screen, "pipeTypes");
        if (tab != recorded.tab() && recorded.tab() >= 0 && kinds != null && recorded.tab() < kinds.length) {
            // As clicking a tab turns it: the screen lays itself out again for that kind of pipe
            Reflect.set(screen, "currentindex", recorded.tab());
            Reflect.call(screen, "init", new Class<?>[0]);
        }
        Object list = Reflect.get(screen, "filterList");
        if (list != null) {
            Reflect.set(list, "selected", recorded.selected());
            Reflect.set(list, "offset", recorded.offset());
        }
    }

    // --- A filter being edited ---

    static byte @Nullable [] captureFilter(AbstractContainerScreen<?> screen) {
        if (!(screen instanceof FilterScreen) || !(screen.getMenu() instanceof FilterContainer menu)) {
            return null;
        }
        EditBox item = Reflect.get(screen, "item");
        EditBox data = Reflect.get(screen, "nbt");
        Filter<?, ?> filter = menu.getFilter();
        return ExtrasStates.pipeFilter(new ExtrasStates.PipeFilter(item == null ? "" : item.getValue(), data == null ? "" : data.getValue(),
            filter != null && filter.isExactMetadata(), filter != null && filter.isInvert()));
    }

    static void applyFilter(AbstractContainerScreen<?> screen, byte[] state) {
        if (!(screen instanceof FilterScreen) || !(screen.getMenu() instanceof FilterContainer menu)) {
            return;
        }
        ExtrasStates.PipeFilter recorded = ExtrasStates.readPipeFilter(state);
        EditBox item = Reflect.get(screen, "item");
        if (item != null && !item.getValue().equals(recorded.item())) {
            item.setValue(recorded.item());
        }
        EditBox data = Reflect.get(screen, "nbt");
        if (data != null && !data.getValue().equals(recorded.data())) {
            data.setValue(recorded.data());
        }
        Filter<?, ?> filter = menu.getFilter();
        if (filter != null) {
            filter.setExactMetadata(recorded.exact());
            filter.setInvert(recorded.invert());
        }
    }

}
