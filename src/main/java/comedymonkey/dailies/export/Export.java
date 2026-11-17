package comedymonkey.dailies.export;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.edit.Scene;
import comedymonkey.dailies.edit.TimeMap;
import comedymonkey.dailies.editor.EditorSession;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.sound.ExportSound;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Renders a scene's range to pictures or a video, one frame at a time, however long each frame takes (specification
 * chapter VI). Frame n is drawn at the moment of the recording that n ÷ frame rate seconds maps to, so the result is
 * the same on a fast computer and a slow one. Everything here runs on the client thread except the writing of frames.
 */
public final class Export {

    /** What to export. Plain fields: the MCP tool and the export screen both fill one in */
    public static final class Settings {
        public Path output;
        public int width = 1920;
        public int height = 1080;
        public double framesPerSecond = 60.0;
        /** -1 for the scene's export range, else its keyframes, else the whole recording */
        public int startTick = -1;
        public int endTick = -1;
        /** mp4, mkv, mov, webm, or png_sequence */
        public String container = "mp4";
        /** h264, h265, av1, vp9, prores or dnxhr: one of those {@link Encoder#choices} has on this machine */
        public String videoCodec = "h264";
        public @Nullable String encoder = null;
        public long bitrate = 20_000_000L;
        public boolean audio = false;
        public boolean stereo = true;
        /** aac, opus or pcm; "auto" for the usual one of the container (AAC for mp4 and mov, Opus for webm and mkv) */
        public String audioCodec = "aac";
        /** Also write the game's sound, each speaker and the music as WAV files beside the video (EXP-12) */
        public boolean stemFiles = false;
        /** Also put them in the file as named tracks, where the kind of file can hold them */
        public boolean stemTracks = false;
        /** What a preset had to change to work on this machine, for whoever asks how the export went; or null */
        public @Nullable String note = null;
        public boolean hideGui = false;
        public boolean supersample = false;
        public boolean transparent = false;
        /** One picture of the view as it is now, not a range */
        public boolean still = false;
    }

    private static volatile boolean active = false;
    private static volatile boolean cancelRequested = false;
    private static volatile long frame = 0;
    private static volatile long totalFrames = 0;
    private static volatile String lastError = null;
    private static volatile Path lastOutput = null;
    private static volatile boolean lastCancelled = false;
    private static volatile long startedNanos = 0;
    private static volatile String lastNote = null;
    private static boolean machineTold = false;
    private static ExportPresets presets = null;

    private static Settings settings;
    private static TimeMap timeMap;
    private static FrameSink sink;
    private static Thread writer;
    private static ArrayBlockingQueue<byte[]> queue;
    private static volatile Throwable writerFailure = null;
    private static final byte[] END = new byte[0];

    private static float partialTick = 1.0F;
    private static long preparedFrame = -1;
    private static int framesToThrowAway = 0;
    private static int chunkWaits = 0;
    private static boolean frameReady = false;
    private static ByteBuffer pixels = null;
    private static boolean worldRebuilt = false;
    private static int lastDrawn = -1;
    private static int settledFrames = 0;
    private static int rebuildWaits = 0;

    // What is put back afterwards (EXP-7)
    private static int savedPosition;
    private static boolean savedPaused;
    private static double savedSpeed;
    private static int savedWidth, savedHeight;
    private static int realWidth, realHeight;
    private static boolean savedHideGui;
    private static boolean savedFollow;

