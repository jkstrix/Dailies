package comedymonkey.dailies.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Packing and naming blocks, on good and on hostile input (code review FILE-1, L-30) */
class PackingTest {

    @TempDir
    Path folder;

    private static byte[] data(int seed, int length) {
        byte[] bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
        for (int i = 0; i < length; i += 2) {
            bytes[i] = 0;
        }
        return bytes;
    }

    /** Data much like itself, as the pieces of chunks are: the same few words over and over, in a different order each time */
    private static byte[] alike(int seed, int length) {
        String[] words = {"minecraft:stone ", "minecraft:air ", "minecraft:deepslate ", "minecraft:water ", "minecraft:grass_block ", "minecraft:oak_log "};
        Random random = new Random(seed);
        StringBuilder text = new StringBuilder();
        while (text.length() < length) {
            text.append(words[random.nextInt(words.length)]);
        }
        return Arrays.copyOf(text.toString().getBytes(StandardCharsets.US_ASCII), length);
    }

    /** Every way a block can be packed: without a dictionary, with the mod's own, and with one made up of data like the blocks */
    private static Packing.Dictionary[] dictionaries() {
        return new Packing.Dictionary[]{null, Packing.Dictionaries.current().shared(), Packing.Dictionaries.current().parts(), Packing.Dictionary.of(alike(99, 8192))};
    }

    @Test
    void packedDataUnpacksAsItWasOverAndOverWithAndWithoutADictionary() throws RecordingFormatException {
        for (Packing.Dictionary dictionary : dictionaries()) {
            // The compressors are used again for every block: what one block leaves behind must never reach the next
            for (int round = 0; round < 20; round++) {
                byte[] original = round % 2 == 0 ? data(round, 1000 + round * 3333) : alike(round, 1000 + round * 3333);
                byte[] prefix = {1, 2, 3};
                byte[] packed = Packing.pack(dictionary, prefix, original, 0, original.length);
                assertArrayEquals(prefix, Arrays.copyOf(packed, 3));
                assertArrayEquals(original, Packing.unpack(dictionary, packed, 3));
            }
            byte[] empty = Packing.pack(dictionary, new byte[0], new byte[0], 0, 0);
            assertEquals(0, Packing.unpack(dictionary, empty, 0).length);
        }
    }

    @Test
    void aDictionaryOfDataLikeTheBlocksPacksThemSmaller() throws RecordingFormatException {
        Packing.Dictionary dictionary = Packing.Dictionary.of(alike(99, 8192));
        byte[] small = alike(5, 300);
        byte[] without = Packing.pack(null, new byte[0], small, 0, small.length);
        byte[] with = Packing.pack(dictionary, new byte[0], small, 0, small.length);
        assertTrue(with.length < without.length, with.length + " bytes with the dictionary, " + without.length + " without");
        // Unpacked with another dictionary, or none, it is never the same bytes
        for (Packing.Dictionary other : new Packing.Dictionary[]{null, Packing.Dictionary.of(alike(98, 8192))}) {
            try {
                assertFalse(Arrays.equals(small, Packing.unpack(other, with, 0)));
            } catch (RecordingFormatException expected) {
                // As likely
            }
        }
    }

    @Test
    void aCompressorUsedWithADictionaryForgetsItAfter() throws RecordingFormatException {
        // The compressors are kept and used again, and the next block may be packed with another dictionary or none
        Packing.Dictionary dictionary = Packing.Dictionary.of(alike(99, 8192));
        for (int round = 0; round < 20; round++) {
            byte[] block = alike(round, 2_000);
            Packing.unpack(dictionary, Packing.pack(dictionary, new byte[0], block, 0, block.length), 0);
            byte[] plain = Packing.pack(null, new byte[0], block, 0, block.length);
            assertArrayEquals(block, Packing.unpack(null, plain, 0));
        }
    }

    @Test
    void dictionariesWithTheSameBytesAreOne() {
        byte[] bytes = alike(7, 4096);
        Packing.Dictionary one = Packing.Dictionary.of(bytes);
        assertSame(one, Packing.Dictionary.of(bytes.clone()));
        assertTrue(Packing.Dictionary.same(one, Packing.Dictionary.of(bytes.clone())));
        assertFalse(Packing.Dictionary.same(one, Packing.Dictionary.of(alike(8, 4096))));
        assertFalse(Packing.Dictionary.same(one, null));
        assertTrue(Packing.Dictionary.same(null, null));
        assertNull(Packing.Dictionary.of(new byte[0]));
    }

    @Test
    void theModsOwnDictionariesArePackedIn() {
        Packing.Dictionaries current = Packing.Dictionaries.current();
        assertNotNull(current.shared());
        assertNotNull(current.parts());
        assertFalse(Packing.Dictionary.same(current.shared(), current.parts()));
        for (Packing.Dictionary dictionary : new Packing.Dictionary[]{current.shared(), current.parts()}) {
            assertTrue(dictionary.bytes.length > 1024 && dictionary.bytes.length <= 64 * 1024, dictionary.bytes.length + " bytes");
        }
    }

    @Test
    void partOfAnArrayIsPacked() throws RecordingFormatException {
        byte[] original = data(5, 10_000);
        byte[] packed = Packing.pack(null, new byte[0], original, 100, 5_000);
        assertArrayEquals(Arrays.copyOfRange(original, 100, 5_100), Packing.unpack(null, packed, 0));
    }

