package comedymonkey.dailies.selftest;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When the scripted test's recorded fixtures are used again and when they are recorded afresh */
class FixtureRulesTest {

    private static Map<String, byte[]> classes(String... pairs) {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            classes.put(pairs[i], pairs[i + 1].getBytes(StandardCharsets.UTF_8));
        }
        return classes;
    }

    @Test
    void theSameClassesGiveTheSameFingerprintInAnyOrder() {
        Map<String, byte[]> one = classes("a/A.class", "alpha", "b/B.class", "beta");
        Map<String, byte[]> two = new HashMap<>();
        two.put("b/B.class", "beta".getBytes(StandardCharsets.UTF_8));
        two.put("a/A.class", "alpha".getBytes(StandardCharsets.UTF_8));
        assertNotNull(FixtureRules.fingerprint(one));
        assertEquals(FixtureRules.fingerprint(one), FixtureRules.fingerprint(two));
    }

    @Test
    void anyChangeGivesAnotherFingerprint() {
        String before = FixtureRules.fingerprint(classes("a/A.class", "alpha", "b/B.class", "beta"));
        assertNotEquals(before, FixtureRules.fingerprint(classes("a/A.class", "alphb", "b/B.class", "beta")), "a changed byte");
        assertNotEquals(before, FixtureRules.fingerprint(classes("a/A.class", "alpha")), "a class gone");
        assertNotEquals(before, FixtureRules.fingerprint(classes("a/A.class", "alpha", "b/B.class", "beta", "c/C.class", "")), "a class added, even an empty one");
        assertNotEquals(before, FixtureRules.fingerprint(classes("a/C.class", "alpha", "b/B.class", "beta")), "a class renamed");
        // Bytes moved from a name to its file are not the same thing
        assertNotEquals(FixtureRules.fingerprint(classes("ab", "c")), FixtureRules.fingerprint(classes("a", "bc")));
    }

    @Test
    void noClassesIsNoFingerprint() {
        assertNull(FixtureRules.fingerprint(Map.of()));
    }

    @Test
    void theRecordingCodeIsPickedOutByPath() {
        assertTrue(FixtureRules.writesRecordings("comedymonkey/dailies/capture/Recorder.class"));
        assertTrue(FixtureRules.writesRecordings("comedymonkey/dailies/capture/Recorder$State.class"));
        assertTrue(FixtureRules.writesRecordings("comedymonkey/dailies/file/RecordingWriter.class"));
        assertTrue(FixtureRules.writesRecordings("comedymonkey/dailies/packs/GamePacks.class"));
        assertTrue(FixtureRules.writesRecordings("comedymonkey/dailies/mixin/capture/ConnectionMixin.class"));
        assertTrue(FixtureRules.writesRecordings("comedymonkey/dailies/sound/VoiceCapture.class"));
        assertTrue(FixtureRules.writesRecordings("comedymonkey/dailies/sound/VoiceCapture$1.class"));
        assertTrue(FixtureRules.writesRecordings("comedymonkey/dailies/extras/HudCapture.class"));
        assertTrue(FixtureRules.writesRecordings("comedymonkey/dailies/extras/SkinCapture.class"));
        assertTrue(FixtureRules.writesRecordings("comedymonkey/dailies/extras/EntityClocks.class"));

        assertFalse(FixtureRules.writesRecordings("comedymonkey/dailies/playback/Playback.class"), "playback is what the fixtures test");
        assertFalse(FixtureRules.writesRecordings("comedymonkey/dailies/sound/VoicePlayback.class"));
        assertFalse(FixtureRules.writesRecordings("comedymonkey/dailies/sound/VoiceCaptureLater.class"), "another class that only begins the same");
        assertFalse(FixtureRules.writesRecordings("comedymonkey/dailies/extras/HudLayer.class"));
        assertFalse(FixtureRules.writesRecordings("comedymonkey/dailies/mixin/playback/SomeMixin.class"));
        assertFalse(FixtureRules.writesRecordings("comedymonkey/dailies/capture/notes.txt"), "only class files");
    }

    private static final FixtureRules.Stored GOOD = new FixtureRules.Stored(3, "abc");

    private static String why(boolean asked, boolean there, FixtureRules.Stored stored, int version, String fingerprint) {
        return FixtureRules.recordAgain(asked, "dailies.test.recordTours", there, stored, stored == null ? "broken" : null, version, fingerprint);
    }

    @Test
    void aFixtureThatStillFitsIsUsedAgain() {
        assertNull(why(false, true, GOOD, 3, "abc"));
    }

    @Test
    void aFixtureIsRecordedAgainForEachReason() {
        assertTrue(why(true, true, GOOD, 3, "abc").contains("dailies.test.recordTours"), "asked for");
        assertTrue(why(false, false, GOOD, 3, "abc").contains("no fixture"), "missing");
        assertTrue(why(false, true, null, 3, "abc").contains("broken"), "unreadable");
        assertTrue(why(false, true, GOOD, 4, "abc").contains("version 3"), "staged by another version");
        assertTrue(why(false, true, GOOD, 3, "abd").contains("changed"), "recording code changed");
        assertTrue(why(false, true, new FixtureRules.Stored(3, null), 3, "abc").contains("changed"), "made where the code could not be read, read now");
    }

    @Test
    void codeThatCannotBeReadNowIsNoReasonToRecordAgain() {
        assertNull(why(false, true, GOOD, 3, null));
    }

    @Test
    void askingOutranksEverythingAndAMissingFixtureOutranksTheRest() {
        assertTrue(why(true, false, null, 9, "zzz").startsWith("asked"));
        assertTrue(why(false, false, null, 9, "zzz").contains("no fixture"));
    }

    @Test
    void notesComeBackAsTheyWereWritten() throws Exception {
        StringWriter text = new StringWriter();
        try (TourFixtures.Out out = new TourFixtures.Out(new JsonWriter(text))) {
            out.json.beginArray();
            out.string("repeated");
            out.string("12");
            out.string("repeated");
            out.string(null);
            out.strings(List.of("12", "other", "repeated"));
            out.shorts(new short[]{1, -2, 300});
            out.longs(new long[]{Long.MIN_VALUE, 0, 7});
            out.ints(new int[]{-1, 65536});
            out.bytes(null);
            out.json.value(Double.NaN);
            out.json.value((double) 0.1F);
            out.json.endArray();
        }
        assertTrue(text.toString().indexOf("repeated") == text.toString().lastIndexOf("repeated"), "a string said again is said by number: " + text);
        try (TourFixtures.In in = new TourFixtures.In(new JsonReader(new StringReader(text.toString())))) {
            in.json.beginArray();
            assertEquals("repeated", in.string());
            assertEquals("12", in.string());
            assertEquals("repeated", in.string());
            assertNull(in.string());
            assertEquals(List.of("12", "other", "repeated"), in.strings());
            assertArrayEquals(new short[]{1, -2, 300}, in.shorts());
            assertArrayEquals(new long[]{Long.MIN_VALUE, 0, 7}, in.longs());
            assertArrayEquals(new int[]{-1, 65536}, in.ints());
            assertNull(in.bytes());
            assertTrue(Double.isNaN(in.decimal()));
            assertEquals(0.1F, (float) in.decimal());
            assertFalse(in.more());
            in.json.endArray();
        }
    }
}
