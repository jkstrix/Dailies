package comedymonkey.dailies.ui;

import com.mojang.blaze3d.platform.InputConstants;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.file.RecordingFile;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.file.Spans;
import comedymonkey.dailies.library.Library;
import comedymonkey.dailies.library.LibraryJob;
import comedymonkey.dailies.library.LibraryQuery;
import comedymonkey.dailies.moments.Moments;
import comedymonkey.dailies.moments.MomentsTimeline;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Trimming a recording into a new one (specification FILE-10, the screen of REC-5): a timeline of the whole
 * recording with its moments on it (events as marks, clips as bands), so a cut can be made around the good bits.
 * A stretch is chosen by dragging along the timeline and then kept alone, cut out or kept as well; what is kept is
 * shown in brass, what is cut in shadow, and listed underneath where each end can be typed. The trim runs as a job
 * that can be cancelled, and the source is never touched.
 * <p>
 * Opened from the editor, the scene's export range is the first choice and what is kept.
 */
public final class TrimScreen extends KitScreen {

    private static final String KEYS = "dailies.ui.trim.";

    private final Path file;
    private final String recordingId;
    private final String sourceName;
    private final int total;
    private List<Spans.Range> keep;
    private int selectFrom = -1;
    private int selectTo = -1;
    private String name;
    private boolean leaveWorld = false;
    private @Nullable MomentsTimeline.Timeline timeline = null;
    private List<RecordingManifest.Marker> markers = List.of();
    private @Nullable LibraryJob job = null;
    private String rangesBuiltFor = "";

    private final Form ranges = new Form();
    private Ruler line;
    private Gadget.Push keepOnly;
    private Gadget.Push cut;
    private Gadget.Push keepToo;
    private Gadget.Push reset;
    private Entry nameBox;
    private Gadget.Check leaveCheck;
    private Gadget.Words summary;
    private LibraryBits.JobBar bar;
    private Gadget.Push start;
    private Gadget.Push close;

    /**
     * @param fromTick the first tick chosen and kept to begin with, or -1 for none (the whole recording is kept)
     * @param toTick   the tick after the last one chosen
     */
    public TrimScreen(@Nullable Screen parent, Path file, String recordingId, String sourceName, int total, int fromTick, int toTick) {
        super(Component.translatable(KEYS + "title", sourceName), parent);
        this.file = file;
        this.recordingId = recordingId;
        this.sourceName = sourceName;
        this.total = Math.max(1, total);
        this.name = Component.translatable(KEYS + "default_name", sourceName).getString();
        if (fromTick >= 0 && toTick > fromTick) {
            this.selectFrom = Math.max(0, fromTick);
            this.selectTo = Math.min(this.total, toTick);
            this.keep = Spans.normalise(List.of(new Spans.Range(this.selectFrom, this.selectTo)), this.total);
        } else {
            this.keep = List.of(new Spans.Range(0, this.total));
        }
        if (this.keep.isEmpty()) {
            this.keep = List.of(new Spans.Range(0, this.total));
        }
    }

    /** The trim screen for the recording open in the editor, starting from the current scene's export range */
    public static TrimScreen fromEditor(@Nullable Screen parent) {
        RecordingFile open = Playback.file();
        if (open == null) {
            throw new IllegalStateException("No recording is open");
        }
        comedymonkey.dailies.edit.Scene scene = comedymonkey.dailies.editor.EditorSession.scene();
        int from = scene.hasExportRange() ? scene.exportStart() : -1;
        int to = scene.hasExportRange() ? scene.exportEnd() + 1 : -1;
        String name = open.manifest().name.isBlank() ? open.file().getFileName().toString() : open.manifest().name;
        return new TrimScreen(parent, open.file(), open.id(), name, Playback.total(), from, to);
    }

    private static Component t(String key, Object... arguments) {
        return Component.translatable(KEYS + key, arguments);
    }

    private boolean running() {
        return this.job != null && this.job.state() == LibraryJob.State.RUNNING;
    }

    private boolean selected() {
        return this.selectFrom >= 0 && this.selectTo > this.selectFrom;
    }

    private Spans.Range selection() {
        return new Spans.Range(this.selectFrom, this.selectTo);
    }

