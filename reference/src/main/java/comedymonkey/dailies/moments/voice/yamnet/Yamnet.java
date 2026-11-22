package comedymonkey.dailies.moments.voice.yamnet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Google's YAMNet sound classifier (Apache 2.0), run in plain Java: a MobileNet v1 body of one convolution and
 * thirteen depthwise-separable blocks over a 96 by 64 log mel patch, average pooled, then 521 logistic outputs, one
 * per AudioSet class. Batch norm was folded into the weights when they were converted, so each layer here is a
 * convolution, a bias and a ReLU.
 * <p>
 * The weights are {@code assets/dailies/models/yamnet.bin}, made from Google's yamnet.h5 by a conversion script:
 * the bytes "DYAM", an int version (1), an int tensor count, then per tensor an int name length, the UTF-8 name, a
 * type byte (1 float16, 2 float32), an int rank, the int dimensions and the values in row-major order, all
 * little-endian. Kernels are float16, which halves the size and moves a score by well under a hundredth.
 * <p>
 * An instance holds no state between calls and may be used from several threads at once.
 */
public final class Yamnet {

    public static final int SAMPLE_RATE = LogMel.SAMPLE_RATE;
    public static final int CLASSES = 521;
    private static final String WEIGHTS = "/assets/dailies/models/yamnet.bin";
    private static final String CLASS_NAMES = "/assets/dailies/models/yamnet_classes.txt";
    /** Stride and filters of layers 2 to 14; layer 1 is a plain convolution of stride 2 into 32 */
    private static final int[][] BLOCKS = {
        {1, 64}, {2, 128}, {1, 128}, {2, 256}, {1, 256}, {2, 512}, {1, 512}, {1, 512}, {1, 512}, {1, 512}, {1, 512}, {2, 1024}, {1, 1024}
    };
    private static final int FIRST_FILTERS = 32;

    private final float[] firstKernel;
    private final float[] firstBias;
    private final float[][] depthwise = new float[BLOCKS.length][];
    private final float[][] depthwiseBias = new float[BLOCKS.length][];
    private final float[][] pointwise = new float[BLOCKS.length][];
    private final float[][] pointwiseBias = new float[BLOCKS.length][];
    private final float[] logitsKernel;
    private final float[] logitsBias;
    private final List<String> classNames;

    private Yamnet(Map<String, float[]> tensors, List<String> classNames) throws IOException {
        this.firstKernel = need(tensors, "layer1/kernel", 9 * FIRST_FILTERS);
        this.firstBias = need(tensors, "layer1/bias", FIRST_FILTERS);
        int channels = FIRST_FILTERS;
        for (int i = 0; i < BLOCKS.length; i++) {
            String name = "layer" + (i + 2);
            int filters = BLOCKS[i][1];
            this.depthwise[i] = need(tensors, name + "/depthwise", 9 * channels);
            this.depthwiseBias[i] = need(tensors, name + "/depthwise_bias", channels);
            this.pointwise[i] = need(tensors, name + "/pointwise", channels * filters);
            this.pointwiseBias[i] = need(tensors, name + "/pointwise_bias", filters);
            channels = filters;
        }
        this.logitsKernel = need(tensors, "logits/kernel", channels * CLASSES);
        this.logitsBias = need(tensors, "logits/bias", CLASSES);
        if (classNames.size() != CLASSES) {
            throw new IOException("YAMNet class list has " + classNames.size() + " names, not " + CLASSES);
        }
        this.classNames = List.copyOf(classNames);
    }

    private static float[] need(Map<String, float[]> tensors, String name, int size) throws IOException {
        float[] values = tensors.get(name);
        if (values == null || values.length != size) {
            throw new IOException("YAMNet weights lack " + name + " of " + size + " values");
        }
        return values;
    }

