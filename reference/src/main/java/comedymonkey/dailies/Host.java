package comedymonkey.dailies;

import com.google.gson.JsonObject;
import comedymonkey.dailies.mcp.McpProtocol;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * The one place where Moments, the Smart Camera and the MCP server reach the rest of the mod. The core implements
 * {@link Mod} (and {@link Crafts} when Sable is installed) and installs them at start-up. Nothing else in these
 * packages knows how recording, playback, the editor or the settings are built.
 */
public final class Host {

    public static final Logger LOGGER = LoggerFactory.getLogger("Dailies");

    private static volatile Mod mod = null;
    private static volatile Crafts crafts = new Crafts() {};

    private Host() {
    }

    public static void install(Mod implementation) {
        mod = implementation;
    }

    public static void installCrafts(Crafts implementation) {
        crafts = implementation;
    }

    public static Mod get() {
        Mod installed = mod;
        if (installed == null) {
            throw new IllegalStateException("Host.install has not been called yet");
        }
        return installed;
    }

    public static Crafts crafts() {
        return crafts;
    }

    /**
     * A recording that holds a moment, and the tick the moment is at there. A moment keeps the id and tick of the
     * recording it was made in for good; recordings made by combining or trimming know which ticks of which originals
     * they hold, so the same moment can be in several.
     *
     * @param recordingId the id of the recording that holds it, which is not the moment's own when it is an edited one
     * @param open        whether this is the recording open in playback right now
     * @param original    whether this is the recording the moment was made in
     */
    public record Located(Path file, String recordingId, String name, int tick, boolean open, boolean original) {
    }

    /** A piece of a clip that is still in a recording: ticks there, and the ticks of the original they hold */
    public record Stretch(int tick, int endTick, int sourceTick, int sourceEndTick) {
    }

    /**
     * A recording that holds some or all of a clip.
     *
     * @param stretches    the pieces that are there, in the order they play, back to back where a cut fell inside the clip
     * @param cut          whether part of the clip was cut from this recording
     * @param removedTicks how many ticks of the clip are missing here
     */
    public record ClipLocation(Path file, String recordingId, String name, List<Stretch> stretches, boolean cut, int removedTicks, boolean open, boolean original) {
    }

    /**
     * Another player's recording of the same stretch of play (PLAY-18): made on the same server at the same time, and
     * lined up with the one asked about by the server's game time.
     *
     * @param startTick where the stretch asked about starts in this recording (or where this recording starts, when later)
     * @param endTick   where it ends here
     * @param open      whether this is the recording open in playback right now
     */
    public record PointOfView(Path file, String recordingId, String name, String recorder, int startTick, int endTick, boolean open) {
    }

    /** Edits to make together, see {@link Mod#asOneEdit} */
    @FunctionalInterface
    public interface EditWork {
        void run() throws Exception;
    }

    /** What the core provides */
    public interface Mod {
        /** The mod's own folder in the game directory, where "moments" lives */
        Path dataDirectory();
        /** The game's config folder: where secrets are kept apart from Dailies' own folder, which can be copied or shared */
        Path configDirectory();
        String modVersion();
        boolean isModLoaded(String modId);

        // --- Settings (the names are the ones in the specification's settings chapter) ---
        boolean settingsLoaded();
        /** The value of a setting, or the fallback while the settings have not loaded. The fallback gives the type */
        <V> V setting(String name, V fallback);
        /** Change a setting and save the settings file */
        void saveSetting(String name, Object value);

        // --- Recording ---
        /** The unique id of the recording being made right now, or null */
        @Nullable String recordingId();
        /** How many ticks the recording being made holds so far */
        int recordingTick();

        // --- Playback ---
        /** Whether the game is showing a recording (never a live world) */
        boolean inPlayback();
        /** The unique id of the recording that is open in playback, or null */
        @Nullable String openRecordingId();
        int openRecordingTicks();
        /** Jump to a tick of the open recording; the jump may finish later. Client thread */
        void seek(int tick, boolean pause);

        /**
         * Runs this on the client thread once the recording now opening is up (at once when one is up and nothing is
         * opening). Opening another recording first drops it, so it never runs on a recording it was not meant for
         */
        void whenLoaded(Runnable action);
        /**
         * Every recording that holds a moment, best first: the recording that is open, then the original, then the
         * others from the newest. Empty when the moment was cut from all of them, or its recording is gone.
         */
        List<Located> locate(String recordingId, int tick);
        /**
         * As {@link #locate(String, int)}, with a fallback for a moment whose recording is not known (no id, or an id
         * no recording here has or is made from): a recording of the same world whose time index holds the real time.
         *
         * @param realTime milliseconds since 1970, or 0 when not known
         * @param world    the world or server name, or null when not known
         */
        List<Located> locate(@Nullable String recordingId, int tick, long realTime, @Nullable String world);
        /** Every recording that holds some of a clip (ticks startTick to endTick of an original), in the order of {@link #locate} */
        List<ClipLocation> locateClip(String recordingId, int startTick, int endTick);
        /** The tick at which the open recording holds a moment, or -1 when it does not (or none is open) */
        int openRecordingContains(String recordingId, int tick);
        /** Whether the open recording is, or holds footage of, the recording with this id */
        boolean openRecordingMadeFrom(String recordingId);
        /** Leave whatever is open and open this recording. Client thread; loading continues afterwards */
        void openRecording(Path file) throws Exception;
        boolean exportRunning();
        /** The export presets, in their order: each one's name and a line saying what it makes */
        java.util.Map<String, String> exportPresets();

