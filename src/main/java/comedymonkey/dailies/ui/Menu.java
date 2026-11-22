package comedymonkey.dailies.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.util.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A list of things to choose from that opens over everything else and goes away after a choice: the list under a
 * dropdown, the kinds of track to add, the scenes, what can be done with a track. One at a time.
 * <p>
 * It opens where the mouse is, or hangs from the control it belongs to: under it, or over it when there is no room
 * below. A long list scrolls, with the wheel or by its bar. Typing narrows the list to the lines that fit what was
 * typed; the arrow keys move along it, Enter chooses and Escape closes. A click anywhere else closes it and does
 * nothing more.
 */
final class Menu {

    /**
     * One line of a menu.
     *
     * @param marked whether this is the choice in force now: it gets a tick
     * @param swatch a colour to show beside the words as 0xRRGGBB, or -1 for none
     */
    record Item(Component label, Runnable action, boolean enabled, boolean marked, int swatch) {
        Item(Component label, Runnable action) {
            this(label, action, true, false, -1);
        }

        Item(Component label, Runnable action, boolean enabled, boolean marked) {
            this(label, action, enabled, marked, -1);
        }
    }

    private static final int LINE = 14;
    private static final int EDGE = 2;
    private static final int GUTTER = 13;
    /** The most lines a dropdown shows before it scrolls */
    private static final int MOST_LINES = 10;
    private static final long OPENING_MILLIS = 110;

    /** The menu that is open, or null. Client thread */
    static @Nullable Menu open = null;

    private final List<Item> items;
    private final List<String> labels = new ArrayList<>();
    private final boolean hung;
    private final int anchorX;
    private final int anchorY;
    private final int anchorWidth;
    private final int anchorHeight;
    /** The control this list hangs from, which draws itself as open meanwhile; null for a menu at the mouse */
    final @Nullable Object owner;
    private final boolean swatches;
    private final long openedAt = Util.getMillis();

    /** The lines on show: places in {@link #items}, all of them until something is typed */
    private List<Integer> shown = new ArrayList<>();
    private String query = "";
    /** The line the keys or the mouse are on, as a place in {@link #shown}; -1 for none */
    private int highlight = -1;
    private boolean reveal = false;
    private boolean placed = false;
    private boolean above = false;
    private int x;
    private int y;
    private int width;
    private int height;
    private int listTop;
    private int listHeight;
    private int scroll = 0;
    private int lastMouseX = Integer.MIN_VALUE;
    private int lastMouseY = Integer.MIN_VALUE;
    private boolean barHeld = false;
    private int barGrab = 0;

    private Menu(List<Item> items, boolean hung, int anchorX, int anchorY, int anchorWidth, int anchorHeight, @Nullable Object owner) {
        this.items = items;
        this.hung = hung;
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.anchorWidth = anchorWidth;
        this.anchorHeight = anchorHeight;
        this.owner = owner;
        boolean coloured = false;
        for (int index = 0; index < items.size(); index++) {
            Item item = items.get(index);
            this.labels.add(item.label().getString());
            this.shown.add(index);
            coloured |= item.swatch() >= 0;
            if (item.marked() && this.highlight < 0) {
                this.highlight = index;
                this.reveal = true;
            }
        }
        this.swatches = coloured;
    }

    /** Opens a menu at a point of the screen, usually where the mouse is */
    static void show(List<Item> items, double atX, double atY) {
        if (!items.isEmpty()) {
            open = new Menu(new ArrayList<>(items), false, (int) atX, (int) atY, 0, 0, null);
            Entry.keysToMenu();
        }
    }

    /**
     * Opens a list hanging from a control: as wide as the control at least, under it where there is room and over it
     * where there is not.
     *
     * @param owner the control, so that it can tell its own list is the one open
     */
    static void drop(List<Item> items, int boxX, int boxY, int boxWidth, int boxHeight, Object owner) {
        if (!items.isEmpty()) {
            open = new Menu(new ArrayList<>(items), true, boxX, boxY, boxWidth, boxHeight, owner);
            Entry.keysToMenu();
        }
    }

    static void close() {
        open = null;
        Entry.menuClosed();
    }

    /** Whether the list hanging from this control is the one open */
    static boolean openFor(Object control) {
        Menu menu = open;
        return menu != null && menu.owner == control;
    }

    /** The words of every line, in the order given, whatever has been typed */
    List<String> labels() {
        return List.copyOf(this.labels);
    }

    /** The words of the lines on show now */
    List<String> shownLabels() {
        List<String> words = new ArrayList<>();
        for (int place : this.shown) {
            words.add(this.labels.get(place));
        }
        return words;
    }

