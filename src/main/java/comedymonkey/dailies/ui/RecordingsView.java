package comedymonkey.dailies.ui;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.file.RecordingFile;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.library.Library;
import comedymonkey.dailies.library.LibraryIndex;
import comedymonkey.dailies.library.LibraryQuery;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The library's view of the recordings (specification REC-5 and REC-8): searched by name, world, player or date, in
 * the order chosen, folder by folder or all at once, with a pane of details for the one chosen. Several can be chosen
 * with Ctrl and Shift, to combine, move or delete together.
 */
final class RecordingsView extends LibraryView {

    // What is shown of the library
    String folder = LibraryQuery.ALL;
    String search = "";
    LibraryQuery.Sort sort = LibraryQuery.Sort.NEWEST;
    private boolean starredOnly = false;
    List<LibraryIndex.Entry> shown = List.of();
    /** The recordings chosen, by file relative to the recordings folder, in the order they were chosen */
    final RevisedSet<String> chosen = new RevisedSet<>();
    /** The entries chosen, as last worked out, and what from: the buttons ask for them many times a frame */
    private List<LibraryIndex.Entry> chosenFound = List.of();
    private long chosenFoundAt = -1;
    private List<LibraryIndex.Entry> chosenFoundIn = List.of();
    private @Nullable String anchor = null;

    // The details of the one recording chosen
    @Nullable String detailsFor = null;
    @Nullable RecordingManifest detailsManifest = null;
    private LibraryBits.@Nullable Picture detailsPicture = null;
    private int detailsLook = 0;

    private Gadget.Dropdown<String> folderPick;
    Entry searchBox;
    private Gadget.Dropdown<LibraryQuery.Sort> sortPick;
    private Gadget.Check starredCheck;

    Gadget.Push watch;
    Gadget.Push edit;
    Gadget.Push star;
    Gadget.Push moveTo;
    Gadget.Push combine;
    Gadget.Push trim;
    Gadget.Push delete;
    Gadget.Push folders;
    Gadget.Push share;
    Gadget.Push importing;

    RecordingsView(RecordingsScreen screen) {
        super(screen);
    }

    // ------------------------------------------------------------------ Building

    @Override
    void build() {
        this.folderPick = new Gadget.Dropdown<>(this::folderChoices, RecordingsView::folderName, () -> this.folder, chosenFolder -> {
            this.folder = chosenFolder;
            this.screen.rebuildRows();
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
            this.screen.rebuildRows();
        });
        this.sortPick.tip(t("sort.tip"));
        this.starredCheck = new Gadget.Check(t("starred_only"), () -> this.starredOnly, on -> {
            this.starredOnly = on;
            this.screen.rebuildRows();
        });
        this.starredCheck.tip(t("starred_only.tip"));

        this.watch = push("watch", this::watchChosen, () -> this.chosenEntries().size() == 1);
        this.edit = push("edit", this::editChosen, () -> this.chosenEntries().size() == 1);
        this.star = new Gadget.Push(() -> t(this.allStarred() ? "unstar" : "star"), this::starChosen);
        this.star.when(() -> !this.chosenEntries().isEmpty());
        this.star.liveTip(() -> t(this.allStarred() ? "unstar.tip" : "star.tip"));
        this.moveTo = push("move", this::openMoveMenu, () -> !this.chosenEntries().isEmpty());
        this.combine = push("combine", this::combineChosen, () -> this.chosenEntries().size() >= 2);
        this.trim = push("trim", this::trimChosen, () -> this.chosenEntries().size() == 1);
        this.delete = push("delete", this::deleteChosen, () -> !this.chosenEntries().isEmpty());
        this.folders = push("folders", this::openFolderMenu, () -> true);
        this.share = push("share", this::shareChosen, () -> this.chosenEntries().size() == 1);
        this.importing = push("import", this::importShared, () -> true);
    }

    void searched(String typed) {
        if (!typed.equals(this.search)) {
            this.search = typed;
            this.screen.rebuildRows();
        }
    }

    private List<String> folderChoices() {
        List<String> choices = new ArrayList<>();
        choices.add(LibraryQuery.ALL);
        choices.add(LibraryQuery.TOP);
        choices.addAll(this.screen.folderList);
        if (!choices.contains(this.folder)) {
            choices.add(this.folder);
        }
        return choices;
    }

    static Component folderName(String folder) {
        if (LibraryQuery.ALL.equals(folder)) {
            return t("folder.all");
        }
        if (LibraryQuery.TOP.equals(folder)) {
            return t("folder.top");
        }
        return Component.literal(folder.replace("/", " / "));
    }

