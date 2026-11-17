package comedymonkey.dailies.sound;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The owner's rule for recorded voice chat: distance but no panning, and none of either for group chat or one's own voice */
class VoiceLevelsTest {

    private static final UUID SPEAKER = UUID.fromString("0a1b2c3d-0000-4000-8000-000000000002");

    @Test
    void proximityFadesLinearlyFromTheFullRadiusToTheRange() {
        assertEquals(1.0, VoiceLevels.loudness(0.0, 48.0));
        assertEquals(1.0, VoiceLevels.loudness(4.0, 48.0));
        assertEquals(0.5, VoiceLevels.loudness(26.0, 48.0), 1e-9);
        assertEquals(0.0, VoiceLevels.loudness(48.0, 48.0));
        assertEquals(0.0, VoiceLevels.loudness(200.0, 48.0));
        assertTrue(VoiceLevels.loudness(10.0, 48.0) > VoiceLevels.loudness(20.0, 48.0));
        // A whisper's short range keeps a smaller full radius
        assertEquals(2.0, VoiceLevels.fullRadius(8.0));
        assertEquals(0.0, VoiceLevels.loudness(8.0, 8.0));
    }

    @Test
    void groupChatAndOwnVoiceIgnoreDistance() {
        assertEquals(1.0, VoiceLevels.gain(VoiceLevels.Kind.GROUP, 500.0, 48.0));
        assertEquals(1.0, VoiceLevels.gain(VoiceLevels.Kind.OWN, 500.0, 48.0));
        assertEquals(0.0, VoiceLevels.gain(VoiceLevels.Kind.PROXIMITY, 500.0, 48.0));
        // Unknown range: the voice chat's usual 48 blocks
        assertEquals(VoiceLevels.loudness(30.0, 48.0), VoiceLevels.gain(VoiceLevels.Kind.PROXIMITY, 30.0, 0.0));
        // Unknown distance (speaker not found): heard rather than lost
        assertEquals(1.0, VoiceLevels.gain(VoiceLevels.Kind.PROXIMITY, -1.0, 48.0));
    }

    @Test
    void kindsAreReadFromNewAndOldRecordings() {
        assertEquals(VoiceLevels.Kind.OWN, VoiceLevels.kind(VoiceFormat.OWN_VOICE | VoiceFormat.HAS_POSITION));
        assertEquals(VoiceLevels.Kind.GROUP, VoiceLevels.kind(VoiceFormat.GROUP));
        assertEquals(VoiceLevels.Kind.PROXIMITY, VoiceLevels.kind(VoiceFormat.HAS_POSITION | VoiceFormat.HAS_RANGE));
        // Older recordings: a position meant another player or a fixed place, none meant group chat
        assertEquals(VoiceLevels.Kind.PROXIMITY, VoiceLevels.kind(VoiceFormat.HAS_POSITION));
        assertEquals(VoiceLevels.Kind.GROUP, VoiceLevels.kind(0));
    }

    @Test
    void theRangeAndKindSurviveTheRecordingFormat() {
        byte[] audio = VoiceFormat.pcmBytes(new short[]{1, 2, 3});
        byte[] stored = VoiceFormat.write(VoiceFormat.PCM, VoiceFormat.HAS_POSITION | VoiceFormat.HAS_RANGE, SPEAKER, 10.0, 64.0, 0.0, 24.0F, audio);
        VoiceFormat.Frame frame = VoiceFormat.read(stored, 0, stored.length);
        assertEquals(24.0F, frame.range());
        assertArrayEquals(audio, frame.audio());
        // Camera 12 blocks away in a 24-block range: a whisper, half way between its full radius (4) and its end
        assertEquals((24.0 - 12.0) / (24.0 - 4.0), VoiceLevels.gain(frame, -2.0, 64.0, 0.0), 1e-9);
        // An old record, written without a range, reads as before
        byte[] old = VoiceFormat.write(VoiceFormat.PCM, VoiceFormat.HAS_POSITION, SPEAKER, 30.0, 0.0, 0.0, audio);
        VoiceFormat.Frame oldFrame = VoiceFormat.read(old, 0, old.length);
        assertEquals(0.0F, oldFrame.range());
        assertArrayEquals(audio, oldFrame.audio());
        assertEquals(VoiceLevels.loudness(30.0, 48.0), VoiceLevels.gain(oldFrame, 0.0, 0.0, 0.0), 1e-9);
    }

    @Test
    void noVoiceIsPanned() {
        short[] mono = tone(960, 9000);
        for (float gain : new float[]{1.0F, 0.37F, 0.0F}) {
            ByteBuffer stereo = ByteBuffer.wrap(VoiceLevels.stereo(mono, gain)).order(ByteOrder.LITTLE_ENDIAN);
            for (short sample : mono) {
                short left = stereo.getShort();
                short right = stereo.getShort();
                assertEquals(left, right);
                assertEquals(Math.round(sample * gain), left);
            }
        }
    }

    @Test
    void speakersTracksAddUpToTheMixWithDistanceApplied() {
        int frames = 960;
        short[] near = tone(frames, 9000);
        short[] far = tone(frames, 8000);
        short[] group = tone(frames, 7000);
        float nearGain = (float) VoiceLevels.gain(VoiceLevels.Kind.PROXIMITY, 10.0, 48.0);
        float farGain = (float) VoiceLevels.gain(VoiceLevels.Kind.PROXIMITY, 40.0, 48.0);
        float groupGain = (float) VoiceLevels.gain(VoiceLevels.Kind.GROUP, 300.0, 48.0);
        assertTrue(nearGain > farGain && farGain > 0.0F && groupGain == 1.0F);
        byte[][] tracks = {VoiceLevels.stereo(near, nearGain), VoiceLevels.stereo(far, farGain), VoiceLevels.stereo(group, groupGain)};

        for (int channels = 1; channels <= 2; channels++) {
            int[] total = new int[frames * channels];
            short[][] kept = new short[tracks.length][];
            for (int t = 0; t < tracks.length; t++) {
                byte[] one = new byte[frames * 2 * channels];
                VoiceLevels.accumulate(ByteBuffer.wrap(tracks[t]).order(ByteOrder.LITTLE_ENDIAN), ByteBuffer.wrap(one).order(ByteOrder.LITTLE_ENDIAN), total, frames, channels);
                kept[t] = new short[frames * channels];
                ByteBuffer.wrap(one).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(kept[t]);
            }
            for (int i = 0; i < frames * channels; i++) {
                int frame = i / channels;
                short mix = VoiceLevels.clamp(total[i]);
                // Each track carries its distance gain, and the mix is their sum
                assertEquals(Math.round(near[frame] * nearGain), kept[0][i]);
                assertEquals(Math.round(far[frame] * farGain), kept[1][i]);
                assertEquals(group[frame], kept[2][i]);
                assertEquals(VoiceLevels.clamp((long) kept[0][i] + kept[1][i] + kept[2][i]), mix);
                if (channels == 2 && i % 2 == 1) {
                    assertEquals(VoiceLevels.clamp(total[i - 1]), mix);
                }
            }
        }
    }

    private static short[] tone(int length, int amplitude) {
        short[] samples = new short[length];
        for (int i = 0; i < length; i++) {
            samples[i] = (short) Math.round(amplitude * Math.sin(2.0 * Math.PI * 330.0 * i / VoiceFormat.SAMPLE_RATE));
        }
        return samples;
    }

}
