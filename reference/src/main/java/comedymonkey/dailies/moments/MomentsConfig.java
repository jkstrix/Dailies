package comedymonkey.dailies.moments;

import comedymonkey.dailies.Host;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The "moments" settings, read through {@link Host}. Safe to call from any thread and before the settings have
 * loaded (the defaults given here are returned then).
 */
public final class MomentsConfig {

    private MomentsConfig() {
    }

    public static boolean enabled() {
        return Host.get().setting("momentsEnabled", true);
    }

    public static boolean logRepeats() {
        return Host.get().setting("momentsLogRepeats", false);
    }

    public static int statsPollSeconds() {
        return Host.get().setting("momentsStatsPollSeconds", 5);
    }

    public static String whisperUrl() {
        return baseUrl(Host.get().setting("momentsWhisperUrl", "http://192.168.188.50:8000"));
    }

    /** The one switch for everything that talks to an AI service (Whisper, Ollama). Off by default */
    public static boolean aiEnabled() {
        return Host.get().setting("momentsAiEnabled", false);
    }

    /** Transcription is optional and off until momentsAiEnabled is set: nothing is sent anywhere and voice segments stay untranscribed */
    public static boolean whisperConfigured() {
        return aiEnabled() && !whisperUrl().isEmpty();
    }

    /** Model-written clip summaries are optional and off until momentsAiEnabled is set: clips keep the built-in titles and summaries */
    public static boolean ollamaConfigured() {
        return aiEnabled() && !ollamaUrl().isEmpty();
    }

    public static String whisperModel() {
        return Host.get().setting("momentsWhisperModel", "Systran/faster-whisper-large-v3").trim();
    }

    public static String whisperLanguage() {
        return Host.get().setting("momentsWhisperLanguage", "en").trim();
    }

    public static boolean transcribeLive() {
        return Host.get().setting("momentsTranscribeLive", true);
    }

    public static String ollamaUrl() {
        return baseUrl(Host.get().setting("momentsOllamaUrl", "http://192.168.188.50:11434"));
    }

    public static String ollamaModel() {
        return Host.get().setting("momentsOllamaModel", "gemma4:e4b").trim();
    }

    public static boolean summarizeClips() {
        return Host.get().setting("momentsSummarizeClips", true);
    }

    public static double proximityRadius() {
        return Host.get().setting("momentsProximityRadius", 10.0);
    }

    public static int proximityMinSeconds() {
        return Host.get().setting("momentsProximityMinSeconds", 20);
    }

    public static int voiceGapMillis() {
        return Host.get().setting("momentsVoiceGapMillis", 800);
    }

    public static int contextSeconds() {
        return Host.get().setting("momentsContextSeconds", 30);
    }

    public static int clipPreRollSeconds() {
        return Host.get().setting("momentsClipPreRollSeconds", 15);
    }

    public static int clipPostRollSeconds() {
        return Host.get().setting("momentsClipPostRollSeconds", 20);
    }

    public static int bossHealthThreshold() {
        return Host.get().setting("momentsBossHealthThreshold", 150);
    }

    public static Set<String> notableEntities() {
        return idList(Host.get().setting("momentsNotableEntities", "minecraft:ender_dragon, minecraft:wither, minecraft:warden, minecraft:elder_guardian"));
    }

    public static Set<String> structureBlocks() {
        return idList(Host.get().setting("momentsStructureBlocks", "minecraft:spawner, minecraft:trial_spawner, minecraft:vault, minecraft:end_portal_frame"));
    }

