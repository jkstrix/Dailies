package comedymonkey.dailies.api.compat;

import comedymonkey.dailies.windows.Compats;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Where a compat registers, and what it may ask of Dailies. Only call it when Dailies is loaded
 * ({@code ModList.get().isLoaded("dailies")}): see the package description.
 */
public final class DailiesCompats {

    private DailiesCompats() {
    }

    /**
     * Registers a compat and takes its claims. Call it while the mod loads, from the client side.
     *
     * @throws IllegalArgumentException when the id is not namespaced, is in a namespace of Dailies' own, is taken, or the
     *                                  version is below 1, or a claim's name is not one a record can carry
     */
    public static void register(DailiesCompat compat) {
        Compats.register(compat);
    }

    /**
     * In playback, the recorder as the playback world shows them: their held items, armour and the rest, as the recording
     * holds them. Null outside playback, or while the recorder is not in the world. Client thread
     */
    public static @Nullable Player recorder() {
        return Compats.recorder();
    }

    /**
     * Whether overlays are drawn at this moment in playback: the viewer rides along with the recorder in first person and
     * the scene's recorder's HUD look is on. {@link OverlayPart#draw} is only called while they are; this is for the part
     * of an overlay a compat draws into the world rather than over it (an outline on a block, from the compat's own
     * drawing of the world), so that part follows the same rule. False outside playback. Client thread. Added after
     * the first API jar of 1.1.0 (Post Rider's copy has it not), so a compat that calls it catches the
     * {@code LinkageError} a Dailies without it throws.
     */
    public static boolean overlaysShown() {
        return Compats.overlaysShown();
    }

    /**
     * Stores bytes once in the recording being made, for a state to name by the handle returned instead of carrying them:
     * a state is written again in every snapshot, so what is large and seldom changes (a schematic's blocks, say) goes
     * here. The same bytes stored again give the same handle and are stored once. The recording keeps them itself,
     * packed, so it needs nothing from the disk they came from; they come along when it is trimmed or combined, and
     * playback finds them by the handle wherever it jumps to ({@link #stored}). Outside a recording nothing is stored,
     * and the handle is still returned. Client thread. Added after the first API jar of 1.1.0 (Post Rider's copy has it
     * not), so a compat that calls it catches the {@code LinkageError} a Dailies without it throws.
     *
     * @param data at most 16 MiB; only read, and copied before this returns
     * @return the handle: the SHA-256 of the bytes, as 64 lower-case hex digits
     * @throws IllegalArgumentException when the bytes are more than 16 MiB
     */
    public static String storeOnce(byte[] data) {
        return Compats.storeOnce(data);
    }

    /**
     * In playback, bytes a compat stored with {@link #storeOnce}, by their handle; null outside playback, or when the
     * recording open holds no such bytes of a compat's. Client thread. Added with {@link #storeOnce}.
     */
    public static byte @Nullable [] stored(String handle) {
        return Compats.stored(handle);
    }

    /**
     * Asks the server's plugin of this id, through the Dailies Companion, for what it has for this player; the answer
     * arrives at the {@link ServerHalf} registered for the id. Does nothing in playback or on a server without the
     * companion. Client thread
     *
     * @param version the asking client half's version, which the plugin is told
     * @return whether the question was sent
     */
    public static boolean askServer(String pluginId, int version) {
        return Compats.askServer(pluginId, version);
    }

    /** The version of a plugin on the server the game is joined to; 0 when it has none, or has not said yet */
    public static int serverVersion(String pluginId) {
        return Compats.serverVersion(pluginId);
    }

    /** In playback, the version of a plugin on the server the recording was made on; 0 when it had none */
    public static int recordedVersion(String pluginId) {
        return Compats.recordedVersion(pluginId);
    }

}
