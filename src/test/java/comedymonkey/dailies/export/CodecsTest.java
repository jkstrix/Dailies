package comedymonkey.dailies.export;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which codecs are offered, what stands in for one that cannot be made (EXP-1, EXP-14), and how files are named */
class CodecsTest {

    /** The encoders of the build Dailies carries today */
    private static final Set<String> BUILD = Set.of("libaom-av1", "libsvtav1", "av1_nvenc", "av1_mf", "dnxhd", "libopenh264", "h264_d3d12va", "h264_mf", "h264_nvenc",
        "h264_qsv", "h264_vulkan", "hevc_d3d12va", "hevc_mf", "hevc_nvenc", "hevc_qsv", "hevc_vulkan", "prores", "prores_aw", "prores_ks", "libvpx", "libvpx-vp9", "vp9_qsv",
        "aac", "libopus", "pcm_s16le");

    private static List<Codecs.Choice> machine(String... workingHardware) {
        Set<String> works = Set.of(workingHardware);
        return Codecs.working(BUILD, works::contains);
    }

    private static List<String> encoders(List<Codecs.Choice> choices) {
        return choices.stream().map(Codecs.Choice::encoder).toList();
    }

    @Test
    void softwareH264IsNeverOffered() {
        assertFalse(encoders(Codecs.candidates(BUILD)).contains("libopenh264"));
        List<Codecs.Choice> none = machine();
        assertFalse(Codecs.has(none, "h264"), "a machine without a hardware encoder has no H.264");
        assertFalse(Codecs.has(none, "h265"));
        assertEquals(List.of("libsvtav1", "libaom-av1", "libvpx-vp9", "prores_ks", "dnxhd"), encoders(none));
        assertTrue(none.stream().noneMatch(Codecs.Choice::hardware));
        assertNotNull(Codecs.whyMissing(none, "h264"));
        assertTrue(Codecs.whyMissing(none, "h264").contains("hardware"), "the reason says a hardware encoder is what is missing");
        for (Codecs.Choice choice : machine("h264_nvenc", "h264_mf", "hevc_nvenc", "av1_nvenc", "h264_qsv")) {
            if (choice.codec().equals("h264") || choice.codec().equals("h265")) {
                assertTrue(choice.hardware(), choice.encoder() + " is offered for " + choice.codec() + " and is not hardware");
            }
        }
    }

    @Test
    void theFastestWorkingChoiceComesFirstAndSaysWhatItRunsOn() {
        List<Codecs.Choice> nvidia = machine("h264_nvenc", "hevc_nvenc", "av1_nvenc", "h264_mf");
        assertEquals("h264_nvenc", nvidia.get(0).encoder());
        assertEquals("H.264, NVIDIA hardware", nvidia.get(0).label());
        assertEquals(List.of("h264_nvenc", "h264_mf"), encoders(Codecs.forCodec(nvidia, "h264")));
        assertEquals(List.of("av1_nvenc", "libsvtav1", "libaom-av1"), encoders(Codecs.forCodec(nvidia, "av1")), "hardware before software, the quicker software first");
        assertNull(Codecs.whyMissing(nvidia, "h264"));
        assertEquals("AV1, software (SVT-AV1)", machine().get(0).label());
        assertEquals("H.264, Intel hardware", machine("h264_qsv").get(0).label());
        // An encoder in the build whose trial failed is not offered
        assertFalse(encoders(machine("h264_nvenc")).contains("h264_qsv"));
    }

    @Test
    void aPresetFallsBackDownTheListAndSaysSo() {
        List<Codecs.Choice> none = machine();
        Codecs.Pick youtube = Codecs.pick(List.of("h264", "av1"), "mp4", none);
        assertEquals("av1", youtube.codec());
        assertEquals("mp4", youtube.container());
        assertFalse(youtube.fellBack(), "AV1 is the preset's own second choice");
        assertNull(youtube.words(none));

        Codecs.Pick onlyH264 = Codecs.pick(List.of("h264"), "mov", none);
        assertEquals("av1", onlyH264.codec());
        assertEquals("mp4", onlyH264.container(), "MOV cannot hold AV1, so the file follows the codec");
        assertTrue(onlyH264.fellBack());
        assertTrue(onlyH264.words(none).contains("H.264") && onlyH264.words(none).contains("AV1") && onlyH264.words(none).contains("hardware"), onlyH264.words(none));

        Codecs.Pick has = Codecs.pick(List.of("h264", "av1"), "mp4", machine("h264_nvenc"));
        assertEquals("h264", has.codec());
        assertNull(has.words(machine("h264_nvenc")));

        // Down the list: AV1, then VP9, then the editing masters
        List<Codecs.Choice> noAv1 = Codecs.working(Set.of("libvpx-vp9", "prores_ks", "dnxhd"), encoder -> false);
        assertEquals("vp9", Codecs.pick(List.of("h264", "av1"), "mp4", noAv1).codec());
        List<Codecs.Choice> mastersOnly = Codecs.working(Set.of("prores_ks", "dnxhd"), encoder -> false);
        Codecs.Pick master = Codecs.pick(List.of("h264", "av1"), "mp4", mastersOnly);
        assertEquals("prores", master.codec());
        assertEquals("mov", master.container());
        assertEquals("dnxhr", Codecs.pick(List.of("prores", "dnxhr"), "mov", Codecs.working(Set.of("dnxhd"), encoder -> false)).codec());

        Codecs.Pick nothing = Codecs.pick(List.of("h264"), "mp4", List.of());
        assertNull(nothing.codec());
        assertNotNull(nothing.words(List.of()));
    }

