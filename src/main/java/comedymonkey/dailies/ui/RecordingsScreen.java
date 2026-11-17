package comedymonkey.dailies.ui;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.file.RecordingFile;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.library.Bin;
import comedymonkey.dailies.library.DiskGuard;
import comedymonkey.dailies.library.Library;
import comedymonkey.dailies.library.LibraryIndex;
import comedymonkey.dailies.library.LibraryQuery;
import comedymonkey.dailies.library.Retention;
import comedymonkey.dailies.library.StorageReport;
import comedymonkey.dailies.playback.Playback;
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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The library (specification REC-5, REC-8 and REC-9), reached from the title screen and the pause menu. Three views
 * share one plate, chosen from the dropdown at its top left:
 * <ul>
 *     <li>Recordings: searched by name, world, player or date, in the order chosen, folder by folder or all at once,
 *     with a pane of details for the one chosen. Several can be chosen with Ctrl and Shift, to combine, move or
 *     delete together.</li>
 *     <li>The bin: what was deleted, how long it has left, and putting it back. Emptying it is the only delete for
 *     good, and asks first.</li>
 *     <li>Storage: how much room recordings, moments audio, exports and the bin take, per world, and the settings that
 *     keep it in check.</li>
 * </ul>
 * Folders are real folders of the recordings folder. The folder is only ever read, and files only ever changed, away
 * from the client thread.
 */
public final class RecordingsScreen extends KitScreen {

    enum View {
        RECORDINGS, BIN, STORAGE
    }

    private static final int ROW_HEIGHT = 26;
    private static final String KEYS = "dailies.ui.list.";
    private static final int LIST_SHARE = 58;

    private View view = View.RECORDINGS;

    // What was found, by the last look at the folder
    private List<LibraryIndex.Entry> all = List.of();
    private List<String> folderList = List.of();
    private Set<String> starredIds = Set.of();
    private List<Bin.Item> binItems = List.of();
    private @Nullable StorageReport report = null;
    private long free = -1;
    private boolean looking = false;
    private boolean lookedOnce = false;
    private boolean measuring = false;
    /** Goes up with every look, so an answer that arrives after a newer question is ignored */
    private int look = 0;

    // What is shown of it
    private String folder = LibraryQuery.ALL;
    private String search = "";
    private LibraryQuery.Sort sort = LibraryQuery.Sort.NEWEST;
    private boolean starredOnly = false;
    private List<LibraryIndex.Entry> shown = List.of();
    /** The recordings chosen, by file relative to the recordings folder, in the order they were chosen */
    private final LinkedHashSet<String> chosen = new LinkedHashSet<>();
    private @Nullable String anchor = null;
    private @Nullable String chosenBin = null;

    // The details of the one recording chosen
    private @Nullable String detailsFor = null;
    private @Nullable RecordingManifest detailsManifest = null;
    private LibraryBits.@Nullable Picture detailsPicture = null;
    private int detailsLook = 0;
    private String detailsBuiltFor = "";

    /** Settings changed here and on their way to the file, so the boxes show them meanwhile */
    private final Map<String, Object> pending = new HashMap<>();

    private final Form rows = new Form();
    private final Form side = new Form();

    private Gadget.Dropdown<View> viewPick;
    private Gadget.Dropdown<String> folderPick;
    private Entry searchBox;
    private Gadget.Dropdown<LibraryQuery.Sort> sortPick;
    private Gadget.Check starredCheck;
    private Gadget.Words barWords;

    private Gadget.Push watch;
    private Gadget.Push edit;
    private Gadget.Push star;
    private Gadget.Push moveTo;
    private Gadget.Push combine;
    private Gadget.Push trim;
    private Gadget.Push delete;
    private Gadget.Push restore;
    private Gadget.Push emptyBin;
    private Gadget.Push measure;
    private Gadget.Push settings;
    private Gadget.Push folders;
    private Gadget.Push openFolder;
    private Gadget.Push done;
    private Gadget.Push share;
    private Gadget.Push importing;

    public RecordingsScreen(@Nullable Screen parent) {
        super(Component.translatable("dailies.ui.list.title"), parent);
    }

    private static Component t(String key, Object... arguments) {
        return Component.translatable(KEYS + key, arguments);
    }

    // ------------------------------------------------------------------ Building