    @Override
    void build() {
        this.line = new Ruler();
        this.keepOnly = new Gadget.Push(t("keep_only"), () -> this.change(List.of(this.selection())));
        this.keepOnly.when(() -> this.selected() && !this.running()).tip(t("keep_only.tip"));
        this.cut = new Gadget.Push(t("cut"), () -> this.change(LibraryQuery.cutFrom(this.keep, this.selection(), this.total)));
        this.cut.when(() -> this.selected() && !this.running()).tip(t("cut.tip"));
        this.keepToo = new Gadget.Push(t("keep_too"), () -> this.change(LibraryQuery.addTo(this.keep, this.selection(), this.total)));
        this.keepToo.when(() -> this.selected() && !this.running()).tip(t("keep_too.tip"));
        this.reset = new Gadget.Push(t("reset"), () -> {
            this.selectFrom = -1;
            this.selectTo = -1;
            this.change(List.of(new Spans.Range(0, this.total)));
        });
        this.reset.when(() -> !this.running()).tip(t("reset.tip"));
        this.nameBox = new Entry(() -> this.name, typed -> {
            this.name = typed;
            return null;
        }).live(typed -> {
            this.name = typed;
            return null;
        }).max(80);
        this.nameBox.tip(t("name.tip"));
        this.leaveCheck = new Gadget.Check(t("leave_world"), () -> this.leaveWorld, on -> this.leaveWorld = on);
        this.leaveCheck.tip(t("leave_world.tip"));
        this.summary = new Gadget.Words(this::summaryWords, Controls.PARCHMENT_DIM);
        this.summary.tip(t("summary.tip"));
        this.bar = new LibraryBits.JobBar(() -> this.job);
        this.start = new Gadget.Push(t("start"), this::startTrim);
        this.start.when(() -> !this.running() && this.trimsSomething() && !this.name.isBlank()).tip(t("start.tip"));
        this.close = new Gadget.Push(() -> t(this.running() ? "cancel" : "close"), () -> {
            if (this.running()) {
                this.job.cancel();
            } else {
                this.onClose();
            }
        });
        this.close.liveTip(() -> t(this.running() ? "cancel.tip" : "close.tip"));
        for (Gadget gadget : List.of(this.line, this.keepOnly, this.cut, this.keepToo, this.reset, this.nameBox, this.leaveCheck, this.summary, this.bar, this.start, this.close)) {
            this.fixed.add(gadget);
        }
        this.forms.add(this.ranges);
        this.rangesBuiltFor = "";
        if (this.timeline == null) {
            this.load();
        }
    }

    private boolean trimsSomething() {
        return !this.keep.isEmpty() && !(this.keep.size() == 1 && this.keep.get(0).from() == 0 && this.keep.get(0).to() >= this.total);
    }

    private void change(List<Spans.Range> next) {
        List<Spans.Range> normal = Spans.normalise(next, this.total);
        if (normal.isEmpty()) {
            Notices.show(t("nothing_left"));
            return;
        }
        this.keep = normal;
    }

    private Component summaryWords() {
        int kept = 0;
        for (Spans.Range range : this.keep) {
            kept += range.length();
        }
        int cuts = Spans.invert(this.keep, this.total).size();
        if (!this.trimsSomething()) {
            return t("summary.all", Controls.length(this.total));
        }
        return t(cuts == 1 ? "summary_one" : "summary", Controls.length(kept), Controls.length(this.total), Controls.length(this.total - kept), cuts);
    }

    /** The moments of the recording, and its markers, off the client thread */
    private void load() {
        this.timeline = MomentsTimeline.Timeline.empty(this.recordingId);
        Minecraft game = Minecraft.getInstance();
        String id = this.recordingId;
        CompletableFuture.supplyAsync(() -> {
            List<RecordingManifest.Marker> found = List.of();
            try {
                found = RecordingFile.readManifest(this.file).markers;
            } catch (Exception unreadable) {
                // Shown without them
            }
            MomentsTimeline.Timeline moments;
            try {
                moments = Moments.timelineFor(id);
            } catch (Throwable t) {
                Host.LOGGER.warn("Dailies: the moments of a recording could not be put on the trim timeline", t);
                moments = MomentsTimeline.Timeline.empty(id);
            }
            return new Loaded(moments, found);
        }, Util.ioPool()).whenComplete((loaded, error) -> game.execute(() -> {
            if (loaded != null) {
                this.timeline = loaded.timeline();
                this.markers = loaded.markers();
            }
        }));
    }

    private record Loaded(MomentsTimeline.Timeline timeline, List<RecordingManifest.Marker> markers) {
    }

    private boolean inLiveWorld() {
        return this.minecraft != null && this.minecraft.level != null && !Playback.active();
    }

