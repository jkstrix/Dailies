package comedymonkey.dailies.selftest;

import com.google.gson.JsonObject;
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

/**
 * Chapter XIV step 4, "Edit and export": camera keyframes with undo and redo, an export whose frames are counted,
 * measured and looked at, a still, and a check that playback is left as it was.
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
        test.sleepTicks(5);
        check(test.onClient(comedymonkey.dailies.playback.Viewer::position).distanceTo(parked) < 1.0e-6, "a camera track moved the view while the recording was paused");
        test.onClientRun(() -> {
            EditorSession.setFollowCamera(true);
            Playback.playTo(to - 5);
        });
        test.waitFor(() -> Playback.paused() && Playback.position() == to - 5, 30, "play along the camera track");
        Vec3 driven = test.onClient(comedymonkey.dailies.playback.Viewer::position);
        check(Math.abs(driven.y - (recorder.y + 3)) < 0.01 && Math.abs(driven.z - (recorder.z - 6)) < 0.01 && driven.x > recorder.x - 6 && driven.x < recorder.x + 6,
            "the camera did not follow its track while playing: it is at " + driven);

        // Export two seconds as numbered pictures (EXP-1, EXP-2, EXP-4)
        PlaybackSteps.jump(test, from + 3);
        int tickBefore = Playback.position();
        Path folder = minecraft.gameDirectory.toPath().resolve("dailies").resolve("exports").resolve("self-test-frames");
        deleteFolder(folder);
        long started = System.currentTimeMillis();
        test.tool("export_start", args("output_path", folder.toString(), "container", "png_sequence", "width", 640, "height", 360, "framerate", 30,
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
        BufferedImage first = ImageIO.read(frames.get(0).toFile());
        BufferedImage last = ImageIO.read(frames.get(frames.size() - 1).toFile());
        BufferedImage second = ImageIO.read(frames.get(1).toFile());
        check(first.getWidth() == 640 && first.getHeight() == 360, "the pictures are " + first.getWidth() + "x" + first.getHeight() + ", not 640x360");
        check(spread(first) > 12.0 && spread(last) > 12.0, "an exported picture is blank or nearly one colour");
        double moved = difference(first, last);
        double step = difference(first, second);
        check(moved > 4.0, "the first and last pictures are almost the same (" + moved + "): the camera did not move through the export");
        check(step < moved, "neighbouring frames differ more than the whole shot does: the frames are scrambled");
        test.log("exported " + frames.size() + " frames of 640x360 in " + (System.currentTimeMillis() - started) + " ms; first to last differ by "
            + String.format("%.1f", moved) + ", neighbours by " + String.format("%.1f", step));

        // Playback is exactly as it was (EXP-7)
        test.waitFor(() -> !Playback.jumping(), 30, "playback to be put back after the export");
        test.sleepTicks(5);
        check(Playback.position() == tickBefore && Playback.paused(), "after the export playback is at tick " + Playback.position() + (Playback.paused() ? "" : " and playing") + ", not paused at " + tickBefore);
        check(minecraft.getWindow().getWidth() != 640, "the window was left at the export's size");

        // The same frame again is the same picture: frames belong to moments of the recording, not to the clock (EXP-2)
        Path again = folder.resolveSibling("self-test-again");
        deleteFolder(again);
        test.tool("export_start", args("output_path", again.toString(), "container", "png_sequence", "width", 640, "height", 360, "framerate", 30,
            "start_tick", from, "end_tick", from + 8, "no_gui", true));
        test.waitFor(() -> !Export.active(), 120, "the second export");
        test.waitFor(() -> !Playback.jumping(), 30, "playback to be put back");
        BufferedImage repeat = ImageIO.read(again.resolve(frames.get(5).getFileName()).toFile());
        double drift = difference(ImageIO.read(frames.get(5).toFile()), repeat);
        check(drift < 1.5, "frame 5 came out differently the second time (difference " + drift + ")");

        // A still, larger than the window (EXP-4)
        Path still = folder.resolveSibling("self-test-still.png");
        Files.deleteIfExists(still);
        test.tool("export_screenshot", args("output_path", still.toString(), "width", 2560, "height", 1440, "overwrite", true));
        test.waitFor(() -> !Export.active(), 60, "the still");
        BufferedImage picture = ImageIO.read(still.toFile());
        check(picture != null && picture.getWidth() == 2560 && picture.getHeight() == 1440 && spread(picture) > 12.0, "the still is missing, the wrong size or blank");

        if (!Encoder.bundled()) {
            test.skip("this build has no video encoder packed in: exporting a video file was not tried (EXP-1 containers and codecs, EXP-8)");
        }
        test.skip("sound in exports is not built yet (EXP-3)");
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

    /** The average difference between two pictures, per colour, on a scale of 0 to 255 */
    private static double difference(BufferedImage a, BufferedImage b) {
        double sum = 0;
        int count = 0;
        for (int y = 0; y < Math.min(a.getHeight(), b.getHeight()); y += 2) {
            for (int x = 0; x < Math.min(a.getWidth(), b.getWidth()); x += 2) {
                int p = a.getRGB(x, y);
                int q = b.getRGB(x, y);
                sum += Math.abs((p >> 16 & 0xFF) - (q >> 16 & 0xFF)) + Math.abs((p >> 8 & 0xFF) - (q >> 8 & 0xFF)) + Math.abs((p & 0xFF) - (q & 0xFF));
                count += 3;
            }
        }
        return sum / count;
    }

    private static void deleteFolder(Path folder) throws Exception {
        if (Files.isDirectory(folder)) {
            try (Stream<Path> files = Files.list(folder)) {
                for (Path file : files.toList()) {
                    Files.delete(file);
                }
            }
            Files.delete(folder);
        }
    }

}
