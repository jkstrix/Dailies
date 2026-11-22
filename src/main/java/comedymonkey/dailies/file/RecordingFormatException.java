package comedymonkey.dailies.file;

import java.io.IOException;

/**
 * A recording file that cannot be read, or a part of one. The message is written for the person at the screen.
 */
public class RecordingFormatException extends IOException {

    public enum Reason {
        /** The file is something else */
        NOT_A_RECORDING,
        /** Made by a newer version of the mod than this one */
        NEWER_FORMAT,
        /** A part of the file fails its checksum or is cut short */
        DAMAGED
    }

    private final Reason reason;

    public RecordingFormatException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return this.reason;
    }

}
