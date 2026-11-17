package comedymonkey.dailies.export;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Export presets are kept, changed and brought back (specification EXP-14) */
class ExportPresetsTest {

    private static List<String> names(ExportPresets presets) {
        return presets.all().stream().map(ExportPreset::name).toList();
    }

    @Test
    void theBuiltInPresetsAreThereWithoutAFile(@TempDir Path folder) {
        ExportPresets presets = new ExportPresets(folder.resolve("export-presets.json"));
        assertEquals(List.of("YouTube 1080p60", "YouTube 4K60", "Shorts and Reels", "Editing master", "Smallest"), names(presets));
        ExportPreset shorts = presets.find("shorts and reels");
        assertNotNull(shorts, "a preset is found whatever the capitals");
        assertEquals(1080, shorts.width());
        assertEquals(1920, shorts.height());
        assertEquals(60.0, shorts.framesPerSecond());
        assertEquals(60, shorts.maxSeconds());
        ExportPreset master = presets.find("Editing master");
        assertEquals(List.of("prores", "dnxhr"), master.codecs());
        assertEquals("mov", master.container());
        assertTrue(master.stemFiles() && master.stemTracks(), "the editing master keeps the sound apart both ways");
        assertEquals(List.of("av1"), presets.find("Smallest").codecs());
        assertEquals("moderate", presets.find("Smallest").quality());
        assertEquals(List.of("h264", "av1"), presets.find("YouTube 4K60").codecs());
    }

    @Test
    void changesSurviveBeingWrittenAndReadBack(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("export-presets.json");
        ExportPresets presets = new ExportPresets(file);
        ExportPreset mine = new ExportPreset("Square", "", 1080, 1080, 30.0, "mkv", List.of("vp9"), "custom", 6_000_000L, true, false, "opus", true, false, 0);
        presets.save(mine);
        presets.rename("Smallest", "Tiny");
        presets.remove("YouTube 4K60");
        JsonObject change = new JsonObject();
        change.addProperty("framerate", 50);
        presets.save(ExportPreset.fromJson(change, presets.find("YouTube 1080p60")));
        presets.write();

        ExportPresets again = new ExportPresets(file);
        assertEquals(List.of("YouTube 1080p60", "Shorts and Reels", "Editing master", "Tiny", "Square"), names(again));
        assertEquals(mine, again.find("Square"));
        assertEquals(50.0, again.find("YouTube 1080p60").framesPerSecond());
        assertEquals("youtube_1080p60", again.find("YouTube 1080p60").builtIn(), "a changed built-in preset is still known for what it began as");
        assertEquals("smallest", again.find("Tiny").builtIn());
        assertNull(again.find("YouTube 4K60"), "a removed built-in preset stays away");
    }

    @Test
    void resetBringsTheBuiltInOnesBackAndKeepsTheOthers(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("export-presets.json");
        ExportPresets presets = new ExportPresets(file);
        presets.save(new ExportPreset("Square", "", 1080, 1080, 30.0, "mkv", List.of("vp9"), "high", 1L, true, true, "auto", false, false, 0));
        presets.remove("Editing master");
        presets.rename("Smallest", "Tiny");
        JsonObject change = new JsonObject();
        change.addProperty("width", 1280);
        presets.save(ExportPreset.fromJson(change, presets.find("YouTube 1080p60")));
        presets.reset();
        assertEquals(List.of("YouTube 1080p60", "YouTube 4K60", "Shorts and Reels", "Editing master", "Smallest", "Square"), names(presets));
        assertEquals(1920, presets.find("YouTube 1080p60").width());
        presets.write();
        assertEquals(names(presets), names(new ExportPresets(file)));
    }