    /** The library was read again: a folder on show that is not there any more gives way to them all */
    void libraryRead() {
        if (!LibraryQuery.ALL.equals(this.folder) && !LibraryQuery.TOP.equals(this.folder) && !this.screen.folderList.contains(this.folder)) {
            this.folder = LibraryQuery.ALL;
        }
    }

    // ------------------------------------------------------------------ Setting out

    @Override
    @Nullable Component count() {
        List<LibraryIndex.Entry> all = this.screen.all;
        if (all.isEmpty()) {
            return null;
        }
        Component total = Component.translatable(all.size() == 1 ? "dailies.ui.list.count_one" : "dailies.ui.list.count", all.size());
        return this.shown.size() == all.size() ? total : t("count_shown", total, this.shown.size());
    }

    @Override
    Gadget[] bar() {
        return new Gadget[]{this.folderPick, this.searchBox, this.sortPick, this.starredCheck};
    }

    @Override
    void placeBar(Font font, int x, int y) {
        int inner = this.screen.right - this.screen.left - 16;
        int checkWidth = Math.min(96, this.starredCheck.wantedWidth(font) + 4);
        int sortWidth = 118;
        int folderWidth = Math.min(150, Math.max(90, inner / 6));
        RecordingsScreen.put(font, this.folderPick, x, y, folderWidth);
        x += folderWidth + Kit.GAP * 3;
        int checkLeft = this.screen.right - 8 - checkWidth;
        int sortLeft = checkLeft - Kit.GAP * 3 - sortWidth;
        RecordingsScreen.put(font, this.searchBox, x, y, sortLeft - Kit.GAP * 3 - x);
        RecordingsScreen.put(font, this.sortPick, sortLeft, y, sortWidth);
        RecordingsScreen.put(font, this.starredCheck, checkLeft, y, checkWidth);
    }

    @Override
    Gadget[] footLeft() {
        return new Gadget[]{this.watch, this.edit, this.star, this.moveTo, this.combine, this.trim, this.delete, this.share};
    }

    @Override
    Gadget[] footRight() {
        return new Gadget[]{this.importing, this.folders, this.screen.openFolder, this.screen.done};
    }

    @Override
    boolean empty() {
        return this.screen.rows.isEmpty();
    }

    @Override
    Path folder() {
        if (!LibraryQuery.ALL.equals(this.folder) && !LibraryQuery.TOP.equals(this.folder)) {
            return Recorder.folder().resolve(this.folder);
        }
        return Recorder.folder();
    }

    // ------------------------------------------------------------------ The rows

    @Override
    void fillRows(Form rows) {
        List<LibraryIndex.Entry> all = this.screen.all;
        this.shown = LibraryQuery.apply(all, this.folder, this.search, this.sort, this.screen.starredIds, this.starredOnly, ZoneId.systemDefault());
        if (this.search.isBlank() && !LibraryQuery.ALL.equals(this.folder)) {
            if (!LibraryQuery.TOP.equals(this.folder)) {
                int slash = this.folder.lastIndexOf('/');
                rows.add(new FolderRow(slash < 0 ? LibraryQuery.TOP : this.folder.substring(0, slash), true, 0));
            }
            List<String> children = LibraryQuery.childrenOf(this.screen.folderList, this.folder);
            if (!children.isEmpty()) {
                List<String> holding = new ArrayList<>(all.size());
                all.forEach(entry -> holding.add(LibraryQuery.folderOf(entry)));
                Map<String, Integer> counts = FolderCounts.of(holding);
                for (String child : children) {
                    rows.add(new FolderRow(child, false, counts.getOrDefault(child, 0)));
                }
            }
        }
        Set<String> still = new java.util.HashSet<>();
        for (LibraryIndex.Entry entry : this.shown) {
            rows.add(new Row(entry));
            still.add(entry.file);
        }
        this.chosen.retainAll(still);
    }

    /** The recordings chosen that are on show, in the order of the list. Worked out again only when either has changed */
    List<LibraryIndex.Entry> chosenEntries() {
        if (!this.showing() || this.chosen.isEmpty()) {
            return List.of();
        }
        if (this.chosenFoundAt != this.chosen.revision() || this.chosenFoundIn != this.shown) {
            List<LibraryIndex.Entry> list = new ArrayList<>();
            for (LibraryIndex.Entry entry : this.shown) {
                if (this.chosen.contains(entry.file)) {
                    list.add(entry);
                }
            }
            this.chosenFound = List.copyOf(list);
            this.chosenFoundAt = this.chosen.revision();
            this.chosenFoundIn = this.shown;
        }
        return this.chosenFound;
    }

