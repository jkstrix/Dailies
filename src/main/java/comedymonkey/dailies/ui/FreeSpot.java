package comedymonkey.dailies.ui;

import java.util.List;

/**
 * Finding room on a screen other mods have also added buttons to. Dailies' buttons go to the first of a list of
 * places where every one of them is clear of every widget already there and wholly on the screen, so they never sit
 * on top of another mod's button, whichever mods are installed and wherever they put theirs. No game classes, so it
 * can be tested on its own.
 */
public final class FreeSpot {

    /** A rectangle in screen units: left, top, width and height */
    public record Box(int x, int y, int width, int height) {

        /** Whether this box comes within {@code gap} of the other one */
        public boolean near(Box other, int gap) {
            return this.x < other.x + other.width + gap && other.x < this.x + this.width + gap
                && this.y < other.y + other.height + gap && other.y < this.y + this.height + gap;
        }

        public boolean inside(int screenWidth, int screenHeight, int edge) {
            return this.x >= edge && this.y >= edge && this.x + this.width <= screenWidth - edge && this.y + this.height <= screenHeight - edge;
        }
    }

    private FreeSpot() {
    }

    /** Whether all of the boxes are on the screen and clear of everything taken */
    public static boolean free(List<Box> boxes, List<Box> taken, int screenWidth, int screenHeight, int gap) {
        for (Box box : boxes) {
            if (box.width() <= 0 || box.height() <= 0 || !box.inside(screenWidth, screenHeight, gap)) {
                return false;
            }
            for (Box other : taken) {
                if (other.width() > 0 && other.height() > 0 && box.near(other, gap)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * The first of the candidate places whose boxes are all free, or -1 when none is
     *
     * @param candidates each place, as the boxes the buttons would take there
     * @param gap        the space to keep from other widgets and from the screen's edge
     */
    public static int first(List<List<Box>> candidates, List<Box> taken, int screenWidth, int screenHeight, int gap) {
        for (int i = 0; i < candidates.size(); i++) {
            if (free(candidates.get(i), taken, screenWidth, screenHeight, gap)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * {@code count} squares of {@code size} in a line from (x, y), each the next one along by (stepX, stepY). The first
     * square is at (x, y)
     */
    public static List<Box> line(int x, int y, int stepX, int stepY, int count, int size) {
        Box[] boxes = new Box[count];
        for (int i = 0; i < count; i++) {
            boxes[i] = new Box(x + i * stepX, y + i * stepY, size, size);
        }
        return List.of(boxes);
    }

}
