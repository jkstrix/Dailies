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
        });
        group("export", "Exporting video", () -> {
            text("defaultFileName", "{date}_{time}", "Default name of an exported file. {date} and {time} are filled in when the export starts");
            integer("throwawayFrames", 0, 0, 240, "Extra frames rendered and thrown away before each captured one, for shader packs that build up their image over time");
        });
        group("editor", "The editor", () -> {
            choice("defaultInterpolation", Blend.SMOOTH, "How a new keyframe blends towards the next one");
            bool("flightMomentum", true, "The viewer glides to a stop instead of stopping dead");
            bool("flightLookRelative", false, "Forward follows where the viewer looks, up and down included. When off, forward stays level");
        });
        group("advanced", "Advanced", () -> {
            text("ignoredPayloads", "", "Mod messages that playback must not hand back to their mod, added to the built-in list. Ids or namespace:* separated by commas or new lines; a leading + allows one the built-in list ignores");
        });
        group("mcp", "The MCP server", () -> {
            bool("mcpEnabled", true, "Run the MCP server, which also serves the moments page. It listens on this computer only, unless Listen On is changed");
            integer("mcpPort", 25595, 1024, 65535, "The port the MCP server listens on");
            text("mcpBind", "127.0.0.1", "The address the MCP server listens on. 127.0.0.1 keeps it on this computer, as it always was. One of this computer's own network addresses, or 0.0.0.0 for all of them, lets other machines reach it: it then speaks HTTPS, wants the token on every request and only answers the allowed clients");
            text("mcpToken", "", "The token another machine must send as 'Authorization: Bearer <token>'. Made by the mod the first time remote access is switched on; clear it to have a new one made. Anyone who has it can drive the game, so treat it as a password");
            text("mcpAllowedClients", comedymonkey.dailies.mcp.AddressRules.LOCAL_NETWORKS, "The machines that may reach the MCP server when it listens beyond this computer: addresses or ranges such as 192.168.1.20 or 192.168.1.0/24, separated by commas. The default is the private local-network ranges. This computer itself is always allowed");
            text("mcpCertificateFingerprint", "", "The SHA-256 fingerprint of the certificate the MCP server shows to other machines, for a client to pin. Filled in by the mod when remote access is switched on; changing it here has no effect");
        });
        group("moments", "Moments", () -> {
            bool("momentsEnabled", true, "Keep a log of what happens while you play");
            bool("momentsLogRepeats", false, "Log every crafted item, placed block, mined block and tool use, not only the first of each");
            integer("momentsStatsPollSeconds", 5, 0, 600, "How often the game's statistics are asked for, to confirm kills. 0 turns it off");
            bool("momentsAiEnabled", false, "The one switch for everything that talks to an AI service: transcription and model-written summaries. While off, nothing is sent anywhere. While on, the voice audio Moments captures, other players' included, is sent to the transcription server set below");
            text("momentsWhisperUrl", "http://192.168.188.50:8000", "Address of an OpenAI-compatible transcription server. With AI on, this is where voice audio is sent. Empty keeps transcription off");
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
            text("momentsVoiceExcluded", "", "Players whose voice is never captured for Moments or transcribed: names or uuids, separated by commas. You still hear them, and they are still in a recording's own voice chat when that is on; nothing of their voice reaches Moments or the transcription server. Recording other people's voices is yours to agree with them");
            bool("momentsEventPictures", true, "Keep a small picture of the screen, about 320 pixels wide, with each big moment while playing, for the moments page");
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