    @Test
    void namesMustBeFreeAndPresetsMustExist(@TempDir Path folder) {
        ExportPresets presets = new ExportPresets(folder.resolve("export-presets.json"));
        assertThrows(IllegalArgumentException.class, () -> presets.rename("Smallest", "editing MASTER"));
        assertThrows(IllegalArgumentException.class, () -> presets.rename("No such", "Other"));
        assertThrows(IllegalArgumentException.class, () -> presets.remove("No such"));
        presets.rename("Smallest", "SMALLEST");
        assertEquals("SMALLEST", presets.find("smallest").name());
    }

    @Test
    void aFileThatCannotBeReadLeavesTheBuiltInOnes(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("export-presets.json");
        Files.writeString(file, "{ this is not json");
        assertEquals(5, new ExportPresets(file).all().size());
        Files.writeString(file, "{\"version\":1,\"presets\":[{\"name\":\"Odd\",\"width\":3},{\"name\":\"Fine\",\"width\":640,\"height\":360}],\"removed_built_in\":[\"smallest\"]}");
        ExportPresets presets = new ExportPresets(file);
        assertEquals(List.of("Fine", "YouTube 1080p60", "YouTube 4K60", "Shorts and Reels", "Editing master"), names(presets), "one bad preset does not cost the others");
    }

    @Test
    void valuesAreChecked() {
        ExportPreset base = ExportPresets.builtIn().get(0);
        for (String[] wrong : new String[][]{{"container", "avi"}, {"quality", "best"}, {"audio_codec", "mp3"}, {"separate_audio", "some"}, {"video_codecs", "x264"}, {"bitrate", "lots"}}) {
            JsonObject change = new JsonObject();
            change.addProperty(wrong[0], wrong[1]);
            assertThrows(IllegalArgumentException.class, () -> ExportPreset.fromJson(change, base), wrong[0] + " = " + wrong[1]);
        }
        JsonObject change = new JsonObject();
        change.addProperty("width", 8);
        assertThrows(IllegalArgumentException.class, () -> ExportPreset.fromJson(change, base));
        JsonObject text = new JsonObject();
        text.addProperty("width", "wide");
        assertThrows(IllegalArgumentException.class, () -> ExportPreset.fromJson(text, base));
        assertEquals(base, ExportPreset.fromJson(base.toJson(), ExportPresets.builtIn().get(3)), "a preset comes back from its JSON whatever it is read over");
    }

    @Test
    void aSummarySaysWhatAPresetMakes() {
        List<ExportPreset> made = ExportPresets.builtIn();
        assertEquals("1920x1080, 60 fps, H.264 or AV1 in MP4, high quality, AAC stereo", made.get(0).summary());
        assertEquals("1080x1920, 60 fps, H.264 or AV1 in MP4, high quality, AAC stereo, up to 60 s", made.get(2).summary());
        assertEquals("1920x1080, 60 fps, ProRes 422 HQ or DNxHR HQ in MOV, uncompressed stereo, separate sound tracks", made.get(3).summary());
        assertEquals("1280x720, 30 fps, AV1 in MP4, moderate quality, AAC stereo", made.get(4).summary());
        assertFalse(made.get(0).sameSettings(made.get(1)));
        assertTrue(made.get(0).sameSettings(made.get(0).named("Other")));
    }

    @Test
    void qualityBecomesABitrate() {
        ExportPreset youtube = ExportPresets.builtIn().get(0);
        assertEquals(19_900_000L, youtube.bitrateFor("h264"));
        assertTrue(youtube.bitrateFor("av1") < youtube.bitrateFor("h264"), "the newer codec needs less for the same picture");
        assertTrue(ExportPresets.builtIn().get(1).bitrateFor("h264") > 60_000_000L, "4K at 60 gets a high bitrate");
        assertEquals(0L, ExportPresets.builtIn().get(3).bitrateFor("prores"), "an editing master takes no bitrate");
        JsonObject custom = new JsonObject();
        custom.addProperty("quality", "custom");
        custom.addProperty("bitrate", "8m");
        assertEquals(8_000_000L, ExportPreset.fromJson(custom, youtube).bitrateFor("vp9"));
    }

}
