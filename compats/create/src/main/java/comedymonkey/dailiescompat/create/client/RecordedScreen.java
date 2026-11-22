package comedymonkey.dailiescompat.create.client;

/**
 * Marks a Create screen built by the compat for playback: drawn from a recording, never used. The compat's mixins look
 * for it to keep such a screen from doing what the live one does to the game (moving the player's own mouse pointer).
 */
public interface RecordedScreen {
}
