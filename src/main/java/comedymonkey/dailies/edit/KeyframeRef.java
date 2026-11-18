package comedymonkey.dailies.edit;

/**
 * Points at one keyframe of the current scene: the index of its track and the tick it sits on. A selection is a
 * collection of these.
 */
public record KeyframeRef(int track, int tick) {
}
