package comedymonkey.dailies.ui;

import comedymonkey.dailies.file.RecordingFile;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.library.Library;
import comedymonkey.dailies.library.LibraryIndex;
import comedymonkey.dailies.library.LibraryJob;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Combining recordings into a new one (specification FILE-9, the screen of REC-5). The recordings are listed in the
 * order they will play, each with its preview, and dragged (or moved with the arrows) into another order. Between two
 * of them is the boundary, with the preview of the one that follows: there the world is rebuilt from that recording's
 * start, so recordings of different worlds join cleanly. Recordings of different game versions cannot be combined
 * and say so before anything starts; different mods are listed. The combine runs as a job that can be cancelled, and
 * the sources are never touched.
 */
public final class CombineScreen extends KitScreen {

    private static final String KEYS = "dailies.ui.combine.";
    private static final int SOURCE_HEIGHT = 34;
    private static final int BOUNDARY_HEIGHT = 24;

    private final List<LibraryIndex.Entry> order;
    private final List<LibraryIndex.Entry> everything;
    private final Map<String, byte[]> previews = new HashMap<>();
    private final Map<String, LibraryBits.Picture> textures = new HashMap<>();
    private final Map<String, RecordingManifest> manifests = new HashMap<>();
    private String name;
    private @Nullable LibraryJob job = null;
    private int dragging = -1;
    private int dropAt = -1;
    private String builtFor = "";

    private final Form list = new Form();
    private Gadget.Push add;
    private Gadget.Words total;
    private Gadget notes;
    private Entry nameBox;
    private LibraryBits.JobBar bar;
    private Gadget.Push start;
    private Gadget.Push close;

    public CombineScreen(@Nullable Screen parent, List<LibraryIndex.Entry> sources, List<LibraryIndex.Entry> everything) {
        super(Component.translatable(KEYS + "title"), parent);
        this.order = new ArrayList<>(sources);
        this.everything = List.copyOf(everything);
        String first = sources.isEmpty() ? "" : RecordingsScreen.nameOf(sources.get(0));
        this.name = Component.translatable(KEYS + "default_name", first, Math.max(0, sources.size() - 1)).getString();
    }

    private static Component t(String key, Object... arguments) {
        return Component.translatable(KEYS + key, arguments);
    }

    private boolean running() {
        return this.job != null && this.job.state() == LibraryJob.State.RUNNING;
    }

    @Override
    void build() {
        this.add = new Gadget.Push(t("add"), this::openAddMenu);
        this.add.when(() -> !this.running() && this.order.size() < this.everything.size()).tip(t("add.tip"));
        this.total = new Gadget.Words(this::totalWords, Controls.PARCHMENT_DIM);
        this.total.tip(t("total.tip"));
        this.notes = new Gadget() {
            @Override
            void draw(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
                boolean refused = CombineScreen.this.versionTrouble() != null;
                Kit.text(graphics, font, Kit.clip(font, CombineScreen.this.notesWords().getString(), this.width), this.x + 1, this.y + 3,
                    refused ? Controls.ALERT : Controls.PARCHMENT_DIM);
            }

            @Override
            @Nullable Component tipAt(double mouseX, double mouseY) {
                return this.over(mouseX, mouseY) ? CombineScreen.this.notesTip() : null;
            }
        };
        this.nameBox = new Entry(() -> this.name, typed -> {
            this.name = typed;
            return null;
        }).live(typed -> {
            this.name = typed;
            return null;
        }).max(80);
        this.nameBox.tip(t("name.tip"));
        this.bar = new LibraryBits.JobBar(() -> this.job);
        this.start = new Gadget.Push(t("start"), this::startCombine);
        this.start.when(() -> !this.running() && this.order.size() >= 2 && !this.name.isBlank() && this.versionTrouble() == null).tip(t("start.tip"));
        this.close = new Gadget.Push(() -> t(this.running() ? "cancel" : "close"), () -> {
            if (this.running()) {
                this.job.cancel();
            } else {
                this.onClose();
            }
        });
        this.close.liveTip(() -> t(this.running() ? "cancel.tip" : "close.tip"));
        for (Gadget gadget : List.of(this.add, this.total, this.notes, this.nameBox, this.bar, this.start, this.close)) {
            this.fixed.add(gadget);
        }
        this.forms.add(this.list);
        this.builtFor = "";
        this.load();
    }

