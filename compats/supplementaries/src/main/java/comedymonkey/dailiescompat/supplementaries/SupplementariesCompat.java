package comedymonkey.dailiescompat.supplementaries;

import com.mojang.logging.LogUtils;
import comedymonkey.dailiescompat.supplementaries.client.SuppDailies;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * The Supplementaries compat as a mod of its own, on the player's game only (specification CPT-10). Everything that refers
 * to Dailies or to the mods is in the client package and is only touched when Dailies and at least one of them is loaded:
 * that is the whole of the soft dependencies. Without Dailies the mod does nothing; without one of the mods it leaves out
 * that mod's part.
 */
@Mod(value = SupplementariesCompat.ID, dist = Dist.CLIENT)
public final class SupplementariesCompat {

    public static final String ID = "dailies_supplementaries";
    /** The mods with something drawn during play for the compat to record */
    public static final String SUPPLEMENTARIES = "supplementaries";
    public static final String AMENDMENTS = "amendments";
    private static final Logger LOGGER = LogUtils.getLogger();

    public SupplementariesCompat() {
        ModList mods = ModList.get();
        if (!mods.isLoaded("dailies") || !mods.isLoaded(SUPPLEMENTARIES) && !mods.isLoaded(AMENDMENTS)) {
            LOGGER.info("Dailies Supplementaries compat: Dailies, or Supplementaries and Amendments, are not installed, so there is nothing to do");
            return;
        }
        try {
            SuppDailies.register(mods.isLoaded(SUPPLEMENTARIES), mods.isLoaded(AMENDMENTS));
        } catch (LinkageError tooOld) {
            // A Dailies from before the compat API, or a version of the mods whose classes have changed shape
            LOGGER.warn("Dailies Supplementaries compat: this Dailies or these mods do not have what the compat needs, so their screens are not recorded", tooOld);
        }
    }

}
