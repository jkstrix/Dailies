package comedymonkey.dailies.ui;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.file.RecordingFile;
import comedymonkey.dailies.library.Bin;
import comedymonkey.dailies.library.DiskGuard;
import comedymonkey.dailies.library.Library;
import comedymonkey.dailies.library.LibraryIndex;
import comedymonkey.dailies.library.LibraryQuery;
import comedymonkey.dailies.library.StorageReport;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The library (specification REC-5, REC-8 and REC-9), reached from the title screen and the pause menu. Three views
 * share one plate, chosen from the dropdown at its top left:
 * <ul>
 *     <li>Recordings ({@link RecordingsView}): searched by name, world, player or date, in the order chosen, folder by
 *     folder or all at once, with a pane of details for the one chosen. Several can be chosen with Ctrl and Shift, to
 *     combine, move or delete together.</li>
 *     <li>The bin ({@link BinView}): what was deleted, how long it has left, and putting it back. Emptying it is the
 *     only delete for good, and asks first.</li>
 *     <li>Storage ({@link StorageView}): how much room recordings, moments audio, exports and the bin take, per world,
 *     and the settings that keep it in check.</li>
 * </ul>
 * The screen holds what was last read from the folder and the parts the views share: the dropdown, the list, the pane
 * beside it, Open folder and Done. Folders are real folders of the recordings folder. The folder is only ever read,
 * and files only ever changed, away from the client thread.
 */
public final class RecordingsScreen extends KitScreen {

    enum View {
        RECORDINGS, BIN, STORAGE
    }

    private static final int LIST_SHARE = 58;

    private View view = View.RECORDINGS;
    private final RecordingsView recordings = new RecordingsView(this);
    private final BinView bin = new BinView(this);
    private final StorageView storage = new StorageView(this);

    // What was found, by the last look at the folder
    List<LibraryIndex.Entry> all = List.of();
    List<String> folderList = List.of();
    Set<String> starredIds = Set.of();
    List<Bin.Item> binItems = List.of();
    @Nullable StorageReport report = null;
    long free = -1;
    boolean looking = false;
    private boolean lookedOnce = false;
    boolean measuring = false;
    /** Goes up with every look, so an answer that arrives after a newer question is ignored */
    private int look = 0;

    /** What the pane beside the list was built for; the view on show says what it should hold now */
    String detailsBuiltFor = "";

    final Form rows = new Form();
    private final Form side = new Form();

    private Gadget.Dropdown<View> viewPick;
    Gadget.Words barWords;
    Gadget.Push openFolder;
    Gadget.Push done;

    public RecordingsScreen(@Nullable Screen parent) {
        super(Component.translatable("dailies.ui.list.title"), parent);
    }

    private static Component t(String key, Object... arguments) {
        return LibraryView.t(key, arguments);
    }

    /** The view on show */
    LibraryView current() {
        return switch (this.view) {
            case RECORDINGS -> this.recordings;
            case BIN -> this.bin;
            case STORAGE -> this.storage;
        };
    }

    // ------------------------------------------------------------------ Building

    @Override
    void build() {
        this.viewPick = new Gadget.Dropdown<>(List.of(View.values()), which -> t("view." + which.name().toLowerCase(Locale.ROOT)), () -> this.view, this::show);
        this.viewPick.tip(t("view.tip"));
        this.barWords = new Gadget.Words(() -> this.current().barWords(), Controls.PARCHMENT_DIM);
        this.recordings.build();
        this.bin.build();
        this.storage.build();
        this.openFolder = LibraryView.push("open_folder", this::openFolder, () -> true);
        this.done = new Gadget.Push(t("done"), this::onClose);
        this.done.tip(t("done.tip"));

        // In this order, which is the order Tab goes through those on show
        this.fixed.add(this.viewPick);
        this.fixed.addAll(List.of(this.recordings.bar()));
        this.fixed.add(this.barWords);
        RecordingsView r = this.recordings;
        for (Gadget button : List.of(r.watch, r.edit, r.star, r.moveTo, r.combine, r.trim, r.delete, this.bin.restore, this.bin.emptyBin,
                this.storage.measure, this.storage.settings, r.folders, this.openFolder, r.share, r.importing, this.done)) {
            this.fixed.add(button);
        }
        this.forms.add(this.rows);
        this.forms.add(this.side);
        this.rebuildRows();
        this.detailsBuiltFor = "";
        if (!this.lookedOnce) {
            this.lookedOnce = true;
            this.lookAgain();
        }
    }