    /** The preview and description of every recording that might be combined, off the client thread */
    private void load() {
        List<LibraryIndex.Entry> wanted = new ArrayList<>();
        for (LibraryIndex.Entry entry : this.order) {
            if (!this.manifests.containsKey(entry.file)) {
                wanted.add(entry);
            }
        }
        if (wanted.isEmpty()) {
            return;
        }
        Minecraft game = Minecraft.getInstance();
        CompletableFuture.supplyAsync(() -> {
            Map<String, RecordingManifest> found = new HashMap<>();
            Map<String, byte[]> pictures = new HashMap<>();
            for (LibraryIndex.Entry entry : wanted) {
                try (RecordingFile recording = RecordingFile.open(Library.fileOf(entry))) {
                    found.put(entry.file, recording.manifest());
                    byte[] png = recording.preview();
                    if (png != null) {
                        pictures.put(entry.file, png);
                    }
                } catch (Exception unreadable) {
                    // Combining it will say what is wrong
                }
            }
            return Map.entry(found, pictures);
        }, Util.ioPool()).whenComplete((found, error) -> game.execute(() -> {
            if (found != null) {
                this.manifests.putAll(found.getKey());
                this.previews.putAll(found.getValue());
            }
        }));
    }

    private @Nullable LibraryBits.Picture picture(LibraryIndex.Entry entry) {
        LibraryBits.Picture known = this.textures.get(entry.file);
        if (known == null && this.previews.containsKey(entry.file)) {
            known = LibraryBits.Picture.of(this.previews.get(entry.file));
            if (known != null) {
                this.textures.put(entry.file, known);
            } else {
                this.previews.remove(entry.file);
            }
        }
        return known;
    }

    private @Nullable String versionTrouble() {
        String first = null;
        for (LibraryIndex.Entry entry : this.order) {
            String version = entry.gameVersion;
            if (version == null || version.isBlank()) {
                continue;
            }
            if (first == null) {
                first = version;
            } else if (!first.equals(version)) {
                return t("versions", first, version).getString();
            }
        }
        return null;
    }

    private List<String> modDifferences() {
        List<RecordingManifest> known = new ArrayList<>();
        for (LibraryIndex.Entry entry : this.order) {
            RecordingManifest manifest = this.manifests.get(entry.file);
            if (manifest == null) {
                return List.of();
            }
            known.add(manifest);
        }
        return known.size() < 2 ? List.of() : Library.modDifferences(known);
    }

    private Component totalWords() {
        int ticks = 0;
        for (LibraryIndex.Entry entry : this.order) {
            ticks += entry.totalTicks;
        }
        return t("total", this.order.size(), Controls.length(ticks));
    }

    private Component notesWords() {
        String versions = this.versionTrouble();
        if (versions != null) {
            return Component.literal(versions);
        }
        int differences = this.modDifferences().size();
        return differences == 0 ? t("same_mods") : t(differences == 1 ? "mods_differ_one" : "mods_differ", differences);
    }

    private Component notesTip() {
        List<String> differences = this.modDifferences();
        if (this.versionTrouble() != null || differences.isEmpty()) {
            return t("notes.tip");
        }
        return Component.literal(String.join("\n", differences.subList(0, Math.min(12, differences.size())))
            + (differences.size() > 12 ? "\n..." : "") + "\n").append(t("notes.mods_tip"));
    }

    @Override
    void arrange(Font font) {
        this.centre(640, this.height - 24);
        Component under = t("explained");
        int head = this.headingHeight(font, under);
        int inner = this.right - this.left - 16;
        int x = this.left + 8;
        int footTop = this.bottom - 8 - Kit.ROW - 2;
        int barTop = footTop - 6 - (this.job == null ? 0 : Kit.ROW + 6);
        int nameTop = barTop - Kit.ROW - 6;
        int notesTop = nameTop - Kit.ROW - 6;
        int addTop = notesTop - Kit.ROW - 4;
        this.list.place(font, x - 3, this.top + head, inner + 6, Math.max(30, addTop - 6 - this.top - head));
        this.rebuild();
        int addWidth = Math.max(90, this.add.wantedWidth(font) + 8);
        place(font, this.add, x, addTop, addWidth, Kit.ROW);
        place(font, this.total, x + addWidth + 8, addTop, inner - addWidth - 8, Kit.ROW);
        place(font, this.notes, x, notesTop, inner, Kit.ROW);
        int labelWidth = 90;
        place(font, this.nameBox, x + labelWidth, nameTop, Math.min(320, inner - labelWidth), Kit.ROW);
        place(font, this.bar, x, barTop, inner, this.job == null ? 0 : Kit.ROW);
        this.foot(font, this.start, this.close);
    }

