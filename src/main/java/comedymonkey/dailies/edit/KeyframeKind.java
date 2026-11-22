package comedymonkey.dailies.edit;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The kinds of keyframe a track can hold (specification chapter V, "Kinds of keyframe"). A track holds exactly one
 * kind.
 */
public enum KeyframeKind {

    CAMERA("Camera", true),
    ORBIT("Orbit", true),
    /** Only the offset blends; the entity, body part and mode come from the latest keyframe */
    FOLLOW_ENTITY("Follow entity", true),
    SMART_CAMERA("Smart Camera", false),
    FIELD_OF_VIEW("Field of view", true),
    CAMERA_SHAKE("Camera shake", true),
    SPEED("Speed", true),
    TIMELAPSE("Timelapse", false),
    FREEZE("Freeze", false),
    TIME_OF_DAY("Time of day", true),
    BLOCK_CHANGE("Block change", false),
    AUDIO("Audio", false),
    /** Which of the scene's linked recordings is shown from this tick (PLAY-19, EDIT-11). A cut, never a blend */
    POINT_OF_VIEW("Point of view", false);

    private final String title;
    private final boolean blends;

    KeyframeKind(String title, boolean blends) {
        this.title = title;
        this.blends = blends;
    }

    /** The name used in JSON and by the MCP tools, for example "field_of_view" */
    public String id() {
        return this.name().toLowerCase(Locale.ROOT);
    }

    /** The name a person reads, for example "Field of view" */
    public String title() {
        return this.title;
    }

    /** Whether values between two keyframes of this kind are interpolated */
    public boolean blends() {
        return this.blends;
    }

    /** Whether tracks of this kind place the camera. Only one of them can do so at a time */
    public boolean drivesCamera() {
        return this == CAMERA || this == ORBIT || this == FOLLOW_ENTITY || this == SMART_CAMERA;
    }

    /** Every id, comma separated, for error messages */
    public static String allowed() {
        StringBuilder builder = new StringBuilder();
        for (KeyframeKind kind : values()) {
            if (!builder.isEmpty()) {
                builder.append(", ");
            }
            builder.append(kind.id());
        }
        return builder.toString();
    }

    /** The kind with this id, whatever its case ("fov" also means field of view), or null when there is none */
    public static @Nullable KeyframeKind find(@Nullable String id) {
        if (id == null) {
            return null;
        }
        String wanted = id.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (wanted.equals("fov")) {
            return FIELD_OF_VIEW;
        }
        if (wanted.equals("pov")) {
            return POINT_OF_VIEW;
        }
        for (KeyframeKind kind : values()) {
            if (kind.id().equals(wanted)) {
                return kind;
            }
        }
        return null;
    }

    /** Like {@link #find}, but an unknown id is an error that lists the allowed ids */
    public static KeyframeKind parse(@Nullable String id) {
        KeyframeKind kind = find(id);
        if (kind == null) {
            throw new IllegalArgumentException("'type' must be one of " + allowed() + ", got \"" + id + "\"");
        }
        return kind;
    }

}