    @Override
    void arrange(Font font) {
        this.centre(760, 0);
        Component under = t("explained");
        int head = this.headingHeight(font, under);
        int wanted = head + 76 + 6 + Kit.ROW + 18 + 90 + 6 + Kit.ROW * 3 + 6 * 3 + Kit.ROW + 2 + 8 + 8;
        this.centre(760, Math.min(this.height - 24, wanted));
        int inner = this.right - this.left - 16;
        int x = this.left + 8;
        int y = this.top + head;
        place(font, this.line, x, y, inner, 76);
        y += 76 + 6;
        int bx = x;
        for (Gadget.Push button : List.of(this.keepOnly, this.cut, this.keepToo, this.reset)) {
            int width = Math.max(64, button.wantedWidth(font) + 8);
            place(font, button, bx, y, width, Kit.ROW);
            bx += width + Kit.GAP * 2;
        }
        place(font, this.summary, bx + 6, y, this.right - 8 - bx - 6, Kit.ROW);
        y += Kit.ROW + 6 + 12;
        int footTop = this.bottom - 8 - Kit.ROW - 2;
        int below = footTop - 6 - (Kit.ROW + 6) * 3;
        this.ranges.place(font, x - 3, y, inner + 6, Math.max(20, below - y));
        this.rebuildRanges();
        y = below + 6;
        int labelWidth = 90;
        place(font, this.nameBox, x + labelWidth, y, Math.min(320, inner - labelWidth), Kit.ROW);
        y += Kit.ROW + 6;
        if (this.inLiveWorld()) {
            place(font, this.leaveCheck, x + labelWidth, y, inner - labelWidth, Kit.ROW);
        } else {
            place(font, this.leaveCheck, -1000, -1000, 0, 0);
        }
        y += Kit.ROW + 6;
        place(font, this.bar, x, y, inner, this.job == null ? 0 : Kit.ROW);
        this.foot(font, this.start, this.close);
    }

    private static void place(Font font, Gadget gadget, int x, int y, int width, int height) {
        gadget.clip(Integer.MIN_VALUE, Integer.MAX_VALUE);
        gadget.place(font, x, y, Math.max(0, width), height);
    }

    @Override
    void paint(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        this.heading(graphics, font, t("explained"));
        Kit.text(graphics, font, t("name").getString(), this.left + 9, this.nameBox.y + 3, Controls.PARCHMENT);
        if (this.ranges.isEmpty()) {
            return;
        }
        Kit.text(graphics, font, t("kept_heading").getString(), this.left + 9, this.ranges.y - 10, Controls.BRASS_BRIGHT);
    }

    // ------------------------------------------------------------------ The list of what is kept

    private void rebuildRanges() {
        String wanted = this.keep.toString();
        if (wanted.equals(this.rangesBuiltFor)) {
            return;
        }
        this.rangesBuiltFor = wanted;
        this.ranges.clear();
        for (int i = 0; i < this.keep.size(); i++) {
            int index = i;
            Spans.Range range = this.keep.get(i);
            Gadget label = new Gadget.Words(() -> t("kept", index + 1), Controls.PARCHMENT).wide(64);
            Entry from = new Entry(() -> Controls.time(range.from()), typed -> this.retime(index, typed, true)).max(12);
            from.wide(80).tip(t("from.tip"));
            Entry to = new Entry(() -> Controls.time(range.to()), typed -> this.retime(index, typed, false)).max(12);
            to.wide(80).tip(t("to.tip"));
            Gadget length = new Gadget.Words(() -> t("length", Controls.length(range.length())), Controls.PARCHMENT_DIM);
            Gadget.Push jump = Gadget.Push.icon(Kit.Icon.SEARCH, t("show.tip"), () -> {
                this.selectFrom = range.from();
                this.selectTo = range.to();
            });
            Gadget.Push remove = Gadget.Push.icon(Kit.Icon.CROSS, t("remove.tip"), () -> {
                List<Spans.Range> next = new ArrayList<>(this.keep);
                next.remove(index);
                this.change(next);
            });
            remove.when(() -> this.keep.size() > 1 && !this.running());
            this.ranges.add(new Gadget.Line(label, from, new Gadget.Words(() -> t("to"), Controls.PARCHMENT_DIM).wide(16), to, length, jump, remove));
        }
    }

