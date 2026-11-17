package comedymonkey.dailies.ui;

import comedymonkey.dailies.Host;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * What every Dailies screen other than the editor is built on: one walnut plate in the middle of the window with the
 * kit's controls on it. It hands the mouse and the keys to the controls, the open menu and the box being typed into,
 * moves the keys from control to control with Tab, and shows explanations in the house colours.
 * <p>
 * A screen says what is on it in {@link #build}, where it all goes in {@link #arrange}, and draws its own words in
 * {@link #paint}.
 */
public abstract class KitScreen extends Screen {

    protected final @Nullable Screen parent;
    /** The controls that stay where they are; those that scroll are in {@link #forms} */
    final List<Gadget> fixed = new ArrayList<>();
    final List<Form> forms = new ArrayList<>();
    private @Nullable Gadget held;
    private boolean failureLogged = false;
    /** Where the mouse is taken to be, for a picture of a control with the mouse on it; -1 for where it really is */
    private int pointX = -1;
    private int pointY = -1;
    /** The plate, set by {@link #arrange} */
    int left;
    int top;
    int right;
    int bottom;

    protected KitScreen(Component title, @Nullable Screen parent) {
        super(title);
        this.parent = parent;
    }

    /** Makes the controls. Called whenever the screen is put up or the window changes size */
    abstract void build();

    /** Says where the plate is and puts every control and form in its place. Called before each frame is drawn */
    abstract void arrange(Font font);

    /** Draws what is on the plate besides the controls: its title, its words */
    void paint(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
    }

    /** Whether the world behind is blurred and dimmed. A screen a recording keeps playing behind leaves it alone */
    boolean dimsWorld() {
        return true;
    }

    /** An explanation for a point that no control answers for */
    @Nullable Component tipElsewhere(double mouseX, double mouseY) {
        return null;
    }

    /** A key nothing else wanted. Returns whether this screen did */
    boolean keyElsewhere(int keyCode, int modifiers) {
        return false;
    }

    /** A letter typed while no box is being typed into */
    void typedElsewhere(char character, int modifiers) {
    }

    /** The plate set in the middle of the window, as large as asked where there is room */
    void centre(int wantedWidth, int wantedHeight) {
        int width = Math.max(40, Math.min(wantedWidth, this.width - 16));
        int height = Math.max(40, Math.min(wantedHeight, this.height - 16));
        this.left = (this.width - width) / 2;
        this.top = (this.height - height) / 2;
        this.right = this.left + width;
        this.bottom = this.top + height;
    }

    /** The title at the top of the plate with a rule under it. Returns where what follows may start */
    int heading(GuiGraphics graphics, Font font, @Nullable Component under) {
        int line = this.top + 8;
        Kit.text(graphics, font, Kit.clip(font, this.title.getString(), this.right - this.left - 16), this.left + 8, line, Controls.BRASS_BRIGHT);
        line += 12;
        if (under != null) {
            for (net.minecraft.util.FormattedCharSequence part : font.split(under, Math.max(40, this.right - this.left - 16))) {
                graphics.drawString(font, part, this.left + 8, line, Controls.PARCHMENT_DIM, false);
                line += 10;
            }
            line += 2;
        }
        Kit.rule(graphics, this.left + 8, line, this.right - 8);
        return line + 5;
    }

    /** How high {@link #heading} comes out with these words under the title */
    int headingHeight(Font font, @Nullable Component under) {
        int lines = under == null ? 0 : font.split(under, Math.max(40, this.right - this.left - 16)).size();
        return 8 + 12 + (lines > 0 ? lines * 10 + 2 : 0) + 5;
    }

    /** Buttons in a row along the foot of the plate, set to the right, each as wide as its words need and no narrower than 60 */
    void foot(Font font, Gadget... buttons) {
        int x = this.right - 8;
        for (int i = buttons.length - 1; i >= 0; i--) {
            Gadget button = buttons[i];
            int wide = Math.max(60, button.wantedWidth(font) + 8);
            x -= wide;
            button.clip(Integer.MIN_VALUE, Integer.MAX_VALUE);
            button.place(font, x, this.bottom - 8 - Kit.ROW - 2, wide, Kit.ROW + 2);
            x -= Kit.GAP * 2;
        }
    }

    /** Buttons along the foot of the plate, set to the left: what is done to the thing chosen, apart from the way out */
    void footLeft(Font font, Gadget... buttons) {
        int x = this.left + 8;
        for (Gadget button : buttons) {
            int wide = Math.max(52, button.wantedWidth(font) + 8);
            button.clip(Integer.MIN_VALUE, Integer.MAX_VALUE);
            button.place(font, x, this.bottom - 8 - Kit.ROW - 2, wide, Kit.ROW + 2);
            x += wide + Kit.GAP * 2;
        }
    }

    // ------------------------------------------------------------------ The screen as the game sees it

    @Override
    protected void init() {
        Entry.drop();
        Menu.close();
        Gadget.focus = null;
        this.held = null;
        this.fixed.clear();
        this.forms.clear();
        try {
            this.build();
        } catch (RuntimeException | LinkageError e) {
            Host.LOGGER.error("Dailies: a screen could not be built", e);
            Notices.show(Component.translatable("dailies.ui.notice.went_wrong"));
        }
    }

    @Override
    public void removed() {
        EditorState.guard(Entry::blur);
        Menu.close();
        Gadget.focus = null;
        this.held = null;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (this.dimsWorld()) {
            super.renderBackground(graphics, mouseX, mouseY, partialTick);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int realMouseX, int realMouseY, float partialTick) {
        int mouseX = this.pointX >= 0 ? this.pointX : realMouseX;
        int mouseY = this.pointX >= 0 ? this.pointY : realMouseY;
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        try {
            Font font = this.font;
            this.arrange(font);
            Kit.plate(graphics, this.left, this.top, this.right, this.bottom);
            Menu menu = Menu.open;
            int pointerX = menu != null ? Integer.MIN_VALUE / 2 : mouseX;
            int pointerY = menu != null ? Integer.MIN_VALUE / 2 : mouseY;
            this.paint(graphics, font, pointerX, pointerY);
            for (Gadget gadget : this.fixed) {
                if (gadget.height > 0) {
                    gadget.draw(graphics, font, pointerX, pointerY);
                }
            }
            for (Form form : this.forms) {
                form.draw(graphics, font, pointerX, pointerY);
            }
            Notices.drawOver(graphics, font);
            if (menu != null) {
                menu.draw(graphics, font, mouseX, mouseY, this.width, this.height);
            } else if (this.held == null) {
                Kit.tooltip(graphics, font, this.tipAt(mouseX, mouseY), mouseX, mouseY, this.width, this.height);
            }
        } catch (RuntimeException | LinkageError e) {
            if (!this.failureLogged) {
                this.failureLogged = true;
                Host.LOGGER.error("Dailies: a screen could not be drawn", e);
            }
        }
    }

    private @Nullable Component tipAt(double mouseX, double mouseY) {
        for (Gadget gadget : this.fixed) {
            if (gadget.height > 0) {
                Component words = gadget.tipAt(mouseX, mouseY);
                if (words != null) {
                    return words;
                }
            }
        }
        for (Form form : this.forms) {
            Component words = form.tipAt(mouseX, mouseY);
            if (words != null) {
                return words;
            }
        }
        return this.tipElsewhere(mouseX, mouseY);
    }

    // ------------------------------------------------------------------ The mouse

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        EditorState.guard(() -> this.pressed(mouseX, mouseY, button));
        return true;
    }

    private void pressed(double mouseX, double mouseY, int button) {
        Menu menu = Menu.open;
        if (menu != null) {
            // A click outside the list closes it and is spent
            menu.press(mouseX, mouseY);
            return;
        }
        Entry typing = Entry.focused;
        if (typing != null && !typing.over(mouseX, mouseY)) {
            // What was typed is handed over before the click does anything, so the click acts on the new value
            Entry.blur();
        }
        // The ring is for the keys; the mouse says where it is by itself
        Gadget.focus = null;
        this.held = null;
        for (Gadget gadget : this.fixed) {
            if (gadget.height > 0) {
                Gadget hit = gadget.press(mouseX, mouseY, button);
                if (hit != null) {
                    this.held = hit;
                    return;
                }
            }
        }
        for (Form form : this.forms) {
            Gadget hit = form.press(mouseX, mouseY, button);
            if (hit != null) {
                this.held = hit;
                return;
            }
        }
        this.clickedElsewhere(mouseX, mouseY, button);
    }

    void pointAt(int x, int y) {
        this.pointX = x;
        this.pointY = y;
    }

    /** A click no control took */
    void clickedElsewhere(double mouseX, double mouseY, int button) {
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        Gadget was = this.held;
        this.held = null;
        if (was != null) {
            EditorState.guard(() -> was.release(mouseX, mouseY));
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        Gadget now = this.held;
        if (button == 0 && now != null) {
            EditorState.guard(() -> now.drag(mouseX, mouseY));
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        Menu menu = Menu.open;
        if (menu != null) {
            menu.wheel(scrollY);
            return true;
        }
        for (Form form : this.forms) {
            if (form.wheel(mouseX, mouseY, scrollY)) {
                return true;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ The keys

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        EditorState.guard(() -> this.key(keyCode, scanCode, modifiers));
        return true;
    }

    private void key(int keyCode, int scanCode, int modifiers) {
        Menu menu = Menu.open;
        if (menu != null) {
            menu.key(keyCode, modifiers);
            return;
        }
        boolean back = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
        if (Entry.typing()) {
            Entry box = Entry.focused;
            if (keyCode == GLFW.GLFW_KEY_ESCAPE && !box.dirty()) {
                // Nothing typed would be lost, so Escape means what it means everywhere else
                Entry.drop();
                this.onClose();
            } else if (keyCode == GLFW.GLFW_KEY_TAB) {
                box.key(keyCode, scanCode, modifiers);
                if (Entry.focused == null) {
                    this.moveFocus(box, back);
                }
            } else {
                box.key(keyCode, scanCode, modifiers);
            }
            return;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
        } else if (keyCode == GLFW.GLFW_KEY_TAB) {
            this.moveFocus(Gadget.focus, back);
        } else if (Gadget.focus != null && Gadget.focus.key(keyCode, modifiers)) {
            return;
        } else {
            this.keyElsewhere(keyCode, modifiers);
        }
    }

    @Override
    public boolean charTyped(char character, int modifiers) {
        Entry typing = Entry.focused;
        if (typing != null) {
            EditorState.guard(() -> typing.typed(character, modifiers));
        } else if (Gadget.focus == null || character != ' ') {
            EditorState.guard(() -> this.typedElsewhere(character, modifiers));
        }
        return true;
    }

    /** The controls in the order the Tab key visits them: across the plate's own, then down each list */
    List<Gadget> order() {
        List<Gadget> order = new ArrayList<>();
        for (Gadget gadget : this.fixed) {
            gadget.collect(order);
        }
        for (Form form : this.forms) {
            form.collect(order);
        }
        return order;
    }

    private void moveFocus(@Nullable Gadget from, boolean back) {
        List<Gadget> order = this.order();
        if (order.isEmpty()) {
            Gadget.focus = null;
            return;
        }
        int at = from == null ? -1 : order.indexOf(from);
        int next = at < 0 ? (back ? order.size() - 1 : 0) : Math.floorMod(at + (back ? -1 : 1), order.size());
        this.focusOn(order.get(next));
    }

    /** Puts the keys on a control and scrolls it into view */
    void focusOn(Gadget control) {
        if (control instanceof Entry box) {
            box.focus();
        } else {
            Entry.blur();
            Gadget.focus = control;
        }
        for (Form form : this.forms) {
            List<Gadget> inside = new ArrayList<>();
            form.collect(inside);
            if (inside.contains(control)) {
                form.reveal(control);
            }
        }
    }

}
