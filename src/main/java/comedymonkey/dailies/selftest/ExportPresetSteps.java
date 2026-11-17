package comedymonkey.dailies.selftest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.edit.Interpolation;
import comedymonkey.dailies.edit.Keyframe;
import comedymonkey.dailies.editor.EditorSession;
import comedymonkey.dailies.export.Codecs;
import comedymonkey.dailies.export.Encoder;
import comedymonkey.dailies.export.Export;
import comedymonkey.dailies.export.ExportPreset;
import comedymonkey.dailies.export.ExportPresets;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.sound.ExportSound;
import comedymonkey.dailies.sound.VoiceFormat;
import comedymonkey.dailies.sound.VoicePlayback;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static comedymonkey.dailies.selftest.SelfTest.args;
import static comedymonkey.dailies.selftest.SelfTest.check;

/**
 * What an export can produce: the honest codec list (EXP-1), the export presets and what they fall back to
 * (EXP-14), editing masters, and sound kept apart on tracks of its own that add up to the mix (EXP-12). Runs while
 * the recording of the earlier steps is open in playback.
 */
final class ExportPresetSteps {

    private static final int RATE = ExportSound.SAMPLE_RATE;

    private ExportPresetSteps() {
    }

    static void run(SelfTest test) throws Exception {
        if (!Encoder.bundled()) {
            // The interface and the tools must say so plainly rather than fail (EXP-8)
            JsonObject codecs = test.tool("export_codecs_list", new JsonObject());
            check(codecs.has("note") && codecs.get("note").getAsString().contains(Encoder.SUPPORTED), "without an encoder the codec list does not say which platforms have one: " + codecs);
            test.skip("this build has no video encoder packed in for " + Encoder.platform() + ": codecs, presets and separate sound tracks were not exported (EXP-1, EXP-12, EXP-14)");
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        RecordingSteps.Note middle = RecordingSteps.notes.get(RecordingSteps.notes.size() / 2);
        int from = middle.tick() - 30;
        Path folder = minecraft.gameDirectory.toPath().resolve("dailies").resolve("exports");

        List<Codecs.Choice> working = codecList(test);
        presetsAreKept(test);
        builtInPresets(test, folder, from, working);
        withoutHardware(test, folder, from);
        otherMaster(test, folder, from);
        separateTracks(test, folder, from);
        theTab(test, folder);
    }

    // ------------------------------------------------------------------ EXP-1

    /** The codec list is what this machine can do, and holds no software H.264 */
    private static List<Codecs.Choice> codecList(SelfTest test) throws Exception {
        JsonObject listed = test.tool("export_codecs_list", new JsonObject());
        JsonArray codecs = listed.getAsJsonArray("codecs");
        check(codecs.size() > 0, "the codec list is empty: " + listed);
        StringBuilder said = new StringBuilder();
        boolean hardwareH264 = false;
        for (JsonElement entry : codecs) {
            JsonObject one = entry.getAsJsonObject();
            String codec = one.get("video_codec").getAsString();
            String encoder = one.get("encoder").getAsString();
            String runsOn = one.get("runs_on").getAsString();
            check(!encoder.equals(Codecs.FORBIDDEN) && !encoder.contains("openh264") && !encoder.contains("x264") && !encoder.contains("x265"),
                "the codec list offers a software H.264 or H.265 encoder: " + encoder);
            if (codec.equals("h264") || codec.equals("h265")) {
                check(runsOn.equals("hardware"), codec + " is offered through " + encoder + ", which is not a hardware encoder");
                hardwareH264 |= codec.equals("h264");
            }
            check(runsOn.equals("hardware") || runsOn.equals("software"), "a codec choice does not say whether it is hardware or software: " + one);
            check(!one.get("what").getAsString().isBlank(), "a codec choice does not say what it runs on: " + one);
            said.append(' ').append(encoder).append(" (").append(runsOn).append(", ").append(one.get("what").getAsString()).append(");");
        }
        for (String codec : List.of("av1", "vp9", "prores", "dnxhr")) {
            String wanted = codec;
            check(codecs.asList().stream().anyMatch(entry -> entry.getAsJsonObject().get("video_codec").getAsString().equals(wanted)), "the codec list has nothing for " + codec + ": " + said);
        }
        check(hardwareH264 == !listed.getAsJsonObject("missing").has("h264"), "the codec list neither offers H.264 nor says why not: " + listed);
        check(listed.get("default").getAsString().equals(codecs.get(0).getAsJsonObject().get("video_codec").getAsString()), "the default codec is not the first, fastest, choice");
        // Asking for the software encoder by name is refused even though the build contains it
        Path refused = Minecraft.getInstance().gameDirectory.toPath().resolve("dailies").resolve("exports").resolve("self-test-refused.mp4");
        if (Encoder.encoders().contains(Codecs.FORBIDDEN)) {
            String message = test.toolError("export_start", args("output_path", refused.toString(), "video_codec", "h264", "encoder", Codecs.FORBIDDEN, "width", 640, "height", 360, "overwrite", true));
            check(message.contains(Codecs.FORBIDDEN) && !Export.active(), "asking for " + Codecs.FORBIDDEN + " by name was not refused: " + message);
        }
        check(!Files.exists(refused), "a refused export left a file behind");
        test.log("codec choices on this machine, fastest first:" + said + (hardwareH264 ? "" : " H.264 is missing: " + listed.getAsJsonObject("missing").get("h264").getAsString()));
        return Encoder.choices();
    }

    // ------------------------------------------------------------------ EXP-14

    /** Presets can be listed, added, changed, renamed, removed and reset, and are kept in a file with the mod's data */
    private static void presetsAreKept(SelfTest test) throws Exception {
        // A run that was stopped half way may have left its own presets behind; reset keeps presets a person made
        for (ExportPreset left : Export.presets().all()) {
            if (left.name().startsWith("Self test")) {
                test.tool("export_presets_remove", args("name", left.name()));
            }
        }
        test.tool("export_presets_reset", new JsonObject());
        JsonArray listed = test.tool("export_presets_list", new JsonObject()).getAsJsonArray("presets");
        List<String> names = new ArrayList<>();
        for (JsonElement preset : listed) {
            names.add(preset.getAsJsonObject().get("name").getAsString());
            check(!preset.getAsJsonObject().get("summary").getAsString().isBlank(), "a preset has no summary: " + preset);
        }
        check(names.equals(List.of("YouTube 1080p60", "YouTube 4K60", "Shorts and Reels", "Editing master", "Smallest")), "the built-in presets are not the five of the plan: " + names);
        Map<String, String> viaHost = Host.get().exportPresets();
        check(new ArrayList<>(viaHost.keySet()).equals(names) && viaHost.get("Shorts and Reels").contains("1080x1920"), "Host.exportPresets() does not give the presets and a summary each: " + viaHost);

        JsonObject added = test.tool("export_presets_set", args("name", "Self test", "width", 640, "height", 360, "framerate", 30, "container", "mkv", "quality", "custom", "bitrate", "3m"));
        check(added.get("added").getAsBoolean() && !added.get("built_in").getAsBoolean() && added.get("bitrate").getAsLong() == 3_000_000L, "a preset was not added as asked: " + added);
        JsonObject changed = test.tool("export_presets_set", args("name", "self TEST", "height", 480, "new_name", "Self test two"));
        check(!changed.get("added").getAsBoolean() && changed.get("height").getAsInt() == 480 && changed.get("width").getAsInt() == 640 && changed.get("name").getAsString().equals("Self test two"),
            "changing and renaming a preset did not keep what was not named: " + changed);
        check(test.toolError("export_presets_set", args("name", "Self test two", "container", "avi")).contains("mp4"), "a wrong kind of file was not answered with the allowed ones");
        check(test.toolError("export_presets_get", args("name", "No such preset")).contains("YouTube 1080p60"), "an unknown preset was not answered with the names there are");
        test.tool("export_presets_set", args("name", "YouTube 4K60", "framerate", 30));
        test.tool("export_presets_remove", args("name", "Smallest"));
        Path file = Host.get().dataDirectory().resolve("export-presets.json");
        check(Files.isRegularFile(file) && Files.readString(file).contains("Self test two"), "the presets were not written to " + file);
        // Read afresh from the file, as the next start of the game would
        ExportPresets fresh = new ExportPresets(file);
        check(fresh.find("Self test two") != null && fresh.find("Smallest") == null && fresh.find("YouTube 4K60").framesPerSecond() == 30.0,
            "the presets read back from the file are not the ones that were set");
        test.tool("export_presets_remove", args("name", "Self test two"));
        JsonArray reset = test.tool("export_presets_reset", new JsonObject()).getAsJsonArray("presets");
        check(reset.size() == 5 && test.tool("export_presets_get", args("name", "YouTube 4K60")).get("framerate").getAsDouble() == 60.0, "reset did not bring the built-in presets back: " + reset);
        test.log("presets: listed, added, changed, renamed, removed and reset; kept in " + file.getFileName());
    }

    /** Each built-in preset exports a short range, and the file is what the preset says, or the preset says what it fell back to */
    private static void builtInPresets(SelfTest test, Path folder, int from, List<Codecs.Choice> working) throws Exception {
        for (ExportPreset preset : ExportPresets.builtIn()) {
            if (preset.builtIn().equals("editing_master")) {
                // Exported with voices and music in separateTracks(), where its sound tracks are looked at too
                continue;
            }
            Codecs.Pick expected = Codecs.pick(preset.codecs(), preset.container(), working);
            int ticks = preset.framesPerSecond() >= 60.0 ? 5 : 10;
            Path file = folder.resolve("self-test-preset-" + preset.builtIn() + "." + expected.container());
            Files.deleteIfExists(file);
            JsonObject started = test.tool("export_start", args("output_path", file.toString(), "preset", preset.name(), "start_tick", from, "end_tick", from + ticks, "no_gui", true, "overwrite", true));
            check(started.get("preset").getAsString().equals(preset.name()) && started.get("video_codec").getAsString().equals(expected.codec())
                && started.get("container").getAsString().equals(expected.container()), "the preset " + preset.name() + " did not start as expected: " + started);
            JsonObject done = finish(test, preset.name());
            boolean fellBack = expected.fellBack() || !expected.codec().equals(preset.codecs().get(0));
            if (expected.words(working) != null) {
                check(done.has("note") && done.get("note").getAsString().equals(expected.words(working)), "the preset " + preset.name() + " fell back without saying so: " + done);
            }
            String told = probe("v:0", "stream=codec_name,width,height,r_frame_rate,nb_read_frames", file);
            int frames = (int) Math.round(ticks / 20.0 * preset.framesPerSecond());
            check(told.contains("codec_name=" + Codecs.probeName(expected.codec())), preset.name() + " did not write " + expected.codec() + ": " + told);
            check(told.contains("width=" + preset.width() + "\n") || told.contains("width=" + preset.width() + "\r"), preset.name() + " is not " + preset.width() + " wide: " + told);
            check(told.contains("height=" + preset.height()), preset.name() + " is not " + preset.height() + " high: " + told);
            check(told.contains("r_frame_rate=" + (int) preset.framesPerSecond() + "/1"), preset.name() + " is not at " + preset.framesPerSecond() + " frames a second: " + told);
            check(told.contains("nb_read_frames=" + frames), preset.name() + " does not hold " + frames + " frames: " + told);
            String note = done.has("sound") ? done.get("sound").getAsString() : "";
            if (!note.startsWith("no sound")) {
                String sound = probe("a", "stream=codec_name,channels", file);
                check(sound.contains("codec_name=aac") && sound.contains("channels=2"), preset.name() + " has no stereo AAC sound: " + sound);
            }
            test.log("preset " + preset.name() + ": " + told.replaceAll("\\s+", " ").trim() + (fellBack ? "; second choice or fallback taken: " + expected.codec() : "; first choice")
                + (done.has("note") ? " (" + done.get("note").getAsString() + ")" : ""));
        }
        // A range longer than a preset is meant for is warned about, and exported all the same
        test.tool("export_presets_set", args("name", "Self test short", "width", 640, "height", 360, "framerate", 30, "record_audio", false, "max_seconds", 1));
        Path file = folder.resolve("self-test-preset-too-long.mp4");
        Files.deleteIfExists(file);
        JsonObject started = test.tool("export_start", args("output_path", file.toString(), "preset", "Self test short", "start_tick", from, "end_tick", from + 30, "no_gui", true, "overwrite", true));
        check(started.has("warning") && started.get("warning").getAsString().contains("1.5"), "a range of 1.5 seconds in a preset for 1 second was not warned about: " + started);
        finish(test, "the over-long preset export");
        test.tool("export_presets_remove", args("name", "Self test short"));
        check(!test.tool("export_start", args("output_path", file.toString(), "preset", "Shorts and Reels", "width", 360, "height", 640, "start_tick", from, "end_tick", from + 5,
            "record_audio", false, "no_gui", true, "overwrite", true)).has("warning"), "a quarter of a second was warned about as too long for Shorts");
        finish(test, "the small Shorts export");
        check(probe("v:0", "stream=width,height", file).replaceAll("\\s+", " ").contains("width=360 height=640"), "what is given beside a preset does not win over it");
    }

    /** What a machine without any hardware encoder gets: no H.264, a preset that says what it used instead, and a file of that codec */
    private static void withoutHardware(SelfTest test, Path folder, int from) throws Exception {
        System.setProperty(Encoder.SOFTWARE_ONLY, "true");
        try {
            JsonObject listed = test.tool("export_codecs_list", new JsonObject());
            for (JsonElement entry : listed.getAsJsonArray("codecs")) {
                JsonObject one = entry.getAsJsonObject();
                check(one.get("runs_on").getAsString().equals("software") && !one.get("video_codec").getAsString().startsWith("h26"),
                    "with hardware encoders left out the list still offers " + one);
            }
            check(listed.getAsJsonObject("missing").has("h264") && listed.getAsJsonObject("missing").get("h264").getAsString().contains("hardware"),
                "the list does not say why H.264 is missing: " + listed);
            Path file = folder.resolve("self-test-no-hardware.mp4");
            Files.deleteIfExists(file);
            String refused = test.toolError("export_start", args("output_path", file.toString(), "video_codec", "h264", "width", 640, "height", 360, "start_tick", from, "end_tick", from + 5, "overwrite", true));
            check(refused.contains("hardware") && refused.contains("av1"), "asking for H.264 without a hardware encoder was not answered with the reason and what there is: " + refused);

            // "YouTube 1080p60" wants H.264 or AV1: AV1 is its own second choice, so nothing needs saying
            JsonObject started = test.tool("export_start", args("output_path", file.toString(), "preset", "YouTube 1080p60", "width", 640, "height", 360, "start_tick", from, "end_tick", from + 5,
                "record_audio", false, "no_gui", true, "overwrite", true));
            check(started.get("video_codec").getAsString().equals("av1"), "without hardware the YouTube preset did not go to AV1: " + started);
            finish(test, "the YouTube preset without hardware");
            check(probe("v:0", "stream=codec_name", file).contains("codec_name=av1"), "the file written without hardware is not AV1");

            // A preset that wants only H.264 has to fall back, and says so
            JsonArray only = new JsonArray();
            only.add("h264");
            test.tool("export_presets_set", args("name", "Self test h264", "width", 640, "height", 360, "framerate", 30, "video_codecs", only, "record_audio", false));
            JsonObject described = test.tool("export_presets_get", args("name", "Self test h264"));
            check(described.has("note") && described.get("note").getAsString().contains("H.264") && described.get("uses_here").getAsString().contains("AV1"),
                "a preset that cannot have its codec does not say what it will use: " + described);
            Path fallen = folder.resolve("self-test-fallback.mp4");
            Files.deleteIfExists(fallen);
            started = test.tool("export_start", args("output_path", fallen.toString(), "preset", "Self test h264", "start_tick", from, "end_tick", from + 10, "no_gui", true, "overwrite", true));
            check(started.get("video_codec").getAsString().equals("av1") && started.has("note") && started.get("note").getAsString().contains("H.264"),
                "a preset for H.264 alone did not fall back to AV1 and say so: " + started);
            JsonObject done = finish(test, "the preset that falls back");
            check(done.has("note") && done.get("note").getAsString().contains("is used instead"), "after the export nothing says the preset fell back: " + done);
            String told = probe("v:0", "stream=codec_name,width,height,nb_read_frames", fallen);
            check(told.contains("codec_name=av1") && told.contains("width=640") && told.contains("nb_read_frames=15"), "the file of the preset that fell back is not 15 frames of AV1: " + told);
            test.log("without hardware encoders: H.264 refused with its reason; presets took AV1 (" + started.get("note").getAsString() + ")");
        } finally {
            System.clearProperty(Encoder.SOFTWARE_ONLY);
            if (Export.presets().find("Self test h264") != null) {
                test.tool("export_presets_remove", args("name", "Self test h264"));
            }
        }
    }

    // ------------------------------------------------------------------ Masters

    /** DNxHR HQ in MOV; ProRes is exported as the "Editing master" preset with its sound tracks */
    private static void otherMaster(SelfTest test, Path folder, int from) throws Exception {
        Path file = folder.resolve("self-test-dnxhr.mov");
        Files.deleteIfExists(file);
        test.tool("export_start", args("output_path", file.toString(), "container", "mov", "video_codec", "dnxhr", "width", 1280, "height", 720, "framerate", 30,
            "start_tick", from, "end_tick", from + 10, "no_gui", true, "overwrite", true));
        finish(test, "the DNxHR master");
        String told = probe("v:0", "stream=codec_name,profile,width,height,nb_read_frames", file);
        check(told.contains("codec_name=dnxhd") && told.contains("profile=DNXHR HQ") && told.contains("width=1280") && told.contains("nb_read_frames=15"), "the DNxHR master is not DNxHR HQ in MOV: " + told);
        check(test.toolError("export_start", args("output_path", file.toString(), "container", "mp4", "video_codec", "prores", "width", 640, "height", 360, "overwrite", true)).contains("mov"),
            "ProRes in MP4 was not refused with where it does go");
        test.log("DNxHR master: " + told.replaceAll("\\s+", " ").trim());
    }

    // ------------------------------------------------------------------ EXP-12

    /**
     * Sound kept apart: the game, two speakers and an audio keyframe, as WAV files and as named tracks inside MKV and
     * MOV, all the same length, adding up to the mix sample for sample.
     */
    private static void separateTracks(SelfTest test, Path folder, int from) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        // Music: a second of a 440 Hz tone at 44.1 kHz mono, so it has to be brought to the export's rate and channels
        Path tone = folder.resolve("self-test-tone.wav");
        writeTone(tone);
        int musicTick = from + 4;
        int track = test.onClient(() -> EditorSession.history().setKeyframe(new Keyframe.Audio(musicTick, Interpolation.HOLD, tone.toString(), 0.5)));

        // Voices: two speakers saying a tone each, handed over just as recorded voice chat is while the export steps the recording
        UUID recorder = test.onClient(() -> minecraft.player == null ? UUID.randomUUID() : minecraft.player.getUUID());
        String recorderName = test.onClient(() -> minecraft.player == null ? null : minecraft.player.getGameProfile().getName());
        UUID stranger = UUID.fromString("0a1b2c3d-0000-4000-8000-000000000001");
        long[] spoken = {0, -1};
        Consumer<RenderFrameEvent.Pre> speak = event -> {
            if (!Export.active() || !ExportSound.keepsVoicesApart()) {
                spoken[0] = 0;
                spoken[1] = -1;
                return;
            }
            long frame = Export.frame();
            if (frame == spoken[1]) {
                return;
            }
            spoken[1] = frame;
            // As much speech as the video has had time for, in the voice chat's 20 millisecond pieces
            while (spoken[0] < (frame + 1) * RATE / 30) {
                // Three blocks to the right of the camera
                Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
                org.joml.Vector3f left = minecraft.gameRenderer.getMainCamera().getLeftVector();
                byte[] near = VoiceFormat.write(VoiceFormat.PCM, VoiceFormat.HAS_POSITION | (spoken[0] == 0 ? VoiceFormat.STREAM_START : 0), recorder,
                    camera.x - left.x() * 3.0, camera.y - left.y() * 3.0, camera.z - left.z() * 3.0,
                    VoiceFormat.pcmBytes(tone(spoken[0], VoiceFormat.FRAME_SAMPLES, 330.0, 9000)));
                byte[] everywhere = VoiceFormat.write(VoiceFormat.PCM, spoken[0] == 0 ? VoiceFormat.STREAM_START : 0, stranger, 0, 0, 0,
                    VoiceFormat.pcmBytes(tone(spoken[0], VoiceFormat.FRAME_SAMPLES, 550.0, 7000)));
                VoicePlayback.INSTANCE.play(near, 0, near.length, false);
                VoicePlayback.INSTANCE.play(everywhere, 0, everywhere.length, false);
                spoken[0] += VoiceFormat.FRAME_SAMPLES;
            }
        };
        NeoForge.EVENT_BUS.addListener(speak);
        try {
            String strangerName = "Speaker " + stranger.toString().substring(0, 8);
            // MKV, uncompressed so the samples can be compared, tracks inside and WAV files beside
            Path mkv = folder.resolve("self-test-tracks.mkv");
            Files.deleteIfExists(mkv);
            test.tool("export_start", args("output_path", mkv.toString(), "container", "mkv", "width", 640, "height", 360, "framerate", 30, "start_tick", from, "end_tick", from + 20,
                "no_gui", true, "record_audio", true, "audio_codec", "pcm", "separate_audio", "both", "overwrite", true));
            JsonObject done = finish(test, "the export with separate tracks in MKV");
            String note = done.has("sound") ? done.get("sound").getAsString() : "";
            if (note.startsWith("no sound")) {
                test.skip("the game's sound could not be captured on this machine (" + note + "): separate sound tracks were not checked (EXP-12)");
                return;
            }
            List<String> expected = new ArrayList<>(List.of("Mix", "Game", recorderName == null ? "Speaker " + recorder.toString().substring(0, 8) : recorderName, strangerName, "Music"));
            List<String> titles = new ArrayList<>();
            done.getAsJsonArray("audio_tracks").forEach(title -> titles.add(title.getAsString()));
            check(titles.equals(expected), "the tracks inside the MKV are " + titles + ", not " + expected + " (" + note + ")");
            checkTracks(test, mkv, "title", expected, done, 2, RATE, musicTick - from);

            // The "Editing master" preset: ProRes 422 HQ in MOV, uncompressed sound, both forms of separate tracks
            Path mov = folder.resolve("self-test-preset-editing_master.mov");
            Files.deleteIfExists(mov);
            JsonObject started = test.tool("export_start", args("output_path", mov.toString(), "preset", "Editing master", "start_tick", from, "end_tick", from + 10, "no_gui", true, "overwrite", true));
            check(started.get("video_codec").getAsString().equals("prores") && started.get("container").getAsString().equals("mov"), "the editing master did not start as ProRes in MOV: " + started);
            done = finish(test, "the editing master");
            String told = probe("v:0", "stream=codec_name,profile,width,height,r_frame_rate,nb_read_frames", mov);
            check(told.contains("codec_name=prores") && told.contains("profile=HQ") && told.contains("width=1920") && told.contains("height=1080") && told.contains("r_frame_rate=60/1")
                && told.contains("nb_read_frames=30"), "the editing master is not 30 frames of 1920x1080 ProRes 422 HQ at 60 a second: " + told);
            checkTracks(test, mov, "name", expected, done, 2, RATE / 2, musicTick - from);
            test.log("editing master: " + told.replaceAll("\\s+", " ").trim());

            // Mono: every track has one channel and they still add up
            Path mono = folder.resolve("self-test-tracks-mono.mkv");
            Files.deleteIfExists(mono);
            test.tool("export_start", args("output_path", mono.toString(), "container", "mkv", "width", 640, "height", 360, "framerate", 30, "start_tick", from, "end_tick", from + 10,
                "no_gui", true, "record_audio", true, "stereo_audio", false, "audio_codec", "pcm", "separate_audio", "tracks", "overwrite", true));
            done = finish(test, "the mono export with separate tracks");
            check(!done.has("audio_files"), "WAV files were written when only tracks inside the file were asked for: " + done);
            checkTracks(test, mono, "title", expected, done, 1, RATE / 2, musicTick - from);

            // MP4 is not given separate tracks: asked for them, the WAV files are written instead, and the note says so
            Path mp4 = folder.resolve("self-test-tracks.mp4");
            Files.deleteIfExists(mp4);
            test.tool("export_start", args("output_path", mp4.toString(), "container", "mp4", "width", 640, "height", 360, "framerate", 30, "start_tick", from, "end_tick", from + 10,
                "no_gui", true, "record_audio", true, "separate_audio", "tracks", "overwrite", true));
            done = finish(test, "the MP4 export with separate tracks");
            check(done.has("audio_files") && done.getAsJsonArray("audio_files").size() == 4 && !done.has("audio_tracks") && done.get("sound").getAsString().contains("WAV"),
                "an MP4 asked for separate tracks did not write WAV files and say so: " + done);
            check(probe("a", "stream=codec_name", mp4).lines().filter(line -> line.startsWith("codec_name=")).count() == 1, "the MP4 does not hold exactly one sound track");
            check(test.toolError("export_start", args("output_path", mp4.toString(), "separate_audio", "both", "width", 640, "height", 360, "overwrite", true)).contains("without sound"),
                "separate tracks without sound were not refused");
        } finally {
            NeoForge.EVENT_BUS.unregister(speak);
            // The scene is left as the earlier steps made it
            test.onClientRun(() -> EditorSession.history().removeTrack(track));
        }
    }

