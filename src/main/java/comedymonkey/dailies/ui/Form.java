package comedymonkey.dailies.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A column of controls inside a panel. When there are more than fit, the column scrolls with the mouse wheel or by
 * dragging the bar at its right edge, and nothing is ever drawn outside the panel.
 */
final class Form {

    private static final int GAP = Kit.GAP;
    /** The room kept at the right for the scroll bar, there or not, so that rows do not change width when one appears */
    private static final int BAR = Kit.BAR + 2;
    private static final int SIDE = 3;

    private final List<Gadget> rows = new ArrayList<>();
    /** What the rows were built for; the owner compares it with what they should show now */
    String builtFor = "";
    int x;
    int y;
    int width;
    int height;
    private int scroll = 0;
    private int content = 0;
    /** Whether the names of its pairs stand in one column as wide as the longest needs, rather than a fixed share */
    private boolean fitNames = false;
    private boolean barHeld = false;
    /** How far below the top of the thumb the mouse took hold of it */
    private int barGrab = 0;
    private final Gadget bar = new Gadget() {
        @Override
        void draw(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        }

        @Override
        void drag(double mouseX, double mouseY) {
            if (Form.this.height > 0 && Form.this.content > Form.this.height) {
                Form.this.scroll = Kit.scrollFor((int) mouseY - Form.this.barGrab, Form.this.y, Form.this.height, Form.this.content);
            }
        }

        @Override
        void release(double mouseX, double mouseY) {
            Form.this.barHeld = false;
        }
    };

    /**
     * Lines the names of every name-and-control pair up in one column, as wide as the longest name needs and no wider
     * than half the form, so that in a narrow panel names are not cut short while their controls have room to spare.
     */
    Form fitNames() {
        this.fitNames = true;
        return this;
    }

    Form add(Gadget row) {
        this.rows.add(row);
        return this;
    }

    /**
     * Takes every row away. A box of this form being typed into is left first, its value handed over, and the ring
     * leaves a control of this form. A box or a ring in another form is left alone: that form is not changing.
     */
    void clear() {
        Entry typing = Entry.focused;
        if (typing != null && this.holds(typing)) {
            Entry.blur();
        }
        Gadget ringed = Gadget.focus;
        if (ringed != null && this.holds(ringed)) {
            Gadget.focus = null;
        }
        this.rows.clear();
    }

    /** Whether a control is one of this form's rows or inside one, whether or not it can be seen or reached now */
    boolean holds(Gadget control) {
        for (Gadget row : this.rows) {
            if (row.holds(control)) {
                return true;
            }
        }
        return false;
    }

    boolean isEmpty() {
        return this.rows.isEmpty();
    }

    List<Gadget> rows() {
        return this.rows;
    }

    /** The height all the rows need */
    int contentHeight() {
        return this.content;
    }

    /** Refused values and their explanations are forgotten: what the form shows has changed underneath */
    void forget() {
        this.rows.forEach(Gadget::forget);
    }

    /** The controls the Tab key goes through, top to bottom */
    void collect(List<Gadget> order) {
        this.rows.forEach(row -> row.collect(order));
    }

    /** Scrolls so that a control is on show, for when the keys have moved on to it */
    void reveal(Gadget control) {
        if (control.y < this.y + GAP) {
            this.scroll -= this.y + GAP - control.y;
        } else if (control.y + control.height > this.y + this.height - GAP) {
            this.scroll += control.y + control.height - (this.y + this.height - GAP);
        }
        this.scroll = Math.max(0, Math.min(this.scroll, Math.max(0, this.content - this.height)));
    }

    void scrollToTop() {
        this.scroll = 0;
    }

    /** How high all the rows would be at this width, without moving anything or losing how far it is scrolled */
    int measure(Font font, int forWidth) {
        int total = GAP;
        for (int height : this.heights(font, Math.max(10, forWidth - BAR - 2 * SIDE))) {
            total += height + (height > 0 ? GAP : 0);
        }
        return total + GAP;
    }

