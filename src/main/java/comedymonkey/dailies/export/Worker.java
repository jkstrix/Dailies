package comedymonkey.dailies.export;

import comedymonkey.dailies.Host;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * The hidden second game that works through the export queue (specification EXP-13), from both ends: starting one
 * from the game being played, and, inside the worker itself, getting out of the way: a small hidden window, no sound
 * between exports, and nothing written but its exports and its reports to the queue file.
 * <p>
 * A worker is the same game started again with {@code -Ddailies.worker=<queue file>}. What it must never do is
 * enforced where it would happen: settings are not saved (DailiesConfig, the game's own options), recordings are
 * opened for reading only and their editor state is not saved, the index of recordings is not written, the MCP server
 * does not start, and it never joins a world.
 */
public final class Worker {

    /** The worker's window, before it is hidden. Small, so it costs little while it is being made */
    private static final int WIDTH = 640;
    private static final int HEIGHT = 360;

    private static final @Nullable String QUEUE = System.getProperty(WorkerLaunch.PROPERTY);

    private static boolean setUp = false;
    private static double ownVolume = 1.0;

    private Worker() {
    }

    /** Whether this game is a worker. Safe from any thread and before anything has loaded */
    public static boolean active() {
        return QUEUE != null && !QUEUE.isBlank();
    }

    /** The queue file a worker was told to read */
    public static @Nullable Path queueFile() {
        return active() ? Path.of(QUEUE) : null;
    }

    // ------------------------------------------------------------------ Inside the worker

    /**
     * Once the game has finished loading: hide the window, silence it and stop it pausing when it is not in front.
     * None of this is saved: the worker's settings are never written. Client thread
     */
    static boolean setUpOnce(Minecraft minecraft) {
        if (setUp) {
            return true;
        }
        if (minecraft.getOverlay() != null || !minecraft.isGameLoadFinished()) {
            return false;
        }
        setUp = true;
        try {
            minecraft.options.pauseOnLostFocus = false;
            ownVolume = minecraft.options.getSoundSourceVolume(SoundSource.MASTER);
            mute(minecraft, true);
            String window = System.getProperty(WorkerLaunch.PROPERTY + ".window", "hidden").toLowerCase(Locale.ROOT);
            long handle = minecraft.getWindow().getWindow();
            if (window.equals("minimised") || window.equals("minimized")) {
                GLFW.glfwIconifyWindow(handle);
            } else if (!window.equals("shown")) {
                GLFW.glfwHideWindow(handle);
            }
            minecraft.getWindow().setTitle("Dailies: background export");
        } catch (Throwable t) {
            Host.LOGGER.warn("Dailies worker: the window or the sound could not be set as a worker wants them", t);
        }
        Host.LOGGER.info("Dailies worker: started for {}", QUEUE);
        return true;
    }

    /**
     * Silences the worker, or gives it its sound back for an export that records sound: what an export records goes
     * to a device of its own and is never heard, but the master volume is in what it records.
     */
    static void mute(Minecraft minecraft, boolean silent) {
        if (!active()) {
            return;
        }
        try {
            minecraft.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(silent ? 0.0 : ownVolume);
        } catch (Throwable t) {
            Host.LOGGER.debug("Dailies worker: the volume could not be changed", t);
        }
    }

    // ------------------------------------------------------------------ Starting one

    /** What starting a worker came to */
    public record Started(boolean started, String words, long pid) {
    }

    /**
     * Starts a worker for the queue file. Not for the client thread: it asks the system how this game was started and
     * waits a moment to see that the new game did not close at once.
     *
     * @return started, or not and why not, in words for a person
     */
    public static Started launch(Path queueFile) {
        if (active()) {
            return new Started(false, "This is the background export game itself", 0);
        }
        // A game does not take all it is allowed at once, and some systems count their file cache as used: half of what
        // this game was allowed, and a little for Java itself, is what a second one cannot do without
        long needed = workerHeap() / 2 + 512L * 1024 * 1024;
        long free = freeMemory();
        if (free > 0 && free < needed) {
            return new Started(false, "There is not enough free memory for a second game: it needs at least " + gigabytes(needed)
                + " and " + gigabytes(free) + " is free", 0);
        }
        WorkerLaunch.Plan plan;
        try {
            plan = WorkerLaunch.plan(launched(), queueFile.toAbsolutePath().toString(), WIDTH, HEIGHT);
        } catch (RuntimeException e) {
            return new Started(false, "How this game was started could not be found out: " + e.getMessage(), 0);
        }
        Path log = queueFile.toAbsolutePath().resolveSibling("worker.log");
        try {
            ProcessBuilder builder = new ProcessBuilder(plan.command());
            builder.directory(Path.of(System.getProperty("user.dir")).toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(log.toFile());
            Host.LOGGER.info("Dailies: starting the background export game ({} arguments, found from the {})", plan.command().size(), plan.how());
            Process process = builder.start();
            // A launcher that forbids a second game, or a command that is not quite right, ends it at once
            if (process.waitFor(4, TimeUnit.SECONDS)) {
                return new Started(false, "The second game closed at once (exit code " + process.exitValue() + "). What it said is in " + log, 0);
            }
            return new Started(true, "A background game is exporting the queue. It has no window; its log is " + log, process.pid());
        } catch (Exception e) {
            return new Started(false, "A second game could not be started: " + e.getMessage(), 0);
        }
    }

    /** Everything that can be learnt about how this game was started */
    static WorkerLaunch.Launched launched() {
        ProcessHandle.Info info = ProcessHandle.current().info();
        String executable = info.command().orElse(null);
        if (executable == null || executable.isBlank()) {
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            executable = Path.of(System.getProperty("java.home"), "bin", windows ? "javaw.exe" : "java").toString();
        }
        List<String> arguments = info.arguments().map(List::of).orElse(null);
        String commandLine = null;
        if (arguments == null || arguments.isEmpty()) {
            commandLine = windowsCommandLine();
        }
        List<String> jvm;
        try {
            jvm = new ArrayList<>(ManagementFactory.getRuntimeMXBean().getInputArguments());
        } catch (Throwable t) {
            jvm = List.of();
        }
        return new WorkerLaunch.Launched(executable, arguments, commandLine, jvm, System.getProperty("java.class.path", ""), System.getProperty("sun.java.command", ""));
    }

    /** Windows tells a program its own command line only through the system's management interface */
    private static @Nullable String windowsCommandLine() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return null;
        }
        long pid = ProcessHandle.current().pid();
        try {
            // Its complaints are thrown away, so they can never fill a pipe nobody reads and hold it up
            Process ask = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "[Console]::OutputEncoding = [Text.Encoding]::UTF8; (Get-CimInstance Win32_Process -Filter 'ProcessId=" + pid + "').CommandLine")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            ask.getOutputStream().close();
            StringBuilder said = new StringBuilder();
            // Read on a thread of its own, so that the time allowed below holds even when the answer never ends
            Thread reader = new Thread(() -> {
                try (BufferedReader lines = new BufferedReader(new InputStreamReader(ask.getInputStream(), StandardCharsets.UTF_8))) {
                    for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                        synchronized (said) {
                            said.append(line);
                        }
                    }
                } catch (java.io.IOException ignored) {
                    // PowerShell has gone
                }
            }, "Dailies-Worker-Ask");
            reader.setDaemon(true);
            reader.start();
            if (!ask.waitFor(15, TimeUnit.SECONDS)) {
                ask.destroyForcibly();
                return null;
            }
            reader.join(2000);
            String line;
            synchronized (said) {
                line = said.toString().trim();
            }
            // A byte order mark from the console's encoding is not part of it
            return line.isEmpty() ? null : line.replace("﻿", "");
        } catch (Exception e) {
            Host.LOGGER.debug("Dailies: the command line could not be asked of Windows", e);
            return null;
        }
    }

    /** The memory the worker will take: what this game was allowed */
    private static long workerHeap() {
        return Runtime.getRuntime().maxMemory() == Long.MAX_VALUE ? 2L * 1024 * 1024 * 1024 : Runtime.getRuntime().maxMemory();
    }

    /** Free memory of the computer, or 0 when it cannot be told */
    private static long freeMemory() {
        try {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean system) {
                return system.getFreeMemorySize();
            }
        } catch (Throwable t) {
            // Not told: assume there is room
        }
        return 0;
    }

    private static String gigabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /** The worker's log, for whoever asks why it stopped */
    public static Path log(Path queueFile) {
        return queueFile.toAbsolutePath().resolveSibling("worker.log");
    }

}
