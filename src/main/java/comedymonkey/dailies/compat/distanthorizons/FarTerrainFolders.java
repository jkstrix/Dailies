package comedymonkey.dailies.compat.distanthorizons;

import comedymonkey.dailies.file.SafePaths;
import comedymonkey.dailies.library.Sharing;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Where Distant Horizons kept the far terrain of a world, as a recording remembers it. A recording names that folder
 * so playback can copy the far terrain built while playing; the name comes from a file that may have come from someone
 * else, so it is only ever a path inside the game's folder, and only a recording made here is believed. Needs no game.
 */
final class FarTerrainFolders {

    private FarTerrainFolders() {
    }

    /**
     * What a world's far terrain folder is written into a recording as: its path relative to the game's folder, with
     * forward slashes. Null for a folder outside the game's folder, which playback would refuse anyway
     */
    static @Nullable String toRecord(Path game, Path folder) {
        Path base = game.toAbsolutePath().normalize();
        Path path = folder.toAbsolutePath().normalize();
        if (!SafePaths.inside(base, path)) {
            return null;
        }
        return base.relativize(path).toString().replace('\\', '/');
    }

    /**
     * The far terrain folder a recording names, or null when it is anything but a relative path that stays inside the
     * game's folder: an absolute path, a drive, a network share or {@code ..} that climbs out are all refused
     */
    static @Nullable Path recorded(Path game, @Nullable String recorded) {
        return SafePaths.within(game, recorded);
    }

    /**
     * The path to give Distant Horizons for a playback folder. Distant Horizons 3.3 renames the folder it is given onto
     * itself before it opens it (see DistantHorizonsCompat.bindFolders), and Windows refuses that while any file in the
     * folder is open. Distant Horizons leaves the database open after it closes a level, so every reopen failed: on
     * every frame, with a stack trace each time, and with no far terrain (the owner, 20 November, on a server). On
     * Windows it is therefore given a directory junction at {@code link} that leads to the folder. Renaming a junction
     * onto itself renames only the junction, which has no files of its own open, and the database is still the one
     * file, so a connection left open and a new one share it as SQLite expects. Elsewhere, or when no junction can be
     * made, the folder itself
     */
    static Path openable(Path folder, Path link) throws IOException {
        Files.createDirectories(folder);
        deleteSpares(folder);
        if (!windows()) {
            return folder;
        }
        if (Files.isDirectory(link) && Files.isSameFile(link, folder) && renamable(link)) {
            return link;
        }
        if (Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            // A junction to somewhere else, or a folder in the way: only the entry goes, never what a junction leads to
            Files.delete(link);
        }
        Files.createDirectories(link.getParent());
        Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), folder.toString()).redirectErrorStream(true).start();
        String said = new String(process.getInputStream().readAllBytes()).trim();
        try {
            if (process.waitFor() != 0 || !Files.isSameFile(link, folder)) {
                throw new IOException("mklink /J said: " + said);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while making a junction", e);
        }
        return link;
    }

    static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
    }

    /** What Distant Horizons does to the folder before opening it, done first: false while a file in it is held open */
    static boolean renamable(Path folder) {
        File file = folder.toFile();
        return file.renameTo(file);
    }

    /**
     * Copies of a far terrain store that the build of 20 November made beside a folder still held open, about a
     * gigabyte each for a large world; gone once nothing holds them, and kept until then
     */
    private static void deleteSpares(Path folder) {
        for (int i = 1; i <= 8; i++) {
            Path spare = folder.resolveSibling(folder.getFileName() + "-spare" + i);
            if (!Files.isDirectory(spare, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(spare)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        // Still held: tried again the next time
                    }
                });
            } catch (IOException e) {
                // As above
            }
        }
    }

    /** Whether a recording was taken in from someone else's share pack, which puts it under {@code recordings/shared} */
    static boolean shared(Path recordings, Path file) {
        return SafePaths.inside(recordings.resolve(Sharing.SHARED_FOLDER), file);
    }

}