    @Override
    void build() {
        this.viewPick = new Gadget.Dropdown<>(List.of(View.values()), which -> t("view." + which.name().toLowerCase(Locale.ROOT)), () -> this.view, this::show);
        this.viewPick.tip(t("view.tip"));
        this.folderPick = new Gadget.Dropdown<>(this::folderChoices, RecordingsScreen::folderName, () -> this.folder, chosenFolder -> {
            this.folder = chosenFolder;
            this.rebuildRows();
        });
        this.folderPick.tip(t("folder.tip"));
        this.searchBox = new Entry(() -> this.search, typed -> {
            this.searched(typed);
            return null;
        }).live(typed -> {
            this.searched(typed);
            return null;
        }).max(120).hint(t("search.hint"));
        this.searchBox.tip(t("search.tip"));
        this.sortPick = new Gadget.Dropdown<>(List.of(LibraryQuery.Sort.values()), order -> t("sort." + order.name().toLowerCase(Locale.ROOT)), () -> this.sort, order -> {
            this.sort = order;
            this.rebuildRows();
        });
        this.sortPick.tip(t("sort.tip"));
        this.starredCheck = new Gadget.Check(t("starred_only"), () -> this.starredOnly, on -> {
            this.starredOnly = on;
            this.rebuildRows();
        });
        this.starredCheck.tip(t("starred_only.tip"));
        this.barWords = new Gadget.Words(this::barWords, Controls.PARCHMENT_DIM);

        this.watch = push("watch", this::watchChosen, () -> this.chosenEntries().size() == 1);
        this.edit = push("edit", this::editChosen, () -> this.chosenEntries().size() == 1);
        this.star = new Gadget.Push(() -> t(this.allStarred() ? "unstar" : "star"), this::starChosen);
        this.star.when(() -> !this.chosenEntries().isEmpty());
        this.star.liveTip(() -> t(this.allStarred() ? "unstar.tip" : "star.tip"));
        this.moveTo = push("move", this::openMoveMenu, () -> !this.chosenEntries().isEmpty());
        this.combine = push("combine", this::combineChosen, () -> this.chosenEntries().size() >= 2);
        this.trim = push("trim", this::trimChosen, () -> this.chosenEntries().size() == 1);
        this.delete = push("delete", this::deleteChosen, () -> !this.chosenEntries().isEmpty());
        this.restore = push("restore", this::restoreChosen, () -> this.chosenBinItem() != null);
        this.emptyBin = push("empty_bin", this::askEmptyBin, () -> !this.binItems.isEmpty());
        this.measure = push("measure", this::measureAgain, () -> !this.measuring);
        this.settings = push("settings", () -> SettingsScreen.open(this), () -> true);
        this.folders = push("folders", this::openFolderMenu, () -> true);
        this.openFolder = push("open_folder", this::openFolder, () -> true);
        this.share = push("share", this::shareChosen, () -> this.chosenEntries().size() == 1);
        this.importing = push("import", this::importShared, () -> true);
        this.done = new Gadget.Push(t("done"), this::onClose);
        this.done.tip(t("done.tip"));

        this.fixed.add(this.viewPick);
        this.fixed.add(this.folderPick);
        this.fixed.add(this.searchBox);
        this.fixed.add(this.sortPick);
        this.fixed.add(this.starredCheck);
        this.fixed.add(this.barWords);
        for (Gadget button : List.of(this.watch, this.edit, this.star, this.moveTo, this.combine, this.trim, this.delete, this.restore, this.emptyBin,
                this.measure, this.settings, this.folders, this.openFolder, this.share, this.importing, this.done)) {
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

    private static Gadget.Push push(String key, Runnable action, java.util.function.BooleanSupplier usable) {
        Gadget.Push button = new Gadget.Push(t(key), action);
        button.when(usable).tip(t(key + ".tip"));
        return button;
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

    private void searched(String typed) {
        if (!typed.equals(this.search)) {
            this.search = typed;
            this.rebuildRows();
        }
    }

    private List<String> folderChoices() {
        List<String> choices = new ArrayList<>();
        choices.add(LibraryQuery.ALL);
        choices.add(LibraryQuery.TOP);
        choices.addAll(this.folderList);
        if (!choices.contains(this.folder)) {
            choices.add(this.folder);
        }
        return choices;
    }

    private static Component folderName(String folder) {
        if (LibraryQuery.ALL.equals(folder)) {
            return t("folder.all");
        }
        if (LibraryQuery.TOP.equals(folder)) {
            return t("folder.top");
        }
        return Component.literal(folder.replace("/", " / "));
    }

    private Component barWords() {
        if (this.view == View.BIN) {
            return t("bin.explained", Library.binDays());
        }
        if (this.view == View.STORAGE) {
            return this.free < 0 ? t("storage.measuring") : t("storage.free", StorageReport.bytes(this.free));
        }
        return Component.empty();
    }

    // ------------------------------------------------------------------ Setting out

    private @Nullable Component count() {
        return switch (this.view) {
            case RECORDINGS -> {
                if (this.all.isEmpty()) {
                    yield null;
                }
                Component total = Component.translatable(this.all.size() == 1 ? "dailies.ui.list.count_one" : "dailies.ui.list.count", this.all.size());
                yield this.shown.size() == this.all.size() ? total : t("count_shown", total, this.shown.size());
            }
            case BIN -> t(this.binItems.size() == 1 ? "bin.count_one" : "bin.count", this.binItems.size(), StorageReport.bytes(this.binItems.stream().mapToLong(item -> item.size).sum()));
            case STORAGE -> this.report == null ? null : t("storage.count", StorageReport.bytes(this.report.total()));
        };
    }

    private static void hide(Font font, Gadget... gadgets) {
        for (Gadget gadget : gadgets) {
            gadget.clip(Integer.MIN_VALUE, Integer.MAX_VALUE);
            gadget.place(font, -1000, -1000, 0, 0);
        }
    }

    private static void put(Font font, Gadget gadget, int x, int y, int width) {
        gadget.clip(Integer.MIN_VALUE, Integer.MAX_VALUE);
        gadget.place(font, x, y, Math.max(10, width), Kit.ROW);
    }

    @Override
    void arrange(Font font) {
        this.centre(860, this.height - 24);
        int head = this.headingHeight(font, this.count());
        int inner = this.right - this.left - 16;
        int bar = this.top + head;
        int x = this.left + 8;
        put(font, this.viewPick, x, bar, 104);
        x += 104 + Kit.GAP * 3;
        if (this.view == View.RECORDINGS) {
            int checkWidth = Math.min(96, this.starredCheck.wantedWidth(font) + 4);
            int sortWidth = 118;
            int folderWidth = Math.min(150, Math.max(90, inner / 6));
            put(font, this.folderPick, x, bar, folderWidth);
            x += folderWidth + Kit.GAP * 3;
            int checkLeft = this.right - 8 - checkWidth;
            int sortLeft = checkLeft - Kit.GAP * 3 - sortWidth;
            put(font, this.searchBox, x, bar, sortLeft - Kit.GAP * 3 - x);
            put(font, this.sortPick, sortLeft, bar, sortWidth);
            put(font, this.starredCheck, checkLeft, bar, checkWidth);
            hide(font, this.barWords);
        } else {
            put(font, this.barWords, x, bar, this.right - 8 - x);
            hide(font, this.folderPick, this.searchBox, this.sortPick, this.starredCheck);
        }

        int listTop = bar + Kit.ROW + 6;
        int foot = Kit.ROW + 2 + 8 + 6;
        int listBottom = this.bottom - foot;
        int listWidth = (this.right - this.left - 10) * LIST_SHARE / 100;
        int split = this.left + 5 + listWidth;
        this.rows.place(font, this.left + 5, listTop, listWidth, listBottom - listTop);
        this.side.place(font, split + 6, listTop, this.right - 5 - split - 6, listBottom - listTop);
        this.refreshSide();

        Gadget[] recordingButtons = {this.watch, this.edit, this.star, this.moveTo, this.combine, this.trim, this.delete, this.share};
        switch (this.view) {
            case RECORDINGS -> {
                hide(font, this.restore, this.emptyBin, this.measure, this.settings);
                this.footLeft(font, recordingButtons);
                this.foot(font, this.importing, this.folders, this.openFolder, this.done);
            }
            case BIN -> {
                hide(font, recordingButtons);
                hide(font, this.measure, this.settings, this.folders, this.importing);
                this.footLeft(font, this.restore, this.emptyBin);
                this.foot(font, this.openFolder, this.done);
            }
            case STORAGE -> {
                hide(font, recordingButtons);
                hide(font, this.restore, this.emptyBin, this.folders, this.importing);
                this.footLeft(font, this.measure, this.settings);
                this.foot(font, this.openFolder, this.done);
            }
        }
    }

    @Override
    void paint(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        this.heading(graphics, font, this.count());
        // A thin line between the list and the pane beside it
        int lineX = this.side.x - 3;
        graphics.fill(lineX, this.rows.y + 2, lineX + 1, this.rows.y + this.rows.height - 2, 0x40D4B25A);
        boolean empty = switch (this.view) {
            case RECORDINGS -> this.rows.isEmpty();
            case BIN -> this.binItems.isEmpty();
            case STORAGE -> this.report == null;
        };
        if (!empty) {
            return;
        }
        int middle = this.rows.y + this.rows.height / 2;
        int wide = this.rows.width - 10;
        if (this.looking || this.measuring) {
            Kit.centred(graphics, font, t("looking").getString(), this.rows.x + 5, middle - 4, wide, Controls.PARCHMENT_DIM);
            return;
        }
        String first;
        String second;
        if (this.view == View.BIN) {
            first = t("bin.empty").getString();
            second = t("bin.empty_hint", Library.binDays()).getString();
        } else if (this.all.isEmpty()) {
            first = t("none").getString();
            second = t("none_hint").getString();
        } else {
            first = t("none_match").getString();
            second = t("none_match_hint").getString();
        }
        Kit.centred(graphics, font, first, this.rows.x + 5, middle - 11, wide, Controls.PARCHMENT);
        Kit.centred(graphics, font, second, this.rows.x + 5, middle + 2, wide, Controls.PARCHMENT_DIM);
    }

    // ------------------------------------------------------------------ The rows

    private void rebuildRows() {
        this.rows.clear();
        switch (this.view) {
            case RECORDINGS -> {
                this.shown = LibraryQuery.apply(this.all, this.folder, this.search, this.sort, this.starredIds, this.starredOnly, ZoneId.systemDefault());
                if (this.search.isBlank() && !LibraryQuery.ALL.equals(this.folder)) {
                    if (!LibraryQuery.TOP.equals(this.folder)) {
                        int slash = this.folder.lastIndexOf('/');
                        this.rows.add(new FolderRow(slash < 0 ? LibraryQuery.TOP : this.folder.substring(0, slash), true));
                    }
                    for (String child : LibraryQuery.childrenOf(this.folderList, this.folder)) {
                        this.rows.add(new FolderRow(child, false));
                    }
                }
                Set<String> still = new java.util.HashSet<>();
                for (LibraryIndex.Entry entry : this.shown) {
                    this.rows.add(new Row(entry));
                    still.add(entry.file);
                }
                this.chosen.retainAll(still);
            }
            case BIN -> {
                boolean found = false;
                for (Bin.Item item : this.binItems) {
                    this.rows.add(new BinRow(item));
                    found |= item.id.equals(this.chosenBin);
                }
                if (!found) {
                    this.chosenBin = null;
                }
            }
            case STORAGE -> this.buildStorage();
        }
        this.detailsBuiltFor = "";
    }

    /** Reads the folder and the bin again, off the client thread */
    private void lookAgain() {
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
            if (!LibraryQuery.ALL.equals(this.folder) && !LibraryQuery.TOP.equals(this.folder) && !this.folderList.contains(this.folder)) {
                this.folder = LibraryQuery.ALL;
            }
            this.rebuildRows();
        }));
    }

