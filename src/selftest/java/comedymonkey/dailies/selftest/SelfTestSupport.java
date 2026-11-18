package comedymonkey.dailies.selftest;

import com.google.gson.JsonArray;
import comedymonkey.dailies.playback.Playback;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static comedymonkey.dailies.selftest.SelfTest.check;

/**
 * Small things several steps need, kept here once rather than copied into each: comparing pictures, clearing folders,
 * a JSON list of words, a long answer cut short for a failure message, and the two ways of jumping in playback.
 */
final class SelfTestSupport {

    /** How much of a long answer a failure message quotes */
    private static final int SHORTENED = 800;

    private SelfTestSupport() {
    }

    /** The average difference between two pictures, per colour, on a scale of 0 to 255 */
    static double difference(BufferedImage a, BufferedImage b) {
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

    /** A folder of files, and the folder itself */
    static void deleteFolder(Path folder) throws IOException {
        if (Files.isDirectory(folder)) {
            emptyFolder(folder);
            Files.delete(folder);
        }
    }

    /** The files in a folder, leaving the folder */
    static void emptyFolder(Path folder) throws IOException {
        if (Files.isDirectory(folder)) {
            try (Stream<Path> files = Files.list(folder)) {
                for (Path file : files.toList()) {
                    Files.delete(file);
                }
            }
        }
    }

    /** A folder and everything under it, however deep */
    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    static JsonArray array(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    /** A long answer cut short, for a failure message */
    static String shorten(String text) {
        return text.length() > SHORTENED ? text.substring(0, SHORTENED) + "..." : text;
    }

    /** Jumps, and waits until the recording stands at exactly that tick */
    static void jump(SelfTest test, int tick, int seconds) throws Exception {
        test.onClientRun(() -> Playback.jumpTo(tick, true));
        test.waitFor(() -> !Playback.jumping() && Playback.position() == tick, seconds, "the jump to tick " + tick);
    }

    /**
     * Jumps, and says in milliseconds how long until the recording stood at exactly that tick: watched every two
     * milliseconds, for the budgets, and failed straight away if playback stops on the way
     */
    static long timedJump(SelfTest test, int tick) throws Exception {
        long started = System.nanoTime();
        test.onClientRun(() -> Playback.jumpTo(tick, true));
        long deadline = started + 120_000_000_000L;
        while (Playback.jumping() || Playback.position() != tick) {
            check(System.nanoTime() - deadline < 0, "gave up waiting for the jump to tick " + tick);
            check(Playback.lastError() == null, "playback stopped during the jump to tick " + tick + ": " + Playback.lastError());
            Thread.sleep(2);
        }
        return (System.nanoTime() - started) / 1_000_000;
    }
}
