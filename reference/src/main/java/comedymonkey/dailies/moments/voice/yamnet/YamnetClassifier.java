package comedymonkey.dailies.moments.voice.yamnet;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.moments.voice.BurstClassifier;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Names a burst with YAMNet, on the player's own computer: laughter, cheering, screaming or shouting, whichever of
 * them scores highest, if any reaches {@link #THRESHOLD}. Each name stands for a few AudioSet classes, and a name's
 * score is its best class in its best patch, since a burst seldom fills the whole stretch of audio it is cut from.
 * <p>
 * The model is loaded the first time something is classified, not when the game starts. If it cannot be loaded
 * the classifier says so once in the log and answers nothing from then on.
 */
public final class YamnetClassifier implements BurstClassifier {

    /** The least score a name needs. YAMNet's scores are independent probabilities, and real laughter usually scores well above this */
    public static final double THRESHOLD = 0.2;

    /** Each label and the AudioSet classes (YAMNet display names) that count for it */
    public static final Map<String, List<String>> LABELS;

    static {
        Map<String, List<String>> labels = new LinkedHashMap<>();
        labels.put("laughter", List.of("Laughter", "Baby laughter", "Giggle", "Snicker", "Belly laugh", "Chuckle, chortle"));
        labels.put("cheering", List.of("Cheering"));
        labels.put("screaming", List.of("Screaming"));
        labels.put("shouting", List.of("Shout", "Yell", "Children shouting"));
        LABELS = java.util.Collections.unmodifiableMap(labels);
    }

    private final Object lock = new Object();
    private volatile @Nullable Yamnet model;
    private volatile boolean failed;

    public YamnetClassifier() {
    }

    /** For tests and anything else that already has the model */
    public YamnetClassifier(Yamnet model) {
        this.model = model;
    }

    @Override
    public @Nullable String classify(short[] pcm, int sampleRate) {
        Yamnet yamnet = this.model();
        if (yamnet == null || pcm.length == 0 || sampleRate <= 0) {
            return null;
        }
        return label(yamnet.classNames(), yamnet.scores(toModelRate(pcm, sampleRate)));
    }

    private @Nullable Yamnet model() {
        Yamnet loaded = this.model;
        if (loaded != null || this.failed) {
            return loaded;
        }
        synchronized (this.lock) {
            if (this.model == null && !this.failed) {
                try {
                    this.model = Yamnet.load();
                } catch (Throwable t) {
                    this.failed = true;
                    Host.LOGGER.error("Moments: the sound classifier could not be loaded, so bursts stay unnamed", t);
                }
            }
            return this.model;
        }
    }

    /** The best label for these scores, or null when none reaches the threshold */
    public static @Nullable String label(List<String> classNames, float[][] scores) {
        String best = null;
        double bestScore = THRESHOLD;
        for (Map.Entry<String, Double> entry : labelScores(classNames, scores).entrySet()) {
            if (entry.getValue() >= bestScore) {
                best = entry.getKey();
                bestScore = entry.getValue();
            }
        }
        return best;
    }

    /** Each label's score: its best class in its best patch */
    public static Map<String, Double> labelScores(List<String> classNames, float[][] scores) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : LABELS.entrySet()) {
            double score = 0.0;
            for (String name : entry.getValue()) {
                int index = classNames.indexOf(name);
                if (index < 0) continue;
                for (float[] patch : scores) {
                    score = Math.max(score, patch[index]);
                }
            }
            result.put(entry.getKey(), score);
        }
        return result;
    }

    /**
     * 16-bit audio at any rate to the model's 16 kHz, full scale at 1.0. Going down, a windowed-sinc low-pass keeps
     * what lies above the new Nyquist frequency from folding back in as noise the model would hear.
     */
    public static float[] toModelRate(short[] pcm, int sampleRate) {
        if (sampleRate == Yamnet.SAMPLE_RATE) {
            float[] out = new float[pcm.length];
            for (int i = 0; i < pcm.length; i++) {
                out[i] = pcm[i] / 32768.0f;
            }
            return out;
        }
        double ratio = sampleRate / (double) Yamnet.SAMPLE_RATE;
        // Cut-off in cycles per input sample, a little under the lower of the two Nyquist frequencies
        double cutoff = 0.5 * Math.min(1.0, 1.0 / ratio) * 0.92;
        int half = (int) Math.ceil(8.0 / cutoff);
        int length = (int) ((long) pcm.length * Yamnet.SAMPLE_RATE / sampleRate);
        float[] out = new float[length];
        for (int n = 0; n < length; n++) {
            double center = n * ratio;
            int from = Math.max(0, (int) Math.ceil(center - half));
            int to = Math.min(pcm.length - 1, (int) Math.floor(center + half));
            double sum = 0.0;
            for (int k = from; k <= to; k++) {
                double d = k - center;
                double x = 2.0 * cutoff * d;
                double sinc = x == 0.0 ? 1.0 : Math.sin(Math.PI * x) / (Math.PI * x);
                double window = 0.5 + 0.5 * Math.cos(Math.PI * d / half);
                sum += pcm[k] * 2.0 * cutoff * sinc * window;
            }
            out[n] = (float) (sum / 32768.0);
        }
        return out;
    }

}
