package comedymonkey.dailies.edit;

import java.util.Arrays;
import java.util.List;

/**
 * Converts between seconds of finished video and ticks of the recording for one scene and one range of ticks, taking
 * the scene's speed, timelapse and freeze keyframes into account (specification EXP-2). Export asks it which tick
 * frame n shows; the timeline asks it how long a stretch lasts.
 * <p>
 * The rules:
 * <ul>
 * <li>Speed: the blended value of the speed track that applies, in ticks per second; 20 where none applies.</li>
 * <li>Timelapse: the keyframes of a timelapse track pair up in order, and the ticks inside a pair share the pair's
 * real seconds evenly, whatever the speed tracks say there. Where enabled timelapse tracks overlap, the upper wins.</li>
 * <li>Freeze: on the keyframe's tick the recording holds for the stated seconds, in addition to everything else.
 * Freezes on all enabled freeze tracks count; of two on the same tick, the upper track's.</li>
 * </ul>
 * The time each whole tick takes is worked out once, when the map is built, and time passes evenly within a tick. So
 * the map never runs backwards, gives the same answer for the same input every time, and does not follow later
 * changes to the scene: build a new one after an edit.
 */
public final class TimeMap {

    /** The game's own rate, used where no speed track applies */
    public static final double DEFAULT_TICKS_PER_SECOND = 20.0;

    private final int startTick;
    private final int endTick;
    /** moving[i]: seconds it takes to play from the start tick to start tick + i, not counting freezes */
    private final double[] moving;
    /** hold[i]: seconds the recording is frozen on start tick + i */
    private final double[] hold;
    /** heldBefore[i]: the sum of hold[0] to hold[i - 1] */
    private final double[] heldBefore;

    /** A map over the given ticks of the recording, both included */
    public TimeMap(Scene scene, int startTick, int endTick) {
        if (startTick < 0 || endTick < startTick) {
            throw new IllegalArgumentException("'end_tick' must not be less than 'start_tick' and neither less than 0, got " + startTick + " to " + endTick);
        }
        this.startTick = startTick;
        this.endTick = endTick;
        int length = endTick - startTick;

        // Seconds per tick inside timelapse pairs; lower tracks first so that upper ones overwrite them
        double[] lapse = new double[length];
        Arrays.fill(lapse, Double.NaN);
        this.hold = new double[length + 1];
        List<Track> tracks = scene.tracks();
        for (int t = tracks.size() - 1; t >= 0; t--) {
            Track track = tracks.get(t);
            if (!track.enabled()) {
                continue;
            }
            List<Keyframe> keyframes = track.keyframes();
            if (track.kind() == KeyframeKind.TIMELAPSE) {
                for (int k = 0; k + 1 < keyframes.size(); k += 2) {
                    int from = keyframes.get(k).tick();
                    int to = keyframes.get(k + 1).tick();
                    double perTick = ((Keyframe.Timelapse) keyframes.get(k)).realSeconds() / (to - from);
                    for (int tick = Math.max(from, startTick); tick < Math.min(to, endTick); tick++) {
                        lapse[tick - startTick] = perTick;
                    }
                }
            } else if (track.kind() == KeyframeKind.FREEZE) {
                for (Keyframe keyframe : keyframes) {
                    if (keyframe.tick() >= startTick && keyframe.tick() <= endTick) {
                        this.hold[keyframe.tick() - startTick] = ((Keyframe.Freeze) keyframe).holdSeconds();
                    }
                }
            }
        }

        this.moving = new double[length + 1];
        this.heldBefore = new double[length + 1];
        // Two sample points inside each tick (Gauss-Legendre): exact for a steady speed, close for a changing one
        double offset = 0.5 / Math.sqrt(3.0);
        for (int i = 0; i < length; i++) {
            double seconds = lapse[i];
            if (Double.isNaN(seconds)) {
                double middle = startTick + i + 0.5;
                seconds = 0.5 / Evaluator.speed(scene, middle - offset) + 0.5 / Evaluator.speed(scene, middle + offset);
            }
            this.moving[i + 1] = this.moving[i] + seconds;
            this.heldBefore[i + 1] = this.heldBefore[i] + this.hold[i];
        }
    }

    /**
     * A map over the scene's export range. Without one, over the scene's keyframes from the first to the last, and
     * over tick 0 alone when there are none.
     */
    public static TimeMap forExport(Scene scene) {
        if (scene.hasExportRange()) {
            return new TimeMap(scene, scene.exportStart(), scene.exportEnd());
        }
        return new TimeMap(scene, Math.max(0, scene.firstKeyframeTick()), Math.max(0, scene.lastKeyframeTick()));
    }

    public int startTick() {
        return this.startTick;
    }

    public int endTick() {
        return this.endTick;
    }

    /** How long the whole range lasts as video, including a freeze on its last tick */
    public double totalRealSeconds() {
        int last = this.endTick - this.startTick;
        return this.arrival(last) + this.hold[last];
    }

