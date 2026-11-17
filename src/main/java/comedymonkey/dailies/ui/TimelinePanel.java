package comedymonkey.dailies.ui;

import comedymonkey.dailies.edit.EditHistory;
import comedymonkey.dailies.edit.Keyframe;
import comedymonkey.dailies.edit.KeyframeKind;
import comedymonkey.dailies.edit.KeyframeRef;
import comedymonkey.dailies.edit.Scene;
import comedymonkey.dailies.edit.TimeMap;
import comedymonkey.dailies.edit.Track;
import comedymonkey.dailies.editor.EditorSession;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The bottom panel of the editor (specification chapter V): the transport buttons, a ruler in seconds over the whole
 * recording with the playhead, segment boundaries, marker flags and the export range, and under it one row for every
 * track of the scene with its keyframes as diamonds. The names of the tracks, with what can be done to each, stand in
 * a column at the left.
 */
final class TimelinePanel {

    private static final int BAR = 16;
    private static final int RULER = 14;
    private static final int LANE = 13;
    private static final int SEGMENT_LINE = 0x90EFE6D6;
    private static final int RANGE_TINT = 0x38D4B25A;
    private static final double[] RULER_STEPS = {0.05, 0.25, 0.5, 1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 1800, 3600};

    /** What the left mouse button is in the middle of doing in the rows */
    private enum Drag {
        NONE, KEYS, BOX, RANGE_START, RANGE_END, PAN
    }

    private int left;
    private int top;
    private int right;
    private int bottom;
    private int columnWidth;
    private int laneLeft;
    private int laneWidth;
    private int rulerTop;
    private int lanesTop;
    private int lanesBottom;
    private int statusLeft;
    private int statusRight;

    private final List<Gadget> transport = new ArrayList<>();
    private final Gadget.Push fold;
    private final Gadget.Push addTrack;

    private Drag drag = Drag.NONE;
    private int dragFromTick;
    private int dragOffset;
    private double boxFromX;
    private double boxFromY;
    private double boxToX;
    private double boxToY;
    private boolean boxAdds;
    private int rangePreview = -1;
    /** The tick the mouse last asked for, waiting for the jump before it to finish; -1 for none */
    private int wanted = -1;
    /** Where the playhead is being dragged to, drawn there whatever the recording has caught up with; -1 when not */
    private int scrubTick = -1;
    /** The playhead has been let go and the last rough jump has still to be made exact */
    private boolean refineOwed = false;
    private @Nullable KeyframeRef lastClicked;
    private long lastClickAt;

    private @Nullable TimeMap realTime;
    private long realTimeFor = Long.MIN_VALUE;

    TimelinePanel() {
        this.transport.add(Gadget.Push.icon(Kit.Icon.TO_START, Kit.t("transport.to_start"), () -> Controls.jumpTo(0)));
        this.transport.add(Gadget.Push.icon(Kit.Icon.STEP_BACK, Kit.t("transport.step_back"), () -> Controls.jumpTo(Math.max(0, Playback.position() - 1))));
        this.transport.add(Gadget.Push.icon(() -> Playback.paused() ? Kit.Icon.PLAY : Kit.Icon.PAUSE, Kit.t("transport.play"), Controls::togglePlay)
                .liveTip(() -> Kit.t(Playback.paused() ? "transport.play" : "transport.pause")).wide(20));
        this.transport.add(Gadget.Push.icon(Kit.Icon.STEP_FORWARD, Kit.t("transport.step_forward"), Controls::stepOneTick));
        this.transport.add(Gadget.Push.icon(Kit.Icon.TO_END, Kit.t("transport.to_end"), () -> Controls.jumpTo(Playback.total())));
        this.transport.add(Gadget.Push.icon(Kit.Icon.MINUS, Kit.t("transport.slower"), Controls::slower));
        this.transport.add(new Gadget.Words(() -> Kit.t("transport.speed", Controls.speed(Playback.ticksPerSecond())), Controls.PARCHMENT)
                .wide(34).tip(Kit.t("transport.speed.tip")));
        this.transport.add(Gadget.Push.icon(Kit.Icon.PLUS, Kit.t("transport.faster"), Controls::faster));
        this.transport.add(new Gadget.Push(() -> Kit.t(EditorSession.followCamera() ? "transport.camera_track" : "transport.camera_free"), Controls::toggleFollowCamera)
                .lit(EditorSession::followCamera).tip(Kit.t("transport.camera.tip")).wide(96));
        this.fold = Gadget.Push.icon(() -> EditorState.timelineFolded ? Kit.Icon.FOLD_UP : Kit.Icon.FOLD_DOWN, Kit.t("timeline.fold"),
                () -> EditorState.timelineFolded = !EditorState.timelineFolded);
        this.addTrack = new Gadget.Push(Kit.t("track.add"), this::openAddMenu);
        this.addTrack.tip(Kit.t("track.add.tip"));
    }

    /** The height the panel would like on a screen of this height */
    static int wantedHeight(int screenHeight) {
        int fixed = 2 + BAR + 2 + RULER;
        if (EditorState.timelineFolded) {
            return fixed + 3;
        }
        int tracks = EditorSession.open() ? EditorState.scene().tracks().size() : 0;
        int most = Math.max(2, (screenHeight * 42 / 100 - fixed - 8) / LANE);
        return fixed + Math.max(2, Math.min(tracks, most)) * LANE + 8;
    }

