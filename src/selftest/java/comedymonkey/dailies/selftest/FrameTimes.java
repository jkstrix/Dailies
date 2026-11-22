package comedymonkey.dailies.selftest;

import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.Arrays;

/**
 * Measures how long the game's frames take, so the scripted run can show what recording costs.
 */
final class FrameTimes {

    record Result(double average, double worst, double p99, int frames) {
        @Override
        public String toString() {
            return String.format("%.2f ms average, %.2f ms 99th percentile, %.2f ms worst over %d frames", this.average, this.p99, this.worst, this.frames);
        }
    }

    private static final double[] SAMPLES = new double[200_000];
    private static volatile int count = -1;
    private static long last = 0;
    private static boolean registered = false;

    private FrameTimes() {
    }

    static synchronized void begin() {
        if (!registered) {
            registered = true;
            NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Post event) -> {
                long now = System.nanoTime();
                int index = count;
                if (index >= 0 && index < SAMPLES.length) {
                    if (last != 0) {
                        SAMPLES[index] = (now - last) / 1_000_000.0;
                        count = index + 1;
                    }
                }
                last = now;
            });
        }
        last = 0;
        count = 0;
    }

    static synchronized Result end() {
        int frames = Math.max(0, count);
        count = -1;
        if (frames == 0) {
            return new Result(0, 0, 0, 0);
        }
        double[] sorted = Arrays.copyOf(SAMPLES, frames);
        Arrays.sort(sorted);
        return new Result(Arrays.stream(sorted).average().orElse(0), sorted[frames - 1], sorted[(int) (frames * 0.99)], frames);
    }

    static Result measure(SelfTest test, int ticks) throws Exception {
        begin();
        test.waitTicks(ticks);
        return end();
    }

}