    private @Nullable Component retime(int index, String typed, boolean start) {
        int tick = LibraryQuery.ticksFromWords(typed);
        if (tick < 0) {
            return t("bad_time");
        }
        if (index >= this.keep.size()) {
            return null;
        }
        Spans.Range old = this.keep.get(index);
        Spans.Range next = start ? new Spans.Range(Math.min(tick, old.to() - 1), old.to()) : new Spans.Range(old.from(), Math.max(old.from() + 1, Math.min(this.total, tick)));
        List<Spans.Range> all = new ArrayList<>(this.keep);
        all.set(index, next);
        this.change(all);
        return null;
    }

    // ------------------------------------------------------------------ Running it

    private void startTrim() {
        Entry.blur();
        if (this.running() || !this.trimsSomething()) {
            return;
        }
        try {
            this.job = Library.trim(this.file, List.copyOf(this.keep), this.name.trim(), this.leaveWorld || !this.inLiveWorld(), null);
        } catch (IllegalArgumentException | IllegalStateException e) {
            Notices.show(Component.translatable("dailies.ui.notice.refused", String.valueOf(e.getMessage())));
            return;
        }
        LibraryJob started = this.job;
        Minecraft game = Minecraft.getInstance();
        started.done().whenComplete((made, error) -> game.execute(() -> {
            if (made != null) {
                Notices.show(t("made", started.name));
            }
        }));
    }

    @Override
    public void onClose() {
        if (this.running()) {
            // A trim that plays the recording in the game keeps going behind; its progress is in the list's jobs
            Notices.show(t("still_running"));
        }
        super.onClose();
    }

    // ------------------------------------------------------------------ For the scripted test

    public List<int[]> kept() {
        List<int[]> list = new ArrayList<>();
        this.keep.forEach(range -> list.add(new int[]{range.from(), range.to()}));
        return list;
    }

    public void select(int from, int to) {
        this.selectFrom = from;
        this.selectTo = to;
    }

    public void cutSelection() {
        this.change(LibraryQuery.cutFrom(this.keep, this.selection(), this.total));
    }

    public int momentsShown() {
        MomentsTimeline.Timeline now = this.timeline;
        return now == null ? 0 : now.marks().size() + now.bands().size();
    }

    public void name(String words) {
        this.name = words;
    }

    public @Nullable LibraryJob startNow() {
        this.startTrim();
        return this.job;
    }

    // ------------------------------------------------------------------ The timeline

    /**
     * The whole recording from end to end: a ruler of times, the clips as bands and the events as marks above the
     * strip, what is kept in brass and what is cut in shadow, the markers as thin lines, and the stretch being chosen
     * ringed in parchment. Dragging along it chooses a stretch.
     */
    private final class Ruler extends Gadget {

        private boolean dragging = false;
        private int dragStart = 0;

        private int stripTop() {
            return this.y + 12 + 14;
        }

        private int stripBottom() {
            return this.y + this.height - 4;
        }

        private double perTick() {
            return Math.max(1, this.width - 2) / (double) TrimScreen.this.total;
        }

        private int xOf(int tick) {
            return this.x + 1 + (int) Math.round(tick * this.perTick());
        }

        private int tickAt(double mouseX) {
            double share = (mouseX - this.x - 1) / Math.max(1.0, this.width - 2);
            return (int) Math.max(0, Math.min(TrimScreen.this.total, Math.round(share * TrimScreen.this.total)));
        }

