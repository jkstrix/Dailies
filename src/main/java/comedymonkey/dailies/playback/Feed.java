package comedymonkey.dailies.playback;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.PlayerInfoPackets;
import comedymonkey.dailies.capture.WorldData;
import comedymonkey.dailies.file.RecordingFile;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.file.Records;
import net.minecraft.client.Minecraft;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Reads a recording's records in order and gives each to the game at its tick. It knows two things about where it
 * is: how many ticks of the recording have been played (the position), and how many more the record just read says
 * may pass before anything else happens.
 */
final class Feed {

    private final RecordingFile file;
    private final List<RecordingManifest.Segment> segments;
    private Session session;
    private Records.Reader reader;
    private int segmentIndex = -1;
    private int position = 0;
    private int ticksOwed = 0;
    private List<Packet<? super ClientGamePacketListener>> bundle = null;
    /** Set when play runs into a segment that does not follow on from the one before */
    private int reloadWanted = -1;
    private final Set<String> warned = new HashSet<>();
    boolean skipping = false;

    Feed(RecordingFile file) {
        this.file = file;
        this.segments = file.segments();
    }

    Session session() {
        return this.session;
    }

    int position() {
        return this.position;
    }

    /** The index of a segment the world has to be rebuilt from before play can go on, or -1 */
    int reloadWanted() {
        return this.reloadWanted;
    }

    int segmentIndexAt(int tick) {
        int found = 0;
        for (int i = 0; i < this.segments.size(); i++) {
            if (this.segments.get(i).startTick <= tick) {
                found = i;
            }
        }
        return found;
    }

    int segmentStart(int index) {
        return this.segments.get(index).startTick;
    }

    int segmentIndex() {
        return this.segmentIndex;
    }

    /**
     * Rebuilds the world from a segment's snapshot. Must not be called from inside a game tick: the game draws a
     * frame of its own while it changes worlds.
     */
    void load(int index, Minecraft minecraft) throws IOException {
        this.closeReader();
        this.reloadWanted = -1;
        this.bundle = null;
        this.segmentIndex = index;
        RecordingManifest.Segment segment = this.segments.get(index);
        this.position = segment.startTick;
        this.ticksOwed = 0;
        this.reader = new Records.Reader(this.file.openSegment(segment.number));

        PlaybackExtensions.resetAll();
        if (minecraft.level != null) {
            minecraft.clearClientLevel(new net.minecraft.client.gui.screens.GenericMessageScreen(net.minecraft.network.chat.Component.translatable("dailies.playback.jumping")));
        }
        if (this.session != null) {
            this.session.close();
            this.session = null;
        }
        // Everything up to the first tick record is the snapshot and whatever happened on its own tick
        while (this.reader.next()) {
            if (this.reader.kind() == Records.TICK) {
                this.ticksOwed += this.reader.number();
                break;
            }
            this.handle(minecraft);
        }
        if (this.session == null) {
            throw new IOException("Segment " + segment.number + " of the recording does not start with a description of its world");
        }
    }

    /**
     * Moves the recording on by one tick and applies everything recorded on that tick.
     *
     * @return false at the end of the recording, or when the world has to be rebuilt first (see {@link #reloadWanted})
     */
    boolean advance(Minecraft minecraft) throws IOException {
        if (this.reloadWanted >= 0) {
            return false;
        }
        while (this.ticksOwed == 0) {
            if (!this.nextRecord()) {
                return false;
            }
            if (this.reader.kind() == Records.TICK) {
                this.ticksOwed += this.reader.number();
            } else {
                this.handle(minecraft);
            }
        }
        this.ticksOwed--;
        this.position++;
        while (this.ticksOwed == 0) {
            if (!this.nextRecord()) {
                break;
            }
            if (this.reader.kind() == Records.TICK) {
                this.ticksOwed += this.reader.number();
            } else {
                this.handle(minecraft);
            }
        }
        return true;
    }

