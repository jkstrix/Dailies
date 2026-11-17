package comedymonkey.dailies.moments.voice.yamnet;

/**
 * YAMNet's front end, the same sums as Google's features.py with the hyperparameters of its params.py: 16 kHz mono,
 * a 25 ms periodic Hann window every 10 ms into a 512-point FFT, magnitudes onto 64 HTK mel bands from 125 to
 * 7500 Hz, log with an offset of 0.001, then patches of 96 frames every 48. The model was trained on exactly this,
 * so nothing here is to be tuned.
 */
final class LogMel {

    static final int SAMPLE_RATE = 16000;
    static final int WINDOW = 400;
    static final int HOP = 160;
    static final int FFT = 512;
    static final int BINS = FFT / 2 + 1;
    static final int BANDS = 64;
    static final int PATCH_FRAMES = 96;
    static final int PATCH_HOP = 48;
    private static final double MEL_LOW_HZ = 125.0;
    private static final double MEL_HIGH_HZ = 7500.0;
    private static final double LOG_OFFSET = 0.001;
    /** One patch window plus the rest of its last analysis window, worked out as features.py does it (float32 cast) */
    static final int MIN_SAMPLES = (int) (float) ((0.96 + 0.025 - 0.010) * SAMPLE_RATE);
    static final int PATCH_HOP_SAMPLES = (int) (float) (0.48 * SAMPLE_RATE);

    private static final double[] HANN = new double[WINDOW];
    /** [bin][band], with bin 0 (the DC bin) left at zero as tf.signal.linear_to_mel_weight_matrix leaves it */
    private static final double[][] MEL = new double[BINS][BANDS];
    private static final int[] BIT_REVERSE = new int[FFT];
    private static final double[] COS = new double[FFT / 2];
    private static final double[] SIN = new double[FFT / 2];

    static {
        for (int i = 0; i < WINDOW; i++) {
            HANN[i] = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / WINDOW);
        }
        double low = hertzToMel(MEL_LOW_HZ);
        double step = (hertzToMel(MEL_HIGH_HZ) - low) / (BANDS + 1);
        for (int bin = 1; bin < BINS; bin++) {
            double mel = hertzToMel(bin * (SAMPLE_RATE / 2.0) / (BINS - 1));
            for (int band = 0; band < BANDS; band++) {
                double lower = low + band * step;
                double center = low + (band + 1) * step;
                double upper = band + 2 == BANDS + 1 ? hertzToMel(MEL_HIGH_HZ) : low + (band + 2) * step;
                double rising = (mel - lower) / (center - lower);
                double falling = (upper - mel) / (upper - center);
                MEL[bin][band] = Math.max(0.0, Math.min(rising, falling));
            }
        }
        int bits = Integer.numberOfTrailingZeros(FFT);
        for (int i = 0; i < FFT; i++) {
            BIT_REVERSE[i] = Integer.reverse(i) >>> (32 - bits);
        }
        for (int i = 0; i < FFT / 2; i++) {
            COS[i] = Math.cos(-2.0 * Math.PI * i / FFT);
            SIN[i] = Math.sin(-2.0 * Math.PI * i / FFT);
        }
    }

    private LogMel() {
    }

    private static double hertzToMel(double hertz) {
        return 1127.0 * Math.log(1.0 + hertz / 700.0);
    }

    /** Silence on the end so that the audio makes whole patches, and at least one */
    static float[] pad(float[] waveform) {
        int samples = Math.max(waveform.length, MIN_SAMPLES);
        int after = samples - MIN_SAMPLES;
        int hops = (after + PATCH_HOP_SAMPLES - 1) / PATCH_HOP_SAMPLES;
        return java.util.Arrays.copyOf(waveform, MIN_SAMPLES + hops * PATCH_HOP_SAMPLES);
    }

    /** @return each patch as 96 frames of 64 bands, frame after frame */
    static float[][] patches(float[] waveform) {
        float[] padded = pad(waveform);
        int frames = 1 + (padded.length - WINDOW) / HOP;
        float[] logMel = new float[frames * BANDS];
        double[] re = new double[FFT];
        double[] im = new double[FFT];
        double[] mel = new double[BANDS];
        for (int frame = 0; frame < frames; frame++) {
            java.util.Arrays.fill(re, 0.0);
            java.util.Arrays.fill(im, 0.0);
            int start = frame * HOP;
            for (int i = 0; i < WINDOW; i++) {
                re[BIT_REVERSE[i]] = padded[start + i] * HANN[i];
            }
            fft(re, im);
            java.util.Arrays.fill(mel, 0.0);
            for (int bin = 1; bin < BINS; bin++) {
                double magnitude = Math.sqrt(re[bin] * re[bin] + im[bin] * im[bin]);
                double[] weights = MEL[bin];
                for (int band = 0; band < BANDS; band++) {
                    mel[band] += magnitude * weights[band];
                }
            }
            for (int band = 0; band < BANDS; band++) {
                logMel[frame * BANDS + band] = (float) Math.log(mel[band] + LOG_OFFSET);
            }
        }
        int count = 1 + (frames - PATCH_FRAMES) / PATCH_HOP;
        float[][] patches = new float[count][];
        for (int patch = 0; patch < count; patch++) {
            int from = patch * PATCH_HOP * BANDS;
            patches[patch] = java.util.Arrays.copyOfRange(logMel, from, from + PATCH_FRAMES * BANDS);
        }
        return patches;
    }

    /** In-place radix-2 FFT of input already put in bit-reversed order */
    private static void fft(double[] re, double[] im) {
        for (int size = 2; size <= FFT; size <<= 1) {
            int half = size >> 1;
            int stride = FFT / size;
            for (int start = 0; start < FFT; start += size) {
                for (int k = 0; k < half; k++) {
                    double wr = COS[k * stride];
                    double wi = SIN[k * stride];
                    int a = start + k;
                    int b = a + half;
                    double tr = re[b] * wr - im[b] * wi;
                    double ti = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                }
            }
        }
    }

}