    private static String baseUrl(String url) {
        url = url.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private static String lastIdListSource = null;
    private static Set<String> lastIdList = Set.of();
    private static String lastIdListSource2 = null;
    private static Set<String> lastIdList2 = Set.of();

    // The two lists are read every second, so the parsed sets are cached by their source string
    private static synchronized Set<String> idList(String source) {
        if (source.equals(lastIdListSource)) {
            return lastIdList;
        }
        if (source.equals(lastIdListSource2)) {
            return lastIdList2;
        }
        Set<String> set = new LinkedHashSet<>();
        for (String part : source.split(",")) {
            String id = part.trim().toLowerCase(Locale.ROOT);
            if (id.isEmpty()) {
                continue;
            }
            set.add(id.contains(":") ? id : "minecraft:" + id);
        }
        lastIdListSource2 = lastIdListSource;
        lastIdList2 = lastIdList;
        lastIdListSource = source;
        lastIdList = set;
        return set;
    }

    // --- Ignored events ---

    private static volatile String ignoredSource = null;
    private static volatile java.util.List<java.util.regex.Pattern[]> ignoredPatterns = java.util.List.of();

    /** The entries of the ignore list as written, see {@link #isIgnored} */
    public static java.util.List<String> ignoredEvents() {
        java.util.List<String> entries = new java.util.ArrayList<>();
        for (String entry : Host.get().setting("momentsIgnoredEvents", "").split("[,\\n]")) {
            if (!entry.isBlank()) {
                entries.add(entry.trim());
            }
        }
        return entries;
    }

    /**
     * Whether queries and clips leave this event out. An entry is "type", "type:subject" or, for custom and server
     * events, just their name (the subject). * matches any run of characters.
     */
    public static boolean isIgnored(String type, String subject) {
        String source = Host.get().setting("momentsIgnoredEvents", "");
        if (!source.equals(ignoredSource)) {
            java.util.List<java.util.regex.Pattern[]> patterns = new java.util.ArrayList<>();
            for (String entry : ignoredEvents()) {
                int colon = entry.indexOf(':');
                String typePart = colon < 0 ? entry : entry.substring(0, colon).trim();
                String subjectPart = colon < 0 ? null : entry.substring(colon + 1).trim();
                patterns.add(new java.util.regex.Pattern[]{glob(typePart), subjectPart == null ? null : glob(subjectPart)});
            }
            ignoredPatterns = patterns;
            ignoredSource = source;
        }
        for (java.util.regex.Pattern[] pattern : ignoredPatterns) {
            if (pattern[1] == null) {
                if (pattern[0].matcher(type).matches() || ("custom".equals(type) && subject != null && pattern[0].matcher(subject).matches())) {
                    return true;
                }
            } else if (pattern[0].matcher(type).matches() && subject != null && pattern[1].matcher(subject).matches()) {
                return true;
            }
        }
        return false;
    }

    private static java.util.regex.Pattern glob(String text) {
        StringBuilder regex = new StringBuilder();
        for (String part : text.split("\\*", -1)) {
            if (regex.length() > 0) {
                regex.append(".*");
            }
            regex.append(java.util.regex.Pattern.quote(part));
        }
        return java.util.regex.Pattern.compile(regex.toString(), java.util.regex.Pattern.CASE_INSENSITIVE);
    }

    // --- Players whose voice is left alone (specification MOM-36) ---

    private static volatile String excludedSource = null;
    private static volatile Set<String> excluded = Set.of();

    /** The players whose voice never reaches Moments, as written: names or uuids */
    public static java.util.List<String> voiceExcluded() {
        java.util.List<String> entries = new java.util.ArrayList<>();
        for (String entry : Host.get().setting("momentsVoiceExcluded", "").split("[,\\n]")) {
            if (!entry.isBlank()) {
                entries.add(entry.trim());
            }
        }
        return entries;
    }

    /** Whether this speaker is on the list, by uuid or by name. Called for every piece of voice, so the list is parsed once */
    public static boolean isVoiceExcluded(@org.jetbrains.annotations.Nullable String uuid, @org.jetbrains.annotations.Nullable String name) {
        String source = Host.get().setting("momentsVoiceExcluded", "");
        if (!source.equals(excludedSource)) {
            Set<String> set = new LinkedHashSet<>();
            for (String entry : voiceExcluded()) {
                set.add(entry.toLowerCase(Locale.ROOT));
            }
            excluded = set;
            excludedSource = source;
        }
        Set<String> set = excluded;
        if (set.isEmpty()) {
            return false;
        }
        return (uuid != null && set.contains(uuid.toLowerCase(Locale.ROOT))) || (name != null && set.contains(name.toLowerCase(Locale.ROOT)));
    }

    public static void setVoiceExcluded(java.util.Collection<String> entries) {
        Host.get().saveSetting("momentsVoiceExcluded", String.join(", ", entries));
    }

    /** Whether a small picture of the screen is kept with each big event (plan 2.11) */
    public static boolean eventPictures() {
        return Host.get().setting("momentsEventPictures", true);
    }

    /** Replaces the ignore list and saves the config file */
    public static void setIgnoredEvents(java.util.Collection<String> entries) {
        Host.get().saveSetting("momentsIgnoredEvents", String.join(", ", entries));
    }

}
