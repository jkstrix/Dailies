package comedymonkey.dailies.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * Asks for a name: for a recording that is being finished (specification REC-5) and for one that is being renamed.
 * Whoever opens it says what the two buttons are called and what each does, including which screen comes next.
 */
final class NameScreen extends KitScreen {

    private static final int MAX_LENGTH = 80;

    private final Component explanation;
    private final Component acceptLabel;
    private final Component declineLabel;
    private final Consumer<String> accept;
    private final Runnable decline;
    private String name;
    private Entry box;
    private Gadget.Push acceptButton;
    private Gadget.Push declineButton;
    private boolean answered = false;

    NameScreen(Component title, Component explanation, String suggestion, Component acceptLabel, Component declineLabel, Consumer<String> accept, Runnable decline) {
        super(title, null);
        this.explanation = explanation;
        this.name = suggestion;
        this.acceptLabel = acceptLabel;
        this.declineLabel = declineLabel;
        this.accept = accept;
        this.decline = decline;
    }

    @Override
    void build() {
        // The name is kept as it is typed, so that a resized window does not lose it and Enter always has the latest
        this.box = new Entry(() -> this.name, typed -> {
            this.name = typed;
            return null;
        }).live(typed -> {
            this.name = typed;
            return null;
        }).max(MAX_LENGTH).onEnter(() -> this.answer(true));
        this.acceptButton = new Gadget.Push(this.acceptLabel, () -> this.answer(true));
        this.declineButton = new Gadget.Push(this.declineLabel, () -> this.answer(false));
        this.fixed.add(this.box);
        this.fixed.add(this.acceptButton);
        this.fixed.add(this.declineButton);
        this.box.focus();
    }

    @Override
    void arrange(Font font) {
        this.centre(280, 0);
        int head = this.headingHeight(font, this.explanation);
        this.centre(280, head + 4 + Kit.ROW + 10 + Kit.ROW + 2 + 8);
        this.box.clip(Integer.MIN_VALUE, Integer.MAX_VALUE);
        this.box.place(font, this.left + 8, this.top + head + 4, this.right - this.left - 16, Kit.ROW);
        this.foot(font, this.acceptButton, this.declineButton);
    }

    @Override
    void paint(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        this.heading(graphics, font, this.explanation);
    }

    private void answer(boolean accepted) {
        if (this.answered) {
            return;
        }
        this.answered = true;
        Entry.drop();
        Controls.guarded(() -> {
            if (accepted) {
                this.accept.accept(this.name.trim());
            } else {
                this.decline.run();
            }
        });
    }

    @Override
    public void onClose() {
        this.answer(false);
    }

}
