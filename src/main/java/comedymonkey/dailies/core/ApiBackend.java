package comedymonkey.dailies.core;

import comedymonkey.dailies.api.compat.DailiesCompat;
import comedymonkey.dailies.api.internal.Backend;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.windows.Compats;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/** What the API calls through: Dailies' own workings behind {@link Backend}. Loaded by name, so it keeps its name */
public final class ApiBackend implements Backend {

    public ApiBackend() {
    }

    @Override
    public boolean isPlayback() {
        return Playback.active();
    }

    @Override
    public void register(DailiesCompat compat) {
        Compats.register(compat);
    }

    @Override
    public @Nullable Player recorder() {
        return Compats.recorder();
    }

    @Override
    public boolean overlaysShown() {
        return Compats.overlaysShown();
    }

    @Override
    public String storeOnce(byte[] data) {
        return Compats.storeOnce(data);
    }

    @Override
    public byte @Nullable [] stored(String handle) {
        return Compats.stored(handle);
    }

    @Override
    public boolean askServer(String pluginId, int version) {
        return Compats.askServer(pluginId, version);
    }

    @Override
    public int serverVersion(String pluginId) {
        return Compats.serverVersion(pluginId);
    }

    @Override
    public int recordedVersion(String pluginId) {
        return Compats.recordedVersion(pluginId);
    }

}
