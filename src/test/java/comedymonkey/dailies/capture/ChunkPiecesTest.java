package comedymonkey.dailies.capture;

import comedymonkey.dailies.file.PacketPieces;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cutting chunk and light packets into the pieces they are stored as (specification FILE-5), on packets made of bytes
 * laid out as Minecraft 1.21.1 lays them out: the game's classes cannot be used in a unit test.
 */
class ChunkPiecesTest {

    private static final int SECTIONS = 24;
    private static final int LIGHT_SECTIONS = SECTIONS + 2;

    /** Bytes written the way a game's packet buffer writes them */
    private static final class Bytes {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Bytes varint(int value) {
            while ((value & ~0x7F) != 0) {
                this.out.write((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            this.out.write(value);
            return this;
        }

        Bytes u8(int value) {
            this.out.write(value);
            return this;
        }

        Bytes i16(int value) {
            return this.u8(value >>> 8 & 0xFF).u8(value & 0xFF);
        }

        Bytes i32(int value) {
            return this.i16(value >>> 16).i16(value);
        }

        Bytes i64(long value) {
            return this.i32((int) (value >>> 32)).i32((int) value);
        }

        Bytes raw(byte[] bytes) {
            this.out.writeBytes(bytes);
            return this;
        }

        /** A name or a string in NBT: an unsigned short length and the bytes */
        Bytes utf(String text) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            return this.i16(bytes.length).raw(bytes);
        }

        byte[] take() {
            return this.out.toByteArray();
        }
    }

    /** What one made-up chunk holds, to be changed a little between one packet and the next */
    private static final class Chunk {
        int x = 3, z = -7;
        /** A long in the data of each section that has blocks other than air, by section */
        final long[] block = new long[SECTIONS];
        long heightmapSeed = 1;
        boolean withBlockEntities = true;
        /** Each light section's sky light: 0 for dark, 15 for lit, anything else for a mix */
        final int[] sky = new int[LIGHT_SECTIONS];

        Chunk() {
            for (int i = 0; i < SECTIONS; i++) {
                this.block[i] = i < 4 ? 0x1111_2222_3333_4444L * (i + 1) : 0;
            }
            for (int i = 0; i < LIGHT_SECTIONS; i++) {
                this.sky[i] = i < 3 ? 0 : i == 3 ? 7 : 15;
            }
        }
    }

    private static byte[] section(Chunk chunk, int index) {
        Bytes section = new Bytes();
        if (chunk.block[index] == 0) {
            // Air: a single value, no data, and one biome
            section.i16(0);
            section.u8(0).varint(0).varint(0);
            section.u8(0).varint(1).varint(0);
        } else if (index == 2) {
            // Many kinds of block: the whole registry, no palette, 15 bits an entry, four to a long
            section.i16(4096);
            section.u8(15).varint(1024);
            for (int i = 0; i < 1024; i++) {
                section.i64(i == 100 ? chunk.block[index] : 0x0123_4567_89AB_CDEFL ^ i);
            }
            // Two biomes, from a palette of its own
            section.u8(1).varint(2).varint(1).varint(4).varint(1).i64(0xF0F0_F0F0_F0F0_F0F0L);
        } else {
            // A few kinds of block, from a palette: 4 bits an entry for a short palette, 5 for a longer one
            int bits = index == 3 ? 5 : 4;
            int entries = index == 3 ? 20 : 3;
            section.i16(4096);
            section.u8(bits).varint(entries);
            for (int i = 0; i < entries; i++) {
                section.varint(i * 37 + 1);
            }
            int longs = bits == 4 ? 256 : 342;
            section.varint(longs);
            for (int i = 0; i < longs; i++) {
                section.i64(i == 10 ? chunk.block[index] : 0x1010_1010_1010_1010L * (index + 1));
            }
            section.u8(0).varint(1).varint(0);
        }
        return section.take();
    }

