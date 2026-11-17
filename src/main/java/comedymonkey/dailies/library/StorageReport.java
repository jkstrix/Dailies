package comedymonkey.dailies.library;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How much room Dailies takes (specification REC-8): recordings, moments audio, exports and the bin, each per world,
 * and how much is left on the disk. Worked out by looking at the folders, so it is slow on a big library and is never
 * asked for on the client thread. Needs nothing of the game.
 */
public final class StorageReport {

    /** What one world takes, in bytes. The world "" holds whatever cannot be told apart by world */
    public static final class World {
        public final String name;
        public long recordings;
        public long audio;
        public long exports;
        public long bin;
        public int recordingCount;

        World(String name) {
            this.name = name;
        }

        public long total() {
            return this.recordings + this.audio + this.exports + this.bin;
        }
    }

    public final Map<String, World> worlds = new LinkedHashMap<>();
    public long recordings;
    public long audio;
    public long exports;
    public long bin;
    /** Free bytes on the disk the Dailies folder is on; -1 when that could not be told */
    public long free = -1;
    public long disk = -1;

    private StorageReport() {
    }

    public long total() {
        return this.recordings + this.audio + this.exports + this.bin;
    }

    /** The worlds, the one taking most room first, with "" (the rest) last */
    public List<World> byWorld() {
        List<World> list = new ArrayList<>(this.worlds.values());
        list.sort(Comparator.comparing((World world) -> world.name.isEmpty()).thenComparing(Comparator.comparingLong(World::total).reversed()).thenComparing(world -> world.name));
        return list;
    }

    private World world(String name) {
        return this.worlds.computeIfAbsent(name == null ? "" : name, World::new);
    }

    /**
     * Looks at the folders.
     *
     * @param dataDirectory the Dailies folder
     * @param recordings    every recording the index knows
     */
    public static StorageReport measure(Path dataDirectory, List<LibraryIndex.Entry> recordings, Bin bin, ExportLog exportLog) {
        StorageReport report = new StorageReport();
        for (LibraryIndex.Entry entry : recordings) {
            World world = report.world(entry.world);
            world.recordings += entry.size;
            world.recordingCount++;
            report.recordings += entry.size;
        }

        Path moments = dataDirectory.resolve("moments");
        if (Files.isDirectory(moments)) {
            try (DirectoryStream<Path> sessions = Files.newDirectoryStream(moments)) {
                for (Path session : sessions) {
                    Path audio = session.resolve("audio");
                    if (!Files.isDirectory(audio)) {
                        continue;
                    }
                    long size = Bin.sizeOf(audio);
                    report.world(sessionWorld(session)).audio += size;
                    report.audio += size;
                }
            } catch (IOException | RuntimeException e) {
                // Counted as far as it could be
            }
        }

        // Exports Dailies remembers making are put to their world; anything else in the exports folder to none
        Set<Path> counted = new HashSet<>();
        for (ExportLog.Exported exported : exportLog.all()) {
            if (exported.output.isBlank()) {
                continue;
            }
            Path file = dataDirectory.resolve(exported.output).normalize();
            if (counted.add(file) && Files.isRegularFile(file)) {
                long size = Bin.sizeOf(file);
                report.world(exported.world).exports += size;
                report.exports += size;
            }
        }
        Path exports = dataDirectory.resolve("exports");
        if (Files.isDirectory(exports)) {
            try (java.util.stream.Stream<Path> walk = Files.walk(exports)) {
                for (Path file : walk.filter(Files::isRegularFile).toList()) {
                    if (!counted.contains(file.normalize())) {
                        long size = Bin.sizeOf(file);
                        report.world("").exports += size;
                        report.exports += size;
                    }
                }
            } catch (IOException | RuntimeException e) {
                // Counted as far as it could be
            }
        }

        for (Bin.Item item : bin.list()) {
            report.world(item.world).bin += item.size;
            report.bin += item.size;
        }

        try {
            Files.createDirectories(dataDirectory);
            FileStore store = Files.getFileStore(dataDirectory);
            report.free = store.getUsableSpace();
            report.disk = store.getTotalSpace();
        } catch (IOException | RuntimeException e) {
            report.free = -1;
        }
        return report;
    }

    /** The world a moments session was made in, from its session.json */
    static String sessionWorld(Path session) {
        Path meta = session.resolve("session.json");
        if (!Files.isRegularFile(meta)) {
            return "";
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(meta, StandardCharsets.UTF_8));
            if (parsed.isJsonObject()) {
                JsonObject object = parsed.getAsJsonObject();
                JsonElement name = object.get("world_name");
                if (name != null && name.isJsonPrimitive()) {
                    return name.getAsString();
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            // Put with the rest
        }
        return "";
    }

    /** A number of bytes as a person reads it: 512 KB, 3.4 MB, 12.1 GB */
    public static String bytes(long bytes) {
        if (bytes < 0) {
            return "?";
        }
        if (bytes < 1024 * 1024) {
            return Math.max(bytes == 0 ? 0 : 1, Math.round(bytes / 1024.0)) + " KB";
        }
        if (bytes < Retention.GIGABYTE) {
            return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (double) Retention.GIGABYTE);
    }

}
