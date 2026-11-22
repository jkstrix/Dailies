package comedymonkey.dailies.api;

import comedymonkey.dailies.api.internal.Backend;

/**
 * What other mods may rely on. This class and its methods keep their names and meaning from version to version.
 * <p>
 * A recording is watched in a real client world that has no server behind it. A mod whose client code starts work
 * when a world is joined (maps, helpers, anything that talks to its server half) should ask {@link #isPlayback()}
 * first and stand down when it answers true. The check needs no Dailies classes at compile time:
 * <pre>
 * boolean playback = false;
 * try {
 *     playback = (boolean) Class.forName("comedymonkey.dailies.api.DailiesApi").getMethod("isPlayback").invoke(null);
 * } catch (ReflectiveOperationException absent) {
 *     // Dailies is not installed
 * }
 * </pre>
 */
public final class DailiesApi {

    private DailiesApi() {
    }

    /**
     * Whether the world the game is in, or is about to enter, is a recording being played back and not a live world.
     * True from before the playback world is created until after it has been left. Safe from any thread.
     */
    public static boolean isPlayback() {
        return Backend.get().isPlayback();
    }

}
