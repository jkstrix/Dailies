package comedymonkey.dailies.export;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
 * The export presets (specification EXP-14), kept in one small file with the mod's data, never in a recording. The
 * built-in ones can be changed, renamed and removed like any other; "reset" brings them back as they were made.
 * <p>
 * The file holds the whole list in its order, and the ids of built-in presets the person removed, so that a built-in
 * preset added by a later version of Dailies appears by itself while a removed one stays away.
 * <p>
 * Changes are made in memory at once; {@link #write} puts them on disk and is the caller's to run away from the
 * client thread. Safe to use from any thread.
 */
public final class ExportPresets {

    private final Path file;
    private final List<ExportPreset> presets = new ArrayList<>();
    private final Set<String> removed = new LinkedHashSet<>();
    private boolean loaded = false;

    public ExportPresets(Path file) {
        this.file = file;
    }

    /** The presets Dailies comes with, in the order they are listed */
    public static List<ExportPreset> builtIn() {
        return List.of(
            new ExportPreset("YouTube 1080p60", "youtube_1080p60", 1920, 1080, 60.0, "mp4", List.of("h264", "av1"), "high", 20_000_000L, true, true, "aac", false, false, 0),
            new ExportPreset("YouTube 4K60", "youtube_4k60", 3840, 2160, 60.0, "mp4", List.of("h264", "av1"), "high", 80_000_000L, true, true, "aac", false, false, 0),
            new ExportPreset("Shorts and Reels", "shorts", 1080, 1920, 60.0, "mp4", List.of("h264", "av1"), "high", 20_000_000L, true, true, "aac", false, false, 60),
            new ExportPreset("Editing master", "editing_master", 1920, 1080, 60.0, "mov", List.of("prores", "dnxhr"), "high", 20_000_000L, true, true, "pcm", true, true, 0),
            new ExportPreset("Smallest", "smallest", 1280, 720, 30.0, "mp4", List.of("av1"), "moderate", 1_000_000L, true, true, "aac", false, false, 0));
    }

    private synchronized void load() {
        if (this.loaded) {
            return;
        }
        this.loaded = true;
        this.presets.clear();
        this.removed.clear();
        if (Files.isRegularFile(this.file)) {
            try {
                JsonObject json = JsonParser.parseString(Files.readString(this.file, StandardCharsets.UTF_8)).getAsJsonObject();
                if (json.has("removed_built_in")) {
                    for (JsonElement id : json.getAsJsonArray("removed_built_in")) {
                        this.removed.add(id.getAsString());
                    }
                }
                // What a preset in the file does not say is taken from here; it began as nothing built in unless it says so
                ExportPreset base = new ExportPreset("Preset", "", 1920, 1080, 60.0, "mp4", List.of("h264", "av1"), "high", 20_000_000L, true, true, "auto", false, false, 0);
                for (JsonElement entry : json.getAsJsonArray("presets")) {
                    try {
                        ExportPreset preset = ExportPreset.fromJson(entry.getAsJsonObject(), base);
                        if (this.index(preset.name()) < 0) {
                            this.presets.add(preset);
                        }
                    } catch (RuntimeException e) {
                        // One preset that cannot be read must not cost the others
                    }
                }
            } catch (IOException | RuntimeException e) {
                // A file that cannot be read is treated as none: the built-in presets are always there
                this.presets.clear();
                this.removed.clear();
            }
        }
        for (ExportPreset factory : builtIn()) {
            boolean there = this.presets.stream().anyMatch(preset -> preset.builtIn().equals(factory.builtIn()));
            if (!there && !this.removed.contains(factory.builtIn()) && this.index(factory.name()) < 0) {
                this.presets.add(factory);
            }
        }
    }

    private int index(String name) {
        for (int i = 0; i < this.presets.size(); i++) {
            if (this.presets.get(i).name().equalsIgnoreCase(name.trim())) {
                return i;
            }
        }
        return -1;
    }

    public synchronized List<ExportPreset> all() {
        this.load();
        return List.copyOf(this.presets);
    }

    /** The preset of this name, whatever its capitals, or null */
    public synchronized ExportPreset find(String name) {
        this.load();
        int index = name == null ? -1 : this.index(name);
        return index < 0 ? null : this.presets.get(index);
    }

    /** Adds a preset, or changes the one that has its name */
    public synchronized void save(ExportPreset preset) {
        this.load();
        int index = this.index(preset.name());
        if (index >= 0) {
            // What it began as stays known, so "reset" can find it
            ExportPreset old = this.presets.get(index);
            this.presets.set(index, preset.builtIn().isEmpty() && !old.builtIn().isEmpty() ? ExportPreset.fromJson(with("built_in", old.builtIn()), preset) : preset);
        } else {
            this.presets.add(preset);
        }
    }

    private static JsonObject with(String key, String value) {
        JsonObject json = new JsonObject();
        json.addProperty(key, value);
        return json;
    }

    public synchronized void rename(String name, String newName) {
        this.load();
        int index = this.index(name);
        if (index < 0) {
            throw new IllegalArgumentException("There is no preset called '" + name + "'");
        }
        int taken = this.index(newName);
        if (taken >= 0 && taken != index) {
            throw new IllegalArgumentException("There is already a preset called '" + newName.trim() + "'");
        }
        this.presets.set(index, this.presets.get(index).named(newName));
    }

    public synchronized void remove(String name) {
        this.load();
        int index = this.index(name);
        if (index < 0) {
            throw new IllegalArgumentException("There is no preset called '" + name + "'");
        }
        ExportPreset gone = this.presets.remove(index);
        if (!gone.builtIn().isEmpty()) {
            this.removed.add(gone.builtIn());
        }
    }

    /** The built-in presets as Dailies made them, first in the list; presets the person made are kept after them */
    public synchronized void reset() {
        this.load();
        List<ExportPreset> own = new ArrayList<>();
        List<ExportPreset> factory = builtIn();
        for (ExportPreset preset : this.presets) {
            boolean clashes = factory.stream().anyMatch(made -> made.name().equalsIgnoreCase(preset.name()));
            if (preset.builtIn().isEmpty() && !clashes) {
                own.add(preset);
            }
        }
        this.presets.clear();
        this.presets.addAll(factory);
        this.presets.addAll(own);
        this.removed.clear();
    }

    /** Puts the list on disk. Not for the client thread */
    public void write() throws IOException {
        JsonObject json = new JsonObject();
        synchronized (this) {
            this.load();
            json.addProperty("version", 1);
            JsonArray list = new JsonArray();
            this.presets.forEach(preset -> list.add(preset.toJson()));
            json.add("presets", list);
            JsonArray gone = new JsonArray();
            this.removed.forEach(gone::add);
            json.add("removed_built_in", gone);
        }
        Path parent = this.file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        // Written beside and moved into place, so a crash half way leaves the old file
        Path beside = this.file.resolveSibling(this.file.getFileName() + ".new");
        Files.writeString(beside, new GsonBuilder().setPrettyPrinting().create().toJson(json), StandardCharsets.UTF_8);
        Files.move(beside, this.file, StandardCopyOption.REPLACE_EXISTING);
    }

}
