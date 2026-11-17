package comedymonkey.dailies.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One control of the editor: a button, a checkbox, a slider, a line of words. The editor draws its own controls
 * because they live in panels that scroll, and each reads what it shows from the recording's editor state on every
 * frame, so undo, redo and the MCP tools are reflected at once. The kinds of control are the classes nested here;
 * text boxes are {@link Entry}.
 */
abstract class Gadget {

    int x;
    int y;
    int width;
    int height = Kit.ROW;
    /** The part of the screen this control can be seen and clicked in: the panel it scrolls inside */
    int clipTop = Integer.MIN_VALUE;
    int clipBottom = Integer.MAX_VALUE;
    @Nullable Component tip;
    BooleanSupplier usable = () -> true;
    /** The width it takes in a line of controls; 0 to share what is left */
    int fixedWidth = 0;

    Gadget tip(@Nullable Component words) {
        this.tip = words;
        return this;
    }

    Gadget when(BooleanSupplier condition) {
        this.usable = condition;
        return this;
    }

    Gadget wide(int pixels) {
        this.fixedWidth = pixels;
        return this;
    }

    /** The width it would like in a bar of controls set out side by side */
    int wantedWidth(Font font) {
        return this.fixedWidth > 0 ? this.fixedWidth : 40;
    }

    /** The height it needs when given this width */
    int heightFor(Font font, int forWidth) {
        return Kit.ROW;
    }

    void clip(int top, int bottom) {
        this.clipTop = top;
        this.clipBottom = bottom;
    }

    void place(Font font, int newX, int newY, int newWidth, int newHeight) {
        this.x = newX;
        this.y = newY;
        this.width = newWidth;
        this.height = newHeight;
    }

    boolean over(double mouseX, double mouseY) {
        return mouseX >= this.x && mouseX < this.x + this.width && mouseY >= this.y && mouseY < this.y + this.height
                && mouseY >= this.clipTop && mouseY < this.clipBottom;
    }

    abstract void draw(GuiGraphics graphics, Font font, int mouseX, int mouseY);

    /**
     * A mouse button went down on this control.
     *
     * @return the control that now follows the mouse until the button comes up, or null when nothing was hit
     */
    @Nullable Gadget press(double mouseX, double mouseY, int button) {
        return null;
    }

    void drag(double mouseX, double mouseY) {
    }

    void release(double mouseX, double mouseY) {
    }

    /** The explanation to show for the point under the mouse, or null */
    @Nullable Component tipAt(double mouseX, double mouseY) {
        return this.over(mouseX, mouseY) ? this.tip : null;
    }

    /** Forgets a refused value and its explanation, where the control keeps one */
    void forget() {
    }

    /** An explanation that also gives the name of the control in full, for when there was no room to draw all of it */
    static Component whole(Component label, @Nullable Component explanation, boolean cut) {
        if (explanation == null) {
            return label;
        }
        return cut ? Component.empty().append(label).append(": ").append(explanation) : explanation;
    }

    // ------------------------------------------------------------------ Words

    /** A heading over a group of controls */
    static final class Heading extends Gadget {
        private final Supplier<Component> words;

        Heading(Component words) {
            this(() -> words);
        }

        Heading(Supplier<Component> words) {
            this.words = words;
        }

        @Override
        int heightFor(Font font, int forWidth) {
            return 14;
        }

        @Override
        int wantedWidth(Font font) {
            return font.width(this.words.get()) + 6;
        }

        @Override
        void draw(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
            Kit.text(graphics, font, Kit.clip(font, this.words.get().getString(), this.width), this.x, this.y + 3, Controls.BRASS_BRIGHT);
            graphics.fill(this.x, this.y + this.height - 1, this.x + this.width, this.y + this.height, Controls.BRASS);
        }
    }

    /** One line of plain words, cut short when it does not fit; the whole of it is in its explanation */
    static final class Words extends Gadget {
        private final Supplier<Component> words;
        private final int colour;

        Words(Supplier<Component> words, int colour) {
            this.words = words;
            this.colour = colour;
        }

        @Override
        int wantedWidth(Font font) {
            return this.fixedWidth > 0 ? this.fixedWidth : font.width(this.words.get()) + 4;
        }

        @Override
        void draw(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
            Kit.text(graphics, font, Kit.clip(font, this.words.get().getString(), this.width), this.x + 1, this.y + (this.height - 8) / 2, this.colour);
        }

