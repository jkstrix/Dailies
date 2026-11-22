package comedymonkey.dailiescompat.supplementaries.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

import java.util.function.Consumer;

/**
 * A mod's screen as playback draws it: the mod's own screen inside, laid out when this one is (without telling other mods,
 * as Dailies lays out every screen it draws) and then given what the recorder's screen held that only exists once it is
 * laid out (a cursor, a button lit). Most of Supplementaries' screens can only be made through their own constructors,
 * which they keep to themselves, so they cannot be extended; this stands in front of one instead. It takes no input, and
 * the screen inside is never ticked, closed or told it was removed, so it never sends anything.
 */
final class Shown<T extends Screen> extends Screen {

    private final T inside;
    private final Consumer<T> laidOut;

    Shown(T inside, Consumer<T> laidOut) {
        super(inside.getTitle());
        this.inside = inside;
        this.laidOut = laidOut;
    }

    T inside() {
        return this.inside;
    }

    @Override
    protected void init() {
        Reflect.set(this.inside, "minecraft", this.minecraft);
        Reflect.set(this.inside, "font", this.font);
        this.inside.width = this.width;
        this.inside.height = this.height;
        // The screen's own layout, as Screen.init calls it, with no event for other mods to add their buttons by
        Reflect.call(this.inside, "init", new Class<?>[0]);
        this.laidOut.accept(this.inside);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.inside.renderWithTooltip(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The screen inside draws its own
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
    }

    @Override
    public void onClose() {
    }

    @Override
    public void removed() {
    }

}
