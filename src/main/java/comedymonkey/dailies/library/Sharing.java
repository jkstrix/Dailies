package comedymonkey.dailies.library;

import com.google.gson.JsonObject;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.file.RecordingFile;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.moments.Moments;
import comedymonkey.dailies.moments.storage.MomentsShare;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * "Share session" and "Import" (FILE-15): a recording and its moments in one file for a friend, and a friend's file
 * taken into this library and onto the moments page, marked as theirs. Once imported, the friend's recording links with
 * this player's own recordings of the same evening by itself (PLAY-18). Reads and writes files: never the client thread.
 */
public final class Sharing {

    /** Where imported recordings go, inside the recordings folder: one folder per friend */
    public static final String SHARED_FOLDER = "shared";

    /** What an import did */
    public record Imported(Path recording, String recordingId, String recorder, boolean alreadyHere, List<String> sessions) {
    }

    private Sharing() {
    }

    /** The folder packs are written to unless another is asked for */
    public static Path packFolder() {
        return Host.get().dataDirectory().resolve("shared");
    }

    /**
     * Packs a recording and the moments sessions it was made in.
     *
     * @param out where to write the pack, or null for the shared folder under the recording's name
     * @return the pack
     */
    public static Path share(Path recording, @Nullable Path out) throws IOException {
        RecordingManifest manifest = RecordingFile.readManifest(recording);
        Path target = out != null ? out : unique(packFolder(), safeName(manifest.name.isBlank() ? manifest.id : manifest.name), SharePack.EXTENSION);
        if (!target.getFileName().toString().endsWith(SharePack.EXTENSION)) {
            target = target.resolveSibling(target.getFileName() + SharePack.EXTENSION);
        }
        List<SharePack.Item> items = new ArrayList<>();
        items.add(SharePack.Item.of(SharePack.RECORDING, recording));
        List<String> sessions = new ArrayList<>();
        if (Moments.storage() != null) {
            for (MomentsShare.File file : MomentsShare.filesFor(Moments.storage(), manifest.id)) {
                items.add(file.path() != null ? SharePack.Item.of(file.name(), file.path()) : SharePack.Item.of(file.name(), file.bytes()));
                String session = file.name().split("/")[1];
                if (!sessions.contains(session)) {
                    sessions.add(session);
                }
            }
        }
        JsonObject info = new JsonObject();
        info.addProperty("recording_id", manifest.id);
        info.addProperty("recording_name", manifest.name);
        info.addProperty("recorder_name", manifest.recorderName);
        info.addProperty("recorder_id", manifest.recorderId);
        info.addProperty("world_name", manifest.worldName);
        info.addProperty("total_ticks", manifest.totalTicks);
        info.addProperty("shared_at", System.currentTimeMillis());
        info.addProperty("mod_version", Host.get().modVersion());
        com.google.gson.JsonArray list = new com.google.gson.JsonArray();
        sessions.forEach(list::add);
        info.add("moments_sessions", list);
        SharePack.write(target, info, items);
        return target;
    }

    /** Takes in a friend's pack: the recording into the library (unless it is here already) and their moments onto the page */
    public static Imported importPack(Path pack) throws IOException {
        Path staging = Host.get().dataDirectory().resolve("shared").resolve(".unpacking-" + UUID.randomUUID());
        try {
            JsonObject info = SharePack.unpack(pack, staging);
            Path unpacked = staging.resolve(SharePack.RECORDING);
            RecordingManifest manifest = RecordingFile.readManifest(unpacked);
            String recorder = manifest.recorderName == null || manifest.recorderName.isBlank() ? text(info, "recorder_name", "friend") : manifest.recorderName;
            Path existing = Library.index().findById(manifest.id);
            Path recording;
            boolean alreadyHere = existing != null;
            if (alreadyHere) {
                recording = existing;
            } else {
                Path folder = Recorder.folder().resolve(SHARED_FOLDER).resolve(safeName(recorder));
                recording = unique(folder, safeName(manifest.name.isBlank() ? manifest.id : manifest.name), RecordingFile.EXTENSION);
                Files.createDirectories(folder);
                Files.move(unpacked, recording, StandardCopyOption.REPLACE_EXISTING);
                Library.changed(recording);
            }
            List<String> sessions = Moments.storage() == null ? List.of()
                : MomentsShare.importSessions(Moments.storage(), staging.resolve("moments"), recorder, manifest.recorderId == null ? "" : manifest.recorderId);
            return new Imported(recording, manifest.id, recorder, alreadyHere, sessions);
        } finally {
            deleteTree(staging);
        }
    }

    private static String text(JsonObject json, String key, String fallback) {
        return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : fallback;
    }

    private static Path unique(Path folder, String base, String extension) {
        Path candidate = folder.resolve(base + extension);
        for (int n = 2; Files.exists(candidate); n++) {
            candidate = folder.resolve(base + " (" + n + ")" + extension);
        }
        return candidate;
    }

    /** A name a file can have on any system */
    static String safeName(String name) {
        String safe = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (safe.endsWith(".")) {
            safe = safe.substring(0, safe.length() - 1);
        }
        return safe.isEmpty() ? "recording" : safe.length() > 80 ? safe.substring(0, 80) : safe;
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            Host.LOGGER.warn("Dailies: the unpacked shared session could not be tidied away from {}", root, e);
        }
    }

}
