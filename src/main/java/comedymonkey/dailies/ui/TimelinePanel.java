package comedymonkey.dailies.ui;

import comedymonkey.dailies.edit.EditHistory;
import comedymonkey.dailies.edit.Keyframe;
import comedymonkey.dailies.edit.KeyframeKind;
import comedymonkey.dailies.edit.KeyframeRef;
import comedymonkey.dailies.edit.Scene;
import comedymonkey.dailies.edit.TimeMap;
import comedymonkey.dailies.edit.Track;
import comedymonkey.dailies.editor.EditorSession;
import comedymonkey.dailies.editor.PointOfView;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
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
 * a column at the left. Between the ruler and the rows, when there are any and they are wanted, the recording's
 * moments: see {@link MomentsLane}.
 * <p>
 * The rows are compact and scroll when there are more tracks than room, by the wheel over their names or by the bar
 * at their right; the wheel over the rows themselves zooms about the tick under the mouse. How high the panel is
 * comes from {@link EditorLayout}, which gives rows up before the view gets small.
 */
final class TimelinePanel {

    private static final int BAR = EditorLayout.ROW;
    private static final int RULER = EditorLayout.RULER;
    private static final int LANE = EditorLayout.LANE;
    private static final int FOOT = EditorLayout.FOOT;
    /** How near the mouse has to be to a keyframe's middle, across, to take hold of it */
    private static final double REACH = 6.0;
    /** The space between two groups of transport buttons */
    private static final int GROUP_GAP = 8;
    /** Where each group of transport buttons ends, as an index into the list of them: a little room follows it */
    private static final int[] GROUP_ENDS = {4, 7};
    private static final int SEGMENT_LINE = 0x90EFE6D6;
    private static final int RANGE_TINT = 0x38D4B25A;
    private static final double[] RULER_STEPS = {0.05, 0.25, 0.5, 1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 1800, 3600};

    /** What the left mouse button is in the middle of doing in the rows */
    private enum Drag {
        NONE, KEYS, BOX, RANGE_START, RANGE_END, PAN, LANE_BAR
    }

    private int left;
    private int top;
    private int right;
    private int bottom;
    private int columnWidth;
    private int laneLeft;
    private int laneWidth;
    private int rulerTop;
    /** Where the ruler ends; the moments rows, when shown, lie between here and the track rows */
    private int rulerBottom;
    private MomentsLane.Place moments = new MomentsLane.Place(0, 0, 1, 0);
    /** Where the lanes of the scene's points of view start (PLAY-19), under the moments */
    private int viewsTop = 0;
    private @Nullable Font font;
    private int lanesTop;
    private int lanesBottom;
    private int statusLeft;
    private int statusRight;
    /** Where the rows' scroll bar stands; it is only there when there are more tracks than rows */
    private int laneBarLeft;
    private int laneBarGrab;

