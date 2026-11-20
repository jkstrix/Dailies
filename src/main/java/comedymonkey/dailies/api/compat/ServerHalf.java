package comedymonkey.dailies.api.compat;

/**
 * The client half of a compat's server half: what to do with the data a server plugin sends this player through the
 * Dailies Companion's plugin API ({@code CompanionPlugins.push}, specification SRV-6). Registered with
 * {@link Claims#serverHalf}. Data arrives only from a server with the companion and the plugin; Dailies keeps each
 * arrival in the recording, with the last from each plugin in every snapshot, and hands it over again in playback.
 */
public interface ServerHalf {

    /** The plugin's data has arrived in live play. Client thread */
    default void received(int version, byte[] data) {
    }

    /** The same data, reached in playback; skipping is true during a jump. Client thread */
    default void played(int version, byte[] data, boolean skipping) {
    }

    /** Playback is about to rebuild the world from a snapshot, or has closed: forget what was played */
    default void reset() {
    }

}
