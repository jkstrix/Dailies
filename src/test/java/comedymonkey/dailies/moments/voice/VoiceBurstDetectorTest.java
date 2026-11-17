package comedymonkey.dailies.moments.voice;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Laughter and shouting found by loudness alone (plan 2.9), driven by made-up audio on a made-up clock */
class VoiceBurstDetectorTest {

    private static final int FRAME = 960; // 20 ms at 48 kHz, as the voice chat sends it
    private static final double QUIET = 600.0;
    private static final double LOUD = 9000.0; // about 23 dB above QUIET

    private final VoiceBurstDetector detector = new VoiceBurstDetector();
    private final List<VoiceBurstDetector.Burst> reported = new ArrayList<>();
    private long sample = 0;

    private static short[] tone(long firstSample, double amplitude) {
        short[] frame = new short[FRAME];
        for (int i = 0; i < FRAME; i++) {
            frame[i] = (short) (Math.sin((firstSample + i) * 2.0 * Math.PI * 220.0 / 48000.0) * amplitude);
        }
        return frame;
    }

    /** Feeds a stretch of sound from one speaker, starting at a time on the test's clock */
    private long speak(UUID speaker, long fromMillis, long forMillis, double amplitude) {
        long time = fromMillis;
        for (; time < fromMillis + forMillis; time += 20) {
            VoiceBurstDetector.Burst burst = this.detector.feed(speaker, tone(this.sample, amplitude), time, "ref@" + time);
            this.sample += FRAME;
            if (burst != null) {
                this.reported.add(burst);
            }
        }
        return time;
    }

    @Test
    void quietSpeechIsNotABurst() {
        UUID speaker = UUID.randomUUID();
        this.speak(speaker, 0, 60_000, QUIET);
        assertTrue(this.reported.isEmpty());
    }

    @Test
    void aLoudStretchIsOneBurstWithTheBaseWeight() {
        UUID speaker = UUID.randomUUID();
        long time = this.speak(speaker, 0, 5000, QUIET);
        this.speak(speaker, time, 4000, LOUD);
        assertEquals(1, this.reported.size(), "one loud stretch is one burst, however long it lasts");
        VoiceBurstDetector.Burst burst = this.reported.get(0);
        assertTrue(burst.isNew());
        assertEquals(List.of(speaker), burst.speakers());
        assertEquals(30.0, burst.weight());
        assertEquals(5000, burst.startMillis(), "the burst is dated to when it began, not to when it was noticed");
        assertEquals("ref@5000", burst.startRef());
        assertTrue(burst.peakDb() > 20.0 && burst.peakDb() < 26.0, "peak " + burst.peakDb());
    }

    @Test
    void aShortShoutIsNotABurst() {
        UUID speaker = UUID.randomUUID();
        long time = this.speak(speaker, 0, 5000, QUIET);
        time = this.speak(speaker, time, 1200, LOUD);
        this.speak(speaker, time, 3000, QUIET);
        assertTrue(this.reported.isEmpty(), "1.2 s is under the 1.5 s a burst needs");
    }

    @Test
    void nothingCanBeABurstBeforeTheVoiceIsKnown() {
        UUID speaker = UUID.randomUUID();
        this.speak(speaker, 0, 1900, LOUD);
        assertTrue(this.reported.isEmpty());
    }

    @Test
    void laughterInPulsesStillCounts() {
        UUID speaker = UUID.randomUUID();
        long time = this.speak(speaker, 0, 5000, QUIET);
        for (int pulse = 0; pulse < 6; pulse++) {
            time = this.speak(speaker, time, 300, LOUD);
            time = this.speak(speaker, time, 160, QUIET);
        }
        assertEquals(1, this.reported.size());
    }

    @Test
    void twoSpeakersWithinThreeSecondsAreOneStrongerBurst() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        this.speak(first, 0, 5000, QUIET);
        this.speak(second, 0, 5000, QUIET);
        this.speak(first, 5000, 2000, LOUD);
        this.speak(second, 6000, 2000, LOUD);
        assertEquals(2, this.reported.size(), "the burst is reported, then reported again when the second voice joins");
        VoiceBurstDetector.Burst grown = this.reported.get(1);
        assertFalse(grown.isNew());
        assertEquals(this.reported.get(0).id(), grown.id());
        assertEquals(List.of(first, second), grown.speakers());
        assertEquals(40.0, grown.weight());
        assertEquals(50.0, VoiceBurstDetector.weightFor(3));
    }

    @Test
    void atMostOneBurstInTwentySeconds() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        this.speak(first, 0, 5000, QUIET);
        this.speak(second, 0, 5000, QUIET);
        this.speak(first, 5000, 2000, LOUD);
        // Too late to join the first burst, too soon for one of its own
        this.speak(second, 12_000, 2000, LOUD);
        assertEquals(1, this.reported.size());
        // More than twenty seconds after the first: a burst again
        this.speak(second, 20_000, 6000, QUIET);
        this.speak(second, 27_000, 2000, LOUD);
        assertEquals(2, this.reported.size());
        assertTrue(this.reported.get(1).isNew());
    }

    @Test
    void aSlowlyRisingVoiceNeverTriggers() {
        UUID speaker = UUID.randomUUID();
        // From quiet to as loud as a burst over two minutes: the baseline follows, so there is never a jump to notice
        long time = this.speak(speaker, 0, 3000, QUIET);
        double decibels = 20.0 * Math.log10(LOUD / QUIET);
        for (int step = 0; step < 6000; step++) {
            double amplitude = QUIET * Math.pow(10.0, decibels * step / 6000.0 / 20.0);
            time = this.speak(speaker, time, 20, amplitude);
        }
        this.speak(speaker, time, 5000, LOUD);
        assertTrue(this.reported.isEmpty(), "a slow rise was taken for a burst: " + this.reported);
    }

    @Test
    void silenceTeachesNothing() {
        UUID speaker = UUID.randomUUID();
        // A held push-to-talk key sends near-silence; it must not drag the baseline down so ordinary talk looks loud
        long time = this.speak(speaker, 0, 5000, QUIET);
        time = this.speak(speaker, time, 30_000, 4.0);
        this.speak(speaker, time, 5000, QUIET);
        assertTrue(this.reported.isEmpty());
        assertNotNull(this.detector);
    }

    @Test
    void levelsAreInDecibels() {
        assertEquals(Double.NEGATIVE_INFINITY, VoiceBurstDetector.levelDb(new short[FRAME]));
        // A full-scale sine is 3 dB under full scale (a frame holds a little over four waves, hence the slack)
        assertEquals(-3.01, VoiceBurstDetector.levelDb(tone(0, 32767.0)), 0.5);
        short[] square = new short[FRAME];
        java.util.Arrays.fill(square, (short) 16384);
        assertEquals(-6.02, VoiceBurstDetector.levelDb(square), 0.01);
    }

}
