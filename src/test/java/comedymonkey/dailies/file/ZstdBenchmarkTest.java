package comedymonkey.dailies.file;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdDictDecompress;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Measurement only: how Zstandard would do against the deflate (level 3) that {@link Packing} gives every block of a
 * recording, on real recordings. Nothing here changes how the mod packs anything. Each block is packed by itself, as
 * the recording packs it, and the two kinds of block are weighed apart: shared blobs (chunk data, mostly) and segment
 * parts (the stream of small packets, 40 ticks a part).
 * <p>
 * The recordings are -Pdailies.bench.recordings=a.dailies,b.dailies (comma-separated), by default the two long fixtures
 * of the modded test game; the test skips when none of them is there. The table goes to build/reports/zstd-benchmark.md.
 * Run with gradlew benchmarks.
 */
@Tag("benchmark")
class ZstdBenchmarkTest {

    private static final String DEFAULT_RECORDINGS = "E:/Youtube/Dailies/run-modded/dailies/fixtures/realworld.dailies,"
        + "E:/Youtube/Dailies/run-modded/dailies/fixtures/camera-route.dailies";
    private static final double MB = 1_000_000.0;
    /** How much raw data each method packs and unpacks before it is timed */
    private static final long WARM_UP_BYTES = 16L * 1024 * 1024;
    /** Dictionaries are trained on about this many times their size of sample data, as zstd advises */
    private static final int SAMPLE_TIMES_DICTIONARY = 100;

    /** One recording's blocks, unpacked, by kind */
    private record Corpus(String name, List<byte[]> blobs, List<byte[]> parts, List<byte[]> pieces, long storedBlobBytes, long storedPartBytes) {
        List<byte[]> of(Kind kind) {
            return switch (kind) {
                case BLOBS -> this.blobs;
                case PARTS -> this.parts;
                case PIECES -> this.pieces;
            };
        }

        /** What the recording holds for the kind, packed; -1 for the pieces, which no recording on disk holds yet */
        long stored(Kind kind) {
            return switch (kind) {
                case BLOBS -> this.storedBlobBytes;
                case PARTS -> this.storedPartBytes;
                case PIECES -> -1;
            };
        }
    }

    private enum Kind {
        BLOBS("Shared blobs, as recordings stored them until section storage (whole chunks)"),
        PARTS("Segment parts"),
        /**
         * The same shared blobs cut as section storage cuts them (capture.ChunkPieces), each distinct piece once: what a
         * recording in the new format stores for the same play, measured without the game
         */
        PIECES("Section pieces: the same chunks stored by section, each distinct piece once (the new format)");

        final String title;

        Kind(String title) {
            this.title = title;
        }
    }

    /** One way of packing a block by itself. Not thread safe: one of each per run, as the mod has one per thread */
    private interface Codec extends AutoCloseable {
        byte[] pack(byte[] data);

        byte[] unpack(byte[] packed, int length);

        @Override
        default void close() {
        }
    }

    private static final class DeflateCodec implements Codec {
        private final Deflater deflater;
        private final Inflater inflater = new Inflater();
        private final byte[] buffer = new byte[64 * 1024];

        DeflateCodec(int level) {
            this.deflater = new Deflater(level);
        }

        @Override
        public byte[] pack(byte[] data) {
            try {
                this.deflater.setInput(data);
                this.deflater.finish();
                byte[] out = new byte[Math.max(64, data.length / 3)];
                int size = 0;
                while (!this.deflater.finished()) {
                    int made = this.deflater.deflate(this.buffer);
                    if (size + made > out.length) {
                        out = Arrays.copyOf(out, Math.max(out.length * 2, size + made));
                    }
                    System.arraycopy(this.buffer, 0, out, size, made);
                    size += made;
                }
                return Arrays.copyOf(out, size);
            } finally {
                this.deflater.reset();
            }
        }

        @Override
        public void close() {
            this.deflater.end();
            this.inflater.end();
        }

        @Override
        public byte[] unpack(byte[] packed, int length) {
            try {
                this.inflater.setInput(packed);
                byte[] data = new byte[length];
                int done = 0;
                while (done < length) {
                    int read = this.inflater.inflate(data, done, length - done);
                    if (read == 0 && (this.inflater.finished() || this.inflater.needsInput())) {
                        throw new IllegalStateException("Deflate data cut short");
                    }
                    done += read;
                }
                return data;
            } catch (DataFormatException e) {
                throw new IllegalStateException(e);
            } finally {
                this.inflater.reset();
            }
        }
    }

