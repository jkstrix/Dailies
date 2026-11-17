package comedymonkey.dailies.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * A box to type a value into: a number, a name, a path, a colour. What was typed is handed over when Enter is pressed
 * or the box is left. A value that is refused stays in the box, with the reason under it, until it is put right or
 * Escape brings back the value it had. While nobody is typing, the box shows the value as the editor state has it.
 */
final class Entry extends Gadget {

    /** The one box being typed into, or null. Client thread */
    static @Nullable Entry focused = null;

    private final EditBox box;
    private final Supplier<String> value;
    private final Function<String, Component> change;
    private @Nullable Component error = null;
    private boolean errorBelow = true;
    private @Nullable IntSupplier swatch;
    private @Nullable Runnable onEnter;

    /**
     * @param value  what the box shows while nobody is typing in it
     * @param change takes what was typed; returns null when it was accepted, else why it was not
     */
    Entry(Supplier<String> value, Function<String, Component> change) {
        this.value = value;
        this.change = change;
        this.box = new EditBox(Minecraft.getInstance().font, 0, 0, 40, 8, Component.empty());
        this.box.setBordered(false);
        this.box.setMaxLength(1024);
        this.box.setTextColor(Controls.PARCHMENT);
        this.box.setValue(safe(value));
        this.box.moveCursorToStart(false);
    }

    /**
     * A box for a number.
     *
     * @param whole  whether only whole numbers are taken
     * @param change takes the number; returns null when it was accepted, else why it was not
     */
    static Entry number(Supplier<Double> value, double min, double max, boolean whole, Function<Double, Component> change) {
        return new Entry(() -> {
            Double now = value.get();
            return now == null ? "" : Kit.number(now);
        }, typed -> {
            double number;
            try {
                number = Double.parseDouble(typed.trim().replace(',', '.'));
            } catch (NumberFormatException e) {
                return Kit.t(whole ? "error.whole_number" : "error.number");
            }
            if (Double.isNaN(number) || Double.isInfinite(number) || (whole && number != Math.rint(number))) {
                return Kit.t(whole ? "error.whole_number" : "error.number");
            }
            if (number < min || number > max) {
                return Kit.t("error.range", Kit.number(min), Kit.number(max));
            }
            return change.apply(number);
        });
    }

