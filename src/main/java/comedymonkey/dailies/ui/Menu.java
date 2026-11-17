package comedymonkey.dailies.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A short list of things to choose from that opens where the mouse is and goes away after a click: the kinds of track
 * to add, the scenes, what can be done with a track. One at a time; it is drawn over everything else.
 */
final class Menu {

    /** One line of a menu */
    record Item(Component label, Runnable action, boolean enabled, boolean marked) {
        Item(Component label, Runnable action) {
            this(label, action, true, false);
        }
    }

    private static final int LINE = 12;

    /** The menu that is open, or null. Client thread */
    static @Nullable Menu open = null;

    private final List<Item> items;
    private final int anchorX;
    private final int anchorY;
    private int x;
    private int y;
    private int width;
    private int height;
    private int scroll = 0;

    private Menu(List<Item> items, int anchorX, int anchorY) {
        this.items = items;
        this.anchorX = anchorX;
        this.anchorY = anchorY;
    }

    /** Opens a menu at a point of the screen, usually where the mouse is */
    static void show(List<Item> items, double atX, double atY) {
        if (!items.isEmpty()) {
            open = new Menu(new ArrayList<>(items), (int) atX, (int) atY);
        }
    }

    static void close() {
        open = null;
    }

    private void fit(Font font, int screenWidth, int screenHeight) {
        int widest = 40;
        for (Item item : this.items) {
            widest = Math.max(widest, font.width(item.label()) + 16);
        }
        this.width = Math.min(widest, Math.max(40, screenWidth - 4));
        this.height = Math.min(this.items.size() * LINE + 4, Math.max(LINE + 4, screenHeight - 4));
        this.x = Math.max(2, Math.min(this.anchorX, screenWidth - this.width - 2));
        // Below the mouse where there is room, else above it
        this.y = this.anchorY + this.height + 2 <= screenHeight ? this.anchorY : Math.max(2, this.anchorY - this.height);
        this.scroll = Math.max(0, Math.min(this.scroll, this.items.size() * LINE + 4 - this.height));
    }

    void draw(GuiGraphics graphics, Font font, int mouseX, int mouseY, int screenWidth, int screenHeight) {
        this.fit(font, screenWidth, screenHeight);
        graphics.pose().pushPose();
        // Above the panels and whatever they have drawn
        graphics.pose().translate(0.0F, 0.0F, 300.0F);
        try {
            this.drawLines(graphics, font, mouseX, mouseY);
        } finally {
            graphics.pose().popPose();
        }
    }

    private void drawLines(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        graphics.fill(this.x, this.y, this.x + this.width, this.y + this.height, Controls.WALNUT);
        graphics.renderOutline(this.x, this.y, this.width, this.height, Controls.BRASS_BRIGHT);
        graphics.enableScissor(this.x + 1, this.y + 1, this.x + this.width - 1, this.y + this.height - 1);
        try {
            int line = this.y + 2 - this.scroll;
            for (Item item : this.items) {
                boolean hovered = item.enabled() && mouseX >= this.x && mouseX < this.x + this.width && mouseY >= line && mouseY < line + LINE
                        && mouseY >= this.y && mouseY < this.y + this.height;
                if (hovered) {
                    graphics.fill(this.x + 1, line, this.x + this.width - 1, line + LINE, 0xFF5A4520);
                }
                if (item.marked()) {
                    graphics.fill(this.x + 4, line + 4, this.x + 8, line + 8, Controls.BRASS_BRIGHT);
                }
                Kit.text(graphics, font, Kit.clip(font, item.label().getString(), this.width - 14), this.x + 11, line + 2,
                        item.enabled() ? Controls.PARCHMENT : Controls.PARCHMENT_DIM);
                line += LINE;
            }
        } finally {
            graphics.disableScissor();
        }
    }

    boolean over(double mouseX, double mouseY) {
        return mouseX >= this.x && mouseX < this.x + this.width && mouseY >= this.y && mouseY < this.y + this.height;
    }

    /** A click while the menu is open: on a line it does that line, and anywhere it closes the menu */
    void press(double mouseX, double mouseY) {
        close();
        if (!this.over(mouseX, mouseY)) {
            return;
        }
        int index = (int) Math.floor((mouseY - this.y - 2 + this.scroll) / LINE);
        if (index >= 0 && index < this.items.size() && this.items.get(index).enabled()) {
            Kit.click();
            EditorState.guard(this.items.get(index).action());
        }
    }

    void wheel(double amount) {
        this.scroll = Math.max(0, Math.min(this.scroll - (int) Math.round(amount * LINE), Math.max(0, this.items.size() * LINE + 4 - this.height)));
    }

}