    /** zstd-jni. The length is kept outside the frame, as Packing keeps it, so the frame leaves out its own and any dictionary id */
    private static final class ZstdCodec implements Codec {
        private final ZstdCompressCtx packer = new ZstdCompressCtx();
        private final ZstdDecompressCtx unpacker = new ZstdDecompressCtx();
        private ZstdDictCompress packDictionary = null;
        private ZstdDictDecompress unpackDictionary = null;

        ZstdCodec(int level, byte[] dictionary) {
            this.packer.setLevel(level);
            this.packer.setContentSize(false);
            this.packer.setDictID(false);
            this.packer.setChecksum(false);
            if (dictionary != null) {
                this.packDictionary = new ZstdDictCompress(dictionary, level);
                this.unpackDictionary = new ZstdDictDecompress(dictionary);
                this.packer.loadDict(this.packDictionary);
                this.unpacker.loadDict(this.unpackDictionary);
            }
        }

        @Override
        public void close() {
            this.packer.close();
            this.unpacker.close();
            if (this.packDictionary != null) {
                this.packDictionary.close();
                this.unpackDictionary.close();
            }
        }

        @Override
        public byte[] pack(byte[] data) {
            return this.packer.compress(data);
        }

        @Override
        public byte[] unpack(byte[] packed, int length) {
            return this.unpacker.decompress(packed, length);
        }
    }

    /** aircompressor's pure-Java zstd, which has one level (3) and no dictionaries */
    private static final class AirCodec implements Codec {
        private final io.airlift.compress.zstd.ZstdCompressor packer = new io.airlift.compress.zstd.ZstdCompressor();
        private final io.airlift.compress.zstd.ZstdDecompressor unpacker = new io.airlift.compress.zstd.ZstdDecompressor();
        private byte[] buffer = new byte[0];

        @Override
        public byte[] pack(byte[] data) {
            int most = this.packer.maxCompressedLength(data.length);
            if (this.buffer.length < most) {
                this.buffer = new byte[most];
            }
            int size = this.packer.compress(data, 0, data.length, this.buffer, 0, this.buffer.length);
            return Arrays.copyOf(this.buffer, size);
        }

        @Override
        public byte[] unpack(byte[] packed, int length) {
            byte[] data = new byte[length];
            int size = this.unpacker.decompress(packed, 0, packed.length, data, 0, length);
            if (size != length) {
                throw new IllegalStateException("aircompressor unpacked " + size + " bytes, not " + length);
            }
            return data;
        }
    }

    /** One method's result for one kind of block, over every recording */
    private static final class Result {
        final String method;
        final Map<String, Long> sizes = new LinkedHashMap<>();
        long raw = 0;
        long packed = 0;
        long packNanos = 0;
        long unpackNanos = 0;

        Result(String method) {
            this.method = method;
        }
    }

