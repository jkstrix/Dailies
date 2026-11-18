package comedymonkey.dailies.export;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
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


import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.Util;

/**
 * Renders a scene's range to pictures or a video, one frame at a time, however long each frame takes (specification
 * chapter VI). Frame n is drawn at the moment of the recording that n ÷ frame rate seconds maps to, so the result is
 * the same on a fast computer and a slow one. Everything here runs on the client thread except the writing of frames
 * and the finishing of the file once every picture is taken.
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
        /** Also write what was said in the range as SRT and WebVTT files beside the video (EXP-11) */
        public boolean captionSrt = false;
        public boolean captionVtt = false;
        /** Begin each caption with the speaker's name */
        public boolean captionSpeakers = true;
        /** Draw what was said into the picture (EXP-15) */
        public boolean burnCaptions = false;
        /** Lines or word by word; null for the setting */
        public @Nullable Captions.Style captionStyle = null;

        public boolean captions() {
            return this.captionSrt || this.captionVtt || this.burnCaptions;
        }
    }

    /** How long a burned-in export waits for its captions to be worked out before it goes on without them */
    private static final long CAPTION_WAIT_MILLIS = 30_000;

    private static volatile boolean active = false;
    private static volatile boolean cancelRequested = false;
    private static volatile long frame = 0;
    private static volatile long totalFrames = 0;
    private static volatile String lastError = null;
    private static volatile Path lastOutput = null;
    private static volatile boolean lastCancelled = false;
    private static volatile long startedNanos = 0;
    private static volatile String lastNote = null;
    private static volatile @Nullable String lastTimings = null;
    private static volatile @Nullable String captionNote = null;
    private static volatile List<Path> captionFiles = List.of();
    private static volatile @Nullable List<Captions.Cue> burnCues = null;
    private static long captionWaitSince = 0;
    private static boolean machineTold = false;
    private static ExportPresets presets = null;

    private static Settings settings;
    /** The ticks exported, the first included and the last not */
    private static int exportFrom;
    private static int exportTo;
    private static TimeMap timeMap;
    private static FrameSink sink;
    private static Thread writer;
    private static FramePool pool;
    private static @Nullable GpuCapture capture;
    /** What hands each picture read back to this export's own frames, whichever thread it is read back on */
    private static GpuCapture.Receiver receiver;
    private static long delivered = 0;
    private static volatile Throwable writerFailure = null;
    /** The thread finishing the file once every picture is taken, or null before (EXP-3) */
    private static volatile @Nullable Thread finisher = null;
    /** Set by the finishing thread when it is done, with what it wrote or why it could not */
    private static volatile boolean finished = false;
    private static volatile @Nullable Path finishedOutput = null;
    private static volatile @Nullable Throwable finishFailure = null;
    private static boolean finishCancelled = false;
    /** Whether the finishing thread is joining the sound to the picture, the one stage a cancel stops */
    private static volatile boolean soundStage = false;
    /** How long the game waits for the writer to take a frame before the encoder is taken to have stalled */
    private static final long WRITER_WAIT_NANOS = 120_000_000_000L;
    /** How long it waits once the export has been cancelled */
    private static final long CANCELLED_WAIT_NANOS = 10_000_000_000L;
    private static final ExportTimings TIMINGS = new ExportTimings();
    /** When the game's thread last left the export's hooks, by {@link System#nanoTime}; 0 before the first frame */
    private static long drawFrom = 0;
    private static long loopFrom = 0;
    private static long titleNanos = 0;

    private static float partialTick = 1.0F;
    private static long preparedFrame = -1;
    private static int framesToThrowAway = 0;
    private static int chunkWaits = 0;
    private static boolean frameReady = false;
    private static boolean worldRebuilt = false;
    private static int lastDrawn = -1;
    private static int settledFrames = 0;
    private static int rebuildWaits = 0;
    private static int farTerrainWaits = 0;
    private static boolean farTerrainWaitTold = false;
    /** Where the export's own clock started, by the real clocks, for the renderers that keep time by them (REN-4) */
    private static long clockBaseNanos = 0;
    private static long clockBaseMillis = 0;

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
        QueueRunner.init();
        CaptionBurner.init();
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

    /** Where the time of the last export went, stage by stage, as its line in the log; null before the first */
    public static @Nullable String lastTimings() {
        return lastTimings;
    }

    /** What became of the captions of the export under way or the last one; null when none were asked for (EXP-11, EXP-15) */
    public static @Nullable String captionNote() {
        return captionNote;
    }

    /** The caption files the last export wrote */
    public static List<Path> captionFiles() {
        return captionFiles;
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

    // --- The window during a video export: shown a few dozen times a second, not at every one of hundreds of frames ---
    private static final long WINDOW_NANOS = 33_000_000L;
    private static long windowShownAt = 0L;
    private static boolean windowThisFrame = true;
    private static int windowFramesShown = 0;
    private static int windowFramesSkipped = 0;

    /**
     * Asked once a frame, where the game would put its picture in the window: whether the window is drawn and shown
     * this frame. During a video export it shows only the progress panel, and drawing it and handing it to the window
     * system at every frame took the game seven tenths of a millisecond of each, at hundreds of frames a second; thirty
     * times a second reads just as well. Render thread
     */
    public static boolean windowFrameDue() {
        long now = System.nanoTime();
        windowThisFrame = !holdsClock() || now - windowShownAt >= WINDOW_NANOS || Boolean.getBoolean("dailies.export.everyWindowFrame");
        if (windowThisFrame) {
            windowShownAt = now;
            windowFramesShown++;
        } else {
            windowFramesSkipped++;
        }
        return windowThisFrame;
    }

    /** Whether the window is shown this frame, as {@link #windowFrameDue} last said */
    public static boolean windowThisFrame() {
        return windowThisFrame || !holdsClock();
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

    /**
     * The moment of the video the frame being drawn belongs to, as a reading of {@link System#nanoTime}: the export's
     * start plus exactly frame ÷ frame rate seconds, however long the frames really took. Shader packs and other
     * renderers that animate by the real clock (water, wind, Veil's effects) read this instead while an export holds
     * the clock, so an exported frame shows what a live one at that moment would (REN-4). Only meaningful while
     * {@link #holdsClock()} is true. Render thread
     */
    public static long frameClockNanos() {
        Settings current = settings;
        return current == null ? System.nanoTime() : clockBaseNanos + Math.round(frame * 1.0e9 / current.framesPerSecond);
    }

    /** As {@link #frameClockNanos()}, as a reading of {@link System#currentTimeMillis} */
    public static long frameClockMillis() {
        Settings current = settings;
        return current == null ? System.currentTimeMillis() : clockBaseMillis + Math.round(frame * 1000.0 / current.framesPerSecond);
    }

    /** The size to show an export picture at in the real window: as large as fits, in its own proportions */
    public static int[] previewSize(int pictureWidth, int pictureHeight) {
        int windowWidth = realWidth > 0 ? realWidth : pictureWidth;
        int windowHeight = realHeight > 0 ? realHeight : pictureHeight;
        double scale = Math.min((double) windowWidth / pictureWidth, (double) windowHeight / pictureHeight);
        return new int[]{Math.max(1, (int) Math.round(pictureWidth * scale)), Math.max(1, (int) Math.round(pictureHeight * scale))};
    }

    /** The size of the window on the desktop, in pixels, whatever size the game is drawing at */
    private static int[] realWindowSize(Window window) {
        int[] width = new int[1];
        int[] height = new int[1];
        org.lwjgl.glfw.GLFW.glfwGetFramebufferSize(window.getWindow(), width, height);
        if (width[0] <= 0 || height[0] <= 0) {
            // Minimised: the game's record is the best there is
            return new int[]{window.getWidth(), window.getHeight()};
        }
        return new int[]{width[0], height[0]};
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
        // A video is handed to the encoder in its own format, converted on the graphics card, wherever that loses nothing
        boolean i420 = !pictures && scale == 1 && !wanted.transparent && I420.fits(wanted.width, wanted.height)
            && Host.get().setting("exportGpuColour", true) && GpuCapture.converterReady();
        FrameSink newSink = pictures ? new PngSink(wanted.output, wanted.still, scale, wanted.transparent) : Encoder.videoSink(wanted, scale, soundless != null ? soundless : wanted.output, i420);
        // From here the encoder runs: whatever goes wrong before the export is under way stops it again, and whatever
        // goes wrong after puts the game back too, so no encoder is left running with half a file (EXP-4)
        FramePool newPool = null;
        try {
            int drawnWidth = wanted.width * scale;
            int drawnHeight = wanted.height * scale;
            long frameBytes = newSink.layout() == FrameSink.Layout.I420 ? I420.size(drawnWidth, drawnHeight) : 4L * drawnWidth * drawnHeight;
            if (frameBytes > Integer.MAX_VALUE - 16) {
                throw new IllegalArgumentException("A picture of " + drawnWidth + "x" + drawnHeight + " is too large to export");
            }
            TimeMap newMap = new TimeMap(scene, start, end);
            // Enough for the encoder to work on one while the game fills the next; fewer of the very large ones. A sink
            // that holds on to frames (several encoders at once) has its own share on top, made only as it needs them
            newPool = new FramePool((wanted.still ? 1 : frameBytes > 64L * 1024 * 1024 ? 3 : 5) + newSink.keeps(), (int) frameBytes);

            settings = wanted;
            lastNote = wanted.note;
            lastTimings = null;
            sink = newSink;
            timeMap = newMap;
            exportFrom = start;
            exportTo = Math.min(total, end + 1);
            totalFrames = wanted.still ? 1 : Math.max(1, timeMap.frameCount(wanted.framesPerSecond));
            frame = 0;
            // The first picture always waits for the terrain to settle: the world may have been rebuilt a moment ago
            worldRebuilt = true;
            lastDrawn = -1;
            rebuildWaits = 0;
            settledFrames = 0;
            farTerrainWaits = 0;
            farTerrainWaitTold = false;
            clockBaseNanos = System.nanoTime();
            clockBaseMillis = System.currentTimeMillis();
            preparedFrame = -1;
            frameReady = false;
            cancelRequested = false;
            lastError = null;
            writerFailure = null;
            finisher = null;
            finished = false;
            finishedOutput = null;
            finishFailure = null;
            soundStage = false;
            pool = newPool;
            receiver = deliverInto(newPool);
            capture = null;
            delivered = 0;
            TIMINGS.reset();
            drawFrom = 0;
            loopFrom = 0;
            titleNanos = 0;
            windowShownAt = 0L;
            windowThisFrame = true;
            windowFramesShown = 0;
            windowFramesSkipped = 0;
            writer = new Thread(Export::write, "Dailies-Export-Writer");
            writer.setDaemon(true);
            writer.start();

            Window window = minecraft.getWindow();
            savedPosition = Playback.position();
            savedPaused = Playback.paused();
            savedSpeed = Playback.ticksPerSecond();
            // The real window's size, asked of the window system itself: the game's own record of it may still hold the size
            // of an export that has only just finished, when exports run one after another
            int[] real = realWindowSize(window);
            savedWidth = realWidth = real[0];
            savedHeight = realHeight = real[1];
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
            startCaptions(wanted, pictures, start, end);
        } catch (Throwable t) {
            if (active) {
                // Under way already: stopped as any failed export is, and the game put back
                fail(minecraft, t);
            } else {
                try {
                    if (newPool != null) {
                        newPool.abandon();
                    }
                    newSink.abort();
                } catch (Throwable ignored) {
                    // Already failing
                }
                ExportSound.abort();
            }
            throw t;
        }
        Host.LOGGER.info("Dailies: export started, {} frames of {}x{} to {}", totalFrames, wanted.width, wanted.height, wanted.output);
    }

    public static void cancel() {
        if (!active) {
            throw new IllegalStateException("No export is running");
        }
        cancelRequested = true;
        // Finishing, the picture is always completed, being all there is to keep; joining the sound to it, which can
        // take minutes, is stopped, and the picture kept without it
        Thread finishing = finisher;
        if (finishing != null && soundStage) {
            finishing.interrupt();
        }
    }

    /** Whether the export has taken all its pictures and is finishing its file: joining parts, joining the sound */
    public static boolean finishing() {
        return active && finisher != null;
    }

    /**
     * Works out what was said in the range and writes it as caption files beside the output, away from the client
     * thread; a burned-in export waits for it before its first frame (EXP-11, EXP-15).
     */
    private static void startCaptions(Settings wanted, boolean pictures, int start, int end) {
        burnCues = null;
        captionFiles = List.of();
        captionNote = null;
        captionWaitSince = System.currentTimeMillis();
        if (wanted.still || !wanted.captions()) {
            return;
        }
        String recording = Playback.recordingId();
        TimeMap map = timeMap;
        double total = map.totalRealSeconds();
        Path output = wanted.output;
        boolean srt = wanted.captionSrt;
        boolean vtt = wanted.captionVtt;
        boolean speakers = wanted.captionSpeakers;
        boolean burn = wanted.burnCaptions;
        captionNote = "the captions are being worked out";
        CompletableFuture.supplyAsync(() -> {
            comedymonkey.dailies.moments.MomentsTranscript.Transcript transcript = comedymonkey.dailies.moments.Moments.transcriptFor(recording, start, end);
            List<Captions.Cue> cues = Captions.cues(transcript, map::realSecondsAt, total);
            List<Path> written = new ArrayList<>();
            try {
                if (srt) {
                    written.add(writeCaptions(output, pictures, "srt", Captions.srt(cues, speakers)));
                }
                if (vtt) {
                    written.add(writeCaptions(output, pictures, "vtt", Captions.vtt(cues, speakers)));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            captionFiles = List.copyOf(written);
            String said = cues.isEmpty() ? "nobody was heard in this range, so the captions are empty" : cues.size() + " captions";
            captionNote = said + (written.isEmpty() ? "" : ", written to " + String.join(" and ", written.stream().map(path -> path.getFileName().toString()).toList()))
                + (burn ? (cues.isEmpty() ? "" : ", drawn into the picture") : "");
            return cues;
        }, Util.ioPool()).whenComplete((cues, error) -> {
            if (error != null) {
                Host.LOGGER.error("Dailies: the captions could not be made", error);
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                captionNote = "the captions could not be made: " + (cause.getMessage() == null ? cause.toString() : cause.getMessage());
                burnCues = List.of();
            } else {
                burnCues = cues;
            }
        });
    }

    /** clip.mp4 gets clip.srt beside it; a folder of pictures gets captions.srt inside it */
    private static Path writeCaptions(Path output, boolean pictures, String ending, String text) throws IOException {
        Path file;
        if (pictures) {
            Files.createDirectories(output);
            file = output.resolve("captions." + ending);
        } else {
            String name = output.getFileName().toString();
            int dot = name.lastIndexOf('.');
            file = output.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + "." + ending);
        }
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    // --- The work ---

    /** The start of every frame while an export runs: bring the recording to the moment this frame shows */
    public static void frameStart(Minecraft minecraft) {
        if (!active) {
            return;
        }
        long begun = System.nanoTime();
        if (loopFrom > 0) {
            TIMINGS.add(ExportTimings.Stage.LOOP, begun - loopFrom);
        }
        try {
            prepareFrame(minecraft);
        } finally {
            drawFrom = System.nanoTime();
        }
    }

    private static void prepareFrame(Minecraft minecraft) {
        try {
            if (finisher != null) {
                pollFinish(minecraft);
                return;
            }
            if (writerFailure != null) {
                throw new IllegalStateException("The frames could not be written: " + writerFailure.getMessage(), writerFailure);
            }
            if (cancelRequested || frame >= totalFrames) {
                beginFinish(minecraft, cancelRequested);
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
            long ticksFrom = System.nanoTime();
            boolean there = Playback.runTo(minecraft, position);
            TIMINGS.add(ExportTimings.Stage.TICKS, System.nanoTime() - ticksFrom);
            if (!there) {
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
            // Unpacking the encoder and trying the graphics card's encoders takes seconds: done now, beside the game,
            // and not on this thread when the first export starts
            Encoder.warmUp();
        }
        if (!active) {
            return;
        }
        if (finisher != null) {
            // Asked here as well as at the start of the frame, which only comes while a recording is open
            pollFinish(Minecraft.getInstance());
            return;
        }
        long entered = System.nanoTime();
        if (drawFrom > 0) {
            TIMINGS.add(ExportTimings.Stage.DRAW, entered - drawFrom);
            drawFrom = 0;
        }
        try {
            takePicture();
        } finally {
            loopFrom = System.nanoTime();
        }
    }

    private static void takePicture() {
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
                // Distant Horizons builds and draws its far terrain over many frames of its own after the near terrain is
                // done: the same wait, for it (CMP-1). Never forever: a far terrain that will not settle is exported as it is
                if (!comedymonkey.dailies.compat.distanthorizons.DistantHorizonsCompat.settled()) {
                    if (farTerrainWaits++ < 600) {
                        return;
                    }
                    if (!farTerrainWaitTold) {
                        farTerrainWaitTold = true;
                        Host.LOGGER.warn("Dailies: Distant Horizons had not settled after 600 frames; the export goes on with its far terrain as it is. Said once per export");
                    }
                }
                worldRebuilt = false;
                rebuildWaits = 0;
                settledFrames = 0;
                farTerrainWaits = 0;
            }
            if (framesToThrowAway > 0) {
                framesToThrowAway--;
                return;
            }
            RenderTarget target = minecraft.getMainRenderTarget();
            if (settings.burnCaptions && !settings.still) {
                List<Captions.Cue> cues = burnCues;
                if (cues == null && System.currentTimeMillis() - captionWaitSince < CAPTION_WAIT_MILLIS) {
                    // Every frame has its words, the first ones too
                    return;
                }
                if (cues == null) {
                    burnCues = cues = List.of();
                    captionNote = "the captions took too long to work out and were left out of the picture";
                }
                String said = Captions.at(cues, frame / settings.framesPerSecond, settings.captionStyle != null ? settings.captionStyle
                    : Captions.style(Host.get().setting("captionStyle", "LINES")), settings.captionSpeakers);
                if (said != null) {
                    CaptionBurner.drawIntoFrame(minecraft, target, said, settings.captionStyle);
                }
            }
            if (capture == null) {
                capture = new GpuCapture(sink.layout() == FrameSink.Layout.I420, target.width, target.height);
            }
            if (capture.full()) {
                // Never so with the usual lag, but a frame must never be lost: the oldest goes first
                capture.collect(true, receiver, waited -> TIMINGS.add(ExportTimings.Stage.READBACK, waited));
            }
            long convertFrom = System.nanoTime();
            capture.issue(target);
            long slotWait = capture.takeSlotWaitNanos();
            TIMINGS.add(ExportTimings.Stage.CONVERT, System.nanoTime() - convertFrom - slotWait);
            TIMINGS.add(ExportTimings.Stage.QUEUE, slotWait);
            frame++;
            // The sound that belongs to this picture, exactly as much as one frame lasts
            long soundFrom = System.nanoTime();
            ExportSound.frame(frame);
            TIMINGS.add(ExportTimings.Stage.SOUND, System.nanoTime() - soundFrom);
            frameReady = false;
            // The pictures asked for two frames ago are ready by now, and go to the writer
            capture.collect(false, receiver, waited -> TIMINGS.add(ExportTimings.Stage.READBACK, waited));
            long now = System.nanoTime();
            if (now - titleNanos > 250_000_000L || frame >= totalFrames) {
                // The window's title goes through the window system on this thread: a few times a second is plenty
                titleNanos = now;
                minecraft.getWindow().setTitle("Dailies: exporting " + frame + " of " + totalFrames);
            }
        } catch (Throwable t) {
            fail(minecraft, t);
        }
    }

    /** What copies each picture read back into a free frame of this export's own frames, and no other export's */
    private static GpuCapture.Receiver deliverInto(FramePool frames) {
        return (pixels, length, width, height) -> deliver(frames, pixels, length, width, height);
    }

    /**
     * A picture read back from the graphics card, copied into a free frame buffer for the writer. Render thread, or
     * the copying thread beside it. Waits for a free buffer only so long: an encoder that has stopped taking frames
     * without ending would otherwise hold the game still for good (EXP-8)
     */
    private static void deliver(FramePool frames, ByteBuffer pixels, int length, int width, int height) throws Exception {
        long from = System.nanoTime();
        FramePool.Frame free;
        while ((free = frames.take(50)) == null) {
            if (writerFailure != null) {
                throw new IllegalStateException("The frames could not be written: " + writerFailure.getMessage(), writerFailure);
            }
            long waited = System.nanoTime() - from;
            if (waited > WRITER_WAIT_NANOS || cancelRequested && waited > CANCELLED_WAIT_NANOS) {
                throw new IllegalStateException("The encoder took no frame for " + waited / 1_000_000_000L + " seconds and seems to have stalled");
            }
        }
        long got = System.nanoTime();
        TIMINGS.add(ExportTimings.Stage.QUEUE, got - from);
        if (length > free.data.length) {
            frames.giveBack(free);
            throw new IllegalStateException("A picture of " + length + " bytes does not fit the export's frame buffers of " + free.data.length);
        }
        pixels.get(0, free.data, 0, length);
        free.length = length;
        free.width = width;
        free.height = height;
        free.number = delivered++;
        frames.send(free);
        TIMINGS.add(ExportTimings.Stage.COPY, System.nanoTime() - got);
    }

    private static void write() {
        FramePool frames = pool;
        FrameSink out = sink;
        // A sink that holds on to frames is handed them as they are and gives each back when it is done with it
        boolean handOver = out.keeps() > 0;
        try {
            while (true) {
                long idleFrom = System.nanoTime();
                FramePool.Frame picture = frames.next();
                long from = System.nanoTime();
                TIMINGS.add(ExportTimings.Stage.WRITER_IDLE, from - idleFrom);
                if (picture == FramePool.END) {
                    return;
                }
                if (handOver) {
                    try {
                        out.keep(picture.number, picture.data, picture.length, picture.width, picture.height, () -> frames.giveBack(picture));
                    } catch (Throwable t) {
                        // Not kept
                        frames.giveBack(picture);
                        throw t;
                    }
                } else {
                    try {
                        out.frame(picture.number, picture.data, picture.length, picture.width, picture.height);
                    } finally {
                        frames.giveBack(picture);
                    }
                }
                TIMINGS.add(ExportTimings.Stage.PIPE, System.nanoTime() - from);
            }
        } catch (Throwable t) {
            writerFailure = t;
            frames.abandon();
        }
    }

    /** Where the time went, in one line of the log, so a slow export can be read rather than guessed at */
    private static void logTimings() {
        TIMINGS.frames(frame);
        Settings current = settings;
        String what = (current == null ? "?" : current.width + "x" + current.height + " " + (current.still || current.container.equals("png_sequence") ? "pictures" : current.videoCodec))
            + ", " + (capture != null && capture.i420() ? "converted on the graphics card" : "RGBA")
            + (capture != null && capture.persistent() ? ", copied out beside the game (copy and queue wait are on that thread)" : "")
            + (windowFramesSkipped > 0 ? ", the window shown " + windowFramesShown + " times" : "");
        lastTimings = TIMINGS.line(what);
        Host.LOGGER.info("Dailies: {}", lastTimings);
    }

    /**
     * Every picture is taken, or the export was cancelled: the last pictures are taken off the graphics card here,
     * and the rest, which can take minutes (the encoder ending, the parts of several encoders joined, the sound joined
     * to the picture), is done on a thread of its own (EXP-3). The game goes on showing the progress panel meanwhile,
     * and can still be cancelled; {@link #pollFinish} puts it back once the file is done. Client thread
     */
    private static void beginFinish(Minecraft minecraft, boolean cancelled) throws Exception {
        if (capture != null) {
            // The last pictures are still on the graphics card
            capture.collect(true, receiver, waited -> TIMINGS.add(ExportTimings.Stage.READBACK, waited));
            // And the copying thread has finished with them before the writer is told there are no more
            capture.awaitCopies();
        }
        pool.end();
        // Nothing more goes into the file: the game's sound stops going to it
        ExportSound.stopCapture();
        finishCancelled = cancelled;
        finished = false;
        finishedOutput = null;
        finishFailure = null;
        // Only the panel is shown while the file is finished: there is no need to draw hundreds of frames a second
        minecraft.getWindow().setFramerateLimit(30);
        minecraft.getWindow().setTitle("Dailies: finishing the export");
        FrameSink out = sink;
        Thread frameWriter = writer;
        Thread thread = new Thread(() -> finishFile(out, frameWriter, cancelled), "Dailies-Export-Finish");
        thread.setDaemon(true);
        finisher = thread;
        thread.start();
    }

    /** The finishing thread's work. Never throws: what it wrote, or why it could not, is left for the client thread */
    private static void finishFile(FrameSink out, Thread frameWriter, boolean cancelled) {
        try {
            frameWriter.join(WRITER_WAIT_NANOS / 1_000_000L);
            if (frameWriter.isAlive()) {
                // Taken as finished, the file would end short of the export and be called a success (L-7)
                throw new IOException("The last frames did not reach the encoder in two minutes");
            }
            Throwable failed = writerFailure;
            if (failed != null) {
                throw new IllegalStateException("The frames could not be written: " + failed.getMessage(), failed);
            }
            Path written = out.finish(cancelled);
            soundStage = true;
            if (cancelRequested) {
                // Cancelled while the picture was being finished: the sound is not joined
                Thread.currentThread().interrupt();
            }
            try {
                finishedOutput = ExportSound.finish(written, cancelled);
            } finally {
                soundStage = false;
                Thread.interrupted();
            }
        } catch (Throwable t) {
            finishFailure = t;
        } finally {
            finished = true;
        }
    }

    /** Once the finishing thread is done: says how the export went and puts the game back. Client thread */
    private static void pollFinish(Minecraft minecraft) {
        if (!finished) {
            return;
        }
        Throwable failure = finishFailure;
        if (failure != null) {
            fail(minecraft, failure);
            return;
        }
        boolean cancelled = finishCancelled || cancelRequested;
        try {
            logTimings();
            lastOutput = finishedOutput;
            lastCancelled = cancelled;
            comedymonkey.dailies.file.RecordingFile exportedFrom = Playback.file();
            if (!cancelled && settings != null && !settings.still && exportedFrom != null) {
                // Remembered by original and tick, so the retention rule spares every recording that holds this footage (REC-8)
                comedymonkey.dailies.library.Library.exported(exportedFrom.id(), exportedFrom.manifest().worldName, exportedFrom.sourceMap(), exportFrom, exportTo, lastOutput);
            }
            Host.LOGGER.info("Dailies: export {} after {} of {} frames: {}", cancelled ? "stopped" : "finished", frame, totalFrames, lastOutput);
        } catch (Throwable t) {
            Host.LOGGER.error("Dailies: the finished export could not be recorded in the library", t);
        }
        restore(minecraft);
    }

    private static void fail(Minecraft minecraft, Throwable problem) {
        Host.LOGGER.error("Dailies: the export failed", problem);
        lastError = problem.getMessage() == null ? problem.toString() : problem.getMessage();
        logTimings();
        try {
            pool.abandon();
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
        if (capture != null) {
            capture.close();
            capture = null;
        }
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
        finisher = null;
        // Said last: by the time anyone hears the export is over, playback is already on its way back
        active = false;
    }

}
