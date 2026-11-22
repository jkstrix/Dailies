package comedymonkey.dailies.ui;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.mcp.tools.MomentsTools;
import comedymonkey.dailies.moments.Moments;
import comedymonkey.dailies.moments.MomentsTimeline;
import comedymonkey.dailies.moments.MomentsTimeline.Band;
import comedymonkey.dailies.moments.MomentsTimeline.Cluster;
import comedymonkey.dailies.moments.MomentsTimeline.Mark;
import comedymonkey.dailies.moments.MomentsTimeline.Strip;
import comedymonkey.dailies.moments.MomentsTimeline.Talk;
import comedymonkey.dailies.moments.MomentsTimeline.Timeline;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The open recording's moments on the editor's timeline (specification EDIT-9), between the ruler and the track
 * rows: a row of marks for events, coloured by kind; a row of bands for clips; and a thin strip per speaker for when
 * they talked. Marks that would sit on top of each other become one mark with a count. The rows fold down to the
 * marks alone, and a switch on the timeline's bar takes them away altogether, so they never crowd out the tracks.
 * <p>
 * What there is to show comes from {@code Moments.timelineFor}, worked out away from the client thread and again
 * whenever the moments or the ignore list change. Client thread otherwise.
 */
public final class MomentsLane {

    static final String SETTING = "showMomentsOnTimeline";

    private static final int MARKS = 11;
    private static final int BANDS = 10;
    private static final int STRIP = 6;
    /** More speakers than this share the last strip */
    private static final int MOST_STRIPS = 3;
    /** Marks nearer than this, in pixels, are drawn as one */
    private static final double APART = 13.0;

    private static final int RED = 0xFFE8604A;
    private static final int GOLD = 0xFFF0D060;
    private static final int LILAC = 0xFFC9A0E6;
    private static final int BLUE = 0xFF8FB4E8;
    private static final int PEACH = 0xFFE8A878;
    private static final int[] VOICES = {Controls.VERDIGRIS, LILAC, BLUE, PEACH};

    private static Timeline timeline = Timeline.empty("");
    private static @Nullable String loadedFor = null;
    private static long loadedStamp = Long.MIN_VALUE;
    private static boolean loading = false;
    static boolean folded = false;

    private MomentsLane() {
    }

    // ------------------------------------------------------------------ What there is to show

    /** Whether the moments are shown on the timeline: the "showMomentsOnTimeline" setting */
    public static boolean shown() {
        return EditorState.setting(SETTING, true);
    }

    public static void setShown(boolean show) {
        EditorState.saveSetting(SETTING, show);
    }

    /** The moments of the open recording as last worked out; asks for them again when they are out of date */
    public static Timeline timeline() {
        String open = Playback.loaded() ? Playback.recordingId() : null;
        if (open == null) {
            return Timeline.empty("");
        }
        long stamp = stamp();
        if (!loading && (!open.equals(loadedFor) || stamp != loadedStamp)) {
            loading = true;
            Controls.offThread(() -> {
                Timeline fresh = Moments.timelineFor(open);
                Minecraft.getInstance().execute(() -> take(open, stamp, fresh));
            }, Kit.t("moments.failed"), () -> {
                loading = false;
                if (!open.equals(loadedFor) || loadedStamp != stamp) {
                    // It failed, and has said so once; asking again every frame would only say it again
                    take(open, stamp, Timeline.empty(open));
                }
            });
        }
        return open.equals(loadedFor) ? timeline : Timeline.empty(open);
    }

    private static long stamp() {
        return Moments.timelineStamp() * 31 + Playback.total();
    }

    private static void take(String recording, long stamp, Timeline fresh) {
        timeline = fresh;
        loadedFor = recording;
        loadedStamp = stamp;
    }

    /** Works the moments out now and waits for them: for scripts, which cannot wait for a frame. Not for the client thread */
    public static Timeline reloadNow() throws Exception {
        String open = Playback.recordingId();
        if (open == null) {
            return Timeline.empty("");
        }
        Timeline fresh = Moments.timelineFor(open);
        Minecraft.getInstance().submit(() -> take(open, stamp(), fresh)).get(30, java.util.concurrent.TimeUnit.SECONDS);
        return fresh;
    }

    // ------------------------------------------------------------------ Size

    private static int strips(Timeline now) {
        return Math.min(MOST_STRIPS, now.strips().size());
    }

