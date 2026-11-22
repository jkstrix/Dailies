package comedymonkey.dailies.windows;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What a compat's records come to in playback, made as the recorder makes them: a snapshot every 40 ticks at the start
 * of the recorder's tick, before the tick's own records. Playback reaches a tick in two ways, and both must hold what
 * the recorder's game showed: by plain play, which steps over the snapshot of each segment it runs into
 * ({@code playback.SegmentCrossing}), and by a jump, which forgets everything and reads the segment's snapshot.
 */
class CompatWritesTest {

    private static final String COMPAT = "addons";
    private static final int SEGMENT = 40;
    private static final byte[] DYNAMO = new byte[31];
    private static final byte[] DYNAMO_TURNING = {1, 2, 3};
    private static final byte[] GOGGLES = {9};

    static {
        DYNAMO[0] = 7;
    }

    private static CompatWrites.Showing screen(byte[] state, int ticksBetween) {
        return new CompatWrites.Showing("screens/clockwork_dynamo", state, new float[]{12.0F, 30.0F}, false, ticksBetween);
    }

    private static CompatWrites.Showing overlay(byte[] state) {
        return new CompatWrites.Showing("goggles", state, null, false, 1);
    }

    /** A recording made as the recorder makes it, and played back as playback plays it */
    private static final class Made {
        final Map<Integer, List<byte[]>> snapshots = new TreeMap<>();
        final Map<Integer, List<byte[]>> ticks = new HashMap<>();
        final IntFunction<List<CompatWrites.Showing>> shown;

        /**
         * @param shown what shows during the recorder's tick that ends at each tick count, as its snapshot (when one is
         *              taken then) and its tick hook both see it: a screen opens between the game's ticks
         */
        Made(int until, IntFunction<List<CompatWrites.Showing>> shown, boolean snapshotPartWritten) {
            this.shown = shown;
            CompatWrites writes = new CompatWrites();
            for (int tick = 0; tick < until; tick++) {
                List<CompatWrites.Showing> now = shown.apply(tick + 1);
                boolean snapshot = tick % SEGMENT == 0;
                if (snapshot) {
                    List<byte[]> part = new ArrayList<>();
                    if (snapshotPartWritten) {
                        List<CompatRecord.Op> ops = writes.snapshot(COMPAT, now);
                        if (!ops.isEmpty()) {
                            part.add(CompatRecord.write(1, ops));
                        }
                    }
                    this.snapshots.put(tick, part);
                }
                writes.tickBegins(snapshot);
                List<CompatRecord.Op> ops = writes.tick(COMPAT, now);
                if (!ops.isEmpty()) {
                    this.ticks.computeIfAbsent(tick + 1, key -> new ArrayList<>()).add(CompatRecord.write(1, ops));
                }
                writes.tickEnds();
            }
        }

        /** Plain play from the start: only the first snapshot is read, every later one stepped over */
        CompatHeld played(int to) {
            CompatHeld held = new CompatHeld();
            this.snapshots.get(0).forEach(record -> held.play(record, 0, record.length));
            this.ticks(held, 0, to);
            return held;
        }

        /** A jump: everything forgotten, the segment's snapshot read, and its ticks played up to the one jumped to */
        CompatHeld jumped(int to) {
            int start = (to / SEGMENT) * SEGMENT;
            CompatHeld held = new CompatHeld();
            this.snapshots.get(start).forEach(record -> held.play(record, 0, record.length));
            this.ticks(held, start, to);
            return held;
        }

        private void ticks(CompatHeld held, int after, int to) {
            for (int tick = after + 1; tick <= to; tick++) {
                this.ticks.getOrDefault(tick, List.of()).forEach(record -> held.play(record, 0, record.length));
            }
        }

        /** Both ways of reaching each tick hold exactly what showed then */
        void holdsWhatShowed(int from, int to) {
            for (int tick = from; tick <= to; tick++) {
                if (tick % SEGMENT == 0) {
                    // A jump to a snapshot's own tick reads what the snapshot saw, a moment into the next tick
                    continue;
                }
                List<CompatWrites.Showing> now = this.shown.apply(tick);
                for (CompatHeld held : List.of(this.played(tick), this.jumped(tick))) {
                    assertEquals(now.stream().map(CompatWrites.Showing::name).sorted().toList(), held.showing().stream().sorted().toList(), "showing at tick " + tick);
                    for (CompatWrites.Showing showing : now) {
                        assertArrayEquals(showing.state(), held.held(showing.name()).state(), showing.name() + " at tick " + tick);
                        if (showing.pointer() != null) {
                            assertEquals(true, held.hasPointer(showing.name()), "pointer at tick " + tick);
                        }
                    }
                }
            }
        }
    }

