package comedymonkey.dailies.file;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Trains the two Zstandard dictionaries packed into the mod, which every new recording is packed with and carries
 * ({@link Packing.Dictionaries#current}): one on shared data cut into section pieces as the recorder cuts chunks, one on
 * segment parts. 64 KB each, from blocks spread evenly through real recordings, the same way the benchmark trains its
 * own (ZstdBenchmarkTest), which found one trained on another recording does as well as one trained on the same.
 * <p>
 * Not a test: gradlew trainDictionaries, which runs only this, and writes the dictionaries into
 * src/main/resources/assets/dailies/zstd, to be committed. The recordings are -Pdailies.bench.recordings as for the
 * benchmark, by default the two long fixtures of the modded test game, read only. The same recordings give the same
 * dictionaries. Recordings already made keep the dictionaries they carry, so training again changes only new ones.
 */
@Tag("dictionaries")
class DictionaryTraining {

    static final int DICTIONARY_BYTES = 64 * 1024;
    private static final Path INTO = Path.of("src", "main", "resources", "assets", "dailies", "zstd");

    @Test
    void train() throws IOException {
        List<Path> files = RecordingCorpus.recordings();
        Assumptions.assumeFalse(files.isEmpty(), "None of the recordings in dailies.bench.recordings is there");
        List<byte[]> pieces = new ArrayList<>();
        List<byte[]> parts = new ArrayList<>();
        for (Path file : files) {
            RecordingCorpus.Corpus corpus = RecordingCorpus.load(file);
            pieces.addAll(corpus.pieces());
            parts.addAll(corpus.parts());
            System.out.printf(Locale.ROOT, "%s: %d pieces (%.1f MB), %d segment parts (%.1f MB)%n", corpus.name(), corpus.pieces().size(),
                RecordingCorpus.total(corpus.pieces()) / 1e6, corpus.parts().size(), RecordingCorpus.total(corpus.parts()) / 1e6);
        }
        byte[] shared = RecordingCorpus.train(pieces, DICTIONARY_BYTES);
        byte[] partsDictionary = RecordingCorpus.train(parts, DICTIONARY_BYTES);
        Files.createDirectories(INTO);
        Files.write(INTO.resolve(Path.of(Packing.Dictionaries.SHARED_RESOURCE).getFileName().toString()), shared);
        Files.write(INTO.resolve(Path.of(Packing.Dictionaries.PARTS_RESOURCE).getFileName().toString()), partsDictionary);
        System.out.printf(Locale.ROOT, "Wrote %s (%d bytes) and %s (%d bytes) into %s%n", Packing.Dictionaries.SHARED_RESOURCE, shared.length,
            Packing.Dictionaries.PARTS_RESOURCE, partsDictionary.length, INTO.toAbsolutePath());
    }

}
