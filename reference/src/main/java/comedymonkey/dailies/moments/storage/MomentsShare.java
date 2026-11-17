package comedymonkey.dailies.moments.storage;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import comedymonkey.dailies.moments.MJson;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The moments side of a shared session (FILE-15): which sessions go with a recording, what of them goes in the pack,
 * and taking in a friend's sessions, marked as theirs.
 * <p>
 * Other players are treated fairly (plan principle 8): what they said is about them, so a pack carries only the
 * sharer's own lines and voice. The events, who was together, the clips and the pictures go as they are.
 */
public final class MomentsShare {

    /** One file of a session for the pack: a file on disk, or bytes made for the pack */
    public record File(String name, @Nullable Path path, @Nullable byte[] bytes) {
    }

    private MomentsShare() {
    }

    /** The sessions during which a recording was made */
    public static List<MomentsSession> sessionsOf(MomentsStorage storage, String recordingId) {
        List<MomentsSession> found = new ArrayList<>();
        for (String id : storage.listSessionIds()) {
            MomentsSession session = storage.getSession(id);
            if (session == null || MJson.bool(session.meta(), "imported")) {
                continue; // a friend's session is theirs to share
            }
            for (JsonElement replay : MJson.arr(session.meta(), "replays")) {
                if (replay.isJsonPrimitive() && replay.getAsString().equals(recordingId)) {
                    found.add(session);
                    break;
                }
            }
        }
        return found;
    }

    /**
     * Everything of a recording's sessions that goes in its pack, named "moments/&lt;session&gt;/...". Waits for the
     * session files to be written first, so not for the client thread.
     */
    public static List<File> filesFor(MomentsStorage storage, String recordingId) throws IOException {
        storage.flush();
        List<File> files = new ArrayList<>();
        for (MomentsSession session : sessionsOf(storage, recordingId)) {
            String prefix = "moments/" + session.id + "/";
            String own = MJson.str(MJson.obj(session.meta(), "local_player"), "uuid", "");
            Set<String> keptAudio = new HashSet<>();
            Path dialogue = session.dir.resolve("dialogue.jsonl");
            if (Files.isRegularFile(dialogue)) {
                StringBuilder kept = new StringBuilder();
                for (String line : Files.readAllLines(dialogue, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        JsonObject record = JsonParser.parseString(line).getAsJsonObject();
                        if (own.equals(MJson.str(MJson.obj(record, "speaker"), "uuid", ""))) {
                            kept.append(line).append('\n');
                            String audio = MJson.str(record, "audio_file");
                            if (audio != null) {
                                keptAudio.add(audio.replace('\\', '/'));
                            }
                        }
                    } catch (RuntimeException ignored) {
                        // A torn last line stays behind
                    }
                }
                files.add(new File(prefix + "dialogue.jsonl", null, kept.toString().getBytes(StandardCharsets.UTF_8)));
            }
            try (Stream<Path> walk = Files.walk(session.dir)) {
                for (Path path : walk.filter(Files::isRegularFile).toList()) {
                    String relative = session.dir.relativize(path).toString().replace('\\', '/');
                    if (relative.equals("dialogue.jsonl") || relative.endsWith(".tmp")) {
                        continue;
                    }
                    if (relative.startsWith("audio/") && !keptAudio.contains(relative)) {
                        continue;
                    }
                    files.add(new File(prefix + relative, path, null));
                }
            }
        }
        return files;
    }

    /**
     * Takes in the sessions of an unpacked pack: each becomes a session here, marked as the sharer's, under an id of
     * its own so that it never takes the place of one of this player's (two people in one evening start their sessions
     * in the same second often enough).
     *
     * @param unpacked the pack's "moments" folder
     * @return the ids the sessions have here
     */
    public static List<String> importSessions(MomentsStorage storage, Path unpacked, String recorderName, String recorderId) throws IOException {
        List<String> ids = new ArrayList<>();
        if (!Files.isDirectory(unpacked)) {
            return ids;
        }
        List<Path> folders;
        try (Stream<Path> list = Files.list(unpacked)) {
            folders = list.filter(Files::isDirectory).sorted().toList();
        }
        for (Path folder : folders) {
            String original = folder.getFileName().toString();
            String base = original + "-" + sanitize(recorderName.isBlank() ? "friend" : recorderName);
            String id = base;
            int suffix = 2;
            while (Files.exists(storage.root().resolve(id))) {
                id = base + "-" + suffix++;
            }
            Path target = storage.root().resolve(id);
            copyTree(folder, target);
            Path sessionFile = target.resolve("session.json");
            JsonObject meta;
            try {
                meta = Files.isRegularFile(sessionFile) ? JsonParser.parseString(Files.readString(sessionFile, StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject();
            } catch (RuntimeException e) {
                meta = new JsonObject();
            }
            meta.addProperty("id", id);
            meta.addProperty("imported", true);
            meta.addProperty("original_session_id", original);
            meta.add("shared_by", MJson.player(recorderId.isBlank() ? null : recorderId, recorderName));
            long now = System.currentTimeMillis();
            meta.addProperty("imported_real_time", now);
            meta.addProperty("imported_real_time_iso", MJson.iso(now));
            Files.writeString(sessionFile, MJson.PRETTY.toJson(meta), StandardCharsets.UTF_8);
            ids.add(id);
        }
        return ids;
    }

    /** Whether a session is a friend's, taken in from a shared session */
    public static boolean theirs(MomentsSession session) {
        return MJson.bool(session.meta(), "imported");
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path path : walk.toList()) {
                Path target = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target);
                }
            }
        }
    }

    private static String sanitize(String name) {
        String sanitized = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return sanitized.isEmpty() ? "friend" : sanitized;
    }

}
