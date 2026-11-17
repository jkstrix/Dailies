package comedymonkey.dailies.edit;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Works out what a scene's tracks say at a point in time (specification EDIT-2, EDIT-4). The tick may fall between
 * whole ticks.
 * <p>
 * For each kind, the uppermost enabled track that applies at the tick decides. A track applies from its first
 * keyframe on; before that a lower track gets its say. Between two keyframes the value blends by the mode set on the
 * earlier one, and after the last keyframe it stays. Angles (camera yaw, orbit angle) take the short way round and
 * come back between -180 and 180.
 * <p>
 * The smooth mode is a monotone cubic curve (Steffen's method): it passes through every keyframe, is smooth across
 * them, and between two keyframes never leaves the range of their two values, however unevenly they are spaced.
 */
public final class Evaluator {

    private Evaluator() {
    }

    /** Where the camera is and how it is turned. Yaw 0 looks towards +Z and grows clockwise seen from above */
    public record CameraPose(double x, double y, double z, double yaw, double pitch, double roll) {
    }

    /** A blended orbit keyframe */
    public record OrbitValue(double centreX, double centreY, double centreZ, double distance, double height, double angle) {

        /**
         * The camera this orbit asks for: {@code distance} blocks from the centre along the ground, {@code height}
         * blocks above it, turned to {@code angle} as its yaw and tilted to look at the centre.
         */
        public CameraPose pose() {
            double radians = Math.toRadians(this.angle);
            double x = this.centreX + Math.sin(radians) * this.distance;
            double z = this.centreZ - Math.cos(radians) * this.distance;
            double pitch = this.distance == 0.0 && this.height == 0.0 ? 0.0 : Math.toDegrees(Math.atan2(this.height, this.distance));
            return new CameraPose(x, this.centreY + this.height, z, this.angle, pitch, 0.0);
        }
    }

    /** Whom the camera follows (from the latest keyframe) and the blended offset */
    public record FollowValue(UUID entityUuid, int entityId, Keyframe.BodyPart part, Keyframe.FollowMode mode, double offsetX, double offsetY,
                              double offsetZ) {
    }

    /** A blended camera shake keyframe */
    public record ShakeValue(double strength, double frequency) {
    }

    /** The position of a block */
    public record BlockPosition(int x, int y, int z) {
    }

    /** The uppermost enabled track of the kind whose first keyframe is at or before the tick, or null */
    public static @Nullable Track applyingTrack(Scene scene, KeyframeKind kind, double tick) {
        for (Track track : scene.tracks()) {
            if (track.kind() == kind && track.appliesAt(tick)) {
                return track;
            }
        }
        return null;
    }

    /**
     * The track that places the camera at the tick: the uppermost enabled camera, orbit, follow entity or Smart
     * Camera track that applies, or null when the camera is left alone.
     */
    public static @Nullable Track cameraDriver(Scene scene, double tick) {
        for (Track track : scene.tracks()) {
            if (track.kind().drivesCamera() && track.appliesAt(tick)) {
                return track;
            }
        }
        return null;
    }

    /** The latest keyframe at or before the tick on the track of the kind that applies, or null */
    public static @Nullable Keyframe latest(Scene scene, KeyframeKind kind, double tick) {
        Track track = applyingTrack(scene, kind, tick);
        return track == null ? null : track.atOrBefore(tick);
    }

    public static @Nullable CameraPose camera(Scene scene, double tick) {
        double[] values = blend(scene, KeyframeKind.CAMERA, tick);
        return values == null ? null : new CameraPose(values[0], values[1], values[2], values[3], values[4], values[5]);
    }

    public static @Nullable OrbitValue orbit(Scene scene, double tick) {
        double[] values = blend(scene, KeyframeKind.ORBIT, tick);
        return values == null ? null : new OrbitValue(values[0], values[1], values[2], values[3], values[4], values[5]);
    }

    /** The entity, body part and mode of the latest follow keyframe, with the offset blended towards the next */
    public static @Nullable FollowValue follow(Scene scene, double tick) {
        Track track = applyingTrack(scene, KeyframeKind.FOLLOW_ENTITY, tick);
        double[] offset = track == null ? null : blend(track, tick);
        if (offset == null) {
            return null;
        }
        Keyframe.FollowEntity target = (Keyframe.FollowEntity) track.atOrBefore(tick);
        return new FollowValue(target.entityUuid(), target.entityId(), target.part(), target.mode(), offset[0], offset[1], offset[2]);
    }

    public static @Nullable Keyframe.SmartCamera smartCamera(Scene scene, double tick) {
        return (Keyframe.SmartCamera) latest(scene, KeyframeKind.SMART_CAMERA, tick);
    }

    /** Field of view in degrees, or null when no track sets it (then {@link Looks#fov} decides) */
    public static @Nullable Double fieldOfView(Scene scene, double tick) {
        double[] values = blend(scene, KeyframeKind.FIELD_OF_VIEW, tick);
        return values == null ? null : values[0];
    }

    public static @Nullable ShakeValue shake(Scene scene, double tick) {
        double[] values = blend(scene, KeyframeKind.CAMERA_SHAKE, tick);
        return values == null ? null : new ShakeValue(values[0], values[1]);
    }

    /** Playback rate in ticks per second; {@link TimeMap#DEFAULT_TICKS_PER_SECOND} where no speed track applies */
    public static double speed(Scene scene, double tick) {
        double[] values = blend(scene, KeyframeKind.SPEED, tick);
        return values == null ? TimeMap.DEFAULT_TICKS_PER_SECOND : values[0];
    }

    /** The sky's time (0 to 24000), or null when no track sets it */
    public static @Nullable Double timeOfDay(Scene scene, double tick) {
        double[] values = blend(scene, KeyframeKind.TIME_OF_DAY, tick);
        return values == null ? null : values[0];
    }

    /**
     * Every block shown differently at the tick. Block changes last from their tick on and pile up, so all enabled
     * block change tracks count; where several name the same position the latest wins, and the upper track on a tie.
     */
    public static Map<BlockPosition, String> blockChanges(Scene scene, double tick) {
        Map<BlockPosition, String> blocks = new LinkedHashMap<>();
        Map<BlockPosition, Integer> since = new LinkedHashMap<>();
        for (Track track : scene.tracks()) {
            if (track.kind() != KeyframeKind.BLOCK_CHANGE || !track.enabled()) {
                continue;
            }
            for (Keyframe keyframe : track.keyframes()) {
                if (keyframe.tick() > tick) {
                    break;
                }
                Keyframe.BlockChange change = (Keyframe.BlockChange) keyframe;
                BlockPosition position = new BlockPosition(change.x(), change.y(), change.z());
                Integer known = since.get(position);
                if (known == null || change.tick() > known) {
                    since.put(position, change.tick());
                    blocks.put(position, change.blockState());
                }
            }
        }
        return blocks;
    }

    /** Every audio keyframe on an enabled track, in tick order (upper tracks first on a tie). All of them play */
    public static List<Keyframe.Audio> audioCues(Scene scene) {
        List<Keyframe.Audio> cues = new ArrayList<>();
        for (Track track : scene.tracks()) {
            if (track.kind() == KeyframeKind.AUDIO && track.enabled()) {
                for (Keyframe keyframe : track.keyframes()) {
                    cues.add((Keyframe.Audio) keyframe);
                }
            }
        }
        cues.sort(Comparator.comparingInt(Keyframe.Audio::tick));
        return cues;
    }

    /**
     * Which recording the scene shows at the tick (PLAY-19): the id named by the latest point of view keyframe, or null
     * for the scene's own recording. A keyframe naming a recording the scene does not hold (unlinked since) shows the
     * scene's own recording too, so a scene never asks for footage it cannot have.
     *
     * @param ownId the id of the scene's own recording, which a keyframe may also name
     */
    public static @Nullable String pointOfView(Scene scene, double tick, @Nullable String ownId) {
        Keyframe latest = latest(scene, KeyframeKind.POINT_OF_VIEW, tick);
        if (!(latest instanceof Keyframe.PointOfView view) || view.recordingId().equals(ownId) || scene.linked(view.recordingId()) == null) {
            return null;
        }
        return view.recordingId();
    }

    /** A change of point of view: from this tick the scene shows this recording (null for its own) */
    public record Switch(int tick, @Nullable String recordingId) {
    }

    /**
     * Every change of point of view between two ticks, both included, in order, for the timeline's lanes and for
     * preloading the next recording before a cut. Keyframes that change nothing (the same recording again) are left out.
     */
    public static List<Switch> pointOfViewSwitches(Scene scene, int fromTick, int toTick, @Nullable String ownId) {
        List<Integer> ticks = new ArrayList<>();
        for (Track track : scene.tracks()) {
            if (track.kind() == KeyframeKind.POINT_OF_VIEW && track.enabled()) {
                for (Keyframe keyframe : track.keyframes()) {
                    if (keyframe.tick() >= fromTick && keyframe.tick() <= toTick && !ticks.contains(keyframe.tick())) {
                        ticks.add(keyframe.tick());
                    }
                }
            }
        }
        ticks.sort(Integer::compare);
        List<Switch> switches = new ArrayList<>();
        String shown = fromTick > 0 ? pointOfView(scene, fromTick - 1, ownId) : null;
        for (int tick : ticks) {
            String now = pointOfView(scene, tick, ownId);
            if (!java.util.Objects.equals(now, shown)) {
                switches.add(new Switch(tick, now));
                shown = now;
            }
        }
        return switches;
    }

    /** The blended numbers of the track of the kind that applies at the tick, or null when none does */
    public static @Nullable double[] blend(Scene scene, KeyframeKind kind, double tick) {
        Track track = applyingTrack(scene, kind, tick);
        return track == null ? null : blend(track, tick);
    }

    /**
     * The blended numbers of one track at the tick, in the order of {@link Keyframe#blendValues}, whether the track
     * is enabled or not. Null before the first keyframe and for kinds that do not blend.
     */
    public static @Nullable double[] blend(Track track, double tick) {
        if (!track.kind().blends()) {
            return null;
        }
        List<Keyframe> keyframes = track.keyframes();
        int index = track.indexAtOrBefore(tick);
        if (index < 0) {
            return null;
        }
        Keyframe earlier = keyframes.get(index);
        double[] from = earlier.blendValues();
        Interpolation mode = earlier.interpolation();
        if (index == keyframes.size() - 1 || mode == Interpolation.HOLD || tick <= earlier.tick()) {
            return from;
        }
        Keyframe later = keyframes.get(index + 1);
        double[] to = later.blendValues();
        double span = later.tick() - earlier.tick();
        double fraction = (tick - earlier.tick()) / span;
        Keyframe before = index > 0 ? keyframes.get(index - 1) : null;
        Keyframe after = index + 2 < keyframes.size() ? keyframes.get(index + 2) : null;
        int angleChannel = track.kind() == KeyframeKind.CAMERA ? 3 : track.kind() == KeyframeKind.ORBIT ? 5 : -1;

        double[] result = new double[from.length];
        for (int channel = 0; channel < from.length; channel++) {
            boolean angle = channel == angleChannel;
            double y1 = from[channel];
            double y2 = angle ? y1 + Keyframe.wrapDegrees(to[channel] - y1) : to[channel];
            double value;
            if (mode == Interpolation.SMOOTH) {
                double slope = (y2 - y1) / span;
                double tangent1 = slope;
                double tangent2 = slope;
                if (before != null) {
                    double raw = before.blendValues()[channel];
                    double y0 = angle ? y1 - Keyframe.wrapDegrees(y1 - raw) : raw;
                    tangent1 = tangent((y1 - y0) / (earlier.tick() - before.tick()), earlier.tick() - before.tick(), slope, span);
                }
                if (after != null) {
                    double raw = after.blendValues()[channel];
                    double y3 = angle ? y2 + Keyframe.wrapDegrees(raw - to[channel]) : raw;
                    tangent2 = tangent(slope, span, (y3 - y2) / (after.tick() - later.tick()), after.tick() - later.tick());
                }
                value = hermite(y1, y2, tangent1 * span, tangent2 * span, fraction);
                // Rounding must not carry the value outside the two keyframes either
                value = Math.max(Math.min(y1, y2), Math.min(Math.max(y1, y2), value));
            } else {
                value = y1 + (y2 - y1) * mode.ease(fraction);
            }
            result[channel] = angle ? Keyframe.wrapDegrees(value) : value;
        }
        return result;
    }

    /**
     * The slope of the smooth curve at a keyframe, from the slopes and lengths of the straight lines to its two
     * neighbours. Zero at a peak or a dip, and never steep enough to make the curve overshoot.
     */
    private static double tangent(double slopeBefore, double lengthBefore, double slopeAfter, double lengthAfter) {
        if (slopeBefore * slopeAfter <= 0.0) {
            return 0.0;
        }
        double weighted = (slopeBefore * lengthAfter + slopeAfter * lengthBefore) / (lengthBefore + lengthAfter);
        double limited = Math.min(Math.min(Math.abs(slopeBefore), Math.abs(slopeAfter)), 0.5 * Math.abs(weighted));
        return Math.signum(slopeBefore) * 2.0 * limited;
    }

    private static double hermite(double y1, double y2, double tangent1, double tangent2, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        return (2 * t3 - 3 * t2 + 1) * y1 + (t3 - 2 * t2 + t) * tangent1 + (-2 * t3 + 3 * t2) * y2 + (t3 - t2) * tangent2;
    }

}