    // ------------------------------------------------------------------ Where it sits

    private int content() {
        return Math.max(1, this.shown.size()) * LINE;
    }

    private void fit(Font font, int screenWidth, int screenHeight) {
        int widest = 40;
        for (Item item : this.items) {
            widest = Math.max(widest, font.width(item.label()) + GUTTER + 8 + (this.swatches ? 12 : 0));
        }
        int queryHeight = this.query.isEmpty() ? 0 : LINE;
        int natural = this.content() + queryHeight + 2 * EDGE;
        if (this.hung) {
            int capped = Math.min(natural, MOST_LINES * LINE + queryHeight + 2 * EDGE);
            if (this.items.size() > MOST_LINES) {
                widest += Kit.BAR;
            }
            this.width = Math.min(Math.max(this.anchorWidth, widest), Math.max(40, screenWidth - 4));
            this.x = Math.max(2, Math.min(this.anchorX, screenWidth - this.width - 2));
            int below = screenHeight - (this.anchorY + this.anchorHeight) - 3;
            int over = this.anchorY - 3;
            if (!this.placed) {
                // Decided once, so that the list does not jump from one side of its control to the other while typing
                this.placed = true;
                this.above = capped > below && over > below;
            }
            int room = Math.max(LINE + 2 * EDGE, this.above ? over : below);
            this.height = Math.min(capped, room);
            this.y = this.above ? this.anchorY - this.height + 1 : this.anchorY + this.anchorHeight - 1;
        } else {
            this.width = Math.min(widest, Math.max(40, screenWidth - 4));
            this.height = Math.min(natural, Math.max(LINE + 2 * EDGE, screenHeight - 4));
            if (this.height < natural) {
                this.width = Math.min(this.width + Kit.BAR, Math.max(40, screenWidth - 4));
            }
            this.x = Math.max(2, Math.min(this.anchorX, screenWidth - this.width - 2));
            // Below the mouse where there is room, else above it
            this.y = this.anchorY + this.height + 2 <= screenHeight ? this.anchorY : Math.max(2, this.anchorY - this.height);
        }
        this.listTop = this.y + EDGE + queryHeight;
        this.listHeight = Math.max(1, this.height - 2 * EDGE - queryHeight);
        if (this.reveal && this.highlight >= 0) {
            this.reveal = false;
            int lineTop = this.highlight * LINE;
            if (lineTop < this.scroll) {
                this.scroll = lineTop;
            } else if (lineTop + LINE > this.scroll + this.listHeight) {
                this.scroll = lineTop + LINE - this.listHeight;
            }
        }
        this.scroll = Math.max(0, Math.min(this.scroll, this.content() - this.listHeight));
    }

    private boolean scrolls() {
        return this.content() > this.listHeight;
    }

    private boolean overBar(double mouseX, double mouseY) {
        return this.scrolls() && mouseX >= this.x + this.width - Kit.BAR - 2 && mouseX < this.x + this.width
                && mouseY >= this.listTop && mouseY < this.listTop + this.listHeight;
    }

    /** The place in {@link #shown} of the line under a point, or -1 */
    private int lineAt(double mouseX, double mouseY) {
        if (mouseX < this.x || mouseX >= this.x + this.width || mouseY < this.listTop || mouseY >= this.listTop + this.listHeight) {
            return -1;
        }
        int place = (int) Math.floor((mouseY - this.listTop + this.scroll) / LINE);
        return place >= 0 && place < this.shown.size() ? place : -1;
    }

    // ------------------------------------------------------------------ Drawing

    void draw(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY, int screenWidth, int screenHeight) {
        this.fit(font, screenWidth, screenHeight);
        if (this.barHeld) {
            if (Kit.mouseDown()) {
                this.scroll = Kit.scrollFor(mouseY - this.barGrab, this.listTop, this.listHeight, this.content());
            } else {
                this.barHeld = false;
            }
        } else if (mouseX != this.lastMouseX || mouseY != this.lastMouseY) {
            // Only a mouse that moves takes the highlight, or it would snatch it back from the arrow keys
            int place = this.lineAt(mouseX, mouseY);
            if (place >= 0 && !this.overBar(mouseX, mouseY) && this.items.get(this.shown.get(place)).enabled()) {
                this.highlight = place;
            }
        }
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;

        // It unrolls from the edge it hangs by
        float opened = Kit.ease((Util.getMillis() - this.openedAt) / (float) OPENING_MILLIS);
        int visible = Math.max(1, Math.round(this.height * opened));
        int top = this.above ? this.y + this.height - visible : this.y;
        graphics.pose().pushMatrix();
        // Above the panels and whatever they have drawn, being drawn after them
        try {
            Kit.shadow(graphics, this.x, top, this.x + this.width, top + visible, 4);
            graphics.enableScissor(this.x, top, this.x + this.width, top + visible);
            try {
                this.drawLines(graphics, font, mouseX, mouseY);
            } finally {
                graphics.disableScissor();
            }
            graphics.outline(this.x, top, this.width, visible, Controls.BRASS_BRIGHT);
        } finally {
            graphics.pose().popMatrix();
        }
    }