    private record Found(List<LibraryIndex.Entry> entries, List<String> folders, Set<String> starred, List<Bin.Item> bin, long free) {
    }

    private void measureAgain() {
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

    private List<LibraryIndex.Entry> chosenEntries() {
        if (this.view != View.RECORDINGS || this.chosen.isEmpty()) {
            return List.of();
        }
        List<LibraryIndex.Entry> list = new ArrayList<>();
        for (LibraryIndex.Entry entry : this.shown) {
            if (this.chosen.contains(entry.file)) {
                list.add(entry);
            }
        }
        return list;
    }

    private @Nullable Bin.Item chosenBinItem() {
        if (this.view != View.BIN) {
            return null;
        }
        for (Bin.Item item : this.binItems) {
            if (item.id.equals(this.chosenBin)) {
                return item;
            }
        }
        return null;
    }

    private boolean allStarred() {
        List<LibraryIndex.Entry> entries = this.chosenEntries();
        return !entries.isEmpty() && entries.stream().allMatch(entry -> this.starredIds.contains(entry.id));
    }

    /** A click on a recording: alone it chooses only that one, with Ctrl it is added or taken away, with Shift the run from the last one is chosen */
    private void choose(LibraryIndex.Entry entry) {
        if (Screen.hasShiftDown() && this.anchor != null) {
            int from = -1;
            int to = -1;
            for (int i = 0; i < this.shown.size(); i++) {
                if (this.shown.get(i).file.equals(this.anchor)) {
                    from = i;
                }
                if (this.shown.get(i).file.equals(entry.file)) {
                    to = i;
                }
            }
            if (from >= 0 && to >= 0) {
                if (!Screen.hasControlDown()) {
                    this.chosen.clear();
                }
                for (int i = Math.min(from, to); i <= Math.max(from, to); i++) {
                    this.chosen.add(this.shown.get(i).file);
                }
                return;
            }
        }
        if (Screen.hasControlDown()) {
            if (!this.chosen.remove(entry.file)) {
                this.chosen.add(entry.file);
            }
        } else {
            this.chosen.clear();
            this.chosen.add(entry.file);
        }
        this.anchor = entry.file;
    }

    // ------------------------------------------------------------------ The pane beside the list

    private void refreshSide() {
        String wanted;
        switch (this.view) {
            case RECORDINGS -> {
                List<LibraryIndex.Entry> entries = this.chosenEntries();
                if (entries.size() == 1) {
                    LibraryIndex.Entry entry = entries.get(0);
                    this.loadDetails(entry);
                    wanted = "one:" + entry.file + ":" + (this.detailsManifest != null && entry.file.equals(this.detailsFor)) + ":" + this.starredIds.contains(entry.id);
                } else {
                    wanted = "many:" + entries.size() + ":" + this.all.size();
                }
            }
            case BIN -> wanted = "bin:" + this.chosenBin + ":" + this.binItems.size();
            default -> wanted = "storage:" + (this.report == null ? 0 : this.report.hashCode()) + ":" + this.pending.size();
        }
        if (wanted.equals(this.detailsBuiltFor)) {
            return;
        }
        this.detailsBuiltFor = wanted;
        this.side.clear();
        switch (this.view) {
            case RECORDINGS -> this.buildDetails();
            case BIN -> this.buildBinDetails();
            case STORAGE -> this.buildStorageSettings();
        }
    }

    private void loadDetails(LibraryIndex.Entry entry) {
        if (entry.file.equals(this.detailsFor)) {
            return;
        }
        this.detailsFor = entry.file;
        this.detailsManifest = null;
        if (this.detailsPicture != null) {
            this.detailsPicture.close();
            this.detailsPicture = null;
        }
        int asked = ++this.detailsLook;
        Path file = Library.fileOf(entry);
        Minecraft game = Minecraft.getInstance();
        CompletableFuture.supplyAsync(() -> {
            try (RecordingFile recording = RecordingFile.open(file)) {
                return new Details(recording.manifest(), recording.preview());
            } catch (Exception e) {
                return null;
            }
        }, Util.ioPool()).whenComplete((details, error) -> game.execute(() -> {
            if (asked != this.detailsLook || details == null) {
                return;
            }
            this.detailsManifest = details.manifest();
            this.detailsPicture = LibraryBits.Picture.of(details.preview());
        }));
    }

    private record Details(RecordingManifest manifest, byte @Nullable [] preview) {
    }

    private void buildDetails() {
        List<LibraryIndex.Entry> entries = this.chosenEntries();
        if (entries.isEmpty()) {
            this.side.add(new Gadget.Note(t("details.none")));
            return;
        }
        if (entries.size() > 1) {
            long size = 0;
            int ticks = 0;
            for (LibraryIndex.Entry entry : entries) {
                size += entry.size;
                ticks += entry.totalTicks;
            }
            this.side.add(new Gadget.Heading(t("details.many", entries.size())));
            this.side.add(new Fact(t("details.length"), Controls.length(ticks)));
            this.side.add(new Fact(t("details.size"), StorageReport.bytes(size)));
            this.side.add(new Gadget.Note(t("details.many_hint")));
            return;
        }
        LibraryIndex.Entry entry = entries.get(0);
        this.side.add(new PictureBox(() -> entry.file.equals(this.detailsFor) ? this.detailsPicture : null));
        this.side.add(new Gadget.Heading(Component.literal((this.starredIds.contains(entry.id) ? "★ " : "") + nameOf(entry))));
        this.side.add(new Fact(t("details.world"), entry.world.isBlank() ? t("details.unknown").getString() : entry.world));
        this.side.add(new Fact(t("details.players"), entry.players.isEmpty() ? t("details.unknown").getString() : String.join(", ", entry.players)));
        this.side.add(new Fact(t("details.recorded"), LibraryBits.date(entry.created > 0 ? entry.created : entry.modified)));
        if (entry.isEdited()) {
            this.side.add(new Fact(t("details.edited"), LibraryBits.date(entry.edited)));
        }
        this.side.add(new Fact(t("details.length"), Controls.length(entry.totalTicks)));
        this.side.add(new Fact(t("details.size"), StorageReport.bytes(entry.size)));
        String where = LibraryQuery.folderOf(entry);
        this.side.add(new Fact(t("details.folder"), where.isEmpty() ? t("folder.top").getString() : where));
        this.side.add(new Fact(t("details.file"), entry.file.substring(entry.file.lastIndexOf('/') + 1)));
        RecordingManifest manifest = entry.file.equals(this.detailsFor) ? this.detailsManifest : null;
        if (manifest == null) {
            this.side.add(new Gadget.Note(() -> t("looking"), Controls.PARCHMENT_FAINT));
            return;
        }
        if (manifest.isEdited()) {
            this.side.add(new Gadget.Heading(t("details.made_from")));
            Map<String, String> names = new HashMap<>();
            for (RecordingManifest.SourceEntry source : manifest.sources) {
                names.put(source.id, source.name.isBlank() ? source.id.substring(0, Math.min(8, source.id.length())) : source.name);
            }
            for (RecordingManifest.SpanEntry span : manifest.spans) {
                comedymonkey.dailies.file.Spans.Span piece = span.span();
                String name = names.getOrDefault(piece.source(), piece.source().substring(0, Math.min(8, piece.source().length())));
                this.side.add(new Fact(Component.literal(Controls.time(piece.here())), t("details.span", name, Controls.time(piece.sourceStart()), Controls.time(piece.sourceEnd())).getString()));
            }
        }
        this.side.add(new Gadget.Heading(t("details.mods", manifest.mods.size())));
        StringBuilder mods = new StringBuilder();
        for (RecordingManifest.ModEntry mod : manifest.mods) {
            if (!mod.id.equals("minecraft") && !mod.id.equals("neoforge")) {
                mods.append(mods.isEmpty() ? "" : ", ").append(mod.id).append(' ').append(mod.version);
            }
        }
        this.side.add(new Gadget.Note(Component.literal(mods.isEmpty() ? t("details.no_mods").getString() : mods.toString())));
        this.side.add(new Fact(t("details.game"), manifest.gameVersion));
    }

    private void buildBinDetails() {
        Bin.Item item = this.chosenBinItem();
        if (item == null) {
            this.side.add(new Gadget.Note(t("bin.details.none", Library.binDays())));
            return;
        }
        this.side.add(new Gadget.Heading(Component.literal(item.name)));
        this.side.add(new Fact(t("bin.details.kind"), t("bin.kind." + item.kind).getString()));
        if (!item.world.isBlank()) {
            this.side.add(new Fact(item.kind() == Bin.Kind.SCENE ? t("bin.details.recording") : t("details.world"), item.world));
        }
        this.side.add(new Fact(t("bin.details.deleted"), LibraryBits.date(item.binned)));
        int left = Retention.daysLeft(item, Library.binDays(), System.currentTimeMillis());
        this.side.add(new Fact(t("bin.details.left"), t(left == 1 ? "bin.day_one" : "bin.days", left).getString()));
        this.side.add(new Fact(t("details.size"), StorageReport.bytes(item.size)));
        if (item.kind() != Bin.Kind.SCENE) {
            this.side.add(new Fact(t("bin.details.from"), item.from));
        }
        this.side.add(new Gadget.Note(t("bin.details.hint." + item.kind)));
    }

    // ------------------------------------------------------------------ The storage view

    private void buildStorage() {
        StorageReport measured = this.report;
        if (measured == null) {
            return;
        }
        this.rows.add(new Gadget.Heading(t("storage.used")));
        long most = Math.max(1, Math.max(Math.max(measured.recordings, measured.audio), Math.max(measured.exports, measured.bin)));
        this.rows.add(new Usage(t("storage.recordings"), measured.recordings, most, t("storage.recordings.tip")));
        this.rows.add(new Usage(t("storage.audio"), measured.audio, most, t("storage.audio.tip")));
        this.rows.add(new Usage(t("storage.exports"), measured.exports, most, t("storage.exports.tip")));
        this.rows.add(new Usage(t("storage.bin"), measured.bin, most, t("storage.bin.tip")));
        Retention.Disk disk = DiskGuard.state(measured.free);
        int colour = disk == Retention.Disk.FINE ? Controls.PARCHMENT_DIM : Controls.ALERT;
        Component freeWords = measured.free < 0 ? t("storage.free_unknown")
            : t("storage.free_of", StorageReport.bytes(measured.free), StorageReport.bytes(measured.disk));
        this.rows.add(new Gadget.Words(() -> freeWords, colour).tip(t("storage.free.tip." + disk.name().toLowerCase(Locale.ROOT),
            Host.get().setting("diskWarnGigabytes", 20), Host.get().setting("diskPauseGigabytes", 5))));
        this.rows.add(new Gadget.Heading(t("storage.by_world")));
        this.rows.add(new WorldRow(null));
        for (StorageReport.World world : measured.byWorld()) {
            this.rows.add(new WorldRow(world));
        }
    }

    private void buildStorageSettings() {
        this.side.add(new Gadget.Heading(t("storage.keeping")));
        this.side.add(number("binDays", 30, 1, 3650));
        this.side.add(number("retentionDays", 0, 0, 36500));
        this.side.add(this.check("voiceAudioOnceTranscribed"));
        this.side.add(number("voiceAudioDays", 0, 0, 36500));
        this.side.add(new Gadget.Heading(t("storage.disk")));
        this.side.add(number("diskWarnGigabytes", 20, 0, 100000));
        this.side.add(number("diskPauseGigabytes", 5, 0, 100000));
        this.side.add(new Gadget.Note(t("storage.zero_keeps")));
    }

    private Object settingNow(String name, Object fallback) {
        Object waiting = this.pending.get(name);
        return waiting != null ? waiting : Host.get().setting(name, fallback);
    }

    private Gadget number(String name, int fallback, int min, int max) {
        Entry box = Entry.number(() -> ((Number) this.settingNow(name, fallback)).doubleValue(), min, max, true, value -> {
            this.saveSetting(name, (int) Math.rint(value));
            return null;
        });
        Gadget pair = new Gadget.Pair(t("setting." + name), box.wide(64));
        pair.tip(t("setting." + name + ".tip"));
        box.tip(t("setting." + name + ".tip"));
        return pair;
    }

    private Gadget check(String name) {
        Gadget.Check box = new Gadget.Check(t("setting." + name), () -> Boolean.TRUE.equals(this.settingNow(name, false)), on -> this.saveSetting(name, on));
        box.tip(t("setting." + name + ".tip"));
        return box;
    }

    private void saveSetting(String name, Object value) {
        this.pending.put(name, value);
        Minecraft game = Minecraft.getInstance();
        CompletableFuture.runAsync(() -> Host.get().saveSetting(name, value), Util.ioPool()).whenComplete((nothing, error) -> game.execute(() -> {
            this.pending.remove(name, value);
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                Notices.show(Component.translatable("dailies.ui.notice.refused", String.valueOf(cause.getMessage())));
            }
            if (this.view == View.STORAGE) {
                this.rebuildRows();
            }
        }));
    }