    /** The next record, moving into the next segment when this one is finished */
    private boolean nextRecord() throws IOException {
        while (true) {
            if (this.reader.next()) {
                return true;
            }
            if (this.segmentIndex + 1 >= this.segments.size()) {
                return false;
            }
            this.closeReader();
            this.segmentIndex++;
            this.reader = new Records.Reader(this.file.openSegment(this.segments.get(this.segmentIndex).number));
            // The world is already as the next snapshot describes it, unless the recording says otherwise
            boolean inSnapshot = false;
            while (this.reader.next()) {
                int kind = this.reader.kind();
                if (kind == Records.SNAPSHOT_BEGIN) {
                    inSnapshot = true;
                } else if (kind == Records.DISCONTINUITY && inSnapshot) {
                    this.reloadWanted = this.segmentIndex;
                    return false;
                } else if (kind == Records.SNAPSHOT_END) {
                    break;
                } else if (!inSnapshot) {
                    return true;
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void handle(Minecraft minecraft) throws IOException {
        switch (this.reader.kind()) {
            case Records.PACKET -> this.packet(this.reader.payload(), 0, this.reader.length(), minecraft);
            case Records.SHARED_PACKET -> {
                byte[] shared = this.file.shared(HexFormat.of().formatHex(this.reader.payload(), 0, this.reader.length()));
                this.packet(shared, 0, shared.length, minecraft);
            }
            case Records.PAYLOAD -> {
                String id = this.reader.name();
                if (PayloadRules.ignored(id) || this.session == null) {
                    return;
                }
                // A message whose mod is not installed here is dropped without a word (PLAY-12)
                ResourceLocation location = ResourceLocation.tryParse(id);
                if (location == null || !NetworkRegistry.hasChannel(this.session.connection, ConnectionProtocol.PLAY, location)) {
                    return;
                }
                int from = this.reader.bodyStart();
                this.packet(this.reader.payload(), from, this.reader.length() - from, minecraft);
            }
            case Records.WORLD -> {
                String name = HexFormat.of().formatHex(this.reader.payload(), 0, this.reader.length());
                if (this.session == null || !this.session.worldName.equals(name)) {
                    if (this.session != null) {
                        this.session.close();
                    }
                    this.session = Session.create(minecraft, name, WorldData.fromBytes(this.file.shared(name)));
                }
            }
            case Records.BUNDLE_BEGIN -> this.bundle = new ArrayList<>();
            case Records.BUNDLE_END -> {
                List<Packet<? super ClientGamePacketListener>> parts = this.bundle;
                this.bundle = null;
                if (parts != null && !parts.isEmpty()) {
                    this.apply(new ClientboundBundlePacket(parts), minecraft);
                }
            }
            case Records.EXTENSION -> {
                String id = this.reader.name();
                PlaybackExtensions.Extension extension = PlaybackExtensions.find(id);
                if (extension != null) {
                    try {
                        int from = this.reader.bodyStart();
                        extension.play(this.reader.payload(), from, this.reader.length() - from, this.skipping);
                    } catch (Throwable t) {
                        this.warnOnce("extension " + id, t);
                    }
                }
            }
            default -> {
                // Snapshot markers, and kinds this version does not know
            }
        }
    }

    private void packet(byte[] bytes, int from, int length, Minecraft minecraft) {
        if (this.session == null) {
            return;
        }
        Packet<? super ClientGamePacketListener> packet;
        try {
            packet = this.session.codec.decode(bytes, from, length);
        } catch (Throwable t) {
            // Something in it this game does not have: another mod set, usually (PLAY-5)
            this.warnOnce("a packet that cannot be read", t);
            return;
        }
        if (this.bundle != null) {
            this.bundle.add(packet);
        } else {
            this.apply(packet, minecraft);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void apply(Packet<? super ClientGamePacketListener> packet, Minecraft minecraft) {
        try {
            ((Packet) packet).handle(this.session.listener);
            if (packet instanceof ClientboundLoginPacket) {
                // The viewer is a spectator who is in nobody's tab list
                ClientboundPlayerInfoUpdatePacket viewer = PlayerInfoPackets.build(this.session.codec, PlayerInfoPackets.allButChat(),
                    List.of(new ClientboundPlayerInfoUpdatePacket.Entry(Session.VIEWER.getId(), Session.VIEWER, false, 0, GameType.SPECTATOR, null, null)));
                viewer.handle(this.session.listener);
                Playback.viewerJoined(minecraft);
            }
        } catch (Throwable t) {
            this.warnOnce(packet.getClass().getSimpleName(), t);
        }
    }

    private void warnOnce(String what, Throwable problem) {
        if (this.warned.add(what)) {
            Host.LOGGER.warn("Dailies: playback could not show {} and carried on without it. Said once per kind", what, problem);
        }
    }

    /** What could not be shown so far, for the list a person is given once (PLAY-5) */
    Set<String> problems() {
        return this.warned;
    }

    private void closeReader() {
        if (this.reader != null) {
            try {
                this.reader.close();
            } catch (IOException ignored) {
                // Nothing to save
            }
            this.reader = null;
        }
    }

    void close() {
        this.closeReader();
        if (this.session != null) {
            this.session.close();
            this.session = null;
        }
    }

}
