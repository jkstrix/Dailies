package comedymonkey.dailies.library;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.edit.EditState;
import comedymonkey.dailies.file.Assembler;
import comedymonkey.dailies.file.RecordingFile;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.file.Spans;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The recordings a person has, and what can be done with them as files: list, combine, trim, rename, move, delete
 * (specification FILE-9 to FILE-14). This is what a screen or a tool calls.
 * <p>
 * Combining and trimming make a new recording and leave their sources alone. They run on a thread of their own,
 * report how far they have got through a {@link LibraryJob} and can be cancelled. A trim whose cut does not fall on
 * the start of a segment needs the world described at the cut, and only playback can do that: such a trim opens the
 * recording in the game, jumps to each cut and has the world written down there, and the job says so.
 */
public final class Library {

    /** While a new recording is being written it has this ending, so nothing takes it for a recording */
    private static final String WORKING_EXTENSION = ".assembling";
    private static final int BOUNDARY_COLOUR = 0xFFFFFF;
    private static final int CUT_COLOUR = 0xFFAA00;

    private static volatile LibraryIndex index = null;
    private static final Map<String, LibraryJob> JOBS = new ConcurrentHashMap<>();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Dailies-Library");
        thread.setDaemon(true);
        return thread;
    });

    private Library() {
    }

    /** At start-up: the index is checked against the files in the background, and built if it is not there (FILE-12) */
    public static void init() {
        Thread check = new Thread(() -> {
            try {
                index().refresh(true);
            } catch (Throwable t) {
                Host.LOGGER.warn("Dailies: the index of recordings could not be checked at start-up. It is checked again when it is next needed", t);
            }
        }, "Dailies-Library-Check");
        check.setDaemon(true);
        check.start();
    }

    public static LibraryIndex index() {
        LibraryIndex known = index;
        if (known == null) {
            synchronized (Library.class) {
                known = index;
                if (known == null) {
                    known = new LibraryIndex(Recorder.folder());
                    index = known;
                }
            }
        }
        return known;
    }

    /** A recording has just been saved, or changed, at this file. Any thread; never throws */
    public static void changed(Path file) {
        try {
            index().update(file);
        } catch (Throwable t) {
            Host.LOGGER.warn("Dailies: the index of recordings could not take in {}. It puts itself right when it is next needed", file.getFileName(), t);
        }
    }

    /** Every recording in the folder, the newest footage first. Looks at the folder: not for the client thread where it can be avoided */
    public static List<LibraryIndex.Entry> list() {
        return index().list();
    }

    public static Path fileOf(LibraryIndex.Entry entry) {
        return index().fileOf(entry);
    }

    /** A recording by path (absolute, or relative to the folder), by file name with or without its extension, by its name or by its id */
    public static @Nullable Path find(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Path folder = Recorder.folder();
        try {
            Path given = Path.of(text);
            Path file = given.isAbsolute() ? given : folder.resolve(given);
            if (Files.isRegularFile(file)) {
                return file;
            }
            file = folder.resolve(text + RecordingFile.EXTENSION);
            if (Files.isRegularFile(file)) {
                return file;
            }
        } catch (java.nio.file.InvalidPathException notAPath) {
            // A name, then
        }
        for (LibraryIndex.Entry entry : list()) {
            if (entry.id.equals(text) || entry.name.equalsIgnoreCase(text)) {
                return fileOf(entry);
            }
        }
        return null;
    }

    public static @Nullable LibraryJob job(String id) {
        return JOBS.get(id);
    }

    public static List<LibraryJob> jobs() {
        return new ArrayList<>(JOBS.values());
    }

    // --- Rename, move, delete ---

    private static void requireNotOpen(Path file) {
        RecordingFile open = Playback.active() ? Playback.file() : null;
        if (open != null && open.file().toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize())) {
            throw new IllegalStateException("That recording is open. Close it first");
        }
    }

    /** Gives a recording another name. Its file keeps its own name, and everything that refers to it by id still finds it */
    public static void rename(Path file, String name) throws IOException {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A recording needs a name");
        }
        requireNotOpen(file);
        try (RecordingFile recording = RecordingFile.openForEditing(file)) {
            recording.rename(name.trim());
        }
        changed(file);
    }

    /**
     * Moves a recording's file, or gives the file another name.
     *
     * @param target the new file, absolute or relative to the recordings folder; the extension is added when left out
     * @return where it is now
     */
    public static Path move(Path file, Path target) throws IOException {
        requireNotOpen(file);
        Path wanted = target.isAbsolute() ? target : Recorder.folder().resolve(target);
        if (!wanted.getFileName().toString().endsWith(RecordingFile.EXTENSION)) {
            wanted = wanted.resolveSibling(wanted.getFileName() + RecordingFile.EXTENSION);
        }
        if (Files.exists(wanted)) {
            throw new IOException("There is already a file called " + wanted.getFileName());
        }
        Files.createDirectories(wanted.toAbsolutePath().getParent());
        Files.move(file, wanted);
        index().forget(file);
        changed(wanted);
        return wanted;
    }

    /** Deletes a recording's file. Moments recorded in it are still found in any combined or trimmed recording that holds its ticks */
    public static void delete(Path file) throws IOException {
        requireNotOpen(file);
        Files.delete(file);
        index().forget(file);
    }

    // --- Combine (FILE-9) ---

    /**
     * Joins recordings, in the order given, into a new one. Each keeps its own world: where one ends and the next
     * begins the world is rebuilt from the next one's opening snapshot, so recordings from different worlds or
     * dimensions join cleanly. The join is a marked point on the timeline and adds no ticks. Recordings from
     * different game versions are refused; different mods are allowed and listed in the job's notes.
     *
     * @param progress told of every change, on the job's own thread; may be null
     */
    public static LibraryJob combine(List<Path> sources, String name, @Nullable Consumer<LibraryJob> progress) {
        if (sources.size() < 2) {
            throw new IllegalArgumentException("Combining needs at least two recordings");
        }
        LibraryJob job = start("combine", name, progress);
        List<Path> files = List.copyOf(sources);
        WORKER.execute(() -> run(job, () -> combineNow(job, files)));
        return job;
    }

    private interface Work {
        Path run() throws Exception;
    }

    private static LibraryJob start(String kind, String name, @Nullable Consumer<LibraryJob> progress) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("The new recording needs a name");
        }
        LibraryJob job = new LibraryJob(UUID.randomUUID().toString().substring(0, 8), kind, name.trim());
        if (progress != null) {
            job.listen(progress);
        }
        JOBS.put(job.id, job);
        return job;
    }

    private static void run(LibraryJob job, Work work) {
        try {
            Path file = work.run();
            RecordingManifest made = RecordingFile.readManifest(file);
            Host.LOGGER.info("Dailies: {} made {} ({} ticks)", job.kind, file.getFileName(), made.totalTicks);
            job.finished(file, made.id, made.totalTicks);
        } catch (Throwable t) {
            Throwable cause = t instanceof ExecutionException && t.getCause() != null ? t.getCause() : t;
            boolean cancelled = cause instanceof Assembler.Cancelled || job.cancelAsked();
            if (!cancelled && !(cause instanceof IllegalStateException) && !(cause instanceof IllegalArgumentException)) {
                Host.LOGGER.error("Dailies: a {} failed. The sources are untouched", job.kind, cause);
            }
            job.failed(cause, cancelled);
        }
    }

    private static Assembler.Progress progress(LibraryJob job, double from, double to, String stage) {
        return new Assembler.Progress() {
            @Override
            public void report(double fraction) {
                job.at(from + (to - from) * fraction, stage);
            }

            @Override
            public boolean cancelled() {
                return job.cancelAsked();
            }
        };
    }

    private static Path combineNow(LibraryJob job, List<Path> files) throws Exception {
        List<RecordingFile> open = new ArrayList<>();
        try {
            job.at(0.0, "Reading the recordings");
            for (Path file : files) {
                open.add(RecordingFile.open(file));
            }
            List<RecordingManifest> manifests = new ArrayList<>();
            for (RecordingFile recording : open) {
                RecordingManifest manifest = recording.manifest();
                if (manifest.totalTicks <= 0) {
                    throw new IllegalStateException("'" + manifest.name + "' is empty and cannot be combined");
                }
                manifests.add(manifest);
            }
            RecordingManifest first = manifests.get(0);
            for (RecordingManifest other : manifests) {
                if (!other.gameVersion.equals(first.gameVersion) || other.dataVersion != first.dataVersion || other.protocolVersion != first.protocolVersion) {
                    throw new IllegalStateException("'" + other.name + "' was recorded with Minecraft " + other.gameVersion + " and '" + first.name + "' with Minecraft " + first.gameVersion
                        + ". Recordings from different game versions cannot be combined");
                }
            }
            modDifferences(manifests).forEach(job::note);

            RecordingManifest manifest = new RecordingManifest();
            manifest.id = UUID.randomUUID().toString();
            manifest.name = job.name;
            manifest.edited = System.currentTimeMillis();
            manifest.created = first.created;
            manifest.gameVersion = first.gameVersion;
            manifest.dataVersion = first.dataVersion;
            manifest.protocolVersion = first.protocolVersion;
            manifest.modVersion = Host.get().modVersion();
            manifest.recorderName = first.recorderName;
            manifest.recorderId = first.recorderId;
            manifest.singleplayer = first.singleplayer;

            List<String> worlds = new ArrayList<>();
            Map<String, String> mods = new LinkedHashMap<>();
            java.util.Set<String> players = new java.util.TreeSet<>();
            List<List<Spans.Span>> maps = new ArrayList<>();
            List<Integer> lengths = new ArrayList<>();
            List<String> names = new ArrayList<>();
            List<EditState> states = new ArrayList<>();
            List<Assembler.Piece> pieces = new ArrayList<>();
            Map<String, List<RecordingManifest.ModEntry>> modsOfSource = new java.util.HashMap<>();
            int offset = 0;
            for (int i = 0; i < open.size(); i++) {
                RecordingManifest source = manifests.get(i);
                if (!source.worldName.isBlank() && !worlds.contains(source.worldName)) {
                    worlds.add(source.worldName);
                }
                source.mods.forEach(mod -> mods.putIfAbsent(mod.id, mod.version));
                players.addAll(source.players);
                manifest.created = Math.min(manifest.created, source.created);
                if (source.realStart > 0) {
                    manifest.realStart = manifest.realStart == 0 ? source.realStart : Math.min(manifest.realStart, source.realStart);
                    manifest.realEnd = Math.max(manifest.realEnd, source.realEnd);
                }
                addSources(manifest, source, modsOfSource);
                if (offset > 0) {
                    // Where one source ends and the next begins: a point on the timeline, not a stretch of it
                    RecordingManifest.Marker boundary = new RecordingManifest.Marker();
                    boundary.tick = offset;
                    boundary.colour = BOUNDARY_COLOUR;
                    boundary.description = source.name;
                    boundary.kind = RecordingManifest.Marker.BOUNDARY;
                    manifest.markers.add(boundary);
                }
                for (RecordingManifest.Marker marker : source.markers) {
                    marker.tick += offset;
                    manifest.markers.add(marker);
                }
                maps.add(source.sourceMap());
                lengths.add(source.totalTicks);
                names.add(source.name);
                byte[] edit = open.get(i).editState();
                states.add(edit == null ? null : readEdit(edit));
                pieces.add(new Assembler.Piece(open.get(i), 0, source.totalTicks, null));
                offset += source.totalTicks;
            }
            manifest.worldName = String.join(" + ", worlds);
            mods.forEach((id, version) -> manifest.mods.add(new RecordingManifest.ModEntry(id, version)));
            noteModsPerSource(manifest, modsOfSource);
            manifest.players = new ArrayList<>(players);
            for (Spans.Span span : Spans.join(maps, lengths)) {
                manifest.spans.add(new RecordingManifest.SpanEntry(span));
            }
            manifest.markers.sort(java.util.Comparator.comparingInt((RecordingManifest.Marker marker) -> marker.tick));
            EditState joined = EditRemap.joined(states, lengths, names);
            return write(job, manifest, pieces, open.get(0).preview(), joined == null ? null : joined.toBytes(), 0.02);
        } finally {
            for (RecordingFile recording : open) {
                try {
                    recording.close();
                } catch (IOException ignored) {
                    // Only read from
                }
            }
        }
    }

    private static @Nullable EditState readEdit(byte[] bytes) {
        try {
            return EditState.fromBytes(bytes);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /** The originals a recording is made of are the originals of whatever is made from it */
    private static void addSources(RecordingManifest target, RecordingManifest source, Map<String, List<RecordingManifest.ModEntry>> modsOfSource) {
        List<RecordingManifest.SourceEntry> entries = new ArrayList<>();
        if (source.isEdited()) {
            entries.addAll(source.sources);
            for (RecordingManifest.SourceEntry entry : source.sources) {
                modsOfSource.putIfAbsent(entry.id, entry.mods(source.mods));
            }
        } else {
            modsOfSource.putIfAbsent(source.id, new ArrayList<>(source.mods));
            RecordingManifest.SourceEntry self = new RecordingManifest.SourceEntry();
            self.id = source.id;
            self.name = source.name;
            self.worldName = source.worldName;
            self.created = source.created;
            self.totalTicks = source.totalTicks;
            entries.add(self);
        }
        for (RecordingManifest.SourceEntry entry : entries) {
            if (target.sources.stream().noneMatch(known -> known.id.equals(entry.id))) {
                target.sources.add(entry);
            }
        }
    }

    /**
     * Writes into each source of a new recording how the mods it was recorded with differ from the new header's list,
     * so that the difference is stored with the spans it applies to and not only told once to whoever made the edit
     */
    static void noteModsPerSource(RecordingManifest manifest, Map<String, List<RecordingManifest.ModEntry>> modsOfSource) {
        List<RecordingManifest.SourceEntry> entries = new ArrayList<>();
        for (RecordingManifest.SourceEntry known : manifest.sources) {
            RecordingManifest.SourceEntry entry = new RecordingManifest.SourceEntry();
            entry.id = known.id;
            entry.name = known.name;
            entry.worldName = known.worldName;
            entry.created = known.created;
            entry.totalTicks = known.totalTicks;
            List<RecordingManifest.ModEntry> mods = modsOfSource.get(known.id);
            entry.modDifferences = mods == null ? new ArrayList<>() : RecordingManifest.differences(mods, manifest.mods);
            entries.add(entry);
        }
        manifest.sources = entries;
    }

    /** How the mods of the later recordings differ from those of the first, one line a difference */
    static List<String> modDifferences(List<RecordingManifest> manifests) {
        List<String> lines = new ArrayList<>();
        RecordingManifest first = manifests.get(0);
        Map<String, String> base = new TreeMap<>();
        first.mods.forEach(mod -> base.put(mod.id, mod.version));
        for (RecordingManifest other : manifests.subList(1, manifests.size())) {
            Map<String, String> theirs = new TreeMap<>();
            other.mods.forEach(mod -> theirs.put(mod.id, mod.version));
            for (Map.Entry<String, String> mod : base.entrySet()) {
                String version = theirs.get(mod.getKey());
                if (version == null) {
                    lines.add(mod.getKey() + " " + mod.getValue() + " is in '" + first.name + "' and not in '" + other.name + "'");
                } else if (!version.equals(mod.getValue())) {
                    lines.add(mod.getKey() + " is " + mod.getValue() + " in '" + first.name + "' and " + version + " in '" + other.name + "'");
                }
            }
            for (Map.Entry<String, String> mod : theirs.entrySet()) {
                if (!base.containsKey(mod.getKey())) {
                    lines.add(mod.getKey() + " " + mod.getValue() + " is in '" + other.name + "' and not in '" + first.name + "'");
                }
            }
        }
        return lines;
    }

    private static Path write(LibraryJob job, RecordingManifest manifest, List<Assembler.Piece> pieces, @Nullable byte[] preview, @Nullable byte[] edit, double from) throws IOException {
        Path folder = Recorder.folder();
        Files.createDirectories(folder);
        Path working = folder.resolve(manifest.id + WORKING_EXTENSION);
        Files.deleteIfExists(working);
        Assembler.write(working, manifest, pieces, preview, edit, progress(job, from, 0.99, "Writing the new recording"));
        Path target = Recorder.freeFile(folder, job.name);
        Files.move(working, target);
        changed(target);
        return target;
    }

    // --- Trim (FILE-10) ---

    /**
     * Makes a new recording from the ranges of a recording that are to be kept, in order; everything else is cut.
     * A range that starts where a segment starts is copied. One that starts anywhere else gets a fresh snapshot of the
     * world at that tick, which playback writes: the recording is opened in the game for that, and the job says so
     * ({@link LibraryJob#throughGame}).
     *
     * @param keep       ranges of ticks to keep; overlapping ones are joined and they are put in order
     * @param leaveWorld whether the world being played right now may be left, should the trim have to go through the game
     * @param progress   told of every change; may be null
     */
    public static LibraryJob trim(Path source, List<Spans.Range> keep, String name, boolean leaveWorld, @Nullable Consumer<LibraryJob> progress) {
        LibraryJob job = start("trim", name, progress);
        List<Spans.Range> wanted = List.copyOf(keep);
        WORKER.execute(() -> run(job, () -> trimNow(job, source, wanted, leaveWorld)));
        return job;
    }

    private static Path trimNow(LibraryJob job, Path file, List<Spans.Range> wanted, boolean leaveWorld) throws Exception {
        job.at(0.0, "Reading the recording");
        try (RecordingFile source = RecordingFile.open(file)) {
            RecordingManifest original = source.manifest();
            List<Spans.Range> keep = Spans.normalise(wanted, original.totalTicks);
            if (keep.isEmpty()) {
                throw new IllegalArgumentException("Nothing would be left: the ranges to keep hold no ticks of this recording, which is " + original.totalTicks + " ticks long");
            }
            if (keep.size() == 1 && keep.get(0).from() == 0 && keep.get(0).to() == original.totalTicks) {
                throw new IllegalArgumentException("Nothing would be cut: the ranges to keep are the whole recording");
            }

            List<Integer> inside = new ArrayList<>();
            for (Spans.Range range : keep) {
                if (Assembler.needsSnapshot(source, range.from())) {
                    inside.add(range.from());
                }
            }
            Map<Integer, Assembler.Snapshot> snapshots = Map.of();
            double written = 0.02;
            if (!inside.isEmpty()) {
                job.usesGame();
                snapshots = describe(job, file, original.id, inside, leaveWorld, 0.4);
                written = 0.4;
            }

            RecordingManifest manifest = source.manifest();
            manifest.id = UUID.randomUUID().toString();
            manifest.name = job.name;
            manifest.edited = System.currentTimeMillis();
            manifest.modVersion = Host.get().modVersion();
            manifest.recovered = false;
            manifest.spans = new ArrayList<>();
            for (Spans.Span span : Spans.slice(original.sourceMap(), keep)) {
                manifest.spans.add(new RecordingManifest.SpanEntry(span));
            }
            manifest.sources = new ArrayList<>();
            Map<String, List<RecordingManifest.ModEntry>> modsOfSource = new java.util.HashMap<>();
            addSources(manifest, original, modsOfSource);
            noteModsPerSource(manifest, modsOfSource);
            manifest.markers = new ArrayList<>();
            for (RecordingManifest.Marker marker : original.markers) {
                int tick = Spans.remap(keep, marker.tick);
                if (tick >= 0) {
                    marker.tick = tick;
                    manifest.markers.add(marker);
                }
            }
            List<Assembler.Piece> pieces = new ArrayList<>();
            int here = 0;
            int before = 0;
            for (Spans.Range range : keep) {
                if (here > 0) {
                    RecordingManifest.Marker cut = new RecordingManifest.Marker();
                    cut.tick = here;
                    cut.colour = CUT_COLOUR;
                    cut.description = String.format(java.util.Locale.ROOT, "%.1f seconds cut", (range.from() - before) / 20.0);
                    cut.kind = RecordingManifest.Marker.CUT;
                    manifest.markers.add(cut);
                }
                pieces.add(new Assembler.Piece(source, range.from(), range.to(), snapshots.get(range.from())));
                here += range.length();
                before = range.to();
            }
            manifest.markers.sort(java.util.Comparator.comparingInt((RecordingManifest.Marker marker) -> marker.tick));
            byte[] edit = source.editState();
            EditState state = edit == null ? null : readEdit(edit);
            return write(job, manifest, pieces, source.preview(), state == null ? null : EditRemap.trimmed(state, keep).toBytes(), written);
        }
    }

    // --- The part of a trim that only the game can do ---

    private static void await(LibraryJob job, BooleanSupplier reached, int seconds, String what) throws Exception {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (!reached.getAsBoolean()) {
            if (job.cancelAsked()) {
                throw new Assembler.Cancelled();
            }
            String error = Playback.lastError();
            if (error != null) {
                throw new IllegalStateException("The recording could not be played to the cut: " + error);
            }
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("Gave up waiting for " + what);
            }
            Thread.sleep(25);
        }
    }

    /**
     * Opens the recording in the game, unless it is the one open already, and has the world described at each tick.
     * Leaves the game as it found it where it can: a recording that was open stays open at the tick it was at, and one
     * opened here is closed again.
     */
    private static Map<Integer, Assembler.Snapshot> describe(LibraryJob job, Path file, String id, List<Integer> ticks, boolean leaveWorld, double share) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        boolean[] opened = {false};
        int[] cameFrom = {-1};
        job.at(0.01, "Opening the recording in the game");
        String refusal = minecraft.submit(() -> {
            if (Recorder.get().state() != Recorder.State.IDLE) {
                return "A recording is being made. Finish it first: this trim has to play the recording in the game";
            }
            if (comedymonkey.dailies.export.Export.active()) {
                return "An export is running. This trim has to play the recording in the game, so wait for the export";
            }
            if (Playback.loaded() && id.equals(Playback.recordingId())) {
                cameFrom[0] = Playback.shown();
                return null;
            }
            if (minecraft.level != null && !Playback.active() && !leaveWorld) {
                return "LEAVE_WORLD_NEEDED: a cut that is not at the start of a segment needs the recording played in the game, which means leaving the world you are in. "
                    + "Ask again with leave_world = true to do that";
            }
            Playback.open(file);
            opened[0] = true;
            return null;
        }).get();
        if (refusal != null) {
            throw new IllegalStateException(refusal);
        }
        Map<Integer, Assembler.Snapshot> snapshots = new java.util.HashMap<>();
        try {
            await(job, () -> Playback.loaded() && !Playback.jumping() && id.equals(Playback.recordingId()), 300, "the recording to open in the game");
            for (int i = 0; i < ticks.size(); i++) {
                int tick = ticks.get(i);
                job.at(0.02 + (share - 0.02) * i / ticks.size(), "Playing to the cut at tick " + tick);
                minecraft.submit(() -> Playback.jumpTo(tick, true)).get();
                await(job, () -> Playback.loaded() && !Playback.jumping() && Playback.position() == tick, 300, "playback to reach tick " + tick);
                Assembler.Snapshot snapshot = minecraft.submit(() -> {
                    if (!Playback.loaded() || Playback.jumping() || Playback.position() != tick || !id.equals(Playback.recordingId())) {
                        throw new IllegalStateException("The recording was moved while the cut at tick " + tick + " was being described. Leave it be and try again");
                    }
                    return Playback.describeWorld();
                }).get();
                snapshots.put(tick, snapshot);
            }
        } finally {
            try {
                minecraft.submit(() -> {
                    if (opened[0]) {
                        Playback.close();
                    } else if (cameFrom[0] >= 0 && Playback.loaded()) {
                        Playback.jumpTo(cameFrom[0], true);
                    }
                }).get();
                if (opened[0]) {
                    long deadline = System.currentTimeMillis() + 60_000;
                    while (Playback.active() && System.currentTimeMillis() < deadline) {
                        Thread.sleep(25);
                    }
                }
            } catch (Exception e) {
                Host.LOGGER.warn("Dailies: the game could not be put back as it was after describing the cuts of a trim", e);
            }
        }
        return snapshots;
    }

}