    private final List<Gadget> transport = new ArrayList<>();
    private final Gadget.Push follow;
    private final Gadget.Push fold;
    private final Gadget.Push momentsSwitch;
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
        this.transport.add(Gadget.Push.icon(Kit.Icon.STEP_BACK, Kit.t("transport.step_back"), () -> Controls.jumpTo(Math.max(0, PointOfView.position() - 1))));
        this.transport.add(Gadget.Push.icon(() -> Playback.paused() ? Kit.Icon.PLAY : Kit.Icon.PAUSE, Kit.t("transport.play"), Controls::togglePlay)
                .liveTip(() -> Kit.t(Playback.paused() ? "transport.play" : "transport.pause")).wide(20));
        this.transport.add(Gadget.Push.icon(Kit.Icon.STEP_FORWARD, Kit.t("transport.step_forward"), Controls::stepOneTick));
        this.transport.add(Gadget.Push.icon(Kit.Icon.TO_END, Kit.t("transport.to_end"), () -> Controls.jumpTo(PointOfView.total())));
        this.transport.add(Gadget.Push.icon(Kit.Icon.MINUS, Kit.t("transport.slower"), Controls::slower));
        this.transport.add(new Gadget.Words(() -> Kit.t("transport.speed", Controls.speed(Playback.ticksPerSecond())), Controls.PARCHMENT)
                .wide(34).tip(Kit.t("transport.speed.tip")));
        this.transport.add(Gadget.Push.icon(Kit.Icon.PLUS, Kit.t("transport.faster"), Controls::faster));
        this.follow = new Gadget.Push(() -> Kit.t(EditorSession.followCamera() ? "transport.camera_track" : "transport.camera_free"), Controls::toggleFollowCamera);
        this.follow.lit(EditorSession::followCamera).tip(Kit.t("transport.camera.tip"));
        this.transport.add(this.follow);
        this.fold = Gadget.Push.icon(() -> EditorState.timelineFolded ? Kit.Icon.FOLD_UP : Kit.Icon.FOLD_DOWN, Kit.t("timeline.fold"),
                () -> EditorState.timelineFolded = !EditorState.timelineFolded);
        this.momentsSwitch = new Gadget.Push(Kit.t("moments.switch"), () -> MomentsLane.setShown(!MomentsLane.shown()));
        this.momentsSwitch.lit(MomentsLane::shown).tip(Kit.t("moments.switch.tip"));
        this.addTrack = new Gadget.Push(Kit.t("track.add"), this::openAddMenu);
        this.addTrack.tip(Kit.t("track.add.tip"));
    }

    void place(Font font, int newLeft, int newTop, int newRight, int newBottom) {
        this.left = newLeft;
        this.top = newTop;
        this.right = newRight;
        this.bottom = newBottom;
        int width = newRight - newLeft;
        int pad = EditorLayout.PAD;
        this.columnWidth = Math.max(110, Math.min(160, width * 15 / 100)) & ~1;
        this.laneLeft = newLeft + this.columnWidth + 1;
        this.font = font;
        this.rulerTop = newTop + EditorLayout.TRANSPORT;
        this.rulerBottom = this.rulerTop + RULER;
        // The rows give way before the moments do, but never altogether: a short window keeps two rows
        this.viewsTop = this.rulerBottom + MomentsLane.height();
        this.lanesTop = Math.min(this.viewsTop + PointOfViewLanes.height(), Math.max(this.rulerBottom, newBottom - FOOT - EditorLayout.FEWEST_LANES * LANE));
        this.lanesBottom = EditorState.timelineFolded ? this.lanesTop : Math.max(this.lanesTop, newBottom - FOOT);
        int tracks = EditorSession.open() ? EditorState.scene().tracks().size() : 0;
        int rows = Math.max(0, (this.lanesBottom - this.lanesTop) / LANE);
        boolean scrolls = rows > 0 && tracks > rows;
        // The rows' scroll bar has a strip of its own at the right, so that it never lies over a keyframe
        this.laneBarLeft = newRight - pad - Kit.BAR;
        this.laneWidth = Math.max(20, (scrolls ? this.laneBarLeft - 3 : newRight - pad) - this.laneLeft);
        this.moments = new MomentsLane.Place(newLeft, this.laneLeft, this.laneWidth, this.rulerBottom);

        // The transport buttons in their groups: where the playhead goes, how fast it goes, and who moves the camera
        int widest = Math.max(font.width(Kit.t("transport.camera_track")), font.width(Kit.t("transport.camera_free"))) + 12;
        this.follow.wide(widest);
        int x = newLeft + pad;
        int y = newTop + EditorLayout.GAP;
        for (int i = 0; i < this.transport.size(); i++) {
            Gadget gadget = this.transport.get(i);
            int wide = gadget.wantedWidth(font);
            gadget.clip(Integer.MIN_VALUE, Integer.MAX_VALUE);
            gadget.place(font, x, y, wide, BAR);
            x += wide + EditorLayout.GAP;
            for (int end : GROUP_ENDS) {
                if (i == end) {
                    x += GROUP_GAP - EditorLayout.GAP;
                }
            }
        }
        this.fold.clip(Integer.MIN_VALUE, Integer.MAX_VALUE);
        this.fold.place(font, newRight - pad - 16, y, 16, BAR);
        int switchWide = this.momentsSwitch.wantedWidth(font);
        this.momentsSwitch.clip(Integer.MIN_VALUE, Integer.MAX_VALUE);
        this.momentsSwitch.place(font, newRight - pad - 16 - EditorLayout.GAP - switchWide, y, switchWide, BAR);
        this.statusLeft = x + GROUP_GAP - EditorLayout.GAP;
        this.statusRight = this.momentsSwitch.x - GROUP_GAP;
        this.addTrack.clip(Integer.MIN_VALUE, Integer.MAX_VALUE);
        this.addTrack.place(font, newLeft + pad, this.rulerTop, this.columnWidth - pad - 2, RULER);

        int total = Math.max(1, PointOfView.total());
        if (EditorState.viewSpan <= 0.0 || EditorState.viewSpan > total) {
            EditorState.viewSpan = total;
        }
        EditorState.viewSpan = Math.max(Math.min(total, 40.0), EditorState.viewSpan);
        EditorState.viewStart = Math.max(0.0, Math.min(total - EditorState.viewSpan, EditorState.viewStart));
        EditorState.laneScroll = Math.max(0, Math.min(EditorState.laneScroll, EditorState.scene().tracks().size() - rows));
    }

    private int visibleLanes() {
        return Math.max(0, (this.lanesBottom - this.lanesTop) / LANE);
    }

    boolean over(double mouseX, double mouseY) {
        return mouseX >= this.left && mouseX < this.right && mouseY >= this.top && mouseY < this.bottom;
    }

    /** Its buttons, in the order the Tab key goes through them */
    void collect(List<Gadget> order) {
        for (Gadget gadget : this.transport) {
            gadget.collect(order);
        }
        this.momentsSwitch.collect(order);
        this.fold.collect(order);
        this.addTrack.collect(order);
    }

    /** Whether there are more tracks than rows, so that the rows scroll */
    private boolean lanesScroll() {
        return this.visibleLanes() > 0 && EditorState.scene().tracks().size() > this.visibleLanes();
    }

    private boolean overLaneBar(double mouseX, double mouseY) {
        return this.lanesScroll() && mouseX >= this.laneBarLeft && mouseX < this.laneBarLeft + Kit.BAR
                && mouseY >= this.lanesTop && mouseY < this.lanesTop + this.visibleLanes() * LANE;
    }

    /** Scrolls the rows so that the top of the bar's thumb is at this height */
    private void dragLaneBar(double mouseY) {
        int rows = this.visibleLanes();
        int tracks = EditorState.scene().tracks().size();
        int height = rows * LANE;
        int scroll = Kit.scrollFor((int) mouseY - this.laneBarGrab, this.lanesTop, height, tracks * LANE);
        EditorState.laneScroll = Math.max(0, Math.min(tracks - rows, Math.round(scroll / (float) LANE)));
    }

    // ------------------------------------------------------------------ Ticks and pixels

    private double across(double tick) {
        return this.laneLeft + (tick - EditorState.viewStart) / EditorState.viewSpan * this.laneWidth;
    }

    private int tickAt(double mouseX) {
        double tick = EditorState.viewStart + (mouseX - this.laneLeft) / this.laneWidth * EditorState.viewSpan;
        return (int) Math.max(0, Math.min(PointOfView.total(), Math.round(tick)));
    }

    private boolean overRuler(double mouseX, double mouseY) {
        return mouseX >= this.laneLeft - 2 && mouseX < this.laneLeft + this.laneWidth + 3 && mouseY >= this.rulerTop && mouseY < this.rulerBottom;
    }

    /** Whether the moments rows are there and the mouse is over them */
    private boolean overMoments(double mouseX, double mouseY) {
        return this.lanesTop > this.rulerBottom && mouseY < this.lanesTop && MomentsLane.over(this.moments, mouseX, mouseY);
    }

    /** Whether the mouse is over the lanes of the scene's points of view */
    private boolean overViews(double mouseX, double mouseY) {
        return PointOfViewLanes.height() > 0 && mouseY < this.lanesTop && PointOfViewLanes.over(this.laneLeft, this.laneLeft + this.laneWidth, this.viewsTop, mouseX, mouseY);
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
        double best = REACH;
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
            if (tick != PointOfView.position()) {
                Controls.scrubTo(tick);
            }
        } else if (this.refineOwed && this.wanted < 0 && !EditorState.scrubbing && !Playback.jumping()) {
            this.refineOwed = false;
            Controls.refine();
        }
        int total = Math.max(1, PointOfView.total());
        if (!Playback.paused() && !EditorState.scrubbing && this.drag == Drag.NONE && EditorState.viewSpan < total) {
            int position = PointOfView.position();
            if (position < EditorState.viewStart || position > EditorState.viewStart + EditorState.viewSpan) {
                EditorState.viewStart = Math.max(0.0, Math.min(total - EditorState.viewSpan, position - EditorState.viewSpan * 0.1));
            }
        }
    }

    /** How long the video runs up to each tick, when speed, timelapse or freeze keyframes change that; else null */
    private @Nullable TimeMap realTime() {
        EditHistory history = EditorState.history();
        long stamp = history.revision() * 31 + PointOfView.total();
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
            if (changes && PointOfView.total() > 0) {
                try {
                    this.realTime = new TimeMap(scene, 0, PointOfView.total());
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
        this.momentsSwitch.draw(graphics, font, mouseX, mouseY);
        this.drawStatus(graphics, font);
        this.addTrack.draw(graphics, font, mouseX, mouseY);

        Scene scene = EditorState.scene();
        int total = Math.max(1, PointOfView.total());
        int laneRight = this.laneLeft + this.laneWidth;

        // The ruler and the rows
        graphics.fill(this.laneLeft, this.rulerTop, laneRight + 1, this.rulerBottom, Controls.FIELD);
        graphics.fill(this.laneLeft, this.rulerBottom - 1, laneRight + 1, this.rulerBottom, Controls.BRASS_DIM);
        int rows = this.visibleLanes();
        int hoveredTrack = this.drag == Drag.NONE && (this.overLanes(mouseX, mouseY) || this.overColumn(mouseX, mouseY)) ? this.trackAt(mouseY) : -1;
        for (int row = 0; row < rows; row++) {
            int index = EditorState.laneScroll + row;
            int y = this.lanesTop + row * LANE;
            int shade = index < scene.tracks().size() ? (row % 2 == 0 ? 0x50000000 : 0x30000000) : 0x18000000;
            graphics.fill(this.laneLeft, y, laneRight + 1, y + LANE - 1, shade);
            if (index == hoveredTrack) {
                // The row under the mouse, across names and keyframes alike, so the eye can follow it
                graphics.fill(this.left + 1, y, laneRight + 1, y + LANE - 1, 0x14EFE6D6);
            }
        }

        graphics.enableScissor(this.laneLeft, this.rulerTop, laneRight + 1, Math.max(this.lanesTop, this.lanesBottom));
        try {
            this.drawRange(graphics, scene, total);
            this.drawRuler(graphics, font, total);
            this.drawMarks(graphics, total);
            if (this.lanesTop > this.rulerBottom) {
                MomentsLane.draw(graphics, font, this.moments, laneRight, mouseX, mouseY);
            }
            if (this.lanesTop >= this.viewsTop + PointOfViewLanes.height() && PointOfViewLanes.height() > 0) {
                graphics.disableScissor();
                PointOfViewLanes.draw(graphics, font, this.left, this.laneLeft, laneRight, this.viewsTop, total, this::across);
                graphics.enableScissor(this.laneLeft, this.rulerTop, laneRight + 1, Math.max(this.lanesTop, this.lanesBottom));
            }
            KeyframeRef under = this.drag == Drag.NONE && this.overLanes(mouseX, mouseY) ? this.keyframeAt(mouseX, mouseY) : null;
            for (int row = 0; row < rows; row++) {
                int index = EditorState.laneScroll + row;
                if (index < scene.tracks().size()) {
                    this.drawKeyframes(graphics, scene.track(index), index, this.lanesTop + row * LANE, under);
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
            // The playhead: a head on the ruler to take hold of, and a line down through the rows
            int hand = (int) Math.round(this.across(Math.max(0, Math.min(total, this.scrubTick >= 0 ? this.scrubTick : PointOfView.shown()))));
            graphics.fill(hand, this.rulerTop, hand + 1, Math.max(this.lanesTop, this.lanesBottom), Controls.PARCHMENT);
            graphics.fill(hand - 3, this.rulerTop, hand + 4, this.rulerTop + 4, Controls.PARCHMENT);
            for (int i = 1; i <= 3; i++) {
                graphics.fill(hand - 3 + i, this.rulerTop + 3 + i, hand + 4 - i, this.rulerTop + 4 + i, Controls.PARCHMENT);
            }
        } finally {
            graphics.disableScissor();
        }

        if (this.lanesTop > this.rulerBottom) {
            MomentsLane.drawColumn(graphics, font, this.moments, this.laneLeft - 1, mouseX, mouseY);
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
        if (this.lanesScroll()) {
            // More tracks than rows: a bar at the right says where among them the rows are, and can be dragged
            boolean bright = this.drag == Drag.LANE_BAR || this.overLaneBar(mouseX, mouseY);
            Kit.scrollBar(graphics, this.laneBarLeft, this.lanesTop, rows * LANE, scene.tracks().size() * LANE, EditorState.laneScroll * LANE, bright);
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
        int position = this.scrubTick >= 0 ? this.scrubTick : PointOfView.shown();
        String time = Component.translatable("dailies.ui.bar.time", Controls.time(position), Controls.time(PointOfView.total())).getString();
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
        graphics.fill(from, this.rulerBottom - 6, from + 4, this.rulerBottom, Controls.BRASS_BRIGHT);
        graphics.fill(from, this.rulerTop, from + 1, this.rulerBottom, Controls.BRASS_BRIGHT);
        graphics.fill(to - 3, this.rulerBottom - 6, to + 1, this.rulerBottom, Controls.BRASS_BRIGHT);
        graphics.fill(to, this.rulerTop, to + 1, this.rulerBottom, Controls.BRASS_BRIGHT);
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
            graphics.fill(x, this.rulerBottom - 5, x + 1, this.rulerBottom, Controls.BRASS);
            int whole = (int) Math.round(tick);
            String label = step < 1.0 ? Controls.time(whole) : Controls.length(whole);
            Kit.text(graphics, font, label, x + 2, this.rulerTop + 2, Controls.PARCHMENT_DIM);
            int half = (int) Math.round(this.across(tick + stepTicks / 2.0));
            if (tick + stepTicks / 2.0 <= total) {
                graphics.fill(half, this.rulerBottom - 2, half + 1, this.rulerBottom, Controls.BRASS);
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
            graphics.fill(x, this.rulerTop + 1, x + 1, this.rulerBottom, colour);
            graphics.fill(x + 1, this.rulerTop + 1, x + 5, this.rulerTop + 5, colour);
        }
    }

    private void drawKeyframes(GuiGraphics graphics, Track track, int index, int y, @Nullable KeyframeRef under) {
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
            KeyframeRef ref = new KeyframeRef(index, keyframe.tick());
            boolean selected = EditorState.selection.contains(ref);
            boolean hovered = ref.equals(under);
            int x = (int) Math.round(this.across(keyframe.tick()));
            if (selected) {
                Kit.diamond(graphics, x, middle, 4, Controls.PARCHMENT);
                Kit.diamond(graphics, x, middle, 2, Controls.ALERT);
            } else if (hovered) {
                // The one a click would take: a size larger and in parchment
                Kit.diamond(graphics, x, middle, 4, 0xFF1A1108);
                Kit.diamond(graphics, x, middle, 3, Controls.PARCHMENT);
            } else {
                // A dark rim, so a diamond reads against the range's tint and the line it sits on
                Kit.diamond(graphics, x, middle, 4, 0xC01A1108);
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
            Kit.face(graphics, bx, y + 1, 11, LANE - 2, hovered, false, usable);
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
        Gadget switched = this.momentsSwitch.press(mouseX, mouseY, button);
        if (switched != null) {
            return switched;
        }
        Gadget adding = this.addTrack.press(mouseX, mouseY, button);
        if (adding != null) {
            return adding;
        }
        if (this.overMoments(mouseX, mouseY)) {
            if (this.font != null) {
                MomentsLane.press(this.font, this.moments, mouseX, mouseY, button);
            }
            return null;
        }
        if (this.overViews(mouseX, mouseY)) {
            if (button == 0) {
                PointOfViewLanes.press(this.viewsTop, mouseY, this.tickAt(mouseX));
            }
            return null;
        }
        Scene scene = EditorState.scene();
        int total = Math.max(1, PointOfView.total());
        if (this.overLaneBar(mouseX, mouseY)) {
            if (button == 0) {
                // Taking hold of the thumb drags it from where it was taken; a click beside it brings its middle there
                int[] thumb = Kit.thumb(this.lanesTop, this.visibleLanes() * LANE, scene.tracks().size() * LANE, EditorState.laneScroll * LANE);
                this.laneBarGrab = mouseY >= thumb[0] && mouseY < thumb[0] + thumb[1] ? (int) mouseY - thumb[0] : thumb[1] / 2;
                this.drag = Drag.LANE_BAR;
                this.dragLaneBar(mouseY);
            }
            return null;
        }
        if (button == 0 && this.overRuler(mouseX, mouseY)) {
            if (scene.hasExportRange() && mouseY >= this.rulerBottom - 7) {
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
        int total = Math.max(1, PointOfView.total());
        switch (this.drag) {
            case KEYS -> {
                int offset = this.tickAt(mouseX) - this.dragFromTick;
                // Every keyframe has to stay inside the recording
                for (KeyframeRef ref : EditorState.selection) {
                    offset = Math.max(offset, -ref.tick());
                    offset = Math.min(offset, PointOfView.total() - ref.tick());
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
            case LANE_BAR -> this.dragLaneBar(mouseY);
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
        int total = Math.max(1, PointOfView.total());
        if (this.overColumn(mouseX, mouseY) || this.overLaneBar(mouseX, mouseY)) {
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
            words = this.momentsSwitch.tipAt(mouseX, mouseY);
        }
        if (words == null) {
            words = this.addTrack.tipAt(mouseX, mouseY);
        }
        if (words != null) {
            return words;
        }
        if (this.overMoments(mouseX, mouseY)) {
            return this.font == null ? null : MomentsLane.tipAt(this.font, this.moments, mouseX, mouseY);
        }
        if (this.overViews(mouseX, mouseY)) {
            return PointOfViewLanes.tipAt(this.viewsTop, mouseY);
        }
        Scene scene = EditorState.scene();
        if (this.overLaneBar(mouseX, mouseY)) {
            return Kit.t("timeline.lanes.tip", EditorState.laneScroll + 1, Math.min(scene.tracks().size(), EditorState.laneScroll + this.visibleLanes()), scene.tracks().size());
        }
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
            if (scene.hasExportRange() && mouseY >= this.rulerBottom - 7) {
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
        minecraft.setScreen(new AskScreen(
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
