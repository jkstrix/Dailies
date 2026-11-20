package comedymonkey.dailiescompat.jade;

import com.mojang.logging.LogUtils;
import comedymonkey.dailiescompat.jade.client.JadeDailies;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * The Jade compat as a mod of its own, on the player's game only (specification CPT-8). Everything that refers to
 * Dailies or Jade is in the client package and is only touched when both are loaded: that is the whole of the two soft
 * dependencies. Without either the mod does nothing. Jade finds the compat's plugin ({@code client.JadeHooks}) by
 * itself, and only loads it when Dailies is loaded too.
 */
@Mod(value = JadeCompat.ID, dist = Dist.CLIENT)
public final class JadeCompat {

    public static final String ID = "dailies_jade";
    private static final Logger LOGGER = LogUtils.getLogger();

    public JadeCompat() {
        if (!ModList.get().isLoaded("dailies") || !ModList.get().isLoaded("jade")) {
            LOGGER.info("Dailies Jade compat: Dailies or Jade is not installed, so there is nothing to do");
            return;
        }
        try {
            JadeDailies.register();
        } catch (LinkageError tooOld) {
            // A Dailies from before the compat API, or a Jade whose classes have changed shape
            LOGGER.warn("Dailies Jade compat: this Dailies or this Jade does not have what the compat needs, so Jade's box is not recorded", tooOld);
        }
    }

}