    private static byte[] heightmaps(Chunk chunk) {
        Bytes nbt = new Bytes();
        nbt.u8(10);
        for (String name : new String[]{"MOTION_BLOCKING", "WORLD_SURFACE"}) {
            nbt.u8(12).utf(name).i32(37);
            for (int i = 0; i < 37; i++) {
                nbt.i64(chunk.heightmapSeed * 31 + i);
            }
        }
        nbt.u8(0);
        return nbt.take();
    }

    private static byte[] blockEntities(Chunk chunk) {
        Bytes list = new Bytes();
        if (!chunk.withBlockEntities) {
            return list.varint(0).take();
        }
        list.varint(2);
        // A chest with an item in it: compound, list of compounds, strings, a byte, an int
        list.u8(0x35).i16(-60).varint(2);
        list.u8(10).u8(9).utf("Items").u8(10).i32(1)
            .u8(8).utf("id").utf("minecraft:stone").u8(1).utf("Slot").u8(3).u8(3).utf("count").i32(12).u8(0)
            .u8(11).utf("ints").i32(2).i32(1).i32(2)
            .u8(7).utf("bytes").i32(3).u8(1).u8(2).u8(3)
            .u8(6).utf("d").i64(0).u8(5).utf("f").i32(0).u8(2).utf("s").i16(9).u8(4).utf("l").i64(5)
            .u8(9).utf("empty").u8(0).i32(0)
            .u8(0);
        // A sign that says nothing of itself
        list.u8(0x80).i16(70).varint(7).u8(0);
        return list.take();
    }

    private static byte[] lightArray(int level, int salt) {
        byte[] bytes = new byte[2048];
        if (level == 15) {
            Arrays.fill(bytes, (byte) 0xFF);
        } else if (level != 0) {
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) (i * 7 + salt);
            }
        }
        return bytes;
    }

    private static byte[] light(Chunk chunk) {
        Bytes light = new Bytes();
        long skyMask = (1L << LIGHT_SECTIONS) - 1;
        light.varint(1).i64(skyMask);
        // Block light in two sections; the rest said outright to be dark; no section's sky light said to be empty
        light.varint(1).i64(0b1100);
        light.varint(0);
        light.varint(1).i64(skyMask & ~0b1100L);
        light.varint(LIGHT_SECTIONS);
        for (int i = 0; i < LIGHT_SECTIONS; i++) {
            light.varint(2048).raw(lightArray(chunk.sky[i], i));
        }
        light.varint(2);
        light.varint(2048).raw(lightArray(0, 0));
        light.varint(2048).raw(lightArray(9, 101));
        return light.take();
    }

    private static byte[] chunkPacket(Chunk chunk) {
        Bytes sections = new Bytes();
        for (int i = 0; i < SECTIONS; i++) {
            sections.raw(section(chunk, i));
        }
        byte[] buffer = sections.take();
        return new Bytes().varint(0x27).i32(chunk.x).i32(chunk.z)
            .raw(heightmaps(chunk)).varint(buffer.length).raw(buffer)
            .raw(blockEntities(chunk)).raw(light(chunk)).take();
    }

    private static byte[] lightPacket(Chunk chunk) {
        return new Bytes().varint(0x2A).varint(chunk.x).varint(chunk.z).raw(light(chunk)).take();
    }

    // --- Reading the pieces back ---

    private static Map<String, byte[]> stored(PacketPieces.Split split) {
        Map<String, byte[]> store = new HashMap<>();
        for (PacketPieces.Blob blob : split.blobs()) {
            store.put(blob.hex(), blob.copy());
        }
        return store;
    }

    private static byte[] rebuild(PacketPieces.Split split) throws Exception {
        Map<String, byte[]> store = stored(split);
        return PacketPieces.rebuild(store.get(HexFormat.of().formatHex(split.name())), store::get);
    }

    /** The names a chunk's own list holds: heightmaps, the sections' list, block entities, the light's list */
    private static List<PacketPieces.Reference> top(PacketPieces.Split split) throws Exception {
        return PacketPieces.references(stored(split).get(HexFormat.of().formatHex(split.name())));
    }

    /** The parts an inner list names */
    private static List<String> inner(PacketPieces.Split split, String list) throws Exception {
        return PacketPieces.references(stored(split).get(list)).stream().map(PacketPieces.Reference::name).toList();
    }