    void place(Font font, int newLeft, int newTop, int newRight, int newBottom) {
        this.left = newLeft;
        this.top = newTop;
        this.right = newRight;
        this.bottom = newBottom;
        int width = newRight - newLeft;
        this.columnWidth = Math.max(84, Math.min(190, width * 24 / 100));
        this.laneLeft = newLeft + this.columnWidth + 1;
        this.laneWidth = Math.max(20, newRight - this.laneLeft - 5);
        this.rulerTop = newTop + 2 + BAR + 2;
        this.lanesTop = this.rulerTop + RULER;
        this.lanesBottom = EditorState.timelineFolded ? this.lanesTop : Math.max(this.lanesTop, newBottom - 8);

        int x = newLeft + 3;
        for (Gadget gadget : this.transport) {
            int wide = gadget.wantedWidth(font);
            gadget.clip(Integer.MIN_VALUE, Integer.MAX_VALUE);
            gadget.place(font, x, newTop + 2, wide, BAR);
            x += wide + 2;
        }
        this.fold.clip(Integer.MIN_VALUE, Integer.MAX_VALUE);
        this.fold.place(font, newRight - 19, newTop + 2, 16, BAR);
        this.statusLeft = x + 4;
        this.statusRight = newRight - 23;
        this.addTrack.clip(Integer.MIN_VALUE, Integer.MAX_VALUE);
        this.addTrack.place(font, newLeft + 3, this.rulerTop, this.columnWidth - 5, RULER - 1);

        int total = Math.max(1, Playback.total());
        if (EditorState.viewSpan <= 0.0 || EditorState.viewSpan > total) {
            EditorState.viewSpan = total;
        }
        EditorState.viewSpan = Math.max(Math.min(total, 40.0), EditorState.viewSpan);
        EditorState.viewStart = Math.max(0.0, Math.min(total - EditorState.viewSpan, EditorState.viewStart));
        int rows = this.visibleLanes();
        int tracks = EditorState.scene().tracks().size();
        EditorState.laneScroll = Math.max(0, Math.min(EditorState.laneScroll, tracks - rows));
    }

    private int visibleLanes() {
        return Math.max(0, (this.lanesBottom - this.lanesTop) / LANE);
    }

    boolean over(double mouseX, double mouseY) {
        return mouseX >= this.left && mouseX < this.right && mouseY >= this.top && mouseY < this.bottom;
    }

    // ------------------------------------------------------------------ Ticks and pixels

    private double across(double tick) {
        return this.laneLeft + (tick - EditorState.viewStart) / EditorState.viewSpan * this.laneWidth;
    }

    private int tickAt(double mouseX) {
        double tick = EditorState.viewStart + (mouseX - this.laneLeft) / this.laneWidth * EditorState.viewSpan;
        return (int) Math.max(0, Math.min(Playback.total(), Math.round(tick)));
    }

    private boolean overRuler(double mouseX, double mouseY) {
        return mouseX >= this.laneLeft - 2 && mouseX < this.laneLeft + this.laneWidth + 3 && mouseY >= this.rulerTop && mouseY < this.lanesTop;
    }

    private boolean overLanes(double mouseX, double mouseY) {
        return mouseX >= this.laneLeft - 2 && mouseX < this.laneLeft + this.laneWidth + 3 && mouseY >= this.lanesTop && mouseY < this.lanesBottom;
    }

    private boolean overColumn(double mouseX, double mouseY) {
        return mouseX >= this.left && mouseX < this.laneLeft && mouseY >= this.lanesTop && mouseY < this.lanesBottom;
    }

    /** The index of the track whose row is at this height, or -1 */
    private int trackAt(double mouseY) {
        if (mouseY < this.lanesTop || mouseY >= this.lanesTop + this.visibleLanes() * LANE) {
            return -1;
        }
        int index = EditorState.laneScroll + (int) ((mouseY - this.lanesTop) / LANE);
        return index < EditorState.scene().tracks().size() ? index : -1;
    }

    private int laneTop(int track) {
        return this.lanesTop + (track - EditorState.laneScroll) * LANE;
    }

    private @Nullable KeyframeRef keyframeAt(double mouseX, double mouseY) {
        int index = this.trackAt(mouseY);
        if (index < 0) {
            return null;
        }
        KeyframeRef nearest = null;
        double best = 4.5;
        for (Keyframe keyframe : EditorState.scene().track(index).keyframes()) {
            double distance = Math.abs(this.across(keyframe.tick()) - mouseX);
            if (distance < best) {
                best = distance;
                nearest = new KeyframeRef(index, keyframe.tick());
            }
        }
        return nearest;
    }

    // ------------------------------------------------------------------ Every frame

    /** Follows the mouse while it drags the playhead, and keeps the playhead in sight while the recording plays */
    void frame(double mouseX, boolean leftButtonDown) {
        if (EditorState.pointerSettling > 0) {
            EditorState.pointerSettling--;
        } else if (EditorState.scrubbing) {
            if (leftButtonDown) {
                this.wanted = this.tickAt(mouseX);
                this.scrubTick = this.wanted;
            } else {
                EditorState.scrubbing = false;
                this.refineOwed = true;
            }
        }
        if (!EditorState.scrubbing && this.wanted < 0 && !Playback.jumping()) {
            this.scrubTick = -1;
        }
        if (this.wanted >= 0 && !Playback.jumping()) {
            int tick = this.wanted;
            this.wanted = -1;
            if (tick != Playback.position()) {
                Controls.scrubTo(tick);
            }
        } else if (this.refineOwed && this.wanted < 0 && !EditorState.scrubbing && !Playback.jumping()) {
            this.refineOwed = false;
            Controls.refine();
        }
        int total = Math.max(1, Playback.total());
        if (!Playback.paused() && !EditorState.scrubbing && this.drag == Drag.NONE && EditorState.viewSpan < total) {
            int position = Playback.position();
            if (position < EditorState.viewStart || position > EditorState.viewStart + EditorState.viewSpan) {
                EditorState.viewStart = Math.max(0.0, Math.min(total - EditorState.viewSpan, position - EditorState.viewSpan * 0.1));
            }
        }
    }

