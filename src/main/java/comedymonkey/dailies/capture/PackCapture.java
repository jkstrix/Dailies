package comedymonkey.dailies.capture;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.packs.GamePacks;
import comedymonkey.dailies.packs.PackLog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Puts the server's resource packs into a recording (specification REC-19): each pack that comes into force or is
 * taken off, at its tick; in every snapshot, the packs in force; and the bytes of each pack, once, read from the
 * game's own downloaded copy by the recording's writer thread. Nothing is ever fetched from anywhere.
 */
final class PackCapture implements GamePacks.Listener {

    private static final PackCapture INSTANCE = new PackCapture();

    /** The recording the set below belongs to */
    private String recording = null;
    /** The packs whose bytes have been handed to this recording's writer */
    private final Set<String> stored = new HashSet<>();

    private PackCapture() {
    }

    static void init() {
        GamePacks.listen(INSTANCE);
        SnapshotExtensions.register(PackLog.ID, (out, codec) -> out.extension(PackLog.ID, PackLog.state(INSTANCE.inForce())));
    }

    @Override
    public void pushed(GamePacks.Entry entry, int index) {
        // While nothing is being written, the next snapshot says what is in force
        if (Recorder.get().capturing()) {
            Recorder.get().writeExtension(PackLog.ID, PackLog.pushed(this.keep(entry), index));
        }
    }

    @Override
    public void popped(GamePacks.Entry entry) {
        Recorder.get().writeExtension(PackLog.ID, PackLog.popped(entry.id()));
    }

    @Override
    public void notApplied(PackLog.Pack push, String reason) {
        Recorder.get().writeExtension(PackLog.ID, PackLog.notApplied(push, reason));
    }

    private List<PackLog.Pack> inForce() {
        List<PackLog.Pack> packs = new ArrayList<>();
        for (GamePacks.Entry entry : GamePacks.inForce()) {
            packs.add(this.keep(entry));
        }
        return packs;
    }

    /** Makes sure the recording being made holds the pack's bytes, and names them in what is written about the pack */
    private PackLog.Pack keep(GamePacks.Entry entry) {
        PackLog.Pack push = entry.push();
        Path path = entry.path();
        if (path == null) {
            return push;
        }
        String id = Recorder.get().recordingId();
        if (!Objects.equals(id, this.recording)) {
            this.recording = id;
            this.stored.clear();
        }
        String key = key(path);
        if (this.stored.add(key)) {
            Recorder.get().spool(writer -> {
                try {
                    for (RecordingManifest.Pack known : writer.manifest().packs) {
                        if (known.key.equals(key)) {
                            return;
                        }
                    }
                    RecordingManifest.Pack kept = writer.putFile(path);
                    kept.key = key;
                    writer.manifest().packs.add(kept);
                    // At once, so that a crash keeps it
                    writer.writeManifest();
                } catch (java.io.IOException e) {
                    // The game's copy has gone or cannot be read. The recording goes on; playback will say the pack is missing
                    Host.LOGGER.warn("Dailies: a server resource pack could not be put into the recording, so playback will be without it", e);
                }
            });
        }
        return new PackLog.Pack(push.id(), push.url(), push.hash(), push.required(), push.prompt(), key);
    }

    /**
     * What a pack's bytes go by in the recording. The game names a downloaded pack by the hash of its content, so the
     * same pack pushed again, under whatever id, has the same key and is stored once. A file from elsewhere (a
     * world's own pack) goes by a hash of where it is, which says nothing about the place.
     */
    static String key(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.matches("[0-9a-f]{40}")) {
            return name;
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(path.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8));
            return "local-" + HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

}