    private void drawLines(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        graphics.fill(this.x, this.y, this.x + this.width, this.y + this.height, 0xFF21160A);
        int right = this.x + this.width - 1 - (this.scrolls() ? Kit.BAR + 1 : 0);
        if (!this.query.isEmpty()) {
            // What has been typed so far, with a caret after it
            int line = this.y + EDGE;
            graphics.fill(this.x + 1, line, this.x + this.width - 1, line + LINE - 1, Controls.FIELD);
            Kit.icon(graphics, Kit.Icon.SEARCH, this.x + 7, line + LINE / 2 - 1, Controls.PARCHMENT_DIM);
            String typed = Kit.clip(font, this.query, this.width - GUTTER - 8);
            Kit.text(graphics, font, typed, this.x + GUTTER, line + 3, Controls.PARCHMENT);
            if ((Util.getMillis() / 400) % 2 == 0) {
                int caret = this.x + GUTTER + font.width(typed) + 1;
                graphics.fill(caret, line + 2, caret + 1, line + LINE - 3, Controls.PARCHMENT);
            }
            graphics.fill(this.x + 1, line + LINE - 1, this.x + this.width - 1, line + LINE, Controls.BRASS_DIM);
        }
        graphics.enableScissor(this.x + 1, this.listTop, this.x + this.width - 1, this.listTop + this.listHeight);
        try {
            if (this.shown.isEmpty()) {
                Kit.text(graphics, font, Kit.clip(font, Component.translatable("dailies.ui.menu.no_match").getString(), this.width - GUTTER - 4),
                        this.x + GUTTER, this.listTop + 3, Controls.PARCHMENT_FAINT);
            }
            int line = this.listTop - this.scroll;
            for (int place = 0; place < this.shown.size(); place++, line += LINE) {
                if (line + LINE <= this.listTop || line >= this.listTop + this.listHeight) {
                    continue;
                }
                Item item = this.items.get(this.shown.get(place));
                if (place == this.highlight && item.enabled()) {
                    graphics.fill(this.x + 1, line, right, line + LINE, Controls.HIGHLIGHT);
                    graphics.fill(this.x + 1, line, this.x + 2, line + LINE, Controls.BRASS_BRIGHT);
                }
                if (item.marked()) {
                    Kit.icon(graphics, Kit.Icon.TICK, this.x + 7, line + LINE / 2, Controls.BRASS_BRIGHT);
                }
                int textLeft = this.x + GUTTER;
                if (item.swatch() >= 0) {
                    graphics.fill(textLeft, line + 3, textLeft + 8, line + LINE - 3, 0xFF000000 | (item.swatch() & 0xFFFFFF));
                    graphics.outline(textLeft, line + 3, 8, LINE - 6, Controls.BRASS_DIM);
                    textLeft += 12;
                }
                int colour = !item.enabled() ? Controls.PARCHMENT_FAINT : item.marked() ? Controls.BRASS_BRIGHT : Controls.PARCHMENT;
                Kit.text(graphics, font, Kit.clip(font, item.label().getString(), right - textLeft - 3), textLeft, line + 3, colour);
            }
        } finally {
            graphics.disableScissor();
        }
        if (this.scrolls()) {
            Kit.scrollBar(graphics, this.x + this.width - Kit.BAR - 1, this.listTop, this.listHeight, this.content(), this.scroll,
                    this.barHeld || this.overBar(mouseX, mouseY));
        }
    }

    // ------------------------------------------------------------------ The mouse

    boolean over(double mouseX, double mouseY) {
        return mouseX >= this.x && mouseX < this.x + this.width && mouseY >= this.y && mouseY < this.y + this.height;
    }

