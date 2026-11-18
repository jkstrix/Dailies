package comedymonkey.dailies.selftest;

import com.google.gson.JsonObject;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.edit.EditHistory;
import comedymonkey.dailies.edit.Interpolation;
import comedymonkey.dailies.edit.Keyframe;
import comedymonkey.dailies.edit.KeyframeKind;
import comedymonkey.dailies.editor.EditorSession;
import comedymonkey.dailies.export.Encoder;
import comedymonkey.dailies.export.Export;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

import static comedymonkey.dailies.selftest.SelfTest.args;
import static comedymonkey.dailies.selftest.SelfTest.check;
import static comedymonkey.dailies.selftest.SelfTestSupport.deleteFolder;
import static comedymonkey.dailies.selftest.SelfTestSupport.difference;

/**
 * Chapter XIV step 4, "Edit and export": camera keyframes with undo and redo, numbered pictures whose frames are
 * counted, measured and looked at, a still, a check that playback is left as it was, and one video for each of H.264,
 * H.265 and AV1 (the AV1 with sound). The owner asked for one export of each kind and no more, so each export carries
 * every check of its kind; VP9, ProRes, DNxHR and separate sound tracks are in {@link ExportPresetSteps}.
 */
final class ExportSteps {

    private ExportSteps() {
    }

    static void run(SelfTest test) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        JsonObject none = new JsonObject();
        RecordingSteps.Note middle = RecordingSteps.notes.get(RecordingSteps.notes.size() / 2);
        int from = middle.tick() - 30;
        int to = middle.tick() + 10;
        Vec3 recorder = middle.positions().get("recorder");

        // Two camera keyframes: the camera travels along a line while it looks towards the recorder (EDIT-3)
        PlaybackSteps.jump(test, from);
        int track = test.onClient(() -> {
            EditHistory history = EditorSession.history();
            int index = history.setKeyframe(new Keyframe.Camera(from, Interpolation.LINEAR, recorder.x - 6, recorder.y + 3, recorder.z - 6, -45.0, 20.0, 0.0));
            history.setKeyframe(index, new Keyframe.Camera(to, Interpolation.LINEAR, recorder.x + 6, recorder.y + 3, recorder.z - 6, 45.0, 20.0, 0.0));
            return index;
        });
        // Undo and redo (EDIT-6)
        test.onClientRun(() -> {
            EditHistory history = EditorSession.history();
            check(history.scene().track(track).keyframes().size() == 2, "the two camera keyframes are not on their track");
            String undone = history.undo();
            check(undone != null && !undone.isBlank(), "undo did not describe what it undid");
            check(history.scene().track(track).keyframes().size() == 1, "undo did not take the second keyframe away");
            check(history.redo() != null && history.scene().track(track).keyframes().size() == 2, "redo did not bring the keyframe back");
            check(history.scene().track(track).kind() == KeyframeKind.CAMERA, "the keyframes are not on a camera track");
        });

        // The camera track moves the view while playing, and only then (EDIT-5)
        Vec3 parked = test.onClient(comedymonkey.dailies.playback.Viewer::position);
        test.waitTicks(5);
        check(test.onClient(comedymonkey.dailies.playback.Viewer::position).distanceTo(parked) < 1.0e-6, "a camera track moved the view while the recording was paused");
        test.onClientRun(() -> {
            EditorSession.setFollowCamera(true);
            Playback.playTo(to - 5);
        });
        test.waitFor(() -> Playback.paused() && Playback.position() == to - 5, 30, "play along the camera track");
        Vec3 driven = test.onClient(comedymonkey.dailies.playback.Viewer::position);
        check(Math.abs(driven.y - (recorder.y + 3)) < 0.01 && Math.abs(driven.z - (recorder.z - 6)) < 0.01 && driven.x > recorder.x - 6 && driven.x < recorder.x + 6,
            "the camera did not follow its track while playing: it is at " + driven);