        @Override
        @Nullable Component tipAt(double mouseX, double mouseY) {
            if (!this.over(mouseX, mouseY)) {
                return null;
            }
            return this.tip != null ? this.tip : this.words.get();
        }
    }

    /** Words over as many lines as they need. With an action, a click on them does it */
    static final class Note extends Gadget {
        private final Supplier<Component> words;
        private final int colour;
        private @Nullable Runnable action;

        Note(Component words) {
            this(() -> words, Controls.PARCHMENT_DIM);
        }

        Note(Supplier<Component> words, int colour) {
            this.words = words;
            this.colour = colour;
        }

        Note action(Runnable onClick) {
            this.action = onClick;
            return this;
        }

        @Override
        int heightFor(Font font, int forWidth) {
            Component now = this.words.get();
            if (now.getString().isEmpty()) {
                return 0;
            }
            return font.split(now, Math.max(20, forWidth - 2)).size() * 9 + 2;
        }

        @Override
        void draw(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
            Component now = this.words.get();
            if (now.getString().isEmpty()) {
                return;
            }
            int line = this.y + 1;
            boolean bright = this.action != null && this.over(mouseX, mouseY);
            for (FormattedCharSequence part : font.split(now, Math.max(20, this.width - 2))) {
                graphics.drawString(font, part, this.x + 1, line, bright ? Controls.PARCHMENT : this.colour, false);
                line += 9;
            }
        }

        @Override
        @Nullable Gadget press(double mouseX, double mouseY, int button) {
            if (this.action == null || button != 0 || !this.over(mouseX, mouseY)) {
                return null;
            }
            Kit.click();
            EditorState.guard(this.action);
            return this;
        }
    }

    // ------------------------------------------------------------------ Buttons

    /** A button with words or a small picture on it. It can be shown switched on */
    static final class Push extends Gadget {
        private final Supplier<Component> label;
        private final Runnable action;
        private @Nullable Supplier<Kit.Icon> icon;
        private BooleanSupplier lit = () -> false;
        private @Nullable Supplier<Component> liveTip;

        Push(Component label, Runnable action) {
            this(() -> label, action);
        }

        Push(Supplier<Component> label, Runnable action) {
            this.label = label;
            this.action = action;
        }

        /** A button with a picture; the words are only read out as its explanation when it has no other */
        static Push icon(Kit.Icon picture, Component explanation, Runnable action) {
            return icon(() -> picture, explanation, action);
        }

        static Push icon(Supplier<Kit.Icon> picture, Component explanation, Runnable action) {
            Push push = new Push(explanation, action);
            push.icon = picture;
            push.tip = explanation;
            push.fixedWidth = 16;
            return push;
        }

        Push lit(BooleanSupplier on) {
            this.lit = on;
            return this;
        }

        Push liveTip(Supplier<Component> words) {
            this.liveTip = words;
            return this;
        }

        @Override
        int wantedWidth(Font font) {
            return this.fixedWidth > 0 ? this.fixedWidth : font.width(this.label.get()) + 10;
        }

        @Override
        void draw(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
            boolean enabled = this.usable.getAsBoolean();
            boolean hovered = this.over(mouseX, mouseY);
            boolean on = this.lit.getAsBoolean();
            Kit.face(graphics, this.x, this.y, this.width, this.height, hovered, on, enabled);
            int colour = !enabled ? Controls.PARCHMENT_DIM : Controls.PARCHMENT;
            if (this.icon != null) {
                Kit.icon(graphics, this.icon.get(), this.x + this.width / 2, this.y + this.height / 2, colour);
            } else {
                Kit.centred(graphics, font, this.label.get().getString(), this.x, this.y + (this.height - 8) / 2, this.width, colour);
            }
        }

        @Override
        @Nullable Gadget press(double mouseX, double mouseY, int button) {
            if (button != 0 || !this.over(mouseX, mouseY)) {
                return null;
            }
            if (this.usable.getAsBoolean()) {
                Kit.click();
                EditorState.guard(this.action);
            }
            return this;
        }

        @Override
        @Nullable Component tipAt(double mouseX, double mouseY) {
            if (!this.over(mouseX, mouseY)) {
                return null;
            }
            return this.liveTip != null ? this.liveTip.get() : this.tip;
        }
    }

