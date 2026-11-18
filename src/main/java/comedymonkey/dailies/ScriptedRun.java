package comedymonkey.dailies;

import net.neoforged.fml.ModList;

/**
 * Whether this game is one of the scripted runs of specification chapter XIV, switched on by the system property
 * dailies.test.script. The script itself is not in this jar: it is the mod dailies_selftest, a jar of its own that the
 * dev runs load beside Dailies and that only the test folders carry, and it starts itself when it is loaded. All
 * Dailies keeps is this: the switch, so a scripted run is left alone where a person would be asked something, and the
 * check that the script is there at all.
 */
public final class ScriptedRun {

    public static final String PROPERTY = "dailies.test.script";
    public static final String SELF_TEST_MOD = "dailies_selftest";

    private ScriptedRun() {
    }

    public static boolean asked() {
        return System.getProperty(PROPERTY) != null;
    }

    /**
     * When the mod starts. A scripted run asked for without the self test loaded would wait for a verdict that never
     * comes, so it is failed straight away, with the line the CI script looks for
     */
    static void checkSelfTestLoaded() {
        if (!asked() || ModList.get().isLoaded(SELF_TEST_MOD)) {
            return;
        }
        Host.LOGGER.error("DAILIES TEST FAILED: {} is set, and the self test ({}) is not loaded: its jar, dailies-selftest-*.jar, belongs beside Dailies in the mods folder (installTestMods puts it there)",
            PROPERTY, SELF_TEST_MOD);
        System.exit(1);
    }
}
