package comedymonkey.dailies.export;

import comedymonkey.dailies.Host;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

/**
 * The video encoder shipped inside the mod (specification EXP-8): the FFmpeg libraries and their command-line front
 * end, in an LGPL build, packed into the mod's jar. Nothing has to be installed. On first use the files are unpacked
 * into the game's dailies/encoder folder; frames are then piped to the encoder as it runs beside the game.
 * <p>
 * Which codecs can be offered is asked of the build itself and tried on this machine, never assumed (EXP-1): see
 * {@link #choices}. What may be used at all, and what stands in for what, is in {@link Codecs}.
 */
public final class Encoder {

    /** The packed encoder for this platform inside the mod's jar, or absent in a build made without one */
    private static final String PACKED = "/encoder/ffmpeg-%s.jar";
    public static final String NOTICE = "FFmpeg, LGPL build, as packaged by the JavaCPP Presets (org.bytedeco:ffmpeg). Licences: THIRD-PARTY-NOTICES.md and the licenses folder inside the Dailies jar";

    private static Path program = null;
    private static Set<String> encoders = null;

    private Encoder() {
    }

    /** Where a video encoder is packed into Dailies today. Other platforms export numbered pictures (EXP-8) */
    public static final String SUPPORTED = "Windows on 64-bit Intel or AMD processors";

