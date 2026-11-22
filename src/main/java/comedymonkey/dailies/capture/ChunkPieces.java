package comedymonkey.dailies.capture;

import comedymonkey.dailies.file.BoundedReader;
import comedymonkey.dailies.file.PacketPieces;
import comedymonkey.dailies.file.RecordingFormatException;

/**
 * Cuts a chunk packet, or a light packet, into the pieces it is stored as (specification FILE-5, file.PacketPieces):
 * each 16x16x16 section's blocks and biomes, each section's sky light and block light, the heightmaps and the block
 * entities are parts of their own, so a part that has not changed is named again rather than stored again. The bytes
 * are only read, never understood: the parts put end to end are the packet exactly, so a packet laid out in some way
 * this does not expect (another mod's doing) is simply cut into fewer pieces, and still stored whole and right.
 * <p>
 * The layout is Minecraft 1.21.1's. A chunk with light: the packet's id, x and z as two ints, the heightmaps as network
 * NBT, the sections as a varint length and that many bytes, the block entities as a varint count and each one's packed
 * position, height, type and NBT, then the light. A light packet: the id, x and z as varints, then the light. The light:
 * four bit sets, each a varint count of longs, then the sky light's arrays and the block light's, each a varint count
 * of arrays of a varint length and 2048 bytes. A section: the count of blocks that are not air as a short, then the
 * blocks and the biomes, each a byte of bits per entry, a palette as those bits call for, and a varint count of longs.
 * Nothing here touches the game, so the unit tests can cut up packets made of bytes.
 */
public final class ChunkPieces {

    /** What a packet is, as far as cutting it up goes */
    public enum Shape {
        /** Anything else: stored as one piece of bytes */
        WHOLE,
        /** A ClientboundLevelChunkWithLightPacket */
        CHUNK,
        /** A ClientboundLightUpdatePacket */
        LIGHT
    }

    /** The bytes of one section's sky light or block light */
    private static final int LIGHT_BYTES = 2048;
    /** Far more sections than any world has: a count above this is not a light packet's */
    private static final int MOST_LIGHT_SECTIONS = 4096;
    /** Far more entries than a palette of 4096 blocks can need */
    private static final int MOST_PALETTE = 4096;
    /** How deep NBT is read before it is taken not to be NBT, as the game's own limit */
    private static final int MOST_NBT_DEPTH = 512;

    private ChunkPieces() {
    }

    /** The packet's pieces, as the frame it was encoded to (packet id, then body) */
    public static PacketPieces.Split split(byte[] frame, Shape shape) {
        try {
            if (shape == Shape.CHUNK) {
                return chunk(frame);
            }
            if (shape == Shape.LIGHT) {
                return light(frame);
            }
        } catch (RecordingFormatException | RuntimeException notAsExpected) {
            // Not laid out as expected: kept whole below, which is always right
        }
        return PacketPieces.whole(frame);
    }