    /** How long the video runs up to each tick, when speed, timelapse or freeze keyframes change that; else null */
    private @Nullable TimeMap realTime() {
        EditHistory history = EditorState.history();
        long stamp = history.revision() * 31 + Playback.total();
        if (stamp != this.realTimeFor) {
            this.realTimeFor = stamp;
            this.realTime = null;
            Scene scene = history.scene();
            boolean changes = false;
            for (Track track : scene.tracks()) {
                KeyframeKind kind = track.kind();
                if (track.enabled() && !track.isEmpty() && (kind == KeyframeKind.SPEED || kind == KeyframeKind.TIMELAPSE || kind == KeyframeKind.FREEZE)) {
                    changes = true;
                }
            }
            if (changes && Playback.total() > 0) {
                try {
                    this.realTime = new TimeMap(scene, 0, Playback.total());
                } catch (RuntimeException e) {
                    this.realTime = null;
                }
            }
        }
        return this.realTime;
    }

    // ------------------------------------------------------------------ Drawing

    void draw(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        Kit.panel(graphics, this.left, this.top, this.right, this.bottom);
        for (Gadget gadget : this.transport) {
            gadget.draw(graphics, font, mouseX, mouseY);
        }
        this.fold.draw(graphics, font, mouseX, mouseY);
        this.drawStatus(graphics, font);
        this.addTrack.draw(graphics, font, mouseX, mouseY);

        Scene scene = EditorState.scene();
        int total = Math.max(1, Playback.total());
        int laneRight = this.laneLeft + this.laneWidth;

        // The ruler and the rows
        graphics.fill(this.laneLeft, this.rulerTop, laneRight + 1, this.lanesTop, Controls.FIELD);
        int rows = this.visibleLanes();
        for (int row = 0; row < rows; row++) {
            int index = EditorState.laneScroll + row;
            int y = this.lanesTop + row * LANE;
            graphics.fill(this.laneLeft, y, laneRight + 1, y + LANE - 1, index < scene.tracks().size() ? (row % 2 == 0 ? 0x50000000 : 0x30000000) : 0x18000000);
        }

        graphics.enableScissor(this.laneLeft, this.rulerTop, laneRight + 1, Math.max(this.lanesTop, this.lanesBottom));
        try {
            this.drawRange(graphics, scene, total);
            this.drawRuler(graphics, font, total);
            this.drawMarks(graphics, total);
            for (int row = 0; row < rows; row++) {
                int index = EditorState.laneScroll + row;
                if (index < scene.tracks().size()) {
                    this.drawKeyframes(graphics, scene.track(index), index, this.lanesTop + row * LANE);
                }
            }
            if (this.drag == Drag.BOX) {
                int x0 = (int) Math.min(this.boxFromX, this.boxToX);
                int x1 = (int) Math.max(this.boxFromX, this.boxToX);
                int y0 = (int) Math.min(this.boxFromY, this.boxToY);
                int y1 = (int) Math.max(this.boxFromY, this.boxToY);
                graphics.fill(x0, y0, x1 + 1, y1 + 1, 0x30EFE6D6);
                graphics.renderOutline(x0, y0, x1 - x0 + 1, y1 - y0 + 1, Controls.PARCHMENT);
            }
            // The playhead
            int hand = (int) Math.round(this.across(Math.max(0, Math.min(total, this.scrubTick >= 0 ? this.scrubTick : Playback.shown()))));
            graphics.fill(hand, this.rulerTop, hand + 1, Math.max(this.lanesTop, this.lanesBottom), Controls.PARCHMENT);
            graphics.fill(hand - 2, this.rulerTop, hand + 3, this.rulerTop + 3, Controls.PARCHMENT);
        } finally {
            graphics.disableScissor();
        }

        // The track names
        for (int row = 0; row < rows; row++) {
            int index = EditorState.laneScroll + row;
            if (index < scene.tracks().size()) {
                this.drawTrackName(graphics, font, scene, index, this.lanesTop + row * LANE, mouseX, mouseY);
            }
        }
        if (!EditorState.timelineFolded && scene.tracks().isEmpty() && rows > 0) {
            Kit.text(graphics, font, Kit.clip(font, Kit.t("track.none").getString(), this.laneWidth - 8), this.laneLeft + 4, this.lanesTop + 3, Controls.PARCHMENT_DIM);
        }
        if (scene.tracks().size() > rows && rows > 0) {
            // More tracks than rows: a thin bar says where among them the rows are
            int height = rows * LANE;
            int thumb = Math.max(6, height * rows / scene.tracks().size());
            int at = this.lanesTop + (height - thumb) * EditorState.laneScroll / Math.max(1, scene.tracks().size() - rows);
            graphics.fill(this.left + 1, at, this.left + 2, at + thumb, Controls.BRASS_BRIGHT);
        }

        // The bar for moving along a zoomed-in timeline
        if (!EditorState.timelineFolded && EditorState.viewSpan < total) {
            int barTop = this.lanesBottom + 2;
            graphics.fill(this.laneLeft, barTop, laneRight + 1, barTop + 4, 0x60000000);
            int from = this.laneLeft + (int) (this.laneWidth * EditorState.viewStart / total);
            int to = this.laneLeft + (int) Math.ceil(this.laneWidth * (EditorState.viewStart + EditorState.viewSpan) / total);
            graphics.fill(from, barTop, Math.max(from + 4, to), barTop + 4, Controls.BRASS_BRIGHT);
        }
    }