    /**
     * The sound tracks of a file: named as expected, all exactly as long as the video, the separate ones adding up to
     * the mix, the WAV files beside it holding the same samples, and the music starting on its own sample.
     */
    private static void checkTracks(SelfTest test, Path video, String titleTag, List<String> expected, JsonObject done, int channels, long frames, int musicTicks) throws Exception {
        String told = probe("a", "stream=index,codec_name,channels:stream_tags=" + titleTag + ",title,name,handler_name", video);
        List<String> named = told.lines().filter(line -> line.toLowerCase().startsWith("tag:" + titleTag + "=")).map(line -> line.substring(line.indexOf('=') + 1).trim()).toList();
        check(named.equals(expected), video.getFileName() + " names its sound tracks " + named + ", not " + expected + ": " + told);
        check(told.lines().filter(line -> line.equals("codec_name=pcm_s16le")).count() == expected.size() && told.lines().filter(line -> line.equals("channels=" + channels)).count() == expected.size(),
            video.getFileName() + " does not hold " + expected.size() + " uncompressed tracks of " + channels + " channel(s): " + told);
        List<short[]> tracks = new ArrayList<>();
        for (int i = 0; i < expected.size(); i++) {
            Path raw = video.resolveSibling("self-test-track.pcm");
            Files.deleteIfExists(raw);
            run(Encoder.programPath().toString(), "-v", "error", "-y", "-i", video.toString(), "-map", "0:a:" + i, "-f", "s16le", "-c:a", "pcm_s16le", raw.toString());
            short[] samples = samples(Files.readAllBytes(raw), 0);
            Files.deleteIfExists(raw);
            check(samples.length == frames * channels, "track '" + expected.get(i) + "' of " + video.getFileName() + " holds " + samples.length / channels + " sample frames, not the video's " + frames);
            tracks.add(samples);
        }
        // The separate tracks add up to the mix, sample for sample; only where the sum would not fit 16 bits may the mix differ
        short[] mix = tracks.get(0);
        long worst = 0;
        int clipped = 0;
        for (int i = 0; i < mix.length; i++) {
            int sum = 0;
            for (int t = 1; t < tracks.size(); t++) {
                sum += tracks.get(t)[i];
            }
            if (sum > Short.MAX_VALUE || sum < Short.MIN_VALUE) {
                clipped++;
                sum = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sum));
            }
            worst = Math.max(worst, Math.abs(sum - mix[i]));
        }
        check(worst == 0, "the separate tracks of " + video.getFileName() + " do not add up to the mix: a sample is off by " + worst);
        check(clipped < mix.length / 10, "the test's own sound is too loud to prove anything: " + clipped + " samples clipped");
        for (int t = 2; t < tracks.size(); t++) {
            check(peak(tracks.get(t)) > 500, "track '" + expected.get(t) + "' of " + video.getFileName() + " is silent");
        }
        check(peak(mix) > 500, "the mixed track of " + video.getFileName() + " is silent");
        // The audio keyframe starts on its own sample: silence before its tick, sound from it on
        short[] music = tracks.get(tracks.size() - 1);
        int first = 0;
        while (first < music.length && music[first] == 0) {
            first++;
        }
        long wanted = (long) musicTicks * RATE / 20;
        check(Math.abs(first / channels - wanted) <= 4, "the music starts at sample " + first / channels + " of " + video.getFileName() + ", not at " + wanted + " where its keyframe is");
        // The speaker near the camera, to its side, is louder in one ear; the one heard everywhere is the same in both
        if (channels == 2) {
            short[] near = tracks.get(2);
            short[] everywhere = tracks.get(3);
            check(peak(near, 1) > peak(near, 0) + 2000, "a voice from the right is not louder in the right ear: left " + peak(near, 0) + ", right " + peak(near, 1));
            check(Math.abs(peak(everywhere, 0) - peak(everywhere, 1)) <= 2, "a voice without a place is not the same in both ears");
        }
        // The WAV files beside the video hold the same samples as the tracks inside it
        int files = 0;
        if (done.has("audio_files")) {
            JsonArray wavs = done.getAsJsonArray("audio_files");
            check(wavs.size() == expected.size() - 1, "there are " + wavs.size() + " WAV files beside " + video.getFileName() + ", not one for each separate track");
            String base = video.getFileName().toString();
            base = base.substring(0, base.lastIndexOf('.'));
            for (int t = 1; t < expected.size(); t++) {
                Path wav = Path.of(wavs.get(t - 1).getAsString());
                String name = wav.getFileName().toString();
                String tag = t == 1 ? "game" : t == expected.size() - 1 ? "music" : "voice-" + expected.get(t).replace(' ', '_');
                check(name.equals(base + "." + tag + ".wav"), "a WAV file is called " + name + ", not " + base + "." + tag + ".wav");
                byte[] bytes = Files.readAllBytes(wav);
                ByteBuffer head = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                check(new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("RIFF") && head.getShort(22) == channels && head.getInt(24) == RATE && head.getShort(34) == 16
                    && head.getInt(40) == frames * channels * 2 && bytes.length == 44 + frames * channels * 2, name + " is not a WAV file of " + frames + " sample frames");
                check(java.util.Arrays.equals(samples(bytes, 44), tracks.get(t)), name + " does not hold the same samples as its track inside the video");
                files++;
            }
        }
        test.log(video.getFileName() + ": sound tracks " + named + ", each " + frames + " sample frames of " + channels + " channel(s), adding up to the mix exactly"
            + (files > 0 ? "; " + files + " WAV files beside it hold the same samples" : "") + (clipped > 0 ? "; " + clipped + " samples at the limit" : ""));
    }

    // ------------------------------------------------------------------ The Export tab

    /** The tab fills in from a preset, marks a change, and is looked at: a picture of it is kept for a person to judge */
    private static void theTab(SelfTest test, Path folder) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        Class<?> forms = Class.forName("comedymonkey.dailies.ui.ExportForms");
        Method describe = forms.getDeclaredMethod("describe");
        Method choose = forms.getDeclaredMethod("choosePreset", String.class);
        Method size = forms.getDeclaredMethod("setSize", int.class, int.class);
        describe.setAccessible(true);
        choose.setAccessible(true);
        size.setAccessible(true);
        test.onClientRun(() -> {
            try {
                Class<?> state = Class.forName("comedymonkey.dailies.ui.EditorState");
                java.lang.reflect.Field tab = state.getDeclaredField("tab");
                tab.setAccessible(true);
                // Opened first: the editor goes back to its first tab when it meets a recording it has not shown before
                comedymonkey.dailies.ui.Controls.openTimeline();
                tab.setInt(null, 2);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
        String[] said = new String[1];
        test.waitFor(() -> {
            try {
                said[0] = test.onClient(() -> (String) describe.invoke(null));
                return said[0] != null;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, 60, "the Export tab to learn what the encoder offers");
        check(said[0].contains("preset=YouTube 1080p60 modified=false size=1920x1080 container=mp4"), "the Export tab does not start from the first preset: " + said[0]);
        check(!said[0].contains("openh264"), "the Export tab chose the software H.264 encoder: " + said[0]);
        test.onClientRun(() -> invoke(size, 1280, 720));
        String changed = test.onClient(() -> (String) describe.invoke(null));
        check(changed.contains("preset=YouTube 1080p60 modified=true size=1280x720"), "changing the size did not mark the preset as modified: " + changed);
        check(test.onClient(() -> (Boolean) choose.invoke(null, "Editing master")), "the Editing master preset could not be chosen");
        String master = test.onClient(() -> (String) describe.invoke(null));
        check(master.contains("preset=Editing master modified=false size=1920x1080 container=mov codec=prores_ks"), "choosing the editing master did not fill the tab in: " + master);
        test.sleepTicks(8);
        shot(test, folder.resolve("self-test-export-tab-master.png"));
        test.onClientRun(() -> invoke(choose, "Shorts and Reels"));
        test.sleepTicks(8);
        shot(test, folder.resolve("self-test-export-tab.png"));
        String shorts = test.onClient(() -> (String) describe.invoke(null));
        check(shorts.contains("preset=Shorts and Reels modified=false size=1080x1920"), "choosing Shorts did not fill the tab in: " + shorts);
        test.onClientRun(() -> {
            invoke(choose, "YouTube 1080p60");
            minecraft.setScreen(null);
        });
        test.log("the Export tab: " + master + " | " + shorts + "; pictures of it are in " + folder);
    }

    private static Object invoke(Method method, Object... arguments) {
        try {
            return method.invoke(null, arguments);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void shot(SelfTest test, Path file) throws Exception {
        test.onClientRun(() -> {
            try (com.mojang.blaze3d.platform.NativeImage image = net.minecraft.client.Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
                image.writeToFile(file);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    // ------------------------------------------------------------------ Helpers

    /** Waits for the export under way and returns its status; it must not have failed */
    private static JsonObject finish(SelfTest test, String what) throws Exception {
        test.waitFor(() -> !Export.active(), 300, what + " to export");
        JsonObject done = test.tool("export_status", new JsonObject());
        test.waitFor(() -> !Playback.jumping(), 30, "playback to be put back");
        check(!done.has("last_error"), what + " failed: " + done);
        return done;
    }

    private static String probe(String streams, String entries, Path file) throws Exception {
        List<String> command = new ArrayList<>(List.of(Encoder.inspector().toString(), "-v", "error", "-select_streams", streams));
        if (streams.startsWith("v")) {
            command.add("-count_frames");
        }
        command.addAll(List.of("-show_entries", entries, "-of", "default=noprint_wrappers=1", file.toString()));
        return run(command.toArray(String[]::new)).replace("\r", "");
    }

    private static String run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String said = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        check(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS), "a check of the video did not finish");
        return said;
    }

    private static short[] samples(byte[] bytes, int from) {
        short[] samples = new short[(bytes.length - from) / 2];
        ByteBuffer.wrap(bytes, from, bytes.length - from).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples);
        return samples;
    }

    private static int peak(short[] samples) {
        int most = 0;
        for (short sample : samples) {
            most = Math.max(most, Math.abs(sample));
        }
        return most;
    }

    /** The loudest sample of one channel of a stereo track */
    private static int peak(short[] stereo, int channel) {
        int most = 0;
        for (int i = channel; i < stereo.length; i += 2) {
            most = Math.max(most, Math.abs(stereo[i]));
        }
        return most;
    }

    private static short[] tone(long start, int count, double hertz, int loudness) {
        short[] samples = new short[count];
        for (int i = 0; i < count; i++) {
            samples[i] = (short) Math.round(Math.sin((start + i) * 2.0 * Math.PI * hertz / RATE) * loudness);
        }
        return samples;
    }

    /** A second of a 440 Hz tone at 44.1 kHz mono, starting at its loudest so its first sample cannot be mistaken for silence */
    private static void writeTone(Path file) throws Exception {
        int rate = 44100;
        ByteBuffer out = ByteBuffer.allocate(44 + rate * 2).order(ByteOrder.LITTLE_ENDIAN);
        out.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + rate * 2).put("WAVE".getBytes(StandardCharsets.US_ASCII));
        out.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16);
        out.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(rate * 2);
        for (int i = 0; i < rate; i++) {
            out.putShort((short) Math.round(Math.cos(i * 2.0 * Math.PI * 440.0 / rate) * 12000));
        }
        Files.createDirectories(file.getParent());
        Files.write(file, out.array());
    }

}
