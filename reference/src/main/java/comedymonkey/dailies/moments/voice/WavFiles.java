package comedymonkey.dailies.moments.voice;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Minimal reading and writing of 16-bit PCM WAV files.
 */
public final class WavFiles {

    private WavFiles() {
    }

    public record Pcm(int sampleRate, short[] samples) {}

    public static byte[] encode(short[] monoSamples, int sampleRate) {
        int dataBytes = monoSamples.length * 2;
        ByteBuffer buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[]{'R', 'I', 'F', 'F'});
        buffer.putInt(36 + dataBytes);
        buffer.put(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
        buffer.putInt(16);
        buffer.putShort((short) 1); // PCM
        buffer.putShort((short) 1); // mono
        buffer.putInt(sampleRate);
        buffer.putInt(sampleRate * 2);
        buffer.putShort((short) 2);
        buffer.putShort((short) 16);
        buffer.put(new byte[]{'d', 'a', 't', 'a'});
        buffer.putInt(dataBytes);
        for (short sample : monoSamples) {
            buffer.putShort(sample);
        }
        return buffer.array();
    }

    /** Read a 16-bit PCM WAV file, mixing multiple channels down to mono */
    public static Pcm read(Path file) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.remaining() < 12 || buffer.getInt(0) != 0x46464952 || buffer.getInt(8) != 0x45564157) {
            throw new IOException("Not a WAV file: " + file);
        }
        buffer.position(12);

        int channels = 0;
        int sampleRate = 0;
        int bitsPerSample = 0;
        while (buffer.remaining() >= 8) {
            int chunkId = buffer.getInt();
            int chunkSize = buffer.getInt();
            int chunkStart = buffer.position();
            if (chunkId == 0x20746d66) { // "fmt "
                int format = buffer.getShort() & 0xFFFF;
                channels = buffer.getShort() & 0xFFFF;
                sampleRate = buffer.getInt();
                buffer.getInt();
                buffer.getShort();
                bitsPerSample = buffer.getShort() & 0xFFFF;
                if ((format != 1 && format != 0xFFFE) || bitsPerSample != 16 || channels < 1) {
                    throw new IOException("Only 16-bit PCM WAV files are supported: " + file);
                }
            } else if (chunkId == 0x61746164) { // "data"
                if (channels == 0) {
                    throw new IOException("WAV data chunk before the format chunk: " + file);
                }
                int frames = Math.min(chunkSize, buffer.remaining()) / (2 * channels);
                short[] samples = new short[frames];
                for (int i = 0; i < frames; i++) {
                    int sum = 0;
                    for (int channel = 0; channel < channels; channel++) {
                        sum += buffer.getShort();
                    }
                    samples[i] = (short) (sum / channels);
                }
                return new Pcm(sampleRate, samples);
            }
            int next = chunkStart + chunkSize + (chunkSize & 1);
            if (next < chunkStart || next > buffer.limit()) {
                break;
            }
            buffer.position(next);
        }
        throw new IOException("No audio data in " + file);
    }

    /** Linear-interpolation resampling. Good enough for speech */
    public static short[] resample(short[] samples, int fromRate, int toRate) {
        if (fromRate == toRate || samples.length == 0) {
            return samples;
        }
        int length = (int) ((long) samples.length * toRate / fromRate);
        short[] out = new short[length];
        double step = fromRate / (double) toRate;
        for (int i = 0; i < length; i++) {
            double position = i * step;
            int index = (int) position;
            double fraction = position - index;
            int a = samples[Math.min(index, samples.length - 1)];
            int b = samples[Math.min(index + 1, samples.length - 1)];
            out[i] = (short) Math.round(a + (b - a) * fraction);
        }
        return out;
    }

}
