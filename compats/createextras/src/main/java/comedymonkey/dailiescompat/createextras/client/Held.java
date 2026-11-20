package comedymonkey.dailiescompat.createextras.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * A screen of the compat's that holds a mod's own screen and only draws it, for a mod screen built in playback that cannot
 * be made to send nothing by extending it (its constructor is its own, or it sends as it closes): the mod's screen is laid
 * out and drawn, but never ticked, handed a key or a click, or closed, so whatever it would send as it closes is never
 * sent.
 */
final class Held extends Screen {

    private final Screen inner;

    Held(Screen inner) {
        super(Component.empty());
        this.inner = inner;
    }

    Screen inner() {
        return this.inner;
    }

    @Override
    protected void init() {
        this.inner.init(this.minecraft, this.width, this.height);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.inner.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void tick() {
    }

    @Override
    public void removed() {
    }

    @Override
    public void onClose() {
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

}
