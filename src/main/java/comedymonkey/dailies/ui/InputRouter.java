package comedymonkey.dailies.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * How the mouse and the keys reach the kit's controls, the same in the editor and in every other Dailies screen: an
 * open menu takes a click first, a box being typed into hands over what it holds before a click lands elsewhere, the
 * control a click took hold of follows the mouse until the button comes up, and Tab moves the keys from control to
 * control, scrolling a form so the one they land on is in sight. Each screen says which controls it has and in what
 * order; this does the rest. Client thread.
 */
final class InputRouter {

    /** The control that follows the mouse until the button comes up, or null */
    private @Nullable Gadget held;

    /** The screen is put up: nothing is typed into, no menu is open, no control has the ring and none is held */
    void begin() {
        Entry.drop();
        Menu.close();
        Gadget.focus = null;
        this.held = null;
    }

    /** The screen is taken away: what was typed is handed over first, then the same as {@link #begin} */
    void end() {
        EditorState.guard(Entry::blur);
        Menu.close();
        Gadget.focus = null;
        this.held = null;
    }

    @Nullable Gadget held() {
        return this.held;
    }

    /** The control a click took hold of, or null when the click took none */
    void hold(@Nullable Gadget control) {
        this.held = control;
    }

    /** The button came up: the control held, which is let go of now, or null when none was */
    @Nullable Gadget letGo() {
        Gadget was = this.held;
        this.held = null;
        return was;
    }

    /** The mouse moved with the left button down: the control held follows it */
    void drag(double mouseX, double mouseY) {
        Gadget now = this.held;
        if (now != null) {
            now.drag(mouseX, mouseY);
        }
    }

    // ------------------------------------------------------------------ The mouse

    /**
     * What every click does before it reaches a control. An open menu takes it; otherwise a box being typed into is
     * left, what was typed handed over, so the click acts on the new value, and the ring leaves the control it is on,
     * since the mouse says where it is by itself.
     *
     * @return whether the click was spent on the open menu, a click outside it closing it
     */
    static boolean beforeClick(double mouseX, double mouseY) {
        Menu menu = Menu.open;
        if (menu != null) {
            menu.press(mouseX, mouseY);
            return true;
        }
        Entry typing = Entry.focused;
        if (typing != null && !typing.over(mouseX, mouseY)) {
            Entry.blur();
        }
        Gadget.focus = null;
        return false;
    }

    /** The control among these a click takes hold of, the first to answer; those taking no room are passed over */
    static @Nullable Gadget pressAmong(Iterable<? extends Gadget> gadgets, double mouseX, double mouseY, int button) {
        for (Gadget gadget : gadgets) {
            if (gadget.height > 0) {
                Gadget hit = gadget.press(mouseX, mouseY, button);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    /** The explanation among these for the point under the mouse, the first to give one; those taking no room are passed over */
    static @Nullable Component tipAmong(Iterable<? extends Gadget> gadgets, double mouseX, double mouseY) {
        for (Gadget gadget : gadgets) {
            if (gadget.height > 0) {
                Component words = gadget.tipAt(mouseX, mouseY);
                if (words != null) {
                    return words;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ The keys

    /** Whether Shift was held, which sends Tab the other way */
    static boolean back(int modifiers) {
        return (modifiers & InputConstants.MOD_SHIFT) != 0;
    }

    /**
     * A key for the box being typed into, which every key belongs to then.
     *
     * @return whether it was Tab and the box handed over what it holds, so the keys move on to the next control
     */
    static boolean typeKey(Entry box, int keyCode, int scanCode, int modifiers) {
        box.key(keyCode, scanCode, modifiers);
        return keyCode == InputConstants.KEY_TAB && Entry.focused == null;
    }

    /**
     * Moves the keys to the control after this one in the order Tab visits them, or before it with Shift.
     *
     * @param order the controls Tab visits, as the screen has them now
     * @param from  the control the keys are on, or null when none
     * @param forms the screen's forms, one of which is scrolled so the control moved to is in sight
     */
    static void moveFocus(List<Gadget> order, @Nullable Gadget from, boolean back, Iterable<Form> forms) {
        int next = FocusStep.next(order.size(), from == null ? -1 : order.indexOf(from), back);
        if (next < 0) {
            Gadget.focus = null;
            return;
        }
        focusOn(order.get(next), forms);
    }

    /** Puts the keys on a control: a box is typed into, anything else gets the ring. A form it is in scrolls it into sight */
    static void focusOn(Gadget control, Iterable<Form> forms) {
        if (control instanceof Entry box) {
            box.focus();
        } else {
            Entry.blur();
            Gadget.focus = control;
        }
        for (Form form : forms) {
            List<Gadget> inside = new ArrayList<>();
            form.collect(inside);
            if (inside.contains(control)) {
                form.reveal(control);
            }
        }
    }

}