    private boolean allStarred() {
        List<LibraryIndex.Entry> entries = this.chosenEntries();
        return !entries.isEmpty() && entries.stream().allMatch(entry -> this.screen.starredIds.contains(entry.id));
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

    @Override
    String sideShape() {
        List<LibraryIndex.Entry> entries = this.chosenEntries();
        if (entries.size() == 1) {
            LibraryIndex.Entry entry = entries.get(0);
            this.loadDetails(entry);
            return "one:" + entry.file + ":" + (this.detailsManifest != null && entry.file.equals(this.detailsFor)) + ":" + this.screen.starredIds.contains(entry.id);
        }
        return "many:" + entries.size() + ":" + this.screen.all.size();
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

    /** The screen was taken away: the picture is let go of, and one still being read is not wanted when it comes */
    void closed() {
        // A preview still being read is not wanted now: its picture would be made after this and never let go of
        this.detailsLook++;
        if (this.detailsPicture != null) {
            this.detailsPicture.close();
            this.detailsPicture = null;
        }
        // Read again when the screen is back, so the picture is made afresh
        this.detailsFor = null;
    }

    @Override
    void fillSide(Form side) {
        List<LibraryIndex.Entry> entries = this.chosenEntries();
        if (entries.isEmpty()) {
            side.add(new Gadget.Note(t("details.none")));
            return;
        }
        if (entries.size() > 1) {
            long size = 0;
            int ticks = 0;
            for (LibraryIndex.Entry entry : entries) {
                size += entry.size;
                ticks += entry.totalTicks;
            }
            side.add(new Gadget.Heading(t("details.many", entries.size())));
            side.add(new LibraryBits.Fact(t("details.length"), Controls.length(ticks)));
            side.add(new LibraryBits.Fact(t("details.size"), StorageReport.bytes(size)));
            side.add(new Gadget.Note(t("details.many_hint")));
            return;
        }
        LibraryIndex.Entry entry = entries.get(0);
        side.add(new PictureBox(() -> entry.file.equals(this.detailsFor) ? this.detailsPicture : null));
        side.add(new Gadget.Heading(Component.literal((this.screen.starredIds.contains(entry.id) ? "★ " : "") + RecordingsScreen.nameOf(entry))));
        side.add(new LibraryBits.Fact(t("details.world"), entry.world.isBlank() ? t("details.unknown").getString() : entry.world));
        side.add(new LibraryBits.Fact(t("details.players"), entry.players.isEmpty() ? t("details.unknown").getString() : String.join(", ", entry.players)));
        side.add(new LibraryBits.Fact(t("details.recorded"), LibraryBits.date(entry.created > 0 ? entry.created : entry.modified)));
        if (entry.isEdited()) {
            side.add(new LibraryBits.Fact(t("details.edited"), LibraryBits.date(entry.edited)));
        }
        side.add(new LibraryBits.Fact(t("details.length"), Controls.length(entry.totalTicks)));
        side.add(new LibraryBits.Fact(t("details.size"), StorageReport.bytes(entry.size)));
        String where = LibraryQuery.folderOf(entry);
        side.add(new LibraryBits.Fact(t("details.folder"), where.isEmpty() ? t("folder.top").getString() : where));
        side.add(new LibraryBits.Fact(t("details.file"), entry.file.substring(entry.file.lastIndexOf('/') + 1)));
        RecordingManifest manifest = entry.file.equals(this.detailsFor) ? this.detailsManifest : null;
        if (manifest == null) {
            side.add(new Gadget.Note(() -> t("looking"), Controls.PARCHMENT_FAINT));
            return;
        }
        if (manifest.isEdited()) {
            side.add(new Gadget.Heading(t("details.made_from")));
            Map<String, String> names = new HashMap<>();
            for (RecordingManifest.SourceEntry source : manifest.sources) {
                names.put(source.id, source.name.isBlank() ? source.id.substring(0, Math.min(8, source.id.length())) : source.name);
            }
            for (RecordingManifest.SpanEntry span : manifest.spans) {
                comedymonkey.dailies.file.Spans.Span piece = span.span();
                String name = names.getOrDefault(piece.source(), piece.source().substring(0, Math.min(8, piece.source().length())));
                side.add(new LibraryBits.Fact(Component.literal(Controls.time(piece.here())), t("details.span", name, Controls.time(piece.sourceStart()), Controls.time(piece.sourceEnd())).getString()));
            }
        }
        side.add(new Gadget.Heading(t("details.mods", manifest.mods.size())));
        StringBuilder mods = new StringBuilder();
        for (RecordingManifest.ModEntry mod : manifest.mods) {
            if (!mod.id.equals("minecraft") && !mod.id.equals("neoforge")) {
                mods.append(mods.isEmpty() ? "" : ", ").append(mod.id).append(' ').append(mod.version);
            }
        }
        side.add(new Gadget.Note(Component.literal(mods.isEmpty() ? t("details.no_mods").getString() : mods.toString())));
        side.add(new LibraryBits.Fact(t("details.game"), manifest.gameVersion));
    }

    // ------------------------------------------------------------------ What the buttons do

    /** Whether this is the recording being watched right now, which must not be changed underneath playback */
    private static boolean beingWatched(Path file) {
        RecordingFile open = Playback.active() ? Playback.file() : null;
        return open != null && open.file().toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize());
    }

    void watchChosen() {
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
            Minecraft.getInstance().setScreen(null);
            Playback.open(file);
        });
    }

    private void editChosen() {
        List<LibraryIndex.Entry> entries = this.chosenEntries();
        if (entries.size() == 1) {
            Minecraft.getInstance().setScreen(new RecordingEditScreen(this.screen, entries.get(0), () -> {
                this.detailsFor = null;
                this.screen.lookAgain();
            }));
        }
    }

    private void starChosen() {
        List<LibraryIndex.Entry> entries = this.chosenEntries();
        boolean on = !this.allStarred();
        Set<String> now = new java.util.HashSet<>(this.screen.starredIds);
        for (LibraryIndex.Entry entry : entries) {
            if (on) {
                now.add(entry.id);
            } else {
                now.remove(entry.id);
            }
        }
        this.screen.starredIds = Set.copyOf(now);
        this.screen.detailsBuiltFor = "";
        this.screen.work(() -> {
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
        targets.addAll(this.screen.folderList);
        for (String target : targets) {
            boolean already = entries.stream().allMatch(entry -> LibraryQuery.folderOf(entry).equals(target));
            items.add(new Menu.Item(folderName(target), () -> this.moveChosen(entries, target), !already, already));
        }
        items.add(new Menu.Item(t("move.new_folder"), () -> this.askFolderName(t("folder.new.title"), "", name -> {
            String parent = LibraryQuery.ALL.equals(this.folder) ? LibraryQuery.TOP : this.folder;
            this.screen.work(() -> {
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
        this.screen.work(() -> Library.moveInto(files(entries), target), t("moved", entries.size(), folderName(target)));
    }

    private void combineChosen() {
        List<LibraryIndex.Entry> entries = this.chosenEntries();
        if (entries.size() >= 2) {
            List<LibraryIndex.Entry> ordered = new ArrayList<>(entries);
            // Oldest footage first: what a person combining a week of sessions nearly always wants
            ordered.sort(java.util.Comparator.comparingLong(LibraryIndex.Entry::newest));
            Minecraft.getInstance().setScreen(new CombineScreen(this.screen, ordered, this.screen.all));
        }
    }

    private void trimChosen() {
        List<LibraryIndex.Entry> entries = this.chosenEntries();
        if (entries.size() == 1) {
            LibraryIndex.Entry entry = entries.get(0);
            Minecraft.getInstance().setScreen(new TrimScreen(this.screen, Library.fileOf(entry), entry.id, RecordingsScreen.nameOf(entry), entry.totalTicks, -1, -1));
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
        Component told = entries.size() == 1 ? t("binned_one", RecordingsScreen.nameOf(entries.get(0)), Library.binDays()) : t("binned", entries.size(), Library.binDays());
        this.screen.work(() -> {
            for (Path file : files(entries)) {
                Library.delete(file);
            }
        }, told);
    }

    private void openFolderMenu() {
        boolean real = !LibraryQuery.ALL.equals(this.folder) && !LibraryQuery.TOP.equals(this.folder);
        List<Menu.Item> items = new ArrayList<>();
        items.add(new Menu.Item(t("folder.new"), () -> this.askFolderName(t("folder.new.title"), "", name -> {
            String parent = real ? this.folder : LibraryQuery.TOP;
            String made = (parent.isEmpty() ? "" : parent + "/") + name.trim();
            this.folder = made;
            this.screen.work(() -> Library.createFolder(parent, name), t("folder.made", name));
        })));
        items.add(new Menu.Item(t("folder.rename"), () -> this.askFolderName(t("folder.rename.title"), this.folder.substring(this.folder.lastIndexOf('/') + 1), name -> {
            String old = this.folder;
            int slash = old.lastIndexOf('/');
            this.folder = (slash < 0 ? "" : old.substring(0, slash + 1)) + name.trim();
            this.screen.work(() -> Library.renameFolder(old, name), null);
        }), real, false));
        items.add(new Menu.Item(t("folder.delete"), () -> {
            String old = this.folder;
            int slash = old.lastIndexOf('/');
            this.folder = slash < 0 ? LibraryQuery.TOP : old.substring(0, slash);
            this.screen.work(() -> Library.deleteFolder(old), t("folder.deleted", old));
        }, real, false));
        Menu.drop(items, this.folders.x, this.folders.y, Math.max(150, this.folders.width), this.folders.height, this.folders);
    }

    private void askFolderName(Component title, String suggestion, java.util.function.Consumer<String> then) {
        Minecraft game = Minecraft.getInstance();
        game.setScreen(new NameScreen(title, t("folder.explanation"), suggestion, t("folder.save"), t("folder.cancel"), name -> {
            game.setScreen(this.screen);
            String bad = LibraryQuery.badFolderName(name);
            if (bad != null) {
                Notices.show(Component.translatable("dailies.ui.notice.refused", bad));
                return;
            }
            then.accept(name.trim());
        }, () -> game.setScreen(this.screen)));
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
        Notices.show(Component.translatable("dailies.ui.share.working", RecordingsScreen.nameOf(entry)));
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
            this.screen.lookAgain();
        });
    }

    // ------------------------------------------------------------------ The keys

    @Override
    boolean key(int keyCode, int modifiers) {
        if ((keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN) && !this.shown.isEmpty()) {
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
            for (Gadget row : this.screen.rows.rows()) {
                if (row instanceof Row recording && recording.entry.file.equals(this.anchor)) {
                    this.screen.rows.reveal(row);
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
        if (keyCode == GLFW.GLFW_KEY_A && Screen.hasControlDown()) {
            this.shown.forEach(entry -> this.chosen.add(entry.file));
            return true;
        }
        return false;
    }

    @Override
    void typed(char character, int modifiers) {
        // Typing anywhere starts a search
        if (!Character.isISOControl(character) && !Screen.hasControlDown()) {
            this.searchBox.focus();
            this.searchBox.type(this.search + character);
        }
    }

    // ------------------------------------------------------------------ The rows

    /** One recording in the list: its name and star, and under it when it was made, how long it is, where, and how big */
    private final class Row extends Gadget {

        private final LibraryIndex.Entry entry;
        private final String name;
        private final String details;
        private long clickedAt = 0;
        private boolean cut = false;

        Row(LibraryIndex.Entry entry) {
            this.entry = entry;
            this.name = RecordingsScreen.nameOf(entry);
            String date = LibraryBits.date(entry.created > 0 ? entry.created : entry.modified);
            String size = StorageReport.bytes(entry.size);
            String length = Controls.length(entry.totalTicks);
            String where = LibraryQuery.ALL.equals(RecordingsView.this.folder) || !RecordingsView.this.search.isBlank() ? LibraryQuery.folderOf(entry) : "";
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
            boolean picked = RecordingsView.this.chosen.contains(this.entry.file);
            boolean hovered = this.over(mouseX, mouseY);
            boolean starred = RecordingsView.this.screen.starredIds.contains(this.entry.id);
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
            RecordingsView.this.choose(this.entry);
            if (twice) {
                RecordingsView.this.chosen.clear();
                RecordingsView.this.chosen.add(this.entry.file);
                RecordingsView.this.watchChosen();
            }
            this.clickedAt = now;
            return this;
        }
    }

    /** A folder inside the one on show, or the way up out of it. A double click goes in */
    private final class FolderRow extends Gadget {

        private final String folder;
        private final boolean up;
        /** How many recordings it holds, those in folders inside it included */
        private final int count;
        private long clickedAt = 0;

        FolderRow(String folder, boolean up, int count) {
            this.folder = folder;
            this.up = up;
            this.count = count;
        }

        @Override
        int heightFor(Font font, int forWidth) {
            return 18;
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
            String count = this.up ? "" : t("folder.holds", this.count).getString();
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
                RecordingsView.this.folder = this.folder;
                RecordingsView.this.chosen.clear();
                RecordingsView.this.screen.rows.scrollToTop();
                RecordingsView.this.screen.rebuildRows();
            }
            this.clickedAt = now;
            return this;
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

}
