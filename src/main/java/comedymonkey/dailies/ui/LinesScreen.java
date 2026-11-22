package comedymonkey.dailies.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * A plate with a title, a line of explanation, a list of lines under headings that scrolls when it is long, and one
 * button to close it. For parts of the mod outside this package that have something to list and want it to look like
 * every other Dailies screen.
 */
public class LinesScreen extends KitScreen {

    private final Component explanation;
    private final Component doneLabel;
    private final boolean dims;
    private final Form list = new Form();
    private Gadget.Push done;

    /**
     * @param dims whether the world behind is blurred and dimmed; false leaves it as it is, for a list shown over a
     *             recording that keeps playing
     */
    protected LinesScreen(Component title, Component explanation, Component doneLabel, @Nullable Screen parent, boolean dims) {
        super(title, parent);
        this.explanation = explanation;
        this.doneLabel = doneLabel;
        this.dims = dims;
    }

    /** Starts a group of lines under a heading */
    protected final void section(Component heading) {
        this.list.add(new Gadget.Heading(heading));
    }

    /** One line of the list. Cut short with dots when it is too long, with all of it shown on hover */
    protected final void entry(Component words) {
        this.list.add(new Gadget.Words(() -> words, Controls.PARCHMENT));
    }

    /** Words that are not part of a group and may run over several lines */
    protected final void words(Component words) {
        this.list.add(new Gadget.Note(() -> words, Controls.PARCHMENT));
    }

    @Override
    boolean dimsWorld() {
        return this.dims;
    }

    @Override
    public boolean isPauseScreen() {
        return this.dims;
    }

    @Override
    void build() {
        this.done = new Gadget.Push(this.doneLabel, this::onClose);
        this.fixed.add(this.done);
        this.forms.add(this.list);
    }

    @Override
    void arrange(Font font) {
        this.centre(380, 0);
        int head = this.headingHeight(font, this.explanation);
        int foot = Kit.ROW + 2 + 8 + 6;
        this.list.place(font, 0, 0, this.right - this.left - 10, 10_000);
        this.centre(380, head + this.list.contentHeight() + foot);
        this.list.place(font, this.left + 5, this.top + head, this.right - this.left - 10, this.bottom - foot - this.top - head);
        this.foot(font, this.done);
    }

    @Override
    void paint(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        this.heading(graphics, font, this.explanation);
    }

}
