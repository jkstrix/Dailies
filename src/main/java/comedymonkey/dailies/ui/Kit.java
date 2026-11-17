package comedymonkey.dailies.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;

/**
 * The drawing every Dailies screen shares: walnut panels with a brass edge and a soft shadow, button faces, boxes to
 * type in, scroll bars, explanations, small pictures made of a few filled rectangles, and the words for numbers.
 * Everything Dailies shows goes through here, so it all looks like one thing.
 * <p>
 * Sizes sit on a grid of two: a control is {@link #ROW} high, controls are {@link #GAP} apart, and what is inside a
 * panel keeps {@link #PAD} from its edge.
 */
final class Kit {

    /** The height of one row of controls */
    static final int ROW = 14;
    /** The space between two controls */
    static final int GAP = 2;
    /** The space between the edge of a panel and what is in it */
    static final int PAD = 4;
    /** The width of a scroll bar */
    static final int BAR = 6;
    private static final String KEYS = "dailies.editor.";

    /** The small pictures drawn on buttons in place of words */
    enum Icon {
        TO_START, STEP_BACK, PLAY, PAUSE, STEP_FORWARD, TO_END, MINUS, PLUS, UP, DOWN, DOTS, CROSS, FOLD_DOWN, FOLD_UP, FOLD_LEFT, FOLD_RIGHT,
        CHEVRON, RESET, SEARCH, TICK, GEAR
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

    // ------------------------------------------------------------------ Panels

    /**
     * A panel docked to an edge of the screen. Its shadow falls on the world beside and below it, and above it only
     * when it stands on the bottom edge, so that panels set against one another do not dirty each other's edges.
     */
    static void panel(GuiGraphics graphics, int left, int top, int right, int bottom) {
        if (right <= left || bottom <= top) {
            return;
        }
        int screenWidth = graphics.guiWidth();
        int screenHeight = graphics.guiHeight();
        boolean standing = bottom >= screenHeight;
        for (int i = 1; i <= 3; i++) {
            int shade = (0x40 >> (i - 1)) << 24;
            if (!standing) {
                graphics.fill(left, bottom + i - 1, right, bottom + i, shade);
            } else if (top > 0) {
                graphics.fill(left, top - i, right, top - i + 1, shade);
            }
            if (left > 0) {
                graphics.fill(left - i, top, left - i + 1, bottom, shade);
            }
            if (right < screenWidth) {
                graphics.fill(right + i - 1, top, right + i, bottom, shade);
            }
        }
        graphics.fill(left, top, right, bottom, Controls.PANEL);
        graphics.renderOutline(left, top, right - left, bottom - top, Controls.BRASS);
        // A thin light along the top, inside the edge: the panel has a thickness
        graphics.fill(left + 1, top + 1, right - 1, top + 2, 0x14FFFFFF);
    }

    /** A panel that floats in the middle of the screen: a dialogue, a list, the settings. Its shadow is all round it */
    static void plate(GuiGraphics graphics, int left, int top, int right, int bottom) {
        if (right <= left || bottom <= top) {
            return;
        }
        shadow(graphics, left, top, right, bottom, 5);
        graphics.fill(left, top, right, bottom, Controls.PLATE);
        graphics.renderOutline(left, top, right - left, bottom - top, Controls.BRASS);
        graphics.fill(left + 1, top + 1, right - 1, top + 2, 0x18FFFFFF);
    }

    /** A soft dark edge outside a box, fading over {@code reach} pixels and a little heavier underneath */
    static void shadow(GuiGraphics graphics, int left, int top, int right, int bottom, int reach) {
        for (int i = 1; i <= reach; i++) {
            int shade = (Math.max(6, 0x58 * (reach - i + 1) / (reach * i))) << 24;
            // Four strips round the box, the corners belonging to the top and bottom ones
            graphics.fill(left - i, top - i, right + i, top - i + 1, shade);
            graphics.fill(left - i, bottom + i - 1, right + i, bottom + i, shade);
            graphics.fill(left - i, top - i + 1, left - i + 1, bottom + i - 1, shade);
            graphics.fill(right + i - 1, top - i + 1, right + i, bottom + i - 1, shade);
        }
        graphics.fill(left + 2, bottom + reach, right - 2, bottom + reach + 1, 0x18000000);
    }

    /** A thin brass line under a heading or between two parts of a panel, fading out to the right */
    static void rule(GuiGraphics graphics, int left, int y, int right) {
        if (right <= left) {
            return;
        }
        int solid = left + (right - left) * 2 / 3;
        graphics.fill(left, y, solid, y + 1, Controls.BRASS_DIM);
        int steps = Math.max(1, Math.min(8, right - solid));
        for (int i = 0; i < steps; i++) {
            int from = solid + (right - solid) * i / steps;
            int to = solid + (right - solid) * (i + 1) / steps;
            int alpha = 0xFF * (steps - i) / (steps + 1);
            graphics.fill(from, y, to, y + 1, (alpha << 24) | (Controls.BRASS_DIM & 0xFFFFFF));
        }
    }

    // ------------------------------------------------------------------ Controls