    // ------------------------------------------------------------------ What the buttons do

    /** File work away from the client thread; a refusal is told as it is, then the folder is looked at again */
    private void work(Controls.FileWork job, @Nullable Component didIt) {
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

    /** Whether this is the recording being watched right now, which must not be changed underneath playback */
    private static boolean beingWatched(Path file) {
        RecordingFile open = Playback.active() ? Playback.file() : null;
        return open != null && open.file().toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize());
    }

    private void watchChosen() {
        List<LibraryIndex.Entry> entries = this.chosenEntries();
        if (entries.size() != 1) {
            return;
        }
        if (Recorder.get().state() != Recorder.State.IDLE) {
            Notices.show(t("finish_first"));
            return;
        }
        Path file = Library.fileOf(entries.get(0));
        Controls.guarded(() -> {
            this.minecraft.setScreen(null);
            Playback.open(file);
        });
    }

    private void editChosen() {
        List<LibraryIndex.Entry> entries = this.chosenEntries();
        if (entries.size() == 1) {
            this.minecraft.setScreen(new RecordingEditScreen(this, entries.get(0), () -> {
                this.detailsFor = null;
                this.lookAgain();
            }));
        }
    }

    private void starChosen() {
        List<LibraryIndex.Entry> entries = this.chosenEntries();
        boolean on = !this.allStarred();
        Set<String> now = new java.util.HashSet<>(this.starredIds);
        for (LibraryIndex.Entry entry : entries) {
            if (on) {
                now.add(entry.id);
            } else {
                now.remove(entry.id);
            }
        }
        this.starredIds = Set.copyOf(now);
        this.detailsBuiltFor = "";
        this.work(() -> {
            for (LibraryIndex.Entry entry : entries) {
                Library.star(entry.id, on);
            }
        }, null);
    }

