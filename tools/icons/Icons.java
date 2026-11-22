import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * The family of icons for Dailies' compats and the companion: Dailies' own tile (walnut backing, brass film strip) with a
 * symbol of the mod in the frame's picture instead of the play arrow. 32x32 pixel art written out at 4x (128x128).
 * Args: out.png glyphName light(true|false). For example, from this folder:
 *   javac Icons.java && java Icons ../../compats/create/src/main/resources/dailies_create.png cog true
 */
public class Icons {
    static final int N = 32, SCALE = 4;
    static final int CLEAR = 0x00000000;
    static final int EDGE = 0xFF1A1108;
    static final int WALNUT = 0xFF2B1D0E;
    static final int FACE = 0xFF3B2A19;
    static final int FACE_DARK = 0xFF251A0D;
    static final int BRASS = 0xFF9A7A34;
    static final int BRASS_BRIGHT = 0xFFD4B25A;
    static final int BRASS_DIM = 0xFF6B5526;
    static final int PARCHMENT = 0xFFEFE6D6;
    static final int PARCHMENT_DIM = 0xFFA89878;
    static final int ALERT = 0xFFE8604A;
    static final int ALERT_DARK = 0xFF9E3526;

    static int[][] px = new int[N][N];

    static void fill(int x0, int y0, int x1, int y1, int c) {
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) if (x >= 0 && y >= 0 && x < N && y < N) px[y][x] = c;
    }

    static final java.util.Map<String, String[]> GLYPHS = new java.util.HashMap<>();
    static {
        GLYPHS.put("cog", new String[]{
            ".....##.....",
            "..#.####.#..",
            "..########..",
            ".###++++###.",
            "..##+..+##..",
            "####+..+####",
            "####+..+####",
            "..##+..+##..",
            ".###++++###.",
            "..########..",
            "..#.####.#..",
            ".....##....."});
        GLYPHS.put("eye", new String[]{
            "............",
            "............",
            "....####....",
            "..##....##..",
            ".#...++...#.",
            "#...+##+...#",
            "#...+##+...#",
            ".#...++...#.",
            "..##....##..",
            "....####....",
            "............",
            "............"});
        GLYPHS.put("balloon", new String[]{
            "...######...",
            "..##+##+##..",
            ".##+##+##+#.",
            ".##+##+##+#.",
            ".##+##+##+#.",
            "..#+##+##+..",
            "...######...",
            "....+..+....",
            "....+..+....",
            ".....++.....",
            "....####....",
            "....####...."});
        GLYPHS.put("jar", new String[]{
            "...######...",
            "...#++++#...",
            "....####....",
            "...#....#...",
            "..#......#..",
            "..#.++++.#..",
            "..#.++++.#..",
            "..#.++++.#..",
            "..#.++++.#..",
            "..#......#..",
            "...######...",
            "............"});
        GLYPHS.put("cogplus", new String[]{
            "..#.#.......",
            ".#####......",
            "##+++##.....",
            ".#+.+#......",
            "##+++##.....",
            ".#####......",
            "..#.#...##..",
            "........##..",
            "......######",
            "......######",
            "........##..",
            "........##.."});
        GLYPHS.put("compass", new String[]{
            "....####....",
            "..##....##..",
            ".#....r...#.",
            ".#...rrr..#.",
            "#....rrr...#",
            "#.....#....#",
            "#.....+....#",
            "#....+++...#",
            ".#...+++..#.",
            ".#....+...#.",
            "..##....##..",
            "....####...."});
    }

    static {
        GLYPHS.put("rack", new String[]{
            "############",
            "#r+........#",
            "############",
            "............",
            "############",
            "#r+........#",
            "############",
            "............",
            "############",
            "#r+........#",
            "############",
            "............"});
    }

    public static void main(String[] args) throws Exception {
        String[] glyph = GLYPHS.get(args[1]);
        boolean light = Boolean.parseBoolean(args[2]);
        // Backing: a rounded walnut square with a dark edge
        fill(1, 0, 30, 31, EDGE);
        fill(0, 1, 31, 30, EDGE);
        fill(1, 1, 30, 30, WALNUT);
        px[1][1] = EDGE; px[1][30] = EDGE; px[30][1] = EDGE; px[30][30] = EDGE;
        px[0][1] = CLEAR; px[1][0] = CLEAR; px[0][30] = CLEAR; px[1][31] = CLEAR;
        px[31][1] = CLEAR; px[30][0] = CLEAR; px[31][30] = CLEAR; px[30][31] = CLEAR;
        // The film strip
        fill(4, 4, 27, 8, BRASS);
        fill(4, 23, 27, 27, BRASS);
        fill(4, 9, 6, 22, BRASS);
        fill(25, 9, 27, 22, BRASS);
        fill(4, 4, 27, 4, BRASS_BRIGHT);
        fill(4, 4, 4, 27, BRASS_BRIGHT);
        fill(4, 23, 27, 23, BRASS_BRIGHT);
        fill(25, 9, 25, 22, BRASS_BRIGHT);
        fill(5, 27, 27, 27, BRASS_DIM);
        fill(27, 5, 27, 27, BRASS_DIM);
        fill(5, 8, 27, 8, BRASS_DIM);
        fill(6, 9, 6, 22, BRASS_DIM);
        for (int x = 6; x <= 24; x += 4) {
            fill(x, 5, x + 1, 6, WALNUT);
            fill(x, 25, x + 1, 26, WALNUT);
        }
        // The frame's picture
        fill(7, 9, 24, 22, FACE);
        fill(7, 9, 24, 9, FACE_DARK);
        fill(7, 9, 7, 22, FACE_DARK);
        // The symbol, 12x12, left of the recording light
        int ox = light ? 9 : 10, oy = 10;
        for (int r = 0; r < glyph.length; r++) {
            for (int c = 0; c < glyph[r].length(); c++) {
                char ch = glyph[r].charAt(c);
                int color = switch (ch) {
                    case '#' -> PARCHMENT;
                    case '+' -> PARCHMENT_DIM;
                    case 'r' -> ALERT;
                    default -> 0;
                };
                if (color != 0) px[oy + r][ox + c] = color;
            }
        }
        if (light) {
            fill(21, 11, 22, 12, ALERT);
            px[11][21] = 0xFFF7A08F;
            px[12][22] = ALERT_DARK;
        }
        BufferedImage out = new BufferedImage(N * SCALE, N * SCALE, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < N * SCALE; y++) for (int x = 0; x < N * SCALE; x++) out.setRGB(x, y, px[y / SCALE][x / SCALE]);
        ImageIO.write(out, "png", new File(args[0]));
    }
}
