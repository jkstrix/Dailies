package comedymonkey.dailies.file;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A recording from elsewhere names its resource packs by their hash, and by nothing that could become a path */
class RecordingManifestTest {

    private static final String HASH = "0123456789abcdef".repeat(4);

    private static RecordingManifest withPack(String sha256) {
        RecordingManifest manifest = new RecordingManifest();
        manifest.id = "a-recording";
        RecordingManifest.Pack pack = new RecordingManifest.Pack();
        pack.key = "server";
        pack.sha256 = sha256;
        pack.size = 10;
        manifest.packs.add(pack);
        return manifest;
    }

    @Test
    void aPackNamedByItsHashIsRead() throws Exception {
        RecordingManifest read = RecordingManifest.fromBytes(withPack(HASH).toBytes());
        assertEquals(1, read.packs.size());
        assertEquals(HASH, read.packs.get(0).sha256);
    }

    @Test
    void aPackNamedByAnythingElseIsRefused() {
        for (String bad : new String[]{"../../evil", "C:\\Windows\\evil", "/etc/evil", HASH.toUpperCase(java.util.Locale.ROOT), HASH.substring(1), HASH + "0", ""}) {
            RecordingFormatException refused = assertThrows(RecordingFormatException.class, () -> RecordingManifest.fromBytes(withPack(bad).toBytes()), bad);
            assertEquals(RecordingFormatException.Reason.DAMAGED, refused.reason());
        }
    }

    @Test
    void aPackWithNoHashOrNoEntryIsRefused() {
        String noHash = "{\"id\":\"a\",\"packs\":[{\"key\":\"server\",\"size\":1}]}";
        // The field's own default is the empty string, which is not a hash either
        assertThrows(RecordingFormatException.class, () -> RecordingManifest.fromBytes(noHash.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String nullHash = "{\"id\":\"a\",\"packs\":[{\"key\":\"server\",\"sha256\":null}]}";
        assertThrows(RecordingFormatException.class, () -> RecordingManifest.fromBytes(nullHash.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String nullPack = "{\"id\":\"a\",\"packs\":[null]}";
        assertThrows(RecordingFormatException.class, () -> RecordingManifest.fromBytes(nullPack.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

}