    private void drawStatus(GuiGraphics graphics, Font font) {
        int room = this.statusRight - this.statusLeft;
        if (room < 30) {
            return;
        }
        int position = this.scrubTick >= 0 ? this.scrubTick : Playback.shown();
        String time = Component.translatable("dailies.ui.bar.time", Controls.time(position), Controls.time(Playback.total())).getString();
        String words = time;
        TimeMap map = this.realTime();
        if (map != null) {
            words += "  " + Kit.t("status.video", Kit.seconds(map.realSecondsAt(position)), Kit.seconds(map.totalRealSeconds())).getString();
        }
        String longer = words + "  " + Kit.t("status.tick", position).getString();
        if (Playback.jumping()) {
            longer += "  " + Component.translatable("dailies.ui.bar.jumping").getString();
        }
        if (!EditorState.selection.isEmpty()) {
            longer += "  " + Kit.t("status.selected", EditorState.selection.size()).getString();
        }
        String shown = font.width(longer) <= room ? longer : font.width(words) <= room ? words : Kit.clip(font, time, room);
        Kit.text(graphics, font, shown, this.statusLeft, this.top + 2 + (BAR - 8) / 2, Controls.PARCHMENT);
    }

    private void drawRange(GuiGraphics graphics, Scene scene, int total) {
        if (!scene.hasExportRange()) {
            return;
        }
        int start = scene.exportStart();
        int end = scene.exportEnd();
        if (this.drag == Drag.RANGE_START && this.rangePreview >= 0) {
            start = Math.min(this.rangePreview, end);
        } else if (this.drag == Drag.RANGE_END && this.rangePreview >= 0) {
            end = Math.max(this.rangePreview, start);
        }
        int from = (int) Math.round(this.across(start));
        int to = (int) Math.round(this.across(end));
        graphics.fill(from, this.rulerTop, to + 1, Math.max(this.lanesTop, this.lanesBottom), RANGE_TINT);
        // A brass tab at either end to take hold of
        graphics.fill(from, this.lanesTop - 6, from + 4, this.lanesTop, Controls.BRASS_BRIGHT);
        graphics.fill(from, this.rulerTop, from + 1, this.lanesTop, Controls.BRASS_BRIGHT);
        graphics.fill(to - 3, this.lanesTop - 6, to + 1, this.lanesTop, Controls.BRASS_BRIGHT);
        graphics.fill(to, this.rulerTop, to + 1, this.lanesTop, Controls.BRASS_BRIGHT);
    }

    private void drawRuler(GuiGraphics graphics, Font font, int total) {
        double pixelsPerSecond = this.laneWidth / (EditorState.viewSpan / 20.0);
        double step = RULER_STEPS[RULER_STEPS.length - 1];
        for (double candidate : RULER_STEPS) {
            if (candidate * pixelsPerSecond >= 56.0) {
                step = candidate;
                break;
            }
        }
        double stepTicks = step * 20.0;
        long first = (long) Math.floor(EditorState.viewStart / stepTicks);
        long last = (long) Math.ceil((EditorState.viewStart + EditorState.viewSpan) / stepTicks);
        // Never more lines than there are pixels to put them on
        if (last - first > this.laneWidth) {
            return;
        }
        for (long mark = first; mark <= last; mark++) {
            double tick = mark * stepTicks;
            if (tick > total) {
                break;
            }
            int x = (int) Math.round(this.across(tick));
            graphics.fill(x, this.lanesTop - 5, x + 1, this.lanesTop, Controls.BRASS);
            int whole = (int) Math.round(tick);
            String label = step < 1.0 ? Controls.time(whole) : Controls.length(whole);
            Kit.text(graphics, font, label, x + 2, this.rulerTop + 2, Controls.PARCHMENT_DIM);
            int half = (int) Math.round(this.across(tick + stepTicks / 2.0));
            if (tick + stepTicks / 2.0 <= total) {
                graphics.fill(half, this.lanesTop - 2, half + 1, this.lanesTop, Controls.BRASS);
            }
        }
    }

    private void drawMarks(GuiGraphics graphics, int total) {
        RecordingManifest manifest = Playback.manifest();
        if (manifest == null) {
            return;
        }
        int floor = Math.max(this.lanesTop, this.lanesBottom);
        for (RecordingManifest.Segment segment : manifest.segments) {
            if (segment.startTick > 0 && segment.startTick < total) {
                int x = (int) Math.round(this.across(segment.startTick));
                // A broken line, so that it is not taken for the playhead
                for (int y = this.rulerTop; y < floor; y += 4) {
                    graphics.fill(x, y, x + 1, Math.min(floor, y + 2), SEGMENT_LINE);
                }
            }
        }
        for (RecordingManifest.Marker marker : manifest.markers) {
            int colour = 0xFF000000 | (marker.colour & 0xFFFFFF);
            int x = (int) Math.round(this.across(Math.max(0, Math.min(total, marker.tick))));
            graphics.fill(x, this.rulerTop + 1, x + 1, this.lanesTop, colour);
            graphics.fill(x + 1, this.rulerTop + 1, x + 5, this.rulerTop + 5, colour);
        }
    }