    private static PacketPieces.Split chunk(byte[] frame) throws RecordingFormatException {
        BoundedReader in = new BoundedReader(frame);
        in.readVarInt();
        in.readInt();
        in.readInt();
        int heightmapsFrom = in.position();
        skipNbt(in);
        int sectionsAt = in.position();
        int sectionsLength = in.length(in.readVarInt());
        int sectionsFrom = in.position();
        int sectionsTo = sectionsFrom + sectionsLength;
        int[] sections = sections(frame, sectionsFrom, sectionsTo);
        in.skip(sectionsLength);

        // The block entities and the light, found before anything is built: if they are not where they should be, all
        // that follows the sections is one part
        int blockEntitiesFrom = in.position();
        int blockEntitiesEnd = -1;
        Light found = null;
        try {
            int count = in.readVarInt();
            if (count < 0) {
                throw notAsExpected("not a count");
            }
            for (int i = 0; i < count; i++) {
                in.readByte();
                in.skip(2);
                in.readVarInt();
                skipNbt(in);
            }
            blockEntitiesEnd = in.position();
            found = light(in);
        } catch (RecordingFormatException notAsExpected) {
            found = null;
        }
        Light light = found;
        int blockEntitiesTo = blockEntitiesEnd;

        PacketPieces.Builder builder = new PacketPieces.Builder();
        builder.bytes(frame, 0, heightmapsFrom);
        builder.part(frame, heightmapsFrom, sectionsAt - heightmapsFrom);
        builder.list(list -> {
            list.bytes(frame, sectionsAt, sectionsFrom - sectionsAt);
            if (sections == null) {
                if (sectionsLength > 0) {
                    list.part(frame, sectionsFrom, sectionsLength);
                }
            } else {
                for (int i = 0; i + 1 < sections.length; i++) {
                    list.part(frame, sections[i], sections[i + 1] - sections[i]);
                }
            }
        });
        if (light == null) {
            if (frame.length > blockEntitiesFrom) {
                builder.part(frame, blockEntitiesFrom, frame.length - blockEntitiesFrom);
            }
        } else {
            builder.part(frame, blockEntitiesFrom, blockEntitiesTo - blockEntitiesFrom);
            builder.list(list -> lightPieces(frame, blockEntitiesTo, light, list));
        }
        return builder.finish();
    }

    private static PacketPieces.Split light(byte[] frame) throws RecordingFormatException {
        BoundedReader in = new BoundedReader(frame);
        in.readVarInt();
        in.readVarInt();
        in.readVarInt();
        int lightFrom = in.position();
        Light light = light(in);
        PacketPieces.Builder builder = new PacketPieces.Builder();
        builder.bytes(frame, 0, lightFrom);
        builder.list(list -> lightPieces(frame, lightFrom, light, list));
        return builder.finish();
    }

    /**
     * Where light data puts its arrays, each a varint length and {@link #LIGHT_BYTES} bytes
     *
     * @param sky          where each sky light array starts
     * @param blockCountAt where the count of block light arrays starts
     * @param block        where each block light array starts
     */
    private record Light(int[] sky, int blockCountAt, int[] block) {
    }

    /** Reads light data, which must run to the end of the frame */
    private static Light light(BoundedReader in) throws RecordingFormatException {
        for (int set = 0; set < 4; set++) {
            int longs = in.readVarInt();
            if (longs < 0 || longs > MOST_LIGHT_SECTIONS / 64 + 1) {
                throw notAsExpected("not a light mask");
            }
            in.skip(longs * 8);
        }
        int[] sky = lightArrays(in);
        int blockCountAt = in.position();
        int[] block = lightArrays(in);
        if (in.remaining() != 0) {
            throw notAsExpected("more after the light");
        }
        return new Light(sky, blockCountAt, block);
    }

    private static int[] lightArrays(BoundedReader in) throws RecordingFormatException {
        int count = in.readVarInt();
        if (count < 0 || count > MOST_LIGHT_SECTIONS || count > in.remaining() / LIGHT_BYTES) {
            throw notAsExpected("not a count of light");
        }
        int[] starts = new int[count];
        for (int i = 0; i < count; i++) {
            starts[i] = in.position();
            if (in.readVarInt() != LIGHT_BYTES) {
                throw notAsExpected("not a section of light");
            }
            in.skip(LIGHT_BYTES);
        }
        return starts;
    }

    /** The light's pieces: the masks and the count of sky arrays as bytes, each array a part, the count of block arrays as bytes */
    private static void lightPieces(byte[] frame, int lightFrom, Light light, PacketPieces.Builder list) {
        int arrayBytes = varintBytes(LIGHT_BYTES) + LIGHT_BYTES;
        int firstSky = light.sky().length > 0 ? light.sky()[0] : light.blockCountAt();
        list.bytes(frame, lightFrom, firstSky - lightFrom);
        for (int at : light.sky()) {
            list.part(frame, at, arrayBytes);
        }
        int firstBlock = light.block().length > 0 ? light.block()[0] : frame.length;
        list.bytes(frame, light.blockCountAt(), firstBlock - light.blockCountAt());
        for (int at : light.block()) {
            list.part(frame, at, arrayBytes);
        }
    }

