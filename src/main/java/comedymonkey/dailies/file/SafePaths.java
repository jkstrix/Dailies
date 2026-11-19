package comedymonkey.dailies.file;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Paths made from text that came from somewhere else: a recording's manifest, a share pack, a tool's arguments. Such
 * text is never trusted to stay inside the folder it is meant for. Everything that turns outside text into a path to
 * read, write, move or delete goes through here.
 */
public final class SafePaths {

    /** A SHA-256 written as lowercase hex, as recordings name the packs and blobs they hold */
    public static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private SafePaths() {
    }

    /**
     * The path that untrusted text names inside a folder, or null when it would lead anywhere else: an absolute path, a
     * drive (including a drive-relative one such as {@code D:x}), a network share, or {@code ..} that climbs out
     *
     * @param root      the folder the path must stay inside
     * @param untrusted relative path text from outside
     */
    public static @Nullable Path within(Path root, @Nullable String untrusted) {
        if (untrusted == null || untrusted.isBlank() || untrusted.indexOf('\0') >= 0) {
            return null;
        }
        // A drive letter or a share is refused outright: on Windows resolve() would otherwise take it as it stands
        if (untrusted.startsWith("/") || untrusted.startsWith("\\") || untrusted.indexOf(':') >= 0) {
            return null;
        }
        try {
            Path relative = Path.of(untrusted);
            if (relative.isAbsolute() || relative.getRoot() != null) {
                return null;
            }
            Path base = root.toAbsolutePath().normalize();
            Path resolved = base.resolve(relative).normalize();
            return resolved.startsWith(base) && !resolved.equals(base) ? resolved : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /**
     * A path a person or a tool names, which may be a full path, taken as given, or a path relative to a base, which must
     * then stay inside it. Null for anything else, never a guess: a drive-relative path such as {@code D:clips} (not
     * absolute, yet resolving it puts it at that drive's root), a root-relative one such as {@code \clips}, or one whose
     * {@code ..} leads out of the base. Full paths are allowed on purpose: whoever names one may write there anyway
     */
    public static @Nullable Path absoluteOrWithin(Path base, @Nullable String text) {
        if (text == null || text.isBlank() || text.indexOf('\0') >= 0) {
            return null;
        }
        String trimmed = text.trim();
        try {
            Path given = Path.of(trimmed);
            if (given.isAbsolute()) {
                return given.normalize();
            }
        } catch (InvalidPathException e) {
            return null;
        }
        return within(base, trimmed);
    }

    /** Whether a path, once made absolute and normalised, lies inside a folder (and is not the folder itself) */
    public static boolean inside(Path root, Path path) {
        Path base = root.toAbsolutePath().normalize();
        Path resolved = path.toAbsolutePath().normalize();
        return resolved.startsWith(base) && !resolved.equals(base);
    }

    /** Whether untrusted text is a whole match for a pattern, such as {@link #SHA256} */
    public static boolean matches(@Nullable String untrusted, Pattern pattern) {
        return untrusted != null && pattern.matcher(untrusted).matches();
    }

}
