package comedymonkey.dailies.moments.storage;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Session ids come from tools and pages, so only plain names lead to a session's folder */
class MomentsStorageTest {

    @Test
    void ordinaryIdsLeadToTheirFolder() throws Exception {
        Path root = Files.createTempDirectory("dailies-moments");
        Path base = root.toAbsolutePath().normalize();
        assertEquals(base.resolve("20261118-201500"), MomentsStorage.sessionDir(root, "20261118-201500"));
        assertEquals(base.resolve("20261118-201500-2"), MomentsStorage.sessionDir(root, "20261118-201500-2"));
        // A friend's session carries their name
        assertEquals(base.resolve("20261118-201500-Steve_99"), MomentsStorage.sessionDir(root, "20261118-201500-Steve_99"));
    }

    @Test
    void anythingThatCouldLeadElsewhereIsRefused() throws Exception {
        Path root = Files.createTempDirectory("dailies-moments");
        for (String id : List.of("", ".", "..", "firsts", "a/b", "a\\b", "../x", "..\\x", "D:x", "C:", "x:y", "/etc", "\\\\server\\share",
                "with space", "tab\tin", "nul\0l", "a".repeat(81))) {
            assertNull(MomentsStorage.sessionDir(root, id), id);
        }
    }

    @Test
    void getSessionRefusesThemWithoutAnError() throws Exception {
        Path root = Files.createTempDirectory("dailies-moments");
        Files.createDirectories(root.resolve("real"));
        MomentsStorage storage = new MomentsStorage(root);
        // On Windows "D:x" would be a folder on another drive, and a colon elsewhere used to throw
        for (String id : List.of("D:x", "a:b", "..", "real/..", "firsts")) {
            assertNull(storage.getSession(id), id);
        }
    }

    @Test
    void aFriendsUntranscribedLinesAreNeverSentAnywhere() throws Exception {
        Path folder = Files.createTempDirectory("dailies-moments");
        Path dialogue = folder.resolve("dialogue.jsonl");
        Files.writeString(dialogue, String.join("\n",
            "{\"id\":\"d1\",\"status\":\"pending\",\"audio_file\":\"C:/Users/x/secret.wav\"}",
            "{\"id\":\"d2\",\"status\":\"done\",\"text\":\"hello\"}",
            "{\"id\":\"d3\",\"audio_file\":\"audio/a_1.wav\"}",
            "{\"id\":\"d4\",\"status\":\"failed\"}",
            "{\"id\":\"d5\",\"status\":\"emp") + "\n", StandardCharsets.UTF_8);

        MomentsShare.settleDialogue(dialogue);

        List<String> lines = Files.readAllLines(dialogue, StandardCharsets.UTF_8);
        assertEquals(5, lines.size());
        assertEquals("failed", status(lines.get(0)));
        assertEquals("done", status(lines.get(1)));
        assertEquals("failed", status(lines.get(2)), "a record with no status is pending");
        assertEquals("failed", status(lines.get(3)));
        // A torn line is left as it was
        assertEquals("{\"id\":\"d5\",\"status\":\"emp", lines.get(4));
    }

    private static String status(String line) {
        JsonObject record = JsonParser.parseString(line).getAsJsonObject();
        return record.get("status").getAsString();
    }

}
