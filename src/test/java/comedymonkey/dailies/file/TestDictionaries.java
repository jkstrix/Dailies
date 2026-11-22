package comedymonkey.dailies.file;

import java.nio.charset.StandardCharsets;
import java.util.Random;

/** Dictionaries other than the mod's own, as a Dailies with dictionaries trained afresh would pack recordings with */
final class TestDictionaries {

    private TestDictionaries() {
    }

    /**
     * Zstandard takes any bytes as a dictionary of plain content; these are made of the words the made-up recordings
     * of the tests are made of, so they are really used
     */
    static Packing.Dictionaries other() {
        return new Packing.Dictionaries(Packing.Dictionary.of(words(1, "chunk ", "world ", "still ", "section ", "light ")),
            Packing.Dictionary.of(words(2, "snapshot ", "s1t", "s2t", "0", "1", "2", "3", "4", "5")));
    }

    private static byte[] words(int seed, String... words) {
        Random random = new Random(seed);
        StringBuilder text = new StringBuilder();
        while (text.length() < 16 * 1024) {
            text.append(words[random.nextInt(words.length)]);
        }
        return text.toString().getBytes(StandardCharsets.US_ASCII);
    }

}
