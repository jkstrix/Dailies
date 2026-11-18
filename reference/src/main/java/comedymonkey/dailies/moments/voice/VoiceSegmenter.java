package comedymonkey.dailies.moments.voice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cuts each speaker's stream of voice frames into utterances. A segment starts with the first frame and ends after
 * a gap without frames (voice chat only sends frames while someone talks) or when it reaches the maximum length.
 * <p>
 * Input is 48 kHz mono 16-bit PCM in frames of any length (Simple Voice Chat uses 20 ms / 960 samples); segments
 * come out as 16 kHz mono, which is what Whisper works with. Has no Minecraft or voice chat dependencies: timestamps
 * are passed in, so tests can feed audio with their own clock. The "ref" objects are opaque, the ones given with
 * the first and last frame of a segment are handed back with it.
 */
public class VoiceSegmenter {

    public static final int INPUT_SAMPLE_RATE = 48000;
    public static final int OUTPUT_SAMPLE_RATE = 16000;
    private static final int DECIMATION = INPUT_SAMPLE_RATE / OUTPUT_SAMPLE_RATE;

    public static final long MAX_SEGMENT_MILLIS = 30_000;
    public static final long MIN_SEGMENT_MILLIS = 400;

    public record Segment(UUID speaker, long startMillis, long endMillis, short[] pcm16k, Object startRef, Object endRef) {
        public double durationSeconds() {
            return this.pcm16k.length / (double) OUTPUT_SAMPLE_RATE;
        }
    }

    private static class Open {
        long start;
        long lastFrameEnd;
        Object startRef;
        Object lastRef;
        short[] samples = new short[OUTPUT_SAMPLE_RATE * 4];
        int length = 0;
        // Input samples that did not fill a whole output sample yet
        int carrySum = 0;
        int carryCount = 0;
    }

    private final Map<UUID, Open> open = new HashMap<>();

    /**
     * @param timeMillis when the frame was captured (its start)
     * @return the segments this frame completed (only when a segment hit the maximum length)
     */
    public synchronized List<Segment> feed(UUID speaker, short[] samples48k, long timeMillis, Object ref, long gapMillis) {
        List<Segment> finished = new ArrayList<>(0);
        if (samples48k.length == 0) {
            return finished;
        }

        Open segment = this.open.get(speaker);
        if (segment != null && timeMillis - segment.lastFrameEnd >= gapMillis) {
            // flush() normally gets there first, this is for callers that only feed
            this.open.remove(speaker);
            this.finish(speaker, segment, finished);
            segment = null;
        }
        if (segment == null) {
            segment = new Open();
            segment.start = timeMillis;
            segment.startRef = ref;
            this.open.put(speaker, segment);
        }

        // 48 kHz -> 16 kHz by averaging groups of three samples, which is also a (crude) low-pass filter
        int needed = segment.length + samples48k.length / DECIMATION + 1;
        if (needed > segment.samples.length) {
            short[] grown = new short[Math.max(needed, segment.samples.length * 2)];
            System.arraycopy(segment.samples, 0, grown, 0, segment.length);
            segment.samples = grown;
        }
        for (short sample : samples48k) {
            segment.carrySum += sample;
            segment.carryCount += 1;
            if (segment.carryCount == DECIMATION) {
                segment.samples[segment.length++] = (short) (segment.carrySum / DECIMATION);
                segment.carrySum = 0;
                segment.carryCount = 0;
            }
        }
        segment.lastFrameEnd = timeMillis + samples48k.length * 1000L / INPUT_SAMPLE_RATE;
        segment.lastRef = ref;

        if (segment.length >= MAX_SEGMENT_MILLIS * OUTPUT_SAMPLE_RATE / 1000) {
            this.open.remove(speaker);
            this.finish(speaker, segment, finished);
        }
        return finished;
    }

    /** End the segments of speakers that have been silent for the gap. Call regularly */
    public synchronized List<Segment> flush(long nowMillis, long gapMillis) {
        List<Segment> finished = new ArrayList<>(0);
        Iterator<Map.Entry<UUID, Open>> iterator = this.open.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Open> entry = iterator.next();
            if (nowMillis - entry.getValue().lastFrameEnd >= gapMillis) {
                iterator.remove();
                this.finish(entry.getKey(), entry.getValue(), finished);
            }
        }
        return finished;
    }

    /** End every open segment, eg. when the session ends */
    public synchronized List<Segment> flushAll() {
        return this.flush(Long.MAX_VALUE, 0);
    }

    private void finish(UUID speaker, Open segment, List<Segment> into) {
        long durationMillis = segment.length * 1000L / OUTPUT_SAMPLE_RATE;
        if (durationMillis < MIN_SEGMENT_MILLIS) {
            return;
        }
        short[] pcm = new short[segment.length];
        System.arraycopy(segment.samples, 0, pcm, 0, segment.length);
        // The end comes from the amount of audio rather than the clock, so start + duration always matches the WAV
        into.add(new Segment(speaker, segment.start, segment.start + durationMillis, pcm, segment.startRef, segment.lastRef));
    }

}
