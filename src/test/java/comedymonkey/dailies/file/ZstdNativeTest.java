package comedymonkey.dailies.file;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.util.Native;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The native Zstandard library loads, and is unpacked into a folder of the game's rather than a temporary file */
class ZstdNativeTest {

    @TempDir
    Path folder;

    @Test
    void theLibraryLoadsInThisJvm() {
        ZstdNative.load();
        assertTrue(Native.isLoaded());
        byte[] text = "a block of a recording, a block of a recording, a block of a recording".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(text, Zstd.decompress(Zstd.compress(text, Packing.LEVEL), text.length));
    }

    @Test
    void theLibraryForThisComputerIsInTheJarAndUnpacksOnce() throws IOException {
        String resource = ZstdNative.resourceName();
        byte[] expected;
        try (InputStream in = Native.class.getResourceAsStream(resource)) {
            assertNotNull(in, "zstd-jni carries no " + resource);
            expected = in.readAllBytes();
        }
        Path unpacked = ZstdNative.unpack(this.folder);
        assertNotNull(unpacked);
        assertArrayEquals(expected, Files.readAllBytes(unpacked));
        // Unpacked already: left exactly as it is
        FileTime old = FileTime.fromMillis(1_000_000_000_000L);
        Files.setLastModifiedTime(unpacked, old);
        assertEquals(unpacked, ZstdNative.unpack(this.folder));
        assertEquals(old, Files.getLastModifiedTime(unpacked));
        // A file that is not the library is replaced, and nothing half-written is left beside it
        Files.write(unpacked, new byte[]{1, 2, 3});
        assertArrayEquals(expected, Files.readAllBytes(ZstdNative.unpack(this.folder)));
        try (var files = Files.list(this.folder)) {
            assertEquals(List.of(unpacked.getFileName().toString()), files.map(file -> file.getFileName().toString()).toList());
        }
    }

    /** In a JVM of its own, as the game is: the library is loaded from the folder it was unpacked into */
    @Test
    void aFreshJvmLoadsTheLibraryFromTheFolder() throws Exception {
        // Not the test's temporary folder: on Windows the library just loaded can stay locked a moment after its JVM ends
        // (a virus scanner, say), and the folder could then not be deleted. Under build, which a clean takes away
        Path folder = Path.of("build", "tmp", "zstd-native-test", java.util.UUID.randomUUID().toString()).toAbsolutePath();
        Files.createDirectories(folder);
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(Probe.class.getName());
        command.add(folder.toString());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("loaded from the folder"), output);
        try (var files = Files.list(folder)) {
            assertEquals(List.of(Path.of(ZstdNative.resourceName()).getFileName().toString()), files.map(file -> file.getFileName().toString()).toList());
        }
        try (var files = Files.list(folder)) {
            for (Path file : files.toList()) {
                Files.deleteIfExists(file);
            }
            Files.deleteIfExists(folder);
        } catch (IOException stillLocked) {
            // Left for the next clean
        }
    }

    /** What the fresh JVM runs */
    public static final class Probe {
        public static void main(String[] args) {
            ZstdNative.useFolder(Path.of(args[0]));
            ZstdNative.load();
            if (ZstdNative.folderFailure() != null) {
                ZstdNative.folderFailure().printStackTrace(System.out);
                System.exit(2);
            }
            byte[] text = "packed and unpacked".getBytes(StandardCharsets.UTF_8);
            if (!java.util.Arrays.equals(text, Zstd.decompress(Zstd.compress(text), text.length))) {
                System.exit(3);
            }
            System.out.println("loaded from the folder");
        }
    }

}
