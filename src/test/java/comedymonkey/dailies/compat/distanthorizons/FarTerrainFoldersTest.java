package comedymonkey.dailies.compat.distanthorizons;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

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

}