    private void show(View which) {
        if (this.view == which) {
            return;
        }
        this.view = which;
        Entry.blur();
        this.rows.scrollToTop();
        this.side.scrollToTop();
        this.detailsBuiltFor = "";
        if (which == View.STORAGE && this.report == null) {
            this.measureAgain();
        }
        if (which != View.STORAGE) {
            this.lookAgain();
        }
        this.rebuildRows();
    }

    // ------------------------------------------------------------------ Setting out

    private static void hide(Font font, Gadget gadget) {
        gadget.clip(Integer.MIN_VALUE, Integer.MAX_VALUE);
        gadget.place(font, -1000, -1000, 0, 0);
    }

    static void put(Font font, Gadget gadget, int x, int y, int width) {
        gadget.clip(Integer.MIN_VALUE, Integer.MAX_VALUE);
        gadget.place(font, x, y, Math.max(10, width), Kit.ROW);
    }

    @Override
    void arrange(Font font) {
        LibraryView now = this.current();
        this.centre(860, this.height - 24);
        int head = this.headingHeight(font, now.count());
        int bar = this.top + head;
        int x = this.left + 8;
        put(font, this.viewPick, x, bar, 104);
        x += 104 + Kit.GAP * 3;
        int barRows = now.placeBar(font, x, bar);

        Gadget[] left = now.footLeft();
        Gadget[] right = now.footRight();
        this.foot(font, right);
        int footRows = this.footWrapped(font, left, right);

        int listTop = bar + barRows * (Kit.ROW + 4) + 2;
        int foot = footRows * (Kit.ROW + 2) + (footRows - 1) * Kit.GAP * 2 + 8 + 6;
        int listBottom = this.bottom - foot;
        int listWidth = (this.right - this.left - 10) * LIST_SHARE / 100;
        int split = this.left + 5 + listWidth;
        this.rows.place(font, this.left + 5, listTop, listWidth, listBottom - listTop);
        this.side.place(font, split + 6, listTop, this.right - 5 - split - 6, listBottom - listTop);
        this.refreshSide();

        // What belongs to the other views takes no room
        Gadget[] own = now.bar();
        for (Gadget gadget : this.fixed) {
            if (gadget != this.viewPick && !among(gadget, own) && !among(gadget, left) && !among(gadget, right)) {
                hide(font, gadget);
            }
        }
    }

