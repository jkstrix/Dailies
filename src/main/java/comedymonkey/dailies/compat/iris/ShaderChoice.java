package comedymonkey.dailies.compat.iris;

import comedymonkey.dailies.edit.Looks;
import org.jetbrains.annotations.Nullable;

/**
 * When the "shaders" look switches Iris's shaders, and what it puts back. Switching makes Iris reload its shader pack,
 * which takes a while, so it is only done when the scene wants something other than what Iris has. The viewer's own
 * setting is remembered the first time the look changes it and given back once the scene leaves it to the game again,
 * or the recording closes, or the game does; until then it is kept however many times the scene changes its mind.
 * <p>
 * Plain Java, so the rules can be tested without the game.
 */
public final class ShaderChoice {

    /** The viewer's own setting while the look has changed it, else null */
    private @Nullable Boolean own = null;

    /**
     * What Iris's shaders should be switched to now, or null to leave them as they are.
     *
     * @param wanted  the scene's choice, or null when no scene is being shown
     * @param current whether Iris's shaders are on now
     */
    public @Nullable Boolean step(@Nullable Looks.Shaders wanted, boolean current) {
        if (wanted == null || wanted == Looks.Shaders.AS_GAME) {
            return this.restore(current);
        }
        boolean want = wanted == Looks.Shaders.ON;
        if (want == current) {
            return null;
        }
        if (this.own == null) {
            this.own = current;
        }
        return want;
    }

    /** What to switch back to for the viewer's own setting, or null when nothing was changed or it is already so */
    public @Nullable Boolean restore(boolean current) {
        Boolean back = this.own;
        this.own = null;
        return back == null || back == current ? null : back;
    }

    /** The viewer's own setting while the look has changed it, else null */
    public @Nullable Boolean own() {
        return this.own;
    }

}
