package comedymonkey.dailies.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every setting of the mod (specification chapter XIII), in one NeoForge client config: config/dailies-client.toml.
 * A setting is one line here, inside its group. Other code reads settings by name through {@link #find}, or keeps
 * the {@link Setting} returned here.
 */
public final class DailiesConfig {

    public enum Side {
        LEFT, RIGHT
    }

    public enum Blend {
        HOLD, LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT, SMOOTH
    }

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    private static final Map<String, Setting<?>> SETTINGS = new LinkedHashMap<>();
    private static String group = "";

    public static final ModConfigSpec SPEC;

    static {
        group("recording", "Making recordings", () -> {
            bool("autoStart", false, "Start a recording by itself on joining any world or server");
            bool("autoFinish", true, "Finish the recording by itself on leaving. When off, the next world joined continues the same recording");
            bool("showNotices", true, "Show an on-screen notice when a recording starts, pauses, resumes, finishes or fails");
            bool("saveWithoutAsking", false, "Finish a recording under its default name without asking for one");
            choice("controlsLocation", Side.RIGHT, "The side of the pause menu the recording buttons sit on");
            bool("markDimensionChanges", true, "Drop a marker on every change of dimension");
            bool("recordHud", false, "Also capture your hotbar, selected slot, health, hunger and experience, so playback can show your HUD");
            integer("recorderUpdatesPerSecond", 20, 1, 20, "How often your own player's movement is captured each second");
            integer("snapshotSeconds", 5, 1, 1800, "How often a recording stores the whole world afresh. Going back in playback replays from the stored world before, so a shorter time makes going back quicker and the file a little larger");
            bool("recordVoiceChat", false, "Capture the voice chat you hear, per speaker (needs Simple Voice Chat)");
            for (int marker = 1; marker <= 4; marker++) {
                text("marker" + marker + "Colour", "red", "Colour of marker key " + marker + ": red, orange, yellow, green, cyan, blue, purple, white, or a custom colour as #RRGGBB");
                text("marker" + marker + "Description", "", "Text stored with marker key " + marker + "; may be empty");
                bool("marker" + marker + "SavePosition", true, "Whether marker key " + marker + " stores where you were standing");
            }
            text("momentKeyLabel", "Good moment", "What the moment key logs: the label of the custom moment it adds, as /dailies moment would with these words");
        });
        group("export", "Exporting video", () -> {
            text("defaultFileName", "{date}_{time}", "Default name of an exported file. {date} and {time} are filled in when the export starts");
            integer("throwawayFrames", 0, 0, 240, "Extra frames rendered and thrown away before each captured one, for shader packs that build up their image over time");
        });
        group("editor", "The editor", () -> {
            choice("defaultInterpolation", Blend.SMOOTH, "How a new keyframe blends towards the next one");
            bool("flightMomentum", true, "The viewer glides to a stop instead of stopping dead");
            bool("flightLookRelative", false, "Forward follows where the viewer looks, up and down included. When off, forward stays level");
            bool("showCameraPath", true, "Draw the camera's path through the world while editing: a line along it, a mark at each keyframe and arrows for where it looks. Never in exports");
            bool("showMarkersInWorld", true, "Show markers that stored a position as coloured rings on the ground while watching. Never in exports");
        });
        group("advanced", "Advanced", () -> {
            text("ignoredPayloads", "", "Mod messages that playback must not hand back to their mod, added to the built-in list. Ids or namespace:* separated by commas or new lines; a leading + allows one the built-in list ignores");
        });
        group("mcp", "The MCP server", () -> {
            bool("mcpEnabled", true, "Run the local MCP server, which also serves the moments page. It only ever listens on this computer");
            integer("mcpPort", 25595, 1024, 65535, "The port the MCP server listens on, at 127.0.0.1");
        });
        group("moments", "Moments", () -> {
            bool("momentsEnabled", true, "Keep a log of what happens while you play");
            bool("momentsLogRepeats", false, "Log every crafted item, placed block, mined block and tool use, not only the first of each");
            integer("momentsStatsPollSeconds", 5, 0, 600, "How often the game's statistics are asked for, to confirm kills. 0 turns it off");
            bool("momentsAiEnabled", false, "The one switch for everything that talks to an AI service: transcription and model-written summaries. While off, nothing is sent anywhere");
            text("momentsWhisperUrl", "http://192.168.188.50:8000", "Address of an OpenAI-compatible transcription server. Empty keeps transcription off");
            text("momentsWhisperModel", "Systran/faster-whisper-large-v3", "The transcription model to ask for");
            text("momentsWhisperLanguage", "en", "Language of the speech. Empty means detect it");
            bool("momentsTranscribeLive", true, "Transcribe voice segments as they are made, not only when asked");
            text("momentsOllamaUrl", "http://192.168.188.50:11434", "Address of an Ollama server for clip titles and summaries. Empty keeps them off");
            text("momentsOllamaModel", "gemma4:e4b", "The model that writes clip titles and summaries");
            bool("momentsSummarizeClips", true, "Ask the model for a better title and summary of each clip");
            decimal("momentsProximityRadius", 10.0, 1.0, 256.0, "Players within this many blocks of each other count as together");
            integer("momentsProximityMinSeconds", 20, 1, 3600, "How long players must stay together before it is logged as a meeting");
            integer("momentsVoiceGapMillis", 800, 100, 10000, "A silence this long ends a voice segment");
            integer("momentsContextSeconds", 30, 1, 3600, "How far either side of a moment its context reaches");
            integer("momentsClipPreRollSeconds", 15, 0, 600, "How long before an event its clip starts");
            integer("momentsClipPostRollSeconds", 20, 0, 600, "How long after an event its clip ends");
            integer("momentsBossHealthThreshold", 150, 1, 100000, "A mob with at least this much maximum health counts as a boss");
            text("momentsNotableEntities", "minecraft:ender_dragon, minecraft:wither, minecraft:warden, minecraft:elder_guardian", "Mobs that always count as bosses, separated by commas");
            text("momentsStructureBlocks", "minecraft:spawner, minecraft:trial_spawner, minecraft:vault, minecraft:end_portal_frame, minecraft:reinforced_deepslate, minecraft:suspicious_sand, minecraft:suspicious_gravel", "Blocks that only occur in structures, separated by commas");
            text("momentsIgnoredEvents", "", "Events hidden from every list and clip: a type, type:subject, or the name of a custom event, separated by commas. * matches anything");
        });
        SPEC = BUILDER.build();
    }

    private DailiesConfig() {
    }

    public static boolean loaded() {
        return SPEC.isLoaded();
    }

    public static @Nullable Setting<?> find(String name) {
        return SETTINGS.get(name);
    }

    public static Collection<Setting<?>> all() {
        return Collections.unmodifiableCollection(SETTINGS.values());
    }

    /** Write the settings file after one or more {@link Setting#setFrom} calls */
    public static void save() {
        SPEC.save();
    }

    // --- The helpers: one line per setting ---

    private static void group(String name, String comment, Runnable contents) {
        BUILDER.comment(comment).push(name);
        group = name;
        contents.run();
        group = "";
        BUILDER.pop();
    }

    private static Setting<Boolean> bool(String name, boolean fallback, String comment) {
        return add(new Setting<>(name, group, Setting.Kind.BOOLEAN, fallback, comment, null, null, BUILDER.comment(comment).define(name, fallback)));
    }

    private static Setting<Integer> integer(String name, int fallback, int min, int max, String comment) {
        return add(new Setting<>(name, group, Setting.Kind.INTEGER, fallback, comment, min, max, BUILDER.comment(comment).defineInRange(name, fallback, min, max)));
    }

    private static Setting<Double> decimal(String name, double fallback, double min, double max, String comment) {
        return add(new Setting<>(name, group, Setting.Kind.DECIMAL, fallback, comment, min, max, BUILDER.comment(comment).defineInRange(name, fallback, min, max)));
    }

    private static Setting<String> text(String name, String fallback, String comment) {
        return add(new Setting<>(name, group, Setting.Kind.TEXT, fallback, comment, null, null, BUILDER.comment(comment).define(name, fallback)));
    }

    private static <E extends Enum<E>> Setting<E> choice(String name, E fallback, String comment) {
        return add(new Setting<>(name, group, Setting.Kind.CHOICE, fallback, comment, null, null, BUILDER.comment(comment).defineEnum(name, fallback)));
    }

    private static <T> Setting<T> add(Setting<T> setting) {
        if (SETTINGS.put(setting.name(), setting) != null) {
            throw new IllegalStateException("Two settings are both called " + setting.name());
        }
        return setting;
    }

}
