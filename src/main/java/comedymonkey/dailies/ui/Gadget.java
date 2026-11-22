package comedymonkey.dailies.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * One control of a Dailies screen: a button, a tick box, a dropdown, a slider, a line of words. Dailies draws its own
 * controls because they live in panels that scroll, and each reads what it shows from the state behind it on every
 * frame, so undo, redo, the MCP tools and a settings file changed by hand are reflected at once. The kinds of control
 * are the classes nested here; text boxes are {@link Entry}.
 * <p>
 * Every control is {@link Kit#ROW} high and shows the same states the same way: brighter under the mouse, darker
 * while held, faint when it cannot be used, and ringed in parchment when the keys act on it.
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

    /** The control the keys act on, which is drawn with a ring; null when none, or when a box is being typed into */
    static @Nullable Gadget focus = null;

    /** Whether the Tab key stops here */
    boolean focusable() {
        return false;
    }

    /** Adds itself, or the name-and-control pairs it is made of, so that a form can line their names up in one column */
    void pairs(List<Pair> into) {
    }

    /** Adds itself, or the controls it is made of, to the order the Tab key goes through */
    void collect(List<Gadget> order) {
        if (this.focusable() && this.usable.getAsBoolean() && this.height > 0) {
            order.add(this);
        }
    }

    /**
     * Whether a control is this one or one it is made of, whether or not it can be reached now: folded away, unusable
     * and out of sight count too. Every control that holds others answers for them, so that a form can tell whether
     * the box being typed into is one of its own.
     */
    boolean holds(Gadget control) {
        return control == this;
    }

    /**
     * A key went down while the keys act on this control.
     *
     * @return whether the key was meant for it
     */
    boolean key(int keyCode, int modifiers) {
        return false;
    }

    static boolean activates(int keyCode) {
        return keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER || keyCode == InputConstants.KEY_SPACE;
    }

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

    abstract void draw(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY);

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
            return 18;
        }

        @Override
        int wantedWidth(Font font) {
            return font.width(this.words.get()) + 6;
        }

        @Override
        void draw(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
            // Room above sets a group apart from the one before; the rule under the words ties it to what follows
            Kit.text(graphics, font, Kit.clip(font, this.words.get().getString(), this.width - 2), this.x + 1, this.y + 6, Controls.BRASS_BRIGHT);
            Kit.rule(graphics, this.x, this.y + this.height - 2, this.x + this.width);
        }

        @Override
        @Nullable Component tipAt(double mouseX, double mouseY) {
            if (!this.over(mouseX, mouseY)) {
                return null;
            }
            Component words = this.words.get();
            return this.tip != null ? this.tip : Minecraft.getInstance().font.width(words) > this.width - 2 ? words : null;
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
        void draw(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
            Kit.text(graphics, font, Kit.clip(font, this.words.get().getString(), this.width), this.x + 1, this.y + (this.height - 8) / 2, this.colour);
        }

        @Override
        @Nullable Component tipAt(double mouseX, double mouseY) {
            if (!this.over(mouseX, mouseY)) {
                return null;
            }
            if (this.tip != null) {
                return this.tip;
            }
            Component words = this.words.get();
            return Minecraft.getInstance().font.width(words) > this.width - 1 ? words : null;
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
            return font.split(now, Math.max(20, forWidth - 2)).size() * 10 + 2;
        }

        @Override
        void draw(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
            Component now = this.words.get();
            if (now.getString().isEmpty()) {
                return;
            }
            int line = this.y + 1;
            boolean bright = this.action != null && this.over(mouseX, mouseY);
            for (FormattedCharSequence part : font.split(now, Math.max(20, this.width - 2))) {
                graphics.text(font, part, this.x + 1, line, bright ? Controls.PARCHMENT : this.colour, false);
                line += 10;
            }
        }

        @Override
        @Nullable Gadget press(double mouseX, double mouseY, int button) {
            if (this.action == null || button != InputConstants.MOUSE_BUTTON_LEFT || !this.over(mouseX, mouseY)) {
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
        private boolean cut = false;

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
        boolean focusable() {
            return true;
        }

        @Override
        boolean key(int keyCode, int modifiers) {
            if (!activates(keyCode) || !this.usable.getAsBoolean()) {
                return false;
            }
            Kit.click();
            EditorState.guard(this.action);
            return true;
        }

        @Override
        void draw(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
            boolean enabled = this.usable.getAsBoolean();
            boolean hovered = this.over(mouseX, mouseY);
            boolean on = this.lit.getAsBoolean();
            boolean pressed = hovered && enabled && Kit.mouseDown();
            Kit.face(graphics, this.x, this.y, this.width, this.height, hovered, on, enabled, pressed, focus == this);
            int colour = !enabled ? Controls.PARCHMENT_FAINT : Controls.PARCHMENT;
            // The words sink a pixel with the face while the button is held
            int sunk = pressed ? 1 : 0;
            if (this.icon != null) {
                Kit.icon(graphics, this.icon.get(), this.x + this.width / 2, this.y + this.height / 2 + sunk, colour);
            } else {
                String words = this.label.get().getString();
                this.cut = font.width(words) > this.width - 4;
                Kit.centred(graphics, font, words, this.x + 1, this.y + (this.height - 8) / 2 + sunk, this.width - 2, colour);
            }
        }

        @Override
        @Nullable Gadget press(double mouseX, double mouseY, int button) {
            if (button != InputConstants.MOUSE_BUTTON_LEFT || !this.over(mouseX, mouseY)) {
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
            Component words = this.liveTip != null ? this.liveTip.get() : this.tip;
            if (this.icon == null && this.cut) {
                // There was no room for all of its name
                return words == null ? this.label.get() : whole(this.label.get(), words, true);
            }
            return words;
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
        int wantedWidth(Font font) {
            return this.fixedWidth > 0 ? this.fixedWidth : font.width(this.label) + 18;
        }

        @Override
        boolean focusable() {
            return true;
        }

        @Override
        boolean key(int keyCode, int modifiers) {
            if (!activates(keyCode) || !this.usable.getAsBoolean()) {
                return false;
            }
            Kit.click();
            boolean next = !this.value.getAsBoolean();
            EditorState.guard(() -> this.change.accept(next));
            return true;
        }

        @Override
        void draw(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
            boolean enabled = this.usable.getAsBoolean();
            boolean hovered = enabled && this.over(mouseX, mouseY);
            boolean on = this.value.getAsBoolean();
            int top = this.y + (this.height - 10) / 2;
            int edge = !enabled ? Controls.BRASS_DIM : hovered ? Controls.BRASS_BRIGHT : Controls.BRASS;
            if (on) {
                // A ticked box is filled with brass, so a column of them can be read at a glance
                graphics.fill(this.x, top, this.x + 10, top + 10, enabled ? (hovered ? 0xFF85682A : Controls.FACE_LIT) : 0xFF4A3A1C);
                graphics.outline(this.x, top, 10, 10, enabled ? Controls.BRASS_BRIGHT : Controls.BRASS_DIM);
                Kit.icon(graphics, Kit.Icon.TICK, this.x + 5, top + 5, enabled ? Controls.PARCHMENT : Controls.PARCHMENT_FAINT);
            } else {
                Kit.field(graphics, this.x, top, 10, 10, edge);
            }
            if (focus == this) {
                Kit.focusRing(graphics, this.x, top, 10, 10);
            }
            this.cut = font.width(this.label) > this.width - 15;
            Kit.text(graphics, font, Kit.clip(font, this.label.getString(), this.width - 15), this.x + 15, this.y + (this.height - 8) / 2,
                    enabled ? Controls.PARCHMENT : Controls.PARCHMENT_FAINT);
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
            if (button != InputConstants.MOUSE_BUTTON_LEFT || !this.over(mouseX, mouseY)) {
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

    /**
     * A dropdown: a box that shows the choice in force with a small arrow at its right end. A click opens the list of
     * choices under it, or over it when there is no room below; see {@link Menu} for how the list behaves. With the
     * keys on it, Enter, the space bar or the down arrow opens the list.
     * <p>
     * It keeps the name it had when it was a button that stepped through the choices one click at a time, so that the
     * forms written for that button show dropdowns without being touched. {@link Dropdown} is the same control under
     * its proper name.
     */
    static class Cycle<T> extends Gadget {
        private final Supplier<List<T>> choices;
        private final Function<T, Component> names;
        private final Supplier<T> value;
        private final Consumer<T> change;
        private @Nullable ToIntFunction<T> swatch;
        private @Nullable Supplier<Component> otherwise;
        private boolean cut = false;

        Cycle(List<T> choices, Function<T, Component> names, Supplier<T> value, Consumer<T> change) {
            this(() -> choices, names, value, change);
        }

        Cycle(Supplier<List<T>> choices, Function<T, Component> names, Supplier<T> value, Consumer<T> change) {
            this.choices = choices;
            this.names = names;
            this.value = value;
            this.change = change;
        }

        /** Shows a patch of colour beside each choice, as 0xRRGGBB; -1 for a choice that has none */
        Cycle<T> swatch(ToIntFunction<T> colour) {
            this.swatch = colour;
            return this;
        }

        /** The words the closed box shows when what is in force is not one of the choices */
        Cycle<T> otherwise(Supplier<Component> words) {
            this.otherwise = words;
            return this;
        }

        @Override
        int wantedWidth(Font font) {
            return this.fixedWidth > 0 ? this.fixedWidth : 80;
        }

        @Override
        boolean focusable() {
            return true;
        }

        private boolean ready() {
            return this.usable.getAsBoolean() && !this.choices.get().isEmpty();
        }

        private Component shown() {
            T now = this.value.get();
            if (now != null && (this.otherwise == null || this.choices.get().contains(now))) {
                return this.names.apply(now);
            }
            return this.otherwise != null ? this.otherwise.get() : Component.empty();
        }

        /** Opens the list of choices, hanging from this box, with the one in force ticked */
        void open() {
            List<T> all = this.choices.get();
            if (all.isEmpty()) {
                return;
            }
            T now = this.value.get();
            List<Menu.Item> items = new ArrayList<>();
            for (T choice : all) {
                items.add(new Menu.Item(this.names.apply(choice), () -> this.change.accept(choice), true, Objects.equals(choice, now),
                        this.swatch == null ? -1 : this.swatch.applyAsInt(choice)));
            }
            Menu.drop(items, this.x, this.y, this.width, Kit.ROW, this);
        }

        /** The words the closed box shows */
        String showing() {
            return this.shown().getString();
        }

        /** The choices as the list would offer them now */
        List<T> offered() {
            return List.copyOf(this.choices.get());
        }

        @Override
        boolean key(int keyCode, int modifiers) {
            if ((activates(keyCode) || keyCode == InputConstants.KEY_DOWN) && this.ready()) {
                Kit.click();
                this.open();
                return true;
            }
            return false;
        }

        @Override
        void draw(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
            boolean enabled = this.ready();
            boolean hovered = this.over(mouseX, mouseY);
            boolean opened = Menu.openFor(this);
            Kit.face(graphics, this.x, this.y, this.width, Kit.ROW, hovered || opened, false, enabled, opened || (hovered && enabled && Kit.mouseDown()), focus == this);
            int colour = enabled ? Controls.PARCHMENT : Controls.PARCHMENT_FAINT;
            int textLeft = this.x + 4;
            T now = this.value.get();
            if (this.swatch != null && now != null) {
                int patch = this.swatch.applyAsInt(now);
                if (patch >= 0) {
                    graphics.fill(textLeft, this.y + 3, textLeft + 8, this.y + Kit.ROW - 3, 0xFF000000 | (patch & 0xFFFFFF));
                    graphics.outline(textLeft, this.y + 3, 8, Kit.ROW - 6, Controls.BRASS_DIM);
                    textLeft += 12;
                }
            }
            // The arrow has a part of the box to itself, set off by a line, so the words never run under it
            int arrowLeft = this.x + this.width - 13;
            String words = this.shown().getString();
            int room = arrowLeft - textLeft - 2;
            this.cut = font.width(words) > room;
            Kit.text(graphics, font, Kit.clip(font, words, room), textLeft, this.y + 3, colour);
            if (this.width > 30) {
                graphics.fill(arrowLeft, this.y + 2, arrowLeft + 1, this.y + Kit.ROW - 2, enabled ? Controls.BRASS_DIM : 0xFF4A3A1C);
            }
            Kit.icon(graphics, Kit.Icon.CHEVRON, this.x + this.width - 7, this.y + Kit.ROW / 2 - 1,
                    !enabled ? Controls.PARCHMENT_FAINT : hovered || opened ? Controls.PARCHMENT : Controls.BRASS_BRIGHT);
        }

        @Override
        @Nullable Gadget press(double mouseX, double mouseY, int button) {
            if (button != InputConstants.MOUSE_BUTTON_LEFT || !this.over(mouseX, mouseY)) {
                return null;
            }
            if (this.ready()) {
                Kit.click();
                focus = null;
                this.open();
            }
            return this;
        }

        @Override
        @Nullable Component tipAt(double mouseX, double mouseY) {
            if (!this.over(mouseX, mouseY)) {
                return null;
            }
            if (this.cut) {
                return this.tip == null ? this.shown() : Component.empty().append(this.shown()).append("\n").append(this.tip);
            }
            return this.tip;
        }
    }

    /** A dropdown, under its proper name. See {@link Cycle} */
    static final class Dropdown<T> extends Cycle<T> {
        Dropdown(List<T> choices, Function<T, Component> names, Supplier<T> value, Consumer<T> change) {
            super(choices, names, value, change);
        }

        Dropdown(Supplier<List<T>> choices, Function<T, Component> names, Supplier<T> value, Consumer<T> change) {
            super(choices, names, value, change);
        }
    }

    /** A switch that is on or off, for a setting that has its name beside it already */
    static final class Switch extends Gadget {
        private final BooleanSupplier value;
        private final Consumer<Boolean> change;
        private float slid = -1.0F;
        private long drawnAt = 0;

        Switch(BooleanSupplier value, Consumer<Boolean> change) {
            this.value = value;
            this.change = change;
        }

        @Override
        int wantedWidth(Font font) {
            return this.fixedWidth > 0 ? this.fixedWidth : 60;
        }

        @Override
        boolean focusable() {
            return true;
        }

        private void flip() {
            Kit.click();
            boolean next = !this.value.getAsBoolean();
            EditorState.guard(() -> this.change.accept(next));
        }

        @Override
        boolean key(int keyCode, int modifiers) {
            if (!activates(keyCode) || !this.usable.getAsBoolean()) {
                return false;
            }
            this.flip();
            return true;
        }

        private boolean overSwitch(double mouseX, double mouseY) {
            return this.over(mouseX, mouseY) && mouseX < this.x + 26 + 30;
        }

        @Override
        void draw(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
            boolean enabled = this.usable.getAsBoolean();
            boolean hovered = enabled && this.overSwitch(mouseX, mouseY);
            boolean on = this.value.getAsBoolean();
            // The knob slides across in a tenth of a second
            long now = net.minecraft.util.Util.getMillis();
            float target = on ? 1.0F : 0.0F;
            if (this.slid < 0.0F || now - this.drawnAt > 500) {
                this.slid = target;
            } else {
                float step = (now - this.drawnAt) / 100.0F;
                this.slid = this.slid < target ? Math.min(target, this.slid + step) : Math.max(target, this.slid - step);
            }
            this.drawnAt = now;
            int top = this.y + (this.height - 10) / 2;
            int trackWidth = 22;
            int edge = !enabled ? Controls.BRASS_DIM : hovered ? Controls.BRASS_BRIGHT : Controls.BRASS;
            Kit.field(graphics, this.x, top, trackWidth, 10, edge);
            int knob = this.x + 1 + Math.round((trackWidth - 12) * Kit.ease(this.slid));
            if (this.slid > 0.0F) {
                graphics.fill(this.x + 1, top + 1, knob + 5, top + 9, enabled ? Controls.FACE_LIT : 0xFF4A3A1C);
            }
            graphics.fill(knob, top + 1, knob + 10, top + 9, !enabled ? Controls.PARCHMENT_FAINT : on ? Controls.BRASS_BRIGHT : Controls.PARCHMENT_DIM);
            graphics.fill(knob, top + 1, knob + 10, top + 2, 0x40FFFFFF);
            if (focus == this) {
                Kit.focusRing(graphics, this.x, top, trackWidth, 10);
            }
            Kit.text(graphics, font, Component.translatable(on ? "dailies.ui.switch.on" : "dailies.ui.switch.off").getString(), this.x + trackWidth + 5,
                    this.y + (this.height - 8) / 2, !enabled ? Controls.PARCHMENT_FAINT : on ? Controls.PARCHMENT : Controls.PARCHMENT_DIM);
        }

        @Override
        @Nullable Gadget press(double mouseX, double mouseY, int button) {
            if (button != InputConstants.MOUSE_BUTTON_LEFT || !this.overSwitch(mouseX, mouseY)) {
                return null;
            }
            if (this.usable.getAsBoolean()) {
                this.flip();
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
        private boolean whole = false;

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

        /** Only whole numbers can be slid to */
        Slide whole() {
            this.whole = true;
            return this;
        }

        @Override
        boolean focusable() {
            return true;
        }

        /** The left and right arrows move it a step at a time; each press is one change */
        @Override
        boolean key(int keyCode, int modifiers) {
            if ((keyCode != InputConstants.KEY_LEFT && keyCode != InputConstants.KEY_RIGHT) || !this.usable.getAsBoolean()) {
                return false;
            }
            int way = keyCode == InputConstants.KEY_RIGHT ? 1 : -1;
            double now = this.value.getAsDouble();
            double next;
            if (this.ratio) {
                next = now * Math.pow(this.max / this.min, way * 0.02);
                if (this.whole) {
                    next = way > 0 ? Math.max(now + 1.0, Math.rint(next)) : Math.min(now - 1.0, Math.rint(next));
                }
            } else {
                double step = this.whole ? Math.max(1.0, Math.rint((this.max - this.min) / 100.0)) : (this.max - this.min) / 100.0;
                next = now + way * step;
            }
            double set = Math.max(this.min, Math.min(this.max, next));
            EditorState.guard(() -> this.done.accept(set));
            return true;
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
            if (this.whole) {
                step = Math.max(1.0, step);
            }
            return Math.max(this.min, Math.min(this.max, Math.rint(raw / step) * step));
        }

        @Override
        void draw(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
            boolean enabled = this.usable.getAsBoolean();
            double now = this.dragging ? this.dragged : this.value.getAsDouble();
            boolean hot = this.dragging || (enabled && this.over(mouseX, mouseY));
            Kit.field(graphics, this.x, this.y, this.width, this.height, !enabled ? Controls.BRASS_DIM : hot ? Controls.BRASS_BRIGHT : Controls.BRASS);
            int knob = this.x + 2 + (int) Math.round(this.share(now) * Math.max(1, this.width - 4));
            int fillTo = Math.max(this.x + 1, Math.min(this.x + this.width - 1, knob));
            graphics.fill(this.x + 1, this.y + 1, fillTo, this.y + this.height - 1, enabled ? 0xFF5A4520 : 0xFF3A2D18);
            graphics.fill(this.x + 1, this.y + 1, fillTo, this.y + 2, 0x18FFFFFF);
            graphics.fill(Math.max(this.x + 1, knob - 1), this.y + 1, Math.min(this.x + this.width - 1, knob + 1), this.y + this.height - 1,
                    !enabled ? Controls.PARCHMENT_FAINT : hot ? Controls.PARCHMENT : Controls.BRASS_BRIGHT);
            if (focus == this) {
                Kit.focusRing(graphics, this.x, this.y, this.width, this.height);
            }
            // With a shadow, so that the value reads where the knob stands behind it
            String value = Kit.clip(font, this.words.apply(now), this.width - 4);
            graphics.text(font, value, this.x + (this.width - font.width(value)) / 2, this.y + (this.height - 8) / 2,
                    enabled ? Controls.PARCHMENT : Controls.PARCHMENT_FAINT, true);
        }

        @Override
        @Nullable Gadget press(double mouseX, double mouseY, int button) {
            if (button != InputConstants.MOUSE_BUTTON_LEFT || !this.over(mouseX, mouseY)) {
                return null;
            }
            if (this.usable.getAsBoolean()) {
                focus = null;
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
        /** The width of the names' column when the form lines them up; 0 for the usual share */
        int column = 0;

        Pair(Component label, Gadget control) {
            this.label = label;
            this.control = control;
        }

        /** How wide its name is in the font, for the form to size the column of names */
        int nameWidth(Font font) {
            return font.width(this.label);
        }

        @Override
        void pairs(List<Pair> into) {
            into.add(this);
        }

        private int labelWidth(int of) {
            if (this.column > 0) {
                return Math.max(30, Math.min(this.column, of - 30));
            }
            return Math.max(30, of * 42 / 100);
        }

        @Override
        void collect(List<Gadget> order) {
            this.control.collect(order);
        }

        @Override
        boolean holds(Gadget other) {
            return other == this || this.control.holds(other);
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
        void draw(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
            this.cut = font.width(this.label) > this.labelWidth(this.width) - 3;
            Kit.text(graphics, font, Kit.clip(font, this.label.getString(), this.labelWidth(this.width) - 3), this.x + 1, this.y + 3,
                    this.usable.getAsBoolean() ? Controls.PARCHMENT : Controls.PARCHMENT_FAINT);
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

        @Override
        void collect(List<Gadget> order) {
            this.parts.forEach(part -> part.collect(order));
        }

        @Override
        boolean holds(Gadget other) {
            if (other == this) {
                return true;
            }
            for (Gadget part : this.parts) {
                if (part.holds(other)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        void pairs(List<Pair> into) {
            this.parts.forEach(part -> part.pairs(into));
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
        void draw(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
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

    /**
     * Rows one under another that fold away as one, as the settings of a group do. Folding and unfolding take a
     * moment, so the eye can follow where the rows went.
     */
    static final class Fold extends Gadget {
        private static final float MILLIS = 140.0F;
        private final List<Gadget> rows = new ArrayList<>();
        private final BooleanSupplier open;
        private float shown = -1.0F;
        private long drawnAt = 0;
        private int full = 0;

        Fold(BooleanSupplier open) {
            this.open = open;
        }

        Fold add(Gadget row) {
            this.rows.add(row);
            return this;
        }

        List<Gadget> rows() {
            return this.rows;
        }

        /** Shows it as it is at once, folded or not, without the moment in between */
        void settle() {
            this.shown = -1.0F;
        }

        private float advance() {
            long now = net.minecraft.util.Util.getMillis();
            float target = this.open.getAsBoolean() ? 1.0F : 0.0F;
            if (this.shown < 0.0F || now - this.drawnAt > 1000) {
                this.shown = target;
            } else if (now != this.drawnAt) {
                float step = (now - this.drawnAt) / MILLIS;
                this.shown = this.shown < target ? Math.min(target, this.shown + step) : Math.max(target, this.shown - step);
            }
            this.drawnAt = now;
            return this.shown;
        }

        @Override
        void collect(List<Gadget> order) {
            if (this.open.getAsBoolean()) {
                this.rows.forEach(row -> row.collect(order));
            }
        }

        @Override
        boolean holds(Gadget other) {
            if (other == this) {
                return true;
            }
            for (Gadget row : this.rows) {
                if (row.holds(other)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        void pairs(List<Pair> into) {
            this.rows.forEach(row -> row.pairs(into));
        }

        @Override
        int heightFor(Font font, int forWidth) {
            float share = this.advance();
            if (share <= 0.0F) {
                return 0;
            }
            int total = 0;
            for (Gadget row : this.rows) {
                int needed = Math.max(0, row.heightFor(font, forWidth));
                total += needed + (needed > 0 ? Kit.GAP : 0);
            }
            this.full = total;
            return Math.round(total * Kit.ease(share));
        }

        @Override
        void place(Font font, int newX, int newY, int newWidth, int newHeight) {
            super.place(font, newX, newY, newWidth, newHeight);
            // While it folds, the rows slide up under the heading rather than being squeezed
            int at = newY - (this.full - newHeight);
            int top = Math.max(this.clipTop, newY);
            int bottom = Math.min(this.clipBottom, newY + newHeight);
            for (Gadget row : this.rows) {
                int needed = Math.max(0, row.heightFor(font, newWidth));
                row.clip(top, Math.max(top, bottom));
                row.place(font, newX, at, newWidth, needed);
                at += needed + (needed > 0 ? Kit.GAP : 0);
            }
        }

        @Override
        void draw(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
            if (this.height <= 0) {
                return;
            }
            graphics.enableScissor(this.x - 2, this.y, this.x + this.width + 2, this.y + this.height);
            try {
                for (Gadget row : this.rows) {
                    if (row.height > 0 && row.y + row.height > this.y && row.y < this.y + this.height
                            && row.y + row.height > this.clipTop && row.y < this.clipBottom) {
                        row.draw(graphics, font, mouseX, mouseY);
                    }
                }
            } finally {
                graphics.disableScissor();
            }
        }

        @Override
        @Nullable Gadget press(double mouseX, double mouseY, int button) {
            if (!this.over(mouseX, mouseY)) {
                return null;
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

        @Override
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

        @Override
        void forget() {
            this.rows.forEach(Gadget::forget);
        }
    }

}