    /** Seconds from the start at which start tick + i is first shown */
    private double arrival(int i) {
        return this.moving[i] + this.heldBefore[i];
    }

    /**
     * The recording tick (usually between two whole ticks) shown this many seconds into the video. Before the start
     * it is the start tick, after the end the end tick. Never less for a later time than for an earlier one.
     */
    public double tickAt(double realSeconds) {
        int length = this.endTick - this.startTick;
        if (!(realSeconds > 0.0)) {
            return this.startTick;
        }
        if (realSeconds >= this.arrival(length)) {
            return this.endTick;
        }
        // The last whole tick reached by this time
        int low = 0;
        int high = length;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (this.arrival(middle) <= realSeconds) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        double moved = realSeconds - this.arrival(low) - this.hold[low];
        double tickSeconds = this.moving[low + 1] - this.moving[low];
        if (moved <= 0.0 || tickSeconds <= 0.0) {
            return this.startTick + low;
        }
        return this.startTick + low + Math.min(1.0, moved / tickSeconds);
    }

    /** The tick shown by frame number {@code frame} (from 0) of a video with this many frames per second */
    public double tickAtFrame(long frame, double framesPerSecond) {
        return this.tickAt(frame / framesPerSecond);
    }

    /** How many frames a video of the whole range has: every frame n with n / fps before the end, at least one */
    public long frameCount(double framesPerSecond) {
        return Math.max(1L, (long) Math.ceil(this.totalRealSeconds() * framesPerSecond - 1e-9));
    }

    /**
     * Seconds into the video at which the recording tick is first shown: for a tick with a freeze on it, the moment
     * the freeze begins. Ticks outside the range count as its nearest end.
     */
    public double realSecondsAt(double tick) {
        double along = Math.max(0.0, Math.min(this.endTick - this.startTick, tick - this.startTick));
        int whole = (int) Math.floor(along);
        double fraction = along - whole;
        if (fraction == 0.0) {
            return this.arrival(whole);
        }
        return this.arrival(whole) + this.hold[whole] + fraction * (this.moving[whole + 1] - this.moving[whole]);
    }

    /**
     * How long the video takes to get from one recording tick to another: positive when {@code tickB} is the later.
     * A freeze on the earlier tick is counted, one on the later tick is not.
     */
    public double realSecondsBetween(double tickA, double tickB) {
        return this.realSecondsAt(tickB) - this.realSecondsAt(tickA);
    }

    /** Seconds the recording is frozen on a tick; 0 for most */
    public double holdSecondsAt(int tick) {
        return tick < this.startTick || tick > this.endTick ? 0.0 : this.hold[tick - this.startTick];
    }

    /** Whether the recording is standing still in a freeze this many seconds into the video */
    public boolean isFrozenAt(double realSeconds) {
        if (!(realSeconds >= 0.0) || realSeconds >= this.totalRealSeconds()) {
            return false;
        }
        double tick = this.tickAt(realSeconds);
        int whole = (int) tick;
        return tick == whole && realSeconds < this.realSecondsAt(whole) + this.hold[whole - this.startTick];
    }

    /**
     * The tick at which to sample a track that must carry on moving while the world is frozen (the camera, see the
     * freeze keyframe). Outside freezes this is simply {@link #tickAt}. Where there are freezes between the two
     * keyframes of the track that surround the present tick, the track travels from the one keyframe to the other
     * over the whole of that time, freezes included, a little slower throughout instead of stopping with the world.
     */
    public double cameraTickAt(double realSeconds, Track track) {
        double tick = this.tickAt(realSeconds);
        int index = track.indexAtOrBefore(tick);
        if (index < 0 || index + 1 >= track.size()) {
            return tick;
        }
        List<Keyframe> keyframes = track.keyframes();
        return this.cameraTickAt(realSeconds, keyframes.get(index).tick(), keyframes.get(index + 1).tick());
    }

    /** As {@link #cameraTickAt(double, Track)}, for a stretch between two given ticks */
    public double cameraTickAt(double realSeconds, int segmentStartTick, int segmentEndTick) {
        int from = Math.max(this.startTick, Math.min(this.endTick, segmentStartTick)) - this.startTick;
        int to = Math.max(this.startTick, Math.min(this.endTick, segmentEndTick)) - this.startTick;
        if (to <= from || this.heldBefore[to] - this.heldBefore[from] <= 0.0) {
            return this.tickAt(realSeconds);
        }
        double duration = this.arrival(to) - this.arrival(from);
        double progress = Math.max(0.0, Math.min(1.0, (realSeconds - this.arrival(from)) / duration));
        double target = this.moving[from] + progress * (this.moving[to] - this.moving[from]);
        // The last whole tick whose motion-only time is not after the target
        int low = from;
        int high = to;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (this.moving[middle] <= target) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        if (low >= to) {
            return this.startTick + to;
        }
        double tickSeconds = this.moving[low + 1] - this.moving[low];
        return this.startTick + low + (tickSeconds <= 0.0 ? 0.0 : Math.min(1.0, (target - this.moving[low]) / tickSeconds));
    }

}
