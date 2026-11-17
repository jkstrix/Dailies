package comedymonkey.dailies.moments.voice;

import org.jetbrains.annotations.Nullable;

/**
 * The place for the sound classifier of plan 2.9: something that listens to a burst and says what it was. Nothing is
 * plugged in yet, because the classifier needs a bundled model whose licence the owner has to approve first; until
 * then every burst keeps the subject {@link #UNCLASSIFIED}.
 * <p>
 * An implementation runs on the player's own computer, with no server, and must be quick: it is called on the voice
 * thread with the voice segment a burst fell in, and only while momentsAiEnabled is on. Install one with
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
