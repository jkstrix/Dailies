package comedymonkey.dailies.ui;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.library.DiskGuard;
import comedymonkey.dailies.library.Retention;
import comedymonkey.dailies.library.StorageReport;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Locale;

/**
 * The library's view of storage: how much room recordings, moments audio, exports and the bin take, per world, and
 * the settings that keep it in check.
 */
final class StorageView extends LibraryView {

    Gadget.Push measure;
    Gadget.Push settings;

    StorageView(RecordingsScreen screen) {
        super(screen);
    }

    @Override
    void build() {
        this.measure = push("measure", this.screen::measureAgain, () -> !this.screen.measuring);
        this.settings = push("settings", () -> SettingsScreen.open(this.screen), () -> true);
    }

    @Override
    @Nullable Component count() {
        StorageReport report = this.screen.report;
        return report == null ? null : t("storage.count", StorageReport.bytes(report.total()));
    }

    @Override
    Component barWords() {
        return this.screen.free < 0 ? t("storage.measuring") : t("storage.free", StorageReport.bytes(this.screen.free));
    }

    @Override
    Gadget[] footLeft() {
        return new Gadget[]{this.measure, this.settings};
    }

    @Override
    Gadget[] footRight() {
        return new Gadget[]{this.screen.openFolder, this.screen.done};
    }

    @Override
    boolean empty() {
        return this.screen.report == null;
    }

    @Override
    Path folder() {
        return Host.get().dataDirectory();
    }

    // ------------------------------------------------------------------ The list

    @Override
    void fillRows(Form rows) {
        StorageReport measured = this.screen.report;
        if (measured == null) {
            return;
        }
        rows.add(new Gadget.Heading(t("storage.used")));
        long most = Math.max(1, Math.max(Math.max(measured.recordings, measured.audio), Math.max(measured.exports, measured.bin)));
        rows.add(new Usage(t("storage.recordings"), measured.recordings, most, t("storage.recordings.tip")));
        rows.add(new Usage(t("storage.audio"), measured.audio, most, t("storage.audio.tip")));
        rows.add(new Usage(t("storage.exports"), measured.exports, most, t("storage.exports.tip")));
        rows.add(new Usage(t("storage.bin"), measured.bin, most, t("storage.bin.tip")));
        Retention.Disk disk = DiskGuard.state(measured.free);
        int colour = disk == Retention.Disk.FINE ? Controls.PARCHMENT_DIM : Controls.ALERT;
        Component freeWords = measured.free < 0 ? t("storage.free_unknown")
            : t("storage.free_of", StorageReport.bytes(measured.free), StorageReport.bytes(measured.disk));
        rows.add(new Gadget.Words(() -> freeWords, colour).tip(t("storage.free.tip." + disk.name().toLowerCase(Locale.ROOT),
            Host.get().setting("diskWarnGigabytes", 20), Host.get().setting("diskPauseGigabytes", 5))));
        rows.add(new Gadget.Heading(t("storage.by_world")));
        rows.add(new WorldRow(null));
        for (StorageReport.World world : measured.byWorld()) {
            rows.add(new WorldRow(world));
        }
    }

    // ------------------------------------------------------------------ The settings beside it

    @Override
    String sideShape() {
        // Not built afresh when a setting is saved, which would take the keys away from the box they moved on to
        StorageReport report = this.screen.report;
        return "storage:" + (report == null ? 0 : report.hashCode());
    }

    @Override
    void fillSide(Form side) {
        side.add(new Gadget.Heading(t("storage.keeping")));
        side.add(this.number("binDays", 30, 1, 3650));
        side.add(this.number("retentionDays", 0, 0, 36500));
        side.add(this.check("voiceAudioOnceTranscribed"));
        side.add(this.number("voiceAudioDays", 0, 0, 36500));
        side.add(new Gadget.Heading(t("storage.disk")));
        side.add(this.number("diskWarnGigabytes", 20, 0, 100000));
        side.add(this.number("diskPauseGigabytes", 5, 0, 100000));
        side.add(new Gadget.Note(t("storage.zero_keeps")));
    }

    private static Object settingNow(String name, Object fallback) {
        Object waiting = SettingsWriter.pending(name);
        return waiting != null ? waiting : Host.get().setting(name, fallback);
    }

    private Gadget number(String name, int fallback, int min, int max) {
        Entry box = Entry.number(() -> ((Number) settingNow(name, fallback)).doubleValue(), min, max, true, value -> {
            this.saveSetting(name, (int) Math.rint(value));
            return null;
        });
        Gadget pair = new Gadget.Pair(t("setting." + name), box.wide(64));
        pair.tip(t("setting." + name + ".tip"));
        box.tip(t("setting." + name + ".tip"));
        return pair;
    }

    private Gadget check(String name) {
        Gadget.Check box = new Gadget.Check(t("setting." + name), () -> Boolean.TRUE.equals(settingNow(name, false)), on -> this.saveSetting(name, on));
        box.tip(t("setting." + name + ".tip"));
        return box;
    }

    private void saveSetting(String name, Object value) {
        SettingsWriter.save(name, value, trouble -> {
            if (trouble != null) {
                Notices.show(Component.translatable("dailies.ui.notice.refused", String.valueOf(trouble.getMessage())));
            }
            if (this.showing()) {
                // The list says how full the disk may get before a warning; the settings beside it are left as they are
                this.screen.rows.clear();
                this.fillRows(this.screen.rows);
            }
        });
    }

    // ------------------------------------------------------------------ The rows

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
