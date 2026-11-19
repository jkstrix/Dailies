package comedymonkey.dailies.api.compat;

import org.jetbrains.annotations.Nullable;

/**
 * A compat: what one mod's screens and overlays need to be recorded and drawn again. Implemented by a class of the mod's
 * own and handed to {@link DailiesCompats#register} while the mod loads. See the package description for the whole
 * picture.
 */
public interface DailiesCompat {

    /**
     * A namespaced id, unique among compats, such as {@code postrider:screens}. Records are written under it, and a
     * recording lists the compats that wrote into it by it, so it never changes once recordings carry it. The
     * {@code dailies} and {@code minecraft} namespaces are Dailies' own.
     */
    String id();

    /**
     * The version of what this compat records, 1 or more. Every record carries it, and playback hands it back with each
     * state, so a later version can still draw what an earlier one wrote. Raise it whenever a state's bytes change shape.
     */
    int version();

    /** Says what this compat records and draws. Called once, from {@link DailiesCompats#register} */
    void claim(Claims claims);

    /**
     * The compat's display test, for Dailies' scripted run: its screens opened while recording, then checked to be
     * drawn in first-person playback and not in third person. Null when it has none
     */
    default @Nullable DisplayTest displayTest() {
        return null;
    }

}
