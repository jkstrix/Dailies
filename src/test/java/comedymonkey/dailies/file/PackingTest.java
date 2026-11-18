package comedymonkey.dailies.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void packedDataUnpacksAsItWasOverAndOver() throws RecordingFormatException {
        // The compressor is used again for every block: what one block leaves behind must never reach the next
        for (int round = 0; round < 20; round++) {
            byte[] original = data(round, 1000 + round * 3333);
            byte[] prefix = {1, 2, 3};
            byte[] packed = Packing.pack(prefix, original, 0, original.length);
            assertArrayEquals(prefix, Arrays.copyOf(packed, 3));
            assertArrayEquals(original, Packing.unpack(packed, 3));
        }
        byte[] empty = Packing.pack(new byte[0], new byte[0], 0, 0);
        assertEquals(0, Packing.unpack(empty, 0).length);
    }

    @Test
    void partOfAnArrayIsPacked() throws RecordingFormatException {
        byte[] original = data(5, 10_000);
        byte[] packed = Packing.pack(new byte[0], original, 100, 5_000);
        assertArrayEquals(Arrays.copyOfRange(original, 100, 5_100), Packing.unpack(packed, 0));
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
        RecordingFormatException damaged = assertThrows(RecordingFormatException.class, () -> Packing.unpack(lying, 0));
        assertEquals(RecordingFormatException.Reason.DAMAGED, damaged.reason());
        byte[] negative = ByteBuffer.allocate(12).putInt(-5).array();
        assertThrows(RecordingFormatException.class, () -> Packing.unpack(negative, 0));
    }

    @Test
    void cutShortOrWrongDataIsDamage() {
        byte[] packed = Packing.pack(new byte[0], data(1, 50_000), 0, 50_000);
        assertThrows(RecordingFormatException.class, () -> Packing.unpack(new byte[3], 0));
        assertThrows(RecordingFormatException.class, () -> Packing.unpack(packed, packed.length + 1));
        assertThrows(RecordingFormatException.class, () -> Packing.unpack(packed, -1));
        // Every cut of the packed bytes fails cleanly
        for (int cut = 0; cut < packed.length; cut += 997) {
            byte[] shorter = Arrays.copyOf(packed, cut);
            assertThrows(RecordingFormatException.class, () -> Packing.unpack(shorter, 0), "cut at " + cut);
        }
        // Not deflated at all
        byte[] noise = data(2, 100);
        System.arraycopy(ByteBuffer.allocate(4).putInt(80).array(), 0, noise, 0, 4);
        noise[4] = (byte) 0xFF;
        assertThrows(RecordingFormatException.class, () -> Packing.unpack(noise, 0));
    }

    @Test
    void bitFlipsNeverThrowAnythingButDamage() {
        byte[] original = data(9, 20_000);
        byte[] packed = Packing.pack(new byte[0], original, 0, original.length);
        Random random = new Random(42);
        for (int round = 0; round < 300; round++) {
            byte[] flipped = packed.clone();
            flipped[random.nextInt(flipped.length)] ^= (byte) (1 << random.nextInt(8));
            try {
                byte[] unpacked = Packing.unpack(flipped, 0);
                // A flip deflate does not notice gives other bytes, never a crash
                assertTrue(unpacked.length <= BoundedReader.MAX_LENGTH);
            } catch (RecordingFormatException expected) {
                // As it should be
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