    private Export() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Post event) -> afterFrame());
    }

    // --- What others may ask (any thread) ---

    public static boolean active() {
        return active;
    }

    public static long frame() {
        return frame;
    }

    public static long totalFrames() {
        return totalFrames;
    }

    public static @Nullable String lastError() {
        return lastError;
    }

    public static @Nullable Path lastOutput() {
        return lastOutput;
    }

    public static boolean lastCancelled() {
        return lastCancelled;
    }

    /** What the preset of the export under way or the last one had to change to work here, or null */
    public static @Nullable String lastNote() {
        return lastNote;
    }

    /** The export presets, kept with the mod's data (EXP-14) */
    public static synchronized ExportPresets presets() {
        if (presets == null) {
            presets = new ExportPresets(Host.get().dataDirectory().resolve("export-presets.json"));
        }
        return presets;
    }

    /**
     * How many seconds of video a range would make, worked out as an export does; -1 when it would be empty or no
     * recording is open. Negative ticks mean the scene's own range. Client thread
     */
    public static double rangeSeconds(int startTick, int endTick) {
        if (!Playback.loaded()) {
            return -1.0;
        }
        Scene scene = EditorSession.scene();
        int total = Playback.total();
        int start = startTick >= 0 ? startTick : scene.hasExportRange() ? scene.exportStart() : scene.firstKeyframeTick() >= 0 ? scene.firstKeyframeTick() : 0;
        int end = endTick >= 0 ? endTick : scene.hasExportRange() ? scene.exportEnd() : scene.lastKeyframeTick() > start ? scene.lastKeyframeTick() : total;
        start = Math.max(0, Math.min(total, start));
        end = Math.max(0, Math.min(total, end));
        if (end <= start) {
            return -1.0;
        }
        try {
            return new TimeMap(scene, start, end).totalRealSeconds();
        } catch (RuntimeException e) {
            return (end - start) / 20.0;
        }
    }

    /** Frames written per second of real time so far */
    public static double speed() {
        double seconds = (System.nanoTime() - startedNanos) / 1.0e9;
        return active && seconds > 0.2 ? frame / seconds : 0.0;
    }

    /** What became of the sound of the export under way or the last one; null when none was asked for (EXP-3) */
    public static @Nullable String soundNote() {
        return ExportSound.note();
    }

    /** How fast the recording is passing in the export under way, in ticks per second of video; 0 when none is running or it stands still */
    public static double ticksPerSecondNow() {
        Settings current = settings;
        TimeMap map = timeMap;
        if (!active || current == null || map == null || current.still) {
            return 0.0;
        }
        double rate = current.framesPerSecond;
        long now = frame;
        // The ticks being played now lead up to this frame; the first frame has only what follows it to go by
        return now > 0 ? (map.tickAtFrame(now, rate) - map.tickAtFrame(now - 1, rate)) * rate : (map.tickAtFrame(1, rate) - map.tickAtFrame(0, rate)) * rate;
    }

    /** Width over height of the export under way, or null */
    public static @Nullable Double aspectRatio() {
        Settings current = settings;
        return active && current != null ? (double) current.width / current.height : null;
    }

    // --- For the clock and window hooks ---

    public static boolean holdsClock() {
        return active && settings != null && !settings.still;
    }

    public static float partialTick() {
        return partialTick;
    }

    public static float ticksPerFrame() {
        return settings == null ? 1.0F : (float) (20.0 / settings.framesPerSecond);
    }

    public static boolean holdsWindow() {
        return active;
    }

    /** The size to show an export picture at in the real window: as large as fits, in its own proportions */
    public static int[] previewSize(int pictureWidth, int pictureHeight) {
        int windowWidth = realWidth > 0 ? realWidth : pictureWidth;
        int windowHeight = realHeight > 0 ? realHeight : pictureHeight;
        double scale = Math.min((double) windowWidth / pictureWidth, (double) windowHeight / pictureHeight);
        return new int[]{Math.max(1, (int) Math.round(pictureWidth * scale)), Math.max(1, (int) Math.round(pictureHeight * scale))};
    }

    public static void windowResized(int width, int height) {
        realWidth = width;
        realHeight = height;
    }

    // --- Controls (client thread) ---

    public static void start(Settings wanted) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        if (active) {
            throw new IllegalStateException("An export is already running");
        }
        if (!Playback.loaded() || Playback.jumping()) {
            throw new IllegalStateException(Playback.loaded() ? "The recording is in the middle of a jump" : "No recording is open");
        }
        if (wanted.width < 16 || wanted.height < 16 || wanted.width > 16384 || wanted.height > 16384) {
            throw new IllegalArgumentException("Width and height must be between 16 and 16384");
        }
        if (!(wanted.framesPerSecond >= 1.0 && wanted.framesPerSecond <= 240.0)) {
            throw new IllegalArgumentException("The frame rate must be between 1 and 240");
        }
        Scene scene = EditorSession.scene();
        // A scene that holds its picture to a frame shape is exported in that shape, fitted inside the size asked for
        int[] framed = scene.looks().frameSize(wanted.width, wanted.height);
        wanted.width = framed[0];
        wanted.height = framed[1];
        int total = Playback.total();
        int start = wanted.startTick >= 0 ? wanted.startTick : scene.hasExportRange() ? scene.exportStart() : scene.firstKeyframeTick() >= 0 ? scene.firstKeyframeTick() : 0;
        int end = wanted.endTick >= 0 ? wanted.endTick : scene.hasExportRange() ? scene.exportEnd() : scene.lastKeyframeTick() > start ? scene.lastKeyframeTick() : total;
        start = Math.max(0, Math.min(total, start));
        end = Math.max(0, Math.min(total, end));
        if (!wanted.still && end <= start) {
            throw new IllegalArgumentException("The range to export is empty: it starts at tick " + start + " and ends at tick " + end);
        }
        int scale = wanted.supersample ? 2 : 1;
        boolean pictures = wanted.still || wanted.container.equals("png_sequence");
        if ((wanted.stemFiles || wanted.stemTracks) && !wanted.audio && !pictures) {
            throw new IllegalArgumentException("Separate sound tracks were asked for in an export without sound");
        }
        // With sound, the video is first written without it beside the output and joined with the sound at the end (EXP-3)
        Path soundless = ExportSound.prepare(wanted, pictures);
        FrameSink newSink = pictures ? new PngSink(wanted.output, wanted.still, scale, wanted.transparent) : Encoder.videoSink(wanted, scale, soundless != null ? soundless : wanted.output);

        settings = wanted;
        lastNote = wanted.note;
        sink = newSink;
        timeMap = new TimeMap(scene, start, end);
        totalFrames = wanted.still ? 1 : Math.max(1, timeMap.frameCount(wanted.framesPerSecond));
        frame = 0;
        // The first picture always waits for the terrain to settle: the world may have been rebuilt a moment ago
        worldRebuilt = true;
        lastDrawn = -1;
        rebuildWaits = 0;
        settledFrames = 0;
        preparedFrame = -1;
        frameReady = false;
        cancelRequested = false;
        lastError = null;
        writerFailure = null;
        queue = new ArrayBlockingQueue<>(3);
        writer = new Thread(Export::write, "Dailies-Export-Writer");
        writer.setDaemon(true);
        writer.start();

        Window window = minecraft.getWindow();
        savedPosition = Playback.position();
        savedPaused = Playback.paused();
        savedSpeed = Playback.ticksPerSecond();
        savedWidth = realWidth = window.getWidth();
        savedHeight = realHeight = window.getHeight();
        savedHideGui = minecraft.options.hideGui;
        savedFollow = EditorSession.followCamera();
        startedNanos = System.nanoTime();
        active = true;

        // An export draws each frame once and moves on: nothing should make it wait for the monitor or a frame limit
        window.updateVsync(false);
        window.setFramerateLimit(260);
        minecraft.options.hideGui = wanted.hideGui || !scene.looks().showHud;
        window.setWidth(wanted.width * scale);
        window.setHeight(wanted.height * scale);
        minecraft.resizeDisplay();
        if (!wanted.still) {
            EditorSession.setExporting(true);
            Playback.exportMode(true);
            // From here the game's sound goes into the file and not to the speakers; restore() puts it back
            ExportSound.begin(wanted, timeMap, scene);
        }
        Host.LOGGER.info("Dailies: export started, {} frames of {}x{} to {}", totalFrames, wanted.width, wanted.height, wanted.output);
    }

    public static void cancel() {
        if (!active) {
            throw new IllegalStateException("No export is running");
        }
        cancelRequested = true;
    }

    // --- The work ---

    /** The start of every frame while an export runs: bring the recording to the moment this frame shows */
    public static void frameStart(Minecraft minecraft) {
        if (!active) {
            return;
        }
        try {
            if (writerFailure != null) {
                throw new IllegalStateException("The frames could not be written: " + writerFailure.getMessage(), writerFailure);
            }
            if (cancelRequested || frame >= totalFrames) {
                finish(minecraft, cancelRequested);
                return;
            }
            if (comedymonkey.dailies.playback.PackPlayback.covered()) {
                // The game is loading the recording's resource packs and draws its loading screen over the picture
                frameReady = false;
                worldRebuilt = true;
                return;
            }
            if (settings.still) {
                frameReady = true;
                return;
            }
            if (Playback.jumping()) {
                // The world is being rebuilt: its terrain has to be drawn afresh before any picture is taken
                worldRebuilt = true;
                return;
            }
            double time = timeMap.tickAtFrame(frame, settings.framesPerSecond);
            int whole = (int) Math.floor(time);
            int position = whole + 1;
            float partial = (float) (time - whole);
            if (position > Playback.total()) {
                position = Playback.total();
                partial = 1.0F;
            }
            if (position < Playback.position()) {
                // Behind us: the first frame of a range that starts earlier than where playback stood
                Playback.jumpTo(position, false);
                worldRebuilt = true;
                return;
            }
            if (!Playback.runTo(minecraft, position)) {
                return;
            }
            if (Playback.jumping()) {
                // The tick just played changed the server's resource packs: no picture until they have been loaded
                worldRebuilt = true;
                return;
            }
            partialTick = partial;
            if (preparedFrame != frame) {
                preparedFrame = frame;
                framesToThrowAway = Math.max(0, Host.get().setting("throwawayFrames", 0));
                chunkWaits = 0;
            }
            frameReady = true;
        } catch (Throwable t) {
            fail(minecraft, t);
        }
    }

    /** After the game has drawn a frame and before it reaches the screen: take the picture */
    private static void afterFrame() {
        if (!machineTold) {
            // Encoder trials are remembered per graphics card and driver, and only this thread may ask which they are
            machineTold = true;
            try {
                Encoder.machine(com.mojang.blaze3d.platform.GlUtil.getRenderer() + ", " + com.mojang.blaze3d.platform.GlUtil.getOpenGLVersion());
            } catch (Throwable t) {
                Host.LOGGER.debug("Dailies: the graphics card could not be asked its name", t);
            }
        }
        if (!active || !frameReady) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        try {
            // Everything that loads over several frames has to be there first (EXP-5)
            if (minecraft.levelRenderer != null && !minecraft.levelRenderer.hasRenderedAllSections() && chunkWaits++ < 400) {
                return;
            }
            if (worldRebuilt && minecraft.levelRenderer != null) {
                // A world that has only just appeared reports nothing left to draw before it has drawn anything. Wait until
                // some terrain is on screen and the amount has stopped growing
                int drawn = minecraft.levelRenderer.countRenderedSections();
                settledFrames = drawn > 0 && drawn == lastDrawn ? settledFrames + 1 : 0;
                lastDrawn = drawn;
                if (settledFrames < 8 && rebuildWaits++ < 600) {
                    return;
                }
                worldRebuilt = false;
                rebuildWaits = 0;
                settledFrames = 0;
            }
            if (framesToThrowAway > 0) {
                framesToThrowAway--;
                return;
            }
            RenderTarget target = minecraft.getMainRenderTarget();
            int bytes = target.width * target.height * 4;
            if (pixels == null || pixels.capacity() != bytes) {
                if (pixels != null) {
                    MemoryUtil.memFree(pixels);
                }
                pixels = MemoryUtil.memAlloc(bytes);
            }
            pixels.clear();
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, target.frameBufferId);
            RenderSystem.pixelStore(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glReadPixels(0, 0, target.width, target.height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            byte[] copy = new byte[bytes + 8];
            pixels.get(0, copy, 0, bytes);
            // The size rides along in the last eight bytes, so the writer needs nothing but the array
            ByteBuffer.wrap(copy, bytes, 8).putInt(target.width).putInt(target.height);
            while (!queue.offer(copy, 50, TimeUnit.MILLISECONDS)) {
                if (writerFailure != null) {
                    throw new IllegalStateException("The frames could not be written: " + writerFailure.getMessage(), writerFailure);
                }
            }
            frame++;
            // The sound that belongs to this picture, exactly as much as one frame lasts
            ExportSound.frame(frame);
            frameReady = false;
            minecraft.getWindow().setTitle("Dailies: exporting " + frame + " of " + totalFrames);
        } catch (Throwable t) {
            fail(minecraft, t);
        }
    }

    private static void write() {
        long number = 0;
        try {
            while (true) {
                byte[] picture = queue.take();
                if (picture == END) {
                    return;
                }
                ByteBuffer size = ByteBuffer.wrap(picture, picture.length - 8, 8);
                sink.frame(number++, picture, size.getInt(), size.getInt());
            }
        } catch (Throwable t) {
            writerFailure = t;
            queue.clear();
        }
    }

    private static void finish(Minecraft minecraft, boolean cancelled) throws Exception {
        queue.put(END);
        writer.join(120_000);
        if (writerFailure != null) {
            throw new IllegalStateException("The frames could not be written: " + writerFailure.getMessage(), writerFailure);
        }
        lastOutput = ExportSound.finish(sink.finish(cancelled), cancelled);
        lastCancelled = cancelled;
        Host.LOGGER.info("Dailies: export {} after {} of {} frames: {}", cancelled ? "stopped" : "finished", frame, totalFrames, lastOutput);
        restore(minecraft);
    }

    private static void fail(Minecraft minecraft, Throwable problem) {
        Host.LOGGER.error("Dailies: the export failed", problem);
        lastError = problem.getMessage() == null ? problem.toString() : problem.getMessage();
        try {
            queue.clear();
            queue.offer(END);
            sink.abort();
        } catch (Throwable ignored) {
            // Already failing
        }
        ExportSound.abort();
        restore(minecraft);
    }

    /** Puts the game back exactly as it was before the export (EXP-7) */
    private static void restore(Minecraft minecraft) {
        boolean wasStill = settings != null && settings.still;
        frameReady = false;
        // The game's usual sound device comes back however the export ended
        ExportSound.restore();
        Window window = minecraft.getWindow();
        window.setWidth(realWidth > 0 ? realWidth : savedWidth);
        window.setHeight(realHeight > 0 ? realHeight : savedHeight);
        minecraft.resizeDisplay();
        minecraft.options.hideGui = savedHideGui;
        minecraft.updateTitle();
        if (!wasStill) {
            EditorSession.setExporting(false);
            EditorSession.setFollowCamera(savedFollow);
            Playback.exportMode(false);
            Playback.setTicksPerSecond(savedSpeed);
            if (Playback.loaded()) {
                Playback.jumpTo(savedPosition, true);
                if (!savedPaused) {
                    Playback.whenLoaded(Playback::play);
                }
            }
        }
        minecraft.getWindow().updateVsync(minecraft.options.enableVsync().get());
        minecraft.getWindow().setFramerateLimit(minecraft.options.framerateLimit().get());
        // Said last: by the time anyone hears the export is over, playback is already on its way back
        active = false;
    }

}