    private static int voiceHeight(Timeline now) {
        int strips = strips(now);
        // Room for the word "Voice" beside a single strip
        return strips == 0 ? 0 : Math.max(9, strips * STRIP);
    }

    /** How much of the timeline the moments take, in pixels: nothing when hidden or when the recording has none */
    static int height() {
        if (!shown() || EditorState.timelineFolded) {
            return 0;
        }
        Timeline now = timeline();
        if (now.isEmpty()) {
            return 0;
        }
        if (folded) {
            return MARKS + 1;
        }
        return MARKS + (now.bands().isEmpty() ? 0 : BANDS) + voiceHeight(now) + 1;
    }

    /** As drawn right now; for scripts */
    public static int drawnHeight() {
        return height();
    }

    // ------------------------------------------------------------------ Where things are

    /** The part of the timeline the moments are drawn in; the timeline panel fills this in when it lays itself out */
    record Place(int columnLeft, int laneLeft, int laneWidth, int top) {
        double across(double tick) {
            return this.laneLeft + (tick - EditorState.viewStart) * this.perTick();
        }

        double perTick() {
            return this.laneWidth / span();
        }

        int bandsTop(Timeline now) {
            return this.top + MARKS;
        }

        int voiceTop(Timeline now) {
            return this.top + MARKS + (now.bands().isEmpty() ? 0 : BANDS);
        }
    }

    /** How many ticks the timeline shows; the whole recording until the editor has said otherwise */
    private static double span() {
        return EditorState.viewSpan > 0.0 ? EditorState.viewSpan : Math.max(1.0, Playback.total());
    }

    private static List<Cluster> clusters(Timeline now, Place place) {
        double from = EditorState.viewStart - APART / place.perTick();
        double to = EditorState.viewStart + span() + APART / place.perTick();
        List<Cluster> clusters = MomentsTimeline.cluster(now.marks(), EditorState.viewStart, place.perTick(), APART, mark -> mark.tick() >= from && mark.tick() <= to);
        return clusters;
    }

    private static int clusterHalfWidth(Font font, Cluster cluster) {
        return cluster.count() == 1 ? 4 : (font.width(String.valueOf(cluster.count())) + 7) / 2;
    }

    private static @Nullable Cluster clusterAt(Font font, Timeline now, Place place, double mouseX, double mouseY) {
        if (mouseY < place.top() || mouseY >= place.top() + MARKS) {
            return null;
        }
        Cluster nearest = null;
        double best = Double.MAX_VALUE;
        for (Cluster cluster : clusters(now, place)) {
            double distance = Math.abs(place.laneLeft() + cluster.x() - mouseX);
            if (distance <= clusterHalfWidth(font, cluster) + 1 && distance < best) {
                best = distance;
                nearest = cluster;
            }
        }
        return nearest;
    }

    private static @Nullable Band bandAt(Timeline now, Place place, double mouseX, double mouseY) {
        int top = folded ? place.top() : place.bandsTop(now);
        int height = folded ? MARKS : BANDS;
        if (now.bands().isEmpty() || mouseY < top || mouseY >= top + height) {
            return null;
        }
        Band found = null;
        for (Band band : now.bands()) {
            // The shortest band under the mouse, should two overlap
            if (mouseX >= place.across(band.start()) - 1 && mouseX <= Math.max(place.across(band.end()), place.across(band.start()) + 3) + 1
                    && (found == null || band.end() - band.start() < found.end() - found.start())) {
                found = band;
            }
        }
        return found;
    }

    /** Which speakers a strip row stands for: one each, and everyone left over on the last */
    private static List<Strip> row(Timeline now, int row) {
        if (row < MOST_STRIPS - 1 || now.strips().size() <= MOST_STRIPS) {
            return List.of(now.strips().get(row));
        }
        return now.strips().subList(row, now.strips().size());
    }

    private record Said(Strip strip, Talk talk) {
    }

    private static @Nullable Said talkAt(Timeline now, Place place, double mouseX, double mouseY) {
        if (folded || now.strips().isEmpty()) {
            return null;
        }
        int top = place.voiceTop(now);
        int row = (int) Math.floor((mouseY - top) / STRIP);
        if (mouseY < top || row < 0 || row >= strips(now)) {
            return null;
        }
        for (Strip strip : row(now, row)) {
            for (Talk talk : strip.talks()) {
                if (mouseX >= place.across(talk.start()) - 1 && mouseX <= Math.max(place.across(talk.end()), place.across(talk.start()) + 2) + 1) {
                    return new Said(strip, talk);
                }
            }
        }
        return null;
    }

