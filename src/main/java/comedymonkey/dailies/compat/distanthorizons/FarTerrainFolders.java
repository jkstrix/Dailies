package comedymonkey.dailies.compat.distanthorizons;

import comedymonkey.dailies.file.SafePaths;
import comedymonkey.dailies.library.Sharing;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

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

    /** Whether a recording was taken in from someone else's share pack, which puts it under {@code recordings/shared} */
    static boolean shared(Path recordings, Path file) {
        return SafePaths.inside(recordings.resolve(Sharing.SHARED_FOLDER), file);
    }

}