        // Export two seconds as numbered pictures (EXP-1, EXP-2, EXP-4), at the video's size so that the video below can
        // be held against them
        PlaybackSteps.jump(test, from + 3);
        int tickBefore = Playback.position();
        int windowWidth = minecraft.getWindow().getWidth();
        Path folder = minecraft.gameDirectory.toPath().resolve("dailies").resolve("exports").resolve("self-test-frames");
        deleteFolder(folder);
        long started = System.currentTimeMillis();
        test.tool("export_start", args("output_path", folder.toString(), "container", "png_sequence", "width", 1920, "height", 1080, "framerate", 30,
            "start_tick", from, "end_tick", to, "no_gui", true));
        check(test.toolError("export_start", args("output_path", folder.toString(), "container", "png_sequence")).contains("already running"), "a second export was not refused");
        test.waitFor(() -> !Export.active(), 240, "the picture sequence to export");
        JsonObject done = test.tool("export_status", none);
        check(!done.has("last_error"), "the export failed: " + done);
        List<Path> frames;
        try (Stream<Path> files = Files.list(folder)) {
            frames = files.filter(file -> file.getFileName().toString().endsWith(".png")).sorted().toList();
        }
        int expected = (int) Math.round((to - from) / 20.0 * 30.0);
        check(frames.size() == expected, "exporting " + (to - from) + " ticks at 30 frames a second wrote " + frames.size() + " pictures, not " + expected);
        check(done.has("timings") && done.get("timings").getAsString().contains("over " + expected + " frames"), "the export did not say where its time went: " + done);
        BufferedImage first = ImageIO.read(frames.get(0).toFile());
        BufferedImage last = ImageIO.read(frames.get(frames.size() - 1).toFile());
        BufferedImage second = ImageIO.read(frames.get(1).toFile());
        check(first.getWidth() == 1920 && first.getHeight() == 1080, "the pictures are " + first.getWidth() + "x" + first.getHeight() + ", not 1920x1080");
        check(spread(first) > 12.0 && spread(last) > 12.0, "an exported picture is blank or nearly one colour");
        double moved = difference(first, last);
        double step = difference(first, second);
        check(moved > 4.0, "the first and last pictures are almost the same (" + moved + "): the camera did not move through the export");
        check(step < moved, "neighbouring frames differ more than the whole shot does: the frames are scrambled");
        test.log("exported " + frames.size() + " frames of 1920x1080 in " + (System.currentTimeMillis() - started) + " ms; first to last differ by "
            + String.format("%.1f", moved) + ", neighbours by " + String.format("%.1f", step));

        // Playback is exactly as it was (EXP-7)
        test.waitFor(() -> !Playback.jumping(), 30, "playback to be put back after the export");
        test.waitTicks(5);
        check(Playback.position() == tickBefore && Playback.paused(), "after the export playback is at tick " + Playback.position() + (Playback.paused() ? "" : " and playing") + ", not paused at " + tickBefore);
        check(minecraft.getWindow().getWidth() == windowWidth, "the window was left at another size after the export: " + minecraft.getWindow().getWidth() + " wide, not " + windowWidth);

        // A still, larger than the window (EXP-4)
        Path still = folder.resolveSibling("self-test-still.png");
        Files.deleteIfExists(still);
        test.tool("export_screenshot", args("output_path", still.toString(), "width", 2560, "height", 1440, "overwrite", true));
        test.waitFor(() -> !Export.active(), 60, "the still");
        BufferedImage picture = ImageIO.read(still.toFile());
        check(picture != null && picture.getWidth() == 2560 && picture.getHeight() == 1440 && spread(picture) > 12.0, "the still is missing, the wrong size or blank");

