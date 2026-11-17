package comedymonkey.dailies.library;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import comedymonkey.dailies.file.RecordingFile;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.file.Spans;
import comedymonkey.dailies.file.TimeIndex;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The index of the recordings folder (specification FILE-12): {@code index.json}, which lists every recording's id,
 * file, size, modified time and spans, and what a list of recordings shows. It answers "which files hold this moment,
 * and at what tick" without opening every file.
 * <p>
 * The files are the truth and the index is only a memory of them. Every answer is given after a look at the folder:
 * a file whose size or modified time is not what the index remembers is read again, a file that has gone is
 * forgotten, and one that has appeared (or was renamed or moved by hand) is read. A missing or unreadable index is
 * built again from the files' own descriptions. Nothing here needs the game; all of it is thread safe.
 */
public final class LibraryIndex {

    public static final String FILE_NAME = "index.json";
    private static final int VERSION = 1;
    /** How deep under the folder recordings are looked for */
    private static final int DEPTH = 4;
    /** Looking at the folder again sooner than this after the last look is skipped, unless asked for outright */
    private static final long LOOK_EVERY_MILLIS = 400;
    /** A real time counts as inside a recording when a tick of it is no further from it than this */
    public static final long TIME_TOLERANCE_MILLIS = 1500;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    /** What the index remembers of one recording */
    public static final class Entry {
        public String id = "";
        /** Relative to the recordings folder, with forward slashes */
        public String file = "";
        public long size = 0;
        public long modified = 0;
        public String name = "";
        public String world = "";
        public String recorder = "";
        public List<String> players = new ArrayList<>();
        /** When the footage was recorded, in milliseconds since 1970 */
        public long created = 0;
        /** When it was made by combining or trimming; 0 for an original */
        public long edited = 0;
        public int totalTicks = 0;
        public int markers = 0;
        public String gameVersion = "";
        public boolean recovered = false;
        /** Empty for an original, which stands for itself */
        public List<RecordingManifest.SpanEntry> spans = new ArrayList<>();
        public long realStart = 0;
        public long realEnd = 0;
        /** For an edited recording: how long each original it is made of was, by id, so that is known once the original is gone */
        public Map<String, Integer> sourceTicks = new HashMap<>();

        public boolean isEdited() {
            return this.spans != null && !this.spans.isEmpty();
        }

        public List<Spans.Span> sourceMap() {
            List<Spans.Span> list = new ArrayList<>();
            if (this.spans != null) {
                for (RecordingManifest.SpanEntry span : this.spans) {
                    list.add(span.span());
                }
            }
            return Spans.effective(list, this.id, this.totalTicks);
        }

        /** The newest of when it was recorded and when it was edited: what "the newest recording" goes by */
        public long newest() {
            return Math.max(this.created, this.edited);
        }
    }

    /** A recording that holds a moment, and where */
    public record Hit(Path file, Entry entry, int tick, boolean original) {
    }

    /** A recording that holds some or all of a clip */
    public record ClipHit(Path file, Entry entry, Spans.Clip clip, boolean original) {
    }

    private static final class Stored {
        int version = VERSION;
        List<Entry> recordings = new ArrayList<>();
    }

    private final Path folder;
    /** By relative file */
    private final Map<String, Entry> entries = new HashMap<>();
    private boolean loaded = false;
    private long lastLook = 0;

    public LibraryIndex(Path folder) {
        this.folder = folder;
    }

    public Path folder() {
        return this.folder;
    }

    public Path indexFile() {
        return this.folder.resolve(FILE_NAME);
    }