    /** A box that is ticked or not, with its name beside it */
    static final class Check extends Gadget {
        private final Component label;
        private final BooleanSupplier value;
        private final Consumer<Boolean> change;
        private boolean cut = false;

        Check(Component label, BooleanSupplier value, Consumer<Boolean> change) {
            this.label = label;
            this.value = value;
            this.change = change;
        }

        @Override
        int heightFor(Font font, int forWidth) {
            return 12;
        }

        @Override
        int wantedWidth(Font font) {
            return this.fixedWidth > 0 ? this.fixedWidth : font.width(this.label) + 16;
        }

        @Override
        void draw(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
            boolean enabled = this.usable.getAsBoolean();
            boolean hovered = enabled && this.over(mouseX, mouseY);
            int top = this.y + (this.height - 9) / 2;
            graphics.fill(this.x, top, this.x + 9, top + 9, Controls.FIELD);
            graphics.renderOutline(this.x, top, 9, 9, hovered ? Controls.BRASS_BRIGHT : Controls.BRASS);
            if (this.value.getAsBoolean()) {
                graphics.fill(this.x + 2, top + 2, this.x + 7, top + 7, enabled ? Controls.BRASS_BRIGHT : Controls.BRASS);
            }
            this.cut = font.width(this.label) > this.width - 13;
            Kit.text(graphics, font, Kit.clip(font, this.label.getString(), this.width - 13), this.x + 13, this.y + (this.height - 8) / 2,
                    enabled ? Controls.PARCHMENT : Controls.PARCHMENT_DIM);
        }

        @Override
        @Nullable Component tipAt(double mouseX, double mouseY) {
            if (!this.over(mouseX, mouseY)) {
                return null;
            }
            return whole(this.label, this.tip, this.cut);
        }

        @Override
        @Nullable Gadget press(double mouseX, double mouseY, int button) {
            if (button != 0 || !this.over(mouseX, mouseY)) {
                return null;
            }
            if (this.usable.getAsBoolean()) {
                Kit.click();
                boolean next = !this.value.getAsBoolean();
                EditorState.guard(() -> this.change.accept(next));
            }
            return this;
        }
    }

    /** A button that steps through a list of choices: the left button goes on to the next, the right back to the one before */
    static final class Cycle<T> extends Gadget {
        private final Supplier<List<T>> choices;
        private final Function<T, Component> names;
        private final Supplier<T> value;
        private final Consumer<T> change;

        Cycle(List<T> choices, Function<T, Component> names, Supplier<T> value, Consumer<T> change) {
            this(() -> choices, names, value, change);
        }

        Cycle(Supplier<List<T>> choices, Function<T, Component> names, Supplier<T> value, Consumer<T> change) {
            this.choices = choices;
            this.names = names;
            this.value = value;
            this.change = change;
        }

        @Override
        int wantedWidth(Font font) {
            return this.fixedWidth > 0 ? this.fixedWidth : 80;
        }

        @Override
        void draw(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
            boolean enabled = this.usable.getAsBoolean();
            boolean hovered = this.over(mouseX, mouseY);
            Kit.face(graphics, this.x, this.y, this.width, this.height, hovered, false, enabled);
            int colour = enabled ? Controls.PARCHMENT : Controls.PARCHMENT_DIM;
            T now = this.value.get();
            String words = now == null ? "" : this.names.apply(now).getString();
            Kit.centred(graphics, font, words, this.x + 7, this.y + (this.height - 8) / 2, this.width - 14, colour);
            int middle = this.y + this.height / 2;
            // A small arrow head at either end says there is more than one choice
            for (int i = 0; i < 3; i++) {
                graphics.fill(this.x + 5 - i, middle - 2 + i, this.x + 6 - i, middle + 3 - i, Controls.BRASS_BRIGHT);
                graphics.fill(this.x + this.width - 6 + i, middle - 2 + i, this.x + this.width - 5 + i, middle + 3 - i, Controls.BRASS_BRIGHT);
            }
        }

        @Override
        @Nullable Gadget press(double mouseX, double mouseY, int button) {
            if ((button != 0 && button != 1) || !this.over(mouseX, mouseY)) {
                return null;
            }
            List<T> all = this.choices.get();
            if (this.usable.getAsBoolean() && !all.isEmpty()) {
                Kit.click();
                int index = all.indexOf(this.value.get());
                int step = button == 0 ? 1 : -1;
                T next = all.get(Math.floorMod((index < 0 ? (step > 0 ? -1 : 0) : index) + step, all.size()));
                EditorState.guard(() -> this.change.accept(next));
            }
            return this;
        }
    }

