package comedymonkey.dailies.selftest;

import com.google.gson.JsonObject;
import comedymonkey.dailies.export.Codecs;
import comedymonkey.dailies.export.Encoder;
import comedymonkey.dailies.export.Export;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.client.Minecraft;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

import static comedymonkey.dailies.selftest.SelfTest.args;
import static comedymonkey.dailies.selftest.SelfTest.check;

/**
 * The faster export path: frames read back from the graphics card a couple of frames late and, for video, converted
 * to the encoder's own format on the card. A video made that way must show what the same range exported as pictures
 * shows, the right way up, no worse than one converted by the encoder as before; and every export says where its
 * time went.
 */
final class ExportSpeedSteps {

    private ExportSpeedSteps() {
    }

    static void run(SelfTest test) throws Exception {
        if (!Encoder.bundled()) {
            test.skip("this build has no video encoder packed in: the conversion of video frames on the graphics card was not tried");
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        RecordingSteps.Note middle = RecordingSteps.notes.get(RecordingSteps.notes.size() / 2);
        int from = middle.tick() - 30;
        int to = from + 10;
        Path folder = minecraft.gameDirectory.toPath().resolve("dailies").resolve("exports");

        // The range as pictures: what the video frames are held against
        Path pictures = folder.resolve("self-test-speed-frames");
        deleteFolder(pictures);
        test.tool("export_start", args("output_path", pictures.toString(), "container", "png_sequence", "width", 640, "height", 360, "framerate", 30,
            "start_tick", from, "end_tick", to, "no_gui", true));
        test.waitFor(() -> !Export.active(), 120, "the pictures to hold the video against");
        test.waitFor(() -> !Playback.jumping(), 30, "playback to be put back");
        JsonObject done = test.tool("export_status", new JsonObject());
        check(!done.has("last_error"), "the pictures could not be exported: " + done);
        check(done.has("timings") && done.get("timings").getAsString().contains("over 15 frames"), "the export did not say where its time went: " + done);
        BufferedImage reference = ImageIO.read(pictures.resolve("frame_000007.png").toFile());
        check(reference != null, "picture 7 of the range was not written");

        Codecs.Pick pick = Codecs.pick(List.of("h264"), "mp4", Encoder.choices());
        check(pick.codec() != null, "this machine can encode no video at all");
        double[] differences = new double[2];
        String[] timings = new String[2];
        try {
            for (int pass = 0; pass < 2; pass++) {
                boolean onCard = pass == 0;
                test.tool("config_set", args("key", "exportGpuColour", "value", onCard));
                Path file = folder.resolve("self-test-speed-" + (onCard ? "card" : "encoder") + "." + pick.container());
                Files.deleteIfExists(file);
                test.tool("export_start", args("output_path", file.toString(), "container", pick.container(), "video_codec", pick.codec(), "width", 640, "height", 360,
                    "framerate", 30, "start_tick", from, "end_tick", to, "no_gui", true, "bitrate", "20m"));
                test.waitFor(() -> !Export.active(), 120, "the video converted " + (onCard ? "on the graphics card" : "by the encoder"));
                test.waitFor(() -> !Playback.jumping(), 30, "playback to be put back");
                done = test.tool("export_status", new JsonObject());
                check(!done.has("last_error"), "the video converted " + (onCard ? "on the graphics card" : "by the encoder") + " failed: " + done);
                timings[pass] = done.has("timings") ? done.get("timings").getAsString() : "";
                check(timings[pass].contains(onCard ? "converted on the graphics card" : "RGBA"),
                    "the video was not converted " + (onCard ? "on the graphics card" : "by the encoder") + " as asked: " + timings[pass]);
                String told = run(Encoder.inspector().toString(), "-v", "error", "-select_streams", "v:0", "-count_frames", "-show_entries", "stream=width,height,nb_read_frames,pix_fmt",
                    "-of", "default=noprint_wrappers=1", file.toString());
                check(told.contains("width=640") && told.contains("height=360") && told.contains("nb_read_frames=15"), "the video does not hold 15 frames of 640x360: " + told);
                Path frame = folder.resolve("self-test-speed-frame-" + pass + ".png");
                Files.deleteIfExists(frame);
                run(Encoder.programPath().toString(), "-v", "error", "-y", "-i", file.toString(), "-vf", "select=eq(n\\,7)", "-frames:v", "1", frame.toString());
                BufferedImage picture = ImageIO.read(frame.toFile());
                check(picture != null && picture.getWidth() == 640, "frame 7 could not be taken back out of the video");
                differences[pass] = difference(picture, reference);
            }
        } finally {
            test.tool("config_set", args("key", "exportGpuColour", "value", true));
        }
        // Upside down or with its colour planes muddled, a frame would differ from its picture by tens of levels
        check(differences[0] < 8.0, "a video frame converted on the graphics card differs from the same frame as a picture by " + String.format("%.2f", differences[0]) + " a colour");
        check(differences[0] < differences[1] + 2.0, "a video frame converted on the graphics card differs from its picture by " + String.format("%.2f", differences[0])
            + ", against " + String.format("%.2f", differences[1]) + " when the encoder converts it");
        test.log(String.format("frame 7 against its picture: converted on the card %.2f, by the encoder %.2f (of 255). %s | %s", differences[0], differences[1], timings[0], timings[1]));
    }

    private static String run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String said = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        check(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS), "a check of the video did not finish");
        return said;
    }

    /** The average difference between two pictures, per colour, on a scale of 0 to 255 */
    private static double difference(BufferedImage a, BufferedImage b) {
        double sum = 0;
        int count = 0;
        for (int y = 0; y < Math.min(a.getHeight(), b.getHeight()); y++) {
            for (int x = 0; x < Math.min(a.getWidth(), b.getWidth()); x++) {
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