    @Test
    void filesHoldWhatTheyCanHold() {
        assertTrue(Codecs.holds("mov", "prores") && Codecs.holds("mov", "dnxhr") && Codecs.holds("mkv", "prores"));
        assertFalse(Codecs.holds("mp4", "prores"));
        assertFalse(Codecs.holds("webm", "h264"));
        assertEquals("mov", Codecs.containerFor("dnxhr", "mp4"));
        assertEquals("mkv", Codecs.containerFor("dnxhr", "mkv"));
        assertEquals("webm", Codecs.containerFor("vp9", "mov"));
        assertTrue(Codecs.holdsNamedTracks("mkv") && Codecs.holdsNamedTracks("mov"));
        assertFalse(Codecs.holdsNamedTracks("mp4"));
        assertEquals("hevc", Codecs.probeName("h265"));
        assertEquals("dnxhd", Codecs.probeName("dnxhr"));
    }

    @Test
    void encoderArgumentsFitTheEncoder() {
        assertEquals(List.of("-c:v", "prores_ks", "-profile:v", "3", "-pix_fmt", "yuv422p10le", "-vendor", "apl0"), Codecs.videoArguments("prores_ks", 5_000_000L, false));
        assertEquals(List.of("-c:v", "dnxhd", "-profile:v", "dnxhr_hq", "-pix_fmt", "yuv422p"), Codecs.videoArguments("dnxhd", 0L, false));
        List<String> mediaFoundation = Codecs.videoArguments("h264_mf", 8_000_000L, false);
        assertTrue(mediaFoundation.containsAll(List.of("-hw_encoding", "1", "-b:v", "8000000")), "Media Foundation is made to use hardware or fail");
        assertTrue(Codecs.videoArguments("libvpx-vp9", 1L, true).contains("yuva420p"));
        assertTrue(Codecs.trialArguments("ffmpeg", "h264_nvenc").containsAll(List.of("-c:v", "h264_nvenc", "-f", "null")));
    }

    @Test
    void nvidiaEncodersSplitFramesAcrossTheirUnits() {
        assertEquals(List.of(3, 1, -1), Codecs.splitModes("av1_nvenc", true), "three strips first, then the driver's choice, then none");
        assertEquals(List.of(3, 1, -1), Codecs.splitModes("hevc_nvenc", true));
        assertEquals(List.of(-1), Codecs.splitModes("h264_nvenc", true), "H.264 cannot be split");
        assertEquals(List.of(-1), Codecs.splitModes("libsvtav1", true));
        assertEquals(List.of(-1), Codecs.splitModes("av1_nvenc", false), "the setting turns it off");
        List<String> split = Codecs.videoArguments("av1_nvenc", 80_000_000L, false, 3);
        assertEquals(List.of("-split_encode_mode", "3"), split.subList(split.size() - 2, split.size()));
        assertFalse(Codecs.videoArguments("av1_nvenc", 80_000_000L, false, -1).contains("-split_encode_mode"));
    }

    @Test
    void bitratesAreReadAndWritten() {
        assertEquals(20_000_000L, Codecs.parseBitrate("20m"));
        assertEquals(800_000L, Codecs.parseBitrate(" 800K "));
        assertEquals(5_000_000L, Codecs.parseBitrate("5000000"));
        assertEquals(-1L, Codecs.parseBitrate("fast"));
        assertEquals(-1L, Codecs.parseBitrate("-3m"));
        assertEquals("20m", Codecs.bitrateWords(20_000_000L));
        assertEquals("19.9m", Codecs.bitrateWords(19_900_000L));
        assertEquals("800k", Codecs.bitrateWords(800_000L));
        assertEquals(1_100_000L, Codecs.bitrate("moderate", "av1", 1280, 720, 30.0));
    }

    @Test
    void fileNamesAreFilledInAndKeptSafe() {
        LocalDateTime when = LocalDateTime.of(2026, 11, 17, 9, 5, 7);
        Map<String, String> values = Map.of("preset", "YouTube 1080p60", "scene", "Scene 1", "size", "1920x1080", "fps", "60", "codec", "h264", "recording", "Flight");
        assertEquals("2026-11-17_09.05.07", NamePattern.fill("{date}_{time}", when, values));
        assertEquals("Flight - Scene 1 - YouTube 1080p60 1920x1080 60 h264", NamePattern.fill("{recording} - {scene} - {preset} {size} {fps} {codec}", when, values));
        assertEquals("clip", NamePattern.fill("clip{clip_title}", when, values), "a place nobody filled is left out");
        assertEquals("_.._secret", NamePattern.fill("../../secret", when, values), "a name cannot lead out of its folder");
        assertEquals("a_b_c", NamePattern.fill("a:b?c", when, values));
        assertEquals("export", NamePattern.fill("  ", when, values));
        assertEquals("export", NamePattern.fill("...", when, values));
        assertEquals("With_ a slash", NamePattern.fill("{scene}", when, Map.of("scene", "With/ a slash")));
        assertTrue(NamePattern.fill("x".repeat(300), when, values).length() <= 120);
    }

    @Test
    void aTakenNameGetsANumber() {
        Set<String> there = Set.of("clip.mp4", "clip (2).mp4", "frames");
        assertEquals("other.mp4", NamePattern.unused("other.mp4", there::contains));
        assertEquals("clip (3).mp4", NamePattern.unused("clip.mp4", there::contains));
        assertEquals("frames (2)", NamePattern.unused("frames", there::contains));
    }

}
