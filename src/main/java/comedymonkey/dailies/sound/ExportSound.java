package comedymonkey.dailies.sound;

import com.mojang.blaze3d.audio.Channel;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.edit.Evaluator;
import comedymonkey.dailies.edit.Keyframe;
import comedymonkey.dailies.edit.Scene;
import comedymonkey.dailies.edit.TimeMap;
import comedymonkey.dailies.export.Encoder;
import comedymonkey.dailies.export.Export;
import comedymonkey.dailies.mixin.sound.SoundEngineAccess;
import comedymonkey.dailies.mixin.sound.SoundManagerAccess;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.SOFTLoopback;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * Puts sound into exported videos (specification EXP-3). While an export with sound runs, the game's sound engine
 * plays into an OpenAL "loopback" device and not into the speakers: nothing is heard, and sound only moves on when
 * this class asks for samples. After every exported picture exactly that picture's share of samples is asked for
 * and written to a temporary file, so sound and picture stay together however slowly the export goes.
 * <p>
 * Recorded voice chat is played into the same device by {@link VoicePlayback}, so it is captured with the game's
 * sound, as loud as {@link VoiceLevels} says from the export's camera and never placed left or right. At the end one run of the bundled encoder joins the soundless video, the
 * captured sound and the scene's audio keyframes into the finished file.
 * <p>
 * With separate tracks asked for (EXP-12) the same sound is also kept apart: the game's sound alone, each speaker's
 * voice alone and the audio keyframes alone. Then the voices are not played into the game's device but placed by
 * {@link VoicePlayback} and handed to {@link #voice}, the audio keyframes are rendered to samples of their own, and
 * the mixed track is the sum of the separate ones, sample for sample, so that they always add up to it.
 * <p>
 * Nothing here may stop an export: when sound cannot be captured the video is finished without it and
 * {@link #note} says why. Client thread throughout, except the flags the sound hooks read.
 */
public final class ExportSound {

    public static final int SAMPLE_RATE = 48000;
    private static final int CHUNK_FRAMES = 4096;
    private static final int BYTES_PER_FRAME = 4;

    /** Read by the sound hooks: the game's sound engine must open a loopback device, and keep it */
    private static volatile boolean wantLoopback = false;
    /** The loopback device the sound engine has open, or 0 */
    private static volatile long loopback = 0L;
    private static volatile boolean capturing = false;
    private static volatile @Nullable String note = null;

    private static Export.Settings settings;
    private static TimeMap timeMap;
    private static List<Keyframe.Audio> cues = List.of();
    private static Scene scene;
    private static @Nullable Path videoFile = null;
    private static @Nullable Path pcmFile = null;
    private static @Nullable OutputStream pcm = null;
    private static long rendered = 0;
    private static @Nullable String problem = null;
    private static final ByteBuffer CHUNK = BufferUtils.createByteBuffer(CHUNK_FRAMES * BYTES_PER_FRAME);
    private static final byte[] BYTES = new byte[CHUNK_FRAMES * BYTES_PER_FRAME];
    // --- Separate tracks (EXP-12) ---

    /** Silence put in front of a stretch of speech, as {@link VoicePlayback} does for the ear: pieces arrive a tick apart */
    private static final int VOICE_LEAD = SAMPLE_RATE * 60 / 1000;

    /** One speaker's voice, written as it arrives: stereo, 16 bits, low byte first, silence wherever nothing was said */
    private static final class Voice {
        String name;
        Path file;
        OutputStream out;
        /** Sample frames written so far, and where the next piece of speech goes */
        long written;
        long cursor;
    }

    /** One finished track: what it is called, the word for it in a file name, and its samples */
    private record Track(String title, String tag, Path samples) {
    }

    /** Read by {@link VoicePlayback}: voices are to be handed over and not played into the game's device */
    private static volatile boolean separate = false;
    private static final Map<UUID, Voice> VOICES = new LinkedHashMap<>();
    private static volatile List<String> lastTracks = List.of();
    private static volatile List<Path> lastFiles = List.of();

    /** Sound files the game is still reading; waited for before samples are taken, so a sound starts on its own frame */
    private static final Queue<CompletableFuture<?>> LOADING = new ConcurrentLinkedQueue<>();

    private ExportSound() {
    }

    // --- What others may ask (any thread) ---

    /** What became of the sound of the export under way or last finished; null when none was asked for */
    public static @Nullable String note() {
        return note;
    }

    /** Whether the game's sound is going into an export's file right now */
    public static boolean capturing() {
        return capturing;
    }

    /** Whether voices are being kept apart for separate tracks: then they are handed to {@link #voice}, not played */
    public static boolean keepsVoicesApart() {
        return capturing && separate;
    }

    /** The names of the sound tracks inside the file the last export wrote, the mixed one first; empty when it has only one */
    public static List<String> tracks() {
        return lastTracks;
    }

    /** The WAV files the last export wrote beside its video */
    public static List<Path> files() {
        return lastFiles;
    }

    // --- For the sound hooks ---

    public static boolean holdsDevice() {
        return wantLoopback;
    }

    /** Opens the loopback device when an export wants one. 0 means: open the usual device */
    public static long openLoopback() {
        if (!wantLoopback) {
            return 0L;
        }
        try {
            long device = SOFTLoopback.alcLoopbackOpenDeviceSOFT((ByteBuffer) null);
            if (device == 0L) {
                problem = "the loopback sound device could not be opened";
                return 0L;
            }
            if (!SOFTLoopback.alcIsRenderFormatSupportedSOFT(device, SAMPLE_RATE, SOFTLoopback.ALC_STEREO_SOFT, SOFTLoopback.ALC_SHORT_SOFT)) {
                ALC10.alcCloseDevice(device);
                problem = "the loopback sound device cannot give 48 kHz 16-bit stereo";
                return 0L;
            }
            loopback = device;
            return device;
        } catch (Throwable t) {
            problem = "the loopback sound device could not be opened (" + t + ")";
            Host.LOGGER.warn("Dailies: the loopback sound device could not be opened", t);
            return 0L;
        }
    }

    public static boolean isLoopback(long device) {
        return device != 0L && device == loopback;
    }

    /** What a context on the loopback device has to state: the format the samples are asked for in */
    public static IntBuffer contextAttributes() {
        IntBuffer attributes = BufferUtils.createIntBuffer(9);
        attributes.put(SOFTLoopback.ALC_FORMAT_CHANNELS_SOFT).put(SOFTLoopback.ALC_STEREO_SOFT);
        attributes.put(SOFTLoopback.ALC_FORMAT_TYPE_SOFT).put(SOFTLoopback.ALC_SHORT_SOFT);
        attributes.put(ALC10.ALC_FREQUENCY).put(SAMPLE_RATE);
        // The output limiter, which the game asks for on its usual device too
        attributes.put(0x199A).put(1);
        attributes.put(0);
        attributes.flip();
        return attributes;
    }

    public static void deviceClosing(long device) {
        if (device != 0L && device == loopback) {
            loopback = 0L;
        }
    }

    public static void loading(@Nullable CompletableFuture<?> load) {
        if (capturing && load != null && !load.isDone()) {
            LOADING.add(load);
        }
    }

    // --- For the export ---

    /**
     * Called before an export's video file is opened. Returns where the soundless video is to be written when sound
     * will be added afterwards, or null when the video goes straight to its final place.
     */
    public static @Nullable Path prepare(Export.Settings wanted, boolean pictures) {
        note = null;
        videoFile = null;
        pcmFile = null;
        problem = null;
        capturing = false;
        separate = false;
        lastTracks = List.of();
        lastFiles = List.of();
        dropVoices();
        if (!wanted.audio || wanted.still) {
            return null;
        }
        if (pictures) {
            note = "no sound: pictures hold none";
            return null;
        }
        boolean supported;
        try {
            supported = ALC10.alcIsExtensionPresent(0L, "ALC_SOFT_loopback");
        } catch (Throwable t) {
            supported = false;
        }
        if (!supported) {
            note = "no sound: this computer's OpenAL has no loopback device (ALC_SOFT_loopback), so the game's sound cannot be captured";
            Host.LOGGER.warn("Dailies: {}", note);
            return null;
        }
        Path output = wanted.output.toAbsolutePath();
        String name = output.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "." + wanted.container;
        videoFile = output.resolveSibling(base + ".dailies-picture" + extension);
        pcmFile = output.resolveSibling(base + ".dailies-sound.pcm");
        separate = wanted.stemFiles || wanted.stemTracks;
        return videoFile;
    }

    /** The export has started: turn the game's sound into the file. Never throws */
    public static void begin(Export.Settings wanted, TimeMap map, Scene exported) {
        if (videoFile == null || pcmFile == null) {
            return;
        }
        settings = wanted;
        timeMap = map;
        scene = exported;
        rendered = 0;
        LOADING.clear();
        try {
            cues = Evaluator.audioCues(exported);
        } catch (Throwable t) {
            cues = List.of();
        }
        try {
            Files.createDirectories(pcmFile.getParent());
            pcm = new BufferedOutputStream(Files.newOutputStream(pcmFile), 1 << 16);
            wantLoopback = true;
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.getSoundManager().reload();
            if (loopback == 0L || !engine(minecraft).dailies$loaded()) {
                String why = problem != null ? problem : "the game's sound engine did not start on the loopback device";
                wantLoopback = false;
                loopback = 0L;
                if (!engine(minecraft).dailies$loaded()) {
                    minecraft.getSoundManager().reload();
                }
                closePcm();
                note = "no sound: " + why;
                Host.LOGGER.warn("Dailies: the export goes on without sound: {}", why);
                return;
            }
            capturing = true;
            note = "capturing the game's sound";
        } catch (Throwable t) {
            Host.LOGGER.warn("Dailies: the export goes on without sound", t);
            note = "no sound: " + t;
            capturing = false;
            closePcm();
            restore();
        }
    }

    /** A picture has been taken: take the sound that belongs to it. Never throws */
    public static void frame(long framesDone) {
        if (!capturing || pcm == null) {
            return;
        }
        try {
            long target = Math.round(framesDone * (double) SAMPLE_RATE / settings.framesPerSecond);
            long need = target - rendered;
            if (need <= 0) {
                return;
            }
            settle();
            long device = loopback;
            while (need > 0) {
                int count = (int) Math.min(need, CHUNK_FRAMES);
                if (device != 0L) {
                    CHUNK.clear();
                    SOFTLoopback.alcRenderSamplesSOFT(device, CHUNK, count);
                    CHUNK.get(0, BYTES, 0, count * BYTES_PER_FRAME);
                } else {
                    // The device went away under us: silence keeps the rest in step
                    Arrays.fill(BYTES, (byte) 0);
                }
                pcm.write(BYTES, 0, count * BYTES_PER_FRAME);
                rendered += count;
                need -= count;
            }
        } catch (Throwable t) {
            Host.LOGGER.warn("Dailies: the game's sound could not be captured any further; the export goes on", t);
            problem = "capturing stopped part way (" + t + ")";
            capturing = false;
        }
    }

    /**
     * A piece of one speaker's voice: it goes into that speaker's own track at the moment of the export it was
     * spoken in, the same in both ears. Client thread. Never throws.
     *
     * @param gain how loud it is, 0 to 1 or so: the distance from the camera and the volume settings, by
     *             {@link VoiceLevels}, so that the track carries what the mix would have
     */
    public static void voice(UUID speaker, String name, short[] mono, float gain) {
        if (!keepsVoicesApart() || pcmFile == null || mono.length == 0) {
            return;
        }
        try {
            Voice voice = VOICES.get(speaker);
            if (voice == null) {
                voice = new Voice();
                voice.name = name;
                String file = pcmFile.getFileName().toString();
                voice.file = pcmFile.resolveSibling(file.substring(0, file.length() - 4) + "-voice" + VOICES.size() + ".pcm");
                voice.out = new BufferedOutputStream(Files.newOutputStream(voice.file), 1 << 15);
                VOICES.put(speaker, voice);
            }
            long now = rendered;
            if (voice.cursor < now) {
                // A new stretch of speech starts a little after the picture it arrived with
                voice.cursor = now + VOICE_LEAD;
            }
            if (voice.cursor > now + SAMPLE_RATE / 2) {
                // More than half a second ahead: the recording is not running at the speed it was spoken at
                return;
            }
            silence(voice.out, voice.cursor - voice.written);
            voice.out.write(VoiceLevels.stereo(mono, gain));
            voice.cursor += mono.length;
            voice.written = voice.cursor;
        } catch (Throwable t) {
            Host.LOGGER.warn("Dailies: a speaker's voice could not be kept for its own track; the export goes on", t);
        }
    }

    private static short clamp(long value) {
        return VoiceLevels.clamp(value);
    }

    private static void silence(OutputStream out, long frames) throws IOException {
        byte[] nothing = new byte[4096];
        for (long left = frames * BYTES_PER_FRAME; left > 0; left -= nothing.length) {
            out.write(nothing, 0, (int) Math.min(left, nothing.length));
        }
    }

    /** Closes the speakers' files and, when asked, removes them */
    private static void closeVoices(boolean remove) {
        for (Voice voice : VOICES.values()) {
            try {
                if (voice.out != null) {
                    voice.out.close();
                    voice.out = null;
                }
                if (remove) {
                    Files.deleteIfExists(voice.file);
                }
            } catch (Throwable t) {
                Host.LOGGER.debug("Dailies: a speaker's captured voice could not be closed", t);
            }
        }
    }

    private static void dropVoices() {
        closeVoices(true);
        VOICES.clear();
    }

    /** Lets everything the game has started reach the sound device, and keeps streamed sounds such as music fed */
    private static void settle() {
        try {
            for (CompletableFuture<?> load = LOADING.poll(); load != null; load = LOADING.poll()) {
                try {
                    load.get(2, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // A sound that cannot be read is the game's to report
                }
            }
            SoundEngineAccess engine = engine(Minecraft.getInstance());
            // The game only tops up streamed sounds on game ticks, and an export may go a long time without one
            engine.dailies$channels().executeOnChannels(channels -> channels.forEach(Channel::updateStream));
            engine.dailies$executor().submit(() -> {
            }).get(2, TimeUnit.SECONDS);
        } catch (Throwable t) {
            Host.LOGGER.debug("Dailies: the sound engine could not be waited for", t);
        }
    }

    private static SoundEngineAccess engine(Minecraft minecraft) {
        return (SoundEngineAccess) ((SoundManagerAccess) minecraft.getSoundManager()).dailies$engine();
    }

    /**
     * The video has been written. Joins it with the sound and returns the finished file. Never throws: at worst the
     * soundless video is moved to its final place.
     */
    public static Path finish(Path written, boolean cancelled) {
        Path video = videoFile;
        if (video == null) {
            return written;
        }
        Path output = settings == null ? written : settings.output.toAbsolutePath();
        capturing = false;
        closePcm();
        try {
            if (rendered > 0 && pcmFile != null && Files.exists(pcmFile) && settings != null) {
                StringBuilder said = new StringBuilder();
                if (separate ? muxApart(video, pcmFile, output, said) : mux(video, pcmFile, output, said)) {
                    note = said.toString();
                    Files.deleteIfExists(video);
                    Files.deleteIfExists(pcmFile);
                    dropVoices();
                    videoFile = null;
                    return output;
                }
                note = "no sound: " + said;
            } else if (note == null || !note.startsWith("no sound")) {
                note = "no sound: " + (problem != null ? problem : "none was captured");
            }
        } catch (Throwable t) {
            Host.LOGGER.warn("Dailies: sound could not be added to the export", t);
            note = "no sound: " + t;
        }
        dropVoices();
        try {
            Files.move(video, output, StandardCopyOption.REPLACE_EXISTING);
            if (pcmFile != null) {
                Files.deleteIfExists(pcmFile);
            }
            videoFile = null;
            return output;
        } catch (Throwable t) {
            Host.LOGGER.warn("Dailies: the soundless video could not be moved to {}; it stays at {}", output, video, t);
            return video;
        }
    }

    /** The export failed: throw the temporary files away */
    public static void abort() {
        capturing = false;
        closePcm();
        dropVoices();
        try {
            if (pcmFile != null) {
                Files.deleteIfExists(pcmFile);
            }
            if (videoFile != null) {
                Files.deleteIfExists(videoFile);
            }
        } catch (Throwable t) {
            Host.LOGGER.debug("Dailies: an export's temporary sound files could not be removed", t);
        }
        videoFile = null;
    }

    /** Gives the game its usual sound device back. Safe to call at any time, and always called when an export ends */
    public static void restore() {
        capturing = false;
        LOADING.clear();
        if (!wantLoopback) {
            return;
        }
        wantLoopback = false;
        try {
            Minecraft.getInstance().getSoundManager().reload();
        } catch (Throwable t) {
            Host.LOGGER.error("Dailies: the game's sound could not be put back after the export. Changing the sound device in the game's options restarts it", t);
        }
        loopback = 0L;
    }

    private static void closePcm() {
        OutputStream open = pcm;
        pcm = null;
        if (open != null) {
            try {
                open.close();
            } catch (IOException e) {
                Host.LOGGER.debug("Dailies: the captured sound could not be closed", e);
            }
        }
    }

    // --- Joining sound and picture ---

    private record Cue(Path file, double volume, double startSeconds, double skipSeconds) {
    }

    private static boolean mux(Path video, Path sound, Path output, StringBuilder said) throws IOException {
        StringBuilder remarks = new StringBuilder();
        String[] codec = codec(remarks);
        List<Cue> extra = cues(remarks);
        String failure = run(command(video, sound, output, codec, extra));
        if (failure != null && !extra.isEmpty()) {
            // An audio keyframe's file the encoder cannot read must not cost the game's sound
            Host.LOGGER.warn("Dailies: the audio keyframes could not be mixed in ({}); trying without them", failure);
            remarks.append("; the audio keyframes could not be mixed in (").append(firstLine(failure)).append(')');
            extra = List.of();
            failure = run(command(video, sound, output, codec, extra));
        }
        if (failure != null) {
            Host.LOGGER.warn("Dailies: sound could not be joined to the video: {}", failure);
            said.append("the encoder could not join sound and picture (").append(firstLine(failure)).append(')');
            return false;
        }
        said.append("game sound, with recorded voice chat where the recording has any, ").append(codec[0]).append(settings.stereo ? ", stereo" : ", mono");
        if (!extra.isEmpty()) {
            said.append(", ").append(extra.size()).append(extra.size() == 1 ? " audio keyframe mixed in" : " audio keyframes mixed in");
        }
        if (problem != null) {
            said.append("; ").append(problem);
        }
        said.append(remarks);
        return true;
    }

    /** The codec's name as asked for, then the encoder arguments */
    private static String[] codec(StringBuilder remarks) throws IOException {
        String container = settings.container.toLowerCase(Locale.ROOT);
        String usual = container.equals("webm") || container.equals("mkv") ? "opus" : "aac";
        String asked = settings.audioCodec == null ? "" : settings.audioCodec.trim().toLowerCase(Locale.ROOT);
        String chosen = asked.isEmpty() || asked.equals("auto") ? usual : asked;
        boolean fits = switch (container) {
            case "webm" -> chosen.equals("opus");
            case "mp4" -> chosen.equals("aac") || chosen.equals("opus");
            case "mov" -> chosen.equals("aac") || chosen.equals("pcm");
            default -> chosen.equals("aac") || chosen.equals("opus") || chosen.equals("pcm");
        };
        if (!fits) {
            remarks.append("; ").append(container).append(" cannot hold ").append(chosen).append(" sound, so ").append(usual).append(" was used");
            chosen = usual;
        }
        String rate = settings.stereo ? "192k" : "128k";
        return switch (chosen) {
            case "pcm" -> new String[]{"pcm", "-c:a", "pcm_s16le"};
            case "opus" -> Encoder.encoders().contains("libopus")
                ? new String[]{"opus", "-c:a", "libopus", "-b:a", rate}
                : new String[]{"opus", "-c:a", "opus", "-strict", "-2", "-b:a", rate};
            default -> new String[]{"aac", "-c:a", "aac", "-b:a", rate};
        };
    }

    /** The scene's audio keyframes that sound within the exported range, with when each starts in the video */
    private static List<Cue> cues(StringBuilder remarks) {
        List<Cue> found = new ArrayList<>();
        int missing = 0;
        for (Keyframe.Audio cue : cues) {
            try {
                if (cue.tick() > timeMap.endTick() || cue.volume() <= 0.0) {
                    continue;
                }
                Path file = Path.of(cue.file());
                if (!file.isAbsolute()) {
                    file = Minecraft.getInstance().gameDirectory.toPath().resolve(file);
                }
                if (!Files.isRegularFile(file)) {
                    missing++;
                    continue;
                }
                if (cue.tick() >= timeMap.startTick()) {
                    found.add(new Cue(file, cue.volume(), timeMap.realSecondsAt(cue.tick()), 0.0));
                } else {
                    // Started before the range: it is already this far in when the video begins
                    TimeMap before = new TimeMap(scene, cue.tick(), timeMap.startTick());
                    found.add(new Cue(file, cue.volume(), 0.0, before.realSecondsAt(timeMap.startTick())));
                }
            } catch (Throwable t) {
                missing++;
            }
        }
        if (missing > 0) {
            remarks.append("; ").append(missing).append(missing == 1 ? " audio keyframe's file was not found" : " audio keyframes' files were not found");
        }
        return found;
    }

    private static List<String> command(Path video, Path sound, Path output, String[] codec, List<Cue> extra) throws IOException {
        List<String> command = new ArrayList<>(List.of(Encoder.programPath().toString(), "-hide_banner", "-loglevel", "error", "-y",
            "-i", video.toString(),
            "-f", ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? "s16le" : "s16be", "-ar", String.valueOf(SAMPLE_RATE), "-ac", "2", "-i", sound.toString()));
        for (Cue cue : extra) {
            if (cue.skipSeconds() > 0.0) {
                command.addAll(List.of("-ss", String.format(Locale.ROOT, "%.4f", cue.skipSeconds())));
            }
            command.addAll(List.of("-i", cue.file().toString()));
        }
        if (extra.isEmpty()) {
            command.addAll(List.of("-map", "0:v:0", "-map", "1:a:0"));
        } else {
            StringBuilder graph = new StringBuilder("[1:a]anull[game]");
            StringBuilder inputs = new StringBuilder("[game]");
            for (int i = 0; i < extra.size(); i++) {
                Cue cue = extra.get(i);
                graph.append(";[").append(i + 2).append(":a:0]aresample=").append(SAMPLE_RATE).append(",aformat=channel_layouts=stereo,volume=")
                    .append(String.format(Locale.ROOT, "%.4f", cue.volume()));
                long delay = Math.round(cue.startSeconds() * 1000.0);
                if (delay > 0) {
                    graph.append(",adelay=").append(delay).append(":all=1");
                }
                graph.append("[cue").append(i).append(']');
                inputs.append("[cue").append(i).append(']');
            }
            // The game's sound is exactly as long as the picture, so it sets the length; nothing is turned down to make room
            graph.append(';').append(inputs).append("amix=inputs=").append(extra.size() + 1).append(":duration=first:normalize=0[mix]");
            command.addAll(List.of("-filter_complex", graph.toString(), "-map", "0:v:0", "-map", "[mix]"));
        }
        command.addAll(List.of("-c:v", "copy"));
        command.addAll(Arrays.asList(codec).subList(1, codec.length));
        command.addAll(List.of("-ar", String.valueOf(SAMPLE_RATE), "-ac", settings.stereo ? "2" : "1"));
        String container = settings.container.toLowerCase(Locale.ROOT);
        if (container.equals("mp4") || container.equals("mov")) {
            command.addAll(List.of("-movflags", "+faststart"));
        }
        command.add(output.toString());
        return command;
    }

    // --- Separate tracks: the game, each speaker and the music apart, and a mix that is their sum (EXP-12) ---

    /**
     * Joins the video with a mixed track made by adding the separate ones together, writes the separate ones as WAV
     * files and as named tracks as asked, and says in {@code said} what was done.
     */
    private static boolean muxApart(Path video, Path game, Path output, StringBuilder said) throws IOException {
        StringBuilder remarks = new StringBuilder();
        String[] codec = codec(remarks);
        long frames = rendered;
        int channels = settings.stereo ? 2 : 1;
        String name = output.getFileName().toString();
        String base = name.lastIndexOf('.') > 0 ? name.substring(0, name.lastIndexOf('.')) : name;
        Path scratch = game.resolveSibling(base + ".dailies-track");
        List<Path> temporary = new ArrayList<>();
        try {
            closeVoices(false);
            // The sources, all as 48 kHz stereo: the game as it was captured, each speaker, the audio keyframes
            List<Path> sources = new ArrayList<>();
            List<ByteOrder> orders = new ArrayList<>();
            List<Track> tracks = new ArrayList<>();
            sources.add(game);
            orders.add(ByteOrder.nativeOrder());
            tracks.add(new Track("Game", "game", Path.of(scratch + "-game.pcm")));
            List<String> taken = new ArrayList<>(List.of("mix", "game", "music"));
            for (Voice voice : VOICES.values()) {
                String title = voice.name == null || voice.name.isBlank() ? "Speaker" : voice.name.trim();
                String tag = comedymonkey.dailies.export.NamePattern.safe(title).replace(' ', '_');
                String unique = comedymonkey.dailies.export.NamePattern.unused(tag, candidate -> taken.contains(candidate.toLowerCase(Locale.ROOT)));
                taken.add(unique.toLowerCase(Locale.ROOT));
                sources.add(voice.file);
                orders.add(ByteOrder.LITTLE_ENDIAN);
                tracks.add(new Track(unique.equals(tag) ? title : title + unique.substring(tag.length()), "voice-" + unique, Path.of(scratch + "-voice" + tracks.size() + ".pcm")));
            }
            List<Cue> extra = cues(remarks);
            if (!extra.isEmpty()) {
                Path music = Path.of(scratch + "-music-in.pcm");
                temporary.add(music);
                String failure = run(musicCommand(extra, frames, music));
                if (failure == null && Files.exists(music)) {
                    sources.add(music);
                    orders.add(ByteOrder.LITTLE_ENDIAN);
                    tracks.add(new Track("Music", "music", Path.of(scratch + "-music.pcm")));
                } else {
                    // An audio keyframe's file the encoder cannot read must not cost the rest
                    Host.LOGGER.warn("Dailies: the audio keyframes could not be rendered ({}); going on without them", failure);
                    remarks.append("; the audio keyframes could not be mixed in (").append(firstLine(String.valueOf(failure))).append(')');
                    extra = List.of();
                }
            }
            Path mix = Path.of(scratch + "-mix.pcm");
            temporary.add(mix);
            tracks.forEach(track -> temporary.add(track.samples()));
            sum(sources, orders, tracks, mix, frames, channels);

            // Beside the video: asked for, or the only way when this kind of file cannot hold named tracks
            String container = settings.container.toLowerCase(Locale.ROOT);
            boolean inside = settings.stemTracks && comedymonkey.dailies.export.Codecs.holdsNamedTracks(container);
            boolean beside = settings.stemFiles || !inside;
            if (settings.stemTracks && !inside) {
                remarks.append("; ").append(container).append(" files are not given separate tracks, so they were written as WAV files instead");
            }
            List<Path> written = new ArrayList<>();
            if (beside) {
                try {
                    for (Track track : tracks) {
                        Path wav = output.resolveSibling(base + "." + track.tag() + ".wav");
                        wav(track.samples(), wav, frames, channels);
                        written.add(wav);
                    }
                } catch (IOException e) {
                    Host.LOGGER.warn("Dailies: the separate WAV files could not all be written", e);
                    remarks.append("; the separate WAV files could not all be written (").append(e.getMessage()).append(')');
                }
            }
            String failure = run(apartCommand(video, mix, output, codec, inside ? tracks : List.of(), channels));
            if (failure != null && inside) {
                Host.LOGGER.warn("Dailies: the separate tracks could not be put inside the file ({}); trying with the mixed track alone", failure);
                remarks.append("; the separate tracks could not be put inside the file (").append(firstLine(failure)).append(')');
                inside = false;
                failure = run(apartCommand(video, mix, output, codec, List.of(), channels));
            }
            if (failure != null) {
                Host.LOGGER.warn("Dailies: sound could not be joined to the video: {}", failure);
                said.append("the encoder could not join sound and picture (").append(firstLine(failure)).append(')');
                return false;
            }
            List<String> titles = new ArrayList<>(List.of("Mix"));
            tracks.forEach(track -> titles.add(track.title()));
            lastTracks = inside ? List.copyOf(titles) : List.of();
            lastFiles = List.copyOf(written);
            said.append("mixed sound, ").append(codec[0]).append(settings.stereo ? ", stereo" : ", mono").append("; separate tracks: ")
                .append(String.join(", ", titles.subList(1, titles.size())));
            said.append(inside && !written.isEmpty() ? " (inside the file and as WAV files beside it)" : inside ? " (inside the file)" : " (as WAV files beside the video)");
            if (problem != null) {
                said.append("; ").append(problem);
            }
            said.append(remarks);
            return true;
        } finally {
            for (Path file : temporary) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    Host.LOGGER.debug("Dailies: a temporary sound file could not be removed", e);
                }
            }
        }
    }

    /**
     * Writes every source out as a finished track of exactly {@code frames} sample frames, and their sum as the mix.
     * A source that is shorter is silent for the rest. The mix is the sum of the tracks as they are written, so
     * with one channel it is the sum of the mono tracks; it is only ever different where it would not fit 16 bits.
     */
    private static void sum(List<Path> sources, List<ByteOrder> orders, List<Track> tracks, Path mix, long frames, int channels) throws IOException {
        int count = sources.size();
        List<java.io.InputStream> in = new ArrayList<>();
        List<OutputStream> out = new ArrayList<>();
        try (OutputStream mixed = new BufferedOutputStream(Files.newOutputStream(mix), 1 << 16)) {
            for (int i = 0; i < count; i++) {
                in.add(new java.io.BufferedInputStream(Files.newInputStream(sources.get(i)), 1 << 16));
                out.add(new BufferedOutputStream(Files.newOutputStream(tracks.get(i).samples()), 1 << 16));
            }
            byte[] read = new byte[CHUNK_FRAMES * BYTES_PER_FRAME];
            byte[] one = new byte[CHUNK_FRAMES * 2 * channels];
            int[] total = new int[CHUNK_FRAMES * channels];
            for (long done = 0; done < frames; ) {
                int now = (int) Math.min(CHUNK_FRAMES, frames - done);
                Arrays.fill(total, 0);
                for (int i = 0; i < count; i++) {
                    int got = in.get(i).readNBytes(read, 0, now * BYTES_PER_FRAME);
                    Arrays.fill(read, got, now * BYTES_PER_FRAME, (byte) 0);
                    ByteBuffer from = ByteBuffer.wrap(read).order(orders.get(i));
                    ByteBuffer to = ByteBuffer.wrap(one).order(ByteOrder.LITTLE_ENDIAN);
                    VoiceLevels.accumulate(from, to, total, now, channels);
                    out.get(i).write(one, 0, now * 2 * channels);
                }
                ByteBuffer to = ByteBuffer.wrap(one).order(ByteOrder.LITTLE_ENDIAN);
                for (int sample = 0; sample < now * channels; sample++) {
                    to.putShort(clamp(total[sample]));
                }
                mixed.write(one, 0, now * 2 * channels);
                done += now;
            }
        } finally {
            for (java.io.InputStream stream : in) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // Only read from
                }
            }
            for (OutputStream stream : out) {
                stream.close();
            }
        }
    }

    /** Samples as a WAV file: the 44 bytes that say what they are, then the samples */
    private static void wav(Path samples, Path file, long frames, int channels) throws IOException {
        long bytes = frames * 2 * channels;
        ByteBuffer head = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        head.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt((int) (36 + bytes)).put("WAVE".getBytes(StandardCharsets.US_ASCII));
        head.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) channels).putInt(SAMPLE_RATE)
            .putInt(SAMPLE_RATE * 2 * channels).putShort((short) (2 * channels)).putShort((short) 16);
        head.put("data".getBytes(StandardCharsets.US_ASCII)).putInt((int) bytes);
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 16)) {
            out.write(head.array());
            Files.copy(samples, out);
        }
    }

    /** Renders the audio keyframes alone to stereo samples, exactly as long as the video, each starting on its own sample */
    private static List<String> musicCommand(List<Cue> extra, long frames, Path output) throws IOException {
        List<String> command = new ArrayList<>(List.of(Encoder.programPath().toString(), "-hide_banner", "-loglevel", "error", "-y"));
        for (Cue cue : extra) {
            if (cue.skipSeconds() > 0.0) {
                command.addAll(List.of("-ss", String.format(Locale.ROOT, "%.4f", cue.skipSeconds())));
            }
            command.addAll(List.of("-i", cue.file().toString()));
        }
        StringBuilder graph = new StringBuilder();
        StringBuilder inputs = new StringBuilder();
        for (int i = 0; i < extra.size(); i++) {
            Cue cue = extra.get(i);
            graph.append('[').append(i).append(":a:0]aresample=").append(SAMPLE_RATE).append(",aformat=channel_layouts=stereo,volume=")
                .append(String.format(Locale.ROOT, "%.4f", cue.volume()));
            long delay = Math.round(cue.startSeconds() * SAMPLE_RATE);
            if (delay > 0) {
                graph.append(",adelay=").append(delay).append("S:all=1");
            }
            graph.append("[cue").append(i).append("];");
            inputs.append("[cue").append(i).append(']');
        }
        graph.append(inputs);
        if (extra.size() > 1) {
            graph.append("amix=inputs=").append(extra.size()).append(":duration=longest:normalize=0,");
        }
        graph.append("apad=whole_len=").append(frames).append(",atrim=end_sample=").append(frames).append("[music]");
        command.addAll(List.of("-filter_complex", graph.toString(), "-map", "[music]", "-f", "s16le", "-ar", String.valueOf(SAMPLE_RATE), "-ac", "2", output.toString()));
        return command;
    }

    private static List<String> apartCommand(Path video, Path mix, Path output, String[] codec, List<Track> tracks, int channels) throws IOException {
        List<String> command = new ArrayList<>(List.of(Encoder.programPath().toString(), "-hide_banner", "-loglevel", "error", "-y", "-i", video.toString()));
        List<String> titles = new ArrayList<>(List.of("Mix"));
        List<Path> inputs = new ArrayList<>(List.of(mix));
        for (Track track : tracks) {
            titles.add(track.title());
            inputs.add(track.samples());
        }
        for (Path input : inputs) {
            command.addAll(List.of("-f", "s16le", "-ar", String.valueOf(SAMPLE_RATE), "-ac", String.valueOf(channels), "-i", input.toString()));
        }
        command.addAll(List.of("-map", "0:v:0"));
        for (int i = 0; i < inputs.size(); i++) {
            command.addAll(List.of("-map", (i + 1) + ":a:0"));
        }
        command.addAll(List.of("-c:v", "copy"));
        command.addAll(Arrays.asList(codec).subList(1, codec.length));
        command.addAll(List.of("-ar", String.valueOf(SAMPLE_RATE), "-ac", String.valueOf(channels)));
        for (int i = 0; i < titles.size(); i++) {
            // MKV shows the title; MOV shows the handler name, and keeps the title as the track's name
            command.addAll(List.of("-metadata:s:a:" + i, "title=" + titles.get(i), "-metadata:s:a:" + i, "handler_name=" + titles.get(i)));
            command.addAll(List.of("-disposition:a:" + i, i == 0 ? "default" : "0"));
        }
        String container = settings.container.toLowerCase(Locale.ROOT);
        if (container.equals("mp4") || container.equals("mov")) {
            command.addAll(List.of("-movflags", "+faststart"));
        }
        command.add(output.toString());
        return command;
    }

    /** Runs the encoder to its end. Null when it worked, else what it said */
    private static @Nullable String run(List<String> command) {
        try {
            Host.LOGGER.info("Dailies: joining sound and picture: {}", String.join(" ", command));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            StringBuilder log = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                        synchronized (log) {
                            if (log.length() < 4000) {
                                log.append(line).append('\n');
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // The encoder has gone
                }
            }, "Dailies-Sound-Join-Log");
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor(30, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                return "the encoder did not finish";
            }
            reader.join(2000);
            if (process.exitValue() != 0) {
                synchronized (log) {
                    return log.length() == 0 ? "the encoder stopped without saying why" : log.toString().trim();
                }
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        } catch (Throwable t) {
            return t.toString();
        }
    }

    private static String firstLine(String text) {
        int end = text.indexOf('\n');
        String line = end < 0 ? text : text.substring(0, end);
        return line.length() > 300 ? line.substring(0, 300) : line;
    }

}
