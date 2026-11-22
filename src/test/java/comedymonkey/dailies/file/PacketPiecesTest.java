package comedymonkey.dailies.file;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Packets stored in pieces (specification FILE-5): lists of bytes, parts and inner lists, put back together exactly */
class PacketPiecesTest {

    private static byte[] text(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** The shared data a split stores, by name, as the recording's writer would keep it */
    private static Map<String, byte[]> stored(PacketPieces.Split split) {
        Map<String, byte[]> store = new HashMap<>();
        for (PacketPieces.Blob blob : split.blobs()) {
            store.put(blob.hex(), blob.copy());
        }
        return store;
    }

    private static byte[] rebuild(PacketPieces.Split split, Map<String, byte[]> store) throws Exception {
        return PacketPieces.rebuild(store.get(HexFormat.of().formatHex(split.name())), store::get);
    }

    @Test
    void piecesPutEndToEndAreThePacket() throws Exception {
        byte[] packet = text("head|section one|section two|tail");
        PacketPieces.Builder builder = new PacketPieces.Builder();
        builder.bytes(packet, 0, 4);
        builder.bytes(packet, 4, 1);
        builder.list(list -> {
            list.part(packet, 5, 11);
            list.bytes(packet, 16, 1);
            list.part(packet, 17, 11);
        });
        builder.part(packet, 28, packet.length - 28);
        PacketPieces.Split split = builder.finish();
        Map<String, byte[]> store = stored(split);
        assertArrayEquals(packet, rebuild(split, store));
        // Each name is the SHA-256 of what it names, and the packet's own list comes last
        for (PacketPieces.Blob blob : split.blobs()) {
            assertArrayEquals(Packing.hash(blob.copy()), blob.name());
        }
        assertArrayEquals(split.name(), split.blobs().get(split.blobs().size() - 1).name());
        // Bytes given one after another are one piece: the list names one inner list and one part
        List<PacketPieces.Reference> references = PacketPieces.references(store.get(HexFormat.of().formatHex(split.name())));
        assertEquals(List.of(PacketPieces.LIST, PacketPieces.PART), references.stream().map(PacketPieces.Reference::kind).toList());
    }

    @Test
    void aPacketKeptWholeIsOneListOfItsOwnBytes() throws Exception {
        byte[] packet = text("a packet that is not cut up");
        PacketPieces.Split split = PacketPieces.whole(packet);
        assertEquals(1, split.blobs().size());
        assertEquals(List.of(), PacketPieces.references(split.blobs().get(0).copy()));
        assertArrayEquals(packet, rebuild(split, stored(split)));
        // A long run of bytes has a length of more than one byte
        byte[] big = new byte[70_000];
        big[69_999] = 7;
        PacketPieces.Split whole = PacketPieces.whole(big);
        assertArrayEquals(big, rebuild(whole, stored(whole)));
    }

    @Test
    void theSamePartInTwoPacketsHasOneName() {
        byte[] first = text("AAAA-same section-BBBB");
        byte[] second = text("CCCC-same section-DDDD");
        PacketPieces.Builder one = new PacketPieces.Builder();
        one.bytes(first, 0, 5).part(first, 5, 12).bytes(first, 17, 5);
        PacketPieces.Builder two = new PacketPieces.Builder();
        two.bytes(second, 0, 5).part(second, 5, 12).bytes(second, 17, 5);
        PacketPieces.Split a = one.finish();
        PacketPieces.Split b = two.finish();
        assertNotEquals(HexFormat.of().formatHex(a.name()), HexFormat.of().formatHex(b.name()));
        assertEquals(a.blobs().get(0).hex(), b.blobs().get(0).hex());
    }

    @Test
    void aListThatIsNotOneIsRefused() {
        Map<String, byte[]> store = new HashMap<>();
        // A kind there is not
        assertThrows(RecordingFormatException.class, () -> PacketPieces.rebuild(new byte[]{9, 1, 2}, store::get));
        // Bytes that say they are longer than the list
        assertThrows(RecordingFormatException.class, () -> PacketPieces.rebuild(new byte[]{PacketPieces.BYTES, 5, 1}, store::get));
        // A name cut short
        assertThrows(RecordingFormatException.class, () -> PacketPieces.rebuild(new byte[]{PacketPieces.PART, 1, 2, 3}, store::get));
    }

    @Test
    void listsGoNoDeeperThanTwo() {
        Map<String, byte[]> store = new HashMap<>();
        byte[] part = text("part");
        String partName = HexFormat.of().formatHex(Packing.hash(part));
        store.put(partName, part);
        byte[] deepest = listOf(PacketPieces.PART, partName);
        String deepestName = HexFormat.of().formatHex(Packing.hash(deepest));
        store.put(deepestName, deepest);
        byte[] middle = listOf(PacketPieces.LIST, deepestName);
        String middleName = HexFormat.of().formatHex(Packing.hash(middle));
        store.put(middleName, middle);
        byte[] top = listOf(PacketPieces.LIST, middleName);
        assertThrows(RecordingFormatException.class, () -> PacketPieces.rebuild(top, store::get));
        // And none is ever made: a list inside a list holds only bytes and parts
        assertThrows(IllegalStateException.class, () -> new PacketPieces.Builder().list(inner -> inner.list(innermost -> {
        })));
    }

    @Test
    void aListCannotAskForMoreThanAnyPacketIs() throws Exception {
        Map<String, byte[]> store = new HashMap<>();
        byte[] part = new byte[1024 * 1024];
        String name = HexFormat.of().formatHex(Packing.hash(part));
        store.put(name, part);
        ByteArrayOutputStream list = new ByteArrayOutputStream();
        for (int i = 0; i < 17; i++) {
            list.write(listOf(PacketPieces.PART, name));
        }
        assertThrows(RecordingFormatException.class, () -> PacketPieces.rebuild(list.toByteArray(), store::get));
    }

    private static byte[] listOf(int kind, String name) {
        byte[] list = new byte[1 + Container.HASH_BYTES];
        list[0] = (byte) kind;
        System.arraycopy(HexFormat.of().parseHex(name), 0, list, 1, Container.HASH_BYTES);
        return list;
    }

}
