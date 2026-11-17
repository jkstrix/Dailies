package comedymonkey.dailies.moments.voice;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Notices laughter and shouting without understanding either: each speaker's loudness is followed against that
 * speaker's own rolling baseline, and a stretch well above it is a burst. Two or more speakers bursting within a few
 * seconds of each other make one stronger burst.
 * <p>
 * Input is the same 48 kHz mono 16-bit audio the segmenter gets, in frames of any length. Has no Minecraft or voice
 * chat dependencies: timestamps are passed in, so tests can feed audio with their own clock. The "ref" object is
 * opaque; the one given with the first loud frame is handed back with the burst.
 */
public class VoiceBurstDetector {

    /** How far above their own baseline a speaker has to be */
    public static final double THRESHOLD_DB = 12.0;
    /** How long they have to stay there */
    public static final long MIN_LOUD_MILLIS = 1500;
    /** Other speakers who get there within this long of the first join the same burst */
    public static final long JOIN_WINDOW_MILLIS = 3000;
    /** At most one burst is reported in this long */
    public static final long MIN_GAP_MILLIS = 20_000;
    public static final double BASE_WEIGHT = 30.0;
    public static final double WEIGHT_PER_EXTRA_SPEAKER = 10.0;

    /** Quieter than this is silence (an open microphone, a held push-to-talk key) and says nothing about a voice */
    static final double FLOOR_DB = -55.0;
    /** How much of a voice has to be heard before its baseline is trusted */
    static final long WARM_UP_MILLIS = 2000;
    /** How quickly the baseline follows the voice. Slow enough that a burst stands out, quick enough to follow a mood */
    static final double BASELINE_SECONDS = 20.0;
    /** Loud audio moves the baseline this much more slowly, so a burst does not raise the bar against itself */
    static final double LOUD_SLOWDOWN = 4.0;
    /** Laughter comes in pulses: a dip shorter than this does not end a loud stretch */
    static final long DIP_MILLIS = 300;

    private static final int SAMPLE_RATE = 48000;

    /**
     * A burst as it stands. The same id is reported again, with more speakers, when someone joins in.
     *
     * @param startMillis when the first speaker became loud
     * @param peakDb how far above baseline the loudest speaker got
     */
    public record Burst(long id, long startMillis, Object startRef, List<UUID> speakers, double peakDb, boolean isNew) {
        public double weight() {
            return weightFor(this.speakers.size());
        }
    }

    public static double weightFor(int speakers) {
        return BASE_WEIGHT + WEIGHT_PER_EXTRA_SPEAKER * Math.max(0, speakers - 1);
    }

    private static class Voice {
        double baselineDb;
        double heardMillis;
        long loudStart = -1;
        long lastLoudEnd;
        Object loudRef;
        boolean counted;
        double peakDb;
    }

    private final Map<UUID, Voice> voices = new HashMap<>();
    private long nextId = 1;
    private long currentId = 0;
    private long currentAt = Long.MIN_VALUE;
    private long currentStart;
    private Object currentRef;
    private double currentPeak;
    private final List<UUID> currentSpeakers = new ArrayList<>();

    /** Loudness of a frame in dB relative to full scale; silence is negative infinity */
    public static double levelDb(short[] samples) {
        if (samples.length == 0) {
            return Double.NEGATIVE_INFINITY;
        }
        double sum = 0.0;
        for (short sample : samples) {
            sum += (double) sample * sample;
        }
        double rms = Math.sqrt(sum / samples.length);
        return rms <= 0.0 ? Double.NEGATIVE_INFINITY : 20.0 * Math.log10(rms / 32768.0);
    }

    /**
     * @param timeMillis when the frame was captured (its start)
     * @return the burst this frame started or added a speaker to, otherwise null
     */
    public synchronized @Nullable Burst feed(UUID speaker, short[] samples48k, long timeMillis, Object ref) {
        if (samples48k.length == 0) {
            return null;
        }
        Voice voice = this.voices.computeIfAbsent(speaker, key -> new Voice());
        double frameMillis = samples48k.length * 1000.0 / SAMPLE_RATE;
        double level = levelDb(samples48k);

        if (voice.loudStart >= 0 && timeMillis - voice.lastLoudEnd > DIP_MILLIS) {
            voice.loudStart = -1;
        }
        if (level < FLOOR_DB) {
            return null;
        }
        if (voice.heardMillis < WARM_UP_MILLIS) {
            // The first couple of seconds are simply averaged; nothing can be a burst until there is something to compare with
            voice.baselineDb = (voice.baselineDb * voice.heardMillis + level * frameMillis) / (voice.heardMillis + frameMillis);
            voice.heardMillis += frameMillis;
            return null;
        }

        double above = level - voice.baselineDb;
        boolean loud = above >= THRESHOLD_DB;
        double seconds = BASELINE_SECONDS * (loud ? LOUD_SLOWDOWN : 1.0);
        voice.baselineDb += (level - voice.baselineDb) * (1.0 - Math.exp(-frameMillis / 1000.0 / seconds));
        if (!loud) {
            return null;
        }

        if (voice.loudStart < 0) {
            voice.loudStart = timeMillis;
            voice.loudRef = ref;
            voice.counted = false;
            voice.peakDb = above;
        }
        voice.peakDb = Math.max(voice.peakDb, above);
        voice.lastLoudEnd = timeMillis + Math.round(frameMillis);
        if (voice.counted || voice.lastLoudEnd - voice.loudStart < MIN_LOUD_MILLIS) {
            return null;
        }
        // One loud stretch counts once, however long it goes on
        voice.counted = true;
        return this.count(speaker, voice, voice.lastLoudEnd);
    }

    private @Nullable Burst count(UUID speaker, Voice voice, long now) {
        boolean open = this.currentId != 0 && now - this.currentAt <= JOIN_WINDOW_MILLIS;
        if (open) {
            if (this.currentSpeakers.contains(speaker)) {
                return null;
            }
            this.currentSpeakers.add(speaker);
            this.currentPeak = Math.max(this.currentPeak, voice.peakDb);
            return new Burst(this.currentId, this.currentStart, this.currentRef, List.copyOf(this.currentSpeakers), this.currentPeak, false);
        }
        if (this.currentId != 0 && now - this.currentAt < MIN_GAP_MILLIS) {
            return null;
        }
        this.currentId = this.nextId++;
        this.currentAt = now;
        this.currentStart = voice.loudStart;
        this.currentRef = voice.loudRef;
        this.currentPeak = voice.peakDb;
        this.currentSpeakers.clear();
        this.currentSpeakers.add(speaker);
        return new Burst(this.currentId, this.currentStart, this.currentRef, List.copyOf(this.currentSpeakers), this.currentPeak, true);
    }

}
