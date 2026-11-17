package comedymonkey.dailies.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.List;
import java.util.Locale;

/**
 * The drawing the editor's panels and controls share: walnut panels with a brass edge, button faces, small pictures
 * made of a few filled rectangles, and the words for numbers. Everything the editor shows goes through here, so it
 * all looks like one thing.
 */
final class Kit {

    /** The height of one row of controls */
    static final int ROW = 14;
    private static final String KEYS = "dailies.editor.";

    /** The small pictures drawn on buttons in place of words */
    enum Icon {
        TO_START, STEP_BACK, PLAY, PAUSE, STEP_FORWARD, TO_END, MINUS, PLUS, UP, DOWN, DOTS, CROSS, FOLD_DOWN, FOLD_UP, FOLD_LEFT, FOLD_RIGHT
    }

    private Kit() {
    }

    /** A label of the editor, by the last part of its key */
    static Component t(String key, Object... arguments) {
        return Component.translatable(KEYS + key, arguments);
    }

    /** A label by its key when the language file has it, else the given words */
    static Component named(String key, String otherwise) {
        return I18n.exists(KEYS + key) ? Component.translatable(KEYS + key) : Component.literal(otherwise);
    }

    /** some_name as "Some name" */
    static String pretty(String name) {
        String spaced = name.replace('_', ' ').trim();
        return spaced.isEmpty() ? spaced : spaced.substring(0, 1).toUpperCase(Locale.ROOT) + spaced.substring(1);
    }

    static void panel(GuiGraphics graphics, int left, int top, int right, int bottom) {
        if (right <= left || bottom <= top) {
            return;
        }
        graphics.fill(left, top, right, bottom, Controls.PANEL);
        graphics.renderOutline(left, top, right - left, bottom - top, Controls.BRASS);
    }

    /** The face of a button: darker when it cannot be used, brighter under the mouse, brass when it is switched on */
    static void face(GuiGraphics graphics, int x, int y, int width, int height, boolean hovered, boolean lit, boolean enabled) {
        int fill = lit ? 0xFF6E5522 : hovered && enabled ? 0xFF4A3520 : 0xFF38281A;
        graphics.fill(x, y, x + width, y + height, fill);
        graphics.renderOutline(x, y, width, height, lit ? Controls.BRASS_BRIGHT : hovered && enabled ? Controls.BRASS_BRIGHT : Controls.BRASS);
    }

    /** The text cut short with dots when it is wider than the room for it */
    static String clip(Font font, String text, int width) {
        if (width <= 0) {
            return "";
        }
        if (font.width(text) <= width) {
            return text;
        }
        int dots = font.width("...");
        if (width <= dots) {
            return font.plainSubstrByWidth(text, width);
        }
        return font.plainSubstrByWidth(text, width - dots) + "...";
    }

    static void text(GuiGraphics graphics, Font font, String text, int x, int y, int colour) {
        graphics.drawString(font, text, x, y, colour, false);
    }

    static void centred(GuiGraphics graphics, Font font, String text, int x, int y, int width, int colour) {
        String shown = clip(font, text, width - 2);
        graphics.drawString(font, shown, x + (width - font.width(shown)) / 2, y, colour, false);
    }

