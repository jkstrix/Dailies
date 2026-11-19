package comedymonkey.dailiescompat.aeronautics;

import com.mojang.logging.LogUtils;
import comedymonkey.dailiescompat.aeronautics.client.AeronauticsDailies;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * The Create Aeronautics compat as a mod of its own, on the player's game only (specification CPT-9). Everything that
 * refers to Dailies or to the mods is in the client package and is only touched when Dailies and at least one of them is
 * loaded: that is the whole of the soft dependencies. Without Dailies the mod does nothing; without one of the mods it
 * leaves out that mod's part.
 */
@Mod(value = AeronauticsCompat.ID, dist = Dist.CLIENT)
public final class AeronauticsCompat {

    public static final String ID = "dailies_aeronautics";
    /** The mods with something drawn during play for the compat to record: Create Aeronautics' Simulated, and Simulated Coasters */
    public static final String SIMULATED = "simulated";
    public static final String COASTERS = "simulatedcoasters";
    private static final Logger LOGGER = LogUtils.getLogger();

    public AeronauticsCompat() {
        ModList mods = ModList.get();
        if (!mods.isLoaded("dailies") || !mods.isLoaded(SIMULATED) && !mods.isLoaded(COASTERS)) {
            LOGGER.info("Dailies Create Aeronautics compat: Dailies, or Create Aeronautics and Simulated Coasters, are not installed, so there is nothing to do");
            return;
        }
        try {
            AeronauticsDailies.register(mods.isLoaded(SIMULATED), mods.isLoaded(COASTERS));
        } catch (LinkageError tooOld) {
            // A Dailies from before the compat API, or a version of the mods whose classes have changed shape
            LOGGER.warn("Dailies Create Aeronautics compat: this Dailies or these mods do not have what the compat needs, so their screens are not recorded", tooOld);
        }
    }

}