    static boolean over(Place place, double mouseX, double mouseY) {
        int height = height();
        return height > 0 && mouseY >= place.top() && mouseY < place.top() + height && mouseX >= place.columnLeft();
    }

    // ------------------------------------------------------------------ Drawing

    /** The colour of an event's mark, by what kind of thing happened */
    static int colour(Mark mark) {
        String kind = mark.kind();
        if (kind.endsWith("_kill") || kind.endsWith("_death") || kind.startsWith("boss_")) {
            return RED;
        }
        return switch (kind) {
            case "marker" -> Controls.BRASS_BRIGHT;
            case "advancement", "advancement_other", "milestone", "item_crafted", "block_placed", "block_mined", "tool_first_use" -> GOLD;
            case "biome_discovered", "dimension_entered", "structure_interaction" -> Controls.VERDIGRIS;
            case "voice_burst" -> LILAC;
            case "custom" -> BLUE;
            default -> kind.startsWith("job_") ? PEACH : Controls.PARCHMENT_DIM;
        };
    }

    /** Draws the rows; the caller has already clipped drawing to the lanes */
    static void draw(GuiGraphics graphics, Font font, Place place, int laneRight, int mouseX, int mouseY) {
        Timeline now = timeline();
        int height = height();
        if (height <= 0) {
            return;
        }
        graphics.fill(place.laneLeft(), place.top(), laneRight + 1, place.top() + height - 1, 0x40000000);
        graphics.fill(place.laneLeft(), place.top() + height - 1, laneRight + 1, place.top() + height, Controls.BRASS_DIM);

        // Clips: bands, with their titles where there is room
        Band hoveredBand = clusterAt(font, now, place, mouseX, mouseY) == null ? bandAt(now, place, mouseX, mouseY) : null;
        int bandTop = folded ? place.top() + 1 : place.bandsTop(now);
        int bandHeight = folded ? MARKS - 2 : BANDS - 1;
        for (Band band : now.bands()) {
            int from = (int) Math.round(place.across(band.start()));
            int to = Math.max(from + 3, (int) Math.round(place.across(band.end())));
            if (to < place.laneLeft() || from > laneRight) {
                continue;
            }
            boolean hovered = band == hoveredBand;
            graphics.fill(from, bandTop, to + 1, bandTop + bandHeight, folded ? (hovered ? 0x70D4B25A : 0x48D4B25A) : (hovered ? 0xD09A7A34 : 0xA09A7A34));
            graphics.fill(from, bandTop, to + 1, bandTop + 1, Controls.BRASS_BRIGHT);
            graphics.fill(from, bandTop, from + 1, bandTop + bandHeight, Controls.BRASS_BRIGHT);
            graphics.fill(to, bandTop, to + 1, bandTop + bandHeight, Controls.BRASS_BRIGHT);
            if (!folded && to - from > 28) {
                int textLeft = Math.max(from, place.laneLeft()) + 3;
                Kit.text(graphics, font, Kit.clip(font, band.title(), Math.min(to, laneRight) - textLeft - 2), textLeft, bandTop + 1, Controls.PARCHMENT);
            }
        }

        // Voice: a thin strip per speaker
        if (!folded) {
            int voiceTop = place.voiceTop(now);
            for (int row = 0; row < strips(now); row++) {
                int y = voiceTop + row * STRIP + 1;
                int colour = VOICES[row % VOICES.length];
                for (Strip strip : row(now, row)) {
                    for (Talk talk : strip.talks()) {
                        int from = (int) Math.round(place.across(talk.start()));
                        int to = Math.max(from + 2, (int) Math.round(place.across(talk.end())));
                        if (to < place.laneLeft() || from > laneRight) {
                            continue;
                        }
                        // Words that were written down are solid; sound alone is fainter
                        graphics.fill(from, y, to, y + STRIP - 2, talk.words().isEmpty() ? (colour & 0xFFFFFF) | 0x80000000 : colour);
                    }
                }
            }
        }

        // Events: marks, and one mark with a count where several would overlap
        Cluster hovered = clusterAt(font, now, place, mouseX, mouseY);
        int middle = place.top() + MARKS / 2;
        for (Cluster cluster : clusters(now, place)) {
            int x = place.laneLeft() + (int) Math.round(cluster.x());
            Mark best = cluster.best();
            int colour = colour(best);
            boolean lit = cluster == hovered || (hovered != null && hovered.marks().equals(cluster.marks()));
            if (cluster.count() == 1) {
                if (lit || best.score() >= 50.0) {
                    Kit.diamond(graphics, x, middle, 4, lit ? Controls.PARCHMENT : 0xC0EFE6D6);
                }
                Kit.diamond(graphics, x, middle, 3, colour);
            } else {
                String count = String.valueOf(cluster.count());
                int half = clusterHalfWidth(font, cluster);
                // A dark plate with the number in the colour of its best mark: a number on a bright plate does not read
                graphics.fill(x - half, place.top(), x + half + 1, place.top() + MARKS, Controls.FIELD);
                graphics.renderOutline(x - half, place.top(), half * 2 + 1, MARKS, lit ? Controls.PARCHMENT : colour);
                Kit.text(graphics, font, count, x - half + 4, place.top() + 2, colour);
            }
        }
    }

