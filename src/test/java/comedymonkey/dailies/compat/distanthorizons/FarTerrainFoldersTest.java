package comedymonkey.dailies.compat.distanthorizons;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where a recording says the far terrain was kept: only ever a folder inside the game's folder */
class FarTerrainFoldersTest {

    @TempDir
    Path game;

    /** Somewhere that is not the game's folder */
    @TempDir
    Path elsewhere;

    @Test
    void aFolderInsideTheGamesFolderIsWrittenDownAndBelieved() {
        Path store = this.game.resolve("saves/Kingdom/data/DistantHorizons");
        assertEquals("saves/Kingdom/data/DistantHorizons", FarTerrainFolders.toRecord(this.game, store));
        assertEquals(store.toAbsolutePath().normalize(), FarTerrainFolders.recorded(this.game, "saves/Kingdom/data/DistantHorizons"));
        assertEquals(store.toAbsolutePath().normalize(), FarTerrainFolders.recorded(this.game, "saves\\Kingdom\\data\\..\\data\\DistantHorizons".replace('\\', java.io.File.separatorChar)));
    }

    @Test
    void aFolderAnywhereElseIsNeitherWrittenDownNorBelieved() {
        assertNull(FarTerrainFolders.toRecord(this.game, this.elsewhere.resolve("DistantHorizons")));
        assertNull(FarTerrainFolders.toRecord(this.game, this.game));
        assertNull(FarTerrainFolders.recorded(this.game, this.elsewhere.toAbsolutePath().toString()));
        assertNull(FarTerrainFolders.recorded(this.game, "/etc/far"));
        assertNull(FarTerrainFolders.recorded(this.game, "C:/Users/someone/far"));
        assertNull(FarTerrainFolders.recorded(this.game, "D:far"));
        assertNull(FarTerrainFolders.recorded(this.game, "\\\\server\\share\\DistantHorizons"));
        assertNull(FarTerrainFolders.recorded(this.game, "//server/share/DistantHorizons"));
        assertNull(FarTerrainFolders.recorded(this.game, "../other game/saves/x"));
        assertNull(FarTerrainFolders.recorded(this.game, "saves/../../x"));
        assertNull(FarTerrainFolders.recorded(this.game, ""));
        assertNull(FarTerrainFolders.recorded(this.game, null));
    }

    @Test
    void aRecordingTakenInFromASharePackIsKnownByWhereItLies() {
        Path recordings = this.game.resolve("dailies/recordings");
        assertTrue(FarTerrainFolders.shared(recordings, recordings.resolve("shared/Alex/Evening.dailies")));
        assertFalse(FarTerrainFolders.shared(recordings, recordings.resolve("Evening.dailies")));
        assertFalse(FarTerrainFolders.shared(recordings, recordings.resolve("2026/shared notes/Evening.dailies")));
    }


    /**
     * On Windows Distant Horizons is given a junction to the folder: a file still open in the folder stops the folder
     * itself being renamed onto itself, which Distant Horizons does before opening it, but never the junction
     */
    @Test
    void aJunctionCanBeRenamedOntoItselfWhileTheFolderHasAFileOpen() throws Exception {
        Assumptions.assumeTrue(FarTerrainFolders.windows(), "only Windows refuses to rename a folder with a file open in it");
        Path folder = this.game.resolve("dailies/dh/worlds/abc-world/minecraft_overworld");
        Path link = this.game.resolve("dailies/dh/links/worlds/abc-world/minecraft_overworld");
        Path given = FarTerrainFolders.openable(folder, link);
        assertEquals(link, given);
        assertTrue(Files.isSameFile(given, folder));
        Files.writeString(given.resolve("DistantHorizons.sqlite"), "far terrain");
        try (FileChannel held = FileChannel.open(given.resolve("DistantHorizons.sqlite"), StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            assertFalse(FarTerrainFolders.renamable(folder));
            assertTrue(FarTerrainFolders.renamable(given));
            // Opened again, as after the next jump: the same junction, and the same one file
            assertEquals(link, FarTerrainFolders.openable(folder, link));
            assertEquals("far terrain", Files.readString(folder.resolve("DistantHorizons.sqlite")));
        }
    }

    /** A junction left leading somewhere else is replaced, and what it led to is untouched */
    @Test
    void aJunctionToSomewhereElseIsReplaced() throws Exception {
        Assumptions.assumeTrue(FarTerrainFolders.windows(), "junctions are made only on Windows");
        Path other = this.game.resolve("dailies/dh/worlds/other/minecraft_overworld");
        Path folder = this.game.resolve("dailies/dh/worlds/abc-world/minecraft_overworld");
        Path link = this.game.resolve("dailies/dh/links/worlds/abc-world/minecraft_overworld");
        FarTerrainFolders.openable(other, link);
        Files.writeString(other.resolve("keep.txt"), "kept");
        assertEquals(link, FarTerrainFolders.openable(folder, link));
        assertTrue(Files.isSameFile(link, folder));
        assertEquals("kept", Files.readString(other.resolve("keep.txt")));
    }

    /** The spare copies an older Dailies made beside a folder go once nothing holds them */
    @Test
    void spareCopiesBesideTheFolderAreDeleted() throws Exception {
        Path folder = this.game.resolve("dailies/dh/worlds/abc-world/minecraft_overworld");
        Path spare = folder.resolveSibling("minecraft_overworld-spare2");
        Files.createDirectories(spare);
        Files.writeString(spare.resolve("DistantHorizons.sqlite"), "old copy");
        FarTerrainFolders.openable(folder, this.game.resolve("dailies/dh/links/worlds/abc-world/minecraft_overworld"));
        assertFalse(Files.exists(spare));
        assertTrue(Files.isDirectory(folder));
    }

}
