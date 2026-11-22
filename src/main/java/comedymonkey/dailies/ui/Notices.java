package comedymonkey.dailies.ui;

import comedymonkey.dailies.Dailies;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.Recorder;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

/**
 * Dailies' own on-screen notices (specification REC-4): a line of text that slides in, stays a few seconds and fades
 * away, and a small plate in the corner while a recording is being made.
 */
public final class Notices {

    private static final long SHOWN_MILLIS = 3500;
    private static final long FADE_MILLIS = 500;
    private static final long SLIDE_MILLIS = 140;

    private static volatile Component text = null;
    private static volatile long since = 0;
    private static volatile boolean hidden = false;

    /** The recording timer's plate: its height, and how far it keeps from the screen's edges */
    private static final int PLATE_HEIGHT = 14;
    private static final int MARGIN = 4;

    private Notices() {
    }

    /**
     * Where the timer's plate goes for the recordingTimer setting, as its top left corner: a corner of the screen, kept
     * clear of its edges; the lamp alone sits top left. Anything not a corner is taken as top left
     */
    static int[] place(String where, int screenWidth, int screenHeight, int width, int height) {
        boolean right = where.endsWith("RIGHT");
        boolean bottom = where.startsWith("BOTTOM");
        return new int[]{right ? screenWidth - MARGIN - width : MARGIN, bottom ? screenHeight - MARGIN - height : MARGIN};
    }

    public static void register(RegisterGuiLayersEvent event) {
        event.registerAboveAll(Identifier.fromNamespaceAndPath(Dailies.ID, "notices"), Notices::draw);
    }

    /** Whether the person has asked for everything of Dailies to be kept off the screen ("/dailies hide") */
    public static boolean hidden() {
        return hidden;
    }

    /** Hides or shows the recording mark, these notices, the playback bar and the editor. Any thread */
    public static void setHidden(boolean now) {
        hidden = now;
    }

    /** Any thread */
    public static void show(Component notice) {
        text = notice;
        since = System.currentTimeMillis();
    }

    private static void draw(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        if (hidden || comedymonkey.dailies.ui.HudHidden.get() || comedymonkey.dailies.export.Export.active()) {
            // Nothing of Dailies' own may end up in an exported picture
            return;
        }
        Font font = minecraft.font;
        Recorder.State state = Recorder.get().state();
        String where = Host.get().setting("recordingTimer", "TOP_LEFT").toUpperCase(java.util.Locale.ROOT);
        if (state != Recorder.State.IDLE && !where.equals("OFF")) {
            // A walnut plate with a lamp, lit and blinking while recording, dim while paused, and the time recorded beside
            // it unless the setting asks for the lamp alone
            boolean lampOnly = where.equals("LAMP_ONLY");
            int seconds = Math.max(0, Recorder.get().recordingTick()) / 20;
            String time = String.format("%d:%02d", seconds / 60, seconds % 60);
            int width = lampOnly ? 14 : 16 + font.width(time) + 6;
            int[] at = place(where, graphics.guiWidth(), graphics.guiHeight(), width, PLATE_HEIGHT);
            int x = at[0];
            int y = at[1];
            Kit.shadow(graphics, x, y, x + width, y + PLATE_HEIGHT, 3);
            graphics.fill(x, y, x + width, y + PLATE_HEIGHT, Controls.PLATE);
            graphics.outline(x, y, width, PLATE_HEIGHT, Controls.BRASS);
            boolean lit = state == Recorder.State.RECORDING && (System.currentTimeMillis() / 600) % 2 == 0;
            int lamp = state == Recorder.State.PAUSED ? 0xFF8A7A5A : lit ? 0xFFE0483A : 0xFF7A2A22;
            graphics.fill(x + 5, y + 4, x + 9, y + 10, lamp);
            graphics.fill(x + 4, y + 5, x + 10, y + 9, lamp);
            if (!lampOnly) {
                graphics.text(font, time, x + 14, y + 3, Controls.PARCHMENT, false);
            }
        }
        if (!(minecraft.gui.screen() instanceof KitScreen)) {
            // A Dailies screen draws the notice itself, over its plate and sharp, where this one would be blurred behind it
            drawNotice(graphics, font);
        }
    }

    /** The notice, drawn over a screen: the layer it usually lives on is under every screen and is not drawn at all without a world */
    static void drawOver(GuiGraphicsExtractor graphics, Font font) {
        graphics.pose().pushMatrix();
        try {
            drawNotice(graphics, font);
        } finally {
            graphics.pose().popMatrix();
        }
    }

    private static void drawNotice(GuiGraphicsExtractor graphics, Font font) {
        Component notice = text;
        if (notice == null || !Host.get().setting("showNotices", true)) {
            return;
        }
        long age = System.currentTimeMillis() - since;
        if (age > SHOWN_MILLIS) {
            text = null;
            return;
        }
        float alpha = age > SHOWN_MILLIS - FADE_MILLIS ? (SHOWN_MILLIS - age) / (float) FADE_MILLIS : 1.0F;
        int a = Math.max(4, (int) (alpha * 255));
        String words = Kit.clip(font, notice.getString(), graphics.guiWidth() - 40);
        int width = font.width(words) + 20;
        int left = (graphics.guiWidth() - width) / 2;
        // It drops in from the top edge
        int top = 6 - Math.round(22 * (1.0F - Kit.ease(age / (float) SLIDE_MILLIS)));
        if (alpha >= 1.0F) {
            Kit.shadow(graphics, left, top, left + width, top + 18, 3);
        }
        graphics.fill(left, top, left + width, top + 18, ((int) (alpha * 0xF0) << 24) | 0x261A0C);
        graphics.outline(left, top, width, 18, (a << 24) | (Controls.BRASS & 0xFFFFFF));
        graphics.fill(left + 1, top + 1, left + 3, top + 17, (a << 24) | (Controls.BRASS_BRIGHT & 0xFFFFFF));
        graphics.text(font, words, left + 10, top + 5, (a << 24) | (Controls.PARCHMENT & 0xFFFFFF), false);
    }

}
