package comedymonkey.dailies.playback;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which chunks of a kept world are exactly what a snapshot would build, light included */
class KeptChunksTest {

    private static final long CHUNK = 7L;
    private static final long NEXT_DOOR = 8L;
    private static final String STORED = "aa01";
    private static final String LATER_LIGHT = "bb02";
    private static final String OTHER_LIGHT = "cc03";

    /** Read at a later snapshot: the stored chunk, then the light it had by then on top */
    private static KeptChunks builtWithLight() {
        KeptChunks chunks = new KeptChunks();
        chunks.snapshotBegins();
        chunks.built(CHUNK, STORED);
        chunks.lit(CHUNK, LATER_LIGHT);
        assertTrue(chunks.unlit().isEmpty());
        return chunks;
    }

    @Test
    void aJumpBackToTheStoredChunkAlonePutsItBack() {
        KeptChunks chunks = builtWithLight();
        assertEquals(STORED, chunks.clean(CHUNK));
        assertEquals(LATER_LIGHT, chunks.lightOn(CHUNK));
        // The earlier snapshot names the stored chunk and gives it no light: kept as it is it would keep the later light
        chunks.snapshotBegins();
        assertEquals(CHUNK, chunks.chunkOf(STORED));
        chunks.kept(CHUNK, STORED);
        assertEquals(Map.of(CHUNK, STORED), chunks.unlit());
        // Put back, it is the stored chunk alone again
        chunks.built(CHUNK, STORED);
        assertNull(chunks.lightOn(CHUNK));
        chunks.snapshotBegins();
        chunks.kept(CHUNK, STORED);
        assertTrue(chunks.unlit().isEmpty());
    }

    @Test
    void theSameLightAgainNeedNotBeGiven() {
        KeptChunks chunks = builtWithLight();
        chunks.snapshotBegins();
        chunks.kept(CHUNK, STORED);
        assertEquals(KeptChunks.Light.LEAVE_OUT, chunks.light(CHUNK, LATER_LIGHT));
        assertTrue(chunks.unlit().isEmpty());
        assertEquals(LATER_LIGHT, chunks.lightOn(CHUNK));
    }

    @Test
    void otherLightHasTheStoredChunkPutBackFirst() {
        KeptChunks chunks = builtWithLight();
        chunks.snapshotBegins();
        chunks.kept(CHUNK, STORED);
        KeptChunks.Light choice = chunks.light(CHUNK, OTHER_LIGHT);
        assertFalse(choice.leaveOut());
        assertEquals(STORED, choice.putBack());
        // Once given its light the chunk is no longer waiting for any
        assertTrue(chunks.unlit().isEmpty());
        chunks.built(CHUNK, STORED);
        chunks.lit(CHUNK, OTHER_LIGHT);
        assertEquals(OTHER_LIGHT, chunks.lightOn(CHUNK));
    }

    @Test
    void lightAsAPacketOfItsOwnIsNeverTakenForTheSame() {
        KeptChunks chunks = builtWithLight();
        chunks.snapshotBegins();
        chunks.kept(CHUNK, STORED);
        assertEquals(STORED, chunks.light(CHUNK, null).putBack());
    }

    @Test
    void aStoredChunkKeptWithItsOwnLightStaysAsItIs() {
        KeptChunks chunks = new KeptChunks();
        chunks.built(CHUNK, STORED);
        chunks.snapshotBegins();
        chunks.kept(CHUNK, STORED);
        assertTrue(chunks.unlit().isEmpty());
        // Light for a chunk the snapshot built, not kept, is simply given
        assertEquals(KeptChunks.Light.GIVE, chunks.light(NEXT_DOOR, LATER_LIGHT));
    }

    @Test
    void aChangeNextDoorForgetsTheChunkAndItsLight() {
        KeptChunks chunks = builtWithLight();
        chunks.changed(CHUNK);
        assertNull(chunks.clean(CHUNK));
        assertNull(chunks.sameBlocks(CHUNK));
        assertNull(chunks.lightOn(CHUNK));
        assertNull(chunks.chunkOf(STORED));
        assertTrue(chunks.isEmpty());
    }

    @Test
    void whatABlockEntityHoldsLeavesWhatIsDrawnAndItsLight() {
        KeptChunks chunks = builtWithLight();
        chunks.changedHeld(CHUNK);
        assertNull(chunks.clean(CHUNK));
        assertEquals(STORED, chunks.sameBlocks(CHUNK));
        assertEquals(LATER_LIGHT, chunks.lightOn(CHUNK));
        // The trimmed recording's writer only names a chunk that is clean, so it never names this light either
        assertEquals(CHUNK, chunks.chunkOf(STORED));
    }

    @Test
    void aChunkBuiltFromAPacketOfItsOwnHasNoStoredFormOrLight() {
        KeptChunks chunks = builtWithLight();
        chunks.built(CHUNK, null);
        assertNull(chunks.clean(CHUNK));
        assertNull(chunks.lightOn(CHUNK));
        assertNull(chunks.chunkOf(STORED));
        // Light for it does not make it a stored chunk with light on top
        chunks.lit(CHUNK, LATER_LIGHT);
        assertNull(chunks.lightOn(CHUNK));
    }

    @Test
    void namesAreRememberedOnlyWhileTheirChunkIsAsStored() {
        KeptChunks chunks = new KeptChunks();
        for (int i = 0; i < 1000; i++) {
            chunks.built(CHUNK, "stored" + i);
        }
        assertEquals(1, chunks.namesKnown());
        assertEquals(CHUNK, chunks.chunkOf("stored999"));
        assertNull(chunks.chunkOf("stored0"));
        chunks.changed(CHUNK);
        assertEquals(0, chunks.namesKnown());
    }

    @Test
    void throwingTheWorldAwayForgetsEverything() {
        KeptChunks chunks = builtWithLight();
        chunks.snapshotBegins();
        chunks.kept(CHUNK, STORED);
        chunks.clear();
        assertTrue(chunks.isEmpty());
        assertNull(chunks.lightOn(CHUNK));
        assertTrue(chunks.unlit().isEmpty());
    }

}