        // --- Editor ---
        /** The FOV the open scene forces, or null when it leaves the game's setting alone */
        @Nullable Double sceneFov();
        /** Width over height of the export that is running, or null when none is */
        @Nullable Double exportAspectRatio();
        /**
         * Add a Smart Camera keyframe to the open scene, through the editor's undo stack. The arguments are those of
         * the smart_camera_add tool: tick, target, mode and any setting in snake_case. Client thread
         */
        void addSmartCamera(JsonObject arguments) throws Exception;
        /**
         * Add a scene to the open recording with this name and export range, and make it the current one, through the
         * editor's undo stack. Client thread.
         *
         * @param startTick the first tick exported
         * @param endTick   the last tick exported
         * @return the index of the new scene
         */
        int createScene(String name, int startTick, int endTick) throws Exception;
        /** Run several edits so that they are one step to undo, under this description. Client thread */
        void asOneEdit(String description, EditWork edits) throws Exception;
        /**
         * Put an export of a scene of the open recording on the export queue (EXP-10). It runs when the queue is
         * started, in this game or in the background. Client thread.
         *
         * @param scene  the index of the scene, as {@link #createScene} returns it
         * @param preset the name of an export preset, or null for the first one
         * @param file   the file to write: a name for the exports folder, or an absolute path. Its ending is the
         *               preset's; a name already taken gets a number when the job runs
         * @return the id of the job
         */
        String queueExport(int scene, @Nullable String preset, String file) throws Exception;
        /**
         * Put an export of a stretch of any recording on the export queue (EXP-10), seen through the eyes of whoever
         * made it: the camera rides along with the recorder and none of the recording's scenes plays a part, as
         * PLAY-18's batch of points of view wants. The recording need not be open. Reads the recording's description:
         * not for the client thread.
         *
         * @param recording the recording's file
         * @param startTick the first tick, in that recording's own ticks
         * @param endTick   the last tick, after the first
         * @param preset    the name of an export preset, or null for the first one
         * @param folder    the folder to write to (absolute, or inside the exports folder), or null for the exports folder
         * @param name      the file's name; its ending is the preset's, and a name already taken gets a number when the
         *                  job runs. Empty for one made of the recorder's name and the ticks
         * @return the id of the job
         */
        String queueExport(Path recording, int startTick, int endTick, @Nullable String preset, @Nullable String folder, String name) throws Exception;

        // --- Several points of view (PLAY-18) ---

        /**
         * The other players' recordings that hold some of a stretch of this recording (ticks startTick to endTick), each
         * with the same stretch in its own ticks. Reads recordings' descriptions: not for the client thread
         */
        default List<PointOfView> pointsOfView(String recordingId, int startTick, int endTick) {
            return List.of();
        }

        /** The ids of the other players' recordings linked to this one: the same server, at the same time. Not for the client thread */
        default List<String> linkedRecordings(String recordingId) {
            return List.of();
        }

        // --- MCP ---
        /** Register the tool groups that belong to the core */
        void registerTools(McpProtocol.Registrar registrar);
    }

    /**
     * Sable physics objects ("sub-levels"), for the Smart Camera. The defaults are right when Sable is not installed.
     * Sable keeps an object's blocks, and the entities on it, in a hidden "plot" region of the world; these methods
     * translate between that region and where things appear.
     */
    public interface Crafts {
        /** Whether this position lies in the hidden region */
        default boolean isInPlot(Level level, double x, double z) {
            return false;
        }

        /** Where a position in the hidden region appears in the world */
        default Vec3 projectOutOfSubLevel(Level level, Vec3 position) {
            return position;
        }

        /** The same, at a point between two ticks */
        default Vec3 projectOutOfSubLevel(Level level, Vec3 position, float partialTick) {
            return position;
        }

        /** A direction at a position in the hidden region, turned the way the object is turned */
        default Vec3 projectDirectionOutOfSubLevel(Level level, Vec3 position, Vec3 direction, float partialTick) {
            return direction;
        }

        /** The object an entity is on or part of, or null */
        default @Nullable Object getSubLevel(Entity entity) {
            return null;
        }

        /** The object whose hidden region contains this position, or null */
        default @Nullable Object getSubLevelAt(Level level, Vec3 position) {
            return null;
        }

        /** Whether a world position is within margin blocks of an object's bounds */
        default boolean isNearSubLevel(Object subLevel, Vec3 position, double margin) {
            return false;
        }

        /** An object's bounds in world coordinates, or null */
        default @Nullable AABB getSubLevelBounds(Object subLevel) {
            return null;
        }

        // --- Craft as targets of the Smart Camera ---

        /** Every craft in the level now, in no particular order */
        default List<CraftInfo> crafts(Level level) {
            return List.of();
        }

        /** The craft with this unique id, or null when it is not in the level (never assembled yet, or gone) */
        default @Nullable Object craftById(Level level, UUID uniqueId) {
            return null;
        }

        /** The unique id Sable gives a craft for its whole life, or null when this is not a craft */
        default @Nullable UUID craftId(Object craft) {
            return null;
        }

        /**
         * The way a craft's nose points: its own forward direction (towards positive Z, where it was built) turned as
         * the craft is turned, as drawn this frame. Null when it cannot be told
         */
        default @Nullable Vec3 craftForward(Object craft, float partialTick) {
            return null;
        }
    }

    /**
     * One craft, as the Smart Camera's target lists show it.
     *
     * @param uniqueId the id Sable keeps for the craft's whole life, which every snapshot of a recording carries
     * @param name     the name it was given, or empty
     * @param bounds   its bounds in world coordinates
     * @param craft    the object itself, for the other questions of {@link Crafts}
     */
    public record CraftInfo(UUID uniqueId, String name, AABB bounds, Object craft) {
    }

}
