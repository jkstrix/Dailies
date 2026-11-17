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
 * Dailies' own on-screen notices (specification REC-4): a line of text that slides away after a few seconds, and a
 * small mark in the corner while a recording is being made.
 */
public final class Notices {

    private static final long SHOWN_MILLIS = 3500;
    private static final long FADE_MILLIS = 500;

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
        if (minecraft.options.hideGui) {
            return;
        }
        Font font = minecraft.font;
        Recorder.State state = Recorder.get().state();
        if (state != Recorder.State.IDLE) {
            // A brass plate with a lamp: lit and blinking while recording, dim while paused
            int seconds = Math.max(0, Recorder.get().recordingTick()) / 20;
            String time = String.format("%d:%02d", seconds / 60, seconds % 60);
            int width = 14 + font.width(time) + 5;
            graphics.fill(4, 4, 4 + width, 17, 0xB02B1D0E);
            graphics.fill(4, 16, 4 + width, 17, 0xFF9A7A34);
            boolean lit = state == Recorder.State.RECORDING && (System.currentTimeMillis() / 600) % 2 == 0;
            graphics.fill(8, 8, 13, 13, state == Recorder.State.PAUSED ? 0xFF8A7A5A : lit ? 0xFFE0483A : 0xFF7A2A22);
            graphics.drawString(font, time, 17, 7, 0xFFEFE6D6, false);
        }

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
        int width = font.width(notice) + 16;
        int left = (graphics.guiWidth() - width) / 2;
        int top = 6;
        graphics.fill(left, top, left + width, top + 16, ((int) (alpha * 0xB0) << 24) | 0x2B1D0E);
        graphics.fill(left, top + 15, left + width, top + 16, (a << 24) | 0x9A7A34);
        graphics.drawString(font, notice, left + 8, top + 4, (a << 24) | 0xEFE6D6, false);
    }

}
