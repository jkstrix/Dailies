package comedymonkey.dailies.capture;

import comedymonkey.dailies.file.RecordingFile;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.file.Records;
import net.minecraft.network.protocol.Packet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads a whole recording and counts what is in it, decoding every packet on the way. The scripted test uses it to
 * prove what a recording holds and, as importantly, what it does not.
 */
public final class Audit {

    public final Map<String, Integer> packets = new TreeMap<>();
    public final Map<String, Integer> payloads = new TreeMap<>();
    public final Map<String, Integer> extensions = new TreeMap<>();
    public final List<String> problems = new ArrayList<>();
    public int snapshots = 0;
    public int bundles = 0;
    public int sharedPackets = 0;
    public int snapshotPackets = 0;
    public int worlds = 0;
    /** Every packet in the order it was recorded, with the tick it belongs to. Only filled when asked for */
    public final List<Seen> seen = new ArrayList<>();

    public record Seen(int tick, boolean inSnapshot, Packet<?> packet) {
    }

    public int count(Class<?> packetClass) {
        return this.packets.getOrDefault(packetClass.getSimpleName(), 0);
    }

    /**
     * @param codec a codec for the registries the recording was made with
     * @param keep  whether to keep every decoded packet in {@link #seen}
     */
    public static Audit of(RecordingFile recording, GameCodec codec, boolean keep) throws IOException {
        Audit audit = new Audit();
        for (RecordingManifest.Segment segment : recording.segments()) {
            int tick = segment.startTick;
            boolean inSnapshot = false;
            try (Records.Reader reader = new Records.Reader(recording.openSegment(segment.number))) {
                while (reader.next()) {
                    switch (reader.kind()) {
                        case Records.TICK -> tick += reader.number();
                        case Records.SNAPSHOT_BEGIN -> {
                            audit.snapshots++;
                            inSnapshot = true;
                            if (reader.number() != segment.startTick) {
                                audit.problems.add("Segment " + segment.number + " starts at tick " + segment.startTick + " but its snapshot says " + reader.number());
                            }
                        }
                        case Records.SNAPSHOT_END -> inSnapshot = false;
                        case Records.BUNDLE_BEGIN -> audit.bundles++;
                        case Records.PACKET -> audit.packet(codec, reader.payload(), 0, reader.length(), tick, inSnapshot, keep);
                        case Records.SHARED_PACKET -> {
                            audit.sharedPackets++;
                            byte[] shared = recording.shared(java.util.HexFormat.of().formatHex(reader.payload(), 0, reader.length()));
                            audit.packet(codec, shared, 0, shared.length, tick, inSnapshot, keep);
                        }
                        case Records.WORLD -> {
                            audit.worlds++;
                            WorldData.fromBytes(recording.shared(java.util.HexFormat.of().formatHex(reader.payload(), 0, reader.length())));
                        }
                        case Records.PAYLOAD -> audit.payloads.merge(reader.name(), 1, Integer::sum);
                        case Records.EXTENSION -> audit.extensions.merge(reader.name(), 1, Integer::sum);
                        default -> {
                        }
                    }
                }
            }
            if (tick != segment.endTick) {
                audit.problems.add("Segment " + segment.number + " should end at tick " + segment.endTick + " but its records end at " + tick);
            }
        }
        return audit;
    }

    private void packet(GameCodec codec, byte[] bytes, int from, int length, int tick, boolean inSnapshot, boolean keep) {
        try {
            Packet<?> packet = codec.decode(bytes, from, length);
            this.packets.merge(packet.getClass().getSimpleName(), 1, Integer::sum);
            if (inSnapshot) {
                this.snapshotPackets++;
            }
            if (PacketRules.dropped(packet)) {
                this.problems.add("A " + packet.getClass().getSimpleName() + " was recorded, which must never be");
            }
            if (keep) {
                this.seen.add(new Seen(tick, inSnapshot, packet));
            }
        } catch (RuntimeException e) {
            this.problems.add("A packet at tick " + tick + " cannot be decoded: " + e);
        }
    }

}
