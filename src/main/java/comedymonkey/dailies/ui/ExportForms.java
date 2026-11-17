package comedymonkey.dailies.ui;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.edit.Scene;
import comedymonkey.dailies.edit.TimeMap;
import comedymonkey.dailies.export.Encoder;
import comedymonkey.dailies.export.Export;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.Util;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Builds the "Export" tab of the editor's side panel (specification chapter VI): the size and frame rate of the
 * video, the file format with only the codecs this build of the encoder really has, sound, the options of EXP-4, the
 * name of the file, and the buttons that start an export or write a still picture. What is chosen here is kept for
 * as long as the game runs. Asking the encoder what it can do runs a program, so that is done once, away from the
 * client thread.
 */
final class ExportForms {

    private static final String PICTURES = "png_sequence";
    private static final List<String> SIZES = List.of("720p", "1080p", "1440p", "4k", "vertical", "custom");
    private static final int[][] SIZE_PIXELS = {{1280, 720}, {1920, 1080}, {2560, 1440}, {3840, 2160}, {1080, 1920}};
    private static final List<String> RATES = List.of("24", "25", "30", "50", "60", "120", "custom");
    private static final List<String> SOUND_CODECS = List.of("auto", "aac", "opus", "pcm");
    /** The codecs each kind of video file can hold */
    private static final Map<String, List<String>> HOLDS = new LinkedHashMap<>();

    static {
        HOLDS.put("mp4", List.of("h264", "h265", "av1", "vp9"));
        HOLDS.put("mkv", List.of("h264", "h265", "av1", "vp9"));
        HOLDS.put("mov", List.of("h264", "h265"));
        HOLDS.put("webm", List.of("vp9", "av1"));
    }

    /** What the encoder offers: codec name to encoder names. Null until it has been asked */
    private static volatile @Nullable Map<String, List<String>> offered = null;
    private static volatile boolean asking = false;

    private ExportForms() {
    }

    /** Asks the encoder, once, what it can encode */
    private static void ask() {
        if (offered != null || asking) {
            return;
        }
        asking = true;
        CompletableFuture.runAsync(() -> {
            Map<String, List<String>> found = new LinkedHashMap<>();
            try {
                if (Encoder.bundled()) {
                    found.putAll(Encoder.offered());
                }
            } catch (Exception | LinkageError e) {
                Host.LOGGER.warn("Dailies: the encoder could not be asked what it offers, so only pictures can be exported", e);
            }
            offered = found;
            asking = false;
        }, Util.ioPool());
    }

    private static List<String> containers() {
        List<String> usable = new ArrayList<>();
        Map<String, List<String>> have = offered;
        if (have != null) {
            for (Map.Entry<String, List<String>> entry : HOLDS.entrySet()) {
                if (entry.getValue().stream().anyMatch(have::containsKey)) {
                    usable.add(entry.getKey());
                }
            }
        }
        usable.add(PICTURES);
        return usable;
    }

    private static List<String> codecs() {
        List<String> usable = new ArrayList<>();
        Map<String, List<String>> have = offered;
        if (have != null) {
            for (String codec : HOLDS.getOrDefault(EditorState.exportContainer, List.of())) {
                if (have.containsKey(codec)) {
                    usable.add(codec);
                }
            }
        }
        return usable;
    }

    private static List<String> encoders() {
        List<String> usable = new ArrayList<>();
        usable.add("");
        Map<String, List<String>> have = offered;
        if (have != null) {
            usable.addAll(have.getOrDefault(EditorState.exportCodec, List.of()));
        }
        return usable;
    }

    /** Brings the choices into line with what can be had: a container that exists, a codec it holds, an encoder for that codec */
    private static void settle() {
        if (offered == null) {
            return;
        }
        if (!containers().contains(EditorState.exportContainer)) {
            EditorState.exportContainer = containers().get(0);
        }
        List<String> codecs = codecs();
        if (!codecs.isEmpty() && !codecs.contains(EditorState.exportCodec)) {
            EditorState.exportCodec = codecs.get(0);
        }
        if (!encoders().contains(EditorState.exportEncoder)) {
            EditorState.exportEncoder = "";
        }
    }

    private static boolean pictures() {
        return EditorState.exportContainer.equals(PICTURES);
    }

    private static String name() {
        if (EditorState.exportName == null) {
            EditorState.exportName = Host.get().setting("defaultFileName", "{date}_{time}");
        }
        return EditorState.exportName;
    }

    /** A word for what the tab should hold now; when it changes the tab is built afresh */
    static String shape() {
        ask();
        return (offered == null ? "asking" : "known") + "|" + pictures();
    }