    /**
     * The buttons at the left of the foot, beside those at the right where they fit, otherwise wrapped onto rows above
     * them (a large interface scale). Returns how many rows the foot takes in all
     */
    private int footWrapped(Font font, Gadget[] left, Gadget[] right) {
        int gap = Kit.GAP * 2;
        int rowHeight = Kit.ROW + 2;
        int firstRight = this.right - 8;
        for (Gadget button : right) {
            if (button.height > 0) {
                firstRight = Math.min(firstRight, button.x);
            }
        }
        int start = this.left + 8;
        int lastY = this.bottom - 8 - rowHeight;
        // Split the buttons into rows: the bottom row ends before the buttons at the right, the rows above use the width
        List<List<Gadget>> rows = new ArrayList<>();
        List<Integer> widths = new ArrayList<>();
        List<Gadget> row = new ArrayList<>();
        int used = 0;
        int limit = firstRight - gap - start;
        for (Gadget button : left) {
            int wide = Math.max(52, button.wantedWidth(font) + 8);
            if (!row.isEmpty() && used + gap + wide > limit) {
                rows.add(row);
                widths.add(used);
                row = new ArrayList<>();
                used = 0;
                limit = this.right - 8 - start;
            }
            used += (row.isEmpty() ? 0 : gap) + wide;
            row.add(button);
        }
        rows.add(row);
        widths.add(used);
        if (rows.size() > 1) {
            // Wrapped: keep the bottom row for the buttons at the right alone, and fill the rows above from the left
            rows.clear();
            row = new ArrayList<>();
            used = 0;
            limit = this.right - 8 - start;
            for (Gadget button : left) {
                int wide = Math.max(52, button.wantedWidth(font) + 8);
                if (!row.isEmpty() && used + gap + wide > limit) {
                    rows.add(row);
                    row = new ArrayList<>();
                    used = 0;
                }
                used += (row.isEmpty() ? 0 : gap) + wide;
                row.add(button);
            }
            rows.add(row);
        }
        int count = rows.size() == 1 ? 1 : rows.size() + 1;
        int top = this.bottom - 8 - count * rowHeight - (count - 1) * gap;
        for (int index = 0; index < rows.size(); index++) {
            int y = rows.size() == 1 ? lastY : top + index * (rowHeight + gap);
            int x = start;
            List<Gadget> buttons = rows.get(index);
            // A row that still runs past the plate shares out what room there is
            int natural = 0;
            for (Gadget button : buttons) {
                natural += Math.max(52, button.wantedWidth(font) + 8);
            }
            int room = (rows.size() == 1 ? firstRight - gap : this.right - 8) - start - gap * (buttons.size() - 1);
            for (Gadget button : buttons) {
                int wide = Math.max(52, button.wantedWidth(font) + 8);
                if (natural > room) {
                    wide = Math.max(10, wide * room / natural);
                }
                button.clip(Integer.MIN_VALUE, Integer.MAX_VALUE);
                button.place(font, x, y, wide, rowHeight);
                x += wide + gap;
            }
        }
        return count;
    }

    private static boolean among(Gadget gadget, Gadget[] gadgets) {
        for (Gadget one : gadgets) {
            if (one == gadget) {
                return true;
            }
        }
        return false;
    }

    @Override
    void paint(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        LibraryView now = this.current();
        this.heading(graphics, font, now.count());
        // A thin line between the list and the pane beside it
        int lineX = this.side.x - 3;
        graphics.fill(lineX, this.rows.y + 2, lineX + 1, this.rows.y + this.rows.height - 2, 0x40D4B25A);
        if (!now.empty()) {
            return;
        }
        int middle = this.rows.y + this.rows.height / 2;
        int wide = this.rows.width - 10;
        if (this.looking || this.measuring) {
            Kit.centred(graphics, font, t("looking").getString(), this.rows.x + 5, middle - 4, wide, Controls.PARCHMENT_DIM);
            return;
        }
        String[] words = now.emptyWords();
        Kit.centred(graphics, font, words[0], this.rows.x + 5, middle - 11, wide, Controls.PARCHMENT);
        Kit.centred(graphics, font, words[1], this.rows.x + 5, middle + 2, wide, Controls.PARCHMENT_DIM);
    }

    // ------------------------------------------------------------------ The list and the pane beside it

    void rebuildRows() {
        this.rows.clear();
        this.current().fillRows(this.rows);
        this.detailsBuiltFor = "";
    }

    private void refreshSide() {
        LibraryView now = this.current();
        String wanted = now.sideShape();
        if (wanted.equals(this.detailsBuiltFor)) {
            return;
        }
        this.detailsBuiltFor = wanted;
        this.side.clear();
        now.fillSide(this.side);
    }

