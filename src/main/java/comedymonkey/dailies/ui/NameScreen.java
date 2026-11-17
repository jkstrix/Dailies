package comedymonkey.dailies.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

/**
 * Asks for a name: for a recording that is being finished (specification REC-5) and for one that is being renamed.
 * Whoever opens it says what the two buttons are called and what each does, including which screen comes next.
 */
final class NameScreen extends Screen {

    private static final int MAX_LENGTH = 80;

    private final Component explanation;
    private final Component acceptLabel;
    private final Component declineLabel;
    private final Consumer<String> accept;
    private final Runnable decline;
    private String name;
    private EditBox box;
    private boolean answered = false;

    NameScreen(Component title, Component explanation, String suggestion, Component acceptLabel, Component declineLabel, Consumer<String> accept, Runnable decline) {
        super(title);
        this.explanation = explanation;
        this.name = suggestion;
        this.acceptLabel = acceptLabel;
        this.declineLabel = declineLabel;
        this.accept = accept;
        this.decline = decline;
    }

    @Override
    protected void init() {
        int boxWidth = Math.min(240, this.width - 40);
        int left = (this.width - boxWidth) / 2;
        int top = this.height / 2 - 14;

        this.box = new EditBox(this.font, left, top, boxWidth, 20, this.title);
        this.box.setMaxLength(MAX_LENGTH);
        this.box.setValue(this.name);
        this.box.setResponder(value -> this.name = value);
        this.addRenderableWidget(this.box);

        int buttonWidth = (boxWidth - 4) / 2;
        this.addRenderableWidget(Button.builder(this.acceptLabel, button -> this.answer(true)).bounds(left, top + 28, buttonWidth, 20).build());
        this.addRenderableWidget(Button.builder(this.declineLabel, button -> this.answer(false)).bounds(left + boxWidth - buttonWidth, top + 28, buttonWidth, 20).build());
    }

    @Override
    protected void setInitialFocus() {
        this.setInitialFocus(this.box);
    }

    private void answer(boolean accepted) {
        if (this.answered) {
            return;
        }
        this.answered = true;
        Controls.guarded(() -> {
            if (accepted) {
                this.accept.accept(this.name.trim());
            } else {
                this.decline.run();
            }
        });
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && this.getFocused() == this.box) {
            this.answer(true);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        this.answer(false);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int top = this.height / 2 - 14;
        graphics.drawCenteredString(this.font, this.title, this.width / 2, top - 30, Controls.PARCHMENT);
        graphics.drawCenteredString(this.font, this.explanation, this.width / 2, top - 15, Controls.PARCHMENT_DIM);
    }

}
