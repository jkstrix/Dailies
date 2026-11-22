package comedymonkey.dailies.ui;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * Asks a question with two answers before something that cannot be taken back: deleting a recording, throwing one
 * away. Whoever opens it says what the two buttons are called and is told which was pressed; Escape is the second.
 */
final class AskScreen extends KitScreen {

    private final BooleanConsumer answer;
    private final Component question;
    private final Component yesLabel;
    private final Component noLabel;
    private Gadget.Push yes;
    private Gadget.Push no;
    private boolean answered = false;

    AskScreen(BooleanConsumer answer, Component title, Component question, Component yesLabel, Component noLabel) {
        super(title, null);
        this.answer = answer;
        this.question = question;
        this.yesLabel = yesLabel;
        this.noLabel = noLabel;
    }

    @Override
    void build() {
        this.yes = new Gadget.Push(this.yesLabel, () -> this.answer(true));
        this.no = new Gadget.Push(this.noLabel, () -> this.answer(false));
        this.fixed.add(this.yes);
        this.fixed.add(this.no);
        // The keys start on the answer that changes nothing
        Gadget.focus = this.no;
    }

    @Override
    void arrange(Font font) {
        this.centre(300, 0);
        this.centre(300, this.headingHeight(font, this.question) + 6 + Kit.ROW + 2 + 8);
        this.foot(font, this.yes, this.no);
    }

    @Override
    void paint(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        this.heading(graphics, font, this.question);
    }

    void answer(boolean said) {
        if (this.answered) {
            return;
        }
        this.answered = true;
        Controls.guarded(() -> this.answer.accept(said));
    }

    @Override
    public void onClose() {
        this.answer(false);
    }

}