    /** The names of the rows, in the column the track names stand in */
    static void drawColumn(GuiGraphics graphics, Font font, Place place, int columnRight, int mouseX, int mouseY) {
        Timeline now = timeline();
        int height = height();
        if (height <= 0) {
            return;
        }
        int x = place.columnLeft() + 4;
        boolean overFold = overFold(place, mouseX, mouseY);
        Kit.face(graphics, x, place.top() + 1, 11, MARKS - 2, overFold, false, true);
        Kit.icon(graphics, folded ? Kit.Icon.FOLD_UP : Kit.Icon.FOLD_DOWN, x + 5, place.top() + MARKS / 2, Controls.PARCHMENT);
        int room = columnRight - (x + 14) - 2;
        String events = Kit.t("moments.row.events", now.marks().size()).getString();
        Kit.text(graphics, font, Kit.clip(font, events, room), x + 14, place.top() + 2, Controls.PARCHMENT);
        if (folded) {
            return;
        }
        if (!now.bands().isEmpty()) {
            Kit.text(graphics, font, Kit.clip(font, Kit.t("moments.row.clips", now.bands().size()).getString(), room), x + 14, place.bandsTop(now) + 1, Controls.PARCHMENT_DIM);
        }
        if (!now.strips().isEmpty()) {
            int voiceTop = place.voiceTop(now);
            // A swatch for each strip, so the colours can be told apart, then who they are
            for (int row = 0; row < strips(now); row++) {
                graphics.fill(x + 3, voiceTop + row * STRIP + 1, x + 8, voiceTop + row * STRIP + STRIP - 1, VOICES[row % VOICES.length]);
            }
            Kit.text(graphics, font, Kit.clip(font, speakers(now), room), x + 14, voiceTop + (voiceHeight(now) - 8) / 2, Controls.PARCHMENT_DIM);
        }
    }

    private static String speakers(Timeline now) {
        List<String> names = new ArrayList<>();
        for (Strip strip : now.strips()) {
            names.add(strip.speaker());
        }
        return String.join(", ", names);
    }

    private static boolean overFold(Place place, double mouseX, double mouseY) {
        return mouseX >= place.columnLeft() + 3 && mouseX < place.columnLeft() + 16 && mouseY >= place.top() && mouseY < place.top() + MARKS;
    }

    // ------------------------------------------------------------------ The mouse

    /** A mouse button went down over the rows */
    static void press(Font font, Place place, double mouseX, double mouseY, int button) {
        Timeline now = timeline();
        if (mouseX < place.laneLeft()) {
            if (button == 0 && overFold(place, mouseX, mouseY)) {
                Kit.click();
                folded = !folded;
            }
            return;
        }
        Cluster cluster = clusterAt(font, now, place, mouseX, mouseY);
        if (cluster != null) {
            if (button == 0) {
                Kit.click();
                Controls.jumpTo(cluster.marks().get(0).tick());
            }
            return;
        }
        Band band = bandAt(now, place, mouseX, mouseY);
        if (band != null) {
            if (button == 0) {
                Kit.click();
                chooseBand(band);
            } else if (button == 1) {
                List<Menu.Item> items = new ArrayList<>();
                items.add(new Menu.Item(Kit.t("moments.band.scene"), () -> makeScene(band)));
                items.add(new Menu.Item(Kit.t("moments.band.range"), () -> chooseBand(band)));
                items.add(new Menu.Item(Kit.t("moments.band.jump"), () -> Controls.jumpTo(band.start())));
                Menu.show(items, mouseX, mouseY);
            }
            return;
        }
        Said said = talkAt(now, place, mouseX, mouseY);
        if (said != null && button == 0) {
            Kit.click();
            Controls.jumpTo(said.talk().start());
        }
    }

