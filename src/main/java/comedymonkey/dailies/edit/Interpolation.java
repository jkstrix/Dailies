package comedymonkey.dailies.edit;

import java.util.Locale;

/**
 * How a value travels from one keyframe to the next. The mode set on the EARLIER keyframe of a pair decides
 * (specification EDIT-4).
 */
public enum Interpolation {

    /** Stays at the earlier keyframe's value, then jumps */
    HOLD,
    LINEAR,
    /** Starts slowly */
    EASE_IN,
    /** Ends slowly */
    EASE_OUT,
    /** Starts and ends slowly */
    EASE_IN_OUT,
    /**
     * A curve through the neighbouring keyframes as well that never leaves the range of the two keyframes it joins (a
     * monotone curve), see {@link Evaluator}
     */
    SMOOTH,
    /**
     * A looser curve through the neighbouring keyframes (Catmull-Rom): it keeps its speed through a keyframe, and so
     * may swing a little past one before it turns, see {@link Evaluator}
     */
    HERMITE;

    /** The name used in JSON and by the MCP tools, for example "ease_in_out" */
    public String id() {
        return this.name().toLowerCase(Locale.ROOT);
    }

    /**
     * How far along the value is (0 to 1) when the time is the given fraction (0 to 1) of the way to the next keyframe.
     * {@link #SMOOTH} and {@link #HERMITE} need the neighbouring keyframes, so here they only give a straight line.
     */
    public double ease(double fraction) {
        double t = Math.max(0.0, Math.min(1.0, fraction));
        return switch (this) {
            case HOLD -> t >= 1.0 ? 1.0 : 0.0;
            case LINEAR, SMOOTH, HERMITE -> t;
            case EASE_IN -> t * t;
            case EASE_OUT -> 1.0 - (1.0 - t) * (1.0 - t);
            case EASE_IN_OUT -> t * t * (3.0 - 2.0 * t);
        };
    }

    /** Whether the value follows a curve through the neighbouring keyframes too, not just the two it joins */
    public boolean curve() {
        return this == SMOOTH || this == HERMITE;
    }

    /** Every id, comma separated, for error messages */
    public static String allowed() {
        StringBuilder builder = new StringBuilder();
        for (Interpolation mode : values()) {
            if (!builder.isEmpty()) {
                builder.append(", ");
            }
            builder.append(mode.id());
        }
        return builder.toString();
    }

    /** Reads an id whatever its case. An unknown one is an error that lists the allowed ids */
    public static Interpolation parse(String id) {
        if (id != null) {
            String wanted = id.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            for (Interpolation mode : values()) {
                if (mode.id().equals(wanted)) {
                    return mode;
                }
            }
        }
        throw new IllegalArgumentException("'interpolation' must be one of " + allowed() + ", got \"" + id + "\"");
    }

}