    /** Reads the bundled weights. Takes a fraction of a second and about 15 MB, so it is done once, when first needed */
    public static Yamnet load() throws IOException {
        byte[] weights;
        byte[] names;
        try (InputStream in = Yamnet.class.getResourceAsStream(WEIGHTS); InputStream list = Yamnet.class.getResourceAsStream(CLASS_NAMES)) {
            if (in == null || list == null) {
                throw new IOException("The YAMNet model is missing from the mod");
            }
            weights = in.readAllBytes();
            names = list.readAllBytes();
        }
        return new Yamnet(readTensors(weights), new String(names, StandardCharsets.UTF_8).lines().toList());
    }

    static Map<String, float[]> readTensors(byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.length < 12 || buffer.get() != 'D' || buffer.get() != 'Y' || buffer.get() != 'A' || buffer.get() != 'M') {
            throw new IOException("Not a YAMNet weights file");
        }
        int version = buffer.getInt();
        if (version != 1) {
            throw new IOException("Unknown YAMNet weights version " + version);
        }
        int count = buffer.getInt();
        Map<String, float[]> tensors = new HashMap<>();
        try {
            for (int t = 0; t < count; t++) {
                byte[] name = new byte[buffer.getInt()];
                buffer.get(name);
                byte type = buffer.get();
                int rank = buffer.getInt();
                long size = 1;
                for (int d = 0; d < rank; d++) {
                    size *= buffer.getInt();
                }
                if (size > Integer.MAX_VALUE / 4 || (type != 1 && type != 2)) {
                    throw new IOException("Bad YAMNet tensor " + new String(name, StandardCharsets.UTF_8));
                }
                float[] values = new float[(int) size];
                for (int i = 0; i < values.length; i++) {
                    values[i] = type == 1 ? Float.float16ToFloat(buffer.getShort()) : buffer.getFloat();
                }
                tensors.put(new String(name, StandardCharsets.UTF_8), values);
            }
        } catch (java.nio.BufferUnderflowException e) {
            throw new IOException("YAMNet weights file is cut short", e);
        }
        return tensors;
    }

    /** The AudioSet display name of each output, in output order */
    public List<String> classNames() {
        return this.classNames;
    }

    /**
     * @param waveform 16 kHz mono, full scale at 1.0
     * @return the 521 scores of each 0.96 s patch, patches 0.48 s apart; short audio is padded to one patch
     */
    public float[][] scores(float[] waveform) {
        float[][] patches = LogMel.patches(waveform);
        float[][] scores = new float[patches.length][];
        for (int i = 0; i < patches.length; i++) {
            scores[i] = this.classify(patches[i]);
        }
        return scores;
    }

    /** One patch of 96 frames by 64 bands, through the network. Activations are height by width by channel */
    float[] classify(float[] patch) {
        int height = LogMel.PATCH_FRAMES;
        int width = LogMel.BANDS;
        float[] x = firstConv(patch, height, width);
        height = outSize(height, 2);
        width = outSize(width, 2);
        int channels = FIRST_FILTERS;
        for (int i = 0; i < BLOCKS.length; i++) {
            int stride = BLOCKS[i][0];
            x = depthwise(x, height, width, channels, stride, this.depthwise[i], this.depthwiseBias[i]);
            height = outSize(height, stride);
            width = outSize(width, stride);
            x = pointwise(x, height * width, channels, BLOCKS[i][1], this.pointwise[i], this.pointwiseBias[i]);
            channels = BLOCKS[i][1];
        }

        float[] embedding = new float[channels];
        int pixels = height * width;
        for (int p = 0; p < pixels; p++) {
            for (int c = 0; c < channels; c++) {
                embedding[c] += x[p * channels + c];
            }
        }
        float[] scores = this.logitsBias.clone();
        for (int c = 0; c < channels; c++) {
            float value = embedding[c] / pixels;
            if (value == 0.0f) {
                continue;
            }
            int row = c * CLASSES;
            for (int o = 0; o < CLASSES; o++) {
                scores[o] += value * this.logitsKernel[row + o];
            }
        }
        for (int o = 0; o < CLASSES; o++) {
            scores[o] = (float) (1.0 / (1.0 + Math.exp(-scores[o])));
        }
        return scores;
    }

    /** Keras "same" padding: as many outputs as strides fit, any odd padding going after */
    private static int outSize(int size, int stride) {
        return (size + stride - 1) / stride;
    }

    private static int padBefore(int size, int stride) {
        return Math.max((outSize(size, stride) - 1) * stride + 3 - size, 0) / 2;
    }

    private float[] firstConv(float[] in, int height, int width) {
        int outHeight = outSize(height, 2);
        int outWidth = outSize(width, 2);
        int top = padBefore(height, 2);
        int left = padBefore(width, 2);
        float[] out = new float[outHeight * outWidth * FIRST_FILTERS];
        for (int oy = 0; oy < outHeight; oy++) {
            for (int ox = 0; ox < outWidth; ox++) {
                int base = (oy * outWidth + ox) * FIRST_FILTERS;
                System.arraycopy(this.firstBias, 0, out, base, FIRST_FILTERS);
                for (int ky = 0; ky < 3; ky++) {
                    int iy = oy * 2 + ky - top;
                    if (iy < 0 || iy >= height) continue;
                    for (int kx = 0; kx < 3; kx++) {
                        int ix = ox * 2 + kx - left;
                        if (ix < 0 || ix >= width) continue;
                        float value = in[iy * width + ix];
                        int k = (ky * 3 + kx) * FIRST_FILTERS;
                        for (int o = 0; o < FIRST_FILTERS; o++) {
                            out[base + o] += value * this.firstKernel[k + o];
                        }
                    }
                }
                relu(out, base, FIRST_FILTERS);
            }
        }
        return out;
    }

    private static float[] depthwise(float[] in, int height, int width, int channels, int stride, float[] kernel, float[] bias) {
        int outHeight = outSize(height, stride);
        int outWidth = outSize(width, stride);
        int top = padBefore(height, stride);
        int left = padBefore(width, stride);
        float[] out = new float[outHeight * outWidth * channels];
        for (int oy = 0; oy < outHeight; oy++) {
            for (int ox = 0; ox < outWidth; ox++) {
                int base = (oy * outWidth + ox) * channels;
                System.arraycopy(bias, 0, out, base, channels);
                for (int ky = 0; ky < 3; ky++) {
                    int iy = oy * stride + ky - top;
                    if (iy < 0 || iy >= height) continue;
                    for (int kx = 0; kx < 3; kx++) {
                        int ix = ox * stride + kx - left;
                        if (ix < 0 || ix >= width) continue;
                        int from = (iy * width + ix) * channels;
                        int k = (ky * 3 + kx) * channels;
                        for (int c = 0; c < channels; c++) {
                            out[base + c] += in[from + c] * kernel[k + c];
                        }
                    }
                }
                relu(out, base, channels);
            }
        }
        return out;
    }

    private static float[] pointwise(float[] in, int pixels, int channels, int filters, float[] kernel, float[] bias) {
        float[] out = new float[pixels * filters];
        for (int p = 0; p < pixels; p++) {
            int base = p * filters;
            System.arraycopy(bias, 0, out, base, filters);
            int from = p * channels;
            for (int c = 0; c < channels; c++) {
                float value = in[from + c];
                // After a ReLU many inputs are zero, and skipping them is most of the speed
                if (value == 0.0f) continue;
                int row = c * filters;
                for (int o = 0; o < filters; o++) {
                    out[base + o] += value * kernel[row + o];
                }
            }
            relu(out, base, filters);
        }
        return out;
    }

    private static void relu(float[] values, int from, int count) {
        for (int i = from; i < from + count; i++) {
            if (values[i] < 0.0f) values[i] = 0.0f;
        }
    }

}