    /** The platform the game runs on, as the packed encoders are named: windows-x86_64, linux-arm64 and so on */
    public static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String cpu = arch.contains("aarch64") || arch.contains("arm64") ? "arm64" : "x86_64";
        return (os.contains("win") ? "windows" : os.contains("mac") ? "macosx" : "linux") + "-" + cpu;
    }

    /** What to tell someone whose platform has no encoder packed in, rather than failing obscurely */
    public static String noEncoderWords() {
        return "Dailies carries a video encoder for " + SUPPORTED + " only for now, and this is " + platform()
            + ". Numbered PNG pictures can be exported here instead (container png_sequence)";
    }

    public static boolean bundled() {
        return Encoder.class.getResource(String.format(PACKED, platform())) != null;
    }

    /** Unpacks the encoder on first use and returns its program */
    static synchronized Path program() throws IOException {
        if (program != null) {
            return program;
        }
        String platform = platform();
        String resource = String.format(PACKED, platform);
        try (InputStream packed = Encoder.class.getResourceAsStream(resource)) {
            if (packed == null) {
                throw new IOException(noEncoderWords());
            }
            Path folder = Host.get().dataDirectory().resolve("encoder").resolve(platform);
            Files.createDirectories(folder);
            Path found = null;
            try (JarInputStream jar = new JarInputStream(packed)) {
                for (JarEntry entry = jar.getNextJarEntry(); entry != null; entry = jar.getNextJarEntry()) {
                    String name = entry.getName();
                    if (entry.isDirectory() || !name.contains("/" + platform + "/")) {
                        continue;
                    }
                    Path file = folder.resolve(name.substring(name.lastIndexOf('/') + 1));
                    if (!Files.exists(file) || Files.size(file) != entry.getSize()) {
                        Files.copy(jar, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    String base = file.getFileName().toString();
                    if (base.equals("ffmpeg.exe") || base.equals("ffmpeg")) {
                        found = file;
                        file.toFile().setExecutable(true);
                    }
                }
            }
            if (found == null) {
                throw new IOException("The video encoder packed in this build of Dailies is incomplete");
            }
            program = found;
            return found;
        }
    }

    public static Path programPath() throws IOException {
        return program();
    }

    /** The program that reads a video file and says what is in it, unpacked beside the encoder */
    public static Path inspector() throws IOException {
        Path encoder = program();
        Path probe = encoder.resolveSibling(encoder.getFileName().toString().replace("ffmpeg", "ffprobe"));
        if (!Files.exists(probe)) {
            throw new IOException("The packed encoder has no inspector");
        }
        return probe;
    }

    /** The names of the encoders this build really contains */
    public static synchronized Set<String> encoders() throws IOException {
        if (encoders != null) {
            return encoders;
        }
        Process process = new ProcessBuilder(program().toString(), "-hide_banner", "-encoders").redirectErrorStream(true).start();
        Set<String> found = new TreeSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2 && parts[0].length() == 6 && (parts[0].startsWith("V") || parts[0].startsWith("A"))) {
                    found.add(parts[1]);
                }
            }
        }
        encoders = found;
        return found;
    }

    // ------------------------------------------------------------------ What this machine can really do (EXP-1)

    /**
     * A switch for trying out what a machine without any hardware encoder gets: with the system property set to
     * true, no hardware encoder is offered or used. The scripted test uses it to walk the fallbacks of EXP-14
     */
    public static final String SOFTWARE_ONLY = "dailies.encoder.softwareOnly";

    /** What the game draws with, as it names itself: the graphics card and its driver. Trials are remembered against it */
    private static volatile String machine = "unknown graphics";
    private static final Map<String, List<Codecs.Choice>> TRIED = new ConcurrentHashMap<>();

    /** Told once by the client thread, which is the only one that may ask the graphics driver its name */
    public static void machine(String graphicsAndDriver) {
        if (graphicsAndDriver != null && !graphicsAndDriver.isBlank()) {
            machine = graphicsAndDriver;
        }
    }

    /**
     * The codec choices this machine really has, each codec's fastest first: every software encoder of the build that
     * Dailies uses, and every hardware encoder that has just encoded a few frames here. An encoder being in the
     * build says nothing about the graphics card, so each hardware one is tried once and the answer kept for the
     * session, per graphics card and driver. Takes a few seconds the first time; not for the client thread where it
     * can be helped.
     */
    public static List<Codecs.Choice> choices() throws IOException {
        boolean softwareOnly = Boolean.getBoolean(SOFTWARE_ONLY);
        String key = softwareOnly ? machine + ", hardware encoders left out" : machine;
        List<Codecs.Choice> known = TRIED.get(key);
        if (known != null) {
            return known;
        }
        synchronized (TRIED) {
            known = TRIED.get(key);
            if (known != null) {
                return known;
            }
            Set<String> inBuild = encoders();
            String ffmpeg = program().toString();
            Map<String, Boolean> passed = new ConcurrentHashMap<>();
            List<Thread> trials = new ArrayList<>();
            for (Codecs.Choice candidate : Codecs.candidates(inBuild)) {
                if (!candidate.hardware() || softwareOnly) {
                    continue;
                }
                // Side by side: an encoder for a card that is not there can take two seconds to say so
                Thread trial = new Thread(() -> passed.put(candidate.encoder(), trial(ffmpeg, candidate.encoder())), "Dailies-Encoder-Trial-" + candidate.encoder());
                trial.setDaemon(true);
                trial.start();
                trials.add(trial);
            }
            for (Thread trial : trials) {
                try {
                    trial.join(30_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            known = List.copyOf(Codecs.working(inBuild, encoder -> passed.getOrDefault(encoder, false)));
            TRIED.put(key, known);
            Host.LOGGER.info("Dailies: on {} the encoder offers {}", key, known.stream().map(choice -> choice.encoder() + " (" + choice.where() + ")").toList());
            return known;
        }
    }

    /** The choices if they have been worked out already, else null. Never waits */
    public static @Nullable List<Codecs.Choice> choicesIfKnown() {
        return TRIED.get(Boolean.getBoolean(SOFTWARE_ONLY) ? machine + ", hardware encoders left out" : machine);
    }

    private static boolean trial(String ffmpeg, String encoder) {
        try {
            Process process = new ProcessBuilder(Codecs.trialArguments(ffmpeg, encoder)).redirectErrorStream(true).start();
            // Read to the end so a talkative failure cannot fill the pipe and hang
            Thread drain = new Thread(() -> {
                try {
                    process.getInputStream().readAllBytes();
                } catch (IOException ignored) {
                    // The encoder has gone
                }
            }, "Dailies-Encoder-Trial-Log");
            drain.setDaemon(true);
            drain.start();
            if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** What can be exported on this machine: codec name to the encoders that work for it, fastest first */
    public static Map<String, List<String>> offered() throws IOException {
        Map<String, List<String>> offered = new LinkedHashMap<>();
        for (Codecs.Choice choice : choices()) {
            offered.computeIfAbsent(choice.codec(), codec -> new ArrayList<>()).add(choice.encoder());
        }
        return offered;
    }

    static FrameSink videoSink(Export.Settings settings, int scale) throws IOException {
        return videoSink(settings, scale, settings.output);
    }

    /** As above, written to another file than the export's own: the soundless video that sound is joined to afterwards */
    static FrameSink videoSink(Export.Settings settings, int scale, Path output) throws IOException {
        if (!bundled()) {
            throw new IOException(noEncoderWords());
        }
        List<Codecs.Choice> working = choices();
        String codec = settings.videoCodec.toLowerCase(Locale.ROOT);
        List<Codecs.Choice> forCodec = Codecs.forCodec(working, codec);
        List<String> can = working.stream().map(Codecs.Choice::codec).distinct().toList();
        if (settings.encoder != null && settings.encoder.trim().equals(Codecs.FORBIDDEN)) {
            // Said before anything else, so the answer is the same on a machine with no H.264 at all
            throw new IllegalArgumentException("Dailies does not use " + Codecs.FORBIDDEN + ": H.264 is only made by hardware encoders. "
                + (forCodec.isEmpty() ? "This computer has none; it can encode: " + String.join(", ", can) : "There are: " + String.join(", ", forCodec.stream().map(Codecs.Choice::encoder).toList())));
        }
        if (forCodec.isEmpty()) {
            String why = Codecs.known(codec) ? Codecs.whyMissing(working, codec) : "There is no codec '" + settings.videoCodec + "'";
            throw new IllegalArgumentException(why + ". This computer can encode: " + String.join(", ", can));
        }
        String container = settings.container.toLowerCase(Locale.ROOT);
        if (!Codecs.holds(container, codec)) {
            throw new IllegalArgumentException("A " + container + " file cannot hold " + Codecs.codecLabel(codec) + ". It holds: " + String.join(", ", Codecs.heldBy(container))
                + ". " + Codecs.codecLabel(codec) + " goes in " + Codecs.containerFor(codec, container));
        }
        List<Codecs.Choice> order = forCodec;
        if (settings.encoder != null && !settings.encoder.isBlank()) {
            String asked = settings.encoder.trim();
            order = forCodec.stream().filter(choice -> choice.encoder().equals(asked)).toList();
            if (order.isEmpty()) {
                throw new IllegalArgumentException("There is no working encoder '" + asked + "' for " + settings.videoCodec + " on this computer. There are: "
                    + String.join(", ", forCodec.stream().map(Codecs.Choice::encoder).toList()));
            }
        }
        IOException last = null;
        for (Codecs.Choice choice : order) {
            try {
                // Tried already, but a card can still refuse the size or rate of this export: go on to the next
                return new VideoSink(settings, scale, choice.encoder(), output);
            } catch (IOException e) {
                last = e;
                Host.LOGGER.info("Dailies: encoder {} is not usable for this export ({}), trying the next", choice.encoder(), e.getMessage());
            }
        }
        throw new IOException("None of the encoders for " + settings.videoCodec + " could encode this export: " + (last == null ? "" : last.getMessage()), last);
    }

    private static final class VideoSink implements FrameSink {

        private final Process process;
        private final OutputStream input;
        private final Path output;
        private final StringBuilder log = new StringBuilder();
        private final int width, height;

        VideoSink(Export.Settings settings, int scale, String encoder, Path output) throws IOException {
            this.output = output;
            this.width = settings.width * scale;
            this.height = settings.height * scale;
            Files.createDirectories(output.toAbsolutePath().getParent());
            List<String> command = new ArrayList<>(List.of(program().toString(), "-hide_banner", "-loglevel", "error", "-y",
                "-f", "rawvideo", "-pix_fmt", "rgba", "-s", this.width + "x" + this.height, "-r", String.valueOf(settings.framesPerSecond), "-i", "pipe:0"));
            // Frames arrive bottom row first; supersampled frames are brought down to size here
            command.addAll(List.of("-vf", scale > 1 ? "vflip,scale=" + settings.width + ":" + settings.height + ":flags=lanczos" : "vflip"));
            command.addAll(Codecs.videoArguments(encoder, settings.bitrate, settings.transparent));
            command.add("-an");
            if (settings.container.equals("mp4") || settings.container.equals("mov")) {
                // A file cut short by a cancel must still play (EXP-6)
                command.addAll(List.of("-movflags", "+faststart"));
            }
            command.add(output.toAbsolutePath().toString());
            this.process = new ProcessBuilder(command).redirectErrorStream(true).start();
            this.input = this.process.getOutputStream();
            Thread reader = new Thread(() -> {
                try (BufferedReader lines = new BufferedReader(new InputStreamReader(this.process.getInputStream(), StandardCharsets.UTF_8))) {
                    for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                        synchronized (this.log) {
                            if (this.log.length() < 4000) {
                                this.log.append(line).append('\n');
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // The encoder has gone
                }
            }, "Dailies-Encoder-Log");
            reader.setDaemon(true);
            reader.start();
            // An encoder that cannot start says so at once
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (!this.process.isAlive()) {
                throw new IOException(this.said());
            }
        }

        private String said() {
            synchronized (this.log) {
                return this.log.length() == 0 ? "the encoder stopped without saying why" : this.log.toString().trim();
            }
        }

        @Override
        public void frame(long number, byte[] rgba, int frameWidth, int frameHeight) throws IOException {
            if (frameWidth != this.width || frameHeight != this.height) {
                throw new IOException("A frame of " + frameWidth + "x" + frameHeight + " arrived in an export of " + this.width + "x" + this.height);
            }
            try {
                this.input.write(rgba, 0, frameWidth * frameHeight * 4);
            } catch (IOException e) {
                throw new IOException("The encoder stopped: " + this.said(), e);
            }
        }

        @Override
        public Path finish(boolean cancelled) throws IOException {
            this.input.close();
            try {
                if (!this.process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
                    this.process.destroyForcibly();
                    throw new IOException("The encoder did not finish the file");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (this.process.exitValue() != 0) {
                throw new IOException("The encoder failed: " + this.said());
            }
            return this.output;
        }

        @Override
        public void abort() {
            this.process.destroyForcibly();
        }
    }

}
