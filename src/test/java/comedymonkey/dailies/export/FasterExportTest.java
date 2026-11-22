package comedymonkey.dailies.export;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure parts of the faster export path: the read-back ring, the frame buffers, the I420 layout and the timings */
class FasterExportTest {

    // ------------------------------------------------------------------ Read-back ring

    /** Every frame comes out once, in order, from the buffer it went into, and the last ones on draining */
    @Test
    void ringHandsEveryFrameBackInOrder() {
        ReadbackRing ring = new ReadbackRing(3, 2);
        int[] slotOf = new int[20];
        List<Integer> out = new ArrayList<>();
        for (int frame = 0; frame < 20; frame++) {
            assertFalse(ring.full());
            slotOf[frame] = ring.issue();
            while (ring.due(false)) {
                long number = ring.nextCollected();
                assertEquals(slotOf[(int) number], ring.collect());
                out.add((int) number);
            }
            // Two frames stay in flight once there are two
            assertEquals(Math.min(frame + 1, 2), ring.pending());
        }
        while (ring.due(true)) {
            long number = ring.nextCollected();
            assertEquals(slotOf[(int) number], ring.collect());
            out.add((int) number);
        }
        assertEquals(20, out.size());
        for (int i = 0; i < 20; i++) {
            assertEquals(i, out.get(i));
        }
        assertEquals(0, ring.pending());
    }

    /** A buffer is never handed out again before what is in it has been taken out */
    @Test
    void ringNeverReusesABufferInFlight() {
        ReadbackRing ring = new ReadbackRing(3, 2);
        ring.issue();
        ring.issue();
        ring.issue();
        assertTrue(ring.full());
        assertThrows(IllegalStateException.class, ring::issue);
        assertEquals(0, ring.collect());
        assertEquals(0, ring.issue());
        assertThrows(IllegalArgumentException.class, () -> new ReadbackRing(2, 2));
    }

    /** A still: one frame, which only comes out on draining */
    @Test
    void ringDrainsASingleFrame() {
        ReadbackRing ring = new ReadbackRing(3, 2);
        ring.issue();
        assertFalse(ring.due(false));
        assertTrue(ring.due(true));
        ring.collect();
        assertFalse(ring.due(true));
        assertThrows(IllegalStateException.class, ring::collect);
    }

    // ------------------------------------------------------------------ Frame buffers

    @Test
    void poolGoesRoundWithoutMakingNewBuffers() throws Exception {
        FramePool pool = new FramePool(2, 16);
        FramePool.Frame first = pool.take(10);
        FramePool.Frame second = pool.take(10);
        assertNotNull(first);
        assertNotNull(second);
        // The writer is behind: nothing is free
        assertNull(pool.take(10));
        first.number = 7;
        pool.send(first);
        FramePool.Frame written = pool.next();
        assertSame(first, written);
        assertEquals(7, written.number);
        pool.giveBack(written);
        assertSame(first, pool.take(10));
        pool.end();
        assertSame(FramePool.END, pool.next());
    }

    /** Frames are made as they are first needed, never more than the pool's number, and a free one is used before a new one */
    @Test
    void poolMakesFramesOnlyAsTheyAreNeeded() throws Exception {
        FramePool pool = new FramePool(3, 8);
        assertEquals(0, pool.freeCount(), "nothing is made before it is asked for");
        FramePool.Frame first = pool.take(10);
        pool.giveBack(first);
        assertSame(first, pool.take(10), "the free one comes first");
        FramePool.Frame second = pool.take(10);
        FramePool.Frame third = pool.take(10);
        assertNotNull(second);
        assertNotNull(third);
        assertNotSame(first, second);
        assertNotSame(second, third);
        assertEquals(8, third.data.length);
        assertNull(pool.take(10), "three made, all in use");
        // Given back from another thread, as a sink holding frames does
        Thread giver = new Thread(() -> pool.giveBack(second));
        giver.start();
        giver.join();
        assertSame(second, pool.take(10));
    }

    @Test
    void poolCanAlwaysSayTheEndAfterAFailure() throws Exception {
        FramePool pool = new FramePool(2, 4);
        pool.send(pool.take(10));
        pool.send(pool.take(10));
        pool.abandon();
        assertEquals(2, pool.freeCount());
        assertSame(FramePool.END, pool.next());
    }

    // ------------------------------------------------------------------ I420

    @Test
    void i420PlanesFollowOneAnother() {
        assertEquals(1920 * 1080 * 3 / 2, I420.size(1920, 1080));
        assertEquals(1920 * 1080, I420.blueOffset(1920, 1080));
        assertEquals(1920 * 1080 + 960 * 540, I420.redOffset(1920, 1080));
        assertEquals(3840 * 2160 * 3 / 2, I420.size(3840, 2160));
        assertTrue(I420.fits(1920, 1080));
        assertFalse(I420.fits(1921, 1080));
        assertFalse(I420.fits(1920, 1081));
    }

