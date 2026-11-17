package comedymonkey.dailies.library;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which recordings are starred (specification REC-5), kept by id in {@code stars.json} in the recordings folder. By
 * id and not by file, so a star stays with a recording that is renamed, moved into a folder or put in the bin and
 * restored. Kept apart from the recordings themselves so starring one never rewrites a file of gigabytes, and works
 * on the one being watched. Thread safe.
 */
public final class Stars {

    public static final String FILE_NAME = "stars.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final class Stored {
        List<String> starred = new ArrayList<>();
    }

    private final Path file;
    private final Set<String> starred = new LinkedHashSet<>();
    private boolean loaded = false;

    public Stars(Path folder) {
        this.file = folder.resolve(FILE_NAME);
    }

    private void load() {
        if (this.loaded) {
            return;
        }
        this.loaded = true;
        if (!Files.isRegularFile(this.file)) {
            return;
        }
        try {
            Stored stored = GSON.fromJson(Files.readString(this.file, StandardCharsets.UTF_8), Stored.class);
            if (stored != null && stored.starred != null) {
                for (String id : stored.starred) {
                    if (id != null && !id.isBlank()) {
                        this.starred.add(id);
                    }
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            // Stars are a nicety: an unreadable file starts again empty rather than stopping the list
        }
    }

    private void save() throws IOException {
        Stored stored = new Stored();
        stored.starred = new ArrayList<>(this.starred);
        Files.createDirectories(this.file.getParent());
        Path temporary = this.file.resolveSibling(FILE_NAME + ".writing");
        Files.writeString(temporary, GSON.toJson(stored), StandardCharsets.UTF_8);
        Files.move(temporary, this.file, StandardCopyOption.REPLACE_EXISTING);
    }

    public synchronized boolean has(String id) {
        this.load();
        return this.starred.contains(id);
    }

    public synchronized Set<String> all() {
        this.load();
        return Set.copyOf(this.starred);
    }

    public synchronized void set(String id, boolean on) throws IOException {
        this.load();
        boolean changed = on ? this.starred.add(id) : this.starred.remove(id);
        if (changed) {
            this.save();
        }
    }

}
