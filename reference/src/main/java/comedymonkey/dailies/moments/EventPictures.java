package comedymonkey.dailies.moments;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.moments.analysis.MomentAnalysis;
import comedymonkey.dailies.moments.storage.MomentsSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.stb.STBIWriteCallback;
import org.lwjgl.stb.STBImageWrite;
import org.lwjgl.system.MemoryUtil;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A small picture of the screen for every big moment (plan 2.11), kept beside the session as
 * pictures/&lt;event id&gt;.jpg so the moments page can show what a moment looked like.
 * <p>
 * It must not be felt while playing. The frame that has just been drawn is shrunk on the graphics card, only the
 * shrunken copy (a quarter of a megabyte) is read back, and turning it into a file happens on another thread.
 * Nothing is ever taken while a recording is being watched or exported: those pictures would be of the wrong thing.
 */
public final class EventPictures {

    /** Events scoring at least this get a picture */
    public static final double MIN_SCORE = 50.0;
    public static final int WIDTH = 320;
    private static final int JPEG_QUALITY = 82;

    private record Wanted(MomentsSession session, String eventId) {}

    private static final List<Wanted> WANTED = new ArrayList<>();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Dailies-Moments-Pictures");
        thread.setDaemon(true);
        return thread;
    });

    private static @Nullable RenderTarget half = null;
    private static @Nullable RenderTarget small = null;
    private static boolean broken = false;

    private EventPictures() {
    }

    static void init() {
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Post event) -> {
            if (WANTED.isEmpty() || broken) {
                return;
            }
            try {
                afterFrame();
            } catch (Throwable t) {
                // A graphics driver that will not do this costs the pictures, never the game
                broken = true;
                WANTED.clear();
                Host.LOGGER.warn("Moments: event pictures could not be taken and are off until the game restarts", t);
            }
        });
    }

    /** Asks for a picture of the next frame when this event is big enough and has none yet. Client thread */
    public static void consider(MomentsSession session, JsonObject event) {
        try {
            if (broken || event.has("picture") || !MomentsConfig.eventPictures() || Host.get().inPlayback() || Host.get().exportRunning()) {
                return;
            }
            // Most events are nowhere near; only a likely one is worth the look at everything around it
            if (MomentAnalysis.ownScore(event) + MomentAnalysis.MAX_CONTEXT_BONUS < MIN_SCORE) {
                return;
            }
            String id = MJson.str(event, "id");
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) {
                return;
            }
            double score = new MomentAnalysis(session, Moments.ongoingInteractions(session), MomentsConfig.contextSeconds()).score(event);
            if (score < MIN_SCORE) {
                return;
            }
            for (Wanted wanted : WANTED) {
                if (wanted.session == session && wanted.eventId.equals(id)) {
                    return;
                }
            }
            if (WANTED.size() < 16) {
                WANTED.add(new Wanted(session, id));
            }
        } catch (Throwable t) {
            Host.LOGGER.error("Moments: error while deciding on an event picture", t);
        }
    }

    private static void afterFrame() {
        Minecraft minecraft = Minecraft.getInstance();
        List<Wanted> wanted = new ArrayList<>(WANTED);
        WANTED.clear();
        if (Host.get().inPlayback() || Host.get().exportRunning() || minecraft.level == null) {
            return;
        }
        RenderTarget main = minecraft.gameRenderer.mainRenderTarget();
        if (main.width < 16 || main.height < 16) {
            return;
        }
        int width = Math.min(WIDTH, main.width);
        int height = Math.max(2, Math.round(width * (float) main.height / main.width));

        // Shrinking by more than half in one go skips pixels and sparkles, so a large screen goes down in two steps
        RenderTarget source = main;
        if (main.width > width * 2) {
            half = sized(half, width * 2, height * 2);
            shrink(source, half);
            source = half;
        }
        small = sized(small, width, height);
        shrink(source, small);

        // Read back once the graphics card has drawn it, a frame or two on, without the game waiting for it
        GpuTexture picture = small.getColorTexture();
        if (picture == null) {
            return;
        }
        GpuBuffer readBack = RenderSystem.getDevice().createBuffer(() -> "Moments event picture", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
            (long) width * height * 4);
        RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(picture, readBack, 0L, () -> {
            ByteBuffer pixels = null;
            try (GpuBufferSlice.MappedView view = readBack.map(true, false)) {
                pixels = MemoryUtil.memAlloc(width * height * 4);
                MemoryUtil.memCopy(view.data(), pixels);
                ByteBuffer taken = pixels;
                pixels = null;
                WRITER.execute(() -> write(taken, width, height, wanted));
            } catch (Throwable t) {
                Host.LOGGER.warn("Moments: an event picture could not be read back", t);
            } finally {
                if (pixels != null) {
                    MemoryUtil.memFree(pixels);
                }
                readBack.close();
            }
        }, 0);
    }

    private static RenderTarget sized(@Nullable RenderTarget target, int width, int height) {
        if (target == null) {
            return new TextureTarget("Moments event picture", width, height, GpuFormat.RGBA8_UNORM, null);
        }
        if (target.width != width || target.height != height) {
            target.resize(width, height);
        }
        return target;
    }

    /** One picture drawn into another of a different size, smoothed as it is shrunk */
    private static void shrink(RenderTarget from, RenderTarget to) {
        if (from.getColorTextureView() == null || to.getColorTextureView() == null) {
            throw new IllegalStateException("A picture to shrink is not there");
        }
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
            .createRenderPass(() -> "Moments event picture", to.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TRACY_BLIT));
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("InSampler", from.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.draw(3, 1, 0, 0);
        }
    }

    /** Off the client thread: the picture as a JPEG, once for each event that asked for this frame */
    private static void write(ByteBuffer pixels, int width, int height, List<Wanted> wanted) {
        try {
            // What comes back from the graphics card is upside down and has a fourth channel nobody needs
            ByteBuffer rgb = MemoryUtil.memAlloc(width * height * 3);
            byte[] jpeg;
            try {
                for (int y = height - 1; y >= 0; y--) {
                    for (int x = 0; x < width; x++) {
                        int at = (y * width + x) * 4;
                        rgb.put(pixels.get(at)).put(pixels.get(at + 1)).put(pixels.get(at + 2));
                    }
                }
                rgb.flip();
                ByteArrayOutputStream out = new ByteArrayOutputStream(24 * 1024);
                try (STBIWriteCallback sink = STBIWriteCallback.create((context, data, size) -> {
                    byte[] chunk = new byte[size];
                    MemoryUtil.memByteBuffer(data, size).get(chunk);
                    out.writeBytes(chunk);
                })) {
                    if (STBImageWrite.stbi_write_jpg_to_func(sink, 0L, width, height, 3, rgb, JPEG_QUALITY) == 0) {
                        throw new IllegalStateException("the picture could not be encoded");
                    }
                }
                jpeg = out.toByteArray();
            } finally {
                MemoryUtil.memFree(rgb);
            }
            for (Wanted each : wanted) {
                String file = "pictures/" + each.eventId + ".jpg";
                each.session.storage().writeBytes(each.session.dir.resolve(file), jpeg);
                // Only once the file is on its way does the event point at it; the page hears of the change and shows it
                each.session.update(MomentsSession.Kind.EVENTS, each.eventId, event -> {
                    event.addProperty("picture", file);
                    event.addProperty("picture_width", width);
                    event.addProperty("picture_height", height);
                });
            }
        } catch (Throwable t) {
            Host.LOGGER.warn("Moments: an event picture could not be written", t);
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

}
