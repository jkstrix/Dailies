package comedymonkey.dailies.playback;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.editor.EditorSession;
import comedymonkey.dailies.file.RecordingFile;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.packs.GamePacks;
import comedymonkey.dailies.packs.PackLog;
import comedymonkey.dailies.ui.Notices;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.server.DownloadedPackSource;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.repository.PackSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Shows a recording with the resource packs its server gave the recorder (specification PLAY-17). The recording says
 * which packs were in force at every tick; this makes the game's packs match, through the game's own way of holding
 * a server's packs, from files written out of the recording into a cache folder.
 * <p>
 * Changing the packs reloads the game's resources, which takes seconds, so it is done as seldom as can be: only when
 * the packs wanted differ from the packs the game has, and never in the middle of a jump. While it is being done the
 * recording stands still ({@link #busy}). All state belongs to the client thread.
 */
public final class PackPlayback implements GamePacks.Listener {

    private static final long STALE_MILLIS = 30L * 24 * 60 * 60 * 1000;
    private static final long GIVE_UP_MILLIS = 180_000;

    /** A recorded pack given to the game: under what id, and which stored bytes */
    private record Applied(UUID id, String sha256) {
    }

    private static final PackLog LOG = new PackLog();
    private static final List<Applied> APPLIED = new ArrayList<>();
    /** Taken off, and still in force until the reload that does it has finished */
    private static final Set<UUID> LEAVING = new HashSet<>();
    /** Content keys and hashes of this recording's packs that cannot be applied */
    private static final Set<String> FAILED = new LinkedHashSet<>();
    /** Where each pack's file is in the cache, by its hash */
    private static final Map<String, Path> FILES = new HashMap<>();

    private static String recording = null;
    private static CompletableFuture<Map<String, Object>> preparing = null;
    private static long actedAt = 0;
    private static volatile boolean busy = false;
    /** From a change of packs until the game's loading screen, which it draws over the picture meanwhile, has gone */
    private static volatile boolean covered = false;
    private static volatile int inForceCount = 0;
    private static volatile int changes = 0;

    /** The person's own choice of packs, noted before the first recorded pack is applied */
    private static List<String> ownPacks = null;
    private static List<String> ownIncompatible = null;
    /** A recorded pack broke the reload, at which the game throws away the person's own choice of packs */
    private static boolean ownPacksLost = false;

    private PackPlayback() {
    }

    static void init() {
        GamePacks.listen(new PackPlayback());
        PlaybackExtensions.register(PackLog.ID, new PlaybackExtensions.Extension() {
            @Override
            public void play(byte[] data, int from, int length, boolean skipping) {
                if (LOG.apply(data, from, length) && Playback.active() && !shas(wanted()).equals(appliedShas())) {
                    // Known at once, so that an export does not take a picture with the packs of a moment ago
                    hold();
                }
            }

            @Override
            public void reset() {
                // The snapshot about to be read says what is in force; a recording from before packs were kept says nothing
                LOG.clear();
            }
        });
    }

    // ------------------------------------------------------------------ What others may ask

    /** Whether the game's packs are being brought into line with the recording. Any thread */
    public static boolean busy() {
        return busy;
    }

    /**
     * Whether the picture is not yet the recording's own: the packs are being changed, or the game's loading screen is
     * still fading from the last change. No picture may be exported meanwhile. Any thread
     */
    public static boolean covered() {
        return covered;
    }

    private static void hold() {
        busy = true;
        covered = true;
    }

    /** How many recorded packs the game has in force. Any thread */
    public static int applied() {
        return inForceCount;
    }

    /** How many times the game's packs have been changed for a recording, each of which reloads its resources. Any thread */
    public static int changes() {
        return changes;
    }

    /** How many packs the recording has in force at the tick it is at. Client thread */
    public static int recorded() {
        return LOG.inForce().size();
    }

    /** The packs of the open recording that could not be applied, by key or hash. Client thread */
    public static List<String> failed() {
        return List.copyOf(FAILED);
    }

    public static Path cacheFolder() {
        return Host.get().dataDirectory().resolve("pack-cache");
    }

    // ------------------------------------------------------------------ The work

    /**
     * Once a frame while a recording is open, outside any game tick.
     *
     * @param still whether the recording is at the tick it is meant to be at, with no jump under way
     */
    static void frame(Minecraft minecraft, boolean still) {
        try {
            sync(minecraft, still);
        } catch (Throwable t) {
            Host.LOGGER.error("Dailies: the recording's resource packs could not be applied; it plays without them", t);
            for (PackLog.Pack pack : LOG.inForce()) {
                FAILED.add(pack.content());
            }
            preparing = null;
            busy = false;
            covered = false;
        }
    }

    private static void sync(Minecraft minecraft, boolean still) {
        String open = Playback.recordingId();
        if (!Objects.equals(open, recording)) {
            recording = open;
            FAILED.clear();
            preparing = null;
        }
        if (preparing != null) {
            if (!preparing.isDone()) {
                hold();
                return;
            }
            Map<String, Object> results = preparing.getNow(Map.of());
            preparing = null;
            results.forEach((sha, result) -> {
                if (result instanceof Path file) {
                    FILES.put(sha, file);
                } else {
                    fail(sha, String.valueOf(result));
                }
            });
        }

        DownloadedPackSource source = minecraft.getDownloadedPackSource();
        LEAVING.removeIf(id -> !GamePacks.inForce(id));
        boolean settled = LEAVING.isEmpty();
        int inForce = 0;
        for (Applied pack : APPLIED) {
            if (GamePacks.inForce(pack.id)) {
                inForce++;
            } else {
                settled = false;
            }
        }
        inForceCount = inForce;
        if (!settled) {
            if (System.currentTimeMillis() - actedAt > GIVE_UP_MILLIS) {
                // The game never said what became of them. Carry on without
                for (Applied pack : new ArrayList<>(APPLIED)) {
                    if (!GamePacks.inForce(pack.id)) {
                        APPLIED.remove(pack);
                        source.popPack(pack.id);
                        fail(pack.sha256, "the game did not load it");
                    }
                }
                LEAVING.clear();
            }
            hold();
            return;
        }
        if (ownPacksLost && minecraft.getOverlay() == null) {
            restoreOwnPacks(minecraft);
        }

        List<RecordingManifest.Pack> wanted = wanted();
        List<String> wantedShas = shas(wanted);
        if (wantedShas.equals(appliedShas())) {
            // The recording can run on while the game's loading screen fades, but nothing may take a picture through it
            busy = false;
            covered = covered && minecraft.getOverlay() != null;
            return;
        }
        hold();
        if (!still) {
            // Wherever the jump lands is what matters: one reload there, or none
            return;
        }
        int same = 0;
        while (same < APPLIED.size() && same < wanted.size() && APPLIED.get(same).sha256.equals(wantedShas.get(same))) {
            same++;
        }
        List<RecordingManifest.Pack> missing = new ArrayList<>();
        for (RecordingManifest.Pack pack : wanted.subList(same, wanted.size())) {
            if (!FILES.containsKey(pack.sha256) || !Files.isRegularFile(FILES.get(pack.sha256))) {
                missing.add(pack);
            }
        }
        if (!missing.isEmpty()) {
            prepare(missing, wantedShas);
            return;
        }

        if (ownPacks == null) {
            ownPacks = List.copyOf(minecraft.options.resourcePacks);
            ownIncompatible = List.copyOf(minecraft.options.incompatibleResourcePacks);
        }
        // As a server's packs, already agreed to, and with nobody to report back to
        source.packType = PackSource.SERVER;
        source.allowServerPacks();
        // A pack can only be added on top, so everything above the first difference comes off and goes back on
        while (APPLIED.size() > same) {
            Applied pack = APPLIED.remove(APPLIED.size() - 1);
            LEAVING.add(pack.id);
            source.popPack(pack.id);
        }
        for (RecordingManifest.Pack pack : wanted.subList(same, wanted.size())) {
            UUID id = UUID.randomUUID();
            APPLIED.add(new Applied(id, pack.sha256));
            source.pushLocalPack(id, FILES.get(pack.sha256));
        }
        actedAt = System.currentTimeMillis();
        changes++;
        Host.LOGGER.info("Dailies: playback is changing the server resource packs in force to {} of the recording's", APPLIED.size());
    }

    /** The stored packs that should be in force now, bottom first */
    private static List<RecordingManifest.Pack> wanted() {
        RecordingManifest manifest = Playback.manifest();
        if (manifest == null || EditorSession.open() && !EditorSession.scene().looks().serverResourcePacks) {
            return List.of();
        }
        List<RecordingManifest.Pack> wanted = new ArrayList<>();
        for (String key : LOG.contents()) {
            if (FAILED.contains(key)) {
                continue;
            }
            RecordingManifest.Pack stored = null;
            for (RecordingManifest.Pack pack : manifest.packs) {
                if (pack.key.equals(key)) {
                    stored = pack;
                }
            }
            if (stored == null) {
                fail(key, "its bytes are not in the recording");
            } else if (!FAILED.contains(stored.sha256)) {
                wanted.add(stored);
            }
        }
        return wanted;
    }

    private static List<String> shas(List<RecordingManifest.Pack> packs) {
        List<String> shas = new ArrayList<>();
        for (RecordingManifest.Pack pack : packs) {
            shas.add(pack.sha256);
        }
        return shas;
    }

    private static List<String> appliedShas() {
        List<String> shas = new ArrayList<>();
        for (Applied pack : APPLIED) {
            shas.add(pack.sha256);
        }
        return shas;
    }

    /** Writes packs out of the recording into the cache folder, away from the game's thread */
    private static void prepare(List<RecordingManifest.Pack> packs, List<String> inUse) {
        RecordingFile file = Playback.file();
        Path folder = cacheFolder();
        if (file == null) {
            return;
        }
        Set<String> keep = new HashSet<>(inUse);
        keep.addAll(appliedShas());
        preparing = CompletableFuture.supplyAsync(() -> {
            Map<String, Object> results = new HashMap<>();
            clean(folder, keep);
            for (RecordingManifest.Pack pack : packs) {
                try {
                    Path target = folder.resolve(pack.sha256 + ".zip");
                    // A file here is named by the hash of what is in it, so one of the right length is the right one
                    if (!Files.isRegularFile(target) || Files.size(target) != pack.size) {
                        file.extractFile(pack, target);
                    }
                    try {
                        Files.setLastModifiedTime(target, FileTime.from(Instant.now()));
                    } catch (IOException ignored) {
                        // Only used to tell which files have not been wanted for a long time
                    }
                    results.put(pack.sha256, target);
                } catch (Throwable t) {
                    Host.LOGGER.warn("Dailies: a resource pack could not be written out of the recording", t);
                    results.put(pack.sha256, t.getMessage() == null ? t.toString() : t.getMessage());
                }
            }
            return results;
        }, Util.ioPool());
    }

    /** Removes packs nobody has opened a recording with for a month, and anything left half written */
    private static void clean(Path folder, Set<String> keep) {
        if (!Files.isDirectory(folder)) {
            return;
        }
        long now = System.currentTimeMillis();
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                try {
                    long age = now - Files.getLastModifiedTime(file).toMillis();
                    boolean stale = name.endsWith(".part") ? age > 60 * 60 * 1000 : age > STALE_MILLIS && !keep.contains(name.replace(".zip", ""));
                    if (stale) {
                        Files.deleteIfExists(file);
                    }
                } catch (IOException inUse) {
                    // Still open somewhere: next time
                }
            }
        } catch (IOException e) {
            // Nothing to clean
        }
    }

    private static void fail(String what, String why) {
        if (FAILED.add(what)) {
            Host.LOGGER.warn("Dailies: a server resource pack of the recording ({}) cannot be applied, so playback goes on without it: {}", what, why);
            Notices.show(Component.translatable("dailies.notice.pack_failed"));
        }
    }

    /** The recording is closed: the game goes back to the person's own packs alone */
    static void closed(Minecraft minecraft) {
        preparing = null;
        recording = null;
        LOG.clear();
        FAILED.clear();
        try {
            DownloadedPackSource source = minecraft.getDownloadedPackSource();
            for (Applied pack : APPLIED) {
                source.popPack(pack.id);
            }
            if (ownPacksLost) {
                restoreOwnPacks(minecraft);
            }
        } catch (Throwable t) {
            Host.LOGGER.warn("Dailies: the recording's resource packs could not be taken off cleanly", t);
        }
        APPLIED.clear();
        LEAVING.clear();
        ownPacks = null;
        ownIncompatible = null;
        inForceCount = 0;
        busy = false;
        covered = false;
    }

    private static void restoreOwnPacks(Minecraft minecraft) {
        ownPacksLost = false;
        if (ownPacks == null || minecraft.options.resourcePacks.equals(ownPacks)) {
            return;
        }
        Host.LOGGER.info("Dailies: putting back your own resource packs, which the game dropped when a recorded pack failed to load");
        minecraft.options.resourcePacks.clear();
        minecraft.options.resourcePacks.addAll(ownPacks);
        minecraft.options.incompatibleResourcePacks.clear();
        minecraft.options.incompatibleResourcePacks.addAll(ownIncompatible);
        minecraft.options.save();
        minecraft.options.loadSelectedResourcePacks(minecraft.getResourcePackRepository());
        minecraft.reloadResourcePacks();
    }

    // ------------------------------------------------------------------ Told by the game

    @Override
    public void pushed(GamePacks.Entry entry, int index) {
    }

    @Override
    public void popped(GamePacks.Entry entry) {
        // Taken off by the game itself, as it does on leaving a world
        if (!LEAVING.contains(entry.id())) {
            APPLIED.removeIf(pack -> pack.id.equals(entry.id()));
        }
    }

    @Override
    public void notApplied(PackLog.Pack push, String reason) {
        for (Applied pack : new ArrayList<>(APPLIED)) {
            if (pack.id.equals(push.id())) {
                APPLIED.remove(pack);
                fail(pack.sha256, "the game could not load it (" + reason + ")");
                if (reason.equals("ACTIVATION_FAILED")) {
                    ownPacksLost = true;
                }
            }
        }
    }

}