    @Test
    void theNameIsTheSha256() throws Exception {
        for (int round = 0; round < 5; round++) {
            byte[] bytes = data(round, 4096);
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(bytes), Packing.hash(bytes));
            assertArrayEquals(Packing.hash(bytes), RecordingWriter.hashOf(bytes));
        }
    }

    @Test
    void aLengthThatLiesIsDamageNotAnOutOfMemory() {
        // Two gigabytes claimed by a dozen bytes
        byte[] lying = ByteBuffer.allocate(12).putInt(Integer.MAX_VALUE).array();
        RecordingFormatException damaged = assertThrows(RecordingFormatException.class, () -> Packing.unpack(null, lying, 0));
        assertEquals(RecordingFormatException.Reason.DAMAGED, damaged.reason());
        byte[] negative = ByteBuffer.allocate(12).putInt(-5).array();
        assertThrows(RecordingFormatException.class, () -> Packing.unpack(null, negative, 0));
    }

    @Test
    void aLengthJustOverTheCeilingIsRefusedWhateverFollows() throws RecordingFormatException {
        // A run of zeros packs to a few bytes, so how little the packed data is says nothing; the ceiling does
        byte[] zeros = new byte[1 << 20];
        byte[] packed = Packing.pack(null, new byte[0], zeros, 0, zeros.length);
        assertTrue(packed.length < 200, packed.length + " bytes");
        assertArrayEquals(zeros, Packing.unpack(null, packed, 0));
        ByteBuffer.wrap(packed).putInt(Packing.MAX_UNPACKED + 1);
        assertThrows(RecordingFormatException.class, () -> Packing.unpack(null, packed, 0));
        // And nothing that big is ever packed, so nothing written can be refused for it
        assertThrows(IllegalArgumentException.class, () -> Packing.pack(null, new byte[0], new byte[0], 0, Packing.MAX_UNPACKED + 1));
    }

    @Test
    void aLengthThatIsNotWhatTheFrameHoldsIsDamage() {
        for (Packing.Dictionary dictionary : dictionaries()) {
            byte[] original = alike(3, 5_000);
            byte[] packed = Packing.pack(dictionary, new byte[0], original, 0, original.length);
            for (int lie : new int[]{0, 1, 4_999, 5_001, 10_000, 1 << 20}) {
                byte[] lying = packed.clone();
                ByteBuffer.wrap(lying).putInt(lie);
                RecordingFormatException damaged = assertThrows(RecordingFormatException.class, () -> Packing.unpack(dictionary, lying, 0), "said " + lie);
                assertEquals(RecordingFormatException.Reason.DAMAGED, damaged.reason());
            }
        }
    }

    @Test
    void cutShortOrWrongDataIsDamage() {
        byte[] packed = Packing.pack(null, new byte[0], data(1, 50_000), 0, 50_000);
        assertThrows(RecordingFormatException.class, () -> Packing.unpack(null, new byte[3], 0));
        assertThrows(RecordingFormatException.class, () -> Packing.unpack(null, new byte[4], 0));
        assertThrows(RecordingFormatException.class, () -> Packing.unpack(null, packed, packed.length + 1));
        assertThrows(RecordingFormatException.class, () -> Packing.unpack(null, packed, -1));
        // Every cut of the packed bytes fails cleanly
        for (int cut = 0; cut < packed.length; cut += 997) {
            byte[] shorter = Arrays.copyOf(packed, cut);
            assertThrows(RecordingFormatException.class, () -> Packing.unpack(null, shorter, 0), "cut at " + cut);
        }
        // More after the frame than the frame
        byte[] longer = Arrays.copyOf(packed, packed.length + 7);
        assertThrows(RecordingFormatException.class, () -> Packing.unpack(null, longer, 0));
        // Not packed at all
        byte[] noise = data(2, 100);
        System.arraycopy(ByteBuffer.allocate(4).putInt(80).array(), 0, noise, 0, 4);
        noise[4] = (byte) 0xFF;
        assertThrows(RecordingFormatException.class, () -> Packing.unpack(null, noise, 0));
    }

    @Test
    void bitFlipsNeverThrowAnythingButDamage() {
        for (Packing.Dictionary dictionary : dictionaries()) {
            byte[] original = data(9, 20_000);
            byte[] packed = Packing.pack(dictionary, new byte[0], original, 0, original.length);
            Random random = new Random(42);
            for (int round = 0; round < 300; round++) {
                byte[] flipped = packed.clone();
                flipped[random.nextInt(flipped.length)] ^= (byte) (1 << random.nextInt(8));
                try {
                    byte[] unpacked = Packing.unpack(dictionary, flipped, 0);
                    // A flip zstd does not notice gives other bytes, never a crash
                    assertTrue(unpacked.length <= Packing.MAX_UNPACKED);
                } catch (RecordingFormatException expected) {
                    // As it should be
                }
            }
        }
    }

    @Test
    void sharedDataStoredUnderAGivenNameIsFoundByIt() throws IOException {
        Path file = this.folder.resolve("named" + RecordingFile.EXTENSION);
        RecordingManifest manifest = new RecordingManifest();
        manifest.id = "5f0e7b1c-2d3a-4b5c-8d9e-0f1a2b3c4d5e";
        manifest.name = "Named";
        RecordingWriter writer = RecordingWriter.create(file, manifest);
        byte[] bytes = data(3, 9000);
        byte[] hash = RecordingWriter.hashOf(bytes);
        String name = writer.putShared(bytes, hash);
        assertEquals(HexFormat.of().formatHex(hash), name);
        assertEquals(name, writer.putShared(bytes));
        assertThrows(IllegalArgumentException.class, () -> writer.putShared(bytes, new byte[5]));
        writer.finish();
        try (RecordingFile recording = RecordingFile.open(file)) {
            assertEquals(1, recording.sharedCount());
            assertArrayEquals(bytes, recording.shared(name));
        }
    }

}