        if (!Encoder.bundled()) {
            test.skip("this build has no video encoder packed in: exporting a video file was not tried (EXP-1 containers and codecs, EXP-8), nor BUD-6 measured");
        } else {
            // Picture 7 is a quarter of a second in, which is frame 14 of a video at sixty frames a second
            video(test, minecraft, from, ImageIO.read(frames.get(7).toFile()));
            otherFormats(test, minecraft, from);
        }
    }

    /**
     * The one H.264 export: three and a half seconds of 1080p60. The file must hold every frame, of the right size and
     * length, with times that run on unbroken: on an NVIDIA card three encoders make it side by side, a second each in
     * turn, and their parts are joined, so four chunks give every encoder one and the first a second. A frame from it
     * must show what the same moment exported as pictures shows, which proves both the conversion on the graphics card
     * and that frames belong to moments of the recording, not to the clock (EXP-2). How fast it went is BUD-6's measure.
     */
    private static void video(SelfTest test, Minecraft minecraft, int from, BufferedImage reference) throws Exception {
        java.util.Map<String, List<String>> offered = Encoder.offered();
        test.log("the packed encoder offers " + offered);
        // H.264 comes from hardware encoders only (decided by the owner): a machine without one exports what stands in for it
        java.util.List<comedymonkey.dailies.export.Codecs.Choice> working = Encoder.choices();
        comedymonkey.dailies.export.Codecs.Pick pick = comedymonkey.dailies.export.Codecs.pick(List.of("h264"), "mp4", working);
        check(pick.codec() != null, "this machine can encode no video at all: " + offered);
        check(offered.containsKey("h264") == !pick.fellBack(), "H.264 is offered and was not chosen, or the other way round: " + offered);
        String codec = pick.codec();
        comedymonkey.dailies.export.Codecs.Choice choice = comedymonkey.dailies.export.Codecs.forCodec(working, codec).get(0);
        test.log(pick.fellBack() ? "this machine has no hardware H.264 encoder: the video checks use " + choice.label() + " instead" : "the video checks use " + choice.label());
        int to = from + 70;
        int expected = (int) Math.round((to - from) / 20.0 * 60.0);
        Path file = minecraft.gameDirectory.toPath().resolve("dailies").resolve("exports").resolve("self-test." + pick.container());
        Files.deleteIfExists(file);
        long started = System.nanoTime();
        test.tool("export_start", args("output_path", file.toString(), "container", pick.container(), "video_codec", codec, "width", 1920, "height", 1080, "framerate", 60,
            "start_tick", from, "end_tick", to, "no_gui", true, "bitrate", "20m"));
        test.waitFor(() -> !Export.active(), 300, "the video to export");
        double took = (System.nanoTime() - started) / 1.0e9;
        JsonObject done = test.tool("export_status", new JsonObject());
        check(!done.has("last_error"), "the video export failed: " + done);
        test.waitFor(() -> !Playback.jumping(), 30, "playback to be put back");
        check(Files.exists(file) && Files.size(file) > 20_000, "the video file is missing or nearly empty");
        String timings = done.has("timings") ? done.get("timings").getAsString() : "";
        if (Host.get().setting("exportGpuColour", true)) {
            check(timings.contains("converted on the graphics card"), "the video was not converted on the graphics card: " + timings);
        }

        // Ask the encoder's own inspector what is in the file
        Path probe = Encoder.inspector();
        String told = run(probe.toString(), "-v", "error", "-select_streams", "v:0", "-count_frames", "-show_entries",
            "stream=codec_name,width,height,nb_read_frames,r_frame_rate:format=duration", "-of", "default=noprint_wrappers=1", file.toString());
        check(told.contains("codec_name=" + comedymonkey.dailies.export.Codecs.probeName(codec)), "the video is not " + codec + ": " + told);
        check(told.contains("width=1920") && told.contains("height=1080"), "the video is not 1920x1080: " + told);
        check(told.contains("r_frame_rate=60/1"), "the video is not at 60 frames a second: " + told);
        check(told.contains("nb_read_frames=" + expected), "the video does not hold " + expected + " frames: " + told);
        double seconds = Double.parseDouble(told.lines().filter(line -> line.startsWith("duration=")).findFirst().orElse("duration=0").substring(9));
        check(Math.abs(seconds - (to - from) / 20.0) < 0.1, "the video is " + seconds + " seconds long, not " + (to - from) / 20.0);

        // Every frame a sixtieth of a second after the one before: parts joined in the wrong order, twice or with a gap
        // would show here. Where the keyframes fall says whether encoders side by side made it (one a second)
        String frameList = run(probe.toString(), "-v", "error", "-select_streams", "v:0", "-show_entries", "frame=key_frame,best_effort_timestamp_time",
            "-of", "compact=p=0", file.toString());
        List<Double> times = new java.util.ArrayList<>();
        List<Integer> keyframes = new java.util.ArrayList<>();
        for (String line : frameList.lines().map(String::trim).filter(line -> line.contains("best_effort_timestamp_time=")).toList()) {
            double time = Double.NaN;
            boolean key = false;
            for (String field : line.split("\\|")) {
                if (field.startsWith("best_effort_timestamp_time=")) {
                    time = Double.parseDouble(field.substring(field.indexOf('=') + 1));
                } else if (field.equals("key_frame=1")) {
                    key = true;
                }
            }
            if (key) {
                keyframes.add(times.size());
            }
            times.add(time);
        }
        check(times.size() == expected, "the inspector lists " + times.size() + " frames, not " + expected);
        check(Math.abs(times.get(0)) < 0.02, "the video's first frame is at " + times.get(0) + " s, not at the start");
        for (int i = 1; i < times.size(); i++) {
            double gap = times.get(i) - times.get(i - 1);
            check(Math.abs(gap - 1.0 / 60.0) < 0.004, "frame " + i + " comes " + String.format(java.util.Locale.ROOT, "%.4f", gap) + " s after the one before, not a sixtieth of a second: the video's parts do not run on unbroken");
        }

        // Pull frame 14 back out of the video and hold it against picture 7 of the numbered pictures: upside down, with
        // its colour planes muddled or of another moment, it would differ by tens of levels
        Path frame = file.resolveSibling("self-test-video-frame.png");
        Files.deleteIfExists(frame);
        run(Encoder.programPath().toString(), "-v", "error", "-y", "-i", file.toString(), "-vf", "select=eq(n\\,14)", "-frames:v", "1", frame.toString());
        BufferedImage picture = ImageIO.read(frame.toFile());
        check(picture != null && picture.getWidth() == 1920 && spread(picture) > 12.0, "a frame taken back out of the video is missing or blank");
        // Upright: the sky is above the grass
        int top = picture.getRGB(960, 40) & 0xFF;
        int bottom = picture.getRGB(960, 1040) & 0xFF;
        check(top > bottom, "the video is upside down or scrambled: blue at the top is " + top + " and at the bottom " + bottom);
        double against = difference(picture, reference);
        check(against < 8.0, "frame 14 of the video differs from the same moment exported as a picture by " + String.format(java.util.Locale.ROOT, "%.2f", against) + " a colour");
        test.log("exported " + expected + " frames of 1920x1080 " + choice.label() + " in " + String.format(java.util.Locale.ROOT, "%.1f", took) + " s, " + Files.size(file) / 1024
            + " KB; keyframes at frames " + keyframes + "; frame 14 against its picture " + String.format(java.util.Locale.ROOT, "%.2f", against) + " of 255; the file reports "
            + told.replace('\n', ' ').trim() + "; " + timings);

        if (pick.fellBack()) {
            test.skip("this machine has no hardware H.264 encoder: BUD-6, which is for hardware encoders, was not measured");
        } else {
            double video = (to - from) / 20.0;
            BudgetSteps.measured("BUD-6", "export speed, 1080p60 H.264 on hardware, against real time", video / took, 1.0, false, "x",
                String.format(java.util.Locale.ROOT, "%.1f s of video in %.1f s with %s, the scripted recording's video export (ExportSteps), the waits for terrain and the encoder's start included",
                    video, took, choice.encoder()));
        }
    }

    /**
     * H.265 in MKV and AV1 in MP4, the AV1 with the game's sound in stereo (EXP-3): each file must hold the codec asked
     * for and every frame, and the sound must be as long as the picture. On an NVIDIA card both are split across its
     * encoding units. VP9, ProRes and DNxHR are exported by the presets' steps.
     */
    private static void otherFormats(SelfTest test, Minecraft minecraft, int from) throws Exception {
        java.util.Map<String, List<String>> offered = Encoder.offered();
        String[][] wanted = {{"h265", "mkv", "hevc"}, {"av1", "mp4", "av1"}};
        StringBuilder worked = new StringBuilder();
        for (String[] format : wanted) {
            boolean sound = format[0].equals("av1");
            if (!offered.containsKey(format[0])) {
                test.skip("the packed encoder has nothing for " + format[0] + " on this machine: " + format[0] + " in " + format[1] + " was not tried" + (sound ? ", nor sound in exports (EXP-3)" : ""));
                continue;
            }
            int ticks = sound ? 20 : 10;
            int frames = ticks * 30 / 20;
            Path file = minecraft.gameDirectory.toPath().resolve("dailies").resolve("exports").resolve("self-test-" + format[0] + "." + format[1]);
            Files.deleteIfExists(file);
            JsonObject arguments = args("output_path", file.toString(), "container", format[1], "video_codec", format[0], "width", 640, "height", 360, "framerate", 30,
                "start_tick", from, "end_tick", from + ticks, "no_gui", true, "bitrate", "2m");
            if (sound) {
                arguments.addProperty("record_audio", true);
                arguments.addProperty("stereo_audio", true);
            }
            test.tool("export_start", arguments);
            test.waitFor(() -> !Export.active(), 300, "the " + format[0] + " export");
            JsonObject done = test.tool("export_status", new JsonObject());
            test.waitFor(() -> !Playback.jumping(), 30, "playback to be put back");
            if (done.has("last_error")) {
                // An encoder can be in the build and still not work on this machine (no such hardware, say)
                test.skip(format[0] + " in " + format[1] + " could not be encoded here: " + done.get("last_error").getAsString());
                continue;
            }
            String told = run(Encoder.inspector().toString(), "-v", "error", "-select_streams", "v:0", "-count_frames", "-show_entries", "stream=codec_name,nb_read_frames",
                "-of", "default=noprint_wrappers=1", file.toString());
            check(told.contains("codec_name=" + format[2]), "the " + format[1] + " file does not hold " + format[0] + ": " + told);
            check(told.contains("nb_read_frames=" + frames), ticks + " ticks of " + format[0] + " at 30 frames a second does not hold " + frames + " frames: " + told);
            worked.append(' ').append(format[0]).append(" in ").append(format[1]);
            if (sound) {
                worked.append(soundIn(test, file, done, ticks / 20.0));
            }
            worked.append(';');
        }
        test.log("also exported and checked:" + worked);
    }

    /** The sound of a video exported with it: a stereo AAC stream as long as the picture (EXP-3); says what it found */
    private static String soundIn(SelfTest test, Path file, JsonObject done, double length) throws Exception {
        String note = done.has("sound") ? done.get("sound").getAsString() : "";
        if (note.startsWith("no sound")) {
            test.skip("the game's sound could not be captured on this machine (" + note + "): sound in exports was not checked (EXP-3)");
            return ", without sound (" + note + ")";
        }
        String told = run(Encoder.inspector().toString(), "-v", "error", "-show_entries", "stream=codec_type,codec_name,channels,duration", "-of", "default=noprint_wrappers=1", file.toString());
        check(told.contains("codec_type=audio") && told.contains("codec_name=aac"), "the file has no AAC sound stream: " + told);
        check(told.contains("channels=2"), "the sound is not stereo: " + told);
        double longest = 0, shortest = 99;
        for (String line : told.lines().toList()) {
            if (line.startsWith("duration=")) {
                double seconds = Double.parseDouble(line.trim().substring(9));
                longest = Math.max(longest, seconds);
                shortest = Math.min(shortest, seconds);
            }
        }
        check(longest - shortest < 0.08 && Math.abs(longest - length) < 0.1, "sound and picture are not the same length: " + told);
        return ", with sound (" + note + "; " + told.replace('\n', ' ').trim() + ")";
    }

    private static String run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String said = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        check(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS), "a check of the video did not finish");
        return said;
    }

    /** How much the colours of a picture vary: near zero for a blank one */
    private static double spread(BufferedImage image) {
        double sum = 0, squares = 0;
        int count = 0;
        for (int y = 0; y < image.getHeight(); y += 4) {
            for (int x = 0; x < image.getWidth(); x += 4) {
                int rgb = image.getRGB(x, y);
                double light = ((rgb >> 16 & 0xFF) + (rgb >> 8 & 0xFF) + (rgb & 0xFF)) / 3.0;
                sum += light;
                squares += light * light;
                count++;
            }
        }
        double mean = sum / count;
        return Math.sqrt(Math.max(0.0, squares / count - mean * mean));
    }

}
