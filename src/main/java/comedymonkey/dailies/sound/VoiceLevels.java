package comedymonkey.dailies.sound;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * How loud a piece of recorded voice chat is, wherever it is heard: watched in playback, mixed into an export, or
 * kept on a speaker's own track (specification PLAY-16, EXP-12). The owner's rule: voice takes distance into account
 * but is never placed left or right, and the recorder's own voice does not even take distance into account.
 * <ul>
 * <li>Proximity voice (another player nearby, or a voice from a fixed place): full volume within
 * {@link #fullRadius} of the camera, then falling in a straight line to silence at the voice chat's range, which is
 * recorded with each piece; older recordings, which lack it, use {@link #DEFAULT_RANGE}.</li>
 * <li>Group chat: full volume wherever the camera is, as Simple Voice Chat plays it.</li>
 * <li>The recorder's own voice: full volume, always.</li>
 * </ul>
 * Every kind is the same in both ears. Plain Java, so the rule can be tested without the game.
 */
public final class VoiceLevels {

    /** Simple Voice Chat's usual range, for recordings made before the range was kept with each piece */
    public static final double DEFAULT_RANGE = 48.0;
    /** How near a proximity voice is heard at full volume, at most */
    public static final double FULL_RADIUS = 4.0;

    public enum Kind {
        PROXIMITY, GROUP, OWN
    }

    private VoiceLevels() {
    }

    /**
     * What kind of voice a piece is, from its flags. Recordings made before the kind was written still tell: the
     * recorder's own voice was always flagged, other players' and fixed places' voices carried a position, and group
     * chat had none.
     */
    public static Kind kind(int flags) {
        if ((flags & VoiceFormat.OWN_VOICE) != 0) {
            return Kind.OWN;
        }
        if ((flags & VoiceFormat.GROUP) != 0) {
            return Kind.GROUP;
        }
        if ((flags & (VoiceFormat.HAS_POSITION | VoiceFormat.HAS_RANGE)) != 0) {
            return Kind.PROXIMITY;
        }
        return Kind.GROUP;
    }

    /** Full volume this near: four blocks, or a quarter of a range shorter than sixteen (a whisper) */
    public static double fullRadius(double range) {
        return Math.min(FULL_RADIUS, range * 0.25);
    }

    /** A proximity voice's loudness at this distance from the camera, 0 to 1: linear from the full radius to the range */
    public static double loudness(double distance, double range) {
        if (!(range > 0.0) || Double.isNaN(distance)) {
            return 0.0;
        }
        double full = fullRadius(range);
        if (distance <= full) {
            return 1.0;
        }
        if (distance >= range) {
            return 0.0;
        }
        return (range - distance) / (range - full);
    }

    /**
     * The loudness of a piece of voice, 0 to 1, before the player's volume settings.
     *
     * @param distance from the camera to where the speaker was, or a negative number when that is not known (the
     *                 speaker could not be found), which is heard at full volume rather than lost
     */
    public static double gain(Kind kind, double distance, double range) {
        if (kind != Kind.PROXIMITY || distance < 0.0) {
            return 1.0;
        }
        return loudness(distance, range > 0.0 ? range : DEFAULT_RANGE);
    }

    /** The gain of a piece as read from a recording, heard from a camera at this place */
    public static double gain(VoiceFormat.Frame frame, double cameraX, double cameraY, double cameraZ) {
        Kind kind = kind(frame.flags());
        if (kind != Kind.PROXIMITY || !frame.hasPosition()) {
            return 1.0;
        }
        double dx = frame.x() - cameraX;
        double dy = frame.y() - cameraY;
        double dz = frame.z() - cameraZ;
        return gain(kind, Math.sqrt(dx * dx + dy * dy + dz * dz), frame.range());
    }

    /** Mono samples as 16-bit stereo, low byte first, at this gain and the same in both ears */
    public static byte[] stereo(short[] mono, float gain) {
        byte[] bytes = new byte[mono.length * 4];
        ByteBuffer out = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (short sample : mono) {
            short placed = clamp(Math.round(sample * gain));
            out.putShort(placed).putShort(placed);
        }
        return bytes;
    }

    /**
     * Adds one track's stereo frames into a running total and copies them, as stereo or folded to mono, into
     * {@code to}: how the separate tracks of an export are summed into its mix, so the tracks always add up to it.
     */
    public static void accumulate(ByteBuffer from, ByteBuffer to, int[] total, int frames, int channels) {
        for (int frame = 0; frame < frames; frame++) {
            short left = from.getShort();
            short right = from.getShort();
            if (channels == 2) {
                to.putShort(left).putShort(right);
                total[frame * 2] += left;
                total[frame * 2 + 1] += right;
            } else {
                short both = (short) Math.floorDiv(left + right + 1, 2);
                to.putShort(both);
                total[frame] += both;
            }
        }
    }

    public static short clamp(long value) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
    }

}
