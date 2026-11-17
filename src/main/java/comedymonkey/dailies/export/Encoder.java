package comedymonkey.dailies.export;

import comedymonkey.dailies.Host;

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
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

/**
 * The video encoder shipped inside the mod (specification EXP-8): the FFmpeg libraries and their command-line front
 * end, in an LGPL build, packed into the mod's jar. Nothing has to be installed. On first use the files are unpacked
 * into the game's dailies/encoder folder; frames are then piped to the encoder as it runs beside the game.
 * <p>
 * Which codecs can be offered is asked of the build itself, never assumed.
 */
public final class Encoder {

    /** The packed encoder for this platform inside the mod's jar, or absent in a build made without one */
    private static final String PACKED = "/encoder/ffmpeg-%s.jar";
    public static final String NOTICE = "FFmpeg, LGPL build, as packaged by the JavaCPP Presets (org.bytedeco:ffmpeg)";

    private static Path program = null;
    private static Set<String> encoders = null;

    private Encoder() {
    }

    private static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String cpu = arch.contains("aarch64") || arch.contains("arm64") ? "arm64" : "x86_64";
        return (os.contains("win") ? "windows" : os.contains("mac") ? "macosx" : "linux") + "-" + cpu;
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
                throw new IOException("This build of Dailies has no video encoder for " + platform + ". Export a PNG sequence instead (container png_sequence)");
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

    /** For each codec name of the specification, the encoders to try, hardware first */
    private static final Map<String, List<String>> CANDIDATES = new LinkedHashMap<>();

    static {
        CANDIDATES.put("h264", List.of("h264_nvenc", "h264_amf", "h264_qsv", "h264_videotoolbox", "libopenh264", "h264_mf"));
        CANDIDATES.put("h265", List.of("hevc_nvenc", "hevc_amf", "hevc_qsv", "hevc_videotoolbox", "hevc_mf"));
        CANDIDATES.put("av1", List.of("av1_nvenc", "av1_amf", "av1_qsv", "libsvtav1", "libaom-av1"));
        CANDIDATES.put("vp9", List.of("libvpx-vp9", "vp9_qsv"));
    }

    /** What can be exported with this build on this machine's encoder list: codec name to the encoders it has for it */
    public static Map<String, List<String>> offered() throws IOException {
        Set<String> present = encoders();
        Map<String, List<String>> offered = new LinkedHashMap<>();
        CANDIDATES.forEach((codec, names) -> {
            List<String> have = names.stream().filter(present::contains).toList();
            if (!have.isEmpty()) {
                offered.put(codec, have);
            }
        });
        return offered;
    }

    static FrameSink videoSink(Export.Settings settings, int scale) throws IOException {
        return videoSink(settings, scale, settings.output);
    }

    /** As above, written to another file than the export's own: the soundless video that sound is joined to afterwards */
    static FrameSink videoSink(Export.Settings settings, int scale, Path output) throws IOException {
        Map<String, List<String>> offered = offered();
        List<String> names = offered.get(settings.videoCodec.toLowerCase(Locale.ROOT));
        if (names == null) {
            throw new IllegalArgumentException("This build cannot encode " + settings.videoCodec + ". It can encode: " + String.join(", ", offered.keySet()));
        }
        List<String> order = new ArrayList<>(names);
        if (settings.encoder != null && !settings.encoder.isBlank()) {
            if (!names.contains(settings.encoder)) {
                throw new IllegalArgumentException("There is no encoder '" + settings.encoder + "' for " + settings.videoCodec + ". There are: " + String.join(", ", names));
            }
            order = List.of(settings.encoder);
        }
        IOException last = null;
        for (String name : order) {
            try {
                // A hardware encoder may be in the build and still not work on this machine: try, and fall back
                return new VideoSink(settings, scale, name, output);
            } catch (IOException e) {
                last = e;
                Host.LOGGER.info("Dailies: encoder {} is not usable here ({}), trying the next", name, e.getMessage());
            }
        }
        throw new IOException("None of the encoders for " + settings.videoCodec + " works on this machine", last);
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
            command.addAll(List.of("-c:v", encoder, "-pix_fmt", settings.transparent && encoder.startsWith("libvpx") ? "yuva420p" : "yuv420p"));
            if (settings.bitrate > 0) {
                command.addAll(List.of("-b:v", String.valueOf(settings.bitrate)));
            }
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
