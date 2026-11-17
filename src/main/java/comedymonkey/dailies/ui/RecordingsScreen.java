package comedymonkey.dailies.ui;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.file.RecordingFile;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.Recordings;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * The list of recordings (specification REC-5), reached from the title screen and the pause menu: newest first, each
 * with its name, date, length, world and size, and buttons to watch, rename and delete one and to open the folder.
 * The folder is only ever read, and files only ever changed, away from the client thread.
 */
public final class RecordingsScreen extends KitScreen {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT);
    private static final int ROW_HEIGHT = 26;

    private List<Recordings.Entry> found = List.of();
    private boolean looking = false;
    private boolean lookedOnce = false;
    /** Goes up with every look, so an answer that arrives after a newer question is ignored */
    private int look = 0;
    private @Nullable Path chosen = null;

    private final Form rows = new Form();
    private Gadget.Push watch;
    private Gadget.Push rename;
    private Gadget.Push delete;
    private Gadget.Push folder;
    private Gadget.Push done;

    public RecordingsScreen(@Nullable Screen parent) {
        super(Component.translatable("dailies.ui.list.title"), parent);
    }

    @Override
    void build() {
        this.watch = new Gadget.Push(Component.translatable("dailies.ui.list.watch"), this::watchChosen);
        this.rename = new Gadget.Push(Component.translatable("dailies.ui.list.rename"), this::renameChosen);
        this.delete = new Gadget.Push(Component.translatable("dailies.ui.list.delete"), this::deleteChosen);
        this.watch.when(() -> this.chosenEntry() != null).tip(Component.translatable("dailies.ui.list.watch.tip"));
        this.rename.when(() -> this.chosenEntry() != null).tip(Component.translatable("dailies.ui.list.rename.tip"));
        this.delete.when(() -> this.chosenEntry() != null).tip(Component.translatable("dailies.ui.list.delete.tip"));
        this.folder = new Gadget.Push(Component.translatable("dailies.ui.list.open_folder"), this::openFolder);
        this.folder.tip(Component.translatable("dailies.ui.list.open_folder.tip"));
        this.done = new Gadget.Push(Component.translatable("dailies.ui.list.done"), this::onClose);
        this.fixed.add(this.watch);
        this.fixed.add(this.rename);
        this.fixed.add(this.delete);
        this.fixed.add(this.folder);
        this.fixed.add(this.done);
        this.forms.add(this.rows);
        this.show(this.found);
        if (!this.lookedOnce) {
            this.lookedOnce = true;
            this.lookAgain();
        }
    }

    private @Nullable Component count() {
        if (this.found.isEmpty()) {
            return null;
        }
        return Component.translatable(this.found.size() == 1 ? "dailies.ui.list.count_one" : "dailies.ui.list.count", this.found.size());
    }

    @Override
    void arrange(Font font) {
        this.centre(460, this.height - 24);
        int head = this.headingHeight(font, this.count());
        int foot = Kit.ROW + 2 + 8 + 6;
        this.rows.place(font, this.left + 5, this.top + head, this.right - this.left - 10, this.bottom - foot - this.top - head);
        this.footLeft(font, this.watch, this.rename, this.delete);
        this.foot(font, this.folder, this.done);
    }

    private void show(List<Recordings.Entry> entries) {
        this.rows.clear();
        boolean still = false;
        for (Recordings.Entry entry : entries) {
            this.rows.add(new Row(entry));
            still |= entry.file().equals(this.chosen);
        }
        if (!still) {
            this.chosen = null;
        }
    }

    /** Reads the folder again, off the client thread */
    private void lookAgain() {
        int asked = ++this.look;
        this.looking = true;
        Minecraft game = Minecraft.getInstance();
        CompletableFuture.supplyAsync(Recordings::list, Util.ioPool()).whenComplete((list, error) -> game.execute(() -> {
            if (asked != this.look) {
                return;
            }
            this.looking = false;
            if (error != null) {
                Host.LOGGER.error("Dailies: the recordings folder could not be read", error);
                Notices.show(Component.translatable("dailies.ui.list.unreadable"));
            }
            this.found = list == null ? List.of() : List.copyOf(list);
            this.show(this.found);
        }));
    }

    private @Nullable Recordings.Entry chosenEntry() {
        for (Recordings.Entry entry : this.found) {
            if (entry.file().equals(this.chosen)) {
                return entry;
            }
        }
        return null;
    }

    /** Whether this is the recording being watched right now, which must not be changed underneath playback */
    private static boolean beingWatched(Path file) {
        RecordingFile open = Playback.active() ? Playback.file() : null;
        return open != null && open.file().toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize());
    }

    private void watchChosen() {
        Recordings.Entry entry = this.chosenEntry();
        if (entry == null) {
            return;
        }
        if (Recorder.get().state() != Recorder.State.IDLE) {
            Notices.show(Component.translatable("dailies.ui.list.finish_first"));
            return;
        }
        Path file = entry.file();
        Controls.guarded(() -> {
            this.minecraft.setScreen(null);
            Playback.open(file);
        });
    }

    private void renameChosen() {
        Recordings.Entry entry = this.chosenEntry();
        if (entry == null) {
            return;
        }
        Path file = entry.file();
        if (beingWatched(file)) {
            Notices.show(Component.translatable("dailies.ui.list.close_first"));
            return;
        }
        this.minecraft.setScreen(new NameScreen(
                Component.translatable("dailies.ui.rename.title"),
                Component.translatable("dailies.ui.rename.explanation"),
                entry.manifest().name,
                Component.translatable("dailies.ui.rename.save"),
                Component.translatable("dailies.ui.rename.cancel"),
                name -> {
                    this.minecraft.setScreen(this);
                    if (name.isBlank() || name.equals(entry.manifest().name)) {
                        return;
                    }
                    this.looking = true;
                    Controls.offThread(() -> {
                        try (RecordingFile recording = RecordingFile.openForEditing(file)) {
                            recording.rename(name);
                        }
                    }, Component.translatable("dailies.ui.rename.failed"), this::lookAgain);
                },
                () -> this.minecraft.setScreen(this)));
    }

    private void deleteChosen() {
        Recordings.Entry entry = this.chosenEntry();
        if (entry == null) {
            return;
        }
        Path file = entry.file();
        if (beingWatched(file)) {
            Notices.show(Component.translatable("dailies.ui.list.close_first"));
            return;
        }
        this.minecraft.setScreen(new AskScreen(
                yes -> {
                    this.minecraft.setScreen(this);
                    if (yes) {
                        this.looking = true;
                        Controls.offThread(() -> Files.delete(file), Component.translatable("dailies.ui.delete.failed"), this::lookAgain);
                    }
                },
                Component.translatable("dailies.ui.delete.title"),
                Component.translatable("dailies.ui.delete.question", nameOf(entry)),
                Component.translatable("dailies.ui.delete.yes"),
                Component.translatable("dailies.ui.delete.no")));
    }

    private void openFolder() {
        Controls.offThread(() -> {
            Path folder = Recorder.folder();
            Files.createDirectories(folder);
            Util.getPlatform().openPath(folder);
        }, Component.translatable("dailies.ui.list.folder_failed"), () -> {
        });
    }

    @Override
    public void onClose() {
        this.look++;
        super.onClose();
    }

    @Override
    void paint(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        this.heading(graphics, font, this.count());
        if (this.found.isEmpty()) {
            int middle = this.rows.y + this.rows.height / 2;
            int wide = this.right - this.left - 20;
            if (this.looking) {
                Kit.centred(graphics, font, Component.translatable("dailies.ui.list.looking").getString(), this.left + 10, middle - 4, wide, Controls.PARCHMENT_DIM);
            } else {
                Kit.centred(graphics, font, Component.translatable("dailies.ui.list.none").getString(), this.left + 10, middle - 11, wide, Controls.PARCHMENT);
                Kit.centred(graphics, font, Component.translatable("dailies.ui.list.none_hint").getString(), this.left + 10, middle + 2, wide, Controls.PARCHMENT_DIM);
            }
        }
    }

    @Override
    boolean keyElsewhere(int keyCode, int modifiers) {
        if ((keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_UP || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN) && !this.found.isEmpty()) {
            // The arrows move the choice along the list
            int at = this.chosenEntry() == null ? -1 : this.found.indexOf(this.chosenEntry());
            int next = Math.max(0, Math.min(this.found.size() - 1, at + (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN ? 1 : -1)));
            this.chosen = this.found.get(next).file();
            if (next < this.rows.rows().size()) {
                this.rows.reveal(this.rows.rows().get(next));
            }
            return true;
        }
        if ((keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) && this.chosenEntry() != null) {
            this.watchChosen();
            return true;
        }
        return false;
    }

    private static String nameOf(Recordings.Entry entry) {
        String name = entry.manifest().name;
        if (name != null && !name.isBlank()) {
            return name;
        }
        String file = entry.file().getFileName().toString();
        return file.endsWith(RecordingFile.EXTENSION) ? file.substring(0, file.length() - RecordingFile.EXTENSION.length()) : file;
    }

    /** One recording in the list: its name, and under it when it was made, how long it is, where, and how big */
    private final class Row extends Gadget {

        private final Recordings.Entry entry;
        private final Component name;
        private final Component details;
        private long clickedAt = 0;
        private boolean cut = false;

        Row(Recordings.Entry entry) {
            this.entry = entry;
            RecordingManifest manifest = entry.manifest();
            this.name = Component.translatable("dailies.ui.list.name", nameOf(entry));
            long when = manifest.created > 0 ? manifest.created : entry.modified();
            String date = DATE.format(Instant.ofEpochMilli(when).atZone(ZoneId.systemDefault()));
            String megabytes = String.format(Locale.ROOT, "%.1f", entry.size() / 1048576.0);
            String length = Controls.length(manifest.totalTicks);
            this.details = manifest.worldName == null || manifest.worldName.isBlank()
                    ? Component.translatable("dailies.ui.list.details_no_world", date, length, megabytes)
                    : Component.translatable("dailies.ui.list.details", date, length, manifest.worldName, megabytes);
        }

        @Override
        int heightFor(Font font, int forWidth) {
            return ROW_HEIGHT;
        }

        @Override
        void draw(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
            boolean picked = this.entry.file().equals(RecordingsScreen.this.chosen);
            boolean hovered = this.over(mouseX, mouseY);
            if (picked) {
                graphics.fill(this.x, this.y, this.x + this.width, this.y + this.height, Controls.HIGHLIGHT);
                graphics.fill(this.x, this.y, this.x + 2, this.y + this.height, Controls.BRASS_BRIGHT);
            } else if (hovered) {
                graphics.fill(this.x, this.y, this.x + this.width, this.y + this.height, 0x28D4B25A);
            }
            // A reel of film at the left, so that the list reads as recordings before a word of it is read
            int reelX = this.x + 14;
            int reelY = this.y + this.height / 2;
            int brass = picked ? Controls.BRASS_BRIGHT : Controls.BRASS;
            graphics.fill(reelX - 6, reelY - 5, reelX + 6, reelY + 5, Controls.FIELD);
            graphics.renderOutline(reelX - 6, reelY - 5, 12, 10, brass);
            Kit.icon(graphics, Kit.Icon.PLAY, reelX, reelY, picked || hovered ? Controls.PARCHMENT : Controls.PARCHMENT_DIM);
            int textLeft = this.x + 28;
            int room = Math.max(10, this.width - 34);
            this.cut = font.width(this.name) > room || font.width(this.details) > room;
            Kit.text(graphics, font, Kit.clip(font, this.name.getString(), room), textLeft, this.y + 4, Controls.PARCHMENT);
            Kit.text(graphics, font, Kit.clip(font, this.details.getString(), room), textLeft, this.y + 15, Controls.PARCHMENT_DIM);
            if (!picked) {
                graphics.fill(this.x + 2, this.y + this.height, this.x + this.width - 2, this.y + this.height + 1, 0x30D4B25A);
            }
        }

        @Override
        @Nullable Component tipAt(double mouseX, double mouseY) {
            if (!this.over(mouseX, mouseY) || !this.cut) {
                return null;
            }
            return Component.empty().append(this.name).append("\n").append(this.details);
        }

        @Override
        @Nullable Gadget press(double mouseX, double mouseY, int button) {
            if (button != 0 || !this.over(mouseX, mouseY)) {
                return null;
            }
            RecordingsScreen.this.chosen = this.entry.file();
            long now = Util.getMillis();
            if (now - this.clickedAt < 300) {
                RecordingsScreen.this.watchChosen();
            }
            this.clickedAt = now;
            return this;
        }
    }

}
