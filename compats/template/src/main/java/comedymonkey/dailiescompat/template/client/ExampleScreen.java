package comedymonkey.dailiescompat.template.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The example screen: a tally the player counts up with a button. Only the player's game knows it, so Dailies cannot
 * record it by itself; the compat records the count and builds the screen again from it in playback, read only.
 */
public final class ExampleScreen extends Screen {

    public static final int WIDTH = 160;
    public static final int HEIGHT = 80;

    private int count;
    /** Built by Dailies for playback: draws as the player saw it and does nothing when used */
    private final boolean readOnly;

    public ExampleScreen(int count, boolean readOnly) {
        super(Component.translatable("screen.dailies_template.example"));
        this.count = count;
        this.readOnly = readOnly;
    }

    public int count() {
        return this.count;
    }

    /** The window's top left corner, which the pointer is recorded from */
    public int left() {
        return (this.width - WIDTH) / 2;
    }

    public int top() {
        return (this.height - HEIGHT) / 2;
    }

    @Override
    protected void init() {
        this.addRenderableWidget(Button.builder(Component.literal("+1"), button -> {
            if (!this.readOnly) {
                this.count++;
            }
        }).bounds(this.left() + WIDTH / 2 - 20, this.top() + HEIGHT - 28, 40, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.fill(this.left(), this.top(), this.left() + WIDTH, this.top() + HEIGHT - 32, 0xC0101010);
        graphics.centeredText(this.font, this.title, this.width / 2, this.top() + 8, 0xFFFFFFFF);
        graphics.centeredText(this.font, Component.translatable("screen.dailies_template.count", this.count), this.width / 2, this.top() + 26, 0xFFFFE070);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

}