    /**
     * Where each section starts in the stretch of the frame that holds them, and where the last ends; null when the
     * stretch cannot be read as sections end to end
     */
    private static int[] sections(byte[] frame, int from, int to) {
        try {
            BoundedReader in = new BoundedReader(frame, from, to - from);
            java.util.List<Integer> starts = new java.util.ArrayList<>();
            while (in.remaining() > 0) {
                starts.add(in.position());
                in.skip(2);
                paletted(in, 8);
                paletted(in, 3);
            }
            starts.add(in.position());
            int[] at = new int[starts.size()];
            for (int i = 0; i < at.length; i++) {
                at[i] = starts.get(i);
            }
            return at;
        } catch (RecordingFormatException | RuntimeException notSections) {
            return null;
        }
    }

    /**
     * Steps over a paletted container: blocks, whose palette is in the packet up to 8 bits an entry, or biomes, up to 3.
     * One bits value a palette entry beyond those is the whole registry, with no palette in the packet
     */
    private static void paletted(BoundedReader in, int mostPaletteBits) throws RecordingFormatException {
        int bits = in.readByte() & 0xFF;
        if (bits == 0) {
            in.readVarInt();
        } else if (bits <= mostPaletteBits) {
            int entries = in.readVarInt();
            if (entries < 0 || entries > MOST_PALETTE) {
                throw notAsExpected("not a palette");
            }
            for (int i = 0; i < entries; i++) {
                in.readVarInt();
            }
        }
        int longs = in.readVarInt();
        if (longs < 0 || longs > MOST_PALETTE * 64) {
            throw notAsExpected("not a container");
        }
        in.skip(longs * 8);
    }

    /** Steps over one piece of network NBT: its type, then what that type holds, with no name */
    private static void skipNbt(BoundedReader in) throws RecordingFormatException {
        int type = in.readByte();
        if (type != 0) {
            skipNbtValue(in, type, 0);
        }
    }

    private static void skipNbtValue(BoundedReader in, int type, int depth) throws RecordingFormatException {
        if (depth > MOST_NBT_DEPTH) {
            throw notAsExpected("NBT too deep");
        }
        switch (type) {
            case 1 -> in.skip(1);
            case 2 -> in.skip(2);
            case 3, 5 -> in.skip(4);
            case 4, 6 -> in.skip(8);
            case 7 -> in.skip(count(in, 1));
            case 8 -> in.skip(unsignedShort(in));
            case 9 -> {
                int inner = in.readByte();
                int length = in.readInt();
                if (length < 0 || (inner == 0 && length > 0)) {
                    throw notAsExpected("not an NBT list");
                }
                for (int i = 0; i < length; i++) {
                    skipNbtValue(in, inner, depth + 1);
                }
            }
            case 10 -> {
                while (true) {
                    int inner = in.readByte();
                    if (inner == 0) {
                        break;
                    }
                    in.skip(unsignedShort(in));
                    skipNbtValue(in, inner, depth + 1);
                }
            }
            case 11 -> in.skip(count(in, 4));
            case 12 -> in.skip(count(in, 8));
            default -> throw notAsExpected("not an NBT type");
        }
    }

    /** A count of things of so many bytes each, as the number of bytes they come to */
    private static int count(BoundedReader in, int each) throws RecordingFormatException {
        long length = in.readInt() * (long) each;
        return in.length(length);
    }

    private static int unsignedShort(BoundedReader in) throws RecordingFormatException {
        return (in.readByte() & 0xFF) << 8 | in.readByte() & 0xFF;
    }

    /** Thrown where the bytes are not laid out as expected, to have the packet cut into fewer pieces; never seen outside */
    private static RecordingFormatException notAsExpected(String what) {
        return new RecordingFormatException(RecordingFormatException.Reason.DAMAGED, "Not laid out as a packet of this shape: " + what);
    }

    private static int varintBytes(int value) {
        int bytes = 1;
        while ((value & ~0x7F) != 0) {
            bytes++;
            value >>>= 7;
        }
        return bytes;
    }

}
