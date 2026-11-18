package comedymonkey.dailies.compat.voicechat;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the voice chat look keeps of the recording's voice chat messages */
class RecordedVoiceChatTest {

    private static final UUID ALICE = UUID.fromString("0b1e6f0e-5a52-4c61-9a37-0c1d2e3f4a5b");
    private static final UUID BOB = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");
    private static final UUID CAROL = UUID.fromString("16fd2706-8baf-433b-82eb-8c7fada847da");
    private static final UUID TEAM = UUID.fromString("886313e1-3b8a-5372-9b90-0c9aee199e5d");
    private static final UUID OTHERS = UUID.fromString("a8098c1a-f86e-11da-bd1a-00112444be1e");

    @Test
    void statesAreKeptOneByOneAndReplacedAllAtOnce() {
        RecordedVoiceChat<String, String> voice = new RecordedVoiceChat<>();
        assertTrue(voice.isEmpty());
        voice.state(ALICE, "alice talking");
        voice.state(BOB, "bob muted");
        voice.state(ALICE, "alice off");
        assertEquals(Map.of(ALICE, "alice off", BOB, "bob muted"), voice.states());

        Map<UUID, String> all = new LinkedHashMap<>();
        all.put(CAROL, "carol");
        all.put(BOB, "bob back");
        voice.states(all);
        // Those not among them have no state any more, as voice chat takes it
        assertEquals(List.of(CAROL, BOB), List.copyOf(voice.states().keySet()));
        assertEquals("bob back", voice.states().get(BOB));

        voice.removeState(CAROL);
        voice.removeState(ALICE);
        assertEquals(Map.of(BOB, "bob back"), voice.states());
    }

    @Test
    void groupsAndTheRecordersOwnGroupAreKept() {
        RecordedVoiceChat<String, String> voice = new RecordedVoiceChat<>();
        voice.addGroup(TEAM, "team");
        voice.addGroup(OTHERS, "others");
        voice.joined(TEAM);
        assertEquals(TEAM, voice.group());
        assertEquals(List.of(TEAM, OTHERS), List.copyOf(voice.groups().keySet()));

        voice.removeGroup(OTHERS);
        assertEquals(Map.of(TEAM, "team"), voice.groups());
        voice.joined(null);
        assertNull(voice.group());
        assertEquals(Map.of(TEAM, "team"), voice.groups());
    }

    @Test
    void aSnapshotStartsFromNothing() {
        RecordedVoiceChat<String, String> voice = new RecordedVoiceChat<>();
        voice.state(ALICE, "alice");
        voice.addGroup(TEAM, "team");
        voice.joined(TEAM);
        voice.clear();
        assertTrue(voice.isEmpty());
        assertTrue(voice.states().isEmpty() && voice.groups().isEmpty());
        assertNull(voice.group());
    }

    @Test
    void everyChangeIsCountedSoItIsCopiedOutAgain() {
        RecordedVoiceChat<String, String> voice = new RecordedVoiceChat<>();
        int before = voice.changes();
        voice.state(ALICE, "alice");
        int after = voice.changes();
        assertNotEquals(before, after);
        // Taking away what is not there changes nothing
        voice.removeState(BOB);
        voice.removeGroup(TEAM);
        assertEquals(after, voice.changes());
        voice.joined(TEAM);
        assertNotEquals(after, voice.changes());
        int joined = voice.changes();
        voice.clear();
        assertNotEquals(joined, voice.changes());
    }

}
