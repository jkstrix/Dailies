package comedymonkey.dailies.ui;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.file.RecordingFile;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.Recordings;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.ConfirmScreen;
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
public final class RecordingsScreen extends Screen {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT);
    private static final int ROW_HEIGHT = 26;

    private final @Nullable Screen parent;
    private List<Recordings.Entry> found = List.of();
    private boolean looking = false;
    private boolean lookedOnce = false;
    /** Goes up with every look, so an answer that arrives after a newer question is ignored */
    private int look = 0;
    private @Nullable Path chosen = null;

    private Rows rows;
    private Button watch;
    private Button rename;
    private Button delete;

    public RecordingsScreen(@Nullable Screen parent) {
        super(Component.translatable("dailies.ui.list.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int listTop = 32;
        int listHeight = Math.max(ROW_HEIGHT, this.height - 64 - listTop);
        this.rows = new Rows(this.minecraft, this.width, listHeight, listTop);
        this.addRenderableWidget(this.rows);

        int gap = 4;
        int buttonWidth = Math.min(100, (this.width - 16 - 2 * gap) / 3);
        int rowWidth = 3 * buttonWidth + 2 * gap;
        int left = (this.width - rowWidth) / 2;
        int top = this.height - 54;
        this.watch = this.addRenderableWidget(Button.builder(Component.translatable("dailies.ui.list.watch"), button -> this.watchChosen()).bounds(left, top, buttonWidth, 20).build());
        this.rename = this.addRenderableWidget(Button.builder(Component.translatable("dailies.ui.list.rename"), button -> this.renameChosen()).bounds(left + buttonWidth + gap, top, buttonWidth, 20).build());
        this.delete = this.addRenderableWidget(Button.builder(Component.translatable("dailies.ui.list.delete"), button -> this.deleteChosen()).bounds(left + 2 * (buttonWidth + gap), top, buttonWidth, 20).build());

        int halfWidth = (rowWidth - gap) / 2;
        this.addRenderableWidget(Button.builder(Component.translatable("dailies.ui.list.open_folder"), button -> this.openFolder()).bounds(left, top + 24, halfWidth, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("dailies.ui.list.done"), button -> this.onClose()).bounds(left + rowWidth - halfWidth, top + 24, halfWidth, 20).build());

        this.rows.show(this.found);
        if (!this.lookedOnce) {
            this.lookedOnce = true;
            this.lookAgain();
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
            if (this.rows != null) {
                this.rows.show(this.found);
            }
        }));
    }

    private @Nullable Recordings.Entry chosenEntry() {
        Row row = this.rows == null ? null : this.rows.getSelected();
        return row == null ? null : row.entry;
    }

    private void updateButtons() {
        boolean any = this.chosenEntry() != null;
        this.watch.active = any;
        this.rename.active = any;
        this.delete.active = any;
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
                    Controls.offThread(() -> comedymonkey.dailies.library.Library.rename(file, name), Component.translatable("dailies.ui.rename.failed"), this::lookAgain);
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
        this.minecraft.setScreen(new ConfirmScreen(
                yes -> {
                    this.minecraft.setScreen(this);
                    if (yes) {
                        this.looking = true;
                        Controls.offThread(() -> comedymonkey.dailies.library.Library.delete(file), Component.translatable("dailies.ui.delete.failed"), this::lookAgain);
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
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, Controls.PARCHMENT);
        if (this.found.isEmpty()) {
            int middle = this.rows.getY() + this.rows.getHeight() / 2;
            if (this.looking) {
                graphics.drawCenteredString(this.font, Component.translatable("dailies.ui.list.looking"), this.width / 2, middle - 4, Controls.PARCHMENT_DIM);
            } else {
                graphics.drawCenteredString(this.font, Component.translatable("dailies.ui.list.none"), this.width / 2, middle - 10, Controls.PARCHMENT);
                graphics.drawCenteredString(this.font, Component.translatable("dailies.ui.list.none_hint"), this.width / 2, middle + 2, Controls.PARCHMENT_DIM);
            }
        }
    }

    private static String nameOf(Recordings.Entry entry) {
        String name = entry.manifest().name;
        if (name != null && !name.isBlank()) {
            return name;
        }
        String file = entry.file().getFileName().toString();
        return file.endsWith(RecordingFile.EXTENSION) ? file.substring(0, file.length() - RecordingFile.EXTENSION.length()) : file;
    }

    /** The scrolling list */
    private final class Rows extends ObjectSelectionList<Row> {

        Rows(Minecraft minecraft, int width, int height, int top) {
            super(minecraft, width, height, top, ROW_HEIGHT);
        }

        void show(List<Recordings.Entry> entries) {
            Path keep = RecordingsScreen.this.chosen;
            this.clearEntries();
            Row again = null;
            for (Recordings.Entry entry : entries) {
                Row row = new Row(entry);
                this.addEntry(row);
                if (entry.file().equals(keep)) {
                    again = row;
                }
            }
            this.setScrollAmount(this.getScrollAmount());
            this.setSelected(again);
        }

        @Override
        public void setSelected(@Nullable Row row) {
            super.setSelected(row);
            RecordingsScreen.this.chosen = row == null ? null : row.entry.file();
            if (RecordingsScreen.this.watch != null) {
                RecordingsScreen.this.updateButtons();
            }
        }

        @Override
        public int getRowWidth() {
            return Math.max(120, Math.min(400, this.width - 40));
        }
    }

    /** One recording in the list: its name, and under it when it was made, how long it is, where, and how big */
    private final class Row extends ObjectSelectionList.Entry<Row> {

        private final Recordings.Entry entry;
        private final Component name;
        private final Component details;
        private long clickedAt = 0;

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
        public Component getNarration() {
            return Component.translatable("narrator.select", this.name);
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovering, float partialTick) {
            int room = Math.max(10, width - 8);
            graphics.drawString(RecordingsScreen.this.font, RecordingsScreen.this.font.plainSubstrByWidth(this.name.getString(), room), left + 3, top + 2, Controls.PARCHMENT, false);
            graphics.drawString(RecordingsScreen.this.font, RecordingsScreen.this.font.plainSubstrByWidth(this.details.getString(), room), left + 3, top + 13, Controls.PARCHMENT_DIM, false);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != 0) {
                return false;
            }
            RecordingsScreen.this.rows.setSelected(this);
            long now = Util.getMillis();
            if (now - this.clickedAt < 250) {
                RecordingsScreen.this.watchChosen();
            }
            this.clickedAt = now;
            return true;
        }
    }

}
