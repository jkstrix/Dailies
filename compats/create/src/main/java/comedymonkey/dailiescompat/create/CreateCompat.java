package comedymonkey.dailiescompat.create;

import com.mojang.logging.LogUtils;
import comedymonkey.dailiescompat.create.client.CreateDailies;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * The Create compat as a mod of its own, on the player's game only (specification CPT-6). Everything that refers to
 * Dailies or Create is in the client package and is only touched when both are loaded: that is the whole of the two soft
 * dependencies. Without either the mod does nothing.
 */
@Mod(value = CreateCompat.ID, dist = Dist.CLIENT)
public final class CreateCompat {

    public static final String ID = "dailies_create";
    private static final Logger LOGGER = LogUtils.getLogger();

    public CreateCompat() {
        if (!ModList.get().isLoaded("dailies") || !ModList.get().isLoaded("create")) {
            LOGGER.info("Dailies Create compat: Dailies or Create is not installed, so there is nothing to do");
            return;
        }
        try {
            CreateDailies.register();
        } catch (LinkageError tooOld) {
            // A Dailies from before the compat API, or a Create whose classes have changed shape
            LOGGER.warn("Dailies Create compat: this Dailies or this Create does not have what the compat needs, so Create's screens are not recorded", tooOld);
        }
    }

}