    private int[] heights(Font font, int inner) {
        if (this.fitNames) {
            List<Gadget.Pair> pairs = new ArrayList<>();
            this.rows.forEach(row -> row.pairs(pairs));
            int widest = 0;
            for (Gadget.Pair pair : pairs) {
                widest = Math.max(widest, pair.nameWidth(font) + 6);
            }
            int column = Math.max(inner * 30 / 100, Math.min(inner / 2, widest));
            pairs.forEach(pair -> pair.column = column);
        }
        int[] heights = new int[this.rows.size()];
        for (int i = 0; i < heights.length; i++) {
            heights[i] = Math.max(0, this.rows.get(i).heightFor(font, inner));
        }
        return heights;
    }

    void place(Font font, int newX, int newY, int newWidth, int newHeight) {
        this.x = newX;
        this.y = newY;
        this.width = Math.max(0, newWidth);
        this.height = Math.max(0, newHeight);
        int inner = Math.max(10, this.width - BAR - 2 * SIDE);
        int[] heights = this.heights(font, inner);
        int total = GAP;
        for (int height : heights) {
            total += height + (height > 0 ? GAP : 0);
        }
        this.content = total + GAP;
        this.scroll = Math.max(0, Math.min(this.scroll, this.content - this.height));
        int at = this.y + GAP - this.scroll;
        for (int i = 0; i < heights.length; i++) {
            Gadget row = this.rows.get(i);
            row.clip(this.y, this.y + this.height);
            row.place(font, this.x + SIDE, at, inner, heights[i]);
            at += heights[i] + (heights[i] > 0 ? GAP : 0);
        }
    }

    boolean over(double mouseX, double mouseY) {
        return mouseX >= this.x && mouseX < this.x + this.width && mouseY >= this.y && mouseY < this.y + this.height;
    }

    private boolean scrolls() {
        return this.content > this.height;
    }

    private boolean overBar(double mouseX, double mouseY) {
        return this.scrolls() && this.over(mouseX, mouseY) && mouseX >= this.x + this.width - BAR;
    }

    void draw(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        if (this.width <= 0 || this.height <= 0) {
            return;
        }
        boolean inside = this.over(mouseX, mouseY) && !this.barHeld;
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
        if (this.scrolls()) {
            Kit.scrollBar(graphics, this.x + this.width - Kit.BAR - 1, this.y, this.height, this.content, this.scroll,
                    this.barHeld || this.overBar(mouseX, mouseY));
            // A shade at an edge says there is more that way
            if (this.scroll > 0) {
                graphics.fillGradient(this.x, this.y, this.x + this.width - BAR, this.y + 4, 0x60000000, 0x00000000);
            }
            if (this.scroll < this.content - this.height) {
                graphics.fillGradient(this.x, this.y + this.height - 4, this.x + this.width - BAR, this.y + this.height, 0x00000000, 0x60000000);
            }
        }
    }

    @Nullable Gadget press(double mouseX, double mouseY, int button) {
        if (!this.over(mouseX, mouseY)) {
            return null;
        }
        if (this.overBar(mouseX, mouseY) && button == 0) {
            // Taking hold of the thumb drags it from where it was taken; a click beside it brings its middle there
            int[] thumb = Kit.thumb(this.y, this.height, this.content, this.scroll);
            boolean onThumb = mouseY >= thumb[0] && mouseY < thumb[0] + thumb[1];
            this.barGrab = onThumb ? (int) mouseY - thumb[0] : thumb[1] / 2;
            this.barHeld = true;
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
        this.scroll = Math.max(0, Math.min(this.scroll - (int) Math.round(amount * 24.0), Math.max(0, this.content - this.height)));
        return true;
    }

    @Nullable Component tipAt(double mouseX, double mouseY) {
        if (!this.over(mouseX, mouseY) || this.barHeld) {
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
