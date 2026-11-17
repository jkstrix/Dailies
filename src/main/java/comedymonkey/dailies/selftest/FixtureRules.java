package comedymonkey.dailies.selftest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * When a recording kept as a test fixture may be used again, and when it has to be recorded afresh. A fixture saves
 * minutes of staging on every run, but one made by older recording code no longer tests the code that writes recordings
 * now, so each fixture keeps a fingerprint of that code and is recorded again when it changes. No game in here, so it
 * is unit tested.
 */
final class FixtureRules {

    /** Packages every class of which has a hand in what a recording holds */
    static final List<String> RECORDING_PACKAGES = List.of(
        "comedymonkey/dailies/capture/",
        "comedymonkey/dailies/file/",
        "comedymonkey/dailies/packs/",
        "comedymonkey/dailies/mixin/capture/",
        "comedymonkey/dailies/mixin/packs/",
        "comedymonkey/dailies/mixin/extras/");
    /** Single classes elsewhere that write into recordings, with their inner classes */
    static final List<String> RECORDING_CLASSES = List.of(
        "comedymonkey/dailies/sound/VoiceCapture",
        "comedymonkey/dailies/extras/HudCapture",
        "comedymonkey/dailies/extras/SkinCapture",
        "comedymonkey/dailies/extras/EntityClocks");

    /** What a fixture says of itself: the version of the staging that made it and the fingerprint of the code that wrote it */
    record Stored(int version, String fingerprint) {
    }

    private FixtureRules() {
    }

    /** Whether a class file, by its path inside the mod with forward slashes, is part of the code that writes recordings */
    static boolean writesRecordings(String path) {
        if (!path.endsWith(".class")) {
            return false;
        }
        for (String folder : RECORDING_PACKAGES) {
            if (path.startsWith(folder)) {
                return true;
            }
        }
        for (String type : RECORDING_CLASSES) {
            if (path.equals(type + ".class") || path.startsWith(type + "$")) {
                return true;
            }
        }
        return false;
    }

    /**
     * A fingerprint of some class files by their paths: the same files give the same fingerprint whatever order they
     * were found in, and any change to a file, its name, or which files there are gives another. Null for none at all,
     * which means the code could not be read rather than that there is none
     */
    static String fingerprint(Map<String, byte[]> classes) {
        if (classes.isEmpty()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, byte[]> entry : new TreeMap<>(classes).entrySet()) {
                byte[] name = entry.getKey().getBytes(StandardCharsets.UTF_8);
                byte[] bytes = entry.getValue();
                // Lengths first, so that no shuffling of bytes between a name and its file comes out the same
                digest.update(lengthOf(name.length));
                digest.update(name);
                digest.update(lengthOf(bytes.length));
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest(), 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java has SHA-256", e);
        }
    }

    private static byte[] lengthOf(int length) {
        return new byte[]{(byte) (length >>> 24), (byte) (length >>> 16), (byte) (length >>> 8), (byte) length};
    }

    /**
     * Why a fixture has to be recorded again, or null when it can be used as it is.
     *
     * @param asked          whether the run was asked to record it again
     * @param flag           the switch that asks, to name in the reason
     * @param recordingThere whether the fixture's recording is there
     * @param stored         what the fixture says of itself; null when that could not be read
     * @param unreadable     why it could not be read, when it could not
     * @param version        the version of the staging this run has
     * @param fingerprint    the fingerprint of the recording code this run has; null when it could not be read, and then
     *                       it is no reason to record again (the caller says so)
     */
    static String recordAgain(boolean asked, String flag, boolean recordingThere, Stored stored, String unreadable, int version, String fingerprint) {
        if (asked) {
            return "asked for (" + flag + ")";
        }
        if (!recordingThere) {
            return "there is no fixture yet";
        }
        if (stored == null) {
            return "what the fixture says of itself could not be read (" + unreadable + ")";
        }
        if (stored.version() != version) {
            return "it was staged by version " + stored.version() + " of the script and this run has version " + version;
        }
        if (fingerprint != null && !fingerprint.equals(stored.fingerprint())) {
            return "the code that writes recordings has changed since it was made (fingerprint " + stored.fingerprint() + ", now " + fingerprint + ")";
        }
        return null;
    }
}
