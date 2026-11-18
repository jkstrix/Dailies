package comedymonkey.dailies.file;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lines up two recordings of the same stretch of play (FILE-16, PLAY-18). The server's game time goes up by one each
 * tick for everyone on it, so two players' recordings agree to the tick wherever both were recording, however their
 * computers' clocks differ and whenever each pressed record. A recording is described here as runs: stretches of ticks
 * over which its game time went up one a tick, one for each segment, from the times the time index keeps for each
 * segment's first tick.
 * <p>
 * Real time is only used to be sure the two are from the same server: game times can agree by chance between two
 * worlds, real times a day apart cannot. Kept apart from the game, and reads nothing, so that it can be tested.
 */
public final class Alignment {

    /** Two recordings whose real times, where their game times meet, are further apart than this are not of one world */
    public static final long CLOCK_SLACK_MILLIS = 120_000;
    /** Recordings that share fewer ticks than this, a second, are not worth showing as points of view of each other */
    public static final int MIN_SHARED_TICKS = 20;

    /**
     * One stretch of a recording over which game time went up one a tick.
     *
     * @param startReal milliseconds since 1970 at the first tick
     */
    public record Run(int startTick, int endTick, long startGame, long startReal) {

        long endGame() {
            return this.startGame + (this.endTick - this.startTick);
        }
    }

    private final List<Run> runs;

    private Alignment(List<Run> runs) {
        this.runs = runs;
    }

    public static Alignment of(List<Run> runs) {
        List<Run> sorted = new ArrayList<>(runs);
        sorted.sort(Comparator.comparingInt(Run::startTick));
        return new Alignment(Collections.unmodifiableList(sorted));
    }

    /** The runs of a recording from its description; none for a recording made before the time index was kept */
    public static Alignment of(RecordingManifest manifest) {
        Map<Integer, RecordingManifest.SegmentTime> times = new HashMap<>();
        for (RecordingManifest.SegmentTime time : manifest.times) {
            times.put(time.segment, time);
        }
        return of(manifest.segments, times);
    }

    /** The same, with the segment times read from the file where the description lacks them */
    public static Alignment of(List<RecordingManifest.Segment> segments, List<RecordingManifest.SegmentTime> segmentTimes) {
        Map<Integer, RecordingManifest.SegmentTime> times = new HashMap<>();
        for (RecordingManifest.SegmentTime time : segmentTimes) {
            times.put(time.segment, time);
        }
        return of(segments, times);
    }

    private static Alignment of(List<RecordingManifest.Segment> segments, Map<Integer, RecordingManifest.SegmentTime> times) {
        List<Run> runs = new ArrayList<>();
        for (RecordingManifest.Segment segment : segments) {
            RecordingManifest.SegmentTime time = times.get(segment.number);
            if (time != null && segment.endTick >= segment.startTick) {
                runs.add(new Run(segment.startTick, segment.endTick, time.game, time.real));
            }
        }
        return of(runs);
    }

    public List<Run> runs() {
        return this.runs;
    }

    public boolean isEmpty() {
        return this.runs.isEmpty();
    }

    /** The run that holds a tick: the later one where two meet, as the time index has it; null outside the recording */
    private @Nullable Run runAt(int tick) {
        Run found = null;
        for (Run run : this.runs) {
            if (run.startTick() <= tick && tick <= run.endTick()) {
                found = run;
            }
        }
        return found;
    }

    /** The game time at a tick, or {@link Long#MIN_VALUE} where the recording holds none */
    public long gameAt(int tick) {
        Run run = this.runAt(tick);
        return run == null ? Long.MIN_VALUE : run.startGame() + (tick - run.startTick());
    }

    /** The real time at a tick, near enough (a tick is fifty milliseconds), or {@link Long#MIN_VALUE} */
    public long realAt(int tick) {
        Run run = this.runAt(tick);
        return run == null ? Long.MIN_VALUE : run.startReal() + (tick - run.startTick()) * 50L;
    }

    /** The tick at which this recording's game time was this, or -1 when it was not recording then */
    public int tickAtGame(long gameTime) {
        for (Run run : this.runs) {
            if (run.startGame() <= gameTime && gameTime <= run.endGame()) {
                return run.startTick() + (int) (gameTime - run.startGame());
            }
        }
        return -1;
    }

    /** The tick of the other recording that shows the same moment as this tick of this one, or -1 when it does not */
    public int map(int tick, Alignment other) {
        long game = this.gameAt(tick);
        return game == Long.MIN_VALUE ? -1 : other.tickAtGame(game);
    }

    /** {@link #map} for a moment between two ticks: the part of a tick goes across as it is */
    public double map(double time, Alignment other) {
        int whole = (int) Math.floor(time);
        int there = this.map(whole, other);
        return there < 0 ? -1 : there + (time - whole);
    }

    /**
     * The stretches of this recording's ticks that the other one also holds, in order, each as {first, last}. Where a
     * stretch of game time is in both, the ticks of it here are listed.
     */
    public List<int[]> shared(Alignment other) {
        List<int[]> stretches = new ArrayList<>();
        for (Run here : this.runs) {
            for (Run there : other.runs) {
                long from = Math.max(here.startGame(), there.startGame());
                long to = Math.min(here.endGame(), there.endGame());
                if (from <= to) {
                    stretches.add(new int[]{here.startTick() + (int) (from - here.startGame()), here.startTick() + (int) (to - here.startGame())});
                }
            }
        }
        stretches.sort(Comparator.comparingInt(stretch -> stretch[0]));
        // Neighbouring runs share their boundary tick; stretches that touch are one
        List<int[]> merged = new ArrayList<>();
        for (int[] stretch : stretches) {
            int[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && stretch[0] <= last[1] + 1) {
                last[1] = Math.max(last[1], stretch[1]);
            } else {
                merged.add(new int[]{stretch[0], stretch[1]});
            }
        }
        return merged;
    }

    /** How many of this recording's ticks the other one also holds */
    public int sharedTicks(Alignment other) {
        int total = 0;
        for (int[] stretch : this.shared(other)) {
            total += stretch[1] - stretch[0] + 1;
        }
        return total;
    }

    /**
     * Whether two recordings are points of view of the same stretch of play on the same server: they share at least
     * {@link #MIN_SHARED_TICKS} of game time, and where they do, their real times agree to within
     * {@link #CLOCK_SLACK_MILLIS}.
     */
    public boolean sameStretch(Alignment other) {
        List<int[]> shared = this.shared(other);
        if (this.sharedTicks(other) < MIN_SHARED_TICKS) {
            return false;
        }
        int[] first = shared.get(0);
        int there = this.map(first[0], other);
        long here = this.realAt(first[0]);
        long elsewhere = there < 0 ? Long.MIN_VALUE : other.realAt(there);
        return here != Long.MIN_VALUE && elsewhere != Long.MIN_VALUE && Math.abs(here - elsewhere) <= CLOCK_SLACK_MILLIS;
    }

    /**
     * Whether two recordings hold the same footage: one was made from the other by trimming or combining, or both from
     * a third. Those are not points of view of each other, only copies.
     *
     * @param sourcesA the ids of the originals the first is made of (itself when never edited)
     */
    public static boolean sameFootage(String idA, Set<String> sourcesA, String idB, Set<String> sourcesB) {
        if (idA.equals(idB) || sourcesA.contains(idB) || sourcesB.contains(idA)) {
            return true;
        }
        Set<String> common = new HashSet<>(sourcesA);
        common.retainAll(sourcesB);
        return !common.isEmpty();
    }

}