    /** A click while the menu is open: on a line it does that line, and anywhere outside it closes the menu and no more */
    void press(double mouseX, double mouseY) {
        if (!this.over(mouseX, mouseY)) {
            close();
            return;
        }
        if (this.overBar(mouseX, mouseY)) {
            // The bar follows the mouse until the button comes up; see draw
            int[] thumb = Kit.thumb(this.listTop, this.listHeight, this.content(), this.scroll);
            boolean onThumb = mouseY >= thumb[0] && mouseY < thumb[0] + thumb[1];
            this.barGrab = onThumb ? (int) mouseY - thumb[0] : thumb[1] / 2;
            this.barHeld = true;
            this.scroll = Kit.scrollFor((int) mouseY - this.barGrab, this.listTop, this.listHeight, this.content());
            return;
        }
        int place = this.lineAt(mouseX, mouseY);
        if (place >= 0) {
            this.choose(place);
        }
    }

    void wheel(double amount) {
        this.scroll = Math.max(0, Math.min(this.scroll - (int) Math.round(amount * LINE * 2), Math.max(0, this.content() - this.listHeight)));
        // The line under the mouse is another one now
        this.lastMouseX = Integer.MIN_VALUE;
    }

    private void choose(int place) {
        if (place < 0 || place >= this.shown.size()) {
            return;
        }
        Item item = this.items.get(this.shown.get(place));
        if (!item.enabled()) {
            return;
        }
        close();
        Kit.click();
        EditorState.guard(item.action());
    }

    /**
     * Chooses a line by its place in the list as it was given, as a click on it would.
     *
     * @return whether there was such a line and it could be chosen
     */
    boolean pick(int index) {
        if (index < 0 || index >= this.items.size() || !this.items.get(index).enabled()) {
            return false;
        }
        Item item = this.items.get(index);
        close();
        EditorState.guard(item.action());
        return true;
    }

    // ------------------------------------------------------------------ The keys

    private void move(int by) {
        if (this.shown.isEmpty()) {
            this.highlight = -1;
            return;
        }
        int count = this.shown.size();
        int at = this.highlight;
        for (int tries = 0; tries < count; tries++) {
            at = at < 0 ? (by > 0 ? 0 : count - 1) : Math.floorMod(at + (by > 0 ? 1 : -1), count);
            if (this.items.get(this.shown.get(at)).enabled()) {
                this.highlight = at;
                this.reveal = true;
                return;
            }
        }
    }

    private void moveTo(int place, int searching) {
        this.highlight = -1;
        int count = this.shown.size();
        for (int at = Math.max(0, Math.min(count - 1, place)); at >= 0 && at < count; at += searching) {
            if (this.items.get(this.shown.get(at)).enabled()) {
                this.highlight = at;
                this.reveal = true;
                return;
            }
        }
    }

    /** A key went down while the menu is open. Every key belongs to the menu then */
    void key(int keyCode, int modifiers) {
        int page = Math.max(1, this.listHeight / LINE - 1);
        switch (keyCode) {
            case InputConstants.KEY_ESCAPE, InputConstants.KEY_TAB -> close();
            case InputConstants.KEY_DOWN -> this.move(1);
            case InputConstants.KEY_UP -> this.move(-1);
            case InputConstants.KEY_HOME -> this.moveTo(0, 1);
            case InputConstants.KEY_END -> this.moveTo(this.shown.size() - 1, -1);
            case InputConstants.KEY_PAGEDOWN -> this.moveTo(Math.max(0, this.highlight) + page, -1);
            case InputConstants.KEY_PAGEUP -> this.moveTo(Math.max(0, this.highlight) - page, 1);
            case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
                if (this.highlight >= 0) {
                    this.choose(this.highlight);
                }
            }
            case InputConstants.KEY_BACKSPACE -> {
                if (!this.query.isEmpty()) {
                    this.find(this.query.substring(0, this.query.length() - 1));
                }
            }
            default -> {
            }
        }
    }

    /** A letter was typed: the list narrows to the lines that fit everything typed so far */
    void typed(int codepoint) {
        if (codepoint < ' ' || codepoint == 127 || (codepoint == ' ' && this.query.isEmpty())) {
            return;
        }
        if (this.query.length() < 40) {
            this.find(this.query + Character.toString(codepoint));
        }
    }

    /** Narrows the list to the lines that fit these words, the best first, and puts the highlight on the first */
    void find(String words) {
        this.query = words == null ? "" : words;
        this.shown = Find.filter(this.labels, this.query);
        this.scroll = 0;
        this.highlight = -1;
        if (this.query.isEmpty()) {
            for (int place = 0; place < this.shown.size(); place++) {
                if (this.items.get(this.shown.get(place)).marked()) {
                    this.highlight = place;
                    this.reveal = true;
                    return;
                }
            }
        }
        this.moveTo(0, 1);
    }

}