    /** The BT.601 limited-range values FFmpeg gives the plain colours */
    @Test
    void i420ColoursAreTheEncodersOwn() {
        assertEquals(235, I420.luma(1, 1, 1));
        assertEquals(16, I420.luma(0, 0, 0));
        assertEquals(128, I420.blue(1, 1, 1));
        assertEquals(128, I420.red(0, 0, 0));
        assertEquals(81, I420.luma(1, 0, 0));
        assertEquals(90, I420.blue(1, 0, 0));
        assertEquals(240, I420.red(1, 0, 0));
        assertEquals(145, I420.luma(0, 1, 0));
        assertEquals(41, I420.luma(0, 0, 1));
        assertEquals(240, I420.blue(0, 0, 1));
    }

    /** The picture comes out the right way up: the card's bottom row first becomes the top row first */
    @Test
    void i420TurnsThePictureTheRightWayUp() {
        int width = 4;
        int height = 4;
        byte[] rgba = new byte[width * height * 4];
        // The card's first rows (the bottom of the picture) black, the last two (the top) red on the left, blue on the right
        for (int row = 2; row < 4; row++) {
            for (int x = 0; x < width; x++) {
                int at = (row * width + x) * 4;
                rgba[at] = (byte) (x < 2 ? 255 : 0);
                rgba[at + 2] = (byte) (x < 2 ? 0 : 255);
                rgba[at + 3] = (byte) 255;
            }
        }
        byte[] out = I420.fromRgbaBottomUp(rgba, width, height);
        assertEquals(I420.size(width, height), out.length);
        // Top rows: red then blue; bottom rows: black
        assertEquals(81, out[0] & 0xFF);
        assertEquals(41, out[3] & 0xFF);
        assertEquals(81, out[width + 1] & 0xFF);
        assertEquals(16, out[2 * width] & 0xFF);
        assertEquals(16, out[3 * width + 3] & 0xFF);
        int blue = I420.blueOffset(width, height);
        int red = I420.redOffset(width, height);
        // The top left two by two is red, the top right blue, the bottom black
        assertEquals(90, out[blue] & 0xFF);
        assertEquals(240, out[red] & 0xFF);
        assertEquals(240, out[blue + 1] & 0xFF);
        assertEquals(128, out[blue + 2] & 0xFF);
        assertEquals(128, out[red + 3] & 0xFF);
    }

    /** The shader the card runs is made from the same numbers */
    @Test
    void shaderUsesTheSameNumbers() {
        String shader = I420.fragmentShader();
        assertTrue(shader.contains("65.4810 * c.r + 128.5530 * c.g + 24.9660 * c.b"), shader);
        assertTrue(shader.contains("112.0000 * c.r + -93.7860 * c.g + -18.2140 * c.b"), shader);
        assertTrue(shader.contains("-37.7970 * c.r + -74.2030 * c.g + 112.0000 * c.b"), shader);
        assertFalse(shader.contains("half "), "half is a reserved word in GLSL");
    }

    @Test
    void onlyEncodersThatWrite420TakeIt() {
        assertTrue(Codecs.takesI420("h264_nvenc", false));
        assertTrue(Codecs.takesI420("hevc_nvenc", false));
        assertTrue(Codecs.takesI420("av1_nvenc", false));
        assertTrue(Codecs.takesI420("libsvtav1", false));
        assertTrue(Codecs.takesI420("libvpx-vp9", false));
        assertFalse(Codecs.takesI420("libvpx-vp9", true));
        assertFalse(Codecs.takesI420("prores_ks", false));
        assertFalse(Codecs.takesI420("dnxhd", false));
    }

    // ------------------------------------------------------------------ Timings

    @Test
    void timingsSayEveryStageInOneLine() {
        ExportTimings timings = new ExportTimings();
        timings.add(ExportTimings.Stage.DRAW, 4_000_000L);
        timings.add(ExportTimings.Stage.DRAW, 2_000_000L);
        timings.add(ExportTimings.Stage.PIPE, 1_000_000L);
        timings.add(ExportTimings.Stage.QUEUE, -5L);
        timings.frames(2);
        String line = timings.line("test", 1_000_000_000L);
        assertTrue(line.contains("over 2 frames at 2.0 a second"), line);
        assertTrue(line.contains("draw 3.00"), line);
        assertTrue(line.contains("pipe write 0.50"), line);
        assertTrue(line.contains("queue wait 0.00"), line);
        assertFalse(line.contains("\n"));
        timings.reset();
        assertEquals(0, timings.total(ExportTimings.Stage.DRAW));
    }

}
