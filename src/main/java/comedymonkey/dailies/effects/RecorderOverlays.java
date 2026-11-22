package comedymonkey.dailies.effects;

/** What the overlay and lightmap mixins ask of {@link RecorderEyes} that is kept inside its package */
public final class RecorderOverlays {

    private RecorderOverlays() {
    }

    /** The recorder's portal intensity at this moment between ticks, when the view is through their eyes */
    public static float portal(float partialTicks) {
        return RecorderEyes.portal(partialTicks);
    }

    public static void failOnce(String what, Throwable t) {
        RecorderEyes.failOnce(what, t);
    }
}
