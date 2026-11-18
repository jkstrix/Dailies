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
        HOLD, LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT, SMOOTH, HERMITE
    }

    public enum CaptionStyle {
        LINES, WORD_BY_WORD
    }

    public enum CaptionFont {
        DEFAULT, UNIFORM
    }

    public enum CaptionPosition {
        BOTTOM, MIDDLE, TOP
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
            integer("recorderUpdatesPerSecond", 20, 1, 20, "How often your own player's movement is captured each second");
            integer("snapshotSeconds", 2, 1, 1800,"How often a recording stores the whole world afresh. Going back in playback replays from the stored world before, so a shorter time makes going back quicker and the file a little larger");
            bool("recordVoiceChat", true, "Capture the voice chat you hear, per speaker, so playback and exports have it (needs Simple Voice Chat)");
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
            bool("exportSplitEncoding", true, "Let an NVIDIA card encode HEVC and AV1 on all of its encoding units at once, each taking a strip of the frame. Much faster at high resolutions on cards with several; turn off for the last bit of quality");
            integer("exportParallelEncoders", 3, 1, 8, "How many H.264 encoders an NVIDIA card runs at once for an export, each taking every so many seconds of the video, which are joined at the end. H.264 frames cannot be split between a card's encoding units as HEVC and AV1 frames are, so this is how H.264 uses them all. It holds frames in memory while it runs, up to about 1.5 GB at 4K, less when the game has less to spare. 1 uses a single encoder");
            bool("exportGpuColour", true,"Convert video frames to the encoder's colour format on the graphics card, which makes exports faster. Turn off if exported colours ever look wrong");
            bool("exportQueueNotify", true, "Show a notification on the desktop when the export queue is done");
            choice("captionStyle", CaptionStyle.LINES, "How captions drawn into the video show what was said: a line at a time, or each word as it is said, as short videos have them");
            choice("captionFont", CaptionFont.DEFAULT, "The font of captions drawn into the video");
            decimal("captionSize", 5.0, 2.0, 20.0, "The height of a line of captions drawn into the video, as a percentage of the picture's height");
            text("captionColour", "white", "The colour of captions drawn into the video: red, orange, yellow, green, cyan, blue, purple, white, or #RRGGBB");
            text("captionOutline", "black", "The colour of the outline round captions drawn into the video, as above. Empty for a plain shadow");
            choice("captionPosition", CaptionPosition.BOTTOM, "Where captions drawn into the video sit");
            bool("captionSpeakerNames", true, "Begin each caption with the name of whoever said it");
            bool("captionPreview", false, "Show the captions in the editor's view, as they would be drawn into the video. They are never drawn into an export unless it asks for them");
        });
        group("editor", "The editor", () -> {
            choice("defaultInterpolation", Blend.SMOOTH, "How a new keyframe blends towards the next one");
            bool("flightMomentum", true, "The viewer glides to a stop instead of stopping dead");
            bool("flightLookRelative", false, "Forward follows where the viewer looks, up and down included. When off, forward stays level");
            bool("showCameraPath", true, "Draw the camera's path through the world while editing: a line along it, a mark at each keyframe and arrows for where it looks. Never in exports");
            bool("showMomentsOnTimeline", true, "Show the recording's moments on the editor's timeline: events as marks, clips as bands and a strip for each voice");
            bool("showMarkersInWorld", true, "Show markers that stored a position as coloured rings on the ground while watching. Never in exports");
        });
        group("storage", "Disk space and the bin", () -> {
            integer("binDays", 30, 1, 3650, "How many days something deleted stays in the bin, where it can be restored, before it is deleted for good");
            integer("retentionDays", 0, 0, 36500, "Move recordings older than this many days to the bin, unless they are starred or hold footage that was exported. 0 keeps every recording");
            bool("voiceAudioOnceTranscribed", false, "Remove a voice segment's audio once it has been transcribed. Its words, times and speaker are kept");
            integer("voiceAudioDays", 0, 0, 36500, "Remove a voice segment's audio once it is this many days old, transcribed or not. 0 keeps it");
            integer("diskWarnGigabytes", 20, 0, 100000, "Warn while recording when the disk has less than this many gigabytes free. 0 never warns");
            integer("diskPauseGigabytes", 5, 0, 100000, "Pause the recording, with a notice, when the disk has less than this many gigabytes free, before it fills. 0 never pauses");
        });
        group("advanced", "Advanced", () -> {
            text("ignoredPayloads", "", "Mod messages that playback must not hand back to their mod, added to the built-in list. Ids or namespace:* separated by commas or new lines; a leading + allows one the built-in list ignores");
            text("testShaderPack", "", "For the scripted test only: the file name of a shader pack in the test game's shaderpacks folder (run-modded/shaderpacks) to try playback and export with, such as the zip of Complementary Shaders - Reimagined. Needs Iris. Empty skips the shader pack checks");
        });
        group("mcp", "The MCP server", () -> {
            bool("mcpEnabled", true, "Run the MCP server, which also serves the moments page. It listens on this computer only, unless Listen On is changed");
            integer("mcpPort", 25595, 1024, 65535, "The port the MCP server listens on");
            text("mcpBind", "127.0.0.1", "The address the MCP server listens on. 127.0.0.1 keeps it on this computer, as it always was. One of this computer's own network addresses, or 0.0.0.0 for all of them, lets other machines reach it: to them it speaks HTTPS, wants the token on every request and only answers the allowed clients. This computer goes on using http://127.0.0.1 with no token");
            text("mcpToken", "", "The token another machine must send over HTTPS as 'Authorization: Bearer <token>'. Made by the mod the first time remote access is switched on; clear it to have a new one made. Anyone who has it can drive the game, so treat it as a password");
            text("mcpAllowedClients", comedymonkey.dailies.mcp.AddressRules.LOCAL_NETWORKS, "The machines that may reach the MCP server when it listens beyond this computer: addresses or ranges such as 192.168.1.20 or 192.168.1.0/24, separated by commas. The default is the private local-network ranges. This computer itself is always allowed");
            text("mcpCertificateFingerprint", "", "The SHA-256 fingerprint of the certificate the MCP server shows to other machines, for a client to pin. Filled in by the mod when remote access is switched on; changing it here has no effect");
        });
        group("moments", "Moments", () -> {
            bool("momentsEnabled", true, "Keep a log of what happens while you play");
            bool("momentsLogRepeats", false, "Log every crafted item, placed block, mined block and tool use, not only the first of each");
            integer("momentsStatsPollSeconds", 5, 0, 600, "How often the game's statistics are asked for, to confirm kills. 0 turns it off");
            bool("momentsBurstLabels", true, "Listen to each burst of noise in voice chat and name it laughter, cheering, screaming or shouting, so laughter can make the better clip. A small sound model bundled with the mod does this on this computer: no AI service is used and nothing is sent anywhere");
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
        if (comedymonkey.dailies.export.Worker.active()) {
            // The background export game never writes the settings (EXP-13); a change lasts until it closes
            return;
        }
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
