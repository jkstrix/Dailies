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

    /** Folders tried beside a playback folder still held open, before giving up on all of them */
    static final int SPARES = 8;

    /** Fills a folder chosen for playback; told whether it is the first choice or a spare beside one still held */
    @FunctionalInterface
    interface Filler {
        void fill(Path folder, boolean spare) throws IOException;
    }

    /**
     * The folder to give Distant Horizons for a playback world: the one asked for when it is free, or else a spare beside
     * it. Distant Horizons 3.3 renames the folder it is given onto itself before it opens it (see
     * DistantHorizonsCompat.bindFolders), and Windows refuses that while any file in it is still open. Distant Horizons can
     * leave its database open after it has closed a level, when a worker thread opens a connection just as the level
     * closes. It then fails again on every frame, with a stack trace each time: no far terrain, and an export slowed to
     * well under 60 frames a second at 4K (the owner, 20 November, after a world swap for an export on a server). So a
     * folder still held is passed over for {@code <name>-spare<n>}, filled by {@code filler}. Spares are deleted once
     * the first choice is free again, and any still held are left for a later time. Null when every folder is held
     */
    static @Nullable Path free(Path wanted, Filler filler) throws IOException {
        for (int i = 0; i <= SPARES; i++) {
            Path folder = i == 0 ? wanted : spare(wanted, i);
            Files.createDirectories(folder);
            if (!renamable(folder)) {
                continue;
            }
            filler.fill(folder, i > 0);
            if (i == 0) {
                for (int j = 1; j <= SPARES; j++) {
                    deleteQuietly(spare(wanted, j));
                }
            }
            return folder;
        }
        return null;
    }

    static Path spare(Path wanted, int number) {
        return wanted.resolveSibling(wanted.getFileName() + "-spare" + number);
    }

    /** What Distant Horizons does to the folder before opening it, done first: false while a file in it is held open */
    static boolean renamable(Path folder) {
        File file = folder.toFile();
        return file.renameTo(file);
    }

    private static void deleteQuietly(Path folder) {
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(folder)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    // Still held: tried again the next time the folder is chosen
                }
            });
        } catch (IOException e) {
            // As above
        }
    }

    /** Whether a recording was taken in from someone else's share pack, which puts it under {@code recordings/shared} */
    static boolean shared(Path recordings, Path file) {
        return SafePaths.inside(recordings.resolve(Sharing.SHARED_FOLDER), file);
    }

}