    /** A number without needless digits: 6, 0.35, 104.5 */
    static String number(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "";
        }
        if (value == Math.rint(value) && Math.abs(value) < 1.0e12) {
            return Long.toString((long) value);
        }
        String text = String.format(Locale.ROOT, "%.3f", value);
        text = text.replaceAll("0+$", "");
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }

    /** Seconds of video as minutes, seconds and tenths: 1:05.3 */
    static String seconds(double seconds) {
        double safe = Math.max(0.0, seconds);
        long tenths = Math.round(safe * 10.0);
        return String.format(Locale.ROOT, "%d:%02d.%d", tenths / 600, (tenths / 10) % 60, tenths % 10);
    }

    static void click() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    /** A diamond, the mark of a keyframe, reaching {@code reach} pixels from its middle */
    static void diamond(GuiGraphics graphics, int centreX, int centreY, int reach, int colour) {
        for (int i = -reach; i <= reach; i++) {
            int half = reach - Math.abs(i);
            graphics.fill(centreX + i, centreY - half, centreX + i + 1, centreY + half + 1, colour);
        }
    }

    static void icon(GuiGraphics graphics, Icon icon, int centreX, int centreY, int colour) {
        switch (icon) {
            case PLAY -> right(graphics, centreX - 1, centreY, colour);
            case PAUSE -> {
                graphics.fill(centreX - 3, centreY - 3, centreX - 1, centreY + 4, colour);
                graphics.fill(centreX + 1, centreY - 3, centreX + 3, centreY + 4, colour);
            }
            case STEP_FORWARD -> {
                right(graphics, centreX - 2, centreY, colour);
                graphics.fill(centreX + 3, centreY - 3, centreX + 4, centreY + 4, colour);
            }
            case STEP_BACK -> {
                left(graphics, centreX + 1, centreY, colour);
                graphics.fill(centreX - 4, centreY - 3, centreX - 3, centreY + 4, colour);
            }
            case TO_END -> {
                right(graphics, centreX - 4, centreY, colour);
                right(graphics, centreX, centreY, colour);
                graphics.fill(centreX + 4, centreY - 3, centreX + 5, centreY + 4, colour);
            }
            case TO_START -> {
                left(graphics, centreX + 3, centreY, colour);
                left(graphics, centreX - 1, centreY, colour);
                graphics.fill(centreX - 5, centreY - 3, centreX - 4, centreY + 4, colour);
            }
            case MINUS -> graphics.fill(centreX - 3, centreY, centreX + 4, centreY + 1, colour);
            case PLUS -> {
                graphics.fill(centreX - 3, centreY, centreX + 4, centreY + 1, colour);
                graphics.fill(centreX, centreY - 3, centreX + 1, centreY + 4, colour);
            }
            case UP, FOLD_UP -> {
                for (int i = 0; i < 4; i++) {
                    graphics.fill(centreX - i, centreY - 2 + i, centreX + i + 1, centreY - 1 + i, colour);
                }
            }
            case DOWN, FOLD_DOWN -> {
                for (int i = 0; i < 4; i++) {
                    graphics.fill(centreX - i, centreY + 2 - i, centreX + i + 1, centreY + 3 - i, colour);
                }
            }
            case FOLD_RIGHT -> right(graphics, centreX - 1, centreY, colour);
            case FOLD_LEFT -> left(graphics, centreX, centreY, colour);
            case DOTS -> {
                graphics.fill(centreX - 4, centreY, centreX - 2, centreY + 2, colour);
                graphics.fill(centreX - 1, centreY, centreX + 1, centreY + 2, colour);
                graphics.fill(centreX + 2, centreY, centreX + 4, centreY + 2, colour);
            }
            case CROSS -> {
                for (int i = -3; i <= 3; i++) {
                    graphics.fill(centreX + i, centreY + i, centreX + i + 1, centreY + i + 1, colour);
                    graphics.fill(centreX + i, centreY - i, centreX + i + 1, centreY - i + 1, colour);
                }
            }
            default -> {
            }
        }
    }

    /** An arrow head pointing right, four pixels wide, its flat side at {@code x} */
    private static void right(GuiGraphics graphics, int x, int centreY, int colour) {
        for (int i = 0; i < 4; i++) {
            graphics.fill(x + i, centreY - 3 + i, x + i + 1, centreY + 4 - i, colour);
        }
    }

    /** An arrow head pointing left, four pixels wide, its flat side at {@code x} */
    private static void left(GuiGraphics graphics, int x, int centreY, int colour) {
        for (int i = 0; i < 4; i++) {
            graphics.fill(x - i, centreY - 3 + i, x - i + 1, centreY + 4 - i, colour);
        }
    }

    /**
     * Sets controls out in a line from the left, starting a new line whenever the next one does not fit.
     *
     * @return the bottom of the last line
     */
    static int flow(Font font, List<Gadget> gadgets, int left, int top, int right, int rowHeight) {
        int x = left;
        int y = top;
        for (Gadget gadget : gadgets) {
            int width = Math.min(gadget.wantedWidth(font), Math.max(10, right - left));
            if (x > left && x + width > right) {
                x = left;
                y += rowHeight + 2;
            }
            gadget.clip(Integer.MIN_VALUE, Integer.MAX_VALUE);
            gadget.place(font, x, y, width, rowHeight);
            x += width + 2;
        }
        return y + rowHeight;
    }

}
