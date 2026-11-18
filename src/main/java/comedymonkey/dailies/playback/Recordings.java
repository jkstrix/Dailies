package comedymonkey.dailies.playback;

import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.file.RecordingFile;
import comedymonkey.dailies.file.RecordingManifest;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The recordings folder: what is in it, newest first, and finding a recording by its id, name or path.
 * Reads files, so not for the client thread where it can be avoided.
 */
public final class Recordings {

    public record Entry(Path file, RecordingManifest manifest, long size, long modified) {
    }

    private Recordings() {
    }

    public static List<Entry> list() {
        Path folder = Recorder.folder();
        List<Entry> entries = new ArrayList<>();
        if (!Files.isDirectory(folder)) {
            return entries;
        }
        try (Stream<Path> files = Files.walk(folder, 4)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(RecordingFile.EXTENSION)).toList()) {
                try {
                    entries.add(new Entry(file, RecordingFile.readManifest(file), Files.size(file), Files.getLastModifiedTime(file).toMillis()));
                } catch (IOException unreadable) {
                    // Not a recording, or too damaged to describe itself: left out of the list
                }
            }
        } catch (IOException e) {
            return entries;
        }
        entries.sort(Comparator.comparingLong((Entry entry) -> entry.manifest().created).reversed());
        return entries;
    }

    /** The recording with this id, wherever in the folder it is and whatever it is called now (FILE-3) */
    public static @Nullable Path findById(String id) {
        // Through the index, which knows every recording's id without opening the files (FILE-12)
        return comedymonkey.dailies.library.Library.index().findById(id);
    }

    /** A recording by path (absolute, or relative to the folder), by file name with or without its extension, or by its name */
    public static @Nullable Path find(@Nullable String path, @Nullable String name) {
        Path folder = Recorder.folder();
        if (path != null && !path.isBlank()) {
            Path given = Path.of(path);
            Path file = given.isAbsolute() ? given : folder.resolve(given);
            return Files.isRegularFile(file) ? file : null;
        }
        if (name == null || name.isBlank()) {
            return null;
        }
        for (String candidate : new String[]{name, name + RecordingFile.EXTENSION}) {
            Path file = folder.resolve(candidate);
            if (Files.isRegularFile(file)) {
                return file;
            }
        }
        for (Entry entry : list()) {
            if (entry.manifest().name.equalsIgnoreCase(name)) {
                return entry.file();
            }
        }
        return null;
    }

}
