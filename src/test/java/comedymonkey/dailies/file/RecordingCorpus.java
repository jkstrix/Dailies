package comedymonkey.dailies.file;

import com.github.luben.zstd.Zstd;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * The blocks of real recordings, unpacked, for the compression benchmark and the training of the dictionaries. Reads
 * recordings as they are made now, and those of format 1, whose blocks were deflated and whose shared data was whole
 * chunk packets: the fixtures of the modded test game stay in that format until they are next recorded.
 */
final class RecordingCorpus {

    /** The two long fixtures of the modded test game, read only, from the project folder the tests run in */
    static final String DEFAULT_RECORDINGS = "run-modded/dailies/fixtures/realworld.dailies,"
        + "run-modded/dailies/fixtures/camera-route.dailies";

    /** Dictionaries are trained on about this many times their size of sample data, as zstd advises */
    static final int SAMPLE_TIMES_DICTIONARY = 100;

    /**
     * One recording's blocks, unpacked.
     *
     * @param blobs           its shared data as stored
     * @param pieces          the shared data cut by section as the recorder cuts it now, each distinct piece once (for a
     *                        recording made now, the shared data as it is)
     * @param storedBlobBytes what the shared data takes packed, without names and lengths
     * @param deflated        whether it is a format 1 recording, whose blocks were deflated
     */
    record Corpus(String name, List<byte[]> blobs, List<byte[]> parts, List<byte[]> pieces, long storedBlobBytes, long storedPartBytes, boolean deflated) {
    }

    private RecordingCorpus() {
    }

    /** The recordings named by -Pdailies.bench.recordings (comma-separated), by default the two long fixtures; those that are there */
    static List<Path> recordings() {
        List<Path> files = new ArrayList<>();
        for (String name : System.getProperty("dailies.bench.recordings", DEFAULT_RECORDINGS).split(",")) {
            Path file = Path.of(name.trim());
            if (!name.isBlank() && Files.isRegularFile(file)) {
                files.add(file);
            }
        }
        return files;
    }

    static Corpus load(Path file) throws IOException {
        String name = file.getFileName().toString().replaceFirst("\\.dailies$", "");
        if (version(file) == 1) {
            return loadDeflated(file, name);
        }
        List<byte[]> blobs = new ArrayList<>();
        List<byte[]> parts = new ArrayList<>();
        long storedBlobs = 0;
        long storedParts = 0;
        try (RecordingFile recording = RecordingFile.open(file)) {
            Container container = recording.container();
            for (Container.Entry entry : container.shared.values()) {
                blobs.add(container.unpackShared(container.read(entry)));
                storedBlobs += entry.length() - Container.HASH_BYTES - 4;
            }
            for (Container.Entry entry : container.parts) {
                parts.add(container.unpackPart(container.read(entry)));
                storedParts += entry.length() - 4;
            }
        }
        return new Corpus(name, blobs, parts, blobs, storedBlobs, storedParts, false);
    }

    private static int version(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer preamble = ByteBuffer.allocate(Container.PREAMBLE_BYTES);
            channel.read(preamble, 0);
            return preamble.getInt(Container.MAGIC.length);
        }
    }

    /**
     * A format 1 recording, read block by block from the start as the format had them (the same preamble and block
     * headers as now), keeping its shared blobs and segment parts. Stops at the first block that is not whole
     */
    private static Corpus loadDeflated(Path file, String name) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        List<byte[]> blobs = new ArrayList<>();
        List<byte[]> parts = new ArrayList<>();
        long storedBlobs = 0;
        long storedParts = 0;
        int position = Container.PREAMBLE_BYTES;
        while (position + Container.HEADER_BYTES <= bytes.length) {
            ByteBuffer header = ByteBuffer.wrap(bytes, position, Container.HEADER_BYTES);
            byte kind = header.get();
            header.getInt();
            header.getInt();
            header.getInt();
            int length = header.getInt();
            int stored = header.getInt();
            int payload = position + Container.HEADER_BYTES;
            if (length < 0 || payload + (long) length > bytes.length) {
                break;
            }
            CRC32 crc = new CRC32();
            crc.update(bytes, position, Container.HEADER_BYTES - 4);
            crc.update(bytes, payload, length);
            if ((int) crc.getValue() != stored) {
                break;
            }
            if (kind == Container.SHARED) {
                blobs.add(inflate(bytes, payload + Container.HASH_BYTES, length - Container.HASH_BYTES));
                storedBlobs += length - Container.HASH_BYTES - 4;
            } else if (kind == Container.SEGMENT_PART) {
                parts.add(inflate(bytes, payload, length));
                storedParts += length - 4;
            }
            position = payload + length;
        }
        return new Corpus(name, blobs, parts, pieces(blobs), storedBlobs, storedParts, true);
    }

    /** Format 1's packing: the length as four bytes, then the data deflated */
    private static byte[] inflate(byte[] bytes, int from, int length) throws IOException {
        int size = ByteBuffer.wrap(bytes, from, 4).getInt();
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(bytes, from + 4, length - 4);
            byte[] data = new byte[size];
            int done = 0;
            while (done < size) {
                int read = inflater.inflate(data, done, size - done);
                if (read == 0 && (inflater.finished() || inflater.needsInput())) {
                    throw new IOException("A deflated block is cut short");
                }
                done += read;
            }
            return data;
        } catch (DataFormatException e) {
            throw new IOException(e);
        } finally {
            inflater.end();
        }
    }

    /**
     * Whole chunk packets cut as the recorder now cuts chunks and light (a chunk is tried first, then a light packet;
     * what is neither stays whole), with every distinct piece, part or list, kept once by its name, as the store keeps them
     */
    static List<byte[]> pieces(List<byte[]> blobs) {
        Map<String, byte[]> distinct = new LinkedHashMap<>();
        for (byte[] blob : blobs) {
            PacketPieces.Split split = comedymonkey.dailies.capture.ChunkPieces.split(blob, comedymonkey.dailies.capture.ChunkPieces.Shape.CHUNK);
            if (split.blobs().size() <= 1) {
                split = comedymonkey.dailies.capture.ChunkPieces.split(blob, comedymonkey.dailies.capture.ChunkPieces.Shape.LIGHT);
            }
            for (PacketPieces.Blob piece : split.blobs()) {
                distinct.putIfAbsent(piece.hex(), piece.copy());
            }
        }
        return new ArrayList<>(distinct.values());
    }

    /** A dictionary of about the size given, from blocks spread evenly through the list */
    static byte[] train(List<byte[]> blocks, int size) {
        long want = (long) size * SAMPLE_TIMES_DICTIONARY;
        long all = total(blocks);
        int step = (int) Math.max(1, all / want);
        List<byte[]> samples = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i += step) {
            samples.add(blocks.get(i));
        }
        byte[] dictionary = new byte[size];
        long made = Zstd.trainFromBuffer(samples.toArray(new byte[0][]), dictionary);
        if (Zstd.isError(made)) {
            throw new IllegalStateException("Training a dictionary failed: " + Zstd.getErrorName(made));
        }
        return Arrays.copyOf(dictionary, (int) made);
    }

    static long total(List<byte[]> blocks) {
        long sum = 0;
        for (byte[] block : blocks) {
            sum += block.length;
        }
        return sum;
    }

}