    private static List<String> sectionNames(PacketPieces.Split split) throws Exception {
        return inner(split, top(split).get(1).name());
    }

    private static List<String> lightNames(PacketPieces.Split split) throws Exception {
        return inner(split, top(split).get(3).name());
    }

    // --- The tests ---

    @Test
    void aChunkCutUpAndPutBackTogetherIsThePacketExactly() throws Exception {
        byte[] packet = chunkPacket(new Chunk());
        PacketPieces.Split split = ChunkPieces.split(packet, ChunkPieces.Shape.CHUNK);
        assertArrayEquals(packet, rebuild(split));
        // Heightmaps, the list of sections, the block entities and the list of light, each a piece of its own
        assertEquals(List.of(PacketPieces.PART, PacketPieces.LIST, PacketPieces.PART, PacketPieces.LIST), top(split).stream().map(PacketPieces.Reference::kind).toList());
        assertEquals(SECTIONS, sectionNames(split).size());
        assertEquals(LIGHT_SECTIONS + 2, lightNames(split).size());
        // Every section of air is one part
        assertEquals(1, sectionNames(split).subList(4, SECTIONS).stream().distinct().count());
        assertEquals(5, sectionNames(split).stream().distinct().count());
        // The parts are the sections themselves, as a section writes itself
        assertArrayEquals(section(new Chunk(), 2), stored(split).get(sectionNames(split).get(2)));
    }

    @Test
    void aChunkWithNoBlockEntitiesAndALightPacketPutBackTogetherToo() throws Exception {
        Chunk chunk = new Chunk();
        chunk.withBlockEntities = false;
        byte[] packet = chunkPacket(chunk);
        assertArrayEquals(packet, rebuild(ChunkPieces.split(packet, ChunkPieces.Shape.CHUNK)));
        byte[] light = lightPacket(chunk);
        PacketPieces.Split split = ChunkPieces.split(light, ChunkPieces.Shape.LIGHT);
        assertArrayEquals(light, rebuild(split));
        // A light packet's light is the same list as the chunk's own light, when the light is the same
        assertEquals(top(ChunkPieces.split(packet, ChunkPieces.Shape.CHUNK)).get(3).name(),
            PacketPieces.references(stored(split).get(HexFormat.of().formatHex(split.name()))).get(0).name());
    }

    @Test
    void oneChangedBlockChangesOneSectionsName() throws Exception {
        Chunk chunk = new Chunk();
        PacketPieces.Split before = ChunkPieces.split(chunkPacket(chunk), ChunkPieces.Shape.CHUNK);
        for (int changed : new int[]{0, 2, 3}) {
            Chunk after = new Chunk();
            after.block[changed] ^= 1L << 17;
            PacketPieces.Split split = ChunkPieces.split(chunkPacket(after), ChunkPieces.Shape.CHUNK);
            List<String> was = sectionNames(before);
            List<String> now = sectionNames(split);
            int differ = 0;
            for (int i = 0; i < SECTIONS; i++) {
                if (!was.get(i).equals(now.get(i))) {
                    differ++;
                    assertEquals(changed, i);
                }
            }
            assertEquals(1, differ, "one block changed in section " + changed);
            // Its heightmaps, block entities and light keep their names; only the chunk's list and the sections' list are new
            assertEquals(top(before).get(0), top(split).get(0));
            assertEquals(top(before).get(2), top(split).get(2));
            assertEquals(top(before).get(3), top(split).get(3));
            assertNotEquals(HexFormat.of().formatHex(before.name()), HexFormat.of().formatHex(split.name()));
            Map<String, byte[]> already = stored(before);
            long fresh = stored(split).keySet().stream().filter(name -> !already.containsKey(name)).count();
            assertEquals(3, fresh, "the section, the list of sections and the chunk's own list are all that is new");
        }
    }

