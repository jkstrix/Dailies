package comedymonkey.dailies.ui;

import comedymonkey.dailies.library.Bin;
import comedymonkey.dailies.library.Library;
import comedymonkey.dailies.library.Retention;
import comedymonkey.dailies.library.StorageReport;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * The library's view of the bin (specification REC-9): what was deleted, how long it has left, and putting it back.
 * Emptying it is the only delete for good, and asks first.
 */
final class BinView extends LibraryView {

    @Nullable String chosen = null;

    Gadget.Push restore;
    Gadget.Push emptyBin;

    BinView(RecordingsScreen screen) {
        super(screen);
    }

    @Override
    void build() {
        this.restore = push("restore", this::restoreChosen, () -> this.chosenItem() != null);
        this.emptyBin = push("empty_bin", this::askEmptyBin, () -> !this.screen.binItems.isEmpty());
    }

    @Override
    @Nullable Component count() {
        return t(this.screen.binItems.size() == 1 ? "bin.count_one" : "bin.count", this.screen.binItems.size(),
                StorageReport.bytes(this.screen.binItems.stream().mapToLong(item -> item.size).sum()));
    }

    @Override
    Component barWords() {
        return t("bin.explained", Library.binDays());
    }

    @Override
    Gadget[] footLeft() {
        return new Gadget[]{this.restore, this.emptyBin};
    }

    @Override
    Gadget[] footRight() {
        return new Gadget[]{this.screen.openFolder, this.screen.done};
    }

    @Override
    boolean empty() {
        return this.screen.binItems.isEmpty();
    }

    @Override
    String[] emptyWords() {
        return new String[]{t("bin.empty").getString(), t("bin.empty_hint", Library.binDays()).getString()};
    }

    @Override
    Path folder() {
        return Library.bin().folder();
    }

    @Override
    void fillRows(Form rows) {
        boolean found = false;
        for (Bin.Item item : this.screen.binItems) {
            rows.add(new BinRow(item));
            found |= item.id.equals(this.chosen);
        }
        if (!found) {
            this.chosen = null;
        }
    }

    /** The thing in the bin chosen, while the bin is on show */
    @Nullable Bin.Item chosenItem() {
        if (!this.showing()) {
            return null;
        }
        for (Bin.Item item : this.screen.binItems) {
            if (item.id.equals(this.chosen)) {
                return item;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ The pane beside the list

    @Override
    String sideShape() {
        return "bin:" + this.chosen + ":" + this.screen.binItems.size();
    }

    @Override
    void fillSide(Form side) {
        Bin.Item item = this.chosenItem();
        if (item == null) {
            side.add(new Gadget.Note(t("bin.details.none", Library.binDays())));
            return;
        }
        side.add(new Gadget.Heading(Component.literal(item.name)));
        side.add(new LibraryBits.Fact(t("bin.details.kind"), t("bin.kind." + item.kind).getString()));
        if (!item.world.isBlank()) {
            side.add(new LibraryBits.Fact(item.kind() == Bin.Kind.SCENE ? t("bin.details.recording") : t("details.world"), item.world));
        }
        side.add(new LibraryBits.Fact(t("bin.details.deleted"), LibraryBits.date(item.binned)));
        int left = Retention.daysLeft(item, Library.binDays(), System.currentTimeMillis());
        side.add(new LibraryBits.Fact(t("bin.details.left"), t(left == 1 ? "bin.day_one" : "bin.days", left).getString()));
        side.add(new LibraryBits.Fact(t("details.size"), StorageReport.bytes(item.size)));
        if (item.kind() != Bin.Kind.SCENE) {
            side.add(new LibraryBits.Fact(t("bin.details.from"), item.from));
        }
        side.add(new Gadget.Note(t("bin.details.hint." + item.kind)));
    }

    // ------------------------------------------------------------------ What the buttons do

    private void restoreChosen() {
        Bin.Item item = this.chosenItem();
        if (item == null) {
            return;
        }
        this.chosen = null;
        this.screen.looking = true;
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
            this.screen.lookAgain();
        }));
    }

    private void askEmptyBin() {
        long size = this.screen.binItems.stream().mapToLong(item -> item.size).sum();
        int count = this.screen.binItems.size();
        Minecraft game = Minecraft.getInstance();
        game.setScreen(new AskScreen(yes -> {
            game.setScreen(this.screen);
            if (yes) {
                this.chosen = null;
                this.screen.work(Library::emptyBin, t("bin.emptied", count));
            }
        }, t("bin.ask.title"), t(count == 1 ? "bin.ask.question_one" : "bin.ask.question", count, StorageReport.bytes(size)), t("bin.ask.yes"), t("bin.ask.no")));
    }

    /** Chooses the item in the bin with this name, for the scripted test */
    boolean chooseByName(String name) {
        for (Bin.Item item : this.screen.binItems) {
            if (item.name.equals(name)) {
                this.chosen = item.id;
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ The rows

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
            boolean picked = this.item.id.equals(BinView.this.chosen);
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
            BinView.this.chosen = this.item.id;
            return this;
        }
    }

}
