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