    private void openMoveMenu() {
        List<LibraryIndex.Entry> entries = this.chosenEntries();
        if (entries.isEmpty()) {
            return;
        }
        List<Menu.Item> items = new ArrayList<>();
        List<String> targets = new ArrayList<>();
        targets.add(LibraryQuery.TOP);
        targets.addAll(this.folderList);
        for (String target : targets) {
            boolean already = entries.stream().allMatch(entry -> LibraryQuery.folderOf(entry).equals(target));
            items.add(new Menu.Item(folderName(target), () -> this.moveChosen(entries, target), !already, already));
        }
        items.add(new Menu.Item(t("move.new_folder"), () -> this.askFolderName(t("folder.new.title"), "", name -> {
            String parent = LibraryQuery.ALL.equals(this.folder) ? LibraryQuery.TOP : this.folder;
            this.work(() -> {
                String made = Library.createFolder(parent, name);
                Library.moveInto(files(entries), made);
            }, t("moved", entries.size(), name));
        })));
        Menu.drop(items, this.moveTo.x, this.moveTo.y, Math.max(140, this.moveTo.width), this.moveTo.height, this.moveTo);
    }

    private static List<Path> files(List<LibraryIndex.Entry> entries) {
        List<Path> list = new ArrayList<>();
        for (LibraryIndex.Entry entry : entries) {
            list.add(Library.fileOf(entry));
        }
        return list;
    }

    private void moveChosen(List<LibraryIndex.Entry> entries, String target) {
        for (LibraryIndex.Entry entry : entries) {
            if (beingWatched(Library.fileOf(entry))) {
                Notices.show(t("close_first"));
                return;
            }
        }
        this.work(() -> Library.moveInto(files(entries), target), t("moved", entries.size(), folderName(target)));
    }

    private void combineChosen() {
        List<LibraryIndex.Entry> entries = this.chosenEntries();
        if (entries.size() >= 2) {
            List<LibraryIndex.Entry> ordered = new ArrayList<>(entries);
            // Oldest footage first: what a person combining a week of sessions nearly always wants
            ordered.sort(java.util.Comparator.comparingLong(LibraryIndex.Entry::newest));
            this.minecraft.setScreen(new CombineScreen(this, ordered, this.all));
        }
    }

    private void trimChosen() {
        List<LibraryIndex.Entry> entries = this.chosenEntries();
        if (entries.size() == 1) {
            this.minecraft.setScreen(new TrimScreen(this, Library.fileOf(entries.get(0)), entries.get(0).id, nameOf(entries.get(0)), entries.get(0).totalTicks, -1, -1));
        }
    }

    private void deleteChosen() {
        List<LibraryIndex.Entry> entries = this.chosenEntries();
        for (LibraryIndex.Entry entry : entries) {
            if (beingWatched(Library.fileOf(entry))) {
                Notices.show(t("close_first"));
                return;
            }
        }
        // Into the bin, so nothing is asked: it can be put back from the bin view
        this.chosen.clear();
        Component told = entries.size() == 1 ? t("binned_one", nameOf(entries.get(0)), Library.binDays()) : t("binned", entries.size(), Library.binDays());
        this.work(() -> {
            for (Path file : files(entries)) {
                Library.delete(file);
            }
        }, told);
    }

