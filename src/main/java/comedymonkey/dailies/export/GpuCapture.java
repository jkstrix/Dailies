package comedymonkey.dailies.export;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import comedymonkey.dailies.Host;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.Optional;

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
 * Unless told not to, the picture is not copied out on the game's thread at all: the game only makes sure the card has
 * finished writing a buffer, opens it, and hands it to a thread of its own, which copies it out for the writer while
 * the game draws on. Copying a 4K picture took the game a millisecond of every frame, a quarter of all it did.
 * <p>
 * Everything on the card goes through the game's own graphics device (Renderpearl), so it works the same on OpenGL and
 * Vulkan. Render thread only, but for the copying thread.
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

    /** The converter's one input: the picture drawn */
    private static final String PICTURE = "picture";
    private static final RenderPipeline CONVERTER = RenderPipeline.builder()
        .withLocation(Identifier.fromNamespaceAndPath("dailies", "pipeline/export_i420"))
        .withVertexShader(Identifier.fromNamespaceAndPath("dailies", "core/export_i420"))
        .withFragmentShader(Identifier.fromNamespaceAndPath("dailies", "core/export_i420"))
        .withBindGroupLayout(BindGroupLayout.builder().withUniform(PICTURE, UniformType.COMBINED_IMAGE_SAMPLER).build())
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.R8_UNORM, ColorTargetState.WRITE_ALL))
        .withCull(false)
        .build();

    /** Whether the buffers are copied out on a thread of their own */
    private final boolean persistent;
    private final int slots;
    private final GpuBuffer[] buffers;
    /** Per buffer, while the copying thread has it, the buffer opened for it; closed again on the render thread */
    private final GpuBufferSlice.@Nullable MappedView[] views;
    private final @Nullable GpuFence[] fences;
    /** Per buffer, whether the copying thread still has it */
    private final java.util.concurrent.atomic.AtomicBoolean[] copying;
    private final java.util.concurrent.ExecutorService copier;
    private volatile Throwable copyFailure = null;
    private long slotWaitNanos = 0L;

    private static @Nullable CompiledRenderPipeline converter = null;
    private static boolean converterTried = false;

    private final boolean i420;
    private final int width;
    private final int height;
    private final int bytes;
    private final ReadbackRing ring;
    private @Nullable GpuTexture planes = null;
    private @Nullable GpuTextureView planesView = null;

    GpuCapture(boolean i420, int width, int height) {
        this.i420 = i420;
        this.width = width;
        this.height = height;
        this.bytes = i420 ? I420.size(width, height) : width * height * 4;
        this.persistent = !Boolean.getBoolean("dailies.export.noPersistentReadback");
        this.slots = this.persistent ? PERSISTENT_SLOTS : SLOTS;
        this.buffers = new GpuBuffer[this.slots];
        this.views = new GpuBufferSlice.MappedView[this.slots];
        this.fences = new GpuFence[this.slots];
        this.copying = new java.util.concurrent.atomic.AtomicBoolean[this.slots];
        this.ring = new ReadbackRing(this.slots, LAG);
        this.copier = this.persistent ? java.util.concurrent.Executors.newSingleThreadExecutor(work -> {
            Thread thread = new Thread(work, "Dailies-Export-Copy");
            thread.setDaemon(true);
            return thread;
        }) : null;
        GpuDevice device = RenderSystem.getDevice();
        try {
            for (int i = 0; i < this.slots; i++) {
                int number = i;
                this.copying[i] = new java.util.concurrent.atomic.AtomicBoolean(false);
                this.buffers[i] = device.createBuffer(() -> "Dailies export read-back " + number, GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, this.bytes);
            }
            if (i420) {
                this.planes = planeTarget(device, width, height + height / 2);
                this.planesView = device.createTextureView(this.planes);
            }
        } catch (RuntimeException e) {
            this.close();
            throw new IllegalStateException("The graphics card would not take a picture of " + width + "x" + height + " to read back: " + e.getMessage(), e);
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

    /** Waits for the copying thread to give a buffer back, and closes it again */
    private void waitForSlot(int slot) {
        if (this.copying[slot].get()) {
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
        GpuBufferSlice.MappedView view = this.views[slot];
        if (view != null) {
            this.views[slot] = null;
            view.close();
        }
    }

    /**
     * Whether pictures can be converted for the encoder on this graphics card: the small shader compiles and its
     * target can be drawn into. Worked out once a session. Render thread
     */
    static boolean converterReady() {
        if (converterTried) {
            return converter != null;
        }
        converterTried = true;
        try {
            GpuDevice device = RenderSystem.getDevice();
            CompiledRenderPipeline compiled;
            try (ConverterSource source = new ConverterSource()) {
                compiled = device.compilePipeline(CONVERTER, source, Runnable::run).join().finishCompile();
            }
            if (compiled == null) {
                throw new IllegalStateException("the converter would not compile");
            }
            // A small target to be sure one can be drawn into at all
            try {
                planeTarget(device, 4, 6).close();
            } catch (RuntimeException e) {
                compiled.close();
                throw new IllegalStateException("a single-channel picture cannot be drawn into (" + e.getMessage() + ")", e);
            }
            converter = compiled;
            return true;
        } catch (RuntimeException e) {
            Host.LOGGER.warn("Dailies: exported frames cannot be converted on this graphics card, so the encoder converts them ({})", e.getMessage());
            return false;
        }
    }

    /** The converter's shaders, straight from {@link I420}, so the numbers on the card are the ones tested */
    private static final class ConverterSource implements ShaderSource {
        @Override
        public @Nullable String getShader(Identifier id, ShaderType type) {
            if (type == ShaderType.VERTEX) {
                return I420.vertexShader();
            }
            return type == ShaderType.FRAGMENT ? I420.fragmentShader() : null;
        }

        @Override
        public ShaderSource.@Nullable CachedIncludeSource getInclude(Identifier id) {
            return null;
        }

        @Override
        public void close() {
        }
    }

    /** A single-channel texture that can be drawn into and copied out */
    private static GpuTexture planeTarget(GpuDevice device, int wide, int high) {
        return device.createTexture("Dailies export planes", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC, GpuFormat.R8_UNORM, wide, high, 1, 1);
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
        GpuTexture colour = target.getColorTexture();
        GpuTextureView colourView = target.getColorTextureView();
        if (colour == null || colourView == null) {
            throw new IllegalStateException("An exported frame was drawn into a picture that is not there");
        }
        this.checkCopies();
        int slot = this.ring.issue();
        // The copying thread may still have this buffer, when the writer is behind
        this.waitForSlot(slot);
        GpuFence old = this.fences[slot];
        if (old != null) {
            old.close();
            this.fences[slot] = null;
        }
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        GpuBuffer buffer = this.buffers[slot];
        Runnable nothing = () -> {
        };
        if (this.i420) {
            GpuTexture planes = this.planes;
            if (planes == null || this.planesView == null || converter == null) {
                throw new IllegalStateException("The picture converter is not ready");
            }
            try (RenderPass pass = encoder.createRenderPass(() -> "Dailies export conversion", this.planesView, Optional.empty())) {
                pass.setPipeline(converter);
                pass.setUniform(PICTURE, colourView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                pass.draw(3, 1, 0, 0);
            }
            int halfWidth = this.width / 2;
            int halfHeight = this.height / 2;
            encoder.copyTextureToBuffer(planes, buffer, 0L, nothing, 0, 0, 0, this.width, this.height);
            encoder.copyTextureToBuffer(planes, buffer, I420.blueOffset(this.width, this.height), nothing, 0, 0, this.height, halfWidth, halfHeight);
            encoder.copyTextureToBuffer(planes, buffer, I420.redOffset(this.width, this.height), nothing, 0, halfWidth, this.height, halfWidth, halfHeight);
        } else {
            encoder.copyTextureToBuffer(colour, buffer, 0L, nothing, 0, 0, 0, this.width, this.height);
        }
        // Says when the card has finished writing the buffer, so it is never waited on by opening it
        this.fences[slot] = encoder.createFence();
    }

    /**
     * Waits for the card to finish writing a buffer. A fence is only signalled once the frame's work has been handed
     * to the card, which the game does at the end of each frame; a picture taken this frame (draining at the end) has
     * the work handed over first.
     */
    private void awaitFence(int slot, java.util.function.LongConsumer waited) {
        GpuFence fence = this.fences[slot];
        if (fence == null) {
            return;
        }
        long from = System.nanoTime();
        if (!fence.awaitCompletion(0L)) {
            boolean submitted = false;
            while (true) {
                boolean done;
                try {
                    done = fence.awaitCompletion(1_000_000_000L);
                } catch (IllegalStateException thisFrame) {
                    if (submitted) {
                        throw thisFrame;
                    }
                    // Still this frame's work: handed to the card now
                    RenderSystem.getDevice().createCommandEncoder().submit();
                    submitted = true;
                    continue;
                }
                if (done) {
                    break;
                }
                if (System.nanoTime() - from > 120_000_000_000L) {
                    throw new IllegalStateException("The graphics card did not say when an exported picture was ready");
                }
            }
        }
        fence.close();
        this.fences[slot] = null;
        waited.accept(System.nanoTime() - from);
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
            this.awaitFence(slot, waited);
            try (GpuBufferSlice.MappedView view = this.buffers[slot].map(0L, this.bytes, true, false)) {
                receiver.frame(view.data(), this.bytes, this.width, this.height);
            }
        }
    }

    /**
     * The same on a thread of its own: once the card has finished writing a due buffer, it is opened and goes to the
     * copying thread, which hands it to the receiver there, in order, and gives the buffer back. The game does not
     * wait for the copy. The receiver is called on the copying thread
     */
    private void collectBeside(boolean draining, Receiver receiver, java.util.function.LongConsumer waited) {
        this.checkCopies();
        while (this.ring.due(draining)) {
            int slot = this.ring.collect();
            this.awaitFence(slot, waited);
            GpuBufferSlice.MappedView view = this.buffers[slot].map(0L, this.bytes, true, false);
            this.views[slot] = view;
            ByteBuffer data = view.data();
            this.copying[slot].set(true);
            this.copier.execute(() -> {
                try {
                    if (this.copyFailure == null) {
                        receiver.frame(data, this.bytes, this.width, this.height);
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
        boolean copierStopped = true;
        if (this.copier != null) {
            this.copier.shutdown();
            try {
                // Whatever is being copied out reads the buffers, which go now
                if (!this.copier.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    // Stuck waiting for a writer that takes nothing: interrupted, and given a moment more
                    this.copier.shutdownNow();
                    copierStopped = this.copier.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                copierStopped = this.copier.isTerminated();
            }
        }
        if (!copierStopped) {
            // A copy may still be reading a buffer: closing it under the copy would crash the game, so the buffers are
            // left to the graphics card rather than freed, a few megabytes lost once
            Host.LOGGER.warn("Dailies: an exported picture was still being copied out as the export ended; its read-back buffers are left unfreed");
        }
        for (int i = 0; i < this.slots; i++) {
            GpuFence fence = this.fences[i];
            if (fence != null) {
                fence.close();
                this.fences[i] = null;
            }
            if (this.buffers[i] != null && copierStopped) {
                GpuBufferSlice.MappedView view = this.views[i];
                if (view != null) {
                    this.views[i] = null;
                    view.close();
                }
                this.buffers[i].close();
                this.buffers[i] = null;
            }
        }
        if (this.planesView != null) {
            this.planesView.close();
            this.planesView = null;
        }
        if (this.planes != null) {
            this.planes.close();
            this.planes = null;
        }
    }

}