    @Test
    void otherHeightmapsOrBlockEntitiesLeaveTheSectionsAsTheyWere() throws Exception {
        Chunk chunk = new Chunk();
        PacketPieces.Split before = ChunkPieces.split(chunkPacket(chunk), ChunkPieces.Shape.CHUNK);
        chunk.heightmapSeed = 99;
        chunk.withBlockEntities = false;
        PacketPieces.Split after = ChunkPieces.split(chunkPacket(chunk), ChunkPieces.Shape.CHUNK);
        assertEquals(top(before).get(1), top(after).get(1));
        assertEquals(top(before).get(3), top(after).get(3));
        assertNotEquals(top(before).get(0), top(after).get(0));
        assertNotEquals(top(before).get(2), top(after).get(2));
    }

    @Test
    void allDarkAndAllLitLightIsOnePartWhereverItIs() throws Exception {
        Chunk first = new Chunk();
        Chunk second = new Chunk();
        second.x = 40;
        second.block[1] = 0x5555L;
        second.sky[3] = 15;
        second.sky[4] = 0;
        List<String> one = lightNames(ChunkPieces.split(chunkPacket(first), ChunkPieces.Shape.CHUNK));
        List<String> two = lightNames(ChunkPieces.split(chunkPacket(second), ChunkPieces.Shape.CHUNK));
        // In the first chunk: sky arrays 0 to 2 dark, 3 mixed, 4 on lit; then the block light's, one dark and one mixed
        String dark = one.get(0);
        String lit = one.get(4);
        assertEquals(List.of(dark, dark, dark), one.subList(0, 3));
        assertTrue(one.subList(4, LIGHT_SECTIONS).stream().allMatch(lit::equals));
        // Dark block light is the same part as dark sky light
        assertEquals(dark, one.get(LIGHT_SECTIONS));
        assertNotEquals(dark, one.get(LIGHT_SECTIONS + 1));
        // And another chunk's dark and lit sections are the same parts again
        assertEquals(dark, two.get(4));
        assertEquals(lit, two.get(3));
        // Dark, lit, the mixed sky section and the mixed block section: four parts for twenty-eight sections of light
        assertEquals(4, one.stream().distinct().count());
    }

    @Test
    void whatIsNotLaidOutAsExpectedIsStillStoredRight() throws Exception {
        Random random = new Random(5);
        for (int i = 0; i < 200; i++) {
            byte[] noise = new byte[random.nextInt(400)];
            random.nextBytes(noise);
            for (ChunkPieces.Shape shape : ChunkPieces.Shape.values()) {
                assertArrayEquals(noise, rebuild(ChunkPieces.split(noise, shape)));
            }
        }
        // A chunk whose sections are not as expected (a mod's own layout, say) keeps its sections as one part, and the rest as usual
        Chunk chunk = new Chunk();
        byte[] packet = chunkPacket(chunk);
        byte[] odd = packet.clone();
        int sections = ChunkPieces.split(packet, ChunkPieces.Shape.CHUNK).blobs().get(0).length() + 1 + 8 + 2;
        odd[sections + 2] = (byte) 200;
        PacketPieces.Split split = ChunkPieces.split(odd, ChunkPieces.Shape.CHUNK);
        assertArrayEquals(odd, rebuild(split));
        assertEquals(1, sectionNames(split).size());
        assertEquals(LIGHT_SECTIONS + 2, lightNames(split).size());
        // Every chunk packet cut short anywhere still comes back whole
        for (int cut = 0; cut < packet.length; cut += 97) {
            byte[] shorter = Arrays.copyOf(packet, cut);
            assertArrayEquals(shorter, rebuild(ChunkPieces.split(shorter, ChunkPieces.Shape.CHUNK)));
        }
        // And one with something after its light
        byte[] longer = Arrays.copyOf(packet, packet.length + 3);
        assertArrayEquals(longer, rebuild(ChunkPieces.split(longer, ChunkPieces.Shape.CHUNK)));
        List<PacketPieces.Reference> pieces = top(ChunkPieces.split(longer, ChunkPieces.Shape.CHUNK));
        assertEquals(3, pieces.size(), "heightmaps, sections, and all that follows as one part: " + pieces);
        List<String> names = new ArrayList<>();
        pieces.forEach(reference -> names.add(reference.name()));
        assertEquals(3, names.stream().distinct().count());
    }

}
