package comedymonkey.dailies.export;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.BufferUploader;
import comedymonkey.dailies.Host;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

import java.nio.ByteBuffer;

/**
 * Takes each exported picture off the graphics card without making the game wait for it (specification EXP-2: still
 * every frame, in order). The picture is copied into one of a few buffers on the card as soon as it is drawn and read
 * out two frames later, by when the copy is done, so the card goes on drawing while the processor reads and the
 * encoder works. Read straight away, as before, the game stood idle every frame until the card had finished all it
 * had been given, and the card stood idle while the game did everything else.
 * <p>
 * For a video, the picture is first turned into the encoder's own format on the card ({@link I420}): turned the right
 * way up and converted to brightness and colour planes, a byte and a half a pixel instead of four. RGBA, bottom row
 * first, is kept for numbered pictures, transparent video, editing masters and supersampling.
 * <p>
 * Where the card can keep its buffers open to the processor for good (OpenGL 4.4), the picture is not copied out on
 * the game's thread at all: the game only makes sure the card has finished writing a buffer and hands it to a thread of
 * its own, which copies it out for the writer while the game draws on. Copying a 4K picture took the game a millisecond
 * of every frame, a quarter of all it did.
 * <p>
 * Render thread only, but for the copying thread. What it changes of the game's drawing state it changes through the
 * game's own state tracker, or puts back.
 */
final class GpuCapture implements AutoCloseable {

    /** Three buffers: one being filled, one in flight, one being read */
    static final int SLOTS = 3;
    /** With a copying thread, two more: one being copied out, and one to spare for when the writer is behind */
    static final int PERSISTENT_SLOTS = 5;
    static final int LAG = 2;

    interface Receiver {
        void frame(ByteBuffer pixels, int length, int width, int height) throws Exception;
    }

    /** Whether the buffers stay open to the processor, and are copied out on a thread of their own */
    private final boolean persistent;
    private final int slots;
    private final ByteBuffer[] views;
    private final long[] fences;
    /** Per buffer, whether the copying thread still has it */
    private final java.util.concurrent.atomic.AtomicBoolean[] copying;
    private final java.util.concurrent.ExecutorService copier;
    private volatile Throwable copyFailure = null;
    private long slotWaitNanos = 0L;

    private static int program = 0;
    private static int pictureUniform = -1;
    private static int vertexArray = 0;
    private static boolean converterTried = false;
    private static boolean samplers = false;

    private final boolean i420;
    private final int width;
    private final int height;
    private final int bytes;
    private final int[] buffers;
    private final ReadbackRing ring;
    private int planes = 0;
    private int planeFramebuffer = 0;

