package comedymonkey.dailies.api.internal;

import comedymonkey.dailies.api.compat.DailiesCompat;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Not for other mods: how {@link comedymonkey.dailies.api.DailiesApi} and
 * {@link comedymonkey.dailies.api.compat.DailiesCompats} reach Dailies' own workings, so that the API compiles without
 * them and its jar builds on its own. Dailies provides the one implementation, {@value #IMPLEMENTATION}, which is loaded
 * the first time the API is called, as the classes it stands for were before. Each method means what the API method of
 * the same name says.
 */
public interface Backend {

    /** The class that implements this, in Dailies' own jar */
    String IMPLEMENTATION = "comedymonkey.dailies.core.ApiBackend";

    /** The implementation, loaded on first use */
    static Backend get() {
        return Loaded.BACKEND;
    }

    boolean isPlayback();

    void register(DailiesCompat compat);

    @Nullable Player recorder();

    boolean overlaysShown();

    String storeOnce(byte[] data);

    byte @Nullable [] stored(String handle);

    boolean askServer(String pluginId, int version);

    int serverVersion(String pluginId);

    int recordedVersion(String pluginId);

}