    private void drawKeyframes(GuiGraphics graphics, Track track, int index, int y) {
        int middle = y + LANE / 2;
        List<Keyframe> keyframes = track.keyframes();
        int plain = track.enabled() ? Controls.BRASS_BRIGHT : 0xFF6A5A3A;
        if (track.kind() == KeyframeKind.TIMELAPSE) {
            // The two ends of a timelapse belong together
            for (int k = 0; k + 1 < keyframes.size(); k += 2) {
                int from = (int) Math.round(this.across(keyframes.get(k).tick()));
                int to = (int) Math.round(this.across(keyframes.get(k + 1).tick()));
                graphics.fill(from, middle - 1, to + 1, middle + 2, track.enabled() ? Controls.BRASS : 0xFF4A3F2A);
            }
        } else if (keyframes.size() > 1) {
            // A thin line over the stretch the track applies to
            int from = (int) Math.round(this.across(keyframes.get(0).tick()));
            int to = (int) Math.round(this.across(keyframes.get(keyframes.size() - 1).tick()));
            graphics.fill(from, middle, to + 1, middle + 1, track.enabled() ? 0x80D4B25A : 0x606A5A3A);
        }
        for (Keyframe keyframe : keyframes) {
            boolean selected = EditorState.selection.contains(new KeyframeRef(index, keyframe.tick()));
            int x = (int) Math.round(this.across(keyframe.tick()));
            if (selected) {
                Kit.diamond(graphics, x, middle, 4, Controls.PARCHMENT);
                Kit.diamond(graphics, x, middle, 2, Controls.ALERT);
            } else {
                Kit.diamond(graphics, x, middle, 3, plain);
            }
        }
        if (this.drag == Drag.KEYS && this.dragOffset != 0) {
            // Where the selected keyframes will land
            for (KeyframeRef ref : EditorState.selection) {
                if (ref.track() == index) {
                    int x = (int) Math.round(this.across(ref.tick() + this.dragOffset));
                    Kit.diamond(graphics, x, middle, 4, 0xA0EFE6D6);
                }
            }
        }
    }

    private void drawTrackName(GuiGraphics graphics, Font font, Scene scene, int index, int y, int mouseX, int mouseY) {
        Track track = scene.track(index);
        int x = this.left + 4;
        boolean rowHovered = mouseX >= this.left && mouseX < this.laneLeft && mouseY >= y && mouseY < y + LANE;
        int boxTop = y + (LANE - 9) / 2;
        graphics.fill(x, boxTop, x + 9, boxTop + 9, Controls.FIELD);
        graphics.renderOutline(x, boxTop, 9, 9, rowHovered && mouseX < x + 11 ? Controls.BRASS_BRIGHT : Controls.BRASS);
        if (track.enabled()) {
            graphics.fill(x + 2, boxTop + 2, x + 7, boxTop + 7, Controls.BRASS_BRIGHT);
        }
        int buttons = this.laneLeft - 1 - 3 * 12;
        String name = this.trackTitle(track);
        Kit.text(graphics, font, Kit.clip(font, name, buttons - (x + 13) - 2), x + 13, y + (LANE - 8) / 2, track.enabled() ? Controls.PARCHMENT : Controls.PARCHMENT_DIM);
        Kit.Icon[] icons = {Kit.Icon.UP, Kit.Icon.DOWN, Kit.Icon.DOTS};
        for (int i = 0; i < icons.length; i++) {
            int bx = buttons + i * 12;
            boolean usable = i == 0 ? index > 0 : i == 1 ? index < scene.tracks().size() - 1 : true;
            boolean hovered = usable && rowHovered && mouseX >= bx && mouseX < bx + 11;
            Kit.face(graphics, bx, y + 1, 11, LANE - 3, hovered, false, usable);
            Kit.icon(graphics, icons[i], bx + 5, y + LANE / 2 - 1, usable ? Controls.PARCHMENT : 0xFF6A5A3A);
        }
    }

    private String trackTitle(Track track) {
        String kind = Kit.t("kind." + track.kind().id()).getString();
        return track.name().isEmpty() ? kind : kind + ": " + track.name();
    }

    // ------------------------------------------------------------------ The mouse