    GpuCapture(boolean i420, int width, int height) {
        this.i420 = i420;
        this.width = width;
        this.height = height;
        this.bytes = i420 ? I420.size(width, height) : width * height * 4;
        this.persistent = org.lwjgl.opengl.GL.getCapabilities().glBufferStorage != 0L && org.lwjgl.opengl.GL.getCapabilities().glFenceSync != 0L
            && !Boolean.getBoolean("dailies.export.noPersistentReadback");
        this.slots = this.persistent ? PERSISTENT_SLOTS : SLOTS;
        this.buffers = new int[this.slots];
        this.views = new ByteBuffer[this.slots];
        this.fences = new long[this.slots];
        this.copying = new java.util.concurrent.atomic.AtomicBoolean[this.slots];
        this.ring = new ReadbackRing(this.slots, LAG);
        try {
            for (int i = 0; i < this.slots; i++) {
                this.copying[i] = new java.util.concurrent.atomic.AtomicBoolean(false);
                this.buffers[i] = GL15.glGenBuffers();
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, this.buffers[i]);
                if (this.persistent) {
                    int flags = GL30.GL_MAP_READ_BIT | org.lwjgl.opengl.GL44.GL_MAP_PERSISTENT_BIT | org.lwjgl.opengl.GL44.GL_MAP_COHERENT_BIT;
                    org.lwjgl.opengl.GL44.glBufferStorage(GL21.GL_PIXEL_PACK_BUFFER, this.bytes, flags);
                    this.views[i] = GL30.glMapBufferRange(GL21.GL_PIXEL_PACK_BUFFER, 0, this.bytes, flags);
                    if (this.views[i] == null) {
                        throw new IllegalStateException("The graphics card would not keep a read-back buffer open");
                    }
                } else {
                    GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, this.bytes, GL15.GL_STREAM_READ);
                }
            }
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        }
        this.copier = this.persistent ? java.util.concurrent.Executors.newSingleThreadExecutor(work -> {
            Thread thread = new Thread(work, "Dailies-Export-Copy");
            thread.setDaemon(true);
            return thread;
        }) : null;
        if (i420) {
            int[] made = planeTarget(width, height + height / 2);
            this.planes = made[0];
            this.planeFramebuffer = made[1];
            if (this.planeFramebuffer == 0) {
                this.close();
                throw new IllegalStateException("The graphics card would not take a picture of " + width + "x" + height + " to convert");
            }
        }
    }

    boolean i420() {
        return this.i420;
    }

    /** Whether pictures are copied out on a thread of their own, beside the game */
    boolean persistent() {
        return this.persistent;
    }

    /** How long the game waited, since last asked, for the copying thread to give a buffer back; in nanoseconds */
    long takeSlotWaitNanos() {
        long waited = this.slotWaitNanos;
        this.slotWaitNanos = 0L;
        return waited;
    }

    private void checkCopies() {
        Throwable failed = this.copyFailure;
        if (failed != null) {
            throw new IllegalStateException("An exported picture could not be copied out: " + failed.getMessage(), failed);
        }
    }

    /** Waits until every picture handed to the copying thread has been copied out. Render thread */
    void awaitCopies() {
        if (!this.persistent) {
            return;
        }
        for (int i = 0; i < this.slots; i++) {
            this.waitForSlot(i);
        }
        this.checkCopies();
    }

    private void waitForSlot(int slot) {
        if (!this.copying[slot].get()) {
            return;
        }
        long from = System.nanoTime();
        long deadline = from + 120_000_000_000L;
        while (this.copying[slot].get()) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("An exported picture was not copied out in two minutes");
            }
            java.util.concurrent.locks.LockSupport.parkNanos(50_000L);
        }
        this.slotWaitNanos += System.nanoTime() - from;
    }

    /**
     * Whether pictures can be converted for the encoder on this graphics card: the small shader compiles and its
     * target can be drawn into. Worked out once a session. Render thread
     */
    static boolean converterReady() {
        if (converterTried) {
            return program != 0;
        }
        converterTried = true;
        samplers = org.lwjgl.opengl.GL.getCapabilities().glBindSampler != 0L;
        int vertex = 0;
        int fragment = 0;
        try {
            vertex = shader(GL20.GL_VERTEX_SHADER, I420.vertexShader());
            fragment = shader(GL20.GL_FRAGMENT_SHADER, I420.fragmentShader());
            int linked = GL20.glCreateProgram();
            GL20.glAttachShader(linked, vertex);
            GL20.glAttachShader(linked, fragment);
            GL30.glBindFragDataLocation(linked, 0, "result");
            GL20.glLinkProgram(linked);
            if (GL20.glGetProgrami(linked, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                String log = GL20.glGetProgramInfoLog(linked, 4096);
                GL20.glDeleteProgram(linked);
                throw new IllegalStateException("the converter would not link: " + log);
            }
            // A small target to be sure one can be drawn into at all
            int[] trial = planeTarget(4, 6);
            GL11.glDeleteTextures(trial[0]);
            if (trial[1] == 0) {
                GL20.glDeleteProgram(linked);
                throw new IllegalStateException("a single-channel picture cannot be drawn into");
            }
            GL30.glDeleteFramebuffers(trial[1]);
            pictureUniform = GL20.glGetUniformLocation(linked, "picture");
            vertexArray = GL30.glGenVertexArrays();
            program = linked;
            return true;
        } catch (RuntimeException e) {
            Host.LOGGER.warn("Dailies: exported frames cannot be converted on this graphics card, so the encoder converts them ({})", e.getMessage());
            return false;
        } finally {
            if (vertex != 0) {
                GL20.glDeleteShader(vertex);
            }
            if (fragment != 0) {
                GL20.glDeleteShader(fragment);
            }
        }
    }

    private static int shader(int kind, String source) {
        int made = GL20.glCreateShader(kind);
        GL20.glShaderSource(made, source);
        GL20.glCompileShader(made);
        if (GL20.glGetShaderi(made, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(made, 4096);
            GL20.glDeleteShader(made);
            throw new IllegalStateException("the converter would not compile: " + log);
        }
        return made;
    }

    /** A single-channel texture and a framebuffer drawing into it; the framebuffer is 0 when it cannot be drawn into */
    private static int[] planeTarget(int wide, int high) {
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int texture = GL11.glGenTextures();
        int framebuffer = GL30.glGenFramebuffers();
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_BORDER);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_BORDER);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, wide, high, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                GL30.glDeleteFramebuffers(framebuffer);
                framebuffer = 0;
            }
        } finally {
            // Put back behind the game's state tracker's back exactly as it was, so the tracker stays right
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
        }
        return new int[]{texture, framebuffer};
    }

    /** Whether the next frame needs an older one taken out first, every buffer being in use */
    boolean full() {
        return this.ring.full();
    }

    /**
     * Asks for the picture just drawn to be copied into the next buffer. Returns at once: the card does the copy
     * after the drawing it has been given. Render thread
     */
    void issue(RenderTarget target) {
        if (target.width != this.width || target.height != this.height) {
            throw new IllegalStateException("A frame of " + target.width + "x" + target.height + " was drawn in an export of " + this.width + "x" + this.height);
        }
        this.checkCopies();
        int slot = this.ring.issue();
        // The copying thread may still have this buffer, when the writer is behind
        this.waitForSlot(slot);
        int previousActive = GlStateManager._getActiveTexture();
        try {
            packTight();
            if (this.i420) {
                int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
                GlStateManager._activeTexture(GL13.GL_TEXTURE0);
                // Sampler objects (a shader pack's, say) would override how the picture is read; older drivers have none
                int previousSampler = samplers ? GL11.glGetInteger(GL33.GL_SAMPLER_BINDING) : 0;
                GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.planeFramebuffer);
                GlStateManager._viewport(0, 0, this.width, this.height + this.height / 2);
                GlStateManager._disableBlend();
                GlStateManager._disableScissorTest();
                GlStateManager._colorMask(true, true, true, true);
                GlStateManager._bindTexture(target.getColorTextureId());
                if (samplers) {
                    GL33.glBindSampler(0, 0);
                }
                GL20.glUseProgram(program);
                GL20.glUniform1i(pictureUniform, 0);
                GlStateManager._glBindVertexArray(vertexArray);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
                GlStateManager._glBindVertexArray(0);
                // The game's own record of what is bound for drawing is no longer true
                BufferUploader.invalidate();
                GL20.glUseProgram(previousProgram);
                if (samplers) {
                    GL33.glBindSampler(0, previousSampler);
                }

                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, this.buffers[slot]);
                int halfWidth = this.width / 2;
                int halfHeight = this.height / 2;
                GL11.glReadPixels(0, 0, this.width, this.height, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, 0L);
                GL11.glReadPixels(0, this.height, halfWidth, halfHeight, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, I420.blueOffset(this.width, this.height));
                GL11.glReadPixels(halfWidth, this.height, halfWidth, halfHeight, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, I420.redOffset(this.width, this.height));
            } else {
                GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, target.frameBufferId);
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, this.buffers[slot]);
                GL11.glReadPixels(0, 0, this.width, this.height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
            }
            if (this.persistent) {
                // A buffer kept open is never waited on by mapping it: this says when the card has finished writing it
                this.fences[slot] = org.lwjgl.opengl.GL32.glFenceSync(org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            }
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            GlStateManager._activeTexture(previousActive);
            target.bindWrite(true);
        }
    }

    /**
     * Hands over every picture that is due, oldest first: those more than two frames old, or all of them when
     * draining at the end. The receiver must be done with the bytes when it returns. Render thread
     *
     * @param waited told how long each wait for the card took, in nanoseconds
     */
    void collect(boolean draining, Receiver receiver, java.util.function.LongConsumer waited) throws Exception {
        if (this.persistent) {
            this.collectBeside(draining, receiver, waited);
            return;
        }
        while (this.ring.due(draining)) {
            int slot = this.ring.collect();
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, this.buffers[slot]);
            try {
                long from = System.nanoTime();
                ByteBuffer mapped = GL30.glMapBufferRange(GL21.GL_PIXEL_PACK_BUFFER, 0, this.bytes, GL30.GL_MAP_READ_BIT);
                waited.accept(System.nanoTime() - from);
                if (mapped == null) {
                    throw new IllegalStateException("The graphics card would not hand over an exported picture");
                }
                try {
                    receiver.frame(mapped, this.bytes, this.width, this.height);
                } finally {
                    GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
                }
            } finally {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            }
        }
    }

    /**
     * The same with the buffers kept open: once the card has finished writing a due buffer, it goes to the copying thread,
     * which hands it to the receiver there, in order, and gives the buffer back. The game does not wait for the copy.
     * The receiver is called on the copying thread
     */
    private void collectBeside(boolean draining, Receiver receiver, java.util.function.LongConsumer waited) {
        this.checkCopies();
        while (this.ring.due(draining)) {
            int slot = this.ring.collect();
            long fence = this.fences[slot];
            if (fence != 0L) {
                long from = System.nanoTime();
                int result;
                do {
                    result = org.lwjgl.opengl.GL32.glClientWaitSync(fence, org.lwjgl.opengl.GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000_000L);
                } while (result == org.lwjgl.opengl.GL32.GL_TIMEOUT_EXPIRED);
                org.lwjgl.opengl.GL32.glDeleteSync(fence);
                this.fences[slot] = 0L;
                waited.accept(System.nanoTime() - from);
                if (result == org.lwjgl.opengl.GL32.GL_WAIT_FAILED) {
                    throw new IllegalStateException("The graphics card did not say when an exported picture was ready");
                }
            }
            ByteBuffer view = this.views[slot];
            this.copying[slot].set(true);
            this.copier.execute(() -> {
                try {
                    if (this.copyFailure == null) {
                        receiver.frame(view, this.bytes, this.width, this.height);
                    }
                } catch (Throwable t) {
                    this.copyFailure = t;
                } finally {
                    this.copying[slot].set(false);
                }
            });
        }
    }

    /** Frees what it holds on the card. Render thread */
    @Override
    public void close() {
        if (this.copier != null) {
            this.copier.shutdown();
            try {
                // Whatever is being copied out reads the buffers, which go now
                this.copier.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        for (int i = 0; i < this.slots; i++) {
            if (this.fences[i] != 0L) {
                org.lwjgl.opengl.GL32.glDeleteSync(this.fences[i]);
                this.fences[i] = 0L;
            }
            if (this.buffers[i] != 0) {
                if (this.views[i] != null) {
                    GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, this.buffers[i]);
                    GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
                    GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
                    this.views[i] = null;
                }
                GL15.glDeleteBuffers(this.buffers[i]);
                this.buffers[i] = 0;
            }
        }
        if (this.planeFramebuffer != 0) {
            GL30.glDeleteFramebuffers(this.planeFramebuffer);
            this.planeFramebuffer = 0;
        }
        if (this.planes != 0) {
            GL11.glDeleteTextures(this.planes);
            this.planes = 0;
        }
    }

    /** Rows packed tight, as the encoder and the picture writer expect them */
    private static void packTight() {
        GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 1);
        GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, 0);
    }

}
