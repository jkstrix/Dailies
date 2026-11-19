package comedymonkey.dailies.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SafePathsTest {

    @TempDir
    Path root;

    @Test
    void relativeNamesStayInside() {
        assertEquals(this.root.toAbsolutePath().normalize().resolve("audio/a.wav"), SafePaths.within(this.root, "audio/a.wav"));
        assertEquals(this.root.toAbsolutePath().normalize().resolve("b.zip"), SafePaths.within(this.root, "x/../b.zip"));
    }

    @Test
    void anythingLeadingOutIsRefused() {
        assertNull(SafePaths.within(this.root, "../x"));
        assertNull(SafePaths.within(this.root, "a/../../x"));
        assertNull(SafePaths.within(this.root, "/etc/passwd"));
        assertNull(SafePaths.within(this.root, "\\\\host\\share\\a.wav"));
        assertNull(SafePaths.within(this.root, "C:\\Windows\\win.ini"));
        assertNull(SafePaths.within(this.root, "D:x"));
        assertNull(SafePaths.within(this.root, "."));
        assertNull(SafePaths.within(this.root, ""));
        assertNull(SafePaths.within(this.root, null));
        assertNull(SafePaths.within(this.root, "a\0b"));
    }

    @Test
    void aFullPathIsTakenAsGiven() {
        Path elsewhere = this.root.resolveSibling("elsewhere").toAbsolutePath();
        assertEquals(elsewhere.normalize(), SafePaths.absoluteOrWithin(this.root, elsewhere.toString()));
    }

    @Test
    void aRelativePathStaysInsideTheBase() {
        assertEquals(this.root.toAbsolutePath().normalize().resolve("clips").resolve("june"), SafePaths.absoluteOrWithin(this.root, "clips/june"));
        assertNull(SafePaths.absoluteOrWithin(this.root, "../../outside"));
        assertNull(SafePaths.absoluteOrWithin(this.root, "..\\..\\outside"));
        assertNull(SafePaths.absoluteOrWithin(this.root, "foo/../../outside"));
    }

    @Test
    void driveAndRootRelativePathsAreRefusedNotResolved() {
        // Not absolute, yet resolving them would put them at a drive's root: neither is taken for a folder inside the base
        assertNull(SafePaths.absoluteOrWithin(this.root, "D:bar"));
        assertNull(SafePaths.absoluteOrWithin(this.root, "D:exports"));
        assertNull(SafePaths.absoluteOrWithin(this.root, "\\clips"));
        assertNull(SafePaths.absoluteOrWithin(this.root, ""));
        assertNull(SafePaths.absoluteOrWithin(this.root, null));
    }

    @Test
    void insideChecksAPathAlreadyMade() {
        assertTrue(SafePaths.inside(this.root, this.root.resolve("a/b")));
        assertFalse(SafePaths.inside(this.root, this.root.resolve("../b")));
        assertFalse(SafePaths.inside(this.root, this.root));
    }

    @Test
    void hashesAreLowercaseHexOfTheRightLength() {
        assertTrue(SafePaths.matches("a".repeat(64), SafePaths.SHA256));
        assertFalse(SafePaths.matches("A".repeat(64), SafePaths.SHA256));
        assertFalse(SafePaths.matches("../" + "a".repeat(61), SafePaths.SHA256));
        assertFalse(SafePaths.matches(null, SafePaths.SHA256));
    }

}