    private static void place(Font font, Gadget gadget, int x, int y, int width, int height) {
        gadget.clip(Integer.MIN_VALUE, Integer.MAX_VALUE);
        gadget.place(font, x, y, Math.max(0, width), height);
    }

    @Override
    void paint(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        this.heading(graphics, font, t("explained"));
        Kit.text(graphics, font, t("name").getString(), this.left + 9, this.nameBox.y + 3, Controls.PARCHMENT);
    }

    private void rebuild() {
        StringBuilder key = new StringBuilder();
        this.order.forEach(entry -> key.append(entry.file).append('|'));
        if (key.toString().equals(this.builtFor)) {
            return;
        }
        this.builtFor = key.toString();
        this.list.clear();
        int at = 0;
        for (int i = 0; i < this.order.size(); i++) {
            if (i > 0) {
                this.list.add(new Boundary(i, at));
            }
            this.list.add(new Source(i));
            at += this.order.get(i).totalTicks;
        }
    }

    private void move(int from, int to) {
        if (from < 0 || from >= this.order.size() || this.running()) {
            return;
        }
        int target = Math.max(0, Math.min(this.order.size() - 1, to));
        if (target == from) {
            return;
        }
        LibraryIndex.Entry moving = this.order.remove(from);
        this.order.add(target, moving);
    }

    private void openAddMenu() {
        List<Menu.Item> items = new ArrayList<>();
        for (LibraryIndex.Entry entry : this.everything) {
            if (this.order.stream().noneMatch(chosen -> chosen.file.equals(entry.file))) {
                items.add(new Menu.Item(Component.literal(RecordingsScreen.nameOf(entry) + "  (" + Controls.length(entry.totalTicks) + ")"), () -> {
                    this.order.add(entry);
                    this.load();
                }));
            }
        }
        Menu.drop(items, this.add.x, this.add.y, Math.max(220, this.add.width), this.add.height, this.add);
    }

    private void startCombine() {
        Entry.blur();
        if (this.running() || this.order.size() < 2) {
            return;
        }
        List<Path> files = new ArrayList<>();
        this.order.forEach(entry -> files.add(Library.fileOf(entry)));
        try {
            this.job = Library.combine(files, this.name.trim(), null);
        } catch (IllegalArgumentException | IllegalStateException e) {
            Notices.show(Component.translatable("dailies.ui.notice.refused", String.valueOf(e.getMessage())));
            return;
        }
        LibraryJob started = this.job;
        Minecraft game = Minecraft.getInstance();
        started.done().whenComplete((made, error) -> game.execute(() -> {
            if (made != null) {
                Notices.show(t("made", started.name));
            }
        }));
    }

    @Override
    public void removed() {
        super.removed();
        this.textures.values().forEach(LibraryBits.Picture::close);
        this.textures.clear();
    }

    // ------------------------------------------------------------------ For the scripted test

    public List<String> orderShown() {
        List<String> names = new ArrayList<>();
        this.order.forEach(entry -> names.add(RecordingsScreen.nameOf(entry)));
        return names;
    }

    public void moveSource(int from, int to) {
        this.move(from, to);
    }

    public int boundariesShown() {
        int count = 0;
        for (Gadget row : this.list.rows()) {
            if (row instanceof Boundary) {
                count++;
            }
        }
        return count;
    }

    public void name(String words) {
        this.name = words;
    }

    public @Nullable LibraryJob startNow() {
        this.startCombine();
        return this.job;
    }

    // ------------------------------------------------------------------ The rows

    /** One recording to combine: a handle to drag it by, its preview, its name and length, and arrows to move it */
    private final class Source extends Gadget {

        private final int index;
        private final Gadget.Push up;
        private final Gadget.Push down;
        private final Gadget.Push remove;