    /** A mouse button went down inside the panel. Returns the control that follows the mouse now, if it was one */
    @Nullable Gadget press(double mouseX, double mouseY, int button) {
        for (Gadget gadget : this.transport) {
            Gadget hit = gadget.press(mouseX, mouseY, button);
            if (hit != null) {
                return hit;
            }
        }
        Gadget folded = this.fold.press(mouseX, mouseY, button);
        if (folded != null) {
            return folded;
        }
        Gadget adding = this.addTrack.press(mouseX, mouseY, button);
        if (adding != null) {
            return adding;
        }
        Scene scene = EditorState.scene();
        int total = Math.max(1, Playback.total());
        if (button == 0 && this.overRuler(mouseX, mouseY)) {
            if (scene.hasExportRange() && mouseY >= this.lanesTop - 7) {
                double start = this.across(scene.exportStart());
                double end = this.across(scene.exportEnd());
                if (mouseX >= start - 2 && mouseX <= start + 5 && Math.abs(mouseX - start) <= Math.abs(mouseX - end)) {
                    this.drag = Drag.RANGE_START;
                    this.rangePreview = scene.exportStart();
                    return null;
                }
                if (mouseX >= end - 5 && mouseX <= end + 2) {
                    this.drag = Drag.RANGE_END;
                    this.rangePreview = scene.exportEnd();
                    return null;
                }
            }
            EditorState.scrubbing = true;
            this.wanted = this.tickAt(mouseX);
            return null;
        }
        if (this.overLanes(mouseX, mouseY)) {
            KeyframeRef hit = this.keyframeAt(mouseX, mouseY);
            if (button == 1) {
                if (hit != null) {
                    if (!EditorState.selection.contains(hit)) {
                        EditorState.selection.clear();
                        EditorState.selection.add(hit);
                    }
                    this.openKeyframeMenu(hit, mouseX, mouseY);
                }
                return null;
            }
            if (button != 0) {
                return null;
            }
            if (hit != null) {
                long now = System.currentTimeMillis();
                boolean again = hit.equals(this.lastClicked) && now - this.lastClickAt < 350;
                this.lastClicked = hit;
                this.lastClickAt = now;
                if (again) {
                    // A double click goes to the keyframe
                    Controls.jumpTo(hit.tick());
                    return null;
                }
                if (Screen.hasShiftDown()) {
                    if (!EditorState.selection.remove(hit)) {
                        EditorState.selection.add(hit);
                    }
                } else if (!EditorState.selection.contains(hit)) {
                    EditorState.selection.clear();
                    EditorState.selection.add(hit);
                }
                if (EditorState.selection.contains(hit)) {
                    EditorState.audioDraft = false;
                    EditorState.tab = EditorState.TAB_KEYFRAME;
                    this.drag = Drag.KEYS;
                    this.dragFromTick = this.tickAt(mouseX);
                    this.dragOffset = 0;
                }
            } else {
                this.boxAdds = Screen.hasShiftDown();
                if (!this.boxAdds) {
                    EditorState.selection.clear();
                }
                this.drag = Drag.BOX;
                this.boxFromX = this.boxToX = mouseX;
                this.boxFromY = this.boxToY = mouseY;
            }
            return null;
        }
        if (this.overColumn(mouseX, mouseY)) {
            int index = this.trackAt(mouseY);
            if (index >= 0) {
                this.pressTrack(scene, index, mouseX, mouseY, button);
            }
            return null;
        }
        if (button == 0 && EditorState.viewSpan < total && mouseY >= this.lanesBottom + 1 && mouseY < this.lanesBottom + 7 && mouseX >= this.laneLeft) {
            this.drag = Drag.PAN;
            this.dragTo(mouseX, mouseY);
        }
        return null;
    }

    private void pressTrack(Scene scene, int index, double mouseX, double mouseY, int button) {
        Track track = scene.track(index);
        EditHistory history = EditorState.history();
        int buttons = this.laneLeft - 1 - 3 * 12;
        if (button == 1) {
            this.openTrackMenu(index, mouseX, mouseY);
        } else if (button != 0) {
            return;
        } else if (mouseX < this.left + 15) {
            Kit.click();
            history.updateTrack(index, null, !track.enabled());
        } else if (mouseX >= buttons + 24) {
            this.openTrackMenu(index, mouseX, mouseY);
        } else if (mouseX >= buttons + 12) {
            if (index < scene.tracks().size() - 1) {
                Kit.click();
                this.moveTrack(index, index + 1);
            }
        } else if (mouseX >= buttons) {
            if (index > 0) {
                Kit.click();
                this.moveTrack(index, index - 1);
            }
        } else {
            // The name: every keyframe of the track
            if (!Screen.hasShiftDown()) {
                EditorState.selection.clear();
            }
            for (Keyframe keyframe : track.keyframes()) {
                EditorState.selection.add(new KeyframeRef(index, keyframe.tick()));
            }
        }
    }

    /** Moves a track, and the selection with it, since keyframes are known by the place of their track */
    private void moveTrack(int from, int to) {
        EditorState.history().moveTrack(from, to);
        Set<KeyframeRef> moved = new LinkedHashSet<>();
        for (KeyframeRef ref : EditorState.selection) {
            int track = ref.track();
            if (track == from) {
                track = to;
            } else if (from < to && track > from && track <= to) {
                track--;
            } else if (from > to && track >= to && track < from) {
                track++;
            }
            moved.add(new KeyframeRef(track, ref.tick()));
        }
        EditorState.selection.clear();
        EditorState.selection.addAll(moved);
    }

    void dragTo(double mouseX, double mouseY) {
        int total = Math.max(1, Playback.total());
        switch (this.drag) {
            case KEYS -> {
                int offset = this.tickAt(mouseX) - this.dragFromTick;
                // Every keyframe has to stay inside the recording
                for (KeyframeRef ref : EditorState.selection) {
                    offset = Math.max(offset, -ref.tick());
                    offset = Math.min(offset, Playback.total() - ref.tick());
                }
                this.dragOffset = offset;
            }
            case BOX -> {
                this.boxToX = Math.max(this.laneLeft, Math.min(this.laneLeft + this.laneWidth, mouseX));
                this.boxToY = Math.max(this.lanesTop, Math.min(this.lanesBottom - 1, mouseY));
            }
            case RANGE_START, RANGE_END -> this.rangePreview = this.tickAt(mouseX);
            case PAN -> {
                double share = (mouseX - this.laneLeft) / this.laneWidth;
                EditorState.viewStart = Math.max(0.0, Math.min(total - EditorState.viewSpan, share * total - EditorState.viewSpan / 2.0));
            }
            default -> {
            }
        }
    }

