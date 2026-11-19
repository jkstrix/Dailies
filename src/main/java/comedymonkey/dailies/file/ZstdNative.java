package comedymonkey.dailies.file;

import com.github.luben.zstd.util.Native;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

/**
 * Loads the native Zstandard library that recordings are packed with (zstd-jni, carried inside the mod's jar).
 * <p>
 * Left to itself zstd-jni copies its library to a new temporary file on every start and loads it from there. That
 * fails where the temporary folder may not run programs (a Linux /tmp mounted noexec), and on Windows, where a loaded
 * library cannot be deleted, it leaves another copy behind every time. So the library for this computer is unpacked
 * once into the game's dailies folder, under its own name and version, and loaded from there.
 * <p>
 * The loading itself is still done by zstd-jni ({@code ZstdNativePath}): a native library belongs to the class loader
 * of the class that loads it, and under NeoForge zstd-jni is a library with a class loader of its own, so a library
 * Dailies loaded would not be found by zstd-jni's native methods. Anything that goes wrong falls back to zstd-jni's
 * own way, so a folder that cannot be written costs only the tidiness.
 */
public final class ZstdNative {

    /** The system property zstd-jni loads its library from when it is set */
    private static final String PATH_PROPERTY = "ZstdNativePath";

    private static volatile boolean loaded = false;
    private static @Nullable Path folder = null;
    private static @Nullable Throwable folderFailure = null;

    private ZstdNative() {
    }

    /** Where to unpack the library to; called at start-up, before anything is packed. Without it the library is loaded zstd-jni's own way */
    public static synchronized void useFolder(Path where) {
        folder = where;
    }

    /** Why the library could not be loaded from the folder, when it was loaded zstd-jni's own way instead; null otherwise */
    public static synchronized @Nullable Throwable folderFailure() {
        return folderFailure;
    }

    /**
     * Makes sure the library is loaded. Cheap once it is.
     *
     * @throws IllegalStateException when it cannot be loaded at all: recordings can then be neither written nor read
     */
    public static void load() {
        if (loaded) {
            return;
        }
        synchronized (ZstdNative.class) {
            if (loaded) {
                return;
            }
            if (!Native.isLoaded() && folder != null) {
                try {
                    Path library = unpack(folder);
                    if (library != null) {
                        System.setProperty(PATH_PROPERTY, library.toString());
                        Native.load();
                    }
                } catch (IOException | RuntimeException | LinkageError e) {
                    folderFailure = e;
                } finally {
                    // Only ever for this one load: a later load (none, once this worked) goes zstd-jni's own way
                    System.clearProperty(PATH_PROPERTY);
                }
            }
            try {
                Native.load();
            } catch (LinkageError e) {
                throw new IllegalStateException("Dailies could not load the Zstandard library it packs recordings with, so recordings cannot be written or opened on this computer ("
                    + System.getProperty("os.name") + ", " + System.getProperty("os.arch") + "): " + e.getMessage(), e);
            }
            loaded = true;
        }
    }

    /** Where zstd-jni keeps the library for this computer inside its jar, as zstd-jni itself works it out */
    static String resourceName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).replace(' ', '_');
        os = os.startsWith("win") ? "win" : os.startsWith("mac") ? "darwin" : os;
        String arch = System.getProperty("os.arch", "");
        if (os.equals("darwin") && arch.equals("amd64")) {
            arch = "x86_64";
        }
        String extension = os.contains("os_x") || os.contains("darwin") ? "dylib" : os.contains("win") ? "dll" : "so";
        return "/" + os + "/" + arch + "/libzstd-jni-" + version() + "." + extension;
    }

    /**
     * The version of zstd-jni that is loaded, read when it runs: another mod may bring a later one, which the game then
     * loads in place of ours, and its library is under its own version's name
     */
    private static String version() {
        try {
            return String.valueOf(com.github.luben.zstd.util.ZstdVersion.class.getField("VERSION").get(null));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("zstd-jni does not say its version", e);
        }
    }

    /**
     * Unpacks the library into the folder, unless the file there already has exactly its bytes, and returns the file;
     * null when zstd-jni carries no library for this computer. Written under a temporary name and moved into place, so
     * an unpacking cut short is never loaded, and two games starting at once each find either nothing or the whole file.
     */
    static @Nullable Path unpack(Path folder) throws IOException {
        String resource = resourceName();
        byte[] bytes;
        try (InputStream in = Native.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            bytes = in.readAllBytes();
        }
        Path file = folder.resolve(resource.substring(resource.lastIndexOf('/') + 1));
        if (same(file, bytes)) {
            return file;
        }
        Files.createDirectories(folder);
        Path temporary = folder.resolve(file.getFileName() + "." + UUID.randomUUID() + ".dailies-unpacking");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException inUse) {
                // Another game running has it loaded (Windows will not replace it then); that game unpacked the same bytes
                if (!same(file, bytes)) {
                    throw inUse;
                }
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return file;
    }

    private static boolean same(Path file, byte[] bytes) {
        try {
            return Files.isRegularFile(file) && Files.size(file) == bytes.length && Arrays.equals(Files.readAllBytes(file), bytes);
        } catch (IOException unreadable) {
            return false;
        }
    }

}
