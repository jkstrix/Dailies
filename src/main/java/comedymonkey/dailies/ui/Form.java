package comedymonkey.dailies.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A column of controls inside a panel. When there are more than fit, the column scrolls with the mouse wheel or by
 * dragging the bar at its right edge, and nothing is ever drawn outside the panel.
 */
final class Form {

    private static final int GAP = 2;
    private static final int BAR = 4;

    private final List<Gadget> rows = new ArrayList<>();
    /** What the rows were built for; the owner compares it with what they should show now */
    String builtFor = "";
    int x;
    int y;
    int width;
    int height;
    private int scroll = 0;
    private int content = 0;
    private final Gadget bar = new Gadget() {
        @Override
        void draw(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        }

        @Override
        void drag(double mouseX, double mouseY) {
            if (Form.this.height > 0 && Form.this.content > Form.this.height) {
                double share = (mouseY - Form.this.y) / Form.this.height;
                Form.this.scroll = (int) Math.round(share * Form.this.content - Form.this.height / 2.0);
            }
        }
    };

    Form add(Gadget row) {
        this.rows.add(row);
        return this;
    }

    /** Takes every row away. A box being typed into is left first, its value handed over */
    void clear() {
        if (Entry.focused != null && this.holdsFocus()) {
            Entry.blur();
        }
        this.rows.clear();
    }

    private boolean holdsFocus() {
        Entry typing = Entry.focused;
        // Boxes in a bar are clipped by nothing; every other box sits in a form, and only one form is typed in at a time
        return typing != null && typing.clipTop != Integer.MIN_VALUE;
    }

    boolean isEmpty() {
        return this.rows.isEmpty();
    }

    /** The height all the rows need */
    int contentHeight() {
        return this.content;
    }

    /** Refused values and their explanations are forgotten: what the form shows has changed underneath */
    void forget() {
        this.rows.forEach(Gadget::forget);
    }

    void place(Font font, int newX, int newY, int newWidth, int newHeight) {
        this.x = newX;
        this.y = newY;
        this.width = Math.max(0, newWidth);
        this.height = Math.max(0, newHeight);
        int inner = Math.max(10, this.width - BAR - 4);
        int[] heights = new int[this.rows.size()];
        int total = GAP;
        for (int i = 0; i < heights.length; i++) {
            heights[i] = Math.max(0, this.rows.get(i).heightFor(font, inner));
            total += heights[i] + (heights[i] > 0 ? GAP : 0);
        }
        this.content = total;
        this.scroll = Math.max(0, Math.min(this.scroll, this.content - this.height));
        int at = this.y + GAP - this.scroll;
        for (int i = 0; i < heights.length; i++) {
            Gadget row = this.rows.get(i);
            row.clip(this.y, this.y + this.height);
            row.place(font, this.x + 2, at, inner, heights[i]);
            at += heights[i] + (heights[i] > 0 ? GAP : 0);
        }
    }

    boolean over(double mouseX, double mouseY) {
        return mouseX >= this.x && mouseX < this.x + this.width && mouseY >= this.y && mouseY < this.y + this.height;
    }

    void draw(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        if (this.width <= 0 || this.height <= 0) {
            return;
        }
        boolean inside = this.over(mouseX, mouseY);
        int pointerX = inside ? mouseX : Integer.MIN_VALUE / 2;
        int pointerY = inside ? mouseY : Integer.MIN_VALUE / 2;
        graphics.enableScissor(this.x, this.y, this.x + this.width, this.y + this.height);
        try {
            for (Gadget row : this.rows) {
                if (row.height > 0 && row.y + row.height > this.y && row.y < this.y + this.height) {
                    row.draw(graphics, font, pointerX, pointerY);
                }
            }
        } finally {
            graphics.disableScissor();
        }
        if (this.content > this.height) {
            int left = this.x + this.width - BAR + 1;
            graphics.fill(left, this.y, left + BAR - 2, this.y + this.height, 0x60000000);
            int thumb = Math.max(8, this.height * this.height / this.content);
            int travel = this.height - thumb;
            int top = this.y + (int) ((long) travel * this.scroll / Math.max(1, this.content - this.height));
            graphics.fill(left, top, left + BAR - 2, top + thumb, Controls.BRASS_BRIGHT);
        }
    }

    @Nullable Gadget press(double mouseX, double mouseY, int button) {
        if (!this.over(mouseX, mouseY)) {
            return null;
        }
        if (this.content > this.height && mouseX >= this.x + this.width - BAR - 1 && button == 0) {
            this.bar.drag(mouseX, mouseY);
            return this.bar;
        }
        for (Gadget row : this.rows) {
            if (row.height > 0 && row.over(mouseX, mouseY)) {
                Gadget hit = row.press(mouseX, mouseY, button);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    boolean wheel(double mouseX, double mouseY, double amount) {
        if (!this.over(mouseX, mouseY)) {
            return false;
        }
        this.scroll = Math.max(0, Math.min(this.scroll - (int) Math.round(amount * 18.0), Math.max(0, this.content - this.height)));
        return true;
    }

    @Nullable Component tipAt(double mouseX, double mouseY) {
        if (!this.over(mouseX, mouseY)) {
            return null;
        }
        for (Gadget row : this.rows) {
            if (row.height > 0) {
                Component words = row.tipAt(mouseX, mouseY);
                if (words != null) {
                    return words;
                }
            }
        }
        return null;
    }

}
