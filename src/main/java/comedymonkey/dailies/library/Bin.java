package comedymonkey.dailies.library;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

/**
 * The bin (specification REC-9): {@code dailies/bin/}. Deleting a recording, a scene or a moments session moves it
 * here instead, where it stays for a number of days (the setting {@code binDays}, 30 unless changed) and can be put
 * back where it came from. Emptying the bin, or an item outliving its days, is the only way anything Dailies keeps is
 * deleted for good.
 * <p>
 * Each item has a folder of its own named by its id, holding the file or folder as it was, and {@code bin.json}
 * remembers what each one is and where it came from. The bin is outside the recordings folder, so the index of
 * recordings never sees a recording in it: a moment whose only recording is in the bin is not found until the
 * recording is restored. Nothing here needs the game; all of it is thread safe.
 */
public final class Bin {

    public static final String FILE_NAME = "bin.json";
    public static final long DAY_MILLIS = 24L * 60 * 60 * 1000;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    public enum Kind {
        RECORDING, SCENE, SESSION;

        public String id() {
            return this.name().toLowerCase(Locale.ROOT);
        }
    }

    /** One thing in the bin */
    public static final class Item {
        public String id = "";
        /** recording, scene or session */
        public String kind = "";
        public String name = "";
        public String world = "";
        /** Where it came from, relative to the Dailies folder with forward slashes; for a scene, the recording's id */
        public String from = "";
        /** The recording it is or belongs to, when there is one */
        public String recordingId = "";
        /** When it was put in the bin, in milliseconds since 1970 */
        public long binned = 0;
        public long size = 0;
        /** The file or folder kept, relative to the item's own folder */
        public String stored = "";

        public Kind kind() {
            try {
                return Kind.valueOf(this.kind.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                return Kind.RECORDING;
            }
        }

        /** When it is deleted for good, given how many days the bin keeps things */
        public long expires(int days) {
            return this.binned + Math.max(1, days) * DAY_MILLIS;
        }
    }

    private static final class Stored {
        List<Item> items = new ArrayList<>();
    }

    private final Path dataDirectory;
    private final Path folder;
    private final LongSupplier clock;
    private final List<Item> items = new ArrayList<>();
    private boolean loaded = false;

    /**
     * @param dataDirectory the Dailies folder: the bin is its sub-folder {@code bin}, and where things came from is said relative to it
     * @param clock         the time now, in milliseconds since 1970
     */
    public Bin(Path dataDirectory, LongSupplier clock) {
        this.dataDirectory = dataDirectory;
        this.folder = dataDirectory.resolve("bin");
        this.clock = clock;
    }

    public Path folder() {
        return this.folder;
    }

    private void load() {
        if (this.loaded) {
            return;
        }
        this.loaded = true;
        this.items.clear();
        Path file = this.folder.resolve(FILE_NAME);
        if (Files.isRegularFile(file)) {
            try {
                Stored stored = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Stored.class);
                if (stored != null && stored.items != null) {
                    for (Item item : stored.items) {
                        if (item != null && item.id != null && !item.id.isBlank() && item.stored != null) {
                            this.items.add(item);
                        }
                    }
                }
            } catch (IOException | RuntimeException unreadable) {
                // What is in the bin is still on disk; without the list it cannot be restored by name, but it is not lost
                this.items.clear();
            }
        }
        // An item whose files were taken out by hand is no longer in the bin
        this.items.removeIf(item -> !Files.exists(this.storedPath(item)));
    }

    private void save() throws IOException {
        Files.createDirectories(this.folder);
        Stored stored = new Stored();
        stored.items = new ArrayList<>(this.items);
        Path temporary = this.folder.resolve(FILE_NAME + ".writing");
        Files.writeString(temporary, GSON.toJson(stored), StandardCharsets.UTF_8);
        Files.move(temporary, this.folder.resolve(FILE_NAME), StandardCopyOption.REPLACE_EXISTING);
    }

    private Path storedPath(Item item) {
        return this.folder.resolve(item.id).resolve(item.stored);
    }

