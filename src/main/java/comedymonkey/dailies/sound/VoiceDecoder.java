package comedymonkey.dailies.sound;

/**
 * Turns recorded Opus frames of one speaker back into samples. The only decoder there is comes from Simple Voice
 * Chat; this interface keeps playback free of that mod's classes, so playback loads whether or not it is installed.
 */
interface VoiceDecoder {

    /** 48 kHz mono samples of one frame */
    short[] decode(byte[] opusFrame);

    /** Forget what came before: the next frame starts a new stretch of speech */
    void reset();

    void close();

}
