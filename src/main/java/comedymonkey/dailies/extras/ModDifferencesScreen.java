package comedymonkey.dailies.extras;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Lists how the mods installed now differ from those the open recording was made with (specification PLAY-5): the
 * ones missing here, the ones extra here and the ones in another version. The recording keeps playing behind it.
 */
final class ModDifferencesScreen extends Screen {

    private static final int BRASS = 0xFF9A7A34;
    private static final int BRASS_BRIGHT = 0xFFD4B25A;
    private static final int WALNUT_GLASS = 0xD02B1D0E;
    private static final int PARCHMENT = 0xFFEFE6D6;
    private static final int PARCHMENT_DIM = 0xFFA89878;
    private static final int LINE_HEIGHT = 11;

    /** One line of the list: a heading in bright brass, or a mod in parchment */
    private record Line(Component words, int colour, int indent) {
    }

    private final List<Line> lines = new ArrayList<>();
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int listTop;
    private int listBottom;
    private int scroll = 0;

    ModDifferencesScreen(@Nullable ModDifferences.Differences differences) {
        super(Component.translatable("dailies.extras.mods.title"));
        if (differences == null) {
            this.lines.add(new Line(Component.translatable("dailies.extras.mods.same"), PARCHMENT, 0));
            return;
        }
        this.section("dailies.extras.mods.missing", differences.missing().stream()
            .map(mod -> Component.translatable("dailies.extras.mods.entry", mod.id(), mod.version())).toList());
        this.section("dailies.extras.mods.changed", differences.changed().stream()
            .map(mod -> Component.translatable("dailies.extras.mods.entry_changed", mod.id(), mod.recorded(), mod.installed())).toList());
        this.section("dailies.extras.mods.extra", differences.extra().stream()
            .map(mod -> Component.translatable("dailies.extras.mods.entry", mod.id(), mod.version())).toList());
    }

    private void section(String heading, List<? extends Component> entries) {
        if (entries.isEmpty()) {
            return;
        }
        if (!this.lines.isEmpty()) {
            this.lines.add(new Line(Component.empty(), PARCHMENT, 0));
        }
        this.lines.add(new Line(Component.translatable(heading, entries.size()), BRASS_BRIGHT, 0));
        for (Component entry : entries) {
            this.lines.add(new Line(entry, PARCHMENT, 8));
        }
    }

    @Override
    protected void init() {
        this.panelWidth = Math.min(360, this.width - 24);
        this.panelLeft = (this.width - this.panelWidth) / 2;
        this.panelTop = 16;
        int panelBottom = this.height - 16;
        this.listTop = this.panelTop + 34;
        this.listBottom = panelBottom - 30;
        this.scroll = Math.max(0, Math.min(this.scroll, this.mostScroll()));
        this.addRenderableWidget(Button.builder(Component.translatable("dailies.extras.mods.done"), button -> this.onClose())
            .bounds(this.width / 2 - 50, panelBottom - 25, 100, 20).build());
    }

    private int mostScroll() {
        return Math.max(0, this.lines.size() * LINE_HEIGHT - (this.listBottom - this.listTop));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** No blur and no dimming of the world: only the panel the list sits on */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int right = this.panelLeft + this.panelWidth;
        int bottom = this.height - 16;
        graphics.fill(this.panelLeft - 1, this.panelTop - 1, right + 1, bottom + 1, BRASS);
        graphics.fill(this.panelLeft, this.panelTop, right, bottom, WALNUT_GLASS);
        graphics.fill(this.panelLeft + 8, this.listTop - 5, right - 8, this.listTop - 4, BRASS);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, this.panelTop + 7, BRASS_BRIGHT);
        graphics.drawCenteredString(this.font, Component.translatable("dailies.extras.mods.explanation"), this.width / 2, this.panelTop + 18, PARCHMENT_DIM);

        int left = this.panelLeft + 10;
        int right = this.panelLeft + this.panelWidth - 10;
        graphics.enableScissor(left, this.listTop, right, this.listBottom);
        int y = this.listTop - this.scroll;
        for (Line line : this.lines) {
            if (y + LINE_HEIGHT > this.listTop && y < this.listBottom) {
                graphics.drawString(this.font, line.words(), left + line.indent(), y, line.colour(), false);
            }
            y += LINE_HEIGHT;
        }
        graphics.disableScissor();

        int most = this.mostScroll();
        if (most > 0) {
            // A brass thumb at the right edge shows how far down the list the view is
            int track = this.listBottom - this.listTop;
            int thumb = Math.max(12, track * track / (track + most));
            int thumbTop = this.listTop + (track - thumb) * this.scroll / most;
            graphics.fill(right + 3, thumbTop, right + 5, thumbTop + thumb, BRASS);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        this.scroll = Math.max(0, Math.min(this.mostScroll(), this.scroll - (int) Math.round(scrollY * LINE_HEIGHT * 3)));
        return true;
    }

}
