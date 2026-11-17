package comedymonkey.dailies.ui;

import comedymonkey.dailies.Dailies;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.editor.EditorSession;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import org.jetbrains.annotations.Nullable;

/**
 * The strip along the bottom of the screen while a recording is watched (specification chapter IV, "The screen during
 * playback"): the whole recording as a brass gauge, filled up to the current tick, with segment boundaries as thin
 * lines and markers as small flags, and above it the time, the speed, what playback is doing and whether the camera
 * follows its track. The game's own "hide the interface" key hides it with everything else.
 *
 * The timeline screen draws the same gauge through {@link #drawGauge} and {@link #drawStatus}.
 */
public final class PlaybackBar {

    private static final int HEIGHT = 22;
    private static final int GAUGE_HEIGHT = 5;
    private static final int SEGMENT_LINE = 0x90EFE6D6;

    private static boolean failureLogged = false;

    private PlaybackBar() {
    }

    public static void register(RegisterGuiLayersEvent event) {
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(Dailies.ID, "playback_bar"), PlaybackBar::draw);
    }

    private static void draw(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!Playback.loaded() || minecraft.options.hideGui || comedymonkey.dailies.export.Export.active() || minecraft.screen instanceof TimelineScreen) {
            return;
        }
        try {
            int width = graphics.guiWidth();
            int top = graphics.guiHeight() - HEIGHT;
            graphics.fill(0, top, width, top + HEIGHT, Controls.WALNUT_GLASS);
            graphics.fill(0, top, width, top + 1, Controls.BRASS);
            drawStatus(graphics, minecraft.font, 6, top + 3, width - 12, null);
            drawGauge(graphics, 6, top + 14, width - 12, GAUGE_HEIGHT);
        } catch (RuntimeException e) {
            if (!failureLogged) {
                failureLogged = true;
                Host.LOGGER.error("Dailies: the playback bar could not be drawn", e);
            }
        }
    }

    /**
     * One line of words: the time and what playback is doing on the left, the speed and the camera on the right.
     *
     * @param instead drawn on the right in place of the camera, or null
     */
    static void drawStatus(GuiGraphics graphics, Font font, int left, int top, int width, @Nullable Component instead) {
        Component time = Component.translatable("dailies.ui.bar.time", Controls.time(Playback.position()), Controls.time(Playback.total()));
        Component doing = Component.translatable(Playback.jumping() ? "dailies.ui.bar.jumping" : Playback.paused() ? "dailies.ui.bar.paused" : "dailies.ui.bar.playing");
        int x = left;
        graphics.drawString(font, time, x, top, Controls.PARCHMENT, false);
        x += font.width(time) + 8;
        graphics.drawString(font, doing, x, top, Controls.BRASS_BRIGHT, false);
        x += font.width(doing) + 8;

        int right = left + width;
        Component last = instead != null ? instead : Component.translatable(EditorSession.followCamera() ? "dailies.ui.bar.camera_track" : "dailies.ui.bar.camera_free");
        if (right - font.width(last) >= x) {
            right -= font.width(last);
            graphics.drawString(font, last, right, top, Controls.PARCHMENT_DIM, false);
            right -= 8;
        }
        if (Math.abs(Playback.ticksPerSecond() - 20.0) > 0.001) {
            Component speed = Component.translatable("dailies.ui.bar.speed", Controls.speed(Playback.ticksPerSecond()));
            if (right - font.width(speed) >= x) {
                graphics.drawString(font, speed, right - font.width(speed), top, Controls.PARCHMENT, false);
            }
        }
    }

    /**
     * The recording as a gauge. Marker flags stand up to 4 pixels above it and the hand 2 pixels above and below.
     */
    static void drawGauge(GuiGraphics graphics, int left, int top, int width, int height) {
        if (width <= 0) {
            return;
        }
        int total = Math.max(1, Playback.total());
        int position = Math.max(0, Math.min(total, Playback.position()));
        int bottom = top + height;

        graphics.fill(left - 1, top - 1, left + width + 1, bottom + 1, Controls.BRASS);
        graphics.fill(left, top, left + width, bottom, Controls.WALNUT);
        int played = across(position, total, width);
        graphics.fill(left, top, left + played, bottom, Controls.BRASS);

        RecordingManifest manifest = Playback.manifest();
        if (manifest != null) {
            for (RecordingManifest.Segment segment : manifest.segments) {
                if (segment.startTick > 0 && segment.startTick < total) {
                    int x = left + Math.min(width - 1, across(segment.startTick, total, width));
                    graphics.fill(x, top, x + 1, bottom, SEGMENT_LINE);
                }
            }
            for (RecordingManifest.Marker marker : manifest.markers) {
                int colour = 0xFF000000 | (marker.colour & 0xFFFFFF);
                int x = left + markerOffset(marker.tick, total, width);
                graphics.fill(x, top - 4, x + 1, bottom, colour);
                graphics.fill(x + 1, top - 4, x + 4, top - 1, colour);
            }
        }

        int hand = left + Math.min(width - 1, played);
        graphics.fill(hand, top - 2, hand + 1, bottom + 2, Controls.PARCHMENT);
    }

    /** How far along a gauge of this width a marker's pole stands */
    static int markerOffset(int tick, int total, int width) {
        return Math.min(width - 1, across(Math.max(0, Math.min(total, tick)), total, width));
    }

    private static int across(int tick, int total, int width) {
        return (int) ((long) width * tick / total);
    }

    /** The tick under a point of a gauge */
    static int tickAt(double mouseX, int left, int width) {
        int total = Math.max(0, Playback.total());
        if (width <= 0) {
            return 0;
        }
        double share = Math.max(0.0, Math.min(1.0, (mouseX - left) / width));
        return (int) Math.round(share * total);
    }

}
