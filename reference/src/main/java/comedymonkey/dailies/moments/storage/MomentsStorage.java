package comedymonkey.dailies.moments.storage;

import com.google.gson.JsonObject;
import comedymonkey.dailies.Host;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The dailies/moments folder: one sub-folder per session plus the per-world "firsts". All file writes go
 * through a single background thread so the client thread never waits for the disk.
 */
public class MomentsStorage {

    private static final DateTimeFormatter SESSION_ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path root;
    private final ExecutorService writer;
    private final Map<String, MomentsSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, FirstsStore> firsts = new ConcurrentHashMap<>();

    public MomentsStorage(Path root) {
        this.root = root;
        this.writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Dailies-Moments-Writer");
            thread.setDaemon(true);
            return thread;
        });
        // Let queued lines reach the disk when the game closes
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            this.writer.shutdown();
            try {
                this.writer.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        }, "Dailies-Moments-Shutdown"));
    }

    public Path root() {
        return this.root;
    }

    void append(Path file, String line) {
        this.submit(() -> {
            Files.createDirectories(file.getParent());
            // Opened per line: the OS gets the data straight away, so a crash loses at most the line being written
            Files.writeString(file, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }, file);
    }

    void writeFile(Path file, String content) {
        this.submit(() -> {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }, file);
    }

    public void writeBytes(Path file, byte[] content) {
        this.submit(() -> {
            Files.createDirectories(file.getParent());
            Files.write(file, content);
        }, file);
    }

    @FunctionalInterface
    private interface IoTask {
        void run() throws IOException;
    }

    private void submit(IoTask task, Path file) {
        try {
            this.writer.execute(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    Host.LOGGER.error("Moments: unable to write {}", file, e);
                }
            });
        } catch (Exception e) {
            // Rejected during shutdown
        }
    }

    /** Blocks until everything queued so far has been written. Not for the client thread */
    public void flush() {
        try {
            this.writer.submit(() -> {}).get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    public MomentsSession createSession(JsonObject meta) {
        String base = LocalDateTime.now().format(SESSION_ID_FORMAT);
        String id = base;
        int suffix = 2;
        while (Files.exists(this.root.resolve(id)) || this.sessions.containsKey(id)) {
            id = base + "-" + suffix++;
        }
        meta.addProperty("id", id);
        MomentsSession session = new MomentsSession(this, id, this.root.resolve(id), meta);
        this.sessions.put(id, session);
        session.saveMeta();
        return session;
    }

    /** Session ids, newest first */
    public List<String> listSessionIds() {
        List<String> ids = new ArrayList<>(this.sessions.keySet());
        if (Files.isDirectory(this.root)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(this.root)) {
                for (Path path : stream) {
                    String name = path.getFileName().toString();
                    if (Files.isDirectory(path) && !name.equals("firsts") && !ids.contains(name)
                            && (Files.exists(path.resolve("session.json")) || Files.exists(path.resolve("events.jsonl")))) {
                        ids.add(name);
                    }
                }
            } catch (IOException e) {
                Host.LOGGER.error("Moments: unable to list sessions", e);
            }
        }
        // Ids start with the date and time, so this is newest first
        ids.sort(Comparator.reverseOrder());
        return ids;
    }

    @Nullable
    public MomentsSession getSession(String id) {
        MomentsSession session = this.sessions.get(id);
        if (session != null) {
            return session;
        }
        if (id.isEmpty() || id.contains("/") || id.contains("\\") || id.contains("..") || id.equals("firsts")) {
            return null;
        }
        Path dir = this.root.resolve(id);
        if (!Files.isDirectory(dir)) {
            return null;
        }
        synchronized (this) {
            session = this.sessions.get(id);
            if (session == null) {
                session = MomentsSession.load(this, id, dir);
                this.sessions.put(id, session);
            }
            return session;
        }
    }

    /**
     * Forgets a session held in memory, after its folder was moved to the bin, so that nothing is written into a
     * folder that is no longer there. Restoring it brings it back from its files the next time it is asked for.
     */
    public void release(String id) {
        this.flush();
        this.sessions.remove(id);
    }

    public FirstsStore getFirsts(String worldKey) {
        return this.firsts.computeIfAbsent(worldKey, key -> FirstsStore.load(this, this.root.resolve("firsts").resolve(key + ".json")));
    }

}