    private String relative(Path path) {
        return this.dataDirectory.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    /** Everything in the bin, the most recently put there first */
    public synchronized List<Item> list() {
        this.load();
        List<Item> list = new ArrayList<>(this.items);
        list.sort(Comparator.comparingLong((Item item) -> item.binned).reversed().thenComparing(item -> item.id));
        return list;
    }

    public synchronized @Nullable Item find(String id) {
        this.load();
        for (Item item : this.items) {
            if (item.id.equals(id)) {
                return item;
            }
        }
        return null;
    }

    /** Where an item's file or folder is kept while it is in the bin */
    public synchronized Path pathOf(Item item) {
        return this.storedPath(item);
    }

    /** How much room the bin takes, in bytes */
    public synchronized long size() {
        this.load();
        long total = 0;
        for (Item item : this.items) {
            total += item.size;
        }
        return total;
    }

    private Item fresh(Kind kind, String name, String world, String recordingId) {
        Item item = new Item();
        item.id = UUID.randomUUID().toString().substring(0, 13).replace("-", "");
        item.kind = kind.id();
        item.name = name == null ? "" : name;
        item.world = world == null ? "" : world;
        item.recordingId = recordingId == null ? "" : recordingId;
        item.binned = this.clock.getAsLong();
        return item;
    }

    /**
     * Moves a recording's file, or a moments session's folder, into the bin.
     *
     * @return what it is in the bin as
     */
    public synchronized Item put(Kind kind, Path original, String name, String world, String recordingId) throws IOException {
        if (kind == Kind.SCENE) {
            throw new IllegalArgumentException("A scene is put in the bin with putScene");
        }
        if (!Files.exists(original)) {
            throw new IOException("There is nothing at " + original.getFileName() + " to put in the bin");
        }
        this.load();
        Item item = this.fresh(kind, name, world, recordingId);
        item.from = this.relative(original);
        item.stored = original.getFileName().toString();
        item.size = sizeOf(original);
        Path target = this.storedPath(item);
        Files.createDirectories(target.getParent());
        Files.move(original, target);
        this.items.add(item);
        this.save();
        return item;
    }

    /** Keeps a scene taken out of a recording, as the editor state writes it, so that it can be put back */
    public synchronized Item putScene(String recordingId, String recordingName, String sceneName, String sceneJson) throws IOException {
        this.load();
        Item item = this.fresh(Kind.SCENE, sceneName, recordingName, recordingId);
        item.from = recordingId;
        item.stored = "scene.json";
        byte[] bytes = sceneJson.getBytes(StandardCharsets.UTF_8);
        item.size = bytes.length;
        Path target = this.storedPath(item);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
        this.items.add(item);
        this.save();
        return item;
    }

    /** The scene a scene item holds, as JSON */
    public synchronized String sceneJson(Item item) throws IOException {
        if (item.kind() != Kind.SCENE) {
            throw new IllegalArgumentException("'" + item.name + "' is not a scene");
        }
        return Files.readString(this.storedPath(item), StandardCharsets.UTF_8);
    }

    /**
     * Puts a recording or a session back where it came from. A recording whose old file name has been taken since
     * comes back under a free one beside it; a session folder whose name has been taken is refused, since the moments
     * of two sessions cannot share one folder.
     *
     * @return where it is now
     */
    public synchronized Path restore(String id) throws IOException {
        this.load();
        Item item = this.find(id);
        if (item == null) {
            throw new IllegalArgumentException("Nothing in the bin has the id '" + id + "'");
        }
        if (item.kind() == Kind.SCENE) {
            throw new IllegalArgumentException("A scene goes back into its recording's editor state; see forget()");
        }
        Path wanted = this.dataDirectory.resolve(item.from).normalize();
        if (!wanted.toAbsolutePath().startsWith(this.dataDirectory.toAbsolutePath().normalize())) {
            throw new IOException("'" + item.name + "' came from outside the Dailies folder and cannot be put back there");
        }
        if (Files.exists(wanted)) {
            if (item.kind() == Kind.SESSION) {
                throw new IOException("There is already a moments session called " + wanted.getFileName() + ". Move it away first");
            }
            wanted = freeName(wanted);
        }
        Files.createDirectories(wanted.toAbsolutePath().getParent());
        Files.move(this.storedPath(item), wanted);
        this.drop(item);
        return wanted;
    }

    /** Takes an item off the bin's list and deletes what is left of it: after a scene has been put back, or for good */
    public synchronized void forget(String id) throws IOException {
        this.load();
        Item item = this.find(id);
        if (item != null) {
            this.drop(item);
        }
    }

    private void drop(Item item) throws IOException {
        this.items.remove(item);
        deleteTree(this.folder.resolve(item.id));
        this.save();
    }

    /**
     * Deletes for good everything that has been in the bin for longer than it keeps things.
     *
     * @return what was deleted
     */
    public synchronized List<Item> expire(int days) throws IOException {
        this.load();
        long now = this.clock.getAsLong();
        List<Item> gone = new ArrayList<>();
        for (Item item : new ArrayList<>(this.items)) {
            if (now >= item.expires(days)) {
                gone.add(item);
                this.items.remove(item);
                deleteTree(this.folder.resolve(item.id));
            }
        }
        if (!gone.isEmpty()) {
            this.save();
        }
        return gone;
    }

    /** Deletes everything in the bin for good. Whoever calls this has asked the person first */
    public synchronized int empty() throws IOException {
        this.load();
        int count = this.items.size();
        for (Item item : this.items) {
            deleteTree(this.folder.resolve(item.id));
        }
        this.items.clear();
        this.save();
        return count;
    }

    /** "Name.dailies" taken: "Name (2).dailies", "Name (3).dailies" and so on */
    static Path freeName(Path wanted) {
        String file = wanted.getFileName().toString();
        int dot = file.indexOf('.');
        String stem = dot <= 0 ? file : file.substring(0, dot);
        String ending = dot <= 0 ? "" : file.substring(dot);
        for (int number = 2; ; number++) {
            Path candidate = wanted.resolveSibling(stem + " (" + number + ")" + ending);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }

    /** The size of a file, or of everything in a folder */
    public static long sizeOf(Path path) {
        if (Files.isRegularFile(path)) {
            try {
                return Files.size(path);
            } catch (IOException e) {
                return 0;
            }
        }
        if (!Files.isDirectory(path)) {
            return 0;
        }
        long[] total = {0};
        try (Stream<Path> walk = Files.walk(path)) {
            walk.filter(Files::isRegularFile).forEach(file -> {
                try {
                    total[0] += Files.size(file);
                } catch (IOException ignored) {
                    // Gone while being counted
                }
            });
        } catch (IOException | RuntimeException e) {
            // Counted as far as it could be
        }
        return total[0];
    }

    static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException problem) throws IOException {
                if (problem != null) {
                    throw problem;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

}