    /**
     * A slider. While it is dragged the value is tried out live; it is only set for good when the button comes up, so
     * a drag is one change and not hundreds.
     */
    static final class Slide extends Gadget {
        private final double min;
        private final double max;
        private final boolean ratio;
        private final DoubleSupplier value;
        private final DoubleConsumer live;
        private final DoubleConsumer done;
        private final DoubleFunction<String> words;
        private boolean dragging = false;
        private double dragged;

        /**
         * @param ratio whether equal distances along the slider multiply the value by equal amounts
         */
        Slide(double min, double max, boolean ratio, DoubleSupplier value, DoubleConsumer live, DoubleConsumer done, DoubleFunction<String> words) {
            this.min = min;
            this.max = max;
            this.ratio = ratio;
            this.value = value;
            this.live = live;
            this.done = done;
            this.words = words;
        }

        private double share(double of) {
            double clamped = Math.max(this.min, Math.min(this.max, of));
            if (this.ratio) {
                return Math.log(clamped / this.min) / Math.log(this.max / this.min);
            }
            return (clamped - this.min) / (this.max - this.min);
        }

        private double at(double mouseX) {
            double share = Math.max(0.0, Math.min(1.0, (mouseX - this.x - 2) / Math.max(1.0, this.width - 4)));
            double raw = this.ratio ? this.min * Math.pow(this.max / this.min, share) : this.min + share * (this.max - this.min);
            // Rounded to what a hand can aim at
            double step = this.ratio ? Math.pow(10.0, Math.floor(Math.log10(Math.max(1.0e-9, raw))) - 1) : (this.max - this.min) / Math.max(1.0, this.width - 4) >= 0.5 ? 1.0 : 0.1;
            return Math.max(this.min, Math.min(this.max, Math.rint(raw / step) * step));
        }

        @Override
        void draw(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
            boolean enabled = this.usable.getAsBoolean();
            double now = this.dragging ? this.dragged : this.value.getAsDouble();
            graphics.fill(this.x, this.y, this.x + this.width, this.y + this.height, Controls.FIELD);
            graphics.renderOutline(this.x, this.y, this.width, this.height, this.dragging || (enabled && this.over(mouseX, mouseY)) ? Controls.BRASS_BRIGHT : Controls.BRASS);
            int knob = this.x + 2 + (int) Math.round(this.share(now) * Math.max(1, this.width - 4));
            graphics.fill(this.x + 1, this.y + 1, Math.max(this.x + 1, Math.min(this.x + this.width - 1, knob)), this.y + this.height - 1, 0xFF5A4520);
            graphics.fill(Math.max(this.x + 1, knob - 1), this.y + 1, Math.min(this.x + this.width - 1, knob + 1), this.y + this.height - 1, enabled ? Controls.PARCHMENT : Controls.PARCHMENT_DIM);
            Kit.centred(graphics, font, this.words.apply(now), this.x, this.y + (this.height - 8) / 2, this.width, enabled ? Controls.PARCHMENT : Controls.PARCHMENT_DIM);
        }

        @Override
        @Nullable Gadget press(double mouseX, double mouseY, int button) {
            if (button != 0 || !this.over(mouseX, mouseY)) {
                return null;
            }
            if (this.usable.getAsBoolean()) {
                this.dragging = true;
                this.drag(mouseX, mouseY);
            }
            return this;
        }

        @Override
        void drag(double mouseX, double mouseY) {
            if (this.dragging) {
                this.dragged = this.at(mouseX);
                double now = this.dragged;
                EditorState.guard(() -> this.live.accept(now));
            }
        }

        @Override
        void release(double mouseX, double mouseY) {
            if (this.dragging) {
                this.dragging = false;
                double now = this.dragged;
                EditorState.guard(() -> this.done.accept(now));
            }
        }
    }

    // ------------------------------------------------------------------ Controls made of controls

    /** A name on the left and the control it names on the right */
    static final class Pair extends Gadget {
        private final Component label;
        private final Gadget control;
        private boolean cut = false;

        Pair(Component label, Gadget control) {
            this.label = label;
            this.control = control;
        }

        private int labelWidth(int of) {
            return Math.max(30, of * 42 / 100);
        }

