package comedymonkey.dailiescompat.extras;

import com.mojang.logging.LogUtils;
import comedymonkey.dailiescompat.extras.client.ExtrasDailies;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

import java.util.List;

/**
 * The compat for the rest of the pack as a mod of its own, on the player's game only (specification CPT-12). Everything
 * that refers to Dailies or to the mods is in the client package and is only touched when Dailies and at least one of
 * them is loaded: that is the whole of the soft dependencies. Without Dailies the mod does nothing; without one of the
 * mods it leaves out that mod's part.
 */
@Mod(value = ExtrasCompat.ID, dist = Dist.CLIENT)
public final class ExtrasCompat {

    public static final String ID = "dailies_extras";
    /** The mods with something drawn during play for the compat to record, by mod id */
    public static final String EXPLORERS_COMPASS = "explorerscompass";
    public static final String NATURES_COMPASS = "naturescompass";
    public static final String VISTA = "vista";
    public static final String EMOTECRAFT = "emotecraft";
    public static final String BACKPACKS = "sophisticatedbackpacks";
    public static final String CURIOS = "curios";
    public static final String CORPSE = "corpse";
    public static final String PIPEZ = "pipez";
    public static final String VANILLA_BACKPORT = "vanillabackport";
    public static final List<String> MODS = List.of(EXPLORERS_COMPASS, NATURES_COMPASS, VISTA, EMOTECRAFT, BACKPACKS, CURIOS, CORPSE, PIPEZ, VANILLA_BACKPORT);
    private static final Logger LOGGER = LogUtils.getLogger();

    public ExtrasCompat() {
        ModList mods = ModList.get();
        if (!mods.isLoaded("dailies") || MODS.stream().noneMatch(mods::isLoaded)) {
            LOGGER.info("Dailies extras compat: Dailies, or every mod it covers, is not installed, so there is nothing to do");
            return;
        }
        try {
            ExtrasDailies.register(mods::isLoaded);
        } catch (LinkageError tooOld) {
            // A Dailies from before the compat API, or a version of the mods whose classes have changed shape
            LOGGER.warn("Dailies extras compat: this Dailies or these mods do not have what the compat needs, so their screens are not recorded", tooOld);
        }
    }

}