    static void build(Form form) {
        form.clear();
        settle();
        form.add(new Gadget.Heading(Kit.t("export.heading.picture")));
        form.add(new Gadget.Pair(Kit.t("export.size"), new Gadget.Cycle<>(SIZES, id -> Kit.t("export.size." + id), ExportForms::sizeName, id -> {
            int index = SIZES.indexOf(id);
            if (index >= 0 && index < SIZE_PIXELS.length) {
                EditorState.exportWidth = SIZE_PIXELS[index][0];
                EditorState.exportHeight = SIZE_PIXELS[index][1];
            }
        })).tip(Kit.t("export.size.tip")));
        form.add(new Gadget.Pair(Kit.t("export.width"), Entry.number(() -> (double) EditorState.exportWidth, 16, 16384, true, value -> {
            EditorState.exportWidth = (int) Math.rint(value);
            return null;
        })).tip(Kit.t("export.width.tip")));
        form.add(new Gadget.Pair(Kit.t("export.height"), Entry.number(() -> (double) EditorState.exportHeight, 16, 16384, true, value -> {
            EditorState.exportHeight = (int) Math.rint(value);
            return null;
        })).tip(Kit.t("export.height.tip")));
        form.add(new Gadget.Pair(Kit.t("export.rate"), new Gadget.Cycle<>(RATES, id -> id.equals("custom") ? Kit.t("export.rate.custom") : Kit.t("export.rate.value", id),
                ExportForms::rateName, id -> {
                    if (!id.equals("custom")) {
                        EditorState.exportRate = Double.parseDouble(id);
                    }
                })).tip(Kit.t("export.rate.tip")));
        form.add(new Gadget.Pair(Kit.t("export.rate_free"), Entry.number(() -> EditorState.exportRate, 1, 240, false, value -> {
            EditorState.exportRate = value;
            return null;
        })).tip(Kit.t("export.rate_free.tip")));
        form.add(new Gadget.Check(Kit.t("export.supersample"), () -> EditorState.exportSupersample, on -> EditorState.exportSupersample = on)
                .tip(Kit.t("export.supersample.tip")));
        form.add(new Gadget.Check(Kit.t("export.hide_hud"), () -> EditorState.exportHideHud, on -> EditorState.exportHideHud = on)
                .tip(Kit.t("export.hide_hud.tip")));
        form.add(new Gadget.Check(Kit.t("export.transparent"), () -> EditorState.exportTransparent, on -> EditorState.exportTransparent = on)
                .tip(Kit.t("export.transparent.tip")));

        form.add(new Gadget.Heading(Kit.t("export.heading.file")));
        if (offered == null) {
            form.add(new Gadget.Note(Kit.t("export.asking")));
        } else if (offered.isEmpty()) {
            form.add(new Gadget.Note(Kit.t("export.no_encoder")));
        }
        form.add(new Gadget.Pair(Kit.t("export.container"), new Gadget.Cycle<>(ExportForms::containers, id -> Kit.t("export.container." + id),
                () -> EditorState.exportContainer, id -> {
                    EditorState.exportContainer = id;
                    settle();
                })).tip(Kit.t("export.container.tip")));
        if (!pictures()) {
            form.add(new Gadget.Pair(Kit.t("export.codec"), new Gadget.Cycle<>(ExportForms::codecs, id -> Kit.t("export.codec." + id),
                    () -> EditorState.exportCodec, id -> {
                        EditorState.exportCodec = id;
                        settle();
                    })).tip(Kit.t("export.codec.tip")));
            form.add(new Gadget.Pair(Kit.t("export.encoder"), new Gadget.Cycle<>(ExportForms::encoders,
                    id -> id.isEmpty() ? Kit.t("export.encoder.auto") : Component.literal(id),
                    () -> EditorState.exportEncoder, id -> EditorState.exportEncoder = id)).tip(Kit.t("export.encoder.tip")));
            form.add(new Gadget.Pair(Kit.t("export.bitrate"), new Entry(() -> EditorState.exportBitrate, typed -> {
                if (bitrate(typed) < 0) {
                    return Kit.t("export.bitrate.wrong");
                }
                EditorState.exportBitrate = typed.trim().toLowerCase(Locale.ROOT);
                return null;
            })).tip(Kit.t("export.bitrate.tip")));

            form.add(new Gadget.Heading(Kit.t("export.heading.sound")));
            form.add(new Gadget.Check(Kit.t("export.sound"), () -> EditorState.exportSound, on -> EditorState.exportSound = on).tip(Kit.t("export.sound.tip")));
            form.add(new Gadget.Check(Kit.t("export.stereo"), () -> EditorState.exportStereo, on -> EditorState.exportStereo = on)
                    .when(() -> EditorState.exportSound).tip(Kit.t("export.stereo.tip")));
            form.add(new Gadget.Pair(Kit.t("export.sound_codec"), new Gadget.Cycle<>(SOUND_CODECS, id -> Kit.t("export.sound_codec." + id),
                    () -> EditorState.exportSoundCodec, id -> EditorState.exportSoundCodec = id)).when(() -> EditorState.exportSound).tip(Kit.t("export.sound_codec.tip")));
        }

        form.add(new Gadget.Heading(Kit.t("export.heading.output")));
        form.add(new Gadget.Pair(Kit.t("export.name"), new Entry(ExportForms::name, typed -> {
            if (typed.isBlank()) {
                return Kit.t("export.name.empty");
            }
            EditorState.exportName = typed.trim();
            return null;
        })).tip(Kit.t("export.name.tip")));
        form.add(new Gadget.Note(() -> Kit.t("export.where", fileName(false)), Controls.PARCHMENT_DIM));
        form.add(new Gadget.Note(ExportForms::rangeWords, Controls.PARCHMENT_DIM));
        form.add(new Gadget.Push(Kit.t("export.start"), () -> start(false)).when(() -> !Export.active()).tip(Kit.t("export.start.tip")));
        form.add(new Gadget.Push(Kit.t("export.still"), () -> start(true)).when(() -> !Export.active()).tip(Kit.t("export.still.tip")));
        form.add(new Gadget.Note(ExportForms::resultWords, Controls.PARCHMENT).action(ExportForms::openFolder).tip(Kit.t("export.result.tip")));
        form.add(new Gadget.Note(() -> {
            String sound = Export.soundNote();
            return sound == null ? Component.empty() : Component.literal(sound);
        }, Controls.PARCHMENT_DIM));
    }