    private String relative(Path file) {
        return this.folder.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private Path absolute(Entry entry) {
        return this.folder.resolve(entry.file);
    }

    private void load() {
        if (this.loaded) {
            return;
        }
        this.loaded = true;
        this.entries.clear();
        Path file = this.indexFile();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            Stored stored = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Stored.class);
            if (stored != null && stored.recordings != null && stored.version <= VERSION) {
                for (Entry entry : stored.recordings) {
                    if (entry != null && entry.id != null && !entry.id.isEmpty() && entry.file != null && !entry.file.isEmpty()) {
                        if (entry.players == null) {
                            entry.players = new ArrayList<>();
                        }
                        if (entry.spans == null) {
                            entry.spans = new ArrayList<>();
                        }
                        if (entry.sourceTicks == null) {
                            entry.sourceTicks = new HashMap<>();
                        }
                        this.entries.put(entry.file, entry);
                    }
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            // Built again from the files below
            this.entries.clear();
        }
    }

    private void save() {
        if (comedymonkey.dailies.export.Worker.active()) {
            // The background export game keeps its index in memory: the game being played owns the file (EXP-13)
            return;
        }
        Stored stored = new Stored();
        stored.recordings = new ArrayList<>(this.entries.values());
        stored.recordings.sort(Comparator.comparing((Entry entry) -> entry.file));
        try {
            Files.createDirectories(this.folder);
            Path temporary = this.folder.resolve(FILE_NAME + ".writing");
            Files.writeString(temporary, GSON.toJson(stored), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, this.indexFile(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException notAtomic) {
                Files.move(temporary, this.indexFile(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // The index is only a memory of the files: the next look builds it again
        }
    }

    private static @Nullable Entry read(Path file, String relative) {
        try (RecordingFile recording = RecordingFile.open(file)) {
            RecordingManifest manifest = recording.manifest();
            Entry entry = new Entry();
            entry.id = manifest.id;
            entry.file = relative;
            entry.size = Files.size(file);
            entry.modified = Files.getLastModifiedTime(file).toMillis();
            entry.name = manifest.name;
            entry.world = manifest.worldName;
            entry.recorder = manifest.recorderName;
            entry.players = new ArrayList<>(manifest.players);
            entry.created = manifest.created;
            entry.edited = manifest.edited;
            entry.totalTicks = manifest.totalTicks;
            entry.markers = manifest.markers.size();
            entry.gameVersion = manifest.gameVersion;
            entry.recovered = manifest.recovered;
            entry.spans = new ArrayList<>(manifest.spans);
            for (RecordingManifest.SourceEntry source : manifest.sources) {
                entry.sourceTicks.put(source.id, source.totalTicks);
            }
            entry.realStart = manifest.realStart;
            entry.realEnd = manifest.realEnd;
            if (entry.realStart == 0 || entry.realEnd == 0) {
                // A recording repaired after a crash never wrote them. Each segment's first time is known, and a segment
                // is never longer than a few minutes, so that much is allowed after the last one
                long first = Long.MAX_VALUE;
                long last = 0;
                for (RecordingManifest.SegmentTime time : recording.segmentTimes()) {
                    first = Math.min(first, time.real);
                    last = Math.max(last, time.real);
                }
                if (last > 0) {
                    entry.realStart = first;
                    entry.realEnd = last + 10 * 60 * 1000L;
                }
            }
            return entry;
        } catch (IOException | RuntimeException unreadable) {
            // Not a recording, or too damaged to describe itself: left out
            return null;
        }
    }

    /** Looks at the folder and puts the index right, unless that was done a moment ago */
    public synchronized void refresh() {
        this.refresh(false);
    }

    /**
     * Looks at the folder and puts the index right: the start-up check, and what every question does first.
     *
     * @param now whether to look even though a look has only just been taken
     */
    public synchronized void refresh(boolean now) {
        this.load();
        boolean indexMissing = !Files.isRegularFile(this.indexFile());
        if (!now && !indexMissing && System.currentTimeMillis() - this.lastLook < LOOK_EVERY_MILLIS) {
            return;
        }
        boolean changed = indexMissing;
        Set<String> present = new HashSet<>();
        if (Files.isDirectory(this.folder)) {
            List<Path> files;
            try (Stream<Path> walk = Files.walk(this.folder, DEPTH)) {
                files = walk.filter(path -> path.getFileName().toString().endsWith(RecordingFile.EXTENSION) && Files.isRegularFile(path)).toList();
            } catch (IOException | RuntimeException e) {
                return;
            }
            for (Path file : files) {
                String relative = this.relative(file);
                Entry known = this.entries.get(relative);
                try {
                    if (known != null && known.size == Files.size(file) && known.modified == Files.getLastModifiedTime(file).toMillis()) {
                        present.add(relative);
                        continue;
                    }
                } catch (IOException gone) {
                    continue;
                }
                Entry fresh = read(file, relative);
                changed = true;
                if (fresh != null) {
                    this.entries.put(relative, fresh);
                    present.add(relative);
                }
            }
        }
        changed |= this.entries.keySet().retainAll(present);
        if (changed) {
            this.save();
        }
        this.lastLook = System.currentTimeMillis();
    }

    /** A recording was saved, made, renamed or moved to this file by the mod itself: read it now */
    public synchronized void update(Path file) {
        this.load();
        String relative = this.relative(file);
        Entry fresh = Files.isRegularFile(file) ? read(file, relative) : null;
        if (fresh != null) {
            this.entries.put(relative, fresh);
        } else {
            this.entries.remove(relative);
        }
        this.save();
    }

    /** A recording's file was deleted or moved away by the mod itself */
    public synchronized void forget(Path file) {
        this.load();
        if (this.entries.remove(this.relative(file)) != null) {
            this.save();
        }
    }

    /** Every recording, the newest footage first */
    public synchronized List<Entry> list() {
        this.refresh();
        List<Entry> list = new ArrayList<>(this.entries.values());
        list.sort(Comparator.comparingLong((Entry entry) -> entry.created).reversed().thenComparing(entry -> entry.file));
        return list;
    }

    public synchronized Path fileOf(Entry entry) {
        return this.absolute(entry);
    }

    /** The recording with this id, wherever in the folder it is and whatever it is called now (FILE-3), or null */
    public synchronized @Nullable Path findById(String id) {
        this.refresh();
        for (Entry entry : this.entries.values()) {
            if (entry.id.equals(id)) {
                return this.absolute(entry);
            }
        }
        return null;
    }

    public synchronized @Nullable Entry entryOf(Path file) {
        this.refresh();
        return this.entries.get(this.relative(file));
    }

    /** Whether any recording here is, or is made from, the recording with this id */
    public synchronized boolean knows(String id) {
        this.refresh();
        for (Entry entry : this.entries.values()) {
            if (entry.id.equals(id)) {
                return true;
            }
            for (RecordingManifest.SpanEntry span : entry.spans) {
                if (span.source.equals(id)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Every recording that holds a moment of an original, with the tick it is at there: the original first, if it
     * still exists, then the others from the newest (specification FILE-13). Empty when it was cut everywhere.
     */
    public synchronized List<Hit> locate(String id, int tick) {
        this.refresh();
        List<Hit> hits = this.locateNow(id, tick);
        if (hits.isEmpty() || hits.stream().anyMatch(hit -> !Files.isRegularFile(hit.file()))) {
            // Before saying it is nowhere, or pointing at a file that has just gone: look at the folder as it is this instant
            this.refresh(true);
            hits = this.locateNow(id, tick);
        }
        return hits;
    }

    private List<Hit> locateNow(String id, int tick) {
        List<Hit> hits = new ArrayList<>();
        for (Entry entry : this.entries.values()) {
            boolean original = entry.id.equals(id) && !entry.isEdited();
            int here;
            if (original) {
                // The last tick of a recording is a moment of it too
                here = tick >= 0 && tick <= entry.totalTicks ? tick : -1;
            } else {
                here = Spans.toHere(entry.sourceMap(), id, tick);
            }
            if (here >= 0) {
                hits.add(new Hit(this.absolute(entry), entry, here, original));
            }
        }
        hits.sort(order(hit -> hit.original(), hit -> hit.entry()));
        return hits;
    }

    /** Every recording that holds some of a clip recorded as ticks from..to of an original, in the order of {@link #locate} */
    public synchronized List<ClipHit> locateClip(String id, int from, int to) {
        this.refresh();
        List<ClipHit> hits = this.locateClipNow(id, from, to);
        if (hits.isEmpty() || hits.stream().anyMatch(hit -> !Files.isRegularFile(hit.file()))) {
            this.refresh(true);
            hits = this.locateClipNow(id, from, to);
        }
        return hits;
    }

    /** How long the original with this id is, from its own file or from what a recording made from it remembers; -1 when nobody knows */
    private int ticksOf(String id) {
        int known = -1;
        for (Entry entry : this.entries.values()) {
            if (entry.id.equals(id) && !entry.isEdited()) {
                return entry.totalTicks;
            }
            Integer remembered = entry.sourceTicks == null ? null : entry.sourceTicks.get(id);
            if (remembered != null && remembered > 0) {
                known = remembered;
            }
        }
        return known;
    }

    private List<ClipHit> locateClipNow(String id, int from, int to) {
        List<ClipHit> hits = new ArrayList<>();
        // A clip's ends are worked out from the clock, and may run past the end of its recording. What was never
        // recorded was not cut, so the clip is held to the recording's length before anything is counted as missing
        int length = this.ticksOf(id);
        if (length > 0) {
            to = Math.min(to, length);
            from = Math.min(from, length - 1);
        }
        for (Entry entry : this.entries.values()) {
            Spans.Clip clip = Spans.clip(entry.sourceMap(), id, from, Math.max(from + 1, to));
            if (clip.present()) {
                hits.add(new ClipHit(this.absolute(entry), entry, clip, entry.id.equals(id) && !entry.isEdited()));
            }
        }
        hits.sort(order(hit -> hit.original(), hit -> hit.entry()));
        return hits;
    }

    private static <T> Comparator<T> order(java.util.function.Predicate<T> original, java.util.function.Function<T, Entry> entry) {
        return Comparator.comparing((T hit) -> !original.test(hit))
            .thenComparing(Comparator.comparingLong((T hit) -> entry.apply(hit).newest()).reversed())
            .thenComparing(hit -> entry.apply(hit).file);
    }

    /**
     * The fallback for a moment whose recording is not known (specification FILE-16): every recording of the world
     * whose time index holds the moment's real time, with the tick nearest to it. Originals first, then the newest.
     * This opens the recordings whose stretch of real time takes the moment in, so it is slower than {@link #locate}.
     *
     * @param world the world or server the moment happened in, or null when that is not known either
     */
    public List<Hit> locateByTime(long realTime, @Nullable String world) {
        List<Entry> candidates = new ArrayList<>();
        synchronized (this) {
            this.refresh();
            for (Entry entry : this.entries.values()) {
                boolean inside = entry.realStart > 0 && realTime >= entry.realStart - TIME_TOLERANCE_MILLIS && realTime <= entry.realEnd + TIME_TOLERANCE_MILLIS;
                boolean sameWorld = world == null || world.isBlank() || entry.world.equals(world) || entry.isEdited() && entry.world.contains(world);
                if (inside && sameWorld) {
                    candidates.add(entry);
                }
            }
        }
        List<Hit> hits = new ArrayList<>();
        for (Entry entry : candidates) {
            Path file = this.folder.resolve(entry.file);
            try (RecordingFile recording = RecordingFile.open(file)) {
                TimeIndex.Nearest nearest = recording.tickNearest(realTime);
                if (nearest != null && nearest.errorMillis() <= TIME_TOLERANCE_MILLIS) {
                    hits.add(new Hit(file, entry, nearest.tick(), !entry.isEdited()));
                }
            } catch (IOException | RuntimeException unreadable) {
                // Not this one, then
            }
        }
        hits.sort(order(hit -> hit.original(), hit -> hit.entry()));
        return hits;
    }

}