        Source(int index) {
            this.index = index;
            this.up = Gadget.Push.icon(Kit.Icon.UP, t("up.tip"), () -> CombineScreen.this.move(this.index, this.index - 1));
            this.up.when(() -> this.index > 0 && !CombineScreen.this.running());
            this.down = Gadget.Push.icon(Kit.Icon.DOWN, t("down.tip"), () -> CombineScreen.this.move(this.index, this.index + 1));
            this.down.when(() -> this.index < CombineScreen.this.order.size() - 1 && !CombineScreen.this.running());
            this.remove = Gadget.Push.icon(Kit.Icon.CROSS, t("remove.tip"), () -> {
                if (!CombineScreen.this.running() && this.index < CombineScreen.this.order.size()) {
                    CombineScreen.this.order.remove(this.index);
                }
            });
            this.remove.when(() -> CombineScreen.this.order.size() > 2 && !CombineScreen.this.running());
        }

        private LibraryIndex.Entry entry() {
            return CombineScreen.this.order.get(Math.min(this.index, CombineScreen.this.order.size() - 1));
        }

        @Override
        int heightFor(Font font, int forWidth) {
            return SOURCE_HEIGHT;
        }

        @Override
        void clip(int top, int bottom) {
            super.clip(top, bottom);
            this.up.clip(top, bottom);
            this.down.clip(top, bottom);
            this.remove.clip(top, bottom);
        }

        @Override
        void place(Font font, int newX, int newY, int newWidth, int newHeight) {
            super.place(font, newX, newY, newWidth, newHeight);
            int buttonY = newY + (newHeight - Kit.ROW) / 2;
            int right = newX + newWidth - 4;
            this.remove.place(font, right - 16, buttonY, 16, Kit.ROW);
            this.down.place(font, right - 34, buttonY, 16, Kit.ROW);
            this.up.place(font, right - 52, buttonY, 16, Kit.ROW);
        }

        @Override
        void draw(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
            if (CombineScreen.this.order.isEmpty()) {
                return;
            }
            LibraryIndex.Entry entry = this.entry();
            boolean held = CombineScreen.this.dragging == this.index;
            boolean hovered = this.over(mouseX, mouseY);
            graphics.fill(this.x, this.y, this.x + this.width, this.y + this.height, held ? Controls.HIGHLIGHT : hovered ? 0x28D4B25A : 0x18000000);
            graphics.outline(this.x, this.y, this.width, this.height, held ? Controls.BRASS_BRIGHT : 0x40D4B25A);
            // The handle: six dots, the sign of something that can be dragged
            for (int row = 0; row < 3; row++) {
                for (int column = 0; column < 2; column++) {
                    int dotX = this.x + 5 + column * 3;
                    int dotY = this.y + this.height / 2 - 4 + row * 3;
                    graphics.fill(dotX, dotY, dotX + 2, dotY + 2, hovered || held ? Controls.PARCHMENT : Controls.BRASS);
                }
            }
            int pictureX = this.x + 16;
            int pictureY = this.y + 3;
            int pictureHeight = this.height - 6;
            int pictureWidth = pictureHeight * 16 / 9;
            LibraryBits.Picture picture = CombineScreen.this.picture(entry);
            if (picture != null) {
                picture.draw(graphics, pictureX, pictureY, pictureWidth, pictureHeight);
            } else {
                LibraryBits.noPicture(graphics, font, pictureX, pictureY, pictureWidth, pictureHeight, null);
            }
            int textLeft = pictureX + pictureWidth + 8;
            int room = Math.max(10, this.x + this.width - 60 - textLeft);
            Kit.text(graphics, font, Kit.clip(font, (this.index + 1) + ". " + RecordingsScreen.nameOf(entry), room), textLeft, this.y + 6, Controls.PARCHMENT);
            String details = entry.world.isBlank() ? t("source", Controls.length(entry.totalTicks), LibraryBits.day(entry.newest())).getString()
                : t("source_world", Controls.length(entry.totalTicks), entry.world, LibraryBits.day(entry.newest())).getString();
            Kit.text(graphics, font, Kit.clip(font, details, room), textLeft, this.y + 18, Controls.PARCHMENT_DIM);
            this.up.draw(graphics, font, mouseX, mouseY);
            this.down.draw(graphics, font, mouseX, mouseY);
            this.remove.draw(graphics, font, mouseX, mouseY);
            int drop = CombineScreen.this.dropAt;
            if (CombineScreen.this.dragging >= 0 && drop >= 0 && drop != CombineScreen.this.dragging) {
                if (drop == this.index && drop < CombineScreen.this.dragging) {
                    graphics.fill(this.x, this.y - 2, this.x + this.width, this.y, Controls.PARCHMENT);
                } else if (drop == this.index && drop > CombineScreen.this.dragging) {
                    graphics.fill(this.x, this.y + this.height, this.x + this.width, this.y + this.height + 2, Controls.PARCHMENT);
                }
            }
        }

