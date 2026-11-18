package comedymonkey.dailies.export;

import comedymonkey.dailies.Host;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.zip.CRC32;

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
        if (Encoder.class.getResource(resource) == null) {
            throw new IOException(noEncoderWords());
        }
        Path folder = Host.get().dataDirectory().resolve("encoder").resolve(platform);
        program = unpack(() -> {
            InputStream packed = Encoder.class.getResourceAsStream(resource);
            if (packed == null) {
                throw new IOException(noEncoderWords());
            }
            return packed;
        }, folder, platform, Host.get().modVersion());
        return program;
    }

    /** Where the packed encoder is read from, as often as it is needed */
    interface Packed {
        InputStream open() throws IOException;
    }

    /** The file beside the unpacked encoder that says which packed encoder it was unpacked from */
    static final String STAMP = ".stamp";
    /** The ending of a file being unpacked, until it is complete and moved into place */
    static final String UNPACKING = ".dailies-unpacking";

    /**
     * Unpacks the encoder into its folder, unless the files there were unpacked from this very packed encoder, as the
     * stamp beside them says: then nothing is written at all, so an encoder that another game (the background export,
     * say) is running is never touched. Otherwise every file is written under a temporary name and moved into place
     * whole, a file already there with the same bytes is left as it is, and the stamp is written last, so an unpacking
     * cut short is never trusted. A lock on a file in the folder keeps two games from unpacking at once.
     *
     * @param version the mod's version, part of the stamp
     * @return the encoder's program
     */
    static Path unpack(Packed packed, Path folder, String platform, String version) throws IOException {
        Files.createDirectories(folder);
        String stamp = stamp(packed, version);
        Path programFile = folder.resolve(platform.startsWith("windows") ? "ffmpeg.exe" : "ffmpeg");
        try (FileChannel channel = FileChannel.open(folder.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            Path stampFile = folder.resolve(STAMP);
            String stored = null;
            try {
                stored = Files.isRegularFile(stampFile) ? Files.readString(stampFile, StandardCharsets.UTF_8) : null;
            } catch (IOException unreadable) {
                // Unpacked again
            }
            if (upToDate(stored, stamp, Files.isRegularFile(programFile))) {
                return programFile;
            }
            Files.deleteIfExists(stampFile);
            sweepUnpacking(folder);
            Path found = null;
            try (JarInputStream jar = new JarInputStream(packed.open())) {
                for (JarEntry entry = jar.getNextJarEntry(); entry != null; entry = jar.getNextJarEntry()) {
                    String name = entry.getName();
                    if (entry.isDirectory() || !name.contains("/" + platform + "/")) {
                        continue;
                    }
                    String base = name.substring(name.lastIndexOf('/') + 1);
                    if (base.isEmpty() || base.equals(".") || base.equals("..")) {
                        continue;
                    }
                    Path file = folder.resolve(base);
                    Path temporary = folder.resolve(base + "." + UUID.randomUUID() + UNPACKING);
                    try {
                        Files.copy(jar, temporary);
                        place(temporary, file);
                    } finally {
                        Files.deleteIfExists(temporary);
                    }
                    if (base.equals("ffmpeg.exe") || base.equals("ffmpeg")) {
                        found = file;
                        file.toFile().setExecutable(true);
                    } else if (base.equals("ffprobe.exe") || base.equals("ffprobe")) {
                        // A jar keeps no execute bit, and on Linux and macOS the inspector needs one as much as the encoder
                        file.toFile().setExecutable(true);
                    }
                }
            }
            if (found == null) {
                throw new IOException("The video encoder packed in this build of Dailies is incomplete");
            }
            Path newStamp = folder.resolve(STAMP + "." + UUID.randomUUID() + UNPACKING);
            try {
                Files.writeString(newStamp, stamp, StandardCharsets.UTF_8);
                move(newStamp, stampFile);
            } finally {
                Files.deleteIfExists(newStamp);
            }
            return found;
        }
    }

    /** Whether the files in the folder can be used as they are: the encoder is there, unpacked from this packed encoder */
    static boolean upToDate(@Nullable String stored, String stamp, boolean programThere) {
        return programThere && stored != null && stored.trim().equals(stamp.trim());
    }

    /**
     * What a packed encoder is known by: the mod's version, and the packed encoder's length and checksum, so that a
     * build with another encoder in it unpacks again though its version is the same
     */
    static String stamp(Packed packed, String version) throws IOException {
        CRC32 checksum = new CRC32();
        long length = 0;
        try (InputStream in = packed.open()) {
            byte[] buffer = new byte[1 << 16];
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                checksum.update(buffer, 0, read);
                length += read;
            }
        }
        return "Dailies " + version + ", " + length + " bytes, CRC32 " + Long.toHexString(checksum.getValue());
    }

    /**
     * Moves a file just unpacked into place. A file there already with the same bytes stays: it may be running in
     * another game, and on Windows a running program cannot be replaced
     */
    private static void place(Path temporary, Path file) throws IOException {
        if (Files.isRegularFile(file) && Files.size(file) == Files.size(temporary) && Files.mismatch(file, temporary) == -1L) {
            return;
        }
        try {
            move(temporary, file);
        } catch (IOException e) {
            throw new IOException("The video encoder's file " + file.getFileName() + " could not be replaced, perhaps because another game is using it. "
                + "Close other games and exports and try again (" + e.getMessage() + ")", e);
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Takes away what an unpacking cut short left behind. Only ever called holding the lock */
    private static void sweepUnpacking(Path folder) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*" + UNPACKING)) {
            for (Path file : files) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException stillOpen) {
                    // Tried again next time
                }
            }
        } catch (IOException e) {
            Host.LOGGER.debug("Dailies: the encoder folder could not be swept", e);
        }
    }

    /**
     * Unpacks the encoder and works out what this machine can encode, on a thread of its own, so the first export
     * does not wait for it on the game's thread. Once a session, after the graphics card has been named. Never throws
     */
    static void warmUp() {
        if (warming || !bundled()) {
            return;
        }
        warming = true;
        Thread warm = new Thread(() -> {
            try {
                choices();
            } catch (Throwable t) {
                Host.LOGGER.info("Dailies: the video encoder could not be made ready ahead of the first export ({})", t.toString());
            }
        }, "Dailies-Encoder-Warm");
        warm.setDaemon(true);
        warm.setPriority(Thread.MIN_PRIORITY);
        warm.start();
    }

    private static volatile boolean warming = false;

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

    /**
     * The names of the encoders this build really contains. Kept for the session once the encoder has answered; an
     * encoder that failed to answer is asked again next time, rather than taken to have none
     */
    public static synchronized Set<String> encoders() throws IOException {
        if (encoders != null) {
            return encoders;
        }
        Set<String> found = new TreeSet<>();
        for (String line : run(List.of(program().toString(), "-hide_banner", "-encoders"), 30)) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 2 && parts[0].length() == 6 && (parts[0].startsWith("V") || parts[0].startsWith("A"))) {
                found.add(parts[1]);
            }
        }
        if (found.isEmpty()) {
            throw new IOException("The video encoder named no encoders");
        }
        encoders = found;
        return found;
    }

    /**
     * Runs the encoder to its end and returns what it said, line by line. Its words are read on a thread of their own,
     * so one that stops talking without ending is still given up on after the time allowed.
     */
    private static List<String> run(List<String> command, long seconds) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        List<String> lines = java.util.Collections.synchronizedList(new ArrayList<>());
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line = in.readLine(); line != null; line = in.readLine()) {
                    lines.add(line);
                }
            } catch (IOException ignored) {
                // The encoder has gone
            }
        }, "Dailies-Encoder-Answer");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("The video encoder did not answer in " + seconds + " seconds");
            }
            reader.join(2000);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while the video encoder was asked", e);
        }
        List<String> said;
        synchronized (lines) {
            said = List.copyOf(lines);
        }
        if (process.exitValue() != 0) {
            throw new IOException("The video encoder failed (exit code " + process.exitValue() + "): " + String.join(" ", said.subList(0, Math.min(5, said.size()))));
        }
        return said;
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
        return videoSink(settings, scale, settings.output, false);
    }

    /**
     * As above, written to another file than the export's own: the soundless video that sound is joined to afterwards.
     *
     * @param i420 the frames can be converted to the encoder's own format on the graphics card; the sink takes them
     *             that way where its encoder writes that format (see {@link FrameSink#layout()})
     */
    static FrameSink videoSink(Export.Settings settings, int scale, Path output, boolean i420) throws IOException {
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
        boolean split = Host.get().setting("exportSplitEncoding", true);
        // A soundless video that sound is joined to afterwards is written once more by that join, which puts the index
        // at the front itself: moving it there in this file too would only write the whole file again for nothing
        boolean faststart = settings.output == null || output.toAbsolutePath().normalize().equals(settings.output.toAbsolutePath().normalize());
        for (Codecs.Choice choice : order) {
            boolean takesI420 = i420 && scale == 1 && Codecs.takesI420(choice.encoder(), settings.transparent);
            if (takesI420 && Codecs.parallel(choice.encoder())) {
                FrameSink parallel = parallelSink(settings, choice.encoder(), container, output, faststart);
                if (parallel != null) {
                    return parallel;
                }
            }
            for (int mode : Codecs.splitModes(choice.encoder(), split)) {
                try {
                    // Tried already, but a card can still refuse the size or rate of this export, or a way of splitting
                    // frames it has too few encoding units for: go on to the next
                    return new VideoSink(settings, scale, choice.encoder(), mode, output, takesI420, faststart);
                } catch (IOException e) {
                    last = e;
                    Host.LOGGER.info("Dailies: encoder {}{} is not usable for this export ({}), trying the next", choice.encoder(),
                        mode >= 0 ? " splitting frames (mode " + mode + ")" : "", e.getMessage());
                }
            }
        }
        throw new IOException("None of the encoders for " + settings.videoCodec + " could encode this export: " + (last == null ? "" : last.getMessage()), last);
    }

    /** The most memory the frames held for side-by-side encoders may take, however much the game has spare */
    private static final long PARALLEL_MEMORY = 2L * 1024 * 1024 * 1024;
    /** Encoder, size and number of encoders that were tried side by side this session, and whether they all started */
    private static final Map<String, Boolean> PARALLEL_TRIED = new ConcurrentHashMap<>();

    /**
     * An export made by several encoders at once ({@link ParallelVideoSink}), as many as the NVIDIA H.264 Encoders at
     * Once setting says; or null, and the caller goes on to a single encoder, when the setting is 1, when there is too
     * little memory spare for the frames they must hold, or when they cannot all start on this card. Never throws.
     */
    private static @Nullable FrameSink parallelSink(Export.Settings settings, String encoder, String container, Path output, boolean faststart) {
        int processes = Host.get().setting("exportParallelEncoders", 3);
        int width = settings.width;
        int height = settings.height;
        if (processes < 2 || !I420.fits(width, height)) {
            return null;
        }
        // Half of what the game has spare, so the game itself is never short; what it counts as used includes garbage
        // not yet collected, so this errs on the small side
        Runtime runtime = Runtime.getRuntime();
        long spare = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
        long frameBytes = I420.size(width, height);
        ParallelVideoSink.Plan plan = ParallelVideoSink.plan(settings.framesPerSecond, frameBytes, processes, Math.min(spare / 2, PARALLEL_MEMORY));
        if (plan == null) {
            Host.LOGGER.info("Dailies: one {} encoder, not {}: {} MB of memory spare is too little to hold the frames they would need", encoder, processes, spare >> 20);
            return null;
        }
        try {
            Path ffmpeg = program();
            if (!parallelTrial(ffmpeg.toString(), encoder, width, height, processes)) {
                Host.LOGGER.info("Dailies: {} {} encoders could not all start at once on this card at {}x{}; one is used", processes, encoder, width, height);
                return null;
            }
            FrameSink sink = new ParallelVideoSink(ffmpeg, width, height, settings.framesPerSecond, Codecs.videoArguments(encoder, settings.bitrate, false, -1),
                container, output, faststart, processes, plan.chunk(), plan.buffers(), line -> Host.LOGGER.info("Dailies: {}", line));
            Host.LOGGER.info("Dailies: {} {} encoders side by side, in chunks of {} frames, each holding up to {} frames ({} MB in all at most)",
                processes, encoder, plan.chunk(), plan.buffers(), (long) processes * plan.buffers() * frameBytes >> 20);
            return sink;
        } catch (IOException e) {
            Host.LOGGER.info("Dailies: {} {} encoders side by side could not start ({}); one is used", processes, encoder, e.getMessage());
            return null;
        }
    }

    /**
     * Whether this many encoders start at once on this card at this size. An encoder only opens on the card when its
     * first frame comes, which for an export is after it has begun; a card or driver that allows fewer encoders at
     * once than asked for (or other programs using some) would then fail the export a few seconds in. So they are
     * tried together first, a few frames each, and the answer kept for the session. Tried outside the map's own lock,
     * so a trial of one size never holds up the answer for another
     */
    private static boolean parallelTrial(String ffmpeg, String encoder, int width, int height, int processes) {
        String key = encoder + " " + width + "x" + height + " x" + processes + " on " + machine;
        Boolean known = PARALLEL_TRIED.get(key);
        if (known != null) {
            return known;
        }
        boolean all = true;
        List<Process> started = new ArrayList<>();
        try {
            for (int i = 0; i < processes; i++) {
                started.add(new ProcessBuilder(Codecs.trialArguments(ffmpeg, encoder, width, height)).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start());
            }
            for (Process process : started) {
                if (!process.waitFor(20, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    all = false;
                } else if (process.exitValue() != 0) {
                    all = false;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            started.forEach(Process::destroyForcibly);
            // Not kept: an interrupted trial says nothing about the card
            return false;
        } catch (IOException | RuntimeException e) {
            started.forEach(Process::destroyForcibly);
            all = false;
        }
        Boolean first = PARALLEL_TRIED.putIfAbsent(key, all);
        return first != null ? first : all;
    }

    /**
     * Whether an encoder just started is still running a moment later: one that cannot start says so at once. Waits
     * at most this long, and less when the encoder ends sooner, rather than always the whole time.
     */
    static boolean stillRunningAfter(Process process, long millis) {
        try {
            process.onExit().get(millis, TimeUnit.MILLISECONDS);
            return false;
        } catch (TimeoutException running) {
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return process.isAlive();
        } catch (ExecutionException e) {
            return process.isAlive();
        }
    }

    private static final class VideoSink implements FrameSink {

        /**
         * How much is handed to the pipe at a time. Java copies each write into a native buffer of its size first:
         * a whole 4K frame at once meant a fresh allocation of tens of megabytes every frame
         */
        private static final int CHUNK = 256 * 1024;

        private final Process process;
        private final OutputStream input;
        private final Path output;
        private final StringBuilder log = new StringBuilder();
        private final int width, height;
        private final Layout layout;

        /**
         * @param faststart the index is moved to the front of an MP4 or MOV, as the file the export ends with needs;
         *                  not a soundless file that sound is joined to afterwards
         */
        VideoSink(Export.Settings settings, int scale, String encoder, int split, Path output, boolean i420, boolean faststart) throws IOException {
            this.output = output;
            this.width = settings.width * scale;
            this.height = settings.height * scale;
            this.layout = i420 && I420.fits(this.width, this.height) ? Layout.I420 : Layout.RGBA_BOTTOM_UP;
            Files.createDirectories(output.toAbsolutePath().getParent());
            List<String> command = new ArrayList<>(List.of(program().toString(), "-hide_banner", "-loglevel", "error", "-y",
                "-f", "rawvideo", "-pix_fmt", this.layout == Layout.I420 ? "yuv420p" : "rgba", "-s", this.width + "x" + this.height,
                "-r", String.valueOf(settings.framesPerSecond), "-i", "pipe:0"));
            if (this.layout == Layout.RGBA_BOTTOM_UP) {
                // Frames arrive bottom row first; supersampled frames are brought down to size here. Frames converted on the
                // graphics card come the right way up, in the format the encoder writes, and need nothing done to them
                command.addAll(List.of("-vf", scale > 1 ? "vflip,scale=" + settings.width + ":" + settings.height + ":flags=lanczos" : "vflip"));
            }
            command.addAll(Codecs.videoArguments(encoder, settings.bitrate, settings.transparent, split));
            command.add("-an");
            if (faststart && (settings.container.equals("mp4") || settings.container.equals("mov"))) {
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
            // An encoder that cannot start says so at once, and is not waited for any longer than it takes to
            if (!stillRunningAfter(this.process, 400)) {
                try {
                    // What it said is still on its way to the reader
                    reader.join(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new IOException(this.said());
            }
        }

        private String said() {
            synchronized (this.log) {
                return this.log.length() == 0 ? "the encoder stopped without saying why" : this.log.toString().trim();
            }
        }

        @Override
        public Layout layout() {
            return this.layout;
        }

        @Override
        public void frame(long number, byte[] pixels, int length, int frameWidth, int frameHeight) throws IOException {
            if (frameWidth != this.width || frameHeight != this.height) {
                throw new IOException("A frame of " + frameWidth + "x" + frameHeight + " arrived in an export of " + this.width + "x" + this.height);
            }
            int expected = this.layout == Layout.I420 ? I420.size(frameWidth, frameHeight) : frameWidth * frameHeight * 4;
            if (length != expected) {
                throw new IOException("A frame of " + length + " bytes arrived where " + expected + " were expected");
            }
            try {
                for (int at = 0; at < length; at += CHUNK) {
                    this.input.write(pixels, at, Math.min(CHUNK, length - at));
                }
            } catch (IOException e) {
                throw new IOException("The encoder stopped: " + this.said(), e);
            }
        }

        @Override
        public Path finish(boolean cancelled) throws IOException {
            try {
                this.input.close();
            } catch (IOException gone) {
                // An encoder that has already stopped: how it ended says why
            }
            // Waited for whatever interrupts this thread: the file is only whole once the encoder has ended by itself,
            // which after its last frame takes moments
            boolean interrupted = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            try {
                while (true) {
                    try {
                        if (!this.process.waitFor(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                            this.process.destroyForcibly();
                            throw new IOException("The encoder did not finish the file");
                        }
                        break;
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
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
