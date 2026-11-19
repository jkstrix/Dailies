package comedymonkey.dailies.selftest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A run of only some groups brings in the groups they work on (-PdailiesOnly) */
class StepGroupsTest {

    @Test
    void aGroupOfItsOwnBringsNothingElse() {
        assertEquals(Set.of("trains"), StepGroups.withNeeds(List.of("trains")));
    }

    @Test
    void theEditorBringsTheExportPlaybackTheRecordingAndItsWorld() {
        assertEquals(Set.of("editor", "export", "playback", "recording", "world"), StepGroups.withNeeds(List.of("editor")));
    }

    @Test
    void theLibraryBringsTheScriptedRecordingThePackRecordingAndTheMoments() {
        assertEquals(Set.of("library", "recording", "packs", "moments", "world"), StepGroups.withNeeds(List.of("library")));
    }

    @Test
    void groupsAskedForTogetherShareWhatTheyNeed() {
        assertEquals(Set.of("balloon", "renderer", "playback", "recording", "world", "edges"), StepGroups.withNeeds(List.of("balloon", "renderer", "edges")));
    }
}
