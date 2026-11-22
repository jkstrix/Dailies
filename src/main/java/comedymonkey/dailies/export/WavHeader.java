package comedymonkey.dailies.export;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * What goes in front of 16-bit samples to make them a WAV file, for the separate tracks written beside a video
 * (specification EXP-12). A plain WAV file says its length in four bytes, so it can be no longer than 4 GiB, which
 * at 48 kHz in stereo is a little over six hours; a longer track is written as RF64 (EBU Tech 3306), the same with
 * its lengths in eight bytes, which FFmpeg and the usual editors read. No Minecraft here.
 */
public final class WavHeader {

    /** The most a plain WAV file's RIFF length can say, read as the unsigned number it is */
    static final long PLAIN_LIMIT = 0xFFFFFFFFL;

    private WavHeader() {
    }

    /**
     * @param frames     sample frames: one sample of every channel
     * @param channels   1 or 2
     * @param sampleRate samples a second of each channel
     */
    public static byte[] of(long frames, int channels, int sampleRate) {
        int blockAlign = 2 * channels;
        long data = frames * blockAlign;
        if (36 + data <= PLAIN_LIMIT) {
            ByteBuffer head = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            // The lengths are unsigned: an int holds the same four bytes, whatever Java takes them to mean
            head.put(ascii("RIFF")).putInt((int) (36 + data)).put(ascii("WAVE"));
            format(head, channels, sampleRate, blockAlign);
            head.put(ascii("data")).putInt((int) data);
            return head.array();
        }
        ByteBuffer head = ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN);
        // The four-byte lengths say "look in ds64", which holds the real ones
        head.put(ascii("RF64")).putInt(-1).put(ascii("WAVE"));
        head.put(ascii("ds64")).putInt(28).putLong(72 + data).putLong(data).putLong(frames).putInt(0);
        format(head, channels, sampleRate, blockAlign);
        head.put(ascii("data")).putInt(-1);
        return head.array();
    }

    private static void format(ByteBuffer head, int channels, int sampleRate, int blockAlign) {
        head.put(ascii("fmt ")).putInt(16).putShort((short) 1).putShort((short) channels).putInt(sampleRate)
            .putInt(sampleRate * blockAlign).putShort((short) blockAlign).putShort((short) 16);
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

}