    private void restoreChosen() {
        Bin.Item item = this.chosenBinItem();
        if (item == null) {
            return;
        }
        this.chosenBin = null;
        this.looking = true;
        Minecraft game = Minecraft.getInstance();
        CompletableFuture.supplyAsync(() -> {
            try {
                return Library.restore(item.id);
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, Util.ioPool()).whenComplete((words, error) -> game.execute(() -> {
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                Notices.show(Component.translatable("dailies.ui.notice.refused", String.valueOf(cause.getMessage())));
            } else {
                Notices.show(Component.literal(words));
            }
            this.lookAgain();
        }));
    }

    private void askEmptyBin() {
        long size = this.binItems.stream().mapToLong(item -> item.size).sum();
        int count = this.binItems.size();
        this.minecraft.setScreen(new AskScreen(yes -> {
            this.minecraft.setScreen(this);
            if (yes) {
                this.chosenBin = null;
                this.work(Library::emptyBin, t("bin.emptied", count));
            }
        }, t("bin.ask.title"), t(count == 1 ? "bin.ask.question_one" : "bin.ask.question", count, StorageReport.bytes(size)), t("bin.ask.yes"), t("bin.ask.no")));
    }

    private void openFolderMenu() {
        boolean real = !LibraryQuery.ALL.equals(this.folder) && !LibraryQuery.TOP.equals(this.folder);
        List<Menu.Item> items = new ArrayList<>();
        items.add(new Menu.Item(t("folder.new"), () -> this.askFolderName(t("folder.new.title"), "", name -> {
            String parent = real ? this.folder : LibraryQuery.TOP;
            String made = (parent.isEmpty() ? "" : parent + "/") + name.trim();
            this.folder = made;
            this.work(() -> Library.createFolder(parent, name), t("folder.made", name));
        })));
        items.add(new Menu.Item(t("folder.rename"), () -> this.askFolderName(t("folder.rename.title"), this.folder.substring(this.folder.lastIndexOf('/') + 1), name -> {
            String old = this.folder;
            int slash = old.lastIndexOf('/');
            this.folder = (slash < 0 ? "" : old.substring(0, slash + 1)) + name.trim();
            this.work(() -> Library.renameFolder(old, name), null);
        }), real, false));
        items.add(new Menu.Item(t("folder.delete"), () -> {
            String old = this.folder;
            int slash = old.lastIndexOf('/');
            this.folder = slash < 0 ? LibraryQuery.TOP : old.substring(0, slash);
            this.work(() -> Library.deleteFolder(old), t("folder.deleted", old));
        }, real, false));
        Menu.drop(items, this.folders.x, this.folders.y, Math.max(150, this.folders.width), this.folders.height, this.folders);
    }

    private void askFolderName(Component title, String suggestion, java.util.function.Consumer<String> then) {
        this.minecraft.setScreen(new NameScreen(title, t("folder.explanation"), suggestion, t("folder.save"), t("folder.cancel"), name -> {
            this.minecraft.setScreen(this);
            String bad = LibraryQuery.badFolderName(name);
            if (bad != null) {
                Notices.show(Component.translatable("dailies.ui.notice.refused", bad));
                return;
            }
            then.accept(name.trim());
        }, () -> this.minecraft.setScreen(this)));
    }

    /** "Share session" (FILE-15): the chosen recording and its moments, packed into one file for a friend */
    private void shareChosen() {
        List<LibraryIndex.Entry> chosen = this.chosenEntries();
        if (chosen.size() != 1) {
            return;
        }
        LibraryIndex.Entry entry = chosen.get(0);
        Path file = comedymonkey.dailies.capture.Recorder.folder().resolve(entry.file);
        Path[] made = new Path[1];
        Notices.show(Component.translatable("dailies.ui.share.working", nameOf(entry)));
        Controls.offThread(() -> {
            made[0] = comedymonkey.dailies.library.Sharing.share(file, null);
            Util.getPlatform().openPath(made[0].getParent());
        }, Component.translatable("dailies.ui.share.failed"), () -> {
            if (made[0] != null) {
                Notices.show(Component.translatable("dailies.ui.share.done", made[0].getFileName().toString()));
            }
        });
    }

    /**
     * "Import" (FILE-15): every friend's shared session put in the import folder is taken into the library, and their
     * moments onto the page. With nothing there yet, the folder is opened so that one can be put in it.
     */
    private void importShared() {
        int[] counts = new int[2];
        Controls.offThread(() -> {
            Path inbox = comedymonkey.dailies.Host.get().dataDirectory().resolve("import");
            Files.createDirectories(inbox);
            java.util.List<Path> packs;
            try (java.util.stream.Stream<Path> list = Files.list(inbox)) {
                packs = list.filter(path -> path.getFileName().toString().endsWith(comedymonkey.dailies.library.SharePack.EXTENSION)).sorted().toList();
            }
            if (packs.isEmpty()) {
                Util.getPlatform().openPath(inbox);
                counts[1] = -1;
                return;
            }
            Path done = inbox.resolve("imported");
            Files.createDirectories(done);
            for (Path pack : packs) {
                try {
                    comedymonkey.dailies.library.Sharing.importPack(pack);
                    Files.move(pack, done.resolve(pack.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    counts[0]++;
                } catch (java.io.IOException | RuntimeException e) {
                    Host.LOGGER.warn("Dailies: the shared session {} could not be imported", pack.getFileName(), e);
                    counts[1]++;
                }
            }
        }, Component.translatable("dailies.ui.import.failed"), () -> {
            if (counts[1] < 0) {
                Notices.show(Component.translatable("dailies.ui.import.empty"));
            } else {
                Notices.show(Component.translatable(counts[1] == 0 ? "dailies.ui.import.done" : "dailies.ui.import.some_failed", counts[0], counts[1]));
            }
            this.lookAgain();
        });
    }

    private void openFolder() {
        Controls.offThread(() -> {
            Path where = this.view == View.BIN ? Library.bin().folder() : this.view == View.STORAGE ? Host.get().dataDirectory() : Recorder.folder();
            if (this.view == View.RECORDINGS && !LibraryQuery.ALL.equals(this.folder) && !LibraryQuery.TOP.equals(this.folder)) {
                where = Recorder.folder().resolve(this.folder);
            }
            Files.createDirectories(where);
            Util.getPlatform().openPath(where);
        }, t("folder_failed"), () -> {
        });
    }

    @Override
    public void removed() {
        super.removed();
        if (this.detailsPicture != null) {
            this.detailsPicture.close();
            this.detailsPicture = null;
        }
        // Read again when the screen is back, so the picture is made afresh
        this.detailsFor = null;
        this.lookedOnce = false;
    }

    @Override
    public void onClose() {
        this.look++;
        super.onClose();
    }

    @Override
    boolean keyElsewhere(int keyCode, int modifiers) {
        if (this.view == View.RECORDINGS && (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN) && !this.shown.isEmpty()) {
            // The arrows move the choice along the list
            int at = -1;
            for (int i = 0; i < this.shown.size(); i++) {
                if (this.chosen.contains(this.shown.get(i).file)) {
                    at = i;
                    if (keyCode == GLFW.GLFW_KEY_UP) {
                        break;
                    }
                }
            }
            int next = Math.max(0, Math.min(this.shown.size() - 1, at + (keyCode == GLFW.GLFW_KEY_DOWN ? 1 : -1)));
            this.chosen.clear();
            this.chosen.add(this.shown.get(next).file);
            this.anchor = this.shown.get(next).file;
            for (Gadget row : this.rows.rows()) {
                if (row instanceof Row recording && recording.entry.file.equals(this.anchor)) {
                    this.rows.reveal(row);
                }
            }
            return true;
        }
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && this.chosenEntries().size() == 1) {
            this.watchChosen();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_DELETE && !this.chosenEntries().isEmpty()) {
            this.deleteChosen();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_A && Screen.hasControlDown() && this.view == View.RECORDINGS) {
            this.shown.forEach(entry -> this.chosen.add(entry.file));
            return true;
        }
        return false;
    }

    @Override
    void typedElsewhere(char character, int modifiers) {
        // Typing anywhere starts a search
        if (this.view == View.RECORDINGS && !Character.isISOControl(character) && !Screen.hasControlDown()) {
            this.searchBox.focus();
            this.searchBox.type(this.search + character);
        }
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
        this.searched(words);
    }

    public void setSort(String order) {
        this.sort = LibraryQuery.Sort.valueOf(order.toUpperCase(Locale.ROOT));
        this.rebuildRows();
    }

    public void setFolder(String relative) {
        this.folder = relative;
        this.rebuildRows();
    }

    /** The names of the recordings on show, in order */
    public List<String> namesShown() {
        List<String> names = new ArrayList<>();
        this.shown.forEach(entry -> names.add(nameOf(entry)));
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
        this.chosen.clear();
        for (String name : names) {
            for (LibraryIndex.Entry entry : this.shown) {
                if (nameOf(entry).equals(name)) {
                    this.chosen.add(entry.file);
                }
            }
        }
    }

    public int chosenCount() {
        return this.chosenEntries().size();
    }

    /** Presses a button of the foot by the last part of its key (watch, delete, combine, restore, empty_bin and so on); returns whether it could be used */
    public boolean press(String key) {
        Gadget.Push button = switch (key) {
            case "watch" -> this.watch;
            case "edit" -> this.edit;
            case "star" -> this.star;
            case "move" -> this.moveTo;
            case "combine" -> this.combine;
            case "trim" -> this.trim;
            case "delete" -> this.delete;
            case "restore" -> this.restore;
            case "empty_bin" -> this.emptyBin;
            case "measure" -> this.measure;
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
        for (Bin.Item item : this.binItems) {
            if (item.name.equals(name)) {
                this.chosenBin = item.id;
                return true;
            }
        }
        return false;
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
            if (row instanceof Fact fact) {
                lines.add(fact.label.getString() + ": " + fact.value);
            }
        }
        return lines;
    }

    /** Whether the details of the one recording chosen have been read from its file, mods and spans included */
    public boolean detailsRead() {
        List<LibraryIndex.Entry> entries = this.chosenEntries();
        return entries.size() == 1 && entries.get(0).file.equals(this.detailsFor) && this.detailsManifest != null;
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

    // ------------------------------------------------------------------ The rows and facts

    /** One recording in the list: its name and star, and under it when it was made, how long it is, where, and how big */
    private final class Row extends Gadget {

        private final LibraryIndex.Entry entry;
        private final String name;
        private final String details;
        private long clickedAt = 0;
        private boolean cut = false;

        Row(LibraryIndex.Entry entry) {
            this.entry = entry;
            this.name = nameOf(entry);
            String date = LibraryBits.date(entry.created > 0 ? entry.created : entry.modified);
            String size = StorageReport.bytes(entry.size);
            String length = Controls.length(entry.totalTicks);
            String where = LibraryQuery.ALL.equals(RecordingsScreen.this.folder) || !RecordingsScreen.this.search.isBlank() ? LibraryQuery.folderOf(entry) : "";
            String world = entry.world == null || entry.world.isBlank() ? "" : entry.world;
            String line = world.isEmpty() ? t("row", date, length, size).getString() : t("row_world", date, length, world, size).getString();
            this.details = where.isEmpty() ? line : line + "  ·  " + where;
        }

        @Override
        int heightFor(Font font, int forWidth) {
            return ROW_HEIGHT;
        }

        @Override
        void draw(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
            boolean picked = RecordingsScreen.this.chosen.contains(this.entry.file);
            boolean hovered = this.over(mouseX, mouseY);
            boolean starred = RecordingsScreen.this.starredIds.contains(this.entry.id);
            if (picked) {
                graphics.fill(this.x, this.y, this.x + this.width, this.y + this.height, Controls.HIGHLIGHT);
                graphics.fill(this.x, this.y, this.x + 2, this.y + this.height, Controls.BRASS_BRIGHT);
            } else if (hovered) {
                graphics.fill(this.x, this.y, this.x + this.width, this.y + this.height, 0x28D4B25A);
            }
            LibraryBits.reel(graphics, this.x + 14, this.y + this.height / 2, picked ? Controls.BRASS_BRIGHT : Controls.BRASS,
                picked || hovered ? Controls.PARCHMENT : Controls.PARCHMENT_DIM);
            int textLeft = this.x + 28;
            int starRoom = starred ? 16 : 0;
            int room = Math.max(10, this.width - 34 - starRoom);
            this.cut = font.width(this.name) > room || font.width(this.details) > room;
            Kit.text(graphics, font, Kit.clip(font, this.name, room), textLeft, this.y + 4, Controls.PARCHMENT);
            Kit.text(graphics, font, Kit.clip(font, this.details, room), textLeft, this.y + 15, Controls.PARCHMENT_DIM);
            if (starred) {
                LibraryBits.star(graphics, this.x + this.width - 10, this.y + 8, Controls.BRASS_BRIGHT);
            }
            if (!picked) {
                graphics.fill(this.x + 2, this.y + this.height, this.x + this.width - 2, this.y + this.height + 1, 0x30D4B25A);
            }
        }

        @Override
        @Nullable Component tipAt(double mouseX, double mouseY) {
            if (!this.over(mouseX, mouseY)) {
                return null;
            }
            Component words = t("row.tip");
            return this.cut ? Component.literal(this.name + "\n" + this.details + "\n").append(words) : words;
        }

        @Override
        @Nullable Gadget press(double mouseX, double mouseY, int button) {
            if (button != 0 || !this.over(mouseX, mouseY)) {
                return null;
            }
            long now = Util.getMillis();
            boolean twice = now - this.clickedAt < 300 && !Screen.hasControlDown() && !Screen.hasShiftDown();
            RecordingsScreen.this.choose(this.entry);
            if (twice) {
                RecordingsScreen.this.chosen.clear();
                RecordingsScreen.this.chosen.add(this.entry.file);
                RecordingsScreen.this.watchChosen();
            }
            this.clickedAt = now;
            return this;
        }
    }

    /** A folder inside the one on show, or the way up out of it. A double click goes in */
    private final class FolderRow extends Gadget {

        private final String folder;
        private final boolean up;
        private long clickedAt = 0;

        FolderRow(String folder, boolean up) {
            this.folder = folder;
            this.up = up;
        }

        @Override
        int heightFor(Font font, int forWidth) {
            return 18;
        }

        private int count() {
            int count = 0;
            for (LibraryIndex.Entry entry : RecordingsScreen.this.all) {
                String in = LibraryQuery.folderOf(entry);
                if (in.equals(this.folder) || in.startsWith(this.folder + "/")) {
                    count++;
                }
            }
            return count;
        }

        @Override
        void draw(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
            boolean hovered = this.over(mouseX, mouseY);
            if (hovered) {
                graphics.fill(this.x, this.y, this.x + this.width, this.y + this.height, 0x28D4B25A);
            }
            if (this.up) {
                Kit.icon(graphics, Kit.Icon.UP, this.x + 14, this.y + this.height / 2, hovered ? Controls.PARCHMENT : Controls.BRASS);
            } else {
                LibraryBits.folder(graphics, this.x + 14, this.y + this.height / 2, hovered ? Controls.BRASS_BRIGHT : Controls.BRASS);
            }
            String words = this.up ? t("folder.up", folderName(this.folder)).getString() : this.folder.substring(this.folder.lastIndexOf('/') + 1);
            String count = this.up ? "" : t("folder.holds", this.count()).getString();
            int countWidth = font.width(count);
            Kit.text(graphics, font, Kit.clip(font, words, this.width - 40 - countWidth), this.x + 28, this.y + 5, this.up ? Controls.PARCHMENT_DIM : Controls.PARCHMENT);
            if (!count.isEmpty()) {
                Kit.text(graphics, font, count, this.x + this.width - 6 - countWidth, this.y + 5, Controls.PARCHMENT_FAINT);
            }
            graphics.fill(this.x + 2, this.y + this.height, this.x + this.width - 2, this.y + this.height + 1, 0x20D4B25A);
        }

        @Override
        @Nullable Component tipAt(double mouseX, double mouseY) {
            return this.over(mouseX, mouseY) ? t(this.up ? "folder.up.tip" : "folder.row.tip") : null;
        }

        @Override
        @Nullable Gadget press(double mouseX, double mouseY, int button) {
            if (button != 0 || !this.over(mouseX, mouseY)) {
                return null;
            }
            long now = Util.getMillis();
            if (now - this.clickedAt < 300 || this.up) {
                RecordingsScreen.this.folder = this.folder;
                RecordingsScreen.this.chosen.clear();
                RecordingsScreen.this.rows.scrollToTop();
                RecordingsScreen.this.rebuildRows();
            }
            this.clickedAt = now;
            return this;
        }
    }

    /** One thing in the bin: what kind, its name, when it was deleted and how long it has left */
    private final class BinRow extends Gadget {

        private final Bin.Item item;

        BinRow(Bin.Item item) {
            this.item = item;
        }

        @Override
        int heightFor(Font font, int forWidth) {
            return ROW_HEIGHT;
        }

        @Override
        void draw(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
            boolean picked = this.item.id.equals(RecordingsScreen.this.chosenBin);
            boolean hovered = this.over(mouseX, mouseY);
            if (picked) {
                graphics.fill(this.x, this.y, this.x + this.width, this.y + this.height, Controls.HIGHLIGHT);
                graphics.fill(this.x, this.y, this.x + 2, this.y + this.height, Controls.BRASS_BRIGHT);
            } else if (hovered) {
                graphics.fill(this.x, this.y, this.x + this.width, this.y + this.height, 0x28D4B25A);
            }
            int colour = picked || hovered ? Controls.PARCHMENT : Controls.PARCHMENT_DIM;
            int middle = this.y + this.height / 2;
            switch (this.item.kind()) {
                case RECORDING -> LibraryBits.reel(graphics, this.x + 14, middle, Controls.BRASS, colour);
                case SCENE -> LibraryBits.scene(graphics, this.x + 14, middle, colour);
                case SESSION -> LibraryBits.voice(graphics, this.x + 14, middle, colour);
            }
            int left = Retention.daysLeft(this.item, Library.binDays(), System.currentTimeMillis());
            String details = t("bin.row", t("bin.kind." + this.item.kind).getString(), LibraryBits.day(this.item.binned),
                t(left == 1 ? "bin.day_one" : "bin.days", left).getString(), StorageReport.bytes(this.item.size)).getString();
            int room = Math.max(10, this.width - 34);
            Kit.text(graphics, font, Kit.clip(font, this.item.name, room), this.x + 28, this.y + 4, Controls.PARCHMENT);
            Kit.text(graphics, font, Kit.clip(font, details, room), this.x + 28, this.y + 15, left <= 3 ? Controls.ALERT : Controls.PARCHMENT_DIM);
            if (!picked) {
                graphics.fill(this.x + 2, this.y + this.height, this.x + this.width - 2, this.y + this.height + 1, 0x30D4B25A);
            }
        }

        @Override
        @Nullable Component tipAt(double mouseX, double mouseY) {
            return this.over(mouseX, mouseY) ? t("bin.row.tip") : null;
        }

        @Override
        @Nullable Gadget press(double mouseX, double mouseY, int button) {
            if (button != 0 || !this.over(mouseX, mouseY)) {
                return null;
            }
            RecordingsScreen.this.chosenBin = this.item.id;
            return this;
        }
    }

    /** A name and its value on one line, the value cut short with its whole in the explanation when it does not fit */
    private static final class Fact extends Gadget {

        private final Component label;
        private final String value;
        private boolean cut = false;

        Fact(Component label, String value) {
            this.label = label;
            this.value = value == null ? "" : value;
        }

        @Override
        int heightFor(Font font, int forWidth) {
            return 12;
        }

        private int labelWidth() {
            return Math.max(48, Math.min(76, this.width * 34 / 100));
        }

        @Override
        void draw(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
            int labelWidth = this.labelWidth();
            Kit.text(graphics, font, Kit.clip(font, this.label.getString(), labelWidth - 4), this.x + 1, this.y + 2, Controls.PARCHMENT_DIM);
            int room = this.width - labelWidth - 2;
            this.cut = font.width(this.value) > room;
            Kit.text(graphics, font, Kit.clip(font, this.value, room), this.x + labelWidth, this.y + 2, Controls.PARCHMENT);
        }

        @Override
        @Nullable Component tipAt(double mouseX, double mouseY) {
            if (!this.over(mouseX, mouseY)) {
                return null;
            }
            return this.cut ? Component.empty().append(this.label).append(": ").append(this.value) : null;
        }
    }

    /** The preview picture of the recording chosen, or a reel where it has none */
    private static final class PictureBox extends Gadget {

        private final java.util.function.Supplier<LibraryBits.Picture> picture;

        PictureBox(java.util.function.Supplier<LibraryBits.Picture> picture) {
            this.picture = picture;
        }

        @Override
        int heightFor(Font font, int forWidth) {
            return Math.min(120, forWidth * 9 / 16);
        }

        @Override
        void draw(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
            LibraryBits.Picture now = this.picture.get();
            if (now != null) {
                now.draw(graphics, this.x, this.y, this.width, this.height);
            } else {
                LibraryBits.noPicture(graphics, font, this.x, this.y, this.width, this.height, t("details.no_preview"));
            }
        }

        @Override
        @Nullable Component tipAt(double mouseX, double mouseY) {
            return this.over(mouseX, mouseY) ? t("details.preview.tip") : null;
        }
    }

    /** A kind of storage with a bar for its share of the largest, and how much it takes */
    private static final class Usage extends Gadget {

        private final Component label;
        private final long bytes;
        private final long most;

        Usage(Component label, long bytes, long most, Component tip) {
            this.label = label;
            this.bytes = bytes;
            this.most = most;
            this.tip = tip;
        }

        @Override
        void draw(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
            int labelWidth = Math.min(110, this.width * 30 / 100);
            int numberWidth = 64;
            Kit.text(graphics, font, Kit.clip(font, this.label.getString(), labelWidth - 4), this.x + 1, this.y + 3, Controls.PARCHMENT);
            LibraryBits.bar(graphics, font, this.x + labelWidth, this.y + 2, this.width - labelWidth - numberWidth - 6, this.height - 4,
                this.bytes / (double) this.most, Controls.FACE_LIT, null);
            String words = StorageReport.bytes(this.bytes);
            Kit.text(graphics, font, words, this.x + this.width - font.width(words) - 2, this.y + 3, Controls.PARCHMENT_DIM);
        }
    }

    /** One world's share of the storage, in columns; null for the row of column names */
    private static final class WorldRow extends Gadget {

        private final StorageReport.@Nullable World world;

        WorldRow(StorageReport.@Nullable World world) {
            this.world = world;
        }

        @Override
        int heightFor(Font font, int forWidth) {
            return 12;
        }

        @Override
        void draw(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
            int column = Math.max(48, Math.min(70, this.width / 6));
            int nameWidth = this.width - column * 4;
            int colour = this.world == null ? Controls.BRASS_BRIGHT : Controls.PARCHMENT;
            String name = this.world == null ? t("storage.world").getString() : this.world.name.isEmpty() ? t("storage.world_other").getString() : this.world.name;
            Kit.text(graphics, font, Kit.clip(font, name, nameWidth - 6), this.x + 1, this.y + 2, colour);
            String[] cells = this.world == null
                ? new String[]{t("storage.column.recordings").getString(), t("storage.column.audio").getString(), t("storage.column.exports").getString(), t("storage.column.bin").getString()}
                : new String[]{StorageReport.bytes(this.world.recordings), StorageReport.bytes(this.world.audio), StorageReport.bytes(this.world.exports), StorageReport.bytes(this.world.bin)};
            for (int i = 0; i < cells.length; i++) {
                String cell = Kit.clip(font, cells[i], column - 4);
                int right = this.x + nameWidth + column * (i + 1) - 2;
                Kit.text(graphics, font, cell, right - font.width(cell), this.y + 2, this.world == null ? Controls.BRASS_BRIGHT : Controls.PARCHMENT_DIM);
            }
            if (this.world == null) {
                Kit.rule(graphics, this.x, this.y + this.height - 1, this.x + this.width);
            }
        }

        @Override
        @Nullable Component tipAt(double mouseX, double mouseY) {
            if (!this.over(mouseX, mouseY)) {
                return null;
            }
            if (this.world == null) {
                return t("storage.columns.tip");
            }
            return t("storage.world.tip", this.world.name.isEmpty() ? t("storage.world_other").getString() : this.world.name, this.world.recordingCount, StorageReport.bytes(this.world.total()));
        }
    }

}
