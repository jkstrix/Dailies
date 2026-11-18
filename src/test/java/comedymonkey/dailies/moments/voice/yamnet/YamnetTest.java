package comedymonkey.dailies.moments.voice.yamnet;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Java YAMNet against scores worked out by a numpy transcription of Google's own front end and network, with
 * Google's float32 weights and unfolded batch norm (the fixtures in src/test/resources/yamnet, made by the same
 * script that converted the weights). The difference left is the float16 weights and float32 sums.
 */
class YamnetTest {

    /** Largest difference allowed in any of the 521 scores of any patch */
    private static final double TOLERANCE = 0.01;

    private static Yamnet model;

    @BeforeAll
    static void load() throws IOException {
        model = Yamnet.load();
    }

    private static short[] pcm(String name) throws IOException {
        byte[] bytes = resource(name + ".pcm");
        short[] samples = new short[bytes.length / 2];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples);
        return samples;
    }

    private static float[][] expected(String name) throws IOException {
        List<String> lines = new String(resource(name + ".scores"), StandardCharsets.UTF_8).lines().filter(line -> !line.isBlank()).toList();
        float[][] scores = new float[lines.size()][];
        for (int i = 0; i < scores.length; i++) {
            String[] parts = lines.get(i).trim().split(" ");
            scores[i] = new float[parts.length];
            for (int j = 0; j < parts.length; j++) {
                scores[i][j] = Float.parseFloat(parts[j]);
            }
        }
        return scores;
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = YamnetTest.class.getResourceAsStream("/yamnet/" + name)) {
            assertNotNull(in, "missing fixture " + name);
            return in.readAllBytes();
        }
    }

    private static int top(float[] scores) {
        int best = 0;
        for (int i = 1; i < scores.length; i++) {
            if (scores[i] > scores[best]) best = i;
        }
        return best;
    }

    private static void matches(String name) throws IOException {
        float[][] want = expected(name);
        float[][] got = model.scores(YamnetClassifier.toModelRate(pcm(name), Yamnet.SAMPLE_RATE));
        assertEquals(want.length, got.length, name + ": number of patches");
        double worst = 0.0;
        for (int p = 0; p < want.length; p++) {
            assertEquals(Yamnet.CLASSES, got[p].length);
            for (int c = 0; c < Yamnet.CLASSES; c++) {
                worst = Math.max(worst, Math.abs(want[p][c] - got[p][c]));
            }
            assertEquals(top(want[p]), top(got[p]), name + ": top class of patch " + p);
        }
        System.out.printf("YAMNet %s: %d patches, largest score difference from the reference %.5f%n", name, want.length, worst);
        assertTrue(worst <= TOLERANCE, name + ": scores differ from the reference by " + worst);
    }

    @Test
    void sineMatchesReference() throws IOException {
        matches("sine");
    }

    @Test
    void noiseMatchesReference() throws IOException {
        matches("noise");
    }

    @Test
    void voiceLikePulsesMatchReference() throws IOException {
        matches("ha");
    }

    @Test
    void classesAreAudioSets() {
        assertEquals(Yamnet.CLASSES, model.classNames().size());
        for (List<String> classes : YamnetClassifier.LABELS.values()) {
            for (String name : classes) {
                assertTrue(model.classNames().contains(name), name);
            }
        }
    }

    @Test
    void fortyEightKilohertzComesOutLikeSixteen() throws IOException {
        // The same tone as the sine fixture, made at the voice chat's rate
        short[] pcm48 = new short[48000 * 6 / 5];
        for (int i = 0; i < pcm48.length; i++) {
            pcm48[i] = (short) Math.round(0.5 * Math.sin(2 * Math.PI * 1000.0 * i / 48000.0) * 32767.0);
        }
        float[][] want = expected("sine");
        float[][] got = model.scores(YamnetClassifier.toModelRate(pcm48, 48000));
        assertEquals(want.length, got.length);
        int beep = top(want[0]);
        assertEquals(beep, top(got[0]));
        assertEquals(want[0][beep], got[0][beep], 0.05);
    }

    /**
     * A measure of the machine as much as of the code, so it is a benchmark, left out of the unit tests a build runs
     * (a busy machine would fail it for nothing): gradlew benchmarks
     */
    @Test
    @Tag("benchmark")
    void oneSecondTakesWellUnderASecond() {
        short[] second = new short[Yamnet.SAMPLE_RATE];
        java.util.Random random = new java.util.Random(7);
        for (int i = 0; i < second.length; i++) {
            second[i] = (short) (random.nextGaussian() * 6000);
        }
        YamnetClassifier classifier = new YamnetClassifier(model);
        for (int i = 0; i < 3; i++) {
            classifier.classify(second, Yamnet.SAMPLE_RATE);
        }
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long start = System.nanoTime();
            classifier.classify(second, Yamnet.SAMPLE_RATE);
            best = Math.min(best, System.nanoTime() - start);
        }
        System.out.printf("YAMNet: one second of audio classified in %.1f ms%n", best / 1e6);
        assertTrue(best < 500_000_000L, "one second of audio took " + best / 1e6 + " ms");
    }

    @Test
    void labelIsTheBestNameAboveTheThreshold() {
        List<String> names = model.classNames();
        float[][] scores = new float[2][Yamnet.CLASSES];
        assertNull(YamnetClassifier.label(names, scores));
        scores[0][names.indexOf("Shout")] = 0.4f;
        assertEquals("shouting", YamnetClassifier.label(names, scores));
        scores[1][names.indexOf("Giggle")] = 0.6f;
        assertEquals("laughter", YamnetClassifier.label(names, scores));
        scores[1][names.indexOf("Cheering")] = 0.9f;
        assertEquals("cheering", YamnetClassifier.label(names, scores));
        float[][] quiet = new float[1][Yamnet.CLASSES];
        quiet[0][names.indexOf("Screaming")] = (float) (YamnetClassifier.THRESHOLD / 2);
        assertNull(YamnetClassifier.label(names, quiet));
    }

}
