package comedymonkey.dailies.compat.distanthorizons;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.playback.Playback;
import it.unimi.dsi.fastutil.longs.Long2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Distant Horizons in playback (specification CMP-1). With it installed, playback shows its far terrain, built from
 * a level-of-detail store that belongs to the recording, kept in dailies/dh/&lt;recording id&gt;, so the data Distant
 * Horizons keeps for the player's real worlds and servers is never read or written by playback.
 * <p>
 * Three things are done, all through Distant Horizons' published API ({@code com.seibel.distanthorizons.api}), found
 * by name so that nothing is compiled against it:
 * <ul>
 *     <li>its save-folder override is given a folder of the recording's own whenever a playback world loads, and is left
 *     to choose for itself at every other time;</li>
 *     <li>each chunk the playback world takes in is handed to it a few frames later (once the game has lit it), so it can
 *     build that chunk's far version, which later frames of the same recording show beyond the view distance;</li>
 *     <li>exports ask {@link #settled} after the world is rebuilt, the same way they wait for the near terrain.</li>
 * </ul>
 * Made for the API of Distant Horizons 3.3 (API version 7). With another major version, or if any of what is used has
 * gone, one line is logged and Distant Horizons is left alone in playback.
 */
public final class DistantHorizonsCompat {

    private static final String API = "com.seibel.distanthorizons.api.";
    private static final int API_MAJOR = 7;
    /** Frames a chunk waits before it is handed over, so the game's light engine has reached it */
    private static final int FRAMES_BEFORE_TELLING = 5;
    private static final int TOLD_PER_FRAME = 32;
    /** Frames with nothing new handed over or confirmed before the far terrain counts as settled */
    private static final int QUIET_FRAMES = 20;
    private static final int CONFIRMS_PER_ASK = 4;
    private static final int CONFIRM_ATTEMPTS = 30;

    private static boolean installed = false;
    private static boolean setUp = false;
    private static volatile boolean working = false;
    private static boolean failed = false;

    /** The recording whose world is loaded now, or null outside playback. Read by Distant Horizons' own threads */
    private static volatile @Nullable String folderRecording = null;

    private static Object overrides, worldProxy, terrainRepo, configs;
    private static Method bind, getAllLoadedLevelWrappers, getWrappedMcObject, getDhIdentifier, getDhSaveFolder;
    private static Method overwriteChunkDataAsync, getAllTerrainDataAtChunkPos, createSoftCache;
    private static Method graphics, renderingEnabled, getValue;
    private static Field resultSuccess, resultMessage, resultPayload;
    private static Class<?> saveStructure;

    /** Chunk positions waiting to be handed over, with the frames each has waited */
    private static final Long2IntLinkedOpenHashMap waiting = new Long2IntLinkedOpenHashMap();
    /** Chunk positions handed over whose far version has not yet been seen in the store, with the times asked */
    private static final Long2IntLinkedOpenHashMap unconfirmed = new Long2IntLinkedOpenHashMap();
    private static long frames = 0;
    private static long lastChange = 0;
    private static int told = 0;
    private static boolean refusalTold = false;

    private static WeakReference<ClientLevel> wrappedLevel = new WeakReference<>(null);
    private static @Nullable Object levelWrapper = null;
    private static @Nullable Object cache = null;

    private DistantHorizonsCompat() {
    }

    public static void init() {
        if (!Host.get().isModLoaded("distanthorizons")) {
            return;
        }
        installed = true;
        // Distant Horizons finishes starting after the mods are built; by the first tick of the game it has
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            if (!setUp) {
                setUp = true;
                setUp();
            }
        });
        // Before Distant Horizons looks at the new world, which it does after the world object is made
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, (LevelEvent.Load event) -> {
            if (event.getLevel() instanceof ClientLevel) {
                folderRecording = Playback.active() ? Playback.recordingId() : null;
                bindFolders(folderRecording != null);
                forgetLevel();
            }
        });
        NeoForge.EVENT_BUS.addListener((ChunkEvent.Load event) -> {
            if (working && event.getLevel() instanceof ClientLevel && Playback.active()) {
                waiting.put(event.getChunk().getPos().toLong(), 0);
            }
        });
        NeoForge.EVENT_BUS.addListener((ChunkEvent.Unload event) -> {
            if (event.getLevel() instanceof ClientLevel) {
                long pos = event.getChunk().getPos().toLong();
                waiting.remove(pos);
                unconfirmed.remove(pos);
            }
        });
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Post event) -> frame());
        Playback.onClosing(() -> {
            waiting.clear();
            unconfirmed.clear();
            folderRecording = null;
            bindFolders(false);
            forgetLevel();
        });
    }

    /** The folder override, made once; bound to Distant Horizons only while a playback world is open */
    private static @Nullable Object folderOverride = null;
    private static Method unbind;
    private static boolean folderBound = false;

    /**
     * Binds the folder override for a playback world and takes it away again for every other. Distant Horizons 3.3
     * takes any bound folder override, even one that answers null, as the folder of old (2.3) data too, and then moves
     * that folder onto itself as it opens a server's world, which fails on Windows: no far terrain, and "Client level
     * loading failed" in the chat every frame, on every server (the owner, 18 November). So outside playback nothing of
     * Dailies' is bound, and Distant Horizons works as if Dailies were not there
     */
    private static void bindFolders(boolean wanted) {
        if (!working || folderOverride == null || wanted == folderBound) {
            return;
        }
        try {
            if (wanted) {
                bind.invoke(overrides, saveStructure, folderOverride);
            } else {
                unbind.invoke(overrides, saveStructure, folderOverride);
            }
            folderBound = wanted;
        } catch (Throwable t) {
            Host.LOGGER.warn("Dailies: could not {} the Distant Horizons folder for playback: {}", wanted ? "set" : "take away", t.toString());
        }
    }

    private static void setUp() {
        try {
            Class<?> dhApi = Class.forName(API + "DhApi");
            int major = (int) dhApi.getMethod("getApiMajorVersion").invoke(null);
            String version = String.valueOf(dhApi.getMethod("getModVersion").invoke(null));
            if (major != API_MAJOR) {
                Host.LOGGER.warn("Dailies: Distant Horizons {} has API version {}, and Dailies knows version {} (Distant Horizons 3.3); playback leaves it alone and shows no far terrain of its own", version, major, API_MAJOR);
                return;
            }
            Class<?> delayed = Class.forName(API + "DhApi$Delayed");
            Class<?> overrideable = Class.forName(API + "interfaces.override.IDhApiOverrideable");
            Class<?> worldProxyType = Class.forName(API + "interfaces.world.IDhApiWorldProxy");
            Class<?> levelType = Class.forName(API + "interfaces.world.IDhApiLevelWrapper");
            Class<?> unsafe = Class.forName(API + "interfaces.IDhApiUnsafeWrapper");
            Class<?> repoType = Class.forName(API + "interfaces.data.IDhApiTerrainDataRepo");
            Class<?> cacheType = Class.forName(API + "interfaces.data.IDhApiTerrainDataCache");
            Class<?> configType = Class.forName(API + "interfaces.config.IDhApiConfig");
            Class<?> graphicsType = Class.forName(API + "interfaces.config.client.IDhApiGraphicsConfig");
            Class<?> valueType = Class.forName(API + "interfaces.config.IDhApiConfigValue");
            Class<?> result = Class.forName(API + "objects.DhApiResult");
            saveStructure = Class.forName(API + "interfaces.override.levelHandling.IDhApiSaveStructure");

            overrides = dhApi.getField("overrides").get(null);
            worldProxy = delayed.getField("worldProxy").get(null);
            terrainRepo = delayed.getField("terrainRepo").get(null);
            configs = delayed.getField("configs").get(null);
            if (overrides == null || worldProxy == null || terrainRepo == null || configs == null) {
                throw new IllegalStateException("Distant Horizons has not made its API ready");
            }
            // Found on the type the API declares, not on the class behind it, which is not part of the API
            bind = dhApi.getField("overrides").getType().getMethod("bind", Class.class, overrideable);
            getAllLoadedLevelWrappers = worldProxyType.getMethod("getAllLoadedLevelWrappers");
            getWrappedMcObject = unsafe.getMethod("getWrappedMcObject");
            getDhIdentifier = levelType.getMethod("getDhIdentifier");
            getDhSaveFolder = levelType.getMethod("getDhSaveFolder");
            overwriteChunkDataAsync = repoType.getMethod("overwriteChunkDataAsync", levelType, Object[].class);
            getAllTerrainDataAtChunkPos = repoType.getMethod("getAllTerrainDataAtChunkPos", levelType, int.class, int.class, cacheType);
            createSoftCache = repoType.getMethod("createSoftCache");
            graphics = configType.getMethod("graphics");
            renderingEnabled = graphicsType.getMethod("renderingEnabled");
            getValue = valueType.getMethod("getValue");
            resultSuccess = result.getField("success");
            resultMessage = result.getField("message");
            resultPayload = result.getField("payload");

            // The folder override: Distant Horizons asks it each time it opens a world, and keeps its own choice when told null
            InvocationHandler folders = (proxy, method, arguments) -> switch (method.getName()) {
                case "overrideFilePath" -> folderFor(arguments[1]);
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                case "toString" -> "Dailies: a playback world's own Distant Horizons folder";
                default -> method.isDefault() ? InvocationHandler.invokeDefault(proxy, method, arguments) : null;
            };
            // Made now, bound only while a playback world is open (see bindFolders)
            folderOverride = Proxy.newProxyInstance(saveStructure.getClassLoader(), new Class<?>[]{saveStructure}, folders);
            unbind = dhApi.getField("overrides").getType().getMethod("unbind", Class.class, overrideable);
            working = true;
            bindFolders(Playback.active());
            Host.LOGGER.info("Dailies: Distant Horizons {} found; playback keeps its far terrain in {}, apart from your own worlds' data", version, folderRoot());
        } catch (Throwable t) {
            Host.LOGGER.warn("Dailies: this Distant Horizons has not the API Dailies knows (that of 3.3), so playback leaves it alone and shows no far terrain of its own: {}", t.toString());
        }
    }

    private static Path folderRoot() {
        return Host.get().dataDirectory().resolve("dh");
    }

    /**
     * Where a playback world's far terrain is kept; null outside playback, which leaves Distant Horizons its own choice.
     * When the recording says where Distant Horizons kept the far terrain of the world it was made in, and that is still
     * there, a copy of it, kept by Dailies for that world and made afresh whenever the world's own is newer: playback
     * then shows the far terrain that was built while playing (the owner, 18 November: "distant horizons data isn't
     * loaded"), and writes only to its copy. Otherwise a store of the recording's own, built from the chunks it holds
     */
    private static @Nullable File folderFor(Object level) {
        String recording = folderRecording;
        if (recording == null) {
            return null;
        }
        String name = "level";
        try {
            name = String.valueOf(getDhIdentifier.invoke(level));
        } catch (Throwable t) {
            // The folder is still the recording's own, only not named after the dimension
        }
        File copied = copyOfWorld(level);
        if (copied != null) {
            return copied;
        }
        File folder = folderRoot().resolve(safe(recording)).resolve(safe(name)).toFile();
        folder.mkdirs();
        return folder;
    }

    private static final String DATABASE = "DistantHorizons.sqlite";

    /** Dailies' copy of the far terrain of the world recorded, for this dimension; null when there is none to copy */
    private static @Nullable File copyOfWorld(Object level) {
        try {
            comedymonkey.dailies.file.RecordingManifest manifest = Playback.manifest();
            Object wrapped = getWrappedMcObject.invoke(level);
            if (manifest == null || manifest.distantHorizonsFolders == null || !(wrapped instanceof net.minecraft.world.level.Level mc)) {
                return null;
            }
            String dimension = mc.dimension().location().toString();
            // Only a recording made here is believed, and only for a folder inside the game's folder: the name comes from
            // the recording's file, and a shared one could otherwise have this game copy and open a store from anywhere
            comedymonkey.dailies.file.RecordingFile open = Playback.file();
            if (open == null || FarTerrainFolders.shared(comedymonkey.dailies.capture.Recorder.folder(), open.file())) {
                return null;
            }
            Path source = FarTerrainFolders.recorded(gameFolder(), manifest.distantHorizonsFolders.get(dimension));
            if (source == null || !java.nio.file.Files.isRegularFile(source.resolve(DATABASE))) {
                return null;
            }
            Path copy = folderRoot().resolve("worlds").resolve(safe(Integer.toHexString(source.toString().toLowerCase(java.util.Locale.ROOT).hashCode())
                + "-" + source.getFileName())).resolve(safe(dimension));
            refresh(source, copy);
            return copy.toFile();
        } catch (Throwable t) {
            Host.LOGGER.warn("Dailies: the far terrain of the world recorded could not be copied for playback; it is built from the recording's chunks instead: {}", t.toString());
            return null;
        }
    }

    /** Copies the world's far terrain store, with the journal that may hold its latest writes, when it has changed since */
    private static void refresh(Path source, Path copy) throws java.io.IOException {
        java.nio.file.Files.createDirectories(copy);
        String stamp = stamp(source);
        Path stampFile = copy.resolve("dailies-source.txt");
        if (java.nio.file.Files.isRegularFile(copy.resolve(DATABASE)) && java.nio.file.Files.isRegularFile(stampFile)
            && java.nio.file.Files.readString(stampFile).equals(stamp)) {
            return;
        }
        long started = System.nanoTime();
        long bytes = 0;
        for (String part : new String[]{DATABASE, DATABASE + "-wal", DATABASE + "-shm"}) {
            Path from = source.resolve(part);
            Path to = copy.resolve(part);
            if (java.nio.file.Files.isRegularFile(from)) {
                Path partial = copy.resolve(part + ".copying");
                java.nio.file.Files.copy(from, partial, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                java.nio.file.Files.move(partial, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                bytes += java.nio.file.Files.size(to);
            } else {
                java.nio.file.Files.deleteIfExists(to);
            }
        }
        java.nio.file.Files.writeString(stampFile, stamp);
        Host.LOGGER.info("Dailies: copied the far terrain of {} for playback ({} MB in {} ms)", source, bytes / (1024 * 1024), (System.nanoTime() - started) / 1_000_000);
    }

    /** What the world's own store is now: when any of its files changes, the copy is made afresh */
    private static String stamp(Path source) throws java.io.IOException {
        StringBuilder stamp = new StringBuilder(source.toString());
        for (String part : new String[]{DATABASE, DATABASE + "-wal"}) {
            Path file = source.resolve(part);
            if (java.nio.file.Files.isRegularFile(file)) {
                stamp.append('|').append(part).append(':').append(java.nio.file.Files.size(file)).append(':').append(java.nio.file.Files.getLastModifiedTime(file).toMillis());
            }
        }
        return stamp.toString();
    }

    private static Path gameFolder() {
        return Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize();
    }

    /**
     * Where Distant Horizons keeps the far terrain of each dimension of the world being played now, by dimension id,
     * relative to the game's folder; a folder outside it is left out. Empty without Distant Horizons, and in playback. Client thread
     */
    public static java.util.Map<String, String> liveFolders() {
        java.util.Map<String, String> folders = new java.util.TreeMap<>();
        if (!working || Playback.active()) {
            return folders;
        }
        try {
            Path game = gameFolder();
            Path ours = folderRoot().toAbsolutePath().normalize();
            for (Object wrapper : (Iterable<?>) getAllLoadedLevelWrappers.invoke(worldProxy)) {
                Object wrapped = getWrappedMcObject.invoke(wrapper);
                Object folder = getDhSaveFolder.invoke(wrapper);
                if (!(wrapped instanceof net.minecraft.world.level.Level level) || !(folder instanceof File file)) {
                    continue;
                }
                Path path = file.toPath().toAbsolutePath().normalize();
                if (path.startsWith(ours)) {
                    continue;
                }
                // A folder outside the game's folder is not written down: playback believes only one inside it
                String relative = FarTerrainFolders.toRecord(game, path);
                if (relative != null) {
                    folders.put(level.dimension().location().toString(), relative);
                }
            }
        } catch (Throwable t) {
            // Nothing to say where the far terrain is: playback builds its own
        }
        return folders;
    }

    private static String safe(String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isEmpty() || cleaned.chars().allMatch(c -> c == '.') ? "_" : cleaned;
    }

    private static void forgetLevel() {
        wrappedLevel = new WeakReference<>(null);
        levelWrapper = null;
        cache = null;
    }

    private static void fail(Throwable problem) {
        if (!failed) {
            failed = true;
            working = false;
            Host.LOGGER.warn("Dailies: Distant Horizons answered in a way Dailies does not know, so playback stops handing it chunks. Said once: {}", problem.toString());
        }
    }

    /** Distant Horizons' wrapper of the world being played back, or null until it has opened it. Client thread */
    private static @Nullable Object wrapperFor(ClientLevel level) throws Exception {
        if (wrappedLevel.get() == level && levelWrapper != null) {
            return levelWrapper;
        }
        Object found = null;
        for (Object wrapper : (Iterable<?>) getAllLoadedLevelWrappers.invoke(worldProxy)) {
            if (getWrappedMcObject.invoke(wrapper) == level) {
                found = wrapper;
                break;
            }
        }
        if (found != null) {
            wrappedLevel = new WeakReference<>(level);
            levelWrapper = found;
            cache = createSoftCache.invoke(terrainRepo);
        }
        return found;
    }

    // --- Each frame (client thread) ---

    private static void frame() {
        frames++;
        if (!working || !Playback.loaded() || Playback.jumping() || waiting.isEmpty()) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        try {
            Object wrapper = wrapperFor(level);
            if (wrapper == null) {
                // Distant Horizons has not opened this world yet; the chunks wait for it
                return;
            }
            List<Long> due = new ArrayList<>();
            for (Long2IntMap.Entry entry : waiting.long2IntEntrySet()) {
                int age = entry.getIntValue() + 1;
                entry.setValue(age);
                if (age >= FRAMES_BEFORE_TELLING && due.size() < TOLD_PER_FRAME) {
                    due.add(entry.getLongKey());
                }
            }
            for (long pos : due) {
                waiting.remove(pos);
                LevelChunk chunk = (LevelChunk) level.getChunkSource().getChunk(ChunkPos.getX(pos), ChunkPos.getZ(pos), ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }
                Object answer = overwriteChunkDataAsync.invoke(terrainRepo, wrapper, new Object[]{chunk, level});
                if (answer != null && !resultSuccess.getBoolean(answer)) {
                    if (!refusalTold) {
                        refusalTold = true;
                        Host.LOGGER.info("Dailies: Distant Horizons turned down a chunk of the playback world: {}. Said once", resultMessage.get(answer));
                    }
                    continue;
                }
                told++;
                unconfirmed.put(pos, 0);
                lastChange = frames;
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    // --- For exports and the tests ---

    /** Whether Distant Horizons is installed, the API is the one Dailies knows, and nothing has gone wrong */
    public static boolean working() {
        return working;
    }

    public static boolean installed() {
        return installed;
    }

    /** How many chunks of playback worlds have been handed to Distant Horizons since the game started */
    public static int chunksTold() {
        return told;
    }

    /**
     * Whether the far terrain has caught up with the world being played back: every chunk the world took in has been
     * handed over, and is in the store, and some frames have passed since. True whenever there is nothing to wait for:
     * no Distant Horizons, its drawing switched off, or outside playback. Client thread
     */
    public static boolean settled() {
        if (!working || !Playback.loaded()) {
            return true;
        }
        try {
            Object value = getValue.invoke(renderingEnabled.invoke(graphics.invoke(configs)));
            if (Boolean.FALSE.equals(value)) {
                return true;
            }
            if (!waiting.isEmpty()) {
                return false;
            }
            confirmSome();
        } catch (Throwable t) {
            fail(t);
            return true;
        }
        return unconfirmed.isEmpty() && frames - lastChange >= QUIET_FRAMES;
    }

    /** Asks the store about a few of the chunks handed over: those it holds are done with */
    private static void confirmSome() throws Exception {
        ClientLevel level = Minecraft.getInstance().level;
        Object wrapper = level == null ? null : wrapperFor(level);
        if (wrapper == null || unconfirmed.isEmpty()) {
            return;
        }
        List<Long> asked = new ArrayList<>();
        for (Long2IntMap.Entry entry : unconfirmed.long2IntEntrySet()) {
            if (asked.size() >= CONFIRMS_PER_ASK) {
                break;
            }
            asked.add(entry.getLongKey());
        }
        for (long pos : asked) {
            Object answer = getAllTerrainDataAtChunkPos.invoke(terrainRepo, wrapper, ChunkPos.getX(pos), ChunkPos.getZ(pos), cache);
            boolean held = answer != null && resultSuccess.getBoolean(answer) && holdsData(resultPayload.get(answer));
            int attempts = unconfirmed.get(pos) + 1;
            if (held || attempts >= CONFIRM_ATTEMPTS) {
                // A chunk asked about thirty times without an answer is let go: an export never waits on one chunk for ever
                unconfirmed.remove(pos);
                lastChange = frames;
            } else {
                // To the back of the line, so the others get asked meanwhile
                unconfirmed.remove(pos);
                unconfirmed.put(pos, attempts);
            }
        }
    }

    private static boolean holdsData(@Nullable Object payload) {
        if (payload == null) {
            return false;
        }
        if (!payload.getClass().isArray()) {
            return true;
        }
        for (int i = 0, length = Array.getLength(payload); i < length; i++) {
            if (holdsData(Array.get(payload, i))) {
                return true;
            }
        }
        return false;
    }

    /** The folder Distant Horizons keeps the world being played back in, or null when it has not opened it. Client thread */
    public static @Nullable Path saveFolder() {
        ClientLevel level = Minecraft.getInstance().level;
        if (!working || level == null) {
            return null;
        }
        try {
            Object wrapper = wrapperFor(level);
            Object folder = wrapper == null ? null : getDhSaveFolder.invoke(wrapper);
            return folder instanceof File file ? file.toPath() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Where the recording's own far terrain is kept */
    public static Path folderOf(String recordingId) {
        return folderRoot().resolve(safe(recordingId));
    }

}