    private static String sizeName() {
        for (int i = 0; i < SIZE_PIXELS.length; i++) {
            if (SIZE_PIXELS[i][0] == EditorState.exportWidth && SIZE_PIXELS[i][1] == EditorState.exportHeight) {
                return SIZES.get(i);
            }
        }
        return "custom";
    }

    private static String rateName() {
        for (String rate : RATES) {
            if (!rate.equals("custom") && Math.abs(Double.parseDouble(rate) - EditorState.exportRate) < 1.0e-9) {
                return rate;
            }
        }
        return "custom";
    }

    /** Bits per second from text like "20m", "800k" or "5000000"; -1 when it cannot be read */
    private static long bitrate(String typed) {
        String text = typed.trim().toLowerCase(Locale.ROOT);
        try {
            double scale = text.endsWith("m") ? 1_000_000 : text.endsWith("k") ? 1_000 : 1;
            double number = Double.parseDouble(scale == 1 ? text : text.substring(0, text.length() - 1).trim());
            if (Double.isNaN(number) || number <= 0 || number * scale > 2.0e9) {
                return -1;
            }
            return (long) (number * scale);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The name of the file an export started now would write, with the date and time filled in */
    private static String fileName(boolean still) {
        LocalDateTime now = LocalDateTime.now();
        String filled = name().replace("{date}", now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))).replace("{time}", now.format(DateTimeFormatter.ofPattern("HH.mm.ss")));
        // Only a name: nothing that would lead out of the exports folder or that a file system refuses
        filled = filled.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (filled.endsWith(".")) {
            filled = filled.substring(0, filled.length() - 1);
        }
        if (filled.isBlank()) {
            filled = "export";
        }
        if (still) {
            return filled + ".png";
        }
        return pictures() ? filled : filled + "." + EditorState.exportContainer;
    }

    private static Path folder() {
        return Host.get().dataDirectory().resolve("exports");
    }

    /** The ticks an export started now would cover, worked out as the export itself does, and how long the video would be */
    private static Component rangeWords() {
        Scene scene = EditorState.scene();
        int total = Playback.total();
        int start = scene.hasExportRange() ? scene.exportStart() : scene.firstKeyframeTick() >= 0 ? scene.firstKeyframeTick() : 0;
        int end = scene.hasExportRange() ? scene.exportEnd() : scene.lastKeyframeTick() > start ? scene.lastKeyframeTick() : total;
        start = Math.max(0, Math.min(total, start));
        end = Math.max(0, Math.min(total, end));
        if (end <= start) {
            return Kit.t("export.range.empty", start, end);
        }
        String length;
        try {
            length = Kit.seconds(rangeSeconds(scene, start, end));
        } catch (RuntimeException e) {
            length = Kit.seconds((end - start) / 20.0);
        }
        return Kit.t(scene.hasExportRange() ? "export.range.set" : "export.range.default", start, end, length);
    }

