package comedymonkey.dailies.selftest;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

/**
 * The self test as a mod of its own, dailies_selftest, loaded after Dailies: by the dev runs from its classes, and in
 * run-modded from its own jar, which installTestMods puts beside the Dailies jar. It is never in the jar players get.
 * Loaded without dailies.test.script, it does nothing.
 */
@Mod(value = SelfTestMod.ID, dist = Dist.CLIENT)
public final class SelfTestMod {

    public static final String ID = "dailies_selftest";

    public SelfTestMod() {
        SelfTest.startIfAsked();
    }
}
