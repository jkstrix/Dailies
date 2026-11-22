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
 * playback"): the whole recording as a brass gauge, filled up to the current tick, with notches at round times spaced
 * by {@link Ruler} for the bar's width and markers as small flags, and above it the time, the speed, what playback is doing and whether the camera
 * follows its track. The game's own "hide the interface" key hides it with everything else.
 *
 * While the editor is open it has a timeline of its own and this strip stays away.
 */
public final class PlaybackBar {

    private static final int HEIGHT = 26;
    private static final int GAUGE_HEIGHT = 6;
    /** Light and part seen through, so a notch shows on the dark of the gauge still to play and on the brass of what has played */
    private static final int NOTCH = 0x80EFE6D6;

    private static boolean failureLogged = false;

    private PlaybackBar() {
    }

    public static void register(RegisterGuiLayersEvent event) {
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(Dailies.ID, "playback_bar"), PlaybackBar::draw);
    }

    private static void draw(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!Playback.loaded() || Notices.hidden() || minecraft.options.hideGui || comedymonkey.dailies.export.Export.active() || minecraft.screen instanceof EditorScreen || minecraft.screen instanceof ExportProgress) {
            return;
        }
        try {
            int width = graphics.guiWidth();
            int top = graphics.guiHeight() - HEIGHT;
            // A panel like the editor's, with its shadow on the world above it, so the words read over a bright floor
            Kit.panel(graphics, -1, top, width + 1, top + HEIGHT + 1);
            drawStatus(graphics, minecraft.font, 8, top + 5, width - 16, null);
            drawGauge(graphics, 8, top + 17, width - 16, GAUGE_HEIGHT);
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
        Component time = Component.translatable("dailies.ui.bar.time", Controls.time(Playback.shown()), Controls.time(Playback.total()));
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
        int position = Math.max(0, Math.min(total, Playback.shown()));
        int bottom = top + height;

        Kit.field(graphics, left - 1, top - 1, width + 2, height + 2, Controls.BRASS);
        int played = across(position, total, width);
        graphics.fill(left, top, left + played, bottom, Controls.BRASS);
        graphics.fill(left, top, left + played, top + 1, Controls.BRASS_BRIGHT);

        // Notches at round times, far enough apart at this width to be told apart: small ones up from the foot of the
        // gauge and taller ones at a longer round time. Segment boundaries are not drawn, since a segment begins at
        // every stored world, every few seconds, and their lines made a comb of the whole gauge.
        long minor = Ruler.minor(total, width, Ruler.MINOR_GAP);
        long major = Ruler.major(minor, total, width, Ruler.MAJOR_GAP);
        for (long tick = minor; tick < total; tick += minor) {
            int x = Ruler.x(tick, 0, total, left, width);
            int tall = tick % major == 0 ? height - 1 : Math.max(1, height / 3);
            graphics.fill(x, bottom - tall, x + 1, bottom, NOTCH);
        }

        RecordingManifest manifest = Playback.manifest();
        if (manifest != null) {
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