    void release(double mouseX, double mouseY) {
        Drag was = this.drag;
        this.drag = Drag.NONE;
        EditHistory history = EditorState.history();
        Scene scene = history.scene();
        switch (was) {
            case KEYS -> {
                int offset = this.dragOffset;
                this.dragOffset = 0;
                if (offset != 0 && !EditorState.selection.isEmpty()) {
                    Set<KeyframeRef> moving = new LinkedHashSet<>(EditorState.selection);
                    // One step to undo, however many keyframes moved
                    history.beginGroup("Move " + moving.size() + (moving.size() == 1 ? " keyframe" : " keyframes"));
                    try {
                        history.moveKeyframes(moving, offset);
                    } finally {
                        history.endGroup();
                    }
                    EditorState.selection.clear();
                    for (KeyframeRef ref : moving) {
                        EditorState.selection.add(new KeyframeRef(ref.track(), ref.tick() + offset));
                    }
                }
            }
            case BOX -> {
                double x0 = Math.min(this.boxFromX, this.boxToX) - 3;
                double x1 = Math.max(this.boxFromX, this.boxToX) + 3;
                double y0 = Math.min(this.boxFromY, this.boxToY);
                double y1 = Math.max(this.boxFromY, this.boxToY);
                if (x1 - x0 > 8 || y1 - y0 > 2) {
                    for (int index = 0; index < scene.tracks().size(); index++) {
                        int laneY = this.laneTop(index);
                        if (laneY + LANE <= y0 || laneY > y1 || laneY < this.lanesTop || laneY + LANE > this.lanesBottom + 1) {
                            continue;
                        }
                        for (Keyframe keyframe : scene.track(index).keyframes()) {
                            double x = this.across(keyframe.tick());
                            if (x >= x0 && x <= x1) {
                                EditorState.selection.add(new KeyframeRef(index, keyframe.tick()));
                            }
                        }
                    }
                    if (!EditorState.selection.isEmpty()) {
                        EditorState.audioDraft = false;
                        EditorState.tab = EditorState.TAB_KEYFRAME;
                    }
                }
            }
            case RANGE_START, RANGE_END -> {
                int tick = this.rangePreview;
                this.rangePreview = -1;
                if (tick >= 0 && scene.hasExportRange()) {
                    int start = was == Drag.RANGE_START ? Math.min(tick, scene.exportEnd()) : scene.exportStart();
                    int end = was == Drag.RANGE_END ? Math.max(tick, scene.exportStart()) : scene.exportEnd();
                    if (start != scene.exportStart() || end != scene.exportEnd()) {
                        history.setExportRange(start, end);
                    }
                }
            }
            default -> {
            }
        }
    }

    /** Whether the mouse is in the middle of something that began in this panel */
    boolean dragging() {
        return this.drag != Drag.NONE || EditorState.scrubbing;
    }

    boolean wheel(double mouseX, double mouseY, double sideways, double amount) {
        if (!this.over(mouseX, mouseY)) {
            return false;
        }
        int total = Math.max(1, Playback.total());
        if (this.overColumn(mouseX, mouseY)) {
            int tracks = EditorState.scene().tracks().size();
            EditorState.laneScroll = Math.max(0, Math.min(EditorState.laneScroll - (int) Math.signum(amount), Math.max(0, tracks - this.visibleLanes())));
            return true;
        }
        if (mouseX < this.laneLeft || mouseY < this.rulerTop) {
            return true;
        }
        if (Screen.hasShiftDown() || (amount == 0.0 && sideways != 0.0)) {
            double by = (amount != 0.0 ? amount : sideways) * EditorState.viewSpan * 0.1;
            EditorState.viewStart = Math.max(0.0, Math.min(total - EditorState.viewSpan, EditorState.viewStart - by));
            return true;
        }
        // Zoom about the tick under the mouse, so that it stays where it is
        double share = Math.max(0.0, Math.min(1.0, (mouseX - this.laneLeft) / this.laneWidth));
        double under = EditorState.viewStart + share * EditorState.viewSpan;
        double span = EditorState.viewSpan * Math.pow(1.25, -amount);
        span = Math.max(Math.min(total, 40.0), Math.min(total, span));
        EditorState.viewSpan = span;
        EditorState.viewStart = Math.max(0.0, Math.min(total - span, under - share * span));
        return true;
    }

