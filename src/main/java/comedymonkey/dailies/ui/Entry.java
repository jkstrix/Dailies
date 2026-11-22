package comedymonkey.dailies.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

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
    private @Nullable Function<String, Component> live;
    private @Nullable Component liveError = null;
    /** Whether this is not a box at all but the stand-in that hands the keys to the open menu */
    private boolean forMenu = false;
    private static @Nullable Entry menuKeys = null;

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

    /**
     * While a menu is open the keys belong to it. Screens hand every key to the box being typed into, so a stand-in
     * takes that place and passes them on; the screens need know nothing of it.
     */
    static void keysToMenu() {
        blur();
        if (menuKeys == null) {
            menuKeys = new Entry(() -> "", typed -> null);
            menuKeys.forMenu = true;
        }
        focused = menuKeys;
    }

    static void menuClosed() {
        if (focused != null && focused.forMenu) {
            focused = null;
        }
    }

    /** Whether a real box is being typed into */
    static boolean typing() {
        return focused != null && !focused.forMenu;
    }

    /**
     * Has what is in the box looked at after every key, not only when it is handed over, so that a mistake shows while
     * it is being made.
     *
     * @param check takes what is in the box now; returns null when it is fine, else what is wrong with it
     */
    Entry live(Function<String, Component> check) {
        this.live = check;
        return this;
    }

    /** Puts words in the box as typing them would, without handing them over */
    void type(String words) {
        this.box.setValue(words);
        this.afterKey();
    }

    /** What is in the box now, typed or not */
    String text() {
        return this.box.getValue();
    }

    /** Starts typing in this box, as a click on it would, with all of it selected */
    void focus() {
        if (focused != this) {
            blur();
            focused = this;
            this.box.setFocused(true);
        }
        Gadget.focus = null;
        this.box.moveCursorToEnd(false);
        this.box.setHighlightPos(0);
    }

    /**
     * Puts words in the box and hands them over, as typing them and pressing Enter would.
     *
     * @return why they were refused, or null when they were taken
     */
    @Nullable Component enter(String words) {
        this.focus();
        this.box.setValue(words);
        this.afterKey();
        Component early = this.liveError;
        boolean taken = this.commit();
        focused = null;
        this.box.setFocused(false);
        Component refusal = taken ? null : this.error != null ? this.error : early;
        // Nobody is at the keys to put it right
        this.error = null;
        this.liveError = null;
        return refusal;
    }

    private @Nullable Component shownError() {
        return this.error != null ? this.error : this.liveError;
    }

    private void afterKey() {
        if (this.live != null) {
            try {
                this.liveError = this.live.apply(this.box.getValue());
            } catch (RuntimeException e) {
                this.liveError = null;
            }
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

    /** The most letters the box takes */
    Entry max(int letters) {
        this.box.setMaxLength(letters);
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
        Component wrong = this.shownError();
        if (wrong == null || !this.errorBelow) {
            return Kit.ROW;
        }
        return Kit.ROW + font.split(wrong, Math.max(20, forWidth - 2)).size() * 10 + 2;
    }

    @Override
    void place(Font font, int newX, int newY, int newWidth, int newHeight) {
        super.place(font, newX, newY, newWidth, newHeight);
        // Known before anything is put in it, so that long words are scrolled for the box as it is and not as it was made
        this.box.setWidth(Math.max(4, newWidth - (this.swatch == null ? 0 : Kit.ROW) - 7));
    }

    @Override
    void forget() {
        this.error = null;
        this.liveError = null;
    }

    @Override
    boolean focusable() {
        return true;
    }

    @Override
    void draw(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        boolean typing = focused == this;
        if (!typing && this.error == null) {
            String now = safe(this.value);
            if (!now.equals(this.box.getValue())) {
                this.box.setValue(now);
                this.box.moveCursorToStart(false);
            }
            this.liveError = null;
        }
        boolean enabled = this.usable.getAsBoolean();
        Component wrong = this.shownError();
        int patch = this.swatch == null ? 0 : Kit.ROW;
        int boxWidth = this.width - patch;
        int edge = wrong != null ? Controls.ALERT : typing ? Controls.PARCHMENT : !enabled ? Controls.BRASS_DIM
                : this.overBox(mouseX, mouseY) ? Controls.BRASS_BRIGHT : Controls.BRASS;
        Kit.field(graphics, this.x, this.y, boxWidth, Kit.ROW, edge);
        this.box.setTextColor(enabled ? Controls.PARCHMENT : Controls.PARCHMENT_FAINT);
        this.box.setX(this.x + 4);
        this.box.setY(this.y + 3);
        this.box.setWidth(Math.max(4, boxWidth - 7));
        this.box.render(graphics, mouseX, mouseY, 0.0F);
        if (this.swatch != null) {
            int left = this.x + boxWidth + Kit.GAP;
            graphics.fill(left, this.y, left + patch - Kit.GAP, this.y + Kit.ROW, 0xFF000000 | (this.swatch.getAsInt() & 0xFFFFFF));
            graphics.outline(left, this.y, patch - Kit.GAP, Kit.ROW, Controls.BRASS);
        }
        if (wrong != null && this.errorBelow) {
            int line = this.y + Kit.ROW + 2;
            for (FormattedCharSequence part : font.split(wrong, Math.max(20, this.width - 2))) {
                graphics.text(font, part, this.x + 1, line, Controls.ALERT, false);
                line += 10;
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
        Gadget.focus = null;
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
        this.liveError = null;
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
            if (!was.forMenu) {
                was.commit();
            }
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
        if (this.forMenu) {
            Menu menu = Menu.open;
            if (menu != null) {
                menu.key(keyCode, modifiers);
            } else {
                focused = null;
            }
            return;
        }
        if (keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER || keyCode == InputConstants.KEY_TAB) {
            if (this.commit()) {
                focused = null;
                this.box.setFocused(false);
                if (this.onEnter != null && keyCode != InputConstants.KEY_TAB) {
                    EditorState.guard(this.onEnter);
                }
            }
        } else if (keyCode == InputConstants.KEY_ESCAPE) {
            // Back to the value it had
            this.error = null;
            this.liveError = null;
            this.box.setValue(safe(this.value));
            this.box.moveCursorToStart(false);
            focused = null;
            this.box.setFocused(false);
        } else {
            this.box.keyPressed(keyCode, scanCode, modifiers);
            this.afterKey();
        }
    }

    void typed(char character, int modifiers) {
        if (this.forMenu) {
            Menu menu = Menu.open;
            if (menu != null) {
                menu.typed(character);
            }
            return;
        }
        this.box.charTyped(character, modifiers);
        this.afterKey();
    }

    /** Whether Escape would throw anything away: something typed and not yet handed over, or a refused value */
    boolean dirty() {
        return this.error != null || !this.box.getValue().equals(safe(this.value));
    }

}