    /** What a click on a clip's band does: the export range becomes the clip, as one step to undo, and the recording goes to its start */
    public static void chooseBand(Band band) {
        EditorState.guard(() -> {
            EditorState.history().setExportRange(band.start(), band.end());
            EditorState.say(Kit.t("moments.band.chosen", band.title()));
        });
        Controls.jumpTo(band.start());
    }

    /** "Make a scene from this clip": a scene named after it with its range and the suggested camera, as one step to undo */
    public static void makeScene(Band band) {
        try {
            MomentsTools.makeScene(band.sessionId(), band.clipId());
            EditorState.selection.clear();
            EditorState.say(Kit.t("moments.band.scene_made", band.title()));
        } catch (Exception refused) {
            Host.LOGGER.warn("Dailies: a scene could not be made from clip {}: {}", band.clipId(), refused.toString());
            String reason = refused.getMessage();
            EditorState.say(reason == null || reason.isBlank() ? Component.translatable("dailies.ui.notice.went_wrong") : Component.literal(reason));
        }
    }

    static @Nullable Component tipAt(Font font, Place place, double mouseX, double mouseY) {
        Timeline now = timeline();
        if (mouseX < place.laneLeft()) {
            if (overFold(place, mouseX, mouseY)) {
                return Kit.t("moments.fold");
            }
            if (!folded && !now.strips().isEmpty() && mouseY >= place.voiceTop(now)) {
                return Kit.t("moments.row.voice.tip", speakers(now));
            }
            if (!folded && !now.bands().isEmpty() && mouseY >= place.bandsTop(now)) {
                return Kit.t("moments.row.clips.tip");
            }
            return Kit.t("moments.row.events.tip");
        }
        Cluster cluster = clusterAt(font, now, place, mouseX, mouseY);
        if (cluster != null) {
            Mark first = cluster.marks().get(0);
            if (cluster.count() == 1) {
                return Kit.t("moments.mark.tip", first.description(), Controls.time(first.tick()), Math.round(first.score()));
            }
            // The best few by name, the rest by number
            List<Mark> best = new ArrayList<>(cluster.marks());
            best.sort((a, b) -> Double.compare(b.score(), a.score()));
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < Math.min(4, best.size()); i++) {
                names.append(i == 0 ? "" : "; ").append(best.get(i).description());
            }
            if (best.size() > 4) {
                return Kit.t("moments.cluster.tip_more", cluster.count(), Controls.time(first.tick()), names.toString(), best.size() - 4);
            }
            return Kit.t("moments.cluster.tip", cluster.count(), Controls.time(first.tick()), names.toString());
        }
        Band band = bandAt(now, place, mouseX, mouseY);
        if (band != null) {
            return Kit.t(band.cut() ? "moments.band.tip_cut" : "moments.band.tip", band.title(), Controls.length(band.end() - band.start()), Math.round(band.score()));
        }
        Said said = talkAt(now, place, mouseX, mouseY);
        if (said != null) {
            Talk talk = said.talk();
            if (!talk.words().isEmpty()) {
                String words = talk.words().length() > 220 ? talk.words().substring(0, 220).trim() + "..." : talk.words();
                return Kit.t("moments.talk.tip", said.strip().speaker(), words);
            }
            return Kit.t(talk.nonSpeech() ? "moments.talk.tip_sound" : "moments.talk.tip_unwritten", said.strip().speaker(), Controls.length(talk.end() - talk.start()));
        }
        return null;
    }

    // ------------------------------------------------------------------ For scripts

    /** What is drawn as one mark at the current zoom over a timeline this many pixels wide: the count of each, in order */
    public static List<Integer> clusterSizes(int laneWidth) {
        Place place = new Place(0, 0, laneWidth, 0);
        List<Integer> sizes = new ArrayList<>();
        for (Cluster cluster : clusters(timeline(), place)) {
            sizes.add(cluster.count());
        }
        return sizes;
    }

}
