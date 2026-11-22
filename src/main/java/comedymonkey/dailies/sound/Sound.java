package comedymonkey.dailies.sound;

import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.PlaybackExtensions;

/**
 * Where the mod's sound features start: recorded voice chat heard again in playback (specification PLAY-16) and,
 * through {@link ExportSound}, sound in exported videos (EXP-3). Capturing voice chat needs no start here: Simple
 * Voice Chat finds {@link VoiceChatPlugin} by itself when it is installed (REC-18).
 */
public final class Sound {

    private Sound() {
    }

    public static void init() {
        PlaybackExtensions.register(VoiceFormat.ID, VoicePlayback.INSTANCE);
        Playback.onClosing(VoicePlayback.INSTANCE::reset);
    }

}
