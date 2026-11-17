package comedymonkey.dailies.export;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * Writes frames as PNG pictures: a numbered sequence in a folder, or one still. Needs no encoder.
 */
final class PngSink implements FrameSink {

    private final Path target;
    private final boolean single;
    private final int scaleDown;
    private final boolean keepAlpha;

    /**
     * @param target    the folder for a sequence, or the file for a single picture
     * @param scaleDown how many drawn pixels make one written pixel each way (supersampling); 1 for none
     */
    PngSink(Path target, boolean single, int scaleDown, boolean keepAlpha) throws IOException {
        this.target = target;
        this.single = single;
        this.scaleDown = Math.max(1, scaleDown);
        this.keepAlpha = keepAlpha;
        Files.createDirectories(single ? target.toAbsolutePath().getParent() : target);
    }

    @Override
    public void frame(long number, byte[] rgba, int width, int height) throws IOException {
        int outWidth = width / this.scaleDown;
        int outHeight = height / this.scaleDown;
        BufferedImage image = new BufferedImage(outWidth, outHeight, this.keepAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        int cells = this.scaleDown * this.scaleDown;
        for (int y = 0; y < outHeight; y++) {
            for (int x = 0; x < outWidth; x++) {
                int r = 0, g = 0, b = 0, a = 0;
                for (int dy = 0; dy < this.scaleDown; dy++) {
                    // The picture arrives bottom row first
                    int row = height - 1 - (y * this.scaleDown + dy);
                    int at = (row * width + x * this.scaleDown) * 4;
                    for (int dx = 0; dx < this.scaleDown; dx++) {
                        r += rgba[at] & 0xFF;
                        g += rgba[at + 1] & 0xFF;
                        b += rgba[at + 2] & 0xFF;
                        a += rgba[at + 3] & 0xFF;
                        at += 4;
                    }
                }
                pixels[y * outWidth + x] = ((this.keepAlpha ? a / cells : 0xFF) << 24) | ((r / cells) << 16) | ((g / cells) << 8) | (b / cells);
            }
        }
        Path file = this.single ? this.target : this.target.resolve(String.format("frame_%06d.png", number));
        ImageIO.write(image, "png", file.toFile());
    }

    @Override
    public Path finish(boolean cancelled) {
        return this.target;
    }

    @Override
    public void abort() {
    }

}