        @Override
        @Nullable Gadget press(double mouseX, double mouseY, int button) {
            if (!this.over(mouseX, mouseY)) {
                return null;
            }
            for (Gadget.Push push : List.of(this.up, this.down, this.remove)) {
                Gadget hit = push.press(mouseX, mouseY, button);
                if (hit != null) {
                    return hit;
                }
            }
            if (button != 0 || CombineScreen.this.running()) {
                return null;
            }
            CombineScreen.this.dragging = this.index;
            CombineScreen.this.dropAt = this.index;
            return this;
        }

        @Override
        void drag(double mouseX, double mouseY) {
            int best = CombineScreen.this.dragging;
            for (Gadget row : CombineScreen.this.list.rows()) {
                if (row instanceof Source other && mouseY >= row.y && mouseY < row.y + row.height + BOUNDARY_HEIGHT) {
                    best = other.index;
                }
            }
            Gadget first = CombineScreen.this.list.rows().isEmpty() ? null : CombineScreen.this.list.rows().get(0);
            if (first != null && mouseY < first.y) {
                best = 0;
            }
            CombineScreen.this.dropAt = best;
        }

        @Override
        void release(double mouseX, double mouseY) {
            int from = CombineScreen.this.dragging;
            int to = CombineScreen.this.dropAt;
            CombineScreen.this.dragging = -1;
            CombineScreen.this.dropAt = -1;
            CombineScreen.this.move(from, to);
        }

        @Override
        @Nullable Component tipAt(double mouseX, double mouseY) {
            if (!this.over(mouseX, mouseY)) {
                return null;
            }
            for (Gadget.Push push : List.of(this.up, this.down, this.remove)) {
                Component words = push.tipAt(mouseX, mouseY);
                if (words != null) {
                    return words;
                }
            }
            return t("source.tip");
        }

        @Override
        void collect(List<Gadget> order) {
            this.up.collect(order);
            this.down.collect(order);
            this.remove.collect(order);
        }

        @Override
        boolean holds(Gadget control) {
            return control == this || control == this.up || control == this.down || control == this.remove;
        }
    }

    /** Where one recording ends and the next begins, with the next one's preview: the world there is rebuilt from its start */
    private final class Boundary extends Gadget {

        private final int next;
        private final int tick;

        Boundary(int next, int tick) {
            this.next = next;
            this.tick = tick;
        }

        @Override
        int heightFor(Font font, int forWidth) {
            return BOUNDARY_HEIGHT;
        }

        @Override
        void draw(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
            if (this.next >= CombineScreen.this.order.size()) {
                return;
            }
            LibraryIndex.Entry entry = CombineScreen.this.order.get(this.next);
            int middle = this.y + this.height / 2;
            // A dashed brass line across: a point on the timeline, not a stretch of it
            for (int dash = this.x + 16; dash < this.x + this.width - 4; dash += 6) {
                graphics.fill(dash, middle, Math.min(dash + 3, this.x + this.width - 4), middle + 1, Controls.BRASS_DIM);
            }
            int pictureHeight = this.height - 4;
            int pictureWidth = pictureHeight * 16 / 9;
            int pictureX = this.x + 40;
            graphics.fill(pictureX - 4, this.y + 1, pictureX + pictureWidth + 4, this.y + this.height - 1, Controls.PLATE);
            LibraryBits.Picture picture = CombineScreen.this.picture(entry);
            if (picture != null) {
                picture.draw(graphics, pictureX, this.y + 2, pictureWidth, pictureHeight);
            } else {
                LibraryBits.noPicture(graphics, font, pictureX, this.y + 2, pictureWidth, pictureHeight, null);
            }
            String words = t("boundary", Controls.time(this.tick), RecordingsScreen.nameOf(entry)).getString();
            int textLeft = pictureX + pictureWidth + 8;
            String shown = Kit.clip(font, words, this.x + this.width - 8 - textLeft);
            graphics.fill(textLeft - 3, middle - 5, textLeft + font.width(shown) + 3, middle + 5, Controls.PLATE);
            Kit.text(graphics, font, shown, textLeft, middle - 4, Controls.BRASS_BRIGHT);
        }

        @Override
        @Nullable Component tipAt(double mouseX, double mouseY) {
            return this.over(mouseX, mouseY) ? t("boundary.tip") : null;
        }
    }

}
