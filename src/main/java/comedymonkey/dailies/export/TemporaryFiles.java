package comedymonkey.dailies.export;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The files an export writes beside its output while it runs and takes away when it ends: the soundless picture and
 * the captured sound (EXP-3), the separate tracks (EXP-12) and the folder of parts several encoders write (EXP-2's
 * fast H.264). Each name carries the id of the game's process, so that a game that closed in the middle of an export,
 * or crashed, can have what it left taken away when a game next starts ({@link #sweep}), and an export still running
 * in another game (the background export, or a second game) is never touched. No Minecraft here.
 */
public final class TemporaryFiles {

    /** What is left this long is taken away even when its process id is in use, which by then is another program's */
    public static final long FORGOTTEN_MILLIS = 7L * 24 * 60 * 60 * 1000;

    private static final long PID = ProcessHandle.current().pid();

    private static final Pattern PICTURE = Pattern.compile(".+\\.dailies-picture-(\\d+)\\.[A-Za-z0-9]+");
    private static final Pattern SOUND = Pattern.compile(".+\\.dailies-sound-(\\d+)(?:-voice\\d+)?\\.pcm");
    private static final Pattern TRACK = Pattern.compile(".+\\.dailies-track-(\\d+)-[A-Za-z0-9-]+\\.pcm");
    private static final Pattern PARTS = Pattern.compile("\\.dailies-parts-(\\d+)-[0-9A-Fa-f-]+");

    private TemporaryFiles() {
    }

    /**
     * The soundless picture of an export with sound, beside its output.
     *
     * @param base      the output's name without its ending
     * @param extension its ending, with the dot
     */
    public static String picture(String base, String extension) {
        return base + ".dailies-picture-" + PID + extension;
    }

    /** The sound captured for an export, beside its output; each speaker's own goes beside it, as {@link #voice} says */
    public static String sound(String base) {
        return base + ".dailies-sound-" + PID + ".pcm";
    }

    /** One speaker's voice, kept apart for its own track */
    public static String voice(String base, int speaker) {
        return base + ".dailies-sound-" + PID + "-voice" + speaker + ".pcm";
    }

    /** A track being made from the captured sound: game, voice0, music, mix and so on, letters, digits and dashes */
    public static String track(String base, String which) {
        return base + ".dailies-track-" + PID + "-" + which + ".pcm";
    }

    /**
     * The folder the encoders working side by side write their parts into. Nothing of the export's own name is in it,
     * since the encoders read the path as a pattern for numbering their files
     */
    public static String parts() {
        return ".dailies-parts-" + PID + "-" + UUID.randomUUID();
    }

    /**
     * The process that made a file or folder of this name, when the name is one of an export's temporary files;
     * empty for every other name.
     */
    public static OptionalLong owner(String name, boolean folder) {
        for (Pattern pattern : folder ? new Pattern[]{PARTS} : new Pattern[]{PICTURE, SOUND, TRACK}) {
            Matcher match = pattern.matcher(name);
            if (match.matches()) {
                try {
                    return OptionalLong.of(Long.parseLong(match.group(1)));
                } catch (NumberFormatException e) {
                    return OptionalLong.empty();
                }
            }
        }
        return OptionalLong.empty();
    }

    /**
     * Takes away, in each of these folders, what exports left behind: the files and folders named as above whose
     * process has ended, or that nobody has changed for a week. Only those names, never anything else, and never
     * into other folders. Never throws.
     *
     * @param alive whether a process with this id is running
     * @return how many files and folders were taken away
     */
    public static int sweep(Collection<Path> folders, LongPredicate alive, long now) {
        int removed = 0;
        Set<Path> seen = new LinkedHashSet<>();
        for (Path folder : folders) {
            if (folder == null || !seen.add(folder.toAbsolutePath().normalize()) || !Files.isDirectory(folder)) {
                continue;
            }
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
                for (Path entry : entries) {
                    boolean isFolder = Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS);
                    OptionalLong owner = owner(entry.getFileName().toString(), isFolder);
                    if (owner.isEmpty() || alive.test(owner.getAsLong()) && !forgotten(entry, now)) {
                        continue;
                    }
                    if (delete(entry)) {
                        removed++;
                    }
                }
            } catch (IOException | RuntimeException e) {
                // A folder that cannot be read is left as it is
            }
        }
        return removed;
    }

    private static boolean forgotten(Path entry, long now) {
        try {
            FileTime changed = Files.getLastModifiedTime(entry);
            return now - changed.toMillis() > FORGOTTEN_MILLIS;
        } catch (IOException e) {
            return false;
        }
    }

    /** Deletes a file, or a folder with what is in it; false when anything is left */
    private static boolean delete(Path entry) {
        try (Stream<Path> inside = Files.walk(entry)) {
            for (Path path : inside.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

}