    /** Whether the left mouse button is down now, for drawing a button as held */
    static boolean mouseDown() {
        Minecraft minecraft = Minecraft.getInstance();
        return GLFW.glfwGetMouseButton(minecraft.getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
    }

    /** The face of a button: darker when it cannot be used, brighter under the mouse, brass when it is switched on */
    static void face(GuiGraphics graphics, int x, int y, int width, int height, boolean hovered, boolean lit, boolean enabled) {
        face(graphics, x, y, width, height, hovered, lit, enabled, hovered && enabled && mouseDown(), false);
    }

    /**
     * The face of a button in each of its states.
     *
     * @param pressed whether the mouse button is held down on it
     * @param focused whether the keys act on it: it gets a parchment ring
     */
    static void face(GuiGraphics graphics, int x, int y, int width, int height, boolean hovered, boolean lit, boolean enabled, boolean pressed, boolean focused) {
        if (width <= 0 || height <= 0) {
            return;
        }
        int fill;
        int edge;
        if (!enabled) {
            fill = lit ? 0xFF4A3A1C : 0xFF2C2014;
            edge = Controls.BRASS_DIM;
        } else if (pressed) {
            fill = lit ? 0xFF5E4819 : Controls.FACE_PRESSED;
            edge = Controls.BRASS_BRIGHT;
        } else if (lit) {
            fill = hovered ? 0xFF85682A : Controls.FACE_LIT;
            edge = Controls.BRASS_BRIGHT;
        } else {
            fill = hovered ? Controls.FACE_HOVER : Controls.FACE;
            edge = hovered ? Controls.BRASS_BRIGHT : Controls.BRASS;
        }
        graphics.fill(x, y, x + width, y + height, fill);
        if (enabled && width > 2 && height > 4) {
            // Lit from above: a lighter line under the top edge and a darker one over the bottom edge, swapped while held
            graphics.fill(x + 1, y + 1, x + width - 1, y + 2, pressed ? 0x30000000 : 0x1CFFFFFF);
            graphics.fill(x + 1, y + height - 2, x + width - 1, y + height - 1, pressed ? 0x10FFFFFF : 0x30000000);
        }
        graphics.renderOutline(x, y, width, height, edge);
        if (focused) {
            focusRing(graphics, x, y, width, height);
        }
    }

    /** The ring round the control the keys act on */
    static void focusRing(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.renderOutline(x - 1, y - 1, width + 2, height + 2, Controls.PARCHMENT);
    }

    /** The inside of a box to type in or to slide along: dark, with a shade under its top edge so that it looks sunk */
    static void field(GuiGraphics graphics, int x, int y, int width, int height, int edge) {
        if (width <= 0 || height <= 0) {
            return;
        }
        graphics.fill(x, y, x + width, y + height, Controls.FIELD);
        if (width > 2 && height > 3) {
            graphics.fill(x + 1, y + 1, x + width - 1, y + 2, 0x50000000);
        }
        graphics.renderOutline(x, y, width, height, edge);
    }

    /**
     * A scroll bar down the right of a list.
     *
     * @param content the height of everything in the list
     * @param scroll  how far down the list is scrolled
     * @param bright  whether the mouse is on it or dragging it
     * @return where its thumb is: top and height
     */
    static int[] scrollBar(GuiGraphics graphics, int left, int top, int height, int content, int scroll, boolean bright) {
        int[] thumb = thumb(top, height, content, scroll);
        graphics.fill(left, top, left + BAR, top + height, 0x70000000);
        graphics.fill(left + 1, thumb[0], left + BAR - 1, thumb[0] + thumb[1], bright ? Controls.BRASS_BRIGHT : Controls.BRASS);
        graphics.fill(left + 1, thumb[0], left + BAR - 1, thumb[0] + 1, 0x30FFFFFF);
        return thumb;
    }

    /** Where the thumb of a scroll bar sits: top and height */
    static int[] thumb(int top, int height, int content, int scroll) {
        int length = Math.max(12, Math.min(height, (int) ((long) height * height / Math.max(1, content))));
        int travel = Math.max(0, height - length);
        int most = Math.max(1, content - height);
        return new int[]{top + (int) ((long) travel * Math.max(0, Math.min(most, scroll)) / most), length};
    }

    /** How far a list is scrolled when the top of its bar's thumb is dragged to {@code thumbTop} */
    static int scrollFor(int thumbTop, int top, int height, int content) {
        int length = Math.max(12, Math.min(height, (int) ((long) height * height / Math.max(1, content))));
        int travel = Math.max(1, height - length);
        int most = Math.max(0, content - height);
        return (int) Math.max(0, Math.min(most, Math.round((double) (thumbTop - top) * most / travel)));
    }

    /** From 0 to 1, quick at first and gentle at the end: how a menu opens and a group folds */
    static float ease(float share) {
        float clamped = Math.max(0.0F, Math.min(1.0F, share));
        float left = 1.0F - clamped;
        return 1.0F - left * left * left;
    }

    // ------------------------------------------------------------------ Words

    /** The text cut short with dots when it is wider than the room for it. Never cut in the middle of a letter */
    static String clip(Font font, String text, int width) {
        if (width <= 0) {
            return "";
        }
        if (font.width(text) <= width) {
            return text;
        }
        int dots = font.width("...");
        if (width <= dots) {
            return "";
        }
        return font.plainSubstrByWidth(text, width - dots).stripTrailing() + "...";
    }

    static void text(GuiGraphics graphics, Font font, String text, int x, int y, int colour) {
        graphics.drawString(font, text, x, y, colour, false);
    }

    static void centred(GuiGraphics graphics, Font font, String text, int x, int y, int width, int colour) {
        String shown = clip(font, text, width - 2);
        graphics.drawString(font, shown, x + (width - font.width(shown)) / 2, y, colour, false);
    }

    /**
     * An explanation beside the mouse, in the house colours. It wraps, and it is moved so that all of it is on the
     * screen: to the left of the mouse or above it when there is no room to the right or below.
     */
    static void tooltip(GuiGraphics graphics, Font font, Component words, int mouseX, int mouseY, int screenWidth, int screenHeight) {
        if (words == null || words.getString().isBlank()) {
            return;
        }
        int room = Math.max(60, Math.min(230, screenWidth - 20));
        List<FormattedCharSequence> lines = font.split(words, room);
        if (lines.isEmpty()) {
            return;
        }
        int widest = 0;
        for (FormattedCharSequence line : lines) {
            widest = Math.max(widest, font.width(line));
        }
        int width = widest + 10;
        int height = lines.size() * 10 + 7;
        int x = mouseX + 10;
        int y = mouseY + 14;
        if (x + width > screenWidth - 4) {
            x = mouseX - 8 - width;
        }
        if (y + height > screenHeight - 4) {
            y = mouseY - 6 - height;
        }
        x = Math.max(4, Math.min(x, screenWidth - 4 - width));
        y = Math.max(4, Math.min(y, screenHeight - 4 - height));
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 400.0F);
        try {
            shadow(graphics, x, y, x + width, y + height, 3);
            graphics.fill(x, y, x + width, y + height, 0xFA1C1308);
            graphics.renderOutline(x, y, width, height, Controls.BRASS);
            int line = y + 4;
            for (FormattedCharSequence part : lines) {
                graphics.drawString(font, part, x + 5, line, Controls.PARCHMENT, false);
                line += 10;
            }
        } finally {
            graphics.pose().popPose();
        }
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

    // ------------------------------------------------------------------ Pictures

    /** A diamond, the mark of a keyframe, reaching {@code reach} pixels from its middle */
    static void diamond(GuiGraphics graphics, int centreX, int centreY, int reach, int colour) {
        for (int i = -reach; i <= reach; i++) {
            int half = reach - Math.abs(i);
            graphics.fill(centreX + i, centreY - half, centreX + i + 1, centreY + half + 1, colour);
        }
    }

    private static final String[] CHEVRON = {
        "##...##",
        ".##.##.",
        "..###..",
        "...#..."};
    private static final String[] RESET = {
        "..#.....",
        ".##.....",
        "######..",
        ".##...#.",
        "..#...#.",
        "......#.",
        "..####.."};
    private static final String[] SEARCH = {
        ".###...",
        "#...#..",
        "#...#..",
        "#...#..",
        ".###...",
        "....##.",
        ".....##"};
    private static final String[] TICK = {
        "......#",
        ".....##",
        "#...##.",
        "##.##..",
        ".###...",
        "..#...."};
    private static final String[] GEAR = {
        "...###...",
        ".#.###.#.",
        ".#######.",
        "###...###",
        "###...###",
        "###...###",
        ".#######.",
        ".#.###.#.",
        "...###..."};

    /** A picture given as rows of dots, drawn about its middle. Whole pixels of the interface, so sharp at any size */
    private static void dots(GuiGraphics graphics, String[] rows, int centreX, int centreY, int colour) {
        int left = centreX - rows[0].length() / 2;
        int top = centreY - rows.length / 2;
        for (int row = 0; row < rows.length; row++) {
            String line = rows[row];
            int from = -1;
            for (int column = 0; column <= line.length(); column++) {
                boolean on = column < line.length() && line.charAt(column) == '#';
                if (on && from < 0) {
                    from = column;
                } else if (!on && from >= 0) {
                    graphics.fill(left + from, top + row, left + column, top + row + 1, colour);
                    from = -1;
                }
            }
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
            case CHEVRON -> dots(graphics, CHEVRON, centreX, centreY + 1, colour);
            case RESET -> dots(graphics, RESET, centreX, centreY, colour);
            case SEARCH -> dots(graphics, SEARCH, centreX, centreY, colour);
            case TICK -> dots(graphics, TICK, centreX, centreY, colour);
            case GEAR -> dots(graphics, GEAR, centreX, centreY, colour);
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

    // ------------------------------------------------------------------ Setting out

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
                y += rowHeight + GAP;
            }
            gadget.clip(Integer.MIN_VALUE, Integer.MAX_VALUE);
            gadget.place(font, x, y, width, rowHeight);
            x += width + GAP;
        }
        return y + rowHeight;
    }

}
