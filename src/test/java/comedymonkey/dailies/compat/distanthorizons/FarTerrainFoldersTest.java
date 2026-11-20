package comedymonkey.dailies.compat.distanthorizons;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

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
     * A folder Distant Horizons still has a file open in cannot be renamed onto itself on Windows, which is what Distant
     * Horizons does before it opens one: a spare beside it is used, and dropped once the first choice is free again
     */
    @Test
    void aFolderStillHeldOpenIsPassedOverForASpareUntilItIsFree() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows"),
            "only Windows refuses to rename a folder with a file open in it");
        Path wanted = this.game.resolve("dailies/dh/worlds/abc-world/minecraft_overworld");
        Files.createDirectories(wanted);
        Path database = wanted.resolve("DistantHorizons.sqlite");
        Files.writeString(database, "far terrain");
        List<String> filled = new ArrayList<>();
        FarTerrainFolders.Filler filler = (folder, spare) -> {
            filled.add(folder.getFileName() + (spare ? " spare" : ""));
            if (spare) {
                Files.copy(database, folder.resolve("DistantHorizons.sqlite"));
            }
        };

        assertEquals(wanted, FarTerrainFolders.free(wanted, filler));
        try (FileChannel held = FileChannel.open(database, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            assertFalse(FarTerrainFolders.renamable(wanted));
            Path spare = FarTerrainFolders.free(wanted, filler);
            assertEquals(FarTerrainFolders.spare(wanted, 1), spare);
            assertTrue(FarTerrainFolders.renamable(spare));
            assertEquals("far terrain", Files.readString(spare.resolve("DistantHorizons.sqlite")));
        }
        assertEquals(wanted, FarTerrainFolders.free(wanted, filler));
        assertFalse(Files.exists(FarTerrainFolders.spare(wanted, 1)), "the spare is dropped once the first choice is free");
        assertEquals(List.of("minecraft_overworld", "minecraft_overworld-spare1 spare", "minecraft_overworld"), filled);
    }

    /** Off Windows a held file never stops a rename, and the first choice is always taken */
    @Test
    void aFreeFolderIsTakenAsItIs() throws Exception {
        Path wanted = this.game.resolve("dailies/dh/recording/level");
        List<Boolean> spares = new ArrayList<>();
        assertEquals(wanted, FarTerrainFolders.free(wanted, (folder, spare) -> spares.add(spare)));
        assertTrue(Files.isDirectory(wanted));
        assertEquals(List.of(false), spares);
    }

}
