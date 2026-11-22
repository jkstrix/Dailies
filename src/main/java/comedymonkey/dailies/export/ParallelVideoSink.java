package comedymonkey.dailies.export;

import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * An H.264 video made by several encoders at once. An NVIDIA card has several encoding units (an RTX 5090 has three),
 * but one H.264 encoder uses only one of them, and unlike HEVC and AV1 an H.264 frame cannot be split between them.
 * So the video is cut into chunks of about a second instead, and chunk i goes to encoder i mod N: each encoder runs as
 * a process of its own, fed on its own pipe by a thread of its own, and starts every chunk of its share on a keyframe
 * that nothing before it is needed for. At the end the chunks are joined in order without being encoded again.
 * <p>
 * Each encoder gives its frames their times in the whole video, not in its own share, and cuts what it writes
 * into files at its keyframes, listing each finished file with the times it covers. Joining is then putting every
 * encoder's files in order of time and passing their bytes, one after the other, to one more run of the encoder that
 * copies them into the file asked for: the times already run on unbroken, so nothing has to be worked out, and a run
 * of FFmpeg's file-by-file joiner, which spent a thirtieth of a second looking into each file, is not needed.
 * <p>
 * Frames still arrive in order on the export's one writer thread. {@link #keep} hands each, as it is, to the encoder
 * whose chunk it belongs to and returns; that encoder's thread pushes it down the pipe and then gives the frame back
 * to the export's own frames, so no frame is copied on its way (a 4K frame is twelve megabytes). While one encoder
 * takes in its chunk the others are still working through theirs, and only frames held for them let them do so:
 * with two or three buffers each, the encoders took turns and a 4K export gained a tenth; with most of a chunk each,
 * it ran twice as fast (on an RTX 5090, 147 frames a second with one encoder, 295 with three, joining included).
 * The export keeps that many more frames ({@link #keeps}), and when all are held it waits for one to come back.
 * <p>
 * An export with sound has it put into the file by that same last run, which reads the parts on its input pipe and the
 * captured sound from its file ({@link Sound}, {@link JoinCommand}): the picture is written to disk once, and not
 * joined into a soundless file first that joining the sound to then copies again (EXP-7).
 * <p>
 * No Minecraft here: measured, and its pure parts tested, without a game.
 */
final class ParallelVideoSink implements FrameSink {

    /** How much is handed to a pipe at a time, as {@code VideoSink} does, so Java never copies a whole frame natively */
    private static final int WRITE = 256 * 1024;

    /** A frame handed to an encoder, and what gives it back once it is down the pipe */
    private record Held(byte[] data, Runnable done) {
    }

    /**
     * The sound of an export, put into the file in the run that joins the parts (EXP-7). The export's finishing thread,
     * once every picture is taken and the sound has stopped being captured
     */
    interface Sound {

        /**
         * Writes the file with the sound, by running the encoder with the parts as its picture, as often as it needs
         * (once more without the audio keyframes, say, when one of their files cannot be read). Whether the file was
         * written with sound: when not, the parts are joined into it alone. Never throws
         *
         * @param cancelled the export was stopped before every picture was taken, or an encoder failed: the picture is
         *                  then kept without sound
         */
        boolean joinInto(JoinCommand.Picture parts, Path output, boolean cancelled);
    }

    /** Handed to an encoder's thread to say its share has ended */
    private static final Held END = new Held(new byte[0], () -> {
    });

    private final Path program;
    private final int width, height;
    private final int frameBytes;
    private final double framesPerSecond;
    private final int chunk;
    private final int offset;
    private final int buffers;
    private final String container;
    private final Path output;
    private final @Nullable Sound sound;
    private final Path folder;
    private final Consumer<String> log;
    private final List<Lane> lanes = new ArrayList<>();
    private long frames = 0;

    /**
     * Starts every encoder, or none: if one cannot start, those already started are stopped and the encoder's own
     * words are thrown, so the caller can fall back to a single encoder.
     *
     * @param encoderArguments as {@link Codecs#videoArguments}, from "-c:v" on
     * @param output           the file the export ends with: the joined file has its index at the front
     * @param sound            the sound put into it as the parts are joined, or null for none
     * @param chunk            frames in a chunk, which is also the keyframe interval
     * @param buffers          frames each encoder may have waiting for it
     */
    ParallelVideoSink(Path program, int width, int height, double framesPerSecond, List<String> encoderArguments, String container,
                      Path output, @Nullable Sound sound, int processes, int chunk, int buffers, Consumer<String> log) throws IOException {
        if (processes < 2) {
            throw new IllegalArgumentException("Encoding side by side needs at least two encoders");
        }
        this.program = program;
        this.width = width;
        this.height = height;
        this.frameBytes = I420.size(width, height);
        this.framesPerSecond = framesPerSecond;
        this.chunk = Math.max(1, chunk);
        this.offset = offset(processes, this.chunk);
        this.buffers = Math.max(1, buffers);
        this.container = container;
        this.output = output.toAbsolutePath();
        this.sound = sound;
        this.log = log;
        Path parent = this.output.getParent();
        Files.createDirectories(parent);
        // A folder of its own every time, named by nothing the person typed: the encoders read its path as a pattern
        this.folder = parent.resolve(TemporaryFiles.parts());
        Files.createDirectories(this.folder);
        try {
            for (int index = 0; index < processes; index++) {
                this.lanes.add(new Lane(index, this.laneCommand(index, processes, encoderArguments)));
            }
            // An encoder that cannot start says so at once, and is not waited for any longer than it takes to
            CompletableFuture<?>[] exits = this.lanes.stream().map(lane -> lane.process.onExit()).toArray(CompletableFuture[]::new);
            try {
                CompletableFuture.anyOf(exits).get(400, TimeUnit.MILLISECONDS);
            } catch (TimeoutException allRunning) {
                // As it should be
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                // Looked at below
            }
            for (Lane lane : this.lanes) {
                if (!lane.process.isAlive()) {
                    try {
                        // What it said is still on its way to the reader
                        lane.reader.join(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    throw new IOException(lane.said());
                }
            }
        } catch (IOException | RuntimeException e) {
            this.abort();
            throw e;
        }
    }

    // ------------------------------------------------------------------ The pure parts

    /** Frames in a chunk of about this many seconds: whole frames, at least one */
    static int chunkFrames(double framesPerSecond, double seconds) {
        return (int) Math.max(1, Math.round(framesPerSecond * seconds));
    }

    /** How long chunks are and how many frames each encoder may hold */
    record Plan(int chunk, int buffers) {
    }

    /**
     * The frames an encoder must be able to hold to stay busy: while it takes in its own chunk the frames come as fast
     * as all the encoders together take them, so it falls behind by all but its own share of the chunk
     */
    static int buffersFor(int chunk, int processes) {
        return (int) Math.ceil((double) chunk * (processes - 1) / processes);
    }

    /**
     * Chunks of a second, with enough frames held for every encoder to stay busy, where the memory allows; measured on
     * an RTX 5090 at 4K, three encoders in chunks of a second with forty frames each ran at 300 frames a second, twice
     * one encoder's speed, where with three frames each they took turns and gained a tenth. Where the memory does not
     * allow it, shorter chunks need fewer frames held, down to half a second, since each chunk starts with a keyframe
     * and keyframes cost bits (on real footage at Dailies' high bitrate, one a second measured as good as one every four
     * seconds, a single encoder's own spacing). Null when even that cannot be held: the encoders would only take turns.
     *
     * @param budget the bytes all the encoders' frames together may take
     */
    static Plan plan(double framesPerSecond, long frameBytes, int processes, long budget) {
        int second = chunkFrames(framesPerSecond, 1.0);
        int ideal = buffersFor(second, processes);
        long affordable = budget / (Math.max(1, frameBytes) * processes);
        if (affordable >= ideal) {
            return new Plan(second, ideal);
        }
        int chunk = (int) Math.max(chunkFrames(framesPerSecond, 0.5), Math.min(second, affordable * processes / (processes - 1)));
        int buffers = (int) Math.min(affordable, buffersFor(chunk, processes));
        return buffers < 4 ? null : new Plan(chunk, buffers);
    }

    /** The encoder a frame of the video goes to: chunk after chunk, round the encoders in turn */
    static int laneOf(long frame, int chunk, int processes) {
        return (int) ((frame / chunk) % processes);
    }

    /** Where the {@code local}th frame an encoder is given falls in the whole video: what {@link #timestamps} works out */
    static long globalFrame(long local, int lane, int processes, int chunk) {
        return ((local / chunk) * processes + lane) * chunk + local % chunk;
    }

    /**
     * How far every time is moved on, in frames: a round of chunks, and never less than a few frames. A B-frame is
     * decoded before the frame shown ahead of it, so the first decoding times would be a few frames below zero, and a
     * muxer that moves them up would move only the first encoder's, out of step with the others
     */
    static int offset(int processes, int chunk) {
        return Math.max(16, processes * chunk);
    }

    /**
     * What moves an encoder's encoded frames to their times in the whole video, as {@link #globalFrame} says, moved on
     * by {@link #offset}. The encoder itself is given its frames one after the other, so it works as on any video;
     * the times are moved afterwards, a chunk at a time, the time of showing and the time of decoding alike by the
     * shift of the chunk the frame is shown in. Moving the frames before encoding would not do: the encoder gives the
     * first frames of each chunk decoding times from the end of its chunk before, seconds back in the joined video.
     * The time unit is one frame, since the frames come in at the video's own rate
     */
    static String timestamps(int lane, int processes, int chunk) {
        String shift = "+floor(PTS/" + chunk + ")*" + (long) (processes - 1) * chunk + "+" + ((long) lane * chunk + offset(processes, chunk));
        return "setts=pts=PTS" + shift + ":dts=DTS" + shift;
    }

    /** One file an encoder finished, with the times it covers in seconds, as its list says */
    record Part(String file, double start, double end) {
    }

    /** A line of an encoder's list of finished files: "name,start,end". Null for a line that is not one */
    static Part parsePart(String line) {
        String text = line.trim();
        int last = text.lastIndexOf(',');
        int middle = last > 0 ? text.lastIndexOf(',', last - 1) : -1;
        if (middle <= 0) {
            return null;
        }
        try {
            String name = text.substring(0, middle);
            if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
                name = name.substring(1, name.length() - 1).replace("\"\"", "\"");
            }
            return new Part(name, Double.parseDouble(text.substring(middle + 1, last)), Double.parseDouble(text.substring(last + 1)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * An encoder's list as it is meant: FFmpeg lists the first file as starting at nought whenever its first frame
     * comes, so that one start is put right from where the encoder's first chunk is known to begin
     */
    static List<Part> laneParts(List<Part> listed, double firstStart) {
        List<Part> parts = new ArrayList<>(listed);
        if (!parts.isEmpty()) {
            Part first = parts.get(0);
            parts.set(0, new Part(first.file(), firstStart, first.end()));
        }
        return parts;
    }

    /**
     * The files in the order they are joined: by time, from the start, as far as they run on without a gap. Every
     * file begins on a keyframe, and wherever one encoder's file follows another's that keyframe needs nothing before
     * it. When an encoder failed or the export was stopped, what is joined still plays from the beginning to the first
     * gap (EXP-6).
     *
     * @param from  when the video starts, in seconds
     * @param slack how far apart two times may be and still count as one, in seconds: under half a frame
     */
    static List<Part> joinOrder(List<Part> parts, double from, double slack) {
        List<Part> sorted = new ArrayList<>(parts);
        sorted.sort(Comparator.comparingDouble(Part::start));
        List<Part> order = new ArrayList<>();
        double reached = from;
        for (Part part : sorted) {
            if (Math.abs(part.start() - reached) > slack) {
                break;
            }
            order.add(part);
            reached = part.end();
        }
        return order;
    }

    /** How many whole frames the joined files cover */
    static long framesCovered(List<Part> order, double from, double framesPerSecond) {
        return order.isEmpty() ? 0 : Math.round((order.get(order.size() - 1).end() - from) * framesPerSecond);
    }

    /**
     * What makes an encoder start each chunk of its share on a keyframe and cut its output into files there. The
     * keyframe is forced at every chunk's first frame and made a full refresh (IDR), so nothing in the chunk looks back
     * past it into the encoder's chunk before, which in the joined video is not the frame before; the keyframe interval
     * is the chunk, so no other keyframe falls inside one. Files are cut at keyframes once a round of chunks has gone
     * by, and each is listed with its times as it is closed, so a file an encoder never finished is never joined.
     * Frames keep the times they were moved to: the encoders' gaps are the other encoders' chunks.
     */
    static List<String> laneArguments(int lane, int processes, int chunk, double framesPerSecond, String listFile) {
        return List.of("-bsf:v", timestamps(lane, processes, chunk),
            "-g", String.valueOf(chunk), "-forced-idr", "1", "-force_key_frames", "expr:eq(mod(n," + chunk + "),0)",
            "-f", "segment", "-segment_format", "mpegts",
            "-segment_time", String.format(Locale.ROOT, "%.6f", (double) processes * chunk / framesPerSecond),
            "-segment_time_delta", String.format(Locale.ROOT, "%.6f", 0.5 / framesPerSecond),
            "-segment_list", listFile, "-segment_list_type", "csv");
    }

    // ------------------------------------------------------------------ The encoders

    private List<String> laneCommand(int index, int processes, List<String> encoderArguments) {
        // The frame rate as the raw reader's own: the time unit is then one frame, which the time-moving filter counts in
        List<String> command = new ArrayList<>(List.of(this.program.toString(), "-hide_banner", "-loglevel", "error", "-y",
            "-f", "rawvideo", "-pix_fmt", "yuv420p", "-s", this.width + "x" + this.height,
            "-framerate", String.valueOf(this.framesPerSecond), "-i", "pipe:0"));
        command.addAll(encoderArguments);
        command.add("-an");
        command.addAll(laneArguments(index, processes, this.chunk, this.framesPerSecond, this.folder.resolve(index + ".csv").toString()));
        command.add(segmentTemplate(this.folder, index));
        return command;
    }

    /**
     * Where an encoder writes its parts: its number and the part's, in the folder. The encoder reads the whole path as
     * a pattern, in which a percent sign starts a number, so every percent sign of the folder's own path is doubled,
     * which the encoder reads as one
     */
    static String segmentTemplate(Path folder, int index) {
        String inside = folder.toAbsolutePath().toString().replace("%", "%%");
        String separator = folder.getFileSystem().getSeparator();
        return (inside.endsWith(separator) ? inside : inside + separator) + index + "-%06d.ts";
    }

    /** One encoder: its process, the frames waiting for it, and the thread that feeds it */
    private final class Lane {

        final int index;
        final Process process;
        final OutputStream input;
        final StringBuilder said = new StringBuilder();
        /** Frames waiting to go down the pipe, with the end after the last; no more than the export's own frames */
        final BlockingQueue<Held> full = new LinkedBlockingQueue<>();
        /** Frames handed to this encoder: one the export never reached has none, and its list says nothing true */
        long sent = 0;
        final Thread reader;
        final Thread feeder;
        volatile IOException failure = null;

        Lane(int index, List<String> command) throws IOException {
            this.index = index;
            this.process = new ProcessBuilder(command).redirectErrorStream(true).start();
            this.input = this.process.getOutputStream();
            this.reader = new Thread(() -> {
                try (BufferedReader lines = new BufferedReader(new InputStreamReader(this.process.getInputStream(), StandardCharsets.UTF_8))) {
                    for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                        synchronized (this.said) {
                            if (this.said.length() < 4000) {
                                this.said.append(line).append('\n');
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // The encoder has gone
                }
            }, "Dailies-Encoder-Log-" + index);
            this.reader.setDaemon(true);
            this.reader.start();
            this.feeder = new Thread(this::feed, "Dailies-Encoder-Feed-" + index);
            this.feeder.setDaemon(true);
            this.feeder.start();
        }

        String said() {
            synchronized (this.said) {
                return this.said.length() == 0 ? "the encoder stopped without saying why" : this.said.toString().trim();
            }
        }

        /**
         * Pushes frames down the pipe until the end, giving each back once it is written. After a failure it goes on
         * taking frames and giving them back unwritten, so the export is never left waiting on a frame that cannot
         * come back; the writer sees the failure at its next frame for this encoder.
         */
        private void feed() {
            try {
                while (true) {
                    Held frame = this.full.take();
                    if (frame == END) {
                        break;
                    }
                    try {
                        if (this.failure == null) {
                            for (int at = 0; at < ParallelVideoSink.this.frameBytes; at += WRITE) {
                                this.input.write(frame.data(), at, Math.min(WRITE, ParallelVideoSink.this.frameBytes - at));
                            }
                        }
                    } catch (IOException e) {
                        this.failure = e;
                    } finally {
                        frame.done().run();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                try {
                    this.input.close();
                } catch (IOException e) {
                    if (this.failure == null) {
                        this.failure = e;
                    }
                }
            }
        }

        /** The files this encoder finished, as it listed them */
        List<Part> finishedParts() {
            List<Part> parts = new ArrayList<>();
            if (this.sent == 0) {
                // An export shorter than a round of chunks never reaches the later encoders. Their lists, if they wrote
                // any, would be put at their first chunk's time by laneParts and break the join (seen in game: a sixty
                // frame export "held -90 of its 60 frames", and fifteen frames were refused although all were there)
                return parts;
            }
            try {
                for (String line : Files.readAllLines(ParallelVideoSink.this.folder.resolve(this.index + ".csv"), StandardCharsets.UTF_8)) {
                    Part part = parsePart(line);
                    if (part != null) {
                        parts.add(part);
                    }
                }
            } catch (IOException e) {
                // No list: the encoder finished no file
            }
            ParallelVideoSink sink = ParallelVideoSink.this;
            return laneParts(parts, (sink.offset + (double) this.index * sink.chunk) / sink.framesPerSecond);
        }
    }

    // ------------------------------------------------------------------ FrameSink

    @Override
    public Layout layout() {
        return Layout.I420;
    }

    @Override
    public int keeps() {
        return this.lanes.size() * this.buffers;
    }

    /**
     * Hands a frame to the encoder whose chunk it belongs to, as it is: the array stays with that encoder until its
     * thread has written it, and {@code done} gives it back. Nothing is kept when this throws.
     */
    @Override
    public void keep(long number, byte[] pixels, int length, int frameWidth, int frameHeight, Runnable done) throws IOException {
        if (frameWidth != this.width || frameHeight != this.height) {
            throw new IOException("A frame of " + frameWidth + "x" + frameHeight + " arrived in an export of " + this.width + "x" + this.height);
        }
        if (length != this.frameBytes || pixels.length < this.frameBytes) {
            throw new IOException("A frame of " + length + " bytes arrived where " + this.frameBytes + " were expected");
        }
        Lane lane = this.lanes.get(laneOf(this.frames, this.chunk, this.lanes.size()));
        if (lane.failure != null) {
            throw new IOException("The encoder stopped: " + lane.said(), lane.failure);
        }
        if (!lane.feeder.isAlive()) {
            throw new IOException("The encoder stopped: " + lane.said());
        }
        lane.full.add(new Held(pixels, done));
        lane.sent++;
        this.frames++;
    }

    /** For a caller that does not hand its frames over: each is copied first, so this costs an array a frame */
    @Override
    public void frame(long number, byte[] pixels, int length, int frameWidth, int frameHeight) throws IOException {
        this.keep(number, Arrays.copyOf(pixels, Math.max(length, 0)), length, frameWidth, frameHeight, () -> {
        });
    }

    @Override
    public Path finish(boolean cancelled) throws IOException {
        for (Lane lane : this.lanes) {
            lane.full.offer(END);
        }
        IOException failed = null;
        for (Lane lane : this.lanes) {
            try {
                lane.feeder.join(120_000);
                if (!lane.process.waitFor(120, TimeUnit.SECONDS)) {
                    lane.process.destroyForcibly();
                    failed = new IOException("The encoder did not finish its part of the file");
                } else if (lane.process.exitValue() != 0 || lane.failure != null) {
                    failed = new IOException("The encoder failed: " + lane.said(), lane.failure);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failed = new IOException("The export was interrupted", e);
            }
        }
        List<Part> parts = new ArrayList<>();
        for (Lane lane : this.lanes) {
            for (Part part : lane.finishedParts()) {
                // A file with no frames in it adds nothing to the video, and has no place in the order
                if (part.end() - part.start() > 0.5 / this.framesPerSecond) {
                    parts.add(part);
                }
            }
        }
        double from = this.offset / this.framesPerSecond;
        List<Part> order = joinOrder(parts, from, 0.5 / this.framesPerSecond);
        long covered = framesCovered(order, from, this.framesPerSecond);
        if (failed == null && (covered != this.frames || order.size() != parts.size())) {
            failed = new IOException("The parts of the video do not join up: they hold " + covered + " of its " + this.frames + " frames");
        }
        if (failed != null && !cancelled) {
            deleteFolder(this.folder);
            throw failed;
        }
        if (order.isEmpty()) {
            deleteFolder(this.folder);
            throw failed != null ? failed : new IOException("No frames were encoded");
        }
        if (failed != null) {
            this.log.accept("an encoder of the stopped export failed; its first " + covered + " frames are kept: " + failed.getMessage());
        }
        long started = System.nanoTime();
        JoinCommand.Picture picture = new JoinCommand.Picture() {
            @Override
            public List<String> arguments() {
                return JoinCommand.PARTS;
            }

            @Override
            public @Nullable String run(List<String> command) {
                return ParallelVideoSink.this.join(order, command);
            }
        };
        // A stopped export, or one whose picture ends short, is kept without its sound, as with a single encoder: the
        // sound side says why, and the picture is joined alone
        boolean withSound = false;
        if (this.sound != null) {
            try {
                withSound = this.sound.joinInto(picture, this.output, cancelled || failed != null);
            } catch (RuntimeException | LinkageError e) {
                // Never meant to happen; the picture is still all there
                this.log.accept("the sound could not be joined in with the parts: " + e);
            }
        }
        if (!withSound) {
            String failure = this.join(order, joinCommand(this.program.toString(), this.output, this.container));
            if (failure != null) {
                deleteFolder(this.folder);
                throw new IOException("The encoder could not join the parts of the video: " + failure);
            }
        }
        this.log.accept("joined " + order.size() + " parts of " + covered + " frames from " + this.lanes.size() + " H.264 encoders"
            + (withSound ? " with the sound" : "") + " in " + (System.nanoTime() - started) / 1_000_000 + " ms");
        deleteFolder(this.folder);
        return this.output;
    }

    /** The run that joins the parts alone, when there is no sound to put with them: copied, never encoded again */
    static List<String> joinCommand(String program, Path output, String container) {
        return JoinCommand.pictureOnly(program, JoinCommand.PARTS, output, container);
    }

    /**
     * Runs the encoder with a command that reads the picture from its input pipe ({@link JoinCommand#PARTS}), and
     * hands it the files, in order: their bytes one after the other, never encoded again. Null when it worked, else
     * what the encoder said; run again, it writes the file afresh. An interrupt stops the encoder and the joining.
     */
    private @Nullable String join(List<Part> order, List<String> command) {
        Path said = this.folder.resolve("join.log");
        Process joiner;
        try {
            joiner = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(said.toFile()).start();
        } catch (IOException e) {
            return "it could not start (" + e.getMessage() + ")";
        }
        long bytes = 0;
        try (OutputStream into = joiner.getOutputStream()) {
            for (Part part : order) {
                bytes += Files.copy(this.folder.resolve(part.file()), into);
            }
        } catch (IOException e) {
            // The joiner stopped taking bytes: what it said comes below
        }
        try {
            // Copying is quick, but a long 4K video is many gigabytes, written twice over for the fast start; the sound,
            // when it goes in too, is encoded many times faster than it plays
            long seconds = 120 + bytes / (50L * 1024 * 1024) + (long) (this.frames / this.framesPerSecond / 10.0);
            if (!joiner.waitFor(seconds, TimeUnit.SECONDS)) {
                joiner.destroyForcibly();
                return "it did not finish in " + seconds + " seconds";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            joiner.destroyForcibly();
            return "the export was interrupted while the parts of the video were joined";
        }
        if (joiner.exitValue() != 0) {
            String words;
            try {
                words = Files.exists(said) ? Files.readString(said, StandardCharsets.UTF_8).trim() : "";
            } catch (IOException e) {
                words = "";
            }
            return words.isEmpty() ? "it stopped without saying why" : words;
        }
        return null;
    }

    @Override
    public void abort() {
        for (Lane lane : this.lanes) {
            lane.process.destroyForcibly();
            lane.feeder.interrupt();
        }
        for (Lane lane : this.lanes) {
            try {
                lane.process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        deleteFolder(this.folder);
    }

    /**
     * Deletes the chunk files and their folder. On Windows a file just written, or an encoder just stopped, can keep a
     * file from going for a moment (a virus scanner looking at it is enough), and the folder cannot go before its
     * files, so it is tried again a few times. Whatever is left is taken away when a game next starts, once this game
     * has closed ({@link TemporaryFiles#sweep})
     */
    private static void deleteFolder(Path folder) {
        for (int attempt = 0; attempt < 20 && Files.exists(folder); attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            try (Stream<Path> files = Files.walk(folder)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException notYet) {
                        // Tried again below
                    }
                }
            } catch (IOException | java.io.UncheckedIOException notYet) {
                // Tried again
            }
        }
    }

}