    private static long lengthFor = Long.MIN_VALUE;
    private static double lengthSeconds = 0.0;

    private static double rangeSeconds(Scene scene, int start, int end) {
        long stamp = EditorState.history().revision() * 1_000_003L + start * 31L + end;
        if (stamp != lengthFor) {
            lengthFor = stamp;
            lengthSeconds = new TimeMap(scene, start, end).totalRealSeconds();
        }
        return lengthSeconds;
    }

    private static Component resultWords() {
        if (Export.active()) {
            return Kit.t("export.result.running");
        }
        String error = Export.lastError();
        if (error != null) {
            return Kit.t("export.result.failed", error);
        }
        Path written = Export.lastOutput();
        if (written != null) {
            return Kit.t(Export.lastCancelled() ? "export.result.cancelled" : "export.result.done", written.getFileName().toString());
        }
        return Component.empty();
    }

    private static void openFolder() {
        Path written = Export.lastOutput();
        Path target = written != null && written.toAbsolutePath().getParent() != null ? written.toAbsolutePath().getParent() : folder();
        Controls.offThread(() -> {
            Files.createDirectories(target);
            Util.getPlatform().openPath(target);
        }, Kit.t("export.folder_failed"), () -> {
        });
    }

    // ------------------------------------------------------------------ Starting

    /**
     * Starts an export with what the tab holds. The exports folder is made, and a name that is not yet taken found,
     * away from the client thread; the export itself begins back on it.
     */
    static void start(boolean still) {
        if (!Playback.loaded() || Playback.jumping()) {
            EditorState.say(Kit.t("export.not_now"));
            return;
        }
        if (Export.active()) {
            EditorState.say(Kit.t("export.result.running"));
            return;
        }
        boolean pictures = still || pictures();
        Export.Settings settings = new Export.Settings();
        settings.width = EditorState.exportWidth;
        settings.height = EditorState.exportHeight;
        if (!pictures) {
            // Video formats want even sizes
            settings.width += settings.width & 1;
            settings.height += settings.height & 1;
            if (codecs().isEmpty()) {
                EditorState.say(Kit.t("export.no_encoder"));
                return;
            }
            long bits = bitrate(EditorState.exportBitrate);
            settings.bitrate = bits > 0 ? bits : 20_000_000L;
            settings.videoCodec = EditorState.exportCodec;
            settings.encoder = EditorState.exportEncoder.isEmpty() ? null : EditorState.exportEncoder;
            settings.audio = EditorState.exportSound;
            settings.stereo = EditorState.exportStereo;
            settings.audioCodec = EditorState.exportSoundCodec;
        }
        settings.container = still ? PICTURES : EditorState.exportContainer;
        settings.framesPerSecond = EditorState.exportRate;
        settings.hideGui = EditorState.exportHideHud;
        settings.supersample = EditorState.exportSupersample;
        settings.transparent = EditorState.exportTransparent;
        settings.still = still;

        Path folder = folder();
        String wanted = fileName(still);
        String recording = Playback.recordingId();
        Path[] free = new Path[1];
        Controls.offThread(() -> {
            Files.createDirectories(folder);
            free[0] = unused(folder, wanted);
        }, Kit.t("export.folder_failed"), () -> {
            if (free[0] == null || !Playback.loaded() || !java.util.Objects.equals(recording, Playback.recordingId())) {
                return;
            }
            settings.output = free[0];
            try {
                Export.start(settings);
                if (still) {
                    EditorState.say(Kit.t("export.still.started", free[0].getFileName().toString()));
                }
            } catch (IllegalStateException | IllegalArgumentException refused) {
                EditorState.say(Component.literal(String.valueOf(refused.getMessage())));
            } catch (Exception | LinkageError e) {
                Host.LOGGER.error("Dailies: the export could not start", e);
                EditorState.say(Kit.t("export.result.failed", String.valueOf(e.getMessage())));
            }
        });
    }

    /** The name, or the name with a number after it, that nothing in the folder has yet. Not the client thread */
    private static Path unused(Path folder, String name) {
        Path first = folder.resolve(name);
        if (!Files.exists(first)) {
            return first;
        }
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ending = dot > 0 ? name.substring(dot) : "";
        for (int number = 2; number < 10000; number++) {
            Path next = folder.resolve(stem + " (" + number + ")" + ending);
            if (!Files.exists(next)) {
                return next;
            }
        }
        return folder.resolve(stem + " (" + System.currentTimeMillis() + ")" + ending);
    }

    /** How the last export ended, in words, for the notice shown when the editor comes back */
    static Component ended() {
        Component words = resultWords();
        return words.getString().isEmpty() ? Kit.t("export.result.none") : words;
    }

}