    @Test
    void aScreenOpenedOnASnapshotsTickIsHeldByPlainPlayAsWellAsByAJump() {
        // The Clockwork Dynamo, opened just before the recorder's tick that takes the snapshot of tick 680
        Made made = new Made(800, tick -> tick > 680 && tick <= 715 ? List.of(screen(DYNAMO, 1)) : List.of(), true);
        assertNotNull(made.played(700).held("screens/clockwork_dynamo"), "plain play running into the segment holds the dynamo");
        assertNotNull(made.jumped(700).held("screens/clockwork_dynamo"), "a jump into the segment holds the dynamo");
        assertNull(made.played(760).held("screens/clockwork_dynamo"));
        made.holdsWhatShowed(600, 800);
    }

    @Test
    void aScreenClosedOnASnapshotsTickIsLetGoByPlainPlay() {
        Made made = new Made(800, tick -> tick > 650 && tick <= 680 ? List.of(screen(DYNAMO, 1)) : List.of(), true);
        assertNotNull(made.played(670).held("screens/clockwork_dynamo"));
        assertNull(made.played(700).held("screens/clockwork_dynamo"), "plain play lets the dynamo go once it has closed");
        made.holdsWhatShowed(600, 800);
    }

    @Test
    void aChangeHeldBackAtASnapshotReachesPlainPlayAndAJumpAlike() {
        // Written at most every 10 ticks; it changes 5 ticks after opening, just before a snapshot, and back just after
        Made made = new Made(200, tick -> tick < 75 || tick > 100 ? List.of()
            : List.of(screen(tick > 79 && tick <= 81 ? DYNAMO_TURNING : DYNAMO, 10)), true);
        for (int tick = 81; tick < 100; tick++) {
            assertArrayEquals(made.jumped(tick).held("screens/clockwork_dynamo").state(), made.played(tick).held("screens/clockwork_dynamo").state(),
                "a jump and plain play agree at tick " + tick);
        }
    }

    @Test
    void aSnapshotWithoutTheCompatsPartIsMadeUpByTheTick() {
        // The snapshot was taken but its part for this compat never written: the tick says it all again
        Made made = new Made(200, tick -> tick > 20 ? List.of(screen(DYNAMO, 1), overlay(GOGGLES)) : List.of(), false);
        assertNotNull(made.jumped(130).held("screens/clockwork_dynamo"));
        assertNotNull(made.jumped(130).held("goggles"));
    }

    @Test
    void everyTickHoldsWhatShowedWhereverTheSnapshotsFall() {
        Random random = new Random(26);
        for (int run = 0; run < 40; run++) {
            int length = 400;
            List<List<CompatWrites.Showing>> shown = new ArrayList<>();
            byte[] state = null;
            byte[] goggles = null;
            float x = 10.0F;
            for (int tick = 0; tick <= length + 1; tick++) {
                // A screen that opens, changes and closes, and an overlay alongside, each on any tick at all
                int roll = random.nextInt(10);
                if (roll == 0) {
                    state = state == null ? new byte[]{(byte) random.nextInt(4)} : null;
                } else if (roll == 1 && state != null) {
                    state = new byte[]{(byte) random.nextInt(4), 5};
                } else if (roll == 2) {
                    goggles = goggles == null ? new byte[]{(byte) random.nextInt(3)} : null;
                }
                List<CompatWrites.Showing> now = new ArrayList<>();
                if (state != null) {
                    x += random.nextInt(3);
                    now.add(new CompatWrites.Showing("screens/clockwork_dynamo", state, new float[]{x, 30.0F}, false, 1));
                }
                if (goggles != null) {
                    now.add(overlay(goggles));
                }
                shown.add(now);
            }
            Made made = new Made(length, shown::get, true);
            made.holdsWhatShowed(1, length);
        }
    }

}