        @Override
        void draw(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
            int top = this.stripTop();
            int bottom = this.stripBottom();
            // The ruler: about one label every 90 pixels, on whole minutes or seconds
            int seconds = TrimScreen.this.total / 20;
            int[] steps = {1, 5, 10, 30, 60, 120, 300, 600, 1200, 1800, 3600, 7200};
            int step = steps[steps.length - 1];
            for (int candidate : steps) {
                if (seconds / Math.max(1, candidate) <= Math.max(1, this.width / 90)) {
                    step = candidate;
                    break;
                }
            }
            for (int second = 0; second <= seconds; second += step) {
                int at = this.xOf(second * 20);
                graphics.fill(at, this.y + 9, at + 1, this.y + 12, Controls.BRASS_DIM);
                String label = Controls.length(second * 20);
                if (at + font.width(label) < this.x + this.width) {
                    Kit.text(graphics, font, label, at + 2, this.y, Controls.PARCHMENT_FAINT);
                }
            }
            // Cut first in shadow, then what is kept over it
            Kit.field(graphics, this.x, top, this.width, bottom - top, Controls.BRASS_DIM);
            for (Spans.Range range : TrimScreen.this.keep) {
                graphics.fill(this.xOf(range.from()), top + 1, Math.max(this.xOf(range.from()) + 1, this.xOf(range.to())), bottom - 1, 0xFF6A5220);
                graphics.fill(this.xOf(range.from()), top + 1, Math.max(this.xOf(range.from()) + 1, this.xOf(range.to())), top + 2, 0x30FFFFFF);
            }
            for (Spans.Range cutRange : Spans.invert(TrimScreen.this.keep, TrimScreen.this.total)) {
                for (int hatch = this.xOf(cutRange.from()); hatch < this.xOf(cutRange.to()); hatch += 4) {
                    graphics.fill(hatch, top + 1, hatch + 1, bottom - 1, 0x30000000);
                }
            }
            for (RecordingManifest.Marker marker : TrimScreen.this.markers) {
                int at = this.xOf(marker.tick);
                graphics.fill(at, top, at + 1, bottom, 0xA0000000 | (marker.colour & 0xFFFFFF));
            }
            // The moments: clips as bands just above the strip, events as marks in a row above them
            MomentsTimeline.Timeline moments = TrimScreen.this.timeline;
            if (moments != null) {
                for (MomentsTimeline.Band band : moments.bands()) {
                    int from = this.xOf(band.start());
                    int to = Math.max(from + 2, this.xOf(band.end()));
                    graphics.fill(from, top - 5, to, top - 2, band.cut() ? 0x907FC4B0 : Controls.VERDIGRIS);
                }
                for (MomentsTimeline.Mark mark : moments.marks()) {
                    int colour = mark.score() >= 50 ? Controls.BRASS_BRIGHT : mark.score() >= 25 ? Controls.PARCHMENT_DIM : Controls.PARCHMENT_FAINT;
                    Kit.diamond(graphics, this.xOf(mark.tick()), top - 10, 2, colour);
                }
            }
            if (TrimScreen.this.selected()) {
                int from = this.xOf(TrimScreen.this.selectFrom);
                int to = Math.max(from + 1, this.xOf(TrimScreen.this.selectTo));
                graphics.fill(from, top + 1, to, bottom - 1, 0x30EFE6D6);
                graphics.outline(from, top - 1, to - from, bottom - top + 2, Controls.PARCHMENT);
            }
            if (this.over(mouseX, mouseY)) {
                int at = Math.max(this.x, Math.min(this.x + this.width - 1, mouseX));
                graphics.fill(at, top - 12, at + 1, bottom, 0x80EFE6D6);
            }
        }

        @Override
        @Nullable Gadget press(double mouseX, double mouseY, int button) {
            if (button != InputConstants.MOUSE_BUTTON_LEFT || !this.over(mouseX, mouseY) || TrimScreen.this.running()) {
                return null;
            }
            this.dragging = true;
            this.dragStart = this.tickAt(mouseX);
            TrimScreen.this.selectFrom = this.dragStart;
            TrimScreen.this.selectTo = this.dragStart;
            return this;
        }

        @Override
        void drag(double mouseX, double mouseY) {
            if (this.dragging) {
                int here = this.tickAt(mouseX);
                TrimScreen.this.selectFrom = Math.min(this.dragStart, here);
                TrimScreen.this.selectTo = Math.max(this.dragStart, here);
            }
        }

        @Override
        void release(double mouseX, double mouseY) {
            this.dragging = false;
            if (TrimScreen.this.selectTo - TrimScreen.this.selectFrom < 2) {
                // A click and not a drag: nothing chosen
                TrimScreen.this.selectFrom = -1;
                TrimScreen.this.selectTo = -1;
            }
        }

        @Override
        @Nullable Component tipAt(double mouseX, double mouseY) {
            if (!this.over(mouseX, mouseY)) {
                return null;
            }
            int tick = this.tickAt(mouseX);
            Component words = Component.translatable(KEYS + "at", Controls.time(tick));
            MomentsTimeline.Timeline moments = TrimScreen.this.timeline;
            int reach = (int) Math.ceil(4 / this.perTick());
            if (moments != null) {
                for (MomentsTimeline.Mark mark : moments.marks()) {
                    if (Math.abs(mark.tick() - tick) <= reach) {
                        words = Component.empty().append(words).append("\n").append(Component.literal(mark.description()));
                        break;
                    }
                }
                for (MomentsTimeline.Band band : moments.bands()) {
                    if (tick >= band.start() && tick <= band.end()) {
                        words = Component.empty().append(words).append("\n").append(t("clip", band.title()));
                        break;
                    }
                }
            }
            return Component.empty().append(words).append("\n").append(t("line.tip"));
        }
    }

}