        @Override
        Gadget when(BooleanSupplier condition) {
            this.control.when(condition);
            return super.when(condition);
        }

        @Override
        int heightFor(Font font, int forWidth) {
            return this.control.heightFor(font, forWidth - this.labelWidth(forWidth));
        }

        @Override
        void clip(int top, int bottom) {
            super.clip(top, bottom);
            this.control.clip(top, bottom);
        }

        @Override
        void place(Font font, int newX, int newY, int newWidth, int newHeight) {
            super.place(font, newX, newY, newWidth, newHeight);
            int labelWidth = this.labelWidth(newWidth);
            this.control.place(font, newX + labelWidth, newY, newWidth - labelWidth, newHeight);
        }

        @Override
        void draw(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
            this.cut = font.width(this.label) > this.labelWidth(this.width) - 3;
            Kit.text(graphics, font, Kit.clip(font, this.label.getString(), this.labelWidth(this.width) - 3), this.x + 1, this.y + 3,
                    this.usable.getAsBoolean() ? Controls.PARCHMENT : Controls.PARCHMENT_DIM);
            this.control.draw(graphics, font, mouseX, mouseY);
        }

        @Override
        @Nullable Gadget press(double mouseX, double mouseY, int button) {
            return this.control.press(mouseX, mouseY, button);
        }

        @Override
        @Nullable Component tipAt(double mouseX, double mouseY) {
            if (!this.over(mouseX, mouseY)) {
                return null;
            }
            Component inner = this.control.tipAt(mouseX, mouseY);
            if (inner != null) {
                return inner;
            }
            return whole(this.label, this.tip, this.cut);
        }

        @Override
        void forget() {
            this.control.forget();
        }
    }

    /** Several controls side by side, sharing the width. Those with a fixed width keep it */
    static final class Line extends Gadget {
        private final List<Gadget> parts = new ArrayList<>();

        Line(Gadget... parts) {
            this.parts.addAll(List.of(parts));
        }

        private int[] widths(int of) {
            int fixed = 0;
            int sharing = 0;
            for (Gadget part : this.parts) {
                if (part.fixedWidth > 0) {
                    fixed += part.fixedWidth;
                } else {
                    sharing++;
                }
            }
            int gaps = 2 * Math.max(0, this.parts.size() - 1);
            int each = sharing == 0 ? 0 : Math.max(8, (of - fixed - gaps) / sharing);
            int[] widths = new int[this.parts.size()];
            for (int i = 0; i < widths.length; i++) {
                widths[i] = this.parts.get(i).fixedWidth > 0 ? this.parts.get(i).fixedWidth : each;
            }
            return widths;
        }

        @Override
        int heightFor(Font font, int forWidth) {
            int[] widths = this.widths(forWidth);
            int most = 0;
            for (int i = 0; i < widths.length; i++) {
                most = Math.max(most, this.parts.get(i).heightFor(font, widths[i]));
            }
            return most;
        }

        @Override
        void clip(int top, int bottom) {
            super.clip(top, bottom);
            this.parts.forEach(part -> part.clip(top, bottom));
        }

        @Override
        void place(Font font, int newX, int newY, int newWidth, int newHeight) {
            super.place(font, newX, newY, newWidth, newHeight);
            int[] widths = this.widths(newWidth);
            int at = newX;
            for (int i = 0; i < widths.length; i++) {
                Gadget part = this.parts.get(i);
                part.place(font, at, newY, widths[i], Math.min(newHeight, Math.max(1, part.heightFor(font, widths[i]))));
                at += widths[i] + 2;
            }
        }

        @Override
        void draw(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
            for (Gadget part : this.parts) {
                part.draw(graphics, font, mouseX, mouseY);
            }
        }

        @Override
        @Nullable Gadget press(double mouseX, double mouseY, int button) {
            for (Gadget part : this.parts) {
                Gadget hit = part.press(mouseX, mouseY, button);
                if (hit != null) {
                    return hit;
                }
            }
            return null;
        }

        @Override
        @Nullable Component tipAt(double mouseX, double mouseY) {
            for (Gadget part : this.parts) {
                Component words = part.tipAt(mouseX, mouseY);
                if (words != null) {
                    return words;
                }
            }
            return this.over(mouseX, mouseY) ? this.tip : null;
        }

        @Override
        void forget() {
            this.parts.forEach(Gadget::forget);
        }
    }

}
