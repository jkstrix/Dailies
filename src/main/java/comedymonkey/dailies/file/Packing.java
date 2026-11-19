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
    /**
     * The most deflate can shrink data by. A length that says it unpacks to more than this many times the packed bytes
     * cannot be true, so nothing is made for it
     */
    private static final long MOST_SHRUNK = 1032;

    // One of each per thread, used again for every block: the recording's writer packs and names thousands of blobs,
    // and making a new compressor, digest and buffer for each was a good part of what it cost
    private static final ThreadLocal<Deflater> DEFLATER = ThreadLocal.withInitial(() -> new Deflater(LEVEL));
    private static final ThreadLocal<MessageDigest> DIGEST = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    });
    private static final ThreadLocal<byte[]> BUFFER = ThreadLocal.withInitial(() -> new byte[64 * 1024]);
    // Playback unpacks a chunk's sections, light and the rest one small blob at a time, dozens to a chunk, so the
    // decompressor is kept per thread too rather than made and ended for every one
    private static final ThreadLocal<Inflater> INFLATER = ThreadLocal.withInitial(Inflater::new);

    private Packing() {
    }

    /** @param prefix bytes to put in front of the packed data, uncompressed (may be empty) */
    static byte[] pack(byte[] prefix, byte[] data, int from, int length) {
        Deflater deflater = DEFLATER.get();
        try {
            deflater.setInput(data, from, length);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(prefix.length + 4 + Math.max(64, length / 3));
            out.write(prefix, 0, prefix.length);
            out.write(ByteBuffer.allocate(4).putInt(length).array(), 0, 4);
            byte[] buffer = BUFFER.get();
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            return out.toByteArray();
        } finally {
            // Ready for the next block, and holding none of this one
            deflater.reset();
        }
    }

    /**
     * @param from where the packed data starts in the payload, after any prefix
     * @throws RecordingFormatException when the payload is not packed data, or says it unpacks to more than it can
     */
    static byte[] unpack(byte[] payload, int from) throws RecordingFormatException {
        if (from < 0 || from > payload.length) {
            throw damaged();
        }
        BoundedReader in = new BoundedReader(payload, from, payload.length - from);
        int length = BoundedReader.checkLength(in.readInt(), in.remaining() * MOST_SHRUNK + 64);
        byte[] data = new byte[length];
        Inflater inflater = INFLATER.get();
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
            // Ready for the next block, and holding none of this one
            inflater.reset();
        }
    }

    /** The SHA-256 of the data, which is what shared data is named by */
    static byte[] hash(byte[] data) {
        // digest() leaves it ready for the next
        return DIGEST.get().digest(data);
    }

    /** The SHA-256 of a stretch of an array, the same as of a copy of just that stretch */
    static byte[] hash(byte[] data, int from, int length) {
        MessageDigest digest = DIGEST.get();
        digest.update(data, from, length);
        return digest.digest();
    }

    private static RecordingFormatException damaged() {
        return new RecordingFormatException(RecordingFormatException.Reason.DAMAGED, "A block of the recording cannot be unpacked");
    }

}