    /** Reads the folder and the bin again, off the client thread */
    void lookAgain() {
        int asked = ++this.look;
        this.looking = true;
        Minecraft game = Minecraft.getInstance();
        CompletableFuture.supplyAsync(() -> {
            List<LibraryIndex.Entry> entries = Library.list();
            return new Found(entries, Library.index().folders(), Library.stars().all(), Library.bin().list(), DiskGuard.free());
        }, Util.ioPool()).whenComplete((found, error) -> game.execute(() -> {
            if (asked != this.look) {
                return;
            }
            this.looking = false;
            if (error != null || found == null) {
                Host.LOGGER.error("Dailies: the recordings folder could not be read", error);
                Notices.show(t("unreadable"));
                return;
            }
            this.all = List.copyOf(found.entries());
            this.folderList = List.copyOf(found.folders());
            this.starredIds = found.starred();
            this.binItems = List.copyOf(found.bin());
            this.free = found.free();
            this.recordings.libraryRead();
            this.rebuildRows();
        }));
    }

    private record Found(List<LibraryIndex.Entry> entries, List<String> folders, Set<String> starred, List<Bin.Item> bin, long free) {
    }

    void measureAgain() {
        this.measuring = true;
        Minecraft game = Minecraft.getInstance();
        CompletableFuture.supplyAsync(Library::storage, Util.ioPool()).whenComplete((measured, error) -> game.execute(() -> {
            this.measuring = false;
            if (error != null || measured == null) {
                Host.LOGGER.error("Dailies: the space Dailies takes could not be measured", error);
                Notices.show(t("storage.failed"));
                return;
            }
            this.report = measured;
            this.free = measured.free;
            if (this.view == View.STORAGE) {
                this.rebuildRows();
            }
        }));
    }

    // ------------------------------------------------------------------ What the buttons do

