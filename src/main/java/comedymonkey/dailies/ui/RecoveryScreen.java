package comedymonkey.dailies.ui;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.Recorder;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.ConfirmScreen;
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
final class RecoveryScreen extends Screen {

    private final List<Path> unfinished;
    private final @Nullable Screen parent;
    private MultiLineLabel words = MultiLineLabel.EMPTY;

    RecoveryScreen(List<Path> unfinished, @Nullable Screen parent) {
        super(Component.translatable("dailies.ui.recovery.title"));
        this.unfinished = List.copyOf(unfinished);
        this.parent = parent;
    }

    @Override
    protected void init() {
        Component found = this.unfinished.size() == 1
                ? Component.translatable("dailies.ui.recovery.found_one")
                : Component.translatable("dailies.ui.recovery.found_many", this.unfinished.size());
        this.words = MultiLineLabel.create(this.font, Math.max(100, this.width - 50), found, Component.translatable("dailies.ui.recovery.explanation"));

        int gap = 4;
        int buttonWidth = Math.min(110, (this.width - 16 - 2 * gap) / 3);
        int left = (this.width - (3 * buttonWidth + 2 * gap)) / 2;
        int top = Math.min(this.height - 28, this.textTop() + this.words.getLineCount() * 10 + 16);
        this.addRenderableWidget(Button.builder(Component.translatable("dailies.ui.recovery.recover"), button -> this.recover()).bounds(left, top, buttonWidth, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("dailies.ui.recovery.discard"), button -> this.askToDiscard()).bounds(left + buttonWidth + gap, top, buttonWidth, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("dailies.ui.recovery.later"), button -> this.onClose()).bounds(left + 2 * (buttonWidth + gap), top, buttonWidth, 20).build());
    }

    private int textTop() {
        return Math.max(30, this.height / 2 - 40);
    }

    private void recover() {
        List<Path> files = this.unfinished;
        AtomicInteger recovered = new AtomicInteger();
        Notices.show(Component.translatable("dailies.ui.recovery.working"));
        this.minecraft.setScreen(this.parent);
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
        this.minecraft.setScreen(new ConfirmScreen(
                yes -> {
                    if (yes) {
                        this.discard();
                    } else {
                        this.minecraft.setScreen(this);
                    }
                },
                Component.translatable("dailies.ui.recovery.discard_title"),
                Component.translatable("dailies.ui.recovery.discard_question"),
                Component.translatable("dailies.ui.recovery.discard_yes"),
                Component.translatable("dailies.ui.recovery.discard_no")));
    }

    private void discard() {
        List<Path> files = this.unfinished;
        this.minecraft.setScreen(this.parent);
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

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, this.textTop() - 18, Controls.PARCHMENT);
        this.words.renderCentered(graphics, this.width / 2, this.textTop(), 10, Controls.PARCHMENT_DIM);
    }

}