    private static String safe(Supplier<String> from) {
        try {
            String text = from.get();
            return text == null ? "" : text;
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Refusals go to the editor's notice line, for a box in a bar that has no room under it */
    Entry errorsAsNotice() {
        this.errorBelow = false;
        return this;
    }

    /** Shows a patch of the colour the box holds, as 0xRRGGBB, at its right end */
    Entry swatch(IntSupplier colour) {
        this.swatch = colour;
        return this;
    }

    /** Words shown in the empty box */
    Entry hint(Component words) {
        this.box.setHint(words);
        return this;
    }

    /** Something to do after Enter has handed over an accepted value */
    Entry onEnter(Runnable then) {
        this.onEnter = then;
        return this;
    }

    @Override
    int wantedWidth(Font font) {
        return this.fixedWidth > 0 ? this.fixedWidth : 60;
    }

    @Override
    int heightFor(Font font, int forWidth) {
        if (this.error == null || !this.errorBelow) {
            return Kit.ROW;
        }
        return Kit.ROW + font.split(this.error, Math.max(20, forWidth - 2)).size() * 9 + 1;
    }

    @Override
    void forget() {
        this.error = null;
    }

    @Override
    void draw(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        boolean typing = focused == this;
        if (!typing && this.error == null) {
            String now = safe(this.value);
            if (!now.equals(this.box.getValue())) {
                this.box.setValue(now);
                this.box.moveCursorToStart(false);
            }
        }
        int patch = this.swatch == null ? 0 : Kit.ROW - 2;
        int boxWidth = this.width - patch;
        graphics.fill(this.x, this.y, this.x + boxWidth, this.y + Kit.ROW, Controls.FIELD);
        int edge = this.error != null ? Controls.ALERT : typing ? Controls.PARCHMENT : this.overBox(mouseX, mouseY) ? Controls.BRASS_BRIGHT : Controls.BRASS;
        graphics.renderOutline(this.x, this.y, boxWidth, Kit.ROW, edge);
        this.box.setX(this.x + 3);
        this.box.setY(this.y + 3);
        this.box.setWidth(Math.max(4, boxWidth - 6));
        this.box.render(graphics, mouseX, mouseY, 0.0F);
        if (this.swatch != null) {
            int left = this.x + boxWidth + 2;
            graphics.fill(left, this.y, left + patch - 2, this.y + Kit.ROW, 0xFF000000 | (this.swatch.getAsInt() & 0xFFFFFF));
            graphics.renderOutline(left, this.y, patch - 2, Kit.ROW, Controls.BRASS);
        }
        if (this.error != null && this.errorBelow) {
            int line = this.y + Kit.ROW + 1;
            for (FormattedCharSequence part : font.split(this.error, Math.max(20, this.width - 2))) {
                graphics.drawString(font, part, this.x + 1, line, Controls.ALERT, false);
                line += 9;
            }
        }
    }

    private boolean overBox(double mouseX, double mouseY) {
        return this.over(mouseX, mouseY) && mouseY < this.y + Kit.ROW;
    }

    @Override
    @Nullable Gadget press(double mouseX, double mouseY, int button) {
        if (button != 0 || !this.overBox(mouseX, mouseY) || !this.usable.getAsBoolean()) {
            return null;
        }
        if (focused != this) {
            blur();
            focused = this;
            this.box.setFocused(true);
        }
        this.box.onClick(mouseX, mouseY);
        return this;
    }

    /** Hands over what was typed. Returns whether it was accepted */
    private boolean commit() {
        String typed = this.box.getValue();
        if (this.error == null && typed.equals(safe(this.value))) {
            return true;
        }
        Component refusal;
        try {
            refusal = this.change.apply(typed);
        } catch (IllegalArgumentException | IllegalStateException refused) {
            refusal = Component.literal(refused.getMessage() == null ? refused.toString() : refused.getMessage());
        } catch (RuntimeException e) {
            comedymonkey.dailies.Host.LOGGER.error("Dailies: a value typed in the editor could not be used", e);
            refusal = Component.translatable("dailies.ui.notice.went_wrong");
        }
        this.error = refusal;
        if (refusal == null) {
            this.box.setValue(safe(this.value));
            this.box.moveCursorToStart(false);
            return true;
        }
        if (!this.errorBelow) {
            EditorState.say(refusal);
        }
        return false;
    }

    /** Leaves the box being typed into, handing over what it holds */
    static void blur() {
        Entry was = focused;
        if (was != null) {
            focused = null;
            was.box.setFocused(false);
            was.commit();
        }
    }

    /** Leaves the box being typed into without handing anything over, for when the form it is in is taken down */
    static void drop() {
        Entry was = focused;
        if (was != null) {
            focused = null;
            was.box.setFocused(false);
        }
    }

    /** A key went down while this box is being typed into. Every key belongs to the box then */
    void key(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER || keyCode == GLFW.GLFW_KEY_TAB) {
            if (this.commit()) {
                focused = null;
                this.box.setFocused(false);
                if (this.onEnter != null && keyCode != GLFW.GLFW_KEY_TAB) {
                    EditorState.guard(this.onEnter);
                }
            }
        } else if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            // Back to the value it had
            this.error = null;
            this.box.setValue(safe(this.value));
            this.box.moveCursorToStart(false);
            focused = null;
            this.box.setFocused(false);
        } else {
            this.box.keyPressed(keyCode, scanCode, modifiers);
        }
    }

    void typed(char character, int modifiers) {
        this.box.charTyped(character, modifiers);
    }

}