    /** File work away from the client thread; a refusal is told as it is, then the folder is looked at again */
    void work(Controls.FileWork job, @Nullable Component didIt) {
        this.looking = true;
        Minecraft game = Minecraft.getInstance();
        CompletableFuture.runAsync(() -> {
            try {
                job.run();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, Util.ioPool()).whenComplete((nothing, error) -> game.execute(() -> {
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                if (!(cause instanceof IllegalStateException) && !(cause instanceof IllegalArgumentException)) {
                    Host.LOGGER.error("Dailies: a change to the recordings folder failed", cause);
                }
                Notices.show(Component.translatable("dailies.ui.notice.refused", String.valueOf(cause.getMessage())));
            } else if (didIt != null) {
                Notices.show(didIt);
            }
            this.lookAgain();
        }));
    }

    private void openFolder() {
        Controls.offThread(() -> {
            Path where = this.current().folder();
            Files.createDirectories(where);
            Util.getPlatform().openPath(where);
        }, t("folder_failed"), () -> {
        });
    }

    @Override
    public void removed() {
        super.removed();
        this.recordings.closed();
        this.lookedOnce = false;
    }

    @Override
    public void onClose() {
        this.look++;
        super.onClose();
    }

    @Override
    boolean keyElsewhere(int keyCode, int modifiers) {
        return this.current().key(keyCode, modifiers);
    }

    @Override
    void typedElsewhere(char character, int modifiers) {
        this.current().typed(character, modifiers);
    }

    static String nameOf(LibraryIndex.Entry entry) {
        if (entry.name != null && !entry.name.isBlank()) {
            return entry.name;
        }
        String file = entry.file.substring(entry.file.lastIndexOf('/') + 1);
        return file.endsWith(RecordingFile.EXTENSION) ? file.substring(0, file.length() - RecordingFile.EXTENSION.length()) : file;
    }

    // ------------------------------------------------------------------ For the scripted test

    /** Which view is on show: recordings, bin or storage */
    public String viewShown() {
        return this.view.name().toLowerCase(Locale.ROOT);
    }

    public void showView(String which) {
        this.show(View.valueOf(which.toUpperCase(Locale.ROOT)));
    }

    public void setSearch(String words) {
        this.recordings.searched(words);
    }

    public void setSort(String order) {
        this.recordings.sort = LibraryQuery.Sort.valueOf(order.toUpperCase(Locale.ROOT));
        this.rebuildRows();
    }

    public void setFolder(String relative) {
        this.recordings.folder = relative;
        this.rebuildRows();
    }

    /** The names of the recordings on show, in order */
    public List<String> namesShown() {
        List<String> names = new ArrayList<>();
        this.recordings.shown.forEach(entry -> names.add(nameOf(entry)));
        return names;
    }

    public boolean stillLooking() {
        return this.looking || this.measuring;
    }

    public void lookNow() {
        this.lookAgain();
    }

    /** Chooses recordings by name, as clicks with Ctrl held would */
    public void chooseByName(String... names) {
        this.recordings.chosen.clear();
        for (String name : names) {
            for (LibraryIndex.Entry entry : this.recordings.shown) {
                if (nameOf(entry).equals(name)) {
                    this.recordings.chosen.add(entry.file);
                }
            }
        }
    }

    public int chosenCount() {
        return this.recordings.chosenEntries().size();
    }

    /** Presses a button of the foot by the last part of its key (watch, delete, combine, restore, empty_bin and so on); returns whether it could be used */
    public boolean press(String key) {
        RecordingsView r = this.recordings;
        Gadget.Push button = switch (key) {
            case "watch" -> r.watch;
            case "edit" -> r.edit;
            case "star" -> r.star;
            case "move" -> r.moveTo;
            case "combine" -> r.combine;
            case "trim" -> r.trim;
            case "delete" -> r.delete;
            case "restore" -> this.bin.restore;
            case "empty_bin" -> this.bin.emptyBin;
            case "measure" -> this.storage.measure;
            default -> throw new IllegalArgumentException("no button " + key);
        };
        if (!button.usable.getAsBoolean()) {
            return false;
        }
        button.key(GLFW.GLFW_KEY_ENTER, 0);
        return true;
    }

    /** Chooses the item in the bin with this name */
    public boolean chooseInBin(String name) {
        return this.bin.chooseByName(name);
    }

    public List<String> binNames() {
        List<String> names = new ArrayList<>();
        this.binItems.forEach(item -> names.add(item.name));
        return names;
    }

    public @Nullable StorageReport storageShown() {
        return this.report;
    }

    /** The lines of the pane of details as it stands, each "name: value" */
    public List<String> detailsLines() {
        List<String> lines = new ArrayList<>();
        for (Gadget row : this.side.rows()) {
            if (row instanceof LibraryBits.Fact fact) {
                lines.add(fact.label.getString() + ": " + fact.value);
            }
        }
        return lines;
    }

    /** Whether the details of the one recording chosen have been read from its file, mods and spans included */
    public boolean detailsRead() {
        List<LibraryIndex.Entry> entries = this.recordings.chosenEntries();
        return entries.size() == 1 && entries.get(0).file.equals(this.recordings.detailsFor) && this.recordings.detailsManifest != null;
    }

    /** Every control on the plate with nothing to explain it when the mouse rests on it: should be none */
    public List<String> controlsWithoutTips() {
        List<String> missing = new ArrayList<>();
        for (Gadget gadget : this.fixed) {
            if (gadget.height > 0 && gadget != this.barWords && gadget.tipAt(gadget.x + 1, gadget.y + 1) == null) {
                missing.add(gadget.getClass().getSimpleName() + " at " + gadget.x + "," + gadget.y);
            }
        }
        return missing;
    }

    /** Controls on the plate that overlap one another or stick out of it: should be none */
    public List<String> crowded() {
        List<String> trouble = new ArrayList<>();
        List<Gadget> placed = new ArrayList<>();
        for (Gadget gadget : this.fixed) {
            if (gadget.height > 0) {
                if (gadget.x < this.left || gadget.x + gadget.width > this.right || gadget.y < this.top || gadget.y + gadget.height > this.bottom) {
                    trouble.add(gadget.getClass().getSimpleName() + " sticks out of the plate");
                }
                for (Gadget other : placed) {
                    if (gadget.x < other.x + other.width && other.x < gadget.x + gadget.width && gadget.y < other.y + other.height && other.y < gadget.y + gadget.height) {
                        trouble.add(gadget.getClass().getSimpleName() + " overlaps " + other.getClass().getSimpleName());
                    }
                }
                placed.add(gadget);
            }
        }
        return trouble;
    }

}
