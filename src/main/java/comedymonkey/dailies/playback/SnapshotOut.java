package comedymonkey.dailies.playback;

import comedymonkey.dailies.capture.GameCodec;
import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.file.Assembler;
import comedymonkey.dailies.file.RecordingFile;
import comedymonkey.dailies.file.Records;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * Where the snapshot writer puts a description of a playback world: into memory, as the records of a snapshot, with
 * whatever shared data the recording being played does not already hold kept beside them. The result goes into a
 * trimmed recording.
 */
final class SnapshotOut implements Recorder.Out {

    private final GameCodec codec;
    private final RecordingFile source;
    private final Records.Writer records = new Records.Writer();
    private final Map<String, byte[]> shared = new HashMap<>();

    SnapshotOut(GameCodec codec, RecordingFile source) {
        this.codec = codec;
        this.source = source;
    }

    Assembler.Snapshot finish() {
        return new Assembler.Snapshot(this.records.take(), this.shared);
    }

    private byte[] keep(byte[] data) {
        byte[] hash;
        try {
            hash = MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        String name = HexFormat.of().formatHex(hash);
        if (!this.source.hasShared(name)) {
            this.shared.putIfAbsent(name, data);
        }
        return hash;
    }

    @Override
    public void packet(Packet<? super ClientGamePacketListener> packet) {
        this.records.record(Records.PACKET, this.codec.encode(packet));
    }

    @Override
    public void sharedRef(int kind, byte[] hash) {
        this.records.record(kind, hash);
    }

    @Override
    public byte[] sharedPacket(byte[] encoded) {
        byte[] hash = this.keep(encoded);
        this.records.record(Records.SHARED_PACKET, hash);
        return hash;
    }

    @Override
    public void sharedRecord(int kind, byte[] data) {
        this.records.record(kind, this.keep(data));
    }

    @Override
    public void frame(byte[] frame) {
        this.records.record(Records.PACKET, frame);
    }

    @Override
    public void payloadFrame(String id, byte[] frame) {
        this.records.named(Records.PAYLOAD, id, frame, 0, frame.length);
    }

    @Override
    public void extension(String id, byte[] data) {
        this.records.named(Records.EXTENSION, id, data, 0, data.length);
    }

    @Override
    public void bundleBegin() {
        this.records.record(Records.BUNDLE_BEGIN);
    }

    @Override
    public void bundleEnd() {
        this.records.record(Records.BUNDLE_END);
    }

}
