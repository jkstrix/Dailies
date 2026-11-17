package comedymonkey.dailies.ui;

import comedymonkey.dailies.Dailies;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.Recorder;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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

    private Notices() {
    }

    public static void register(RegisterGuiLayersEvent event) {
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(Dailies.ID, "notices"), Notices::draw);
    }

    /** Any thread */
    public static void show(Component notice) {
        text = notice;
        since = System.currentTimeMillis();
    }

    private static void draw(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || comedymonkey.dailies.export.Export.active()) {
            // Nothing of Dailies' own may end up in an exported picture
            return;
        }
        Font font = minecraft.font;
        Recorder.State state = Recorder.get().state();
        if (state != Recorder.State.IDLE) {
            // A walnut plate with a lamp: lit and blinking while recording, dim while paused
            int seconds = Math.max(0, Recorder.get().recordingTick()) / 20;
            String time = String.format("%d:%02d", seconds / 60, seconds % 60);
            int width = 16 + font.width(time) + 6;
            Kit.shadow(graphics, 4, 4, 4 + width, 18, 3);
            graphics.fill(4, 4, 4 + width, 18, Controls.PLATE);
            graphics.renderOutline(4, 4, width, 14, Controls.BRASS);
            boolean lit = state == Recorder.State.RECORDING && (System.currentTimeMillis() / 600) % 2 == 0;
            int lamp = state == Recorder.State.PAUSED ? 0xFF8A7A5A : lit ? 0xFFE0483A : 0xFF7A2A22;
            graphics.fill(9, 8, 13, 14, lamp);
            graphics.fill(8, 9, 14, 13, lamp);
            graphics.drawString(font, time, 18, 7, Controls.PARCHMENT, false);
        }
        if (!(minecraft.screen instanceof KitScreen)) {
            // A Dailies screen draws the notice itself, over its plate and sharp, where this one would be blurred behind it
            drawNotice(graphics, font);
        }
    }

    /** The notice, drawn over a screen: the layer it usually lives on is under every screen and is not drawn at all without a world */
    static void drawOver(GuiGraphics graphics, Font font) {
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 350.0F);
        try {
            drawNotice(graphics, font);
        } finally {
            graphics.pose().popPose();
        }
    }

    private static void drawNotice(GuiGraphics graphics, Font font) {
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
        graphics.renderOutline(left, top, width, 18, (a << 24) | (Controls.BRASS & 0xFFFFFF));
        graphics.fill(left + 1, top + 1, left + 3, top + 17, (a << 24) | (Controls.BRASS_BRIGHT & 0xFFFFFF));
        graphics.drawString(font, words, left + 10, top + 5, (a << 24) | (Controls.PARCHMENT & 0xFFFFFF), false);
    }

}
