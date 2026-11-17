package comedymonkey.dailies.file;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Compression and content hashing for the blocks of a recording. Packed data is the original length as four bytes,
 * then the data deflated.
 */
final class Packing {

    // Fast rather than small: recordings are written while the game is being played
    private static final int LEVEL = 3;

    private Packing() {
    }

    /** @param prefix bytes to put in front of the packed data, uncompressed (may be empty) */
    static byte[] pack(byte[] prefix, byte[] data, int from, int length) {
        Deflater deflater = new Deflater(LEVEL);
        try {
            deflater.setInput(data, from, length);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(prefix.length + 4 + Math.max(64, length / 3));
            out.write(prefix, 0, prefix.length);
            out.write(ByteBuffer.allocate(4).putInt(length).array(), 0, 4);
            byte[] buffer = new byte[64 * 1024];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /** @param from where the packed data starts in the payload, after any prefix */
    static byte[] unpack(byte[] payload, int from) throws RecordingFormatException {
        if (payload.length < from + 4) {
            throw damaged();
        }
        int length = ByteBuffer.wrap(payload, from, 4).getInt();
        if (length < 0) {
            throw damaged();
        }
        byte[] data = new byte[length];
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(payload, from + 4, payload.length - from - 4);
            int done = 0;
            while (done < length) {
                int read = inflater.inflate(data, done, length - done);
                if (read == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) {
                    throw damaged();
                }
                done += read;
            }
            return data;
        } catch (DataFormatException e) {
            throw damaged();
        } finally {
            inflater.end();
        }
    }

    static byte[] hash(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RecordingFormatException damaged() {
        return new RecordingFormatException(RecordingFormatException.Reason.DAMAGED, "A block of the recording cannot be unpacked");
    }

}
