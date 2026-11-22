package comedymonkey.dailies.edit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * One keyframe: a value of one kind on a whole tick, with the way it blends towards the next keyframe. Keyframes never
 * change; editing one means putting another in its place ({@link #withTick}, {@link #withInterpolation} or a new one).
 * <p>
 * Every kind reads and writes the same JSON shape: "tick", "interpolation", then its own fields in snake_case. That is
 * the shape saved in the recording, listed by the MCP tools and accepted back by them. Values outside the allowed
 * range are refused with an {@link IllegalArgumentException} that names the field and the range.
 */
public sealed interface Keyframe {

    /** The last tick a keyframe may sit on, far beyond any recording (about 57 days) */
    int MAX_TICK = 100_000_000;
    /** How far from the origin a position may be, in blocks */
    double MAX_POSITION = 30_000_000;

    int tick();

    Interpolation interpolation();

    KeyframeKind kind();

    Keyframe withTick(int tick);

    Keyframe withInterpolation(Interpolation interpolation);

    /** "tick", "interpolation" and the fields of the kind */
    JsonObject toJson();

    /** {@link #toJson} as JSON text */
    default String toJsonText() {
        return this.toJson().toString();
    }

    /** The numbers that blend between two keyframes of this kind, in a fixed order; none for kinds that do not blend */
    default double[] blendValues() {
        return new double[0];
    }

    /** Which body part of an entity a follow keyframe is measured from */
    enum BodyPart {
        FEET, BODY, HEAD, EYES
    }

    /** What the camera does about the entity it follows */
    enum FollowMode {
        /** The camera keeps its place relative to the entity: the offset is from the body part to the camera */
        KEEP_RELATIVE,
        /** The camera stays put and turns to watch the entity: the offset is from the body part to the point watched */
        WATCH_FROM_FIXED
    }

    /** Viewer position and rotation. Yaw is kept between -180 and 180 */
    record Camera(int tick, Interpolation interpolation, double x, double y, double z, double yaw, double pitch, double roll) implements Keyframe {
        public Camera {
            common(tick, interpolation);
            position("x", x);
            position("y", y);
            position("z", z);
            yaw = degrees("yaw", yaw);
            JsonFields.range("pitch", pitch, -90, 90);
            JsonFields.range("roll", roll, -3600, 3600);
        }

        @Override
        public KeyframeKind kind() {
            return KeyframeKind.CAMERA;
        }

        @Override
        public Camera withTick(int tick) {
            return new Camera(tick, this.interpolation, this.x, this.y, this.z, this.yaw, this.pitch, this.roll);
        }

        @Override
        public Camera withInterpolation(Interpolation interpolation) {
            return new Camera(this.tick, interpolation, this.x, this.y, this.z, this.yaw, this.pitch, this.roll);
        }

        @Override
        public JsonObject toJson() {
            JsonObject json = base(this);
            json.addProperty("x", this.x);
            json.addProperty("y", this.y);
            json.addProperty("z", this.z);
            json.addProperty("yaw", this.yaw);
            json.addProperty("pitch", this.pitch);
            json.addProperty("roll", this.roll);
            return json;
        }

        @Override
        public double[] blendValues() {
            return new double[]{this.x, this.y, this.z, this.yaw, this.pitch, this.roll};
        }
    }

    /**
     * The camera circles a centre point while looking at it. Distance is measured along the ground, height is how far
     * the camera is above the centre, and angle is the camera's yaw (kept between -180 and 180).
     * <p>
     * With an entity named ({@code entity_uuid}, and the numeric id it had in the recording, -1 when not known), the
     * circle is centred on that entity as it moves, and the centre numbers are an offset from the middle of the
     * entity (0, 0, 0 for its middle). Like the follow keyframe, the entity is taken from the earlier keyframe; only
     * the numbers blend. Without one the centre is a fixed point, as in scenes made before entities could be named.
     */
    record Orbit(int tick, Interpolation interpolation, double centreX, double centreY, double centreZ, double distance, double height,
                 double angle, UUID entityUuid, int entityId) implements Keyframe {
        public Orbit {
            common(tick, interpolation);
            position("centre_x", centreX);
            position("centre_y", centreY);
            position("centre_z", centreZ);
            JsonFields.range("distance", distance, 0, 1000);
            JsonFields.range("height", height, -1000, 1000);
            angle = degrees("angle", angle);
            if (entityId < -1) {
                throw new IllegalArgumentException("'entity_id' must be a whole number from -1 to " + Integer.MAX_VALUE + ", got " + entityId);
            }
            if (entityUuid == null) {
                entityId = -1;
            }
        }

        /** Around a fixed point */
        public Orbit(int tick, Interpolation interpolation, double centreX, double centreY, double centreZ, double distance, double height, double angle) {
            this(tick, interpolation, centreX, centreY, centreZ, distance, height, angle, null, -1);
        }

        /** Whether the circle is centred on an entity rather than on a fixed point */
        public boolean onEntity() {
            return this.entityUuid != null;
        }

        @Override
        public KeyframeKind kind() {
            return KeyframeKind.ORBIT;
        }

        @Override
        public Orbit withTick(int tick) {
            return new Orbit(tick, this.interpolation, this.centreX, this.centreY, this.centreZ, this.distance, this.height, this.angle, this.entityUuid, this.entityId);
        }

        @Override
        public Orbit withInterpolation(Interpolation interpolation) {
            return new Orbit(this.tick, interpolation, this.centreX, this.centreY, this.centreZ, this.distance, this.height, this.angle, this.entityUuid, this.entityId);
        }

        /** The same circle centred on an entity (an offset from its middle), or on a fixed point when the entity is null */
        public Orbit withEntity(UUID uuid, int id, double offsetX, double offsetY, double offsetZ) {
            return new Orbit(this.tick, this.interpolation, offsetX, offsetY, offsetZ, this.distance, this.height, this.angle, uuid, uuid == null ? -1 : id);
        }

        @Override
        public JsonObject toJson() {
            JsonObject json = base(this);
            json.addProperty("centre_x", this.centreX);
            json.addProperty("centre_y", this.centreY);
            json.addProperty("centre_z", this.centreZ);
            json.addProperty("distance", this.distance);
            json.addProperty("height", this.height);
            json.addProperty("angle", this.angle);
            if (this.entityUuid != null) {
                json.addProperty("entity_uuid", this.entityUuid.toString());
                json.addProperty("entity_id", this.entityId);
            }
            return json;
        }

        @Override
        public double[] blendValues() {
            return new double[]{this.centreX, this.centreY, this.centreZ, this.distance, this.height, this.angle};
        }
    }

    /**
     * Ties the camera to an entity. The entity is named by its UUID; the numeric id it had in the recording is kept as
     * well (-1 when not known) for entities whose UUID is not unique. Only the offset blends.
     */
    record FollowEntity(int tick, Interpolation interpolation, UUID entityUuid, int entityId, BodyPart part, double offsetX, double offsetY,
                        double offsetZ, FollowMode mode) implements Keyframe {
        public FollowEntity {
            common(tick, interpolation);
            if (entityUuid == null) {
                throw new IllegalArgumentException("'entity_uuid' is required: the UUID of the entity to follow");
            }
            if (entityId < -1) {
                throw new IllegalArgumentException("'entity_id' must be a whole number from -1 to " + Integer.MAX_VALUE + ", got " + entityId);
            }
            if (part == null) {
                throw new IllegalArgumentException("'part' is required: one of " + String.join(", ", JsonFields.ids(BodyPart.values(), JsonFields::id)));
            }
            if (mode == null) {
                throw new IllegalArgumentException("'mode' is required: one of " + String.join(", ", JsonFields.ids(FollowMode.values(), JsonFields::id)));
            }
            JsonFields.range("offset_x", offsetX, -1000, 1000);
            JsonFields.range("offset_y", offsetY, -1000, 1000);
            JsonFields.range("offset_z", offsetZ, -1000, 1000);
        }

        @Override
        public KeyframeKind kind() {
            return KeyframeKind.FOLLOW_ENTITY;
        }

        @Override
        public FollowEntity withTick(int tick) {
            return new FollowEntity(tick, this.interpolation, this.entityUuid, this.entityId, this.part, this.offsetX, this.offsetY, this.offsetZ, this.mode);
        }

        @Override
        public FollowEntity withInterpolation(Interpolation interpolation) {
            return new FollowEntity(this.tick, interpolation, this.entityUuid, this.entityId, this.part, this.offsetX, this.offsetY, this.offsetZ, this.mode);
        }

        @Override
        public JsonObject toJson() {
            JsonObject json = base(this);
            json.addProperty("entity_uuid", this.entityUuid.toString());
            json.addProperty("entity_id", this.entityId);
            json.addProperty("part", JsonFields.id(this.part));
            json.addProperty("offset_x", this.offsetX);
            json.addProperty("offset_y", this.offsetY);
            json.addProperty("offset_z", this.offsetZ);
            json.addProperty("mode", JsonFields.id(this.mode));
            return json;
        }

        @Override
        public double[] blendValues() {
            return new double[]{this.offsetX, this.offsetY, this.offsetZ};
        }
    }

    /**
     * A player and a way of filming them. The settings are kept as the JSON object the Smart Camera reads: "target"
     * (a player name or UUID), "mode" (chase, tripod, orbit or director) and any of its other settings in snake_case.
     * Only target and mode are checked here; the Smart Camera clamps the rest when it reads them.
     */
    record SmartCamera(int tick, Interpolation interpolation, JsonObject settings) implements Keyframe {
        /** The ways of filming the Smart Camera knows */
        public static final List<String> MODES = List.of("chase", "tripod", "orbit", "director");

        public SmartCamera {
            common(tick, interpolation);
            if (settings == null) {
                throw new IllegalArgumentException("'target' is required: a player name or UUID");
            }
            settings = settings.deepCopy();
            settings.remove("tick");
            settings.remove("interpolation");
            JsonFields.text(settings, "target");
            String mode = settings.has("mode") ? JsonFields.text(settings.get("mode"), "mode").trim().toLowerCase(Locale.ROOT) : "";
            if (!MODES.contains(mode)) {
                throw new IllegalArgumentException("'mode' must be one of " + String.join(", ", MODES) + ", got " + JsonFields.show(settings.get("mode")));
            }
            settings.addProperty("mode", mode);
        }

        /** A copy; changing it does not change the keyframe */
        @Override
        public JsonObject settings() {
            return this.settings.deepCopy();
        }

        /** The player to film: a name or a UUID */
        public String target() {
            return this.settings.get("target").getAsString();
        }

        /** One of {@link #MODES} */
        public String mode() {
            return this.settings.get("mode").getAsString();
        }

        @Override
        public KeyframeKind kind() {
            return KeyframeKind.SMART_CAMERA;
        }

        @Override
        public SmartCamera withTick(int tick) {
            return new SmartCamera(tick, this.interpolation, this.settings);
        }

        @Override
        public SmartCamera withInterpolation(Interpolation interpolation) {
            return new SmartCamera(this.tick, interpolation, this.settings);
        }

        @Override
        public JsonObject toJson() {
            JsonObject json = base(this);
            for (Map.Entry<String, JsonElement> entry : this.settings.entrySet()) {
                json.add(entry.getKey(), entry.getValue().deepCopy());
            }
            return json;
        }
    }

    /** The viewer's field of view in degrees */
    record FieldOfView(int tick, Interpolation interpolation, double fov) implements Keyframe {
        public FieldOfView {
            common(tick, interpolation);
            JsonFields.range("fov", fov, 1, 170);
        }

        @Override
        public KeyframeKind kind() {
            return KeyframeKind.FIELD_OF_VIEW;
        }

        @Override
        public FieldOfView withTick(int tick) {
            return new FieldOfView(tick, this.interpolation, this.fov);
        }

        @Override
        public FieldOfView withInterpolation(Interpolation interpolation) {
            return new FieldOfView(this.tick, interpolation, this.fov);
        }

        @Override
        public JsonObject toJson() {
            JsonObject json = base(this);
            json.addProperty("fov", this.fov);
            return json;
        }

        @Override
        public double[] blendValues() {
            return new double[]{this.fov};
        }
    }

    /** A hand-held wobble on top of the camera: strength (0 is none, 1 is plain) and wobbles per second */
    record CameraShake(int tick, Interpolation interpolation, double strength, double frequency) implements Keyframe {
        public CameraShake {
            common(tick, interpolation);
            JsonFields.range("strength", strength, 0, 10);
            JsonFields.range("frequency", frequency, 0, 20);
        }

        @Override
        public KeyframeKind kind() {
            return KeyframeKind.CAMERA_SHAKE;
        }

        @Override
        public CameraShake withTick(int tick) {
            return new CameraShake(tick, this.interpolation, this.strength, this.frequency);
        }

        @Override
        public CameraShake withInterpolation(Interpolation interpolation) {
            return new CameraShake(this.tick, interpolation, this.strength, this.frequency);
        }

        @Override
        public JsonObject toJson() {
            JsonObject json = base(this);
            json.addProperty("strength", this.strength);
            json.addProperty("frequency", this.frequency);
            return json;
        }

        @Override
        public double[] blendValues() {
            return new double[]{this.strength, this.frequency};
        }
    }

    /** Playback rate in recording ticks per second of video; 20 is as recorded */
    record Speed(int tick, Interpolation interpolation, double ticksPerSecond) implements Keyframe {
        public static final double MIN = 0.05;
        public static final double MAX = 2000;

        public Speed {
            common(tick, interpolation);
            JsonFields.range("ticks_per_second", ticksPerSecond, MIN, MAX);
        }

        @Override
        public KeyframeKind kind() {
            return KeyframeKind.SPEED;
        }

        @Override
        public Speed withTick(int tick) {
            return new Speed(tick, this.interpolation, this.ticksPerSecond);
        }

        @Override
        public Speed withInterpolation(Interpolation interpolation) {
            return new Speed(this.tick, interpolation, this.ticksPerSecond);
        }

        @Override
        public JsonObject toJson() {
            JsonObject json = base(this);
            json.addProperty("ticks_per_second", this.ticksPerSecond);
            return json;
        }

        @Override
        public double[] blendValues() {
            return new double[]{this.ticksPerSecond};
        }
    }

    /**
     * One end of a timelapse. The keyframes of a track pair up in order (first with second, third with fourth); the
     * stretch of recording inside a pair plays in the real seconds stated on the pair's first keyframe. The value on
     * the second keyframe of a pair is not used, and a keyframe left without a partner does nothing.
     */
    record Timelapse(int tick, Interpolation interpolation, double realSeconds) implements Keyframe {
        public Timelapse {
            common(tick, interpolation);
            JsonFields.range("real_seconds", realSeconds, 0.001, 86400);
        }

        @Override
        public KeyframeKind kind() {
            return KeyframeKind.TIMELAPSE;
        }

        @Override
        public Timelapse withTick(int tick) {
            return new Timelapse(tick, this.interpolation, this.realSeconds);
        }

        @Override
        public Timelapse withInterpolation(Interpolation interpolation) {
            return new Timelapse(this.tick, interpolation, this.realSeconds);
        }

        @Override
        public JsonObject toJson() {
            JsonObject json = base(this);
            json.addProperty("real_seconds", this.realSeconds);
            return json;
        }
    }

    /** Holds the world still on this tick for a number of seconds of video */
    record Freeze(int tick, Interpolation interpolation, double holdSeconds) implements Keyframe {
        public Freeze {
            common(tick, interpolation);
            JsonFields.range("hold_seconds", holdSeconds, 0, 3600);
        }

        @Override
        public KeyframeKind kind() {
            return KeyframeKind.FREEZE;
        }

        @Override
        public Freeze withTick(int tick) {
            return new Freeze(tick, this.interpolation, this.holdSeconds);
        }

        @Override
        public Freeze withInterpolation(Interpolation interpolation) {
            return new Freeze(this.tick, interpolation, this.holdSeconds);
        }

        @Override
        public JsonObject toJson() {
            JsonObject json = base(this);
            json.addProperty("hold_seconds", this.holdSeconds);
            return json;
        }
    }

    /** The sky's time in ticks of the day: 0 sunrise, 6000 noon, 12000 sunset, 18000 midnight */
    record TimeOfDay(int tick, Interpolation interpolation, double time) implements Keyframe {
        public TimeOfDay {
            common(tick, interpolation);
            JsonFields.range("time", time, 0, 24000);
        }

        @Override
        public KeyframeKind kind() {
            return KeyframeKind.TIME_OF_DAY;
        }

        @Override
        public TimeOfDay withTick(int tick) {
            return new TimeOfDay(tick, this.interpolation, this.time);
        }

        @Override
        public TimeOfDay withInterpolation(Interpolation interpolation) {
            return new TimeOfDay(this.tick, interpolation, this.time);
        }

        @Override
        public JsonObject toJson() {
            JsonObject json = base(this);
            json.addProperty("time", this.time);
            return json;
        }

        @Override
        public double[] blendValues() {
            return new double[]{this.time};
        }
    }

    /** Shows a block at a position from this tick on. The block is written as the game writes a block state */
    record BlockChange(int tick, Interpolation interpolation, int x, int y, int z, String blockState) implements Keyframe {
        public BlockChange {
            common(tick, interpolation);
            position("x", x);
            position("y", y);
            position("z", z);
            if (blockState == null || blockState.isBlank()) {
                throw new IllegalArgumentException("'block_state' must be a string that is not empty, for example \"minecraft:oak_stairs[facing=north]\"");
            }
        }

        @Override
        public KeyframeKind kind() {
            return KeyframeKind.BLOCK_CHANGE;
        }

        @Override
        public BlockChange withTick(int tick) {
            return new BlockChange(tick, this.interpolation, this.x, this.y, this.z, this.blockState);
        }

        @Override
        public BlockChange withInterpolation(Interpolation interpolation) {
            return new BlockChange(this.tick, interpolation, this.x, this.y, this.z, this.blockState);
        }

        @Override
        public JsonObject toJson() {
            JsonObject json = base(this);
            json.addProperty("x", this.x);
            json.addProperty("y", this.y);
            json.addProperty("z", this.z);
            json.addProperty("block_state", this.blockState);
            return json;
        }
    }

    /** An audio file from disk that starts playing on this tick. Volume 1 is the file's own loudness */
    record Audio(int tick, Interpolation interpolation, String file, double volume) implements Keyframe {
        public Audio {
            common(tick, interpolation);
            if (file == null || file.isBlank()) {
                throw new IllegalArgumentException("'file' must be a string that is not empty: the path of an audio file");
            }
            JsonFields.range("volume", volume, 0, 2);
        }

        @Override
        public KeyframeKind kind() {
            return KeyframeKind.AUDIO;
        }

        @Override
        public Audio withTick(int tick) {
            return new Audio(tick, this.interpolation, this.file, this.volume);
        }

        @Override
        public Audio withInterpolation(Interpolation interpolation) {
            return new Audio(this.tick, interpolation, this.file, this.volume);
        }

        @Override
        public JsonObject toJson() {
            JsonObject json = base(this);
            json.addProperty("file", this.file);
            json.addProperty("volume", this.volume);
            return json;
        }
    }

    /**
     * From this tick the scene shows another of its linked recordings, the same moment seen by someone else (PLAY-19,
     * EDIT-11). It is a cut: playback jumps the other recording to the tick that lines up with this one, and the camera,
     * the other tracks and the looks carry on as they were. The recording is named by its unique id; the label is the
     * name a person reads, usually the player who recorded it, and may be empty.
     */
    record PointOfView(int tick, Interpolation interpolation, String recordingId, String label) implements Keyframe {
        public PointOfView {
            common(tick, interpolation);
            if (recordingId == null || recordingId.isBlank()) {
                throw new IllegalArgumentException("'recording_id' must be a string that is not empty: the id of one of the scene's recordings");
            }
            recordingId = recordingId.trim();
            label = label == null ? "" : label.trim();
        }

        @Override
        public KeyframeKind kind() {
            return KeyframeKind.POINT_OF_VIEW;
        }

        @Override
        public PointOfView withTick(int tick) {
            return new PointOfView(tick, this.interpolation, this.recordingId, this.label);
        }

        @Override
        public PointOfView withInterpolation(Interpolation interpolation) {
            return new PointOfView(this.tick, interpolation, this.recordingId, this.label);
        }

        @Override
        public JsonObject toJson() {
            JsonObject json = base(this);
            json.addProperty("recording_id", this.recordingId);
            json.addProperty("label", this.label);
            return json;
        }
    }

    /**
     * Reads a keyframe of the given kind. "tick" (default 0) and "interpolation" (default linear) may be left out, so
     * that a caller holding them separately can add them with {@link #withTick} and {@link #withInterpolation}.
     * Fields the kind does not know are ignored.
     *
     * @throws IllegalArgumentException naming the field that is missing or wrong, and what is allowed
     */
    static Keyframe fromJson(KeyframeKind kind, JsonObject json) {
        if (json == null) {
            throw new IllegalArgumentException("'keyframe' is required: an object like " + exampleJson(kind));
        }
        int tick = JsonFields.integer(json, "tick", 0, MAX_TICK, 0);
        Interpolation mode = json.has("interpolation") ? Interpolation.parse(JsonFields.text(json.get("interpolation"), "interpolation")) : Interpolation.LINEAR;
        double far = MAX_POSITION;
        return switch (kind) {
            case CAMERA -> new Camera(tick, mode,
                    JsonFields.number(json, "x", -far, far), JsonFields.number(json, "y", -far, far), JsonFields.number(json, "z", -far, far),
                    JsonFields.number(json, "yaw", -36000, 36000), JsonFields.number(json, "pitch", -90, 90),
                    JsonFields.number(json, "roll", -3600, 3600, 0));
            case ORBIT -> {
                // An entity is optional: scenes from before it could be named have none
                boolean named = json.has("entity_uuid") && !json.get("entity_uuid").isJsonNull()
                        && !(json.get("entity_uuid").isJsonPrimitive() && json.get("entity_uuid").getAsString().isBlank());
                UUID entity = named ? JsonFields.uuid(JsonFields.text(json, "entity_uuid"), "entity_uuid") : null;
                yield new Orbit(tick, mode,
                        (named ? JsonFields.number(json, "centre_x", -far, far, 0) : JsonFields.number(json, "centre_x", -far, far)), (named ? JsonFields.number(json, "centre_y", -far, far, 0) : JsonFields.number(json, "centre_y", -far, far)),
                        (named ? JsonFields.number(json, "centre_z", -far, far, 0) : JsonFields.number(json, "centre_z", -far, far)), JsonFields.number(json, "distance", 0, 1000),
                        JsonFields.number(json, "height", -1000, 1000, 0), JsonFields.number(json, "angle", -36000, 36000, 0),
                        entity, named ? JsonFields.integer(json, "entity_id", -1, Integer.MAX_VALUE, -1) : -1);
            }
            case FOLLOW_ENTITY -> new FollowEntity(tick, mode,
                    JsonFields.uuid(JsonFields.text(json, "entity_uuid"), "entity_uuid"),
                    JsonFields.integer(json, "entity_id", -1, Integer.MAX_VALUE, -1),
                    JsonFields.choice(json, "part", BodyPart.values(), BodyPart.EYES),
                    JsonFields.number(json, "offset_x", -1000, 1000, 0), JsonFields.number(json, "offset_y", -1000, 1000, 0),
                    JsonFields.number(json, "offset_z", -1000, 1000, 0),
                    JsonFields.choice(json, "mode", FollowMode.values(), FollowMode.KEEP_RELATIVE));
            case SMART_CAMERA -> new SmartCamera(tick, mode, json);
            case FIELD_OF_VIEW -> new FieldOfView(tick, mode, JsonFields.number(json, "fov", 1, 170));
            case CAMERA_SHAKE -> new CameraShake(tick, mode, JsonFields.number(json, "strength", 0, 10), JsonFields.number(json, "frequency", 0, 20, 2));
            case SPEED -> new Speed(tick, mode, JsonFields.number(json, "ticks_per_second", Speed.MIN, Speed.MAX));
            case TIMELAPSE -> new Timelapse(tick, mode, JsonFields.number(json, "real_seconds", 0.001, 86400));
            case FREEZE -> new Freeze(tick, mode, JsonFields.number(json, "hold_seconds", 0, 3600));
            case TIME_OF_DAY -> new TimeOfDay(tick, mode, JsonFields.number(json, "time", 0, 24000));
            case BLOCK_CHANGE -> new BlockChange(tick, mode,
                    JsonFields.integer(json, "x", (int) -far, (int) far), JsonFields.integer(json, "y", (int) -far, (int) far),
                    JsonFields.integer(json, "z", (int) -far, (int) far), JsonFields.text(json, "block_state"));
            case AUDIO -> new Audio(tick, mode, JsonFields.text(json, "file"), JsonFields.number(json, "volume", 0, 2, 1));
            case POINT_OF_VIEW -> new PointOfView(tick, mode, JsonFields.text(json, "recording_id"),
                    json.has("label") && json.get("label").isJsonPrimitive() ? json.get("label").getAsString() : "");
        };
    }

    /** {@link #fromJson} for JSON text */
    static Keyframe fromJsonText(KeyframeKind kind, String json) {
        return fromJson(kind, JsonFields.object(json, "keyframe"));
    }

    /** A sensible keyframe of the kind, to show what one looks like */
    static Keyframe example(KeyframeKind kind) {
        return switch (kind) {
            case CAMERA -> new Camera(120, Interpolation.SMOOTH, 104.5, 72.0, -31.25, 135.0, 12.5, 0.0);
            case ORBIT -> new Orbit(120, Interpolation.LINEAR, 104.5, 70.0, -31.5, 12.0, 4.0, 90.0);
            case FOLLOW_ENTITY -> new FollowEntity(120, Interpolation.LINEAR, UUID.fromString("0b1e6f0e-5a52-4c61-9a37-0c1d2e3f4a5b"), 412,
                    BodyPart.HEAD, 0.0, 1.5, -4.0, FollowMode.KEEP_RELATIVE);
            case SMART_CAMERA -> {
                JsonObject settings = new JsonObject();
                settings.addProperty("target", "Steve");
                settings.addProperty("mode", "chase");
                settings.addProperty("distance", 6.0);
                settings.addProperty("height", 2.0);
                settings.addProperty("side_angle", 0.0);
                settings.addProperty("position_smoothing", 0.35);
                settings.addProperty("heading_source", "movement");
                settings.addProperty("aim_smoothing", 0.25);
                settings.addProperty("screen_x", 0.0);
                settings.addProperty("screen_y", 0.0);
                settings.addProperty("lead_room", 0.25);
                settings.addProperty("auto_zoom", false);
                settings.addProperty("fixed_fov", 0.0);
                settings.addProperty("avoid_obstructions", true);
                settings.addProperty("transition_seconds", 0.5);
                yield new SmartCamera(120, Interpolation.HOLD, settings);
            }
            case FIELD_OF_VIEW -> new FieldOfView(120, Interpolation.EASE_IN_OUT, 30.0);
            case CAMERA_SHAKE -> new CameraShake(120, Interpolation.LINEAR, 0.5, 2.0);
            case SPEED -> new Speed(120, Interpolation.LINEAR, 10.0);
            case TIMELAPSE -> new Timelapse(120, Interpolation.HOLD, 5.0);
            case FREEZE -> new Freeze(120, Interpolation.HOLD, 2.0);
            case TIME_OF_DAY -> new TimeOfDay(120, Interpolation.LINEAR, 6000.0);
            case BLOCK_CHANGE -> new BlockChange(120, Interpolation.HOLD, 104, 70, -32, "minecraft:oak_stairs[facing=north]");
            case AUDIO -> new Audio(120, Interpolation.HOLD, "C:/Music/theme.ogg", 1.0);
            case POINT_OF_VIEW -> new PointOfView(120, Interpolation.HOLD, "3f2a9c4e-8b1d-4e7a-9c55-2d6f0a1b7e93", "Alex");
        };
    }

    /** The JSON of {@link #example}: what the MCP tools show an agent for this kind */
    static JsonObject exampleJson(KeyframeKind kind) {
        return example(kind).toJson();
    }

    /** {@link #exampleJson} as JSON text */
    static String exampleJsonText(KeyframeKind kind) {
        return example(kind).toJsonText();
    }

    /** An angle in degrees brought between -180 (included) and 180 (not included) */
    static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0;
        if (wrapped >= 180.0) {
            wrapped -= 360.0;
        }
        if (wrapped < -180.0) {
            wrapped += 360.0;
        }
        return wrapped;
    }

    private static void common(int tick, Interpolation interpolation) {
        if (tick < 0 || tick > MAX_TICK) {
            throw new IllegalArgumentException("'tick' must be a whole number from 0 to " + MAX_TICK + ", got " + tick);
        }
        if (interpolation == null) {
            throw new IllegalArgumentException("'interpolation' is required: one of " + Interpolation.allowed());
        }
    }

    private static void position(String field, double value) {
        JsonFields.range(field, value, -MAX_POSITION, MAX_POSITION);
    }

    private static double degrees(String field, double degrees) {
        if (Double.isNaN(degrees) || Double.isInfinite(degrees)) {
            throw new IllegalArgumentException("'" + field + "' must be a number of degrees, got " + degrees);
        }
        return wrapDegrees(degrees);
    }

    private static JsonObject base(Keyframe keyframe) {
        JsonObject json = new JsonObject();
        json.addProperty("tick", keyframe.tick());
        json.addProperty("interpolation", keyframe.interpolation().id());
        return json;
    }

}
