package comedymonkey.dailies.ui;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.Recorder;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shown once, on the title screen, when the last session left recordings unfinished because the game closed or crashed
 * while they were being made (specification REC-6). Offers to recover them all, discard them all, or leave them for
 * the next launch.
 */
final class RecoveryScreen extends KitScreen {

    private final List<Path> unfinished;
    private final Component words;
    private Gadget.Push recover;
    private Gadget.Push discard;
    private Gadget.Push later;

    RecoveryScreen(List<Path> unfinished, @Nullable Screen parent) {
        super(Component.translatable("dailies.ui.recovery.title"), parent);
        this.unfinished = List.copyOf(unfinished);
        Component found = this.unfinished.size() == 1
                ? Component.translatable("dailies.ui.recovery.found_one")
                : Component.translatable("dailies.ui.recovery.found_many", this.unfinished.size());
        this.words = Component.empty().append(found).append(" ").append(Component.translatable("dailies.ui.recovery.explanation"));
    }

    @Override
    void build() {
        this.recover = new Gadget.Push(Component.translatable("dailies.ui.recovery.recover"), this::recover);
        this.discard = new Gadget.Push(Component.translatable("dailies.ui.recovery.discard"), this::askToDiscard);
        this.later = new Gadget.Push(Component.translatable("dailies.ui.recovery.later"), this::onClose);
        this.fixed.add(this.recover);
        this.fixed.add(this.discard);
        this.fixed.add(this.later);
    }

    @Override
    void arrange(Font font) {
        this.centre(320, 0);
        this.centre(320, this.headingHeight(font, this.words) + 6 + Kit.ROW + 2 + 8);
        this.foot(font, this.recover, this.discard, this.later);
    }

    @Override
    void paint(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        this.heading(graphics, font, this.words);
    }

    private void recover() {
        List<Path> files = this.unfinished;
        AtomicInteger recovered = new AtomicInteger();
        Notices.show(Component.translatable("dailies.ui.recovery.working"));
        this.minecraft.gui.setScreen(this.parent);
        Controls.offThread(() -> {
            for (Path file : files) {
                try {
                    Recorder.recover(file);
                    recovered.incrementAndGet();
                } catch (Exception e) {
                    Host.LOGGER.error("Dailies: the unfinished recording {} could not be recovered", file, e);
                }
            }
        }, Component.translatable("dailies.ui.recovery.failed"), () -> {
            if (recovered.get() == files.size()) {
                Notices.show(files.size() == 1
                        ? Component.translatable("dailies.ui.recovery.recovered_one")
                        : Component.translatable("dailies.ui.recovery.recovered_many", recovered.get()));
            } else {
                Notices.show(Component.translatable("dailies.ui.recovery.recovered_some", recovered.get(), files.size()));
            }
        });
    }

    private void askToDiscard() {
        this.minecraft.gui.setScreen(new AskScreen(
                yes -> {
                    if (yes) {
                        this.discard();
                    } else {
                        this.minecraft.gui.setScreen(this);
                    }
                },
                Component.translatable("dailies.ui.recovery.discard_title"),
                Component.translatable("dailies.ui.recovery.discard_question"),
                Component.translatable("dailies.ui.recovery.discard_yes"),
                Component.translatable("dailies.ui.recovery.discard_no")));
    }

    private void discard() {
        List<Path> files = this.unfinished;
        this.minecraft.gui.setScreen(this.parent);
        AtomicInteger discarded = new AtomicInteger();
        Controls.offThread(() -> {
            for (Path file : files) {
                Files.deleteIfExists(file);
                discarded.incrementAndGet();
            }
        }, Component.translatable("dailies.ui.recovery.discard_failed"), () -> {
            if (discarded.get() == files.size()) {
                Notices.show(Component.translatable("dailies.ui.recovery.discarded"));
            }
        });
    }

}