    @Nullable Component tipAt(double mouseX, double mouseY) {
        if (!this.over(mouseX, mouseY) || this.drag != Drag.NONE) {
            return null;
        }
        for (Gadget gadget : this.transport) {
            Component words = gadget.tipAt(mouseX, mouseY);
            if (words != null) {
                return words;
            }
        }
        Component words = this.fold.tipAt(mouseX, mouseY);
        if (words == null) {
            words = this.addTrack.tipAt(mouseX, mouseY);
        }
        if (words != null) {
            return words;
        }
        Scene scene = EditorState.scene();
        if (this.overRuler(mouseX, mouseY)) {
            RecordingManifest manifest = Playback.manifest();
            if (manifest != null && !EditorState.scrubbing) {
                for (RecordingManifest.Marker marker : manifest.markers) {
                    double flag = this.across(marker.tick);
                    if (mouseX >= flag - 2 && mouseX <= flag + 6) {
                        return marker.description == null || marker.description.isBlank()
                                ? Component.translatable("dailies.ui.timeline.marker", Controls.time(marker.tick))
                                : Component.translatable("dailies.ui.timeline.marker_described", marker.description, Controls.time(marker.tick));
                    }
                }
            }
            if (scene.hasExportRange() && mouseY >= this.lanesTop - 7) {
                double start = this.across(scene.exportStart());
                double end = this.across(scene.exportEnd());
                if ((mouseX >= start - 2 && mouseX <= start + 5) || (mouseX >= end - 5 && mouseX <= end + 2)) {
                    return Kit.t("timeline.range.tip", scene.exportStart(), scene.exportEnd());
                }
            }
            return this.timeTip(this.tickAt(mouseX));
        }
        if (this.overLanes(mouseX, mouseY)) {
            KeyframeRef hit = this.keyframeAt(mouseX, mouseY);
            if (hit != null) {
                Keyframe keyframe = EditorState.keyframe(hit);
                if (keyframe != null) {
                    return Kit.t("timeline.keyframe.tip", Kit.t("kind." + keyframe.kind().id()), Controls.time(hit.tick()), hit.tick());
                }
            }
            return null;
        }
        if (this.overColumn(mouseX, mouseY)) {
            int index = this.trackAt(mouseY);
            if (index < 0) {
                return null;
            }
            int buttons = this.laneLeft - 1 - 3 * 12;
            if (mouseX < this.left + 15) {
                return Kit.t("track.enabled.tip");
            }
            if (mouseX >= buttons + 24) {
                return Kit.t("track.menu.tip");
            }
            if (mouseX >= buttons + 12) {
                return Kit.t("track.down.tip");
            }
            if (mouseX >= buttons) {
                return Kit.t("track.up.tip");
            }
            return Kit.t("track.name.tip", this.trackTitle(scene.track(index)), scene.track(index).size());
        }
        return null;
    }

    private Component timeTip(int tick) {
        TimeMap map = this.realTime();
        if (map != null) {
            return Kit.t("timeline.pointer_video", Controls.time(tick), tick, Kit.seconds(map.realSecondsAt(tick)));
        }
        return Kit.t("timeline.pointer", Controls.time(tick), tick);
    }

    // ------------------------------------------------------------------ Menus

    private void openAddMenu() {
        List<Menu.Item> items = new ArrayList<>();
        for (KeyframeKind kind : KeyframeKind.values()) {
            items.add(new Menu.Item(Kit.t("kind." + kind.id()), () -> {
                int index = EditorState.history().addTrack(kind);
                EditorState.laneScroll = Math.max(0, index - this.visibleLanes() + 1);
            }));
        }
        Minecraft minecraft = Minecraft.getInstance();
        double scale = (double) minecraft.getWindow().getGuiScaledWidth() / Math.max(1, minecraft.getWindow().getScreenWidth());
        Menu.show(items, minecraft.mouseHandler.xpos() * scale, minecraft.mouseHandler.ypos() * scale);
    }

    private void openTrackMenu(int index, double mouseX, double mouseY) {
        Scene scene = EditorState.scene();
        Track track = scene.track(index);
        EditHistory history = EditorState.history();
        List<Menu.Item> items = new ArrayList<>();
        items.add(new Menu.Item(Kit.t("track.rename"), () -> this.renameTrack(index)));
        items.add(new Menu.Item(Kit.t(track.enabled() ? "track.disable" : "track.enable"), () -> history.updateTrack(index, null, !track.enabled())));
        items.add(new Menu.Item(Kit.t("track.up"), () -> this.moveTrack(index, index - 1), index > 0, false));
        items.add(new Menu.Item(Kit.t("track.down"), () -> this.moveTrack(index, index + 1), index < scene.tracks().size() - 1, false));
        items.add(new Menu.Item(Kit.t("track.clear"), () -> {
            EditorState.selection.removeIf(ref -> ref.track() == index);
            history.clearTrack(index);
        }, !track.isEmpty(), false));
        items.add(new Menu.Item(Kit.t("track.remove"), () -> this.removeTrack(index)));
        Menu.show(items, mouseX, mouseY);
    }

    private void openKeyframeMenu(KeyframeRef hit, double mouseX, double mouseY) {
        List<Menu.Item> items = new ArrayList<>();
        items.add(new Menu.Item(Kit.t("keyframe.jump"), () -> Controls.jumpTo(hit.tick())));
        items.add(new Menu.Item(Kit.t("keyframe.copy"), EditorState::copySelected));
        items.add(new Menu.Item(Kit.t("keyframe.remove"), EditorState::removeSelected));
        Menu.show(items, mouseX, mouseY);
    }

    private void renameTrack(int index) {
        Minecraft minecraft = Minecraft.getInstance();
        Track track = EditorState.scene().track(index);
        minecraft.setScreen(new NameScreen(
                Kit.t("track.rename.title"),
                Kit.t("track.rename.explanation", Kit.t("kind." + track.kind().id())),
                track.name(),
                Kit.t("button.rename"),
                Kit.t("button.cancel"),
                name -> {
                    EditorState.guard(() -> EditorState.history().updateTrack(index, name, null));
                    EditorScreen.open();
                },
                EditorScreen::open));
    }

    private void removeTrack(int index) {
        Minecraft minecraft = Minecraft.getInstance();
        Track track = EditorState.scene().track(index);
        minecraft.setScreen(new ConfirmScreen(
                yes -> {
                    if (yes) {
                        EditorState.guard(() -> {
                            EditorState.selection.clear();
                            EditorState.history().removeTrack(index);
                        });
                    }
                    EditorScreen.open();
                },
                Kit.t("track.remove.title"),
                Kit.t("track.remove.question", this.trackTitle(track), track.size()),
                Kit.t("track.remove.yes"),
                Kit.t("button.cancel")));
    }

}
