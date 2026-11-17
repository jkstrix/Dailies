package comedymonkey.dailies.sound;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

/**
 * How one 20 millisecond piece of voice chat is laid out inside a recording (specification REC-18). Each piece is an
 * extension record with the id {@link #ID}: a format byte, a flags byte, the speaker's id, a position where there is
 * one, and then the sound itself, either as an Opus frame or as plain 16-bit samples. Everything is 48 kHz mono.
 */
public final class VoiceFormat {

    public static final String ID = "dailies:voice";
    public static final int SAMPLE_RATE = 48000;
    /** Samples in the 20 millisecond pieces the voice chat works in */
    public static final int FRAME_SAMPLES = 960;

    /** The sound is one Opus frame */
    public static final int OPUS = 1;
    /** The sound is 16-bit samples, low byte first */
    public static final int PCM = 2;

    /** Three doubles follow the speaker's id: where the voice came from */
    public static final int HAS_POSITION = 1;
    /** The first piece after a silence: an Opus decoder starts afresh here */
    public static final int STREAM_START = 2;
    /** The recorder's own voice, as the voice chat sent it */
    public static final int OWN_VOICE = 4;
    /**
     * A proximity voice (another player, or a fixed place): a float follows the position, or the speaker's id when
     * there is none, giving how far the voice chat let it carry. Recordings before this have proximity voices with a
     * position and no range
     */
    public static final int HAS_RANGE = 8;
    /** Group chat, heard the same wherever the listener is. Before this flag, group chat was the voice without a position */
    public static final int GROUP = 16;

    /** One piece of voice read back from a recording; range is 0 when the recording does not say */
    public record Frame(int format, int flags, UUID speaker, double x, double y, double z, float range, byte[] audio) {
        public Frame(int format, int flags, UUID speaker, double x, double y, double z, byte[] audio) {
            this(format, flags, speaker, x, y, z, 0.0F, audio);
        }

        public boolean hasPosition() {
            return (this.flags & HAS_POSITION) != 0;
        }

        public boolean streamStart() {
            return (this.flags & STREAM_START) != 0;
        }
    }

    private VoiceFormat() {
    }

    public static byte[] write(int format, int flags, UUID speaker, double x, double y, double z, byte[] audio) {
        return write(format, flags & ~HAS_RANGE, speaker, x, y, z, 0.0F, audio);
    }

    /** As {@link #write(int, int, UUID, double, double, double, byte[])}, with the range kept when HAS_RANGE is set */
    public static byte[] write(int format, int flags, UUID speaker, double x, double y, double z, float range, byte[] audio) {
        boolean placed = (flags & HAS_POSITION) != 0;
        boolean ranged = (flags & HAS_RANGE) != 0;
        ByteBuffer out = ByteBuffer.allocate(2 + 16 + (placed ? 24 : 0) + (ranged ? 4 : 0) + audio.length);
        out.put((byte) format).put((byte) flags);
        out.putLong(speaker.getMostSignificantBits()).putLong(speaker.getLeastSignificantBits());
        if (placed) {
            out.putDouble(x).putDouble(y).putDouble(z);
        }
        if (ranged) {
            out.putFloat(range);
        }
        out.put(audio);
        return out.array();
    }

    public static Frame read(byte[] data, int from, int length) {
        if (length < 18) {
            throw new IllegalArgumentException("A voice record is too short");
        }
        ByteBuffer in = ByteBuffer.wrap(data, from, length);
        int format = in.get() & 0xFF;
        int flags = in.get() & 0xFF;
        UUID speaker = new UUID(in.getLong(), in.getLong());
        double x = 0.0;
        double y = 0.0;
        double z = 0.0;
        if ((flags & HAS_POSITION) != 0) {
            if (in.remaining() < 24) {
                throw new IllegalArgumentException("A voice record is too short for its position");
            }
            x = in.getDouble();
            y = in.getDouble();
            z = in.getDouble();
        }
        float range = 0.0F;
        if ((flags & HAS_RANGE) != 0) {
            if (in.remaining() < 4) {
                throw new IllegalArgumentException("A voice record is too short for its range");
            }
            range = in.getFloat();
        }
        byte[] audio = new byte[in.remaining()];
        in.get(audio);
        return new Frame(format, flags, speaker, x, y, z, range, audio);
    }

    public static byte[] pcmBytes(short[] samples) {
        ByteBuffer out = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        out.asShortBuffer().put(samples);
        return out.array();
    }

    public static short[] pcmSamples(byte[] bytes) {
        short[] samples = new short[bytes.length / 2];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples);
        return samples;
    }

}