    @Test
    void zstdAgainstDeflate() throws IOException {
        List<Path> files = new ArrayList<>();
        for (String name : System.getProperty("dailies.bench.recordings", DEFAULT_RECORDINGS).split(",")) {
            Path file = Path.of(name.trim());
            if (!name.isBlank() && Files.isRegularFile(file)) {
                files.add(file);
            }
        }
        Assumptions.assumeFalse(files.isEmpty(), "None of the recordings in dailies.bench.recordings is there");

        List<Corpus> corpora = new ArrayList<>();
        for (Path file : files) {
            corpora.add(load(file));
        }

        StringBuilder report = new StringBuilder();
        report.append("# Zstandard against deflate, on real recordings\n\n");
        report.append("Each block packed by itself, as recordings pack them. Sizes are of the packed data alone (the 4-byte length "
            + "and a shared blob's 32-byte name are the same for every method). MB is 10^6 bytes; speeds are MB of raw data a "
            + "second on one thread, after a warm-up. \"% of D3\" is the size against deflate level 3, what recordings use now.\n\n");
        report.append(String.format(Locale.ROOT, "Java %s, %s, %d processors.%n%n", System.getProperty("java.version"),
            System.getProperty("os.name"), Runtime.getRuntime().availableProcessors()));
        report.append("Recordings:\n");
        for (Corpus corpus : corpora) {
            report.append(String.format(Locale.ROOT, "- %s: %d shared blobs (%.1f MB raw), %d segment parts (%.1f MB raw); cut by section: %d distinct pieces (%.1f MB raw)%n", corpus.name,
                corpus.blobs.size(), total(corpus.blobs) / MB, corpus.parts.size(), total(corpus.parts) / MB, corpus.pieces.size(), total(corpus.pieces) / MB));
        }
        report.append('\n');

        for (Kind kind : Kind.values()) {
            report.append(this.measure(kind, corpora));
        }

        String text = report.toString();
        Path out = Path.of("build", "reports", "zstd-benchmark.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, text);
        System.out.println(text);
    }

    private String measure(Kind kind, List<Corpus> corpora) {
        List<Result> results = new ArrayList<>();
        for (int level : new int[]{1, 3, 6, 9}) {
            final int l = level;
            results.add(run("Deflate " + level + (level == 3 ? " (now)" : ""), kind, corpora, corpus -> () -> new DeflateCodec(l)));
        }
        for (int level : new int[]{1, 3, 6, 9, 15, 19}) {
            final int l = level;
            results.add(run("zstd " + level, kind, corpora, corpus -> () -> new ZstdCodec(l, null)));
        }
        results.add(run("aircompressor zstd (3, pure Java)", kind, corpora, corpus -> AirCodec::new));

        // Dictionaries trained on each recording, used on that recording (which flatters them) and on the other ones
        for (int size : new int[]{64 * 1024, 112 * 1024}) {
            Map<String, byte[]> dictionaries = new LinkedHashMap<>();
            for (Corpus corpus : corpora) {
                dictionaries.put(corpus.name, train(corpus.of(kind), size));
            }
            for (int level : new int[]{3, 9}) {
                final int l = level;
                String label = "zstd " + level + " + " + size / 1024 + " KB dict";
                results.add(run(label + ", trained on same", kind, corpora, corpus -> () -> new ZstdCodec(l, dictionaries.get(corpus.name))));
                if (corpora.size() > 1) {
                    // Each recording is packed with the dictionary of the next one round
                    results.add(run(label + ", trained on other", kind, corpora, corpus -> {
                        int at = corpora.indexOf(corpus);
                        byte[] other = dictionaries.get(corpora.get((at + 1) % corpora.size()).name);
                        return () -> new ZstdCodec(l, other);
                    }));
                }
            }
        }

        Result deflate3 = results.get(1);
        // Deflate 3 here is what the recording holds: the same zlib at the same level gives the same bytes
        if (kind != Kind.PIECES) {
            long stored = corpora.stream().mapToLong(corpus -> corpus.stored(kind)).sum();
            assertEquals(stored, deflate3.packed, "Deflate 3 should give exactly what the recordings hold");
        }

        StringBuilder table = new StringBuilder();
        long raw = deflate3.raw;
        table.append(String.format(Locale.ROOT, "## %s: %.1f MB raw, %d blocks%n%n", kind.title, raw / MB,
            corpora.stream().mapToInt(corpus -> corpus.of(kind).size()).sum()));
        table.append("| Method | Total MB | % of D3 |");
        for (Corpus corpus : corpora) {
            table.append(' ').append(corpus.name).append(" MB |");
        }
        table.append(" Pack MB/s | Unpack MB/s |\n|---|---:|---:|");
        table.append("---:|".repeat(corpora.size()));
        table.append("---:|---:|\n");
        for (Result result : results) {
            table.append(String.format(Locale.ROOT, "| %s | %.2f | %.1f%% |", result.method, result.packed / MB, 100.0 * result.packed / deflate3.packed));
            for (Corpus corpus : corpora) {
                table.append(String.format(Locale.ROOT, " %.2f |", result.sizes.get(corpus.name) / MB));
            }
            table.append(String.format(Locale.ROOT, " %.0f | %.0f |%n", result.raw / MB / (result.packNanos / 1e9), result.raw / MB / (result.unpackNanos / 1e9)));
        }
        table.append('\n');
        System.out.print(table);
        return table.toString();
    }

    /** Packs every block of the kind, then unpacks it all, timing each, after a warm-up on the start of the first recording */
    private static Result run(String method, Kind kind, List<Corpus> corpora, java.util.function.Function<Corpus, Supplier<Codec>> codecs) {
        Result result = new Result(method);
        try (Codec warm = codecs.apply(corpora.get(0)).get()) {
            long warmed = 0;
            for (int round = 0; round < 2; round++) {
                for (byte[] block : corpora.get(0).of(kind)) {
                    if (warmed >= WARM_UP_BYTES * (round + 1)) {
                        break;
                    }
                    warm.unpack(warm.pack(block), block.length);
                    warmed += block.length;
                }
            }
        }
        for (Corpus corpus : corpora) {
            List<byte[]> blocks = corpus.of(kind);
            Codec codec = codecs.apply(corpus).get();
            byte[][] packed = new byte[blocks.size()][];
            long start = System.nanoTime();
            for (int i = 0; i < packed.length; i++) {
                packed[i] = codec.pack(blocks.get(i));
            }
            result.packNanos += System.nanoTime() - start;
            byte[][] unpacked = new byte[blocks.size()][];
            start = System.nanoTime();
            for (int i = 0; i < packed.length; i++) {
                unpacked[i] = codec.unpack(packed[i], blocks.get(i).length);
            }
            result.unpackNanos += System.nanoTime() - start;
            codec.close();
            long size = 0;
            for (int i = 0; i < packed.length; i++) {
                assertArrayEquals(blocks.get(i), unpacked[i], method + " did not give back what it packed");
                size += packed[i].length;
            }
            result.sizes.put(corpus.name, size);
            result.packed += size;
            result.raw += total(blocks);
        }
        System.out.printf(Locale.ROOT, "  %s, %s: %.2f MB in %.1f s%n", kind.title, method, result.packed / MB, (result.packNanos + result.unpackNanos) / 1e9);
        return result;
    }

    /** A dictionary of about the size given, from blocks spread evenly through the recording */
    private static byte[] train(List<byte[]> blocks, int size) {
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

    private static Corpus load(Path file) throws IOException {
        List<byte[]> blobs = new ArrayList<>();
        List<byte[]> parts = new ArrayList<>();
        long storedBlobs = 0;
        long storedParts = 0;
        try (RecordingFile recording = RecordingFile.open(file)) {
            Container container = recording.container();
            for (Container.Entry entry : container.shared.values()) {
                blobs.add(Packing.unpack(container.read(entry), Container.HASH_BYTES));
                storedBlobs += entry.length() - Container.HASH_BYTES - 4;
            }
            for (Container.Entry entry : container.parts) {
                parts.add(Packing.unpack(container.read(entry), 0));
                storedParts += entry.length() - 4;
            }
        }
        String name = file.getFileName().toString().replaceFirst("\\.dailies$", "");
        return new Corpus(name, blobs, parts, pieces(blobs), storedBlobs, storedParts);
    }

    /**
     * The shared blobs cut as the recorder now cuts chunks and light (a chunk is tried first, then a light packet; what
     * is neither stays whole), with every distinct piece, part or list, kept once by its name, as the store keeps them
     */
    private static List<byte[]> pieces(List<byte[]> blobs) {
        Map<String, byte[]> distinct = new LinkedHashMap<>();
        for (byte[] blob : blobs) {
            comedymonkey.dailies.file.PacketPieces.Split split = comedymonkey.dailies.capture.ChunkPieces.split(blob, comedymonkey.dailies.capture.ChunkPieces.Shape.CHUNK);
            if (split.blobs().size() <= 1) {
                split = comedymonkey.dailies.capture.ChunkPieces.split(blob, comedymonkey.dailies.capture.ChunkPieces.Shape.LIGHT);
            }
            for (comedymonkey.dailies.file.PacketPieces.Blob piece : split.blobs()) {
                distinct.putIfAbsent(piece.hex(), piece.copy());
            }
        }
        return new ArrayList<>(distinct.values());
    }

    private static long total(List<byte[]> blocks) {
        long sum = 0;
        for (byte[] block : blocks) {
            sum += block.length;
        }
        return sum;
    }

}
