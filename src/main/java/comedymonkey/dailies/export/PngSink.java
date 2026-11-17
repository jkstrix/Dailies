package comedymonkey.dailies.export;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

/**
 * Writes frames as PNG pictures: a numbered sequence in a folder, or one still. Needs no encoder. Compressing a PNG
 * takes far longer than drawing the frame, so the pictures of a sequence are compressed several at a time, each to its
 * own file.
 */
final class PngSink implements FrameSink {

    private final Path target;
    private final boolean single;
    private final int scaleDown;
    private final boolean keepAlpha;
    private final ExecutorService compressors;
    /** Pictures made and not yet written, held to a few so a slow disk cannot fill the memory */
    private final Semaphore room;
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

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
        int threads = single ? 1 : Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() / 4));
        AtomicInteger made = new AtomicInteger();
        this.compressors = Executors.newFixedThreadPool(threads, work -> {
            Thread thread = new Thread(work, "Dailies-Export-Png-" + made.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        this.room = new Semaphore(threads * 2);
    }

    @Override
    public void frame(long number, byte[] rgba, int length, int width, int height) throws IOException {
        this.rethrow();
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
        try {
            this.room.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Stopped while waiting to write a picture", e);
        }
        this.compressors.execute(() -> {
            try {
                if (this.failure.get() == null) {
                    ImageIO.write(image, "png", file.toFile());
                }
            } catch (Throwable t) {
                this.failure.compareAndSet(null, t);
            } finally {
                this.room.release();
            }
        });
    }

    private void rethrow() throws IOException {
        Throwable failed = this.failure.get();
        if (failed != null) {
            throw failed instanceof IOException io ? io : new IOException("A picture could not be written: " + failed, failed);
        }
    }

    @Override
    public Path finish(boolean cancelled) throws IOException {
        this.compressors.shutdown();
        try {
            if (!this.compressors.awaitTermination(10, TimeUnit.MINUTES)) {
                throw new IOException("The last pictures were not written in ten minutes");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Stopped while the last pictures were written", e);
        }
        this.rethrow();
        return this.target;
    }

    @Override
    public void abort() {
        this.compressors.shutdownNow();
    }

}
