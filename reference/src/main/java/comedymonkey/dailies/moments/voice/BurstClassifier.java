package comedymonkey.dailies.moments.voice;

import org.jetbrains.annotations.Nullable;

/**
 * The sound classifier of plan 2.9: something that listens to a burst and says what it was. The one plugged in is
 * {@link comedymonkey.dailies.moments.voice.yamnet.YamnetClassifier}; a burst it cannot name keeps the subject
 * {@link #UNCLASSIFIED}.
 * <p>
 * An implementation runs on the player's own computer, with no server. It is called on a worker thread of its own,
 * with a few seconds of audio around a burst or a stretch the transcription filter found to be no words, only while
 * momentsBurstLabels is on, and never during playback or an export. Install one with
 * {@code Moments.setBurstClassifier}.
 */
@FunctionalInterface
public interface BurstClassifier {

    String UNCLASSIFIED = "unclassified";

    /** Says nothing about anything */
    BurstClassifier NONE = (pcm, sampleRate) -> null;

    /**
     * @param pcm mono 16-bit audio of one speaker
     * @return laughter, cheering, screaming or shouting; null when it cannot tell
     */
    @Nullable String classify(short[] pcm, int sampleRate);

}
