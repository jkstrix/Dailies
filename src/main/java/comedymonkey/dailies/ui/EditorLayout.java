package comedymonkey.dailies.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the parts of the editor go on a window of a given size: the bar along the top, the inspector docked at the
 * right, the timeline docked along the bottom, the viewer's and the players' cards floating over the top left of the
 * view, and the view itself, which is what is left. Pure arithmetic with no game classes in it, so that every size
 * of window can be tried in a unit test.
 * <p>
 * The view is the point of the editor, so the docked panels are as narrow as their content allows and the timeline
 * gives up track rows (which scroll) before the view drops below {@link #VIEW_SHARE} of the window. The two cards are
 * short and see-through and lie over the view rather than shrinking it.
 * <p>
 * Everything sits on the kit's grid: a control is {@link #ROW} high, controls are {@link #GAP} apart, and what is in
 * a panel keeps {@link #PAD} from its edge.
 */
final class EditorLayout {

    static final int ROW = Kit.ROW;
    static final int GAP = Kit.GAP;
    static final int PAD = Kit.PAD;
    /** The bar along the top: one row of controls with a gap above and below */
    static final int BAR = GAP + ROW + GAP;
    /** The top of a panel or card: its title, or its tabs, and its fold button */
    static final int HEADER = GAP + ROW + GAP;
    /** How wide the inspector is when folded to an edge */
    static final int EDGE = GAP + ROW + GAP;
    /** The timeline's top row: the transport buttons and what the playhead is at */
    static final int TRANSPORT = GAP + ROW + GAP;
    static final int RULER = ROW;
    /** One track row of the timeline */
    static final int LANE = 12;
    /** Under the rows: the bar that moves a zoomed timeline along, with room round it */
    static final int FOOT = 8;
    /** The least share of the window the view keeps while there are track rows that could give way */
    static final double VIEW_SHARE = 0.62;
    /** The fewest track rows the timeline shows, however short the window */
    static final int FEWEST_LANES = 2;

    /** A rectangle of the screen, right and bottom not included */
    record Rect(int left, int top, int right, int bottom) {
        static final Rect NONE = new Rect(0, 0, 0, 0);

        int width() {
            return Math.max(0, this.right - this.left);
        }

        int height() {
            return Math.max(0, this.bottom - this.top);
        }

        long area() {
            return (long) this.width() * this.height();
        }

        boolean empty() {
            return this.width() == 0 || this.height() == 0;
        }

        boolean contains(double x, double y) {
            return x >= this.left && x < this.right && y >= this.top && y < this.bottom;
        }

        boolean overlaps(Rect other) {
            return !this.empty() && !other.empty() && this.left < other.right && other.left < this.right && this.top < other.bottom && other.top < this.bottom;
        }

        boolean within(int width, int height) {
            return this.empty() || (this.left >= 0 && this.top >= 0 && this.right <= width && this.bottom <= height);
        }
    }

    /**
     * What the layout depends on.
     *
     * @param windowWidth     the window in real pixels
     * @param scale           the editor's GUI scale; see {@link #scaleFor}
     * @param viewerContent   how high the viewer's controls are, all of them
     * @param playersContent  how high the list of players is, all of it
     * @param tracks          how many tracks the scene has
     * @param momentsHeight   how high the moments are on the timeline; 0 when hidden
     * @param viewerTitle     how wide the viewer card's title is, in the font
     * @param playersTitle    how wide the players card's title is
     */
    record Input(int windowWidth, int windowHeight, int scale, boolean viewerFolded, boolean playersFolded, boolean sideFolded,
                 boolean timelineFolded, int viewerContent, int playersContent, int tracks, int momentsHeight, int viewerTitle, int playersTitle) {
    }

    /**
     * Where everything goes.
     *
     * @param view       the part of the window no docked panel covers; the cards lie over its top left
     * @param frameClear the part a framed picture is fitted into: the view, less the column of the cards while either is open
     * @param lanes      how many track rows the timeline has room for
     */
    record Layout(int width, int height, Rect bar, Rect viewer, Rect players, Rect side, Rect timeline, Rect view, Rect frameClear, int lanes) {

        /** The share of the window the view takes */
        double viewShare() {
            return (double) this.view.area() / Math.max(1L, (long) this.width * this.height);
        }

        /** The share of the window that nothing at all is drawn over, cards included */
        double clearShare() {
            long covered = 0;
            for (Rect card : this.cards()) {
                covered += overlap(card, this.view);
            }
            return (double) (this.view.area() - covered) / Math.max(1L, (long) this.width * this.height);
        }

        List<Rect> docked() {
            return List.of(this.bar, this.side, this.timeline);
        }

        List<Rect> cards() {
            List<Rect> cards = new ArrayList<>();
            if (!this.viewer.empty()) {
                cards.add(this.viewer);
            }
            if (!this.players.empty()) {
                cards.add(this.players);
            }
            return cards;
        }
    }

    private EditorLayout() {
    }

    private static long overlap(Rect a, Rect b) {
        int width = Math.min(a.right, b.right) - Math.max(a.left, b.left);
        int height = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
        return width <= 0 || height <= 0 ? 0 : (long) width * height;
    }

    /**
     * The GUI scale the editor draws at: the largest that still leaves it about 900 by 500 to work in, never larger
     * than the one the game's own menus use, and never below 1.
     */
    static int scaleFor(int windowWidth, int windowHeight, int usual) {
        return Math.max(1, Math.min(usual, Math.max(1, Math.min(windowWidth / 900, windowHeight / 500))));
    }

    /** A length of the window in the editor's units, as the game works it out: rounded up */
    static int scaled(int pixels, int scale) {
        int safe = Math.max(1, scale);
        return pixels / safe + (pixels % safe > 0 ? 1 : 0);
    }

    private static int even(int value) {
        return value & ~1;
    }

    private static int clamp(int value, int least, int most) {
        return Math.max(least, Math.min(most, value));
    }

    /** How wide the inspector at the right is when open: enough for its three tabs and a column of labels and controls */
    static int sideWidth(int width) {
        return Math.min(clamp(even(width * 21 / 100), 200, 232), Math.max(EDGE, width / 3));
    }

    /** How wide the cards are when open: enough for four buttons side by side under each player */
    static int cardWidth(int width) {
        return Math.min(clamp(even(width * 19 / 100), 184, 200), Math.max(60, width / 4));
    }

    /** How high the timeline is with this many track rows */
    static int timelineHeight(int lanes, int momentsHeight, boolean folded) {
        if (folded) {
            return TRANSPORT + RULER + GAP;
        }
        return TRANSPORT + RULER + momentsHeight + lanes * LANE + FOOT;
    }

    static Layout of(Input in) {
        int width = scaled(in.windowWidth(), in.scale());
        int height = scaled(in.windowHeight(), in.scale());
        Rect bar = new Rect(0, 0, width, BAR);

        int sideWidth = in.sideFolded() ? EDGE : sideWidth(width);
        int viewWidth = width - sideWidth;

        // The timeline: a row for every track, until the view would drop below its share; then the rows scroll
        int lanes = Math.max(FEWEST_LANES, in.tracks());
        int timelineHeight;
        if (in.timelineFolded()) {
            timelineHeight = timelineHeight(0, 0, true);
            lanes = 0;
        } else {
            double viewRows = VIEW_SHARE * width * height / Math.max(1, viewWidth);
            int most = (int) Math.floor(height - BAR - viewRows);
            // Never more than about a third of the window, however tall
            most = Math.min(most, height * 34 / 100);
            int fit = (most - timelineHeight(0, in.momentsHeight(), false)) / LANE;
            lanes = Math.max(FEWEST_LANES, Math.min(lanes, fit));
            timelineHeight = timelineHeight(lanes, in.momentsHeight(), false);
        }
        // On a window too short for all that, the timeline keeps what it can below the bar
        timelineHeight = Math.min(timelineHeight, Math.max(0, height - BAR - HEADER));
        int timelineTop = height - timelineHeight;
        Rect timeline = new Rect(0, timelineTop, width, height);

        Rect side = new Rect(width - sideWidth, BAR, width, timelineTop);
        Rect view = new Rect(0, BAR, side.left(), timelineTop);

        // The cards float over the view's top left, the viewer's above the players'
        int cardWidth = Math.min(cardWidth(width), Math.max(0, side.left() - 2 * PAD));
        int top = BAR + PAD;
        int floor = timelineTop - PAD;
        Rect viewer;
        if (floor - top < HEADER || cardWidth < 40) {
            viewer = Rect.NONE;
        } else if (in.viewerFolded()) {
            viewer = new Rect(PAD, top, PAD + Math.min(cardWidth, folded(in.viewerTitle())), top + HEADER);
        } else {
            // Room is kept under it for the players' title, so that card can always be unfolded again
            int room = Math.max(0, floor - top - HEADER - (PAD + HEADER));
            viewer = new Rect(PAD, top, PAD + cardWidth, top + HEADER + Math.min(Math.max(0, in.viewerContent()), room));
        }
        Rect players;
        int playersTop = viewer.empty() ? top : viewer.bottom() + PAD;
        if (viewer.empty() || floor - playersTop < HEADER) {
            players = Rect.NONE;
        } else if (in.playersFolded()) {
            players = new Rect(PAD, playersTop, PAD + Math.min(cardWidth, folded(in.playersTitle())), playersTop + HEADER);
        } else {
            players = new Rect(PAD, playersTop, PAD + cardWidth, playersTop + HEADER + Math.min(Math.max(0, in.playersContent()), floor - playersTop - HEADER));
        }

        boolean cardsOpen = (!viewer.empty() && !in.viewerFolded()) || (!players.empty() && !in.playersFolded());
        int clearLeft = cardsOpen ? Math.max(viewer.right(), players.right()) + PAD : 0;
        Rect frameClear = new Rect(Math.min(clearLeft, view.right()), view.top(), view.right(), view.bottom());
        return new Layout(width, height, bar, viewer, players, side, timeline, view, frameClear, lanes);
    }

    /** How wide a folded card is: its title and its fold button */
    private static int folded(int titleWidth) {
        return even(PAD + Math.max(20, titleWidth) + PAD + ROW + GAP + PAD + 1);
    }

    // ------------------------------------------------------------------ A row of controls

    /**
     * Sets out a row of controls: one group from the left edge and one from the right, each control as wide as asked
     * and {@link #GAP} from the next, with {@code groupGap} between groups. A control that would run into the other
     * side is given no width at all rather than being drawn over its neighbour.
     *
     * @param left   the widths of the groups that start at the left, each group an array
     * @param right  the widths of the groups that end at the right, in the order drawn
     * @return the x of every control, left groups first, then right groups; -1 for one there was no room for
     */
    static int[] row(int from, int to, int groupGap, int[][] left, int[][] right) {
        int count = 0;
        for (int[] group : left) {
            count += group.length;
        }
        for (int[] group : right) {
            count += group.length;
        }
        int[] xs = new int[count];
        // The right groups are measured first: they hold the way out, which must always be there
        int rightWidth = 0;
        for (int g = 0; g < right.length; g++) {
            for (int w : right[g]) {
                rightWidth += w + GAP;
            }
            rightWidth += g < right.length - 1 ? groupGap - GAP : 0;
        }
        rightWidth = Math.max(0, rightWidth - GAP);
        int rightStart = to - rightWidth;
        int at = from;
        int index = 0;
        for (int g = 0; g < left.length; g++) {
            for (int w : left[g]) {
                xs[index++] = at + w <= rightStart - groupGap ? at : -1;
                at += w + GAP;
            }
            at += groupGap - GAP;
        }
        at = rightStart;
        for (int[] group : right) {
            for (int w : group) {
                xs[index++] = at >= from ? at : -1;
                at += w + GAP;
            }
            at += groupGap - GAP;
        }
        return xs;
    }

}
