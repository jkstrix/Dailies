package comedymonkey.dailies.export;

import java.util.Locale;

/**
 * The picture format most video encoders take: planar YUV 4:2:0 ("yuv420p" to FFmpeg). A plane of brightness, one
 * byte a pixel, then a plane of blue difference and one of red difference, each a byte for every two by two pixels.
 * A byte and a half a pixel against the four of RGBA, so less to read back from the graphics card and less to push
 * down the pipe, and nothing for the encoder to convert.
 * <p>
 * The graphics card does the converting ({@link GpuCapture}); the numbers it converts with live here, with a
 * reference conversion in plain Java, so the layout and the colours can be checked without a game. They are the
 * BT.601 limited-range numbers FFmpeg used when it converted the RGBA frames itself, so the colours stay as they were.
 * No Minecraft here.
 */
public final class I420 {

    /** Brightness from red, green and blue between 0 and 1, plus 16: a value from 16 to 235 */
    static final double[] LUMA = {65.481, 128.553, 24.966};
    /** Blue difference, plus 128: 16 to 240 */
    static final double[] BLUE = {-37.797, -74.203, 112.0};
    /** Red difference, plus 128: 16 to 240 */
    static final double[] RED = {112.0, -93.786, -18.214};

    private I420() {
    }

    /** Whether a picture of this size can be held: the colour planes need whole pairs of pixels each way */
    public static boolean fits(int width, int height) {
        return width >= 2 && height >= 2 && width % 2 == 0 && height % 2 == 0;
    }

    public static int size(int width, int height) {
        return width * height + 2 * chromaSize(width, height);
    }

    public static int chromaSize(int width, int height) {
        return (width / 2) * (height / 2);
    }

    public static int blueOffset(int width, int height) {
        return width * height;
    }

    public static int redOffset(int width, int height) {
        return width * height + chromaSize(width, height);
    }

    static int luma(double r, double g, double b) {
        return clampByte(16.0 + LUMA[0] * r + LUMA[1] * g + LUMA[2] * b);
    }

    static int blue(double r, double g, double b) {
        return clampByte(128.0 + BLUE[0] * r + BLUE[1] * g + BLUE[2] * b);
    }

    static int red(double r, double g, double b) {
        return clampByte(128.0 + RED[0] * r + RED[1] * g + RED[2] * b);
    }

    private static int clampByte(double value) {
        return (int) Math.max(0, Math.min(255, Math.round(value)));
    }

    /**
     * What the graphics card's conversion makes of a picture, worked out on the processor: RGBA as the card hands it
     * over, bottom row first, to I420 top row first. Each colour byte is taken from the average of its two by two
     * pixels. For the tests; an export never needs it.
     */
    public static byte[] fromRgbaBottomUp(byte[] rgba, int width, int height) {
        byte[] out = new byte[size(width, height)];
        for (int y = 0; y < height; y++) {
            int source = (height - 1 - y) * width * 4;
            for (int x = 0; x < width; x++) {
                int at = source + x * 4;
                out[y * width + x] = (byte) luma((rgba[at] & 0xFF) / 255.0, (rgba[at + 1] & 0xFF) / 255.0, (rgba[at + 2] & 0xFF) / 255.0);
            }
        }
        int halfWidth = width / 2;
        int blueAt = blueOffset(width, height);
        int redAt = redOffset(width, height);
        for (int row = 0; row < height / 2; row++) {
            int upper = (height - 1 - 2 * row) * width * 4;
            int lower = (height - 2 - 2 * row) * width * 4;
            for (int column = 0; column < halfWidth; column++) {
                double r = 0, g = 0, b = 0;
                for (int line : new int[]{upper, lower}) {
                    for (int dx = 0; dx < 2; dx++) {
                        int at = line + (2 * column + dx) * 4;
                        r += rgba[at] & 0xFF;
                        g += rgba[at + 1] & 0xFF;
                        b += rgba[at + 2] & 0xFF;
                    }
                }
                r /= 4.0 * 255.0;
                g /= 4.0 * 255.0;
                b /= 4.0 * 255.0;
                out[blueAt + row * halfWidth + column] = (byte) blue(r, g, b);
                out[redAt + row * halfWidth + column] = (byte) red(r, g, b);
            }
        }
        return out;
    }

    /**
     * The fragment shader that does the same on the graphics card. It draws into one single-channel texture as wide
     * as the picture and half as tall again: the brightness plane in the first rows, then the blue plane on the left
     * and the red on the right, every row turned top to bottom, so three plain reads give the planes in order.
     */
    static String fragmentShader() {
        return String.format(Locale.ROOT, """
            #version 150
            uniform sampler2D picture;
            out vec4 result;

            vec3 at(int x, int y) {
                return texelFetch(picture, ivec2(x, y), 0).rgb;
            }

            void main() {
                ivec2 size = textureSize(picture, 0);
                ivec2 here = ivec2(gl_FragCoord.xy);
                float value;
                if (here.y < size.y) {
                    vec3 c = at(here.x, size.y - 1 - here.y);
                    value = 16.0 + %1$.4f * c.r + %2$.4f * c.g + %3$.4f * c.b;
                } else {
                    int halfWidth = size.x / 2;
                    int row = here.y - size.y;
                    bool redPlane = here.x >= halfWidth;
                    int column = redPlane ? here.x - halfWidth : here.x;
                    int upper = size.y - 1 - 2 * row;
                    vec3 c = (at(2 * column, upper) + at(2 * column + 1, upper) + at(2 * column, upper - 1) + at(2 * column + 1, upper - 1)) * 0.25;
                    value = redPlane
                        ? 128.0 + %7$.4f * c.r + %8$.4f * c.g + %9$.4f * c.b
                        : 128.0 + %4$.4f * c.r + %5$.4f * c.g + %6$.4f * c.b;
                }
                result = vec4(clamp(value, 0.0, 255.0) / 255.0, 0.0, 0.0, 1.0);
            }
            """, LUMA[0], LUMA[1], LUMA[2], BLUE[0], BLUE[1], BLUE[2], RED[0], RED[1], RED[2]);
    }

    /** A triangle that covers the whole target, made from nothing but the vertex number */
    static String vertexShader() {
        return """
            #version 150
            void main() {
                vec2 corner = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
            }
            """;
    }

}
