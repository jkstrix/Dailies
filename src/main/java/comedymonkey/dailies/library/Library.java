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

    /**
     * At start-up: the index is checked against the files in the background, and built if it is not there (FILE-12).
     * Then the storage settings are applied, except in the background export game, which shares the Dailies folder
     * with the game being played and leaves the bin and the recordings to it (EXP-13)
     */
    public static void init() {
        Thread check = new Thread(() -> {
            try {
                index().refresh(true);
            } catch (Throwable t) {
                Host.LOGGER.warn("Dailies: the index of recordings could not be checked at start-up. It is checked again when it is next needed", t);
            }
            if (comedymonkey.dailies.export.Worker.active()) {
                return;
            }
            try {
                // The settings file is read a moment after start-up; housekeeping waits for it so it acts on what the person chose
                long deadline = System.currentTimeMillis() + 30_000;
                while (!Host.get().settingsLoaded() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(250);
                }
                if (Host.get().settingsLoaded()) {
                    housekeeping();
                }
            } catch (Throwable t) {
                Host.LOGGER.warn("Dailies: the storage settings could not be applied at start-up", t);
            }
        }, "Dailies-Library-Check");
        check.setDaemon(true);
        check.start();
        DiskGuard.init();
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

    /**
     * A recording in the recordings folder by path (absolute, or relative to the folder), by file name with or without
     * its extension, by its name or by its id. A path that leads outside the folder, or to a file that is not a
     * recording, finds nothing
     */
    public static @Nullable Path find(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Path file = index().fileNamed(text);
        if (file != null) {
            return file;
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

    /**
     * Refuses anything but a recording's file in the recordings folder. Everything here that deletes, moves, bins or
     * writes to a recording asks this first: the path may have come from a tool's arguments
     */
    private static void requireRecording(Path file) {
        if (!index().holds(file)) {
            throw new IllegalArgumentException("Only a recording in the recordings folder can be changed, moved or deleted, and " + file.getFileName() + " is not one");
        }
    }

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
        requireRecording(file);
        RecordingFile open = Playback.active() ? Playback.file() : null;
        if (open != null && open.file().toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize())) {
            // The recording being watched is renamed through playback's own handle on it, which can write to it
            open.rename(name.trim());
        } else {
            try (RecordingFile recording = RecordingFile.openForEditing(file)) {
                recording.rename(name.trim());
            }
        }
        changed(file);
    }

    /**
     * Moves a recording's file, or gives the file another name.
     *
     * @param target the new file, absolute or relative to the recordings folder, and inside it; the extension is added when left out
     * @return where it is now
     */
    public static Path move(Path file, Path target) throws IOException {
        requireRecording(file);
        requireNotOpen(file);
        Path wanted = (target.isAbsolute() ? target : Recorder.folder().resolve(target)).normalize();
        if (!wanted.getFileName().toString().endsWith(RecordingFile.EXTENSION)) {
            wanted = wanted.resolveSibling(wanted.getFileName() + RecordingFile.EXTENSION);
        }
        requireRecording(wanted);
        if (Files.exists(wanted)) {
            throw new IOException("There is already a file called " + wanted.getFileName());
        }
        Files.createDirectories(wanted.toAbsolutePath().getParent());
        Files.move(file, wanted);
        index().forget(file);
        changed(wanted);
        return wanted;
    }

    /**
     * Deletes a recording by moving its file to the bin (REC-9), where it stays for {@code binDays} and can be
     * restored. From the moment it is there the index no longer finds it, so moments recorded in it are found in any
     * combined or trimmed recording that holds its ticks, as they would be were it gone for good.
     *
     * @return what it is in the bin as
     */
    public static Bin.Item delete(Path file) throws IOException {
        requireRecording(file);
        requireNotOpen(file);
        String name = file.getFileName().toString();
        String world = "";
        String id = "";
        LibraryIndex.Entry known = index().entryOf(file);
        if (known != null) {
            name = known.name.isBlank() ? name : known.name;
            world = known.world;
            id = known.id;
        } else {
            try {
                RecordingManifest manifest = RecordingFile.readManifest(file);
                name = manifest.name.isBlank() ? name : manifest.name;
                world = manifest.worldName;
                id = manifest.id;
            } catch (IOException unreadable) {
                // Binned under its file name
            }
        }
        Bin.Item item = bin().put(Bin.Kind.RECORDING, file, name, world, id);
        index().forget(file);
        return item;
    }

    // --- The bin, stars, folders and storage (REC-5, REC-8, REC-9) ---

    private static volatile Bin bin = null;
    private static volatile Stars stars = null;
    private static volatile ExportLog exportLog = null;
    /** For the small jobs of the bin and the folders, which must not wait behind a long combine */
    private static final ExecutorService HOUSE = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Dailies-Library-House");
        thread.setDaemon(true);
        return thread;
    });

    public static Bin bin() {
        Bin known = bin;
        if (known == null) {
            synchronized (Library.class) {
                known = bin;
                if (known == null) {
                    known = new Bin(Host.get().dataDirectory(), System::currentTimeMillis);
                    bin = known;
                }
            }
        }
        return known;
    }

    public static Stars stars() {
        Stars known = stars;
        if (known == null) {
            synchronized (Library.class) {
                known = stars;
                if (known == null) {
                    known = new Stars(Recorder.folder());
                    stars = known;
                }
            }
        }
        return known;
    }

    public static ExportLog exportLog() {
        ExportLog known = exportLog;
        if (known == null) {
            synchronized (Library.class) {
                known = exportLog;
                if (known == null) {
                    known = new ExportLog(Recorder.folder());
                    exportLog = known;
                }
            }
        }
        return known;
    }

    public static int binDays() {
        return Math.max(1, Host.get().setting("binDays", 30));
    }

    /**
     * Puts back something from the bin: a recording into the recordings folder, a moments session into the moments
     * folder, a scene into its recording's editor state (into the editor itself when that recording is open there).
     *
     * @return words for the person on where it went
     */
    public static String restore(String itemId) throws Exception {
        Bin.Item item = bin().find(itemId);
        if (item == null) {
            throw new IllegalArgumentException("Nothing in the bin has the id '" + itemId + "'");
        }
        switch (item.kind()) {
            case RECORDING -> {
                Path back = bin().restore(itemId);
                changed(back);
                return "'" + item.name + "' is back in the recordings folder as " + Recorder.folder().relativize(back).toString().replace('\\', '/');
            }
            case SESSION -> {
                bin().restore(itemId);
                return "The moments session '" + item.name + "' is back";
            }
            default -> {
                restoreScene(item);
                bin().forget(itemId);
                return "The scene '" + item.name + "' is back in '" + item.world + "'";
            }
        }
    }

    private static void restoreScene(Bin.Item item) throws Exception {
        com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(bin().sceneJson(item)).getAsJsonObject();
        comedymonkey.dailies.edit.Scene scene = comedymonkey.dailies.edit.Scene.fromJson(json, item.name);
        Minecraft minecraft = Minecraft.getInstance();
        java.util.function.Supplier<Boolean> intoEditor = () -> {
            if (comedymonkey.dailies.editor.EditorSession.open() && item.recordingId.equals(Playback.recordingId())) {
                comedymonkey.dailies.editor.EditorSession.history().putBackScene(scene);
                return true;
            }
            return false;
        };
        boolean done = minecraft.isSameThread() ? intoEditor.get() : minecraft.submit(intoEditor).get();
        if (done) {
            return;
        }
        Path file = index().findById(item.recordingId);
        if (file == null) {
            throw new IllegalStateException("The recording the scene '" + item.name + "' belongs to is not in the recordings folder. If it is in the bin, restore it first");
        }
        requireNotOpen(file);
        try (RecordingFile recording = RecordingFile.openForEditing(file)) {
            byte[] stored = recording.editState();
            EditState state = stored == null ? new EditState() : readEdit(stored);
            if (state == null) {
                throw new IllegalStateException("The editor state of '" + item.world + "' cannot be read, so the scene cannot be put back into it");
            }
            comedymonkey.dailies.edit.Scene copy = scene.copy();
            copy.setName(comedymonkey.dailies.edit.EditHistory.restoredName(state, scene.name()));
            state.addScene(state.scenes().size(), copy);
            recording.saveEditState(state.toBytes());
        }
        changed(file);
    }

    /**
     * Keeps a scene that is being taken out of the open recording, in the bin. Called on the client thread just before
     * the scene is removed; the writing is done away from it. Never throws.
     */
    public static void binScene(comedymonkey.dailies.edit.Scene scene) {
        String recordingId = Playback.recordingId();
        RecordingManifest manifest = Playback.manifest();
        if (recordingId == null) {
            return;
        }
        String recordingName = manifest == null ? "" : manifest.name;
        String json = scene.toJson().toString();
        String name = scene.name();
        HOUSE.execute(() -> {
            try {
                bin().putScene(recordingId, recordingName, name, json);
            } catch (Throwable t) {
                Host.LOGGER.warn("Dailies: the scene '{}' could not be kept in the bin. Undo still brings it back", name, t);
            }
        });
    }

    /** Moves a moments session's folder to the bin. The session being written now cannot be */
    public static Bin.Item binSession(String sessionId) throws IOException {
        if (sessionId == null || sessionId.isBlank() || sessionId.contains("/") || sessionId.contains("\\") || sessionId.contains("..") || sessionId.equals("firsts")) {
            throw new IllegalArgumentException("'" + sessionId + "' is not a moments session");
        }
        comedymonkey.dailies.moments.storage.MomentsSession current = comedymonkey.dailies.moments.Moments.currentSession();
        if (current != null && current.id.equals(sessionId)) {
            throw new IllegalStateException("That moments session is being written now. Leave the world first");
        }
        Path folder = Host.get().dataDirectory().resolve("moments").resolve(sessionId);
        if (!Files.isDirectory(folder)) {
            throw new IllegalArgumentException("There is no moments session '" + sessionId + "'");
        }
        comedymonkey.dailies.moments.storage.MomentsStorage storage = comedymonkey.dailies.moments.Moments.storage();
        if (storage != null) {
            storage.release(sessionId);
        }
        String world = StorageReport.sessionWorld(folder);
        return bin().put(Bin.Kind.SESSION, folder, world.isBlank() ? sessionId : sessionId + " (" + world + ")", world, "");
    }

    /** Deletes everything in the bin for good. Whoever calls this has asked the person first */
    public static int emptyBin() throws IOException {
        int count = bin().empty();
        Host.LOGGER.info("Dailies: the bin was emptied, {} item(s) deleted for good", count);
        return count;
    }

    public static boolean starred(String id) {
        return stars().has(id);
    }

    public static void star(String id, boolean on) throws IOException {
        stars().set(id, on);
    }

    /**
     * Makes a folder in the recordings folder (a real one, so the files stay ordinary files a person can find).
     *
     * @param parent {@link LibraryQuery#TOP} or a folder's relative path
     * @return its relative path
     */
    public static String createFolder(String parent, String name) throws IOException {
        String bad = LibraryQuery.badFolderName(name);
        if (bad != null) {
            throw new IllegalArgumentException(bad);
        }
        String relative = parent == null || parent.isBlank() ? name.trim() : parent + "/" + name.trim();
        Path folder = folderPath(relative);
        if (Files.exists(folder)) {
            throw new IllegalArgumentException("There is already a folder called " + name.trim());
        }
        Files.createDirectories(folder);
        index().foldersChanged();
        return relative;
    }

    /** Gives a folder another name; the recordings inside it go with it */
    public static String renameFolder(String relative, String name) throws IOException {
        String bad = LibraryQuery.badFolderName(name);
        if (bad != null) {
            throw new IllegalArgumentException(bad);
        }
        Path folder = folderPath(relative);
        if (!Files.isDirectory(folder)) {
            throw new IllegalArgumentException("There is no folder " + relative);
        }
        RecordingFile open = Playback.active() ? Playback.file() : null;
        if (open != null && open.file().toAbsolutePath().normalize().startsWith(folder.toAbsolutePath().normalize())) {
            throw new IllegalStateException("A recording in that folder is open. Close it first");
        }
        Path target = folder.resolveSibling(name.trim());
        if (Files.exists(target)) {
            throw new IllegalArgumentException("There is already a folder called " + name.trim());
        }
        Files.move(folder, target);
        index().foldersChanged();
        return Recorder.folder().relativize(target).toString().replace('\\', '/');
    }

    /** Deletes a folder, which must be empty: a recording is only ever deleted by going to the bin */
    public static void deleteFolder(String relative) throws IOException {
        Path folder = folderPath(relative);
        if (!Files.isDirectory(folder)) {
            throw new IllegalArgumentException("There is no folder " + relative);
        }
        try (java.util.stream.Stream<Path> inside = Files.list(folder)) {
            if (inside.findAny().isPresent()) {
                throw new IllegalStateException("Only an empty folder can be deleted. Move or delete what is in it first");
            }
        }
        Files.delete(folder);
        index().foldersChanged();
    }

    /**
     * Moves recordings into a folder. A file whose name is taken there gets a free one beside it.
     *
     * @return where each is now
     */
    public static List<Path> moveInto(List<Path> files, String relative) throws IOException {
        Path folder = relative == null || relative.isBlank() ? Recorder.folder() : folderPath(relative);
        if (!Files.isDirectory(folder)) {
            throw new IllegalArgumentException("There is no folder " + relative);
        }
        for (Path file : files) {
            requireRecording(file);
            requireNotOpen(file);
        }
        List<Path> moved = new ArrayList<>();
        for (Path file : files) {
            Path target = folder.resolve(file.getFileName().toString());
            if (target.toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize())) {
                moved.add(file);
                continue;
            }
            if (Files.exists(target)) {
                target = Bin.freeName(target);
            }
            moved.add(move(file, target));
        }
        return moved;
    }

    /** A folder of the recordings folder by its relative path, refused when the path would lead out of it */
    private static Path folderPath(String relative) {
        Path root = Recorder.folder().toAbsolutePath().normalize();
        Path folder = root.resolve(relative).normalize();
        if (!folder.startsWith(root) || folder.equals(root)) {
            throw new IllegalArgumentException("'" + relative + "' is not a folder of the recordings folder");
        }
        return folder;
    }

    /**
     * Gives a recording another preview picture (FILE-6), shown in the list. The recording being watched takes it
     * through playback's own handle on the file. Any thread but the client's.
     */
    public static void setPreview(Path file, byte[] png) throws IOException {
        requireRecording(file);
        RecordingFile open = Playback.active() ? Playback.file() : null;
        if (open != null && open.file().toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize())) {
            open.savePreview(png);
        } else {
            try (RecordingFile recording = RecordingFile.openForEditing(file)) {
                recording.savePreview(png);
            }
        }
        changed(file);
    }

    /** Remembers that a stretch of the open recording went into a video, for the retention rule (REC-8). Never throws */
    public static void exported(String recordingId, String world, List<Spans.Span> sourceMap, int from, int to, @Nullable Path output) {
        HOUSE.execute(() -> {
            try {
                Path data = Host.get().dataDirectory().toAbsolutePath().normalize();
                String where = "";
                long size = 0;
                if (output != null) {
                    Path absolute = output.toAbsolutePath().normalize();
                    where = absolute.startsWith(data) ? data.relativize(absolute).toString().replace('\\', '/') : absolute.toString();
                    size = Bin.sizeOf(absolute);
                }
                exportLog().add(recordingId, world, where, size, System.currentTimeMillis(), sourceMap, from, to);
            } catch (Throwable t) {
                Host.LOGGER.warn("Dailies: the export could not be noted for the storage settings", t);
            }
        });
    }

    /** How much room everything takes, per world. Looks at the folders: not for the client thread */
    public static StorageReport storage() {
        return StorageReport.measure(Host.get().dataDirectory(), list(), bin(), exportLog());
    }

    /**
     * The storage settings at work (REC-8, REC-9): what has been in the bin too long is deleted for good, recordings
     * older than {@code retentionDays} go to the bin unless starred or holding exported footage, and voice audio goes
     * as the voice settings say. Each part is left alone while its setting keeps everything. Not for the client thread.
     * The background export game never does any of it: the game being played owns the bin and the recordings (EXP-13).
     */
    public static void housekeeping() {
        if (comedymonkey.dailies.export.Worker.active()) {
            return;
        }
        long now = System.currentTimeMillis();
        try {
            List<Bin.Item> gone = bin().expire(binDays());
            if (!gone.isEmpty()) {
                Host.LOGGER.info("Dailies: {} item(s) had been in the bin longer than {} days and were deleted for good", gone.size(), binDays());
            }
        } catch (Throwable t) {
            Host.LOGGER.warn("Dailies: the bin could not be tidied", t);
        }
        int days = Host.get().setting("retentionDays", 0);
        if (days > 0) {
            try {
                RecordingFile open = Playback.active() ? Playback.file() : null;
                for (LibraryIndex.Entry entry : Retention.toBin(list(), now, days, stars().all(), exportLog().all())) {
                    Path file = fileOf(entry);
                    if (open != null && open.file().toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize())) {
                        continue;
                    }
                    delete(file);
                    Host.LOGGER.info("Dailies: '{}' is older than {} days and was moved to the bin", entry.name, days);
                }
            } catch (Throwable t) {
                Host.LOGGER.warn("Dailies: old recordings could not be moved to the bin", t);
            }
        }
        boolean onceTranscribed = Host.get().setting("voiceAudioOnceTranscribed", false);
        int voiceDays = Host.get().setting("voiceAudioDays", 0);
        comedymonkey.dailies.moments.storage.MomentsStorage storage = comedymonkey.dailies.moments.Moments.storage();
        if (storage != null && (onceTranscribed || voiceDays > 0)) {
            try {
                comedymonkey.dailies.moments.voice.VoiceRetention.apply(storage, onceTranscribed, voiceDays, now);
            } catch (Throwable t) {
                Host.LOGGER.warn("Dailies: voice audio could not be tidied", t);
            }
        }
    }

    /** Runs a small job of the bin or the folders on the library's own thread, away from the client thread */
    public static java.util.concurrent.CompletableFuture<Void> house(Runnable work) {
        return java.util.concurrent.CompletableFuture.runAsync(work, HOUSE);
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
            Map<String, String> packShas = new java.util.HashMap<>();
            int offset = 0;
            for (int i = 0; i < open.size(); i++) {
                RecordingManifest source = manifests.get(i);
                if (!source.worldName.isBlank() && !worlds.contains(source.worldName)) {
                    worlds.add(source.worldName);
                }
                source.mods.forEach(mod -> mods.putIfAbsent(mod.id, mod.version));
                if (source.distantHorizonsFolders != null) {
                    source.distantHorizonsFolders.forEach(manifest.distantHorizonsFolders::putIfAbsent);
                }
                players.addAll(source.players);
                if (source.compats != null) {
                    source.compats.forEach((compat, version) -> manifest.compats.merge(compat, version, Math::max));
                }
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
                // Two sources may call different packs by the same key (a world's own pack goes by where its file was).
                // The later one's is given a key of its own, and its records are written to match
                Map<String, String> otherKeys = new java.util.HashMap<>();
                for (RecordingManifest.Pack pack : source.packs) {
                    String known = packShas.get(pack.key);
                    if (known == null) {
                        packShas.put(pack.key, pack.sha256);
                    } else if (!known.equals(pack.sha256)) {
                        String key = pack.key + "-" + pack.sha256.substring(0, Math.min(12, pack.sha256.length()));
                        otherKeys.put(pack.key, key);
                        packShas.put(key, pack.sha256);
                    }
                }
                pieces.add(new Assembler.Piece(open.get(i), 0, source.totalTicks, null, otherKeys));
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
    public static List<String> modDifferences(List<RecordingManifest> manifests) {
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
