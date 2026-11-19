package comedymonkey.dailiescompat.createextras;

import com.mojang.logging.LogUtils;
import comedymonkey.dailiescompat.createextras.client.CreateExtrasDailies;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * The compat for Create's other add-ons as a mod of its own, on the player's game only (specification CPT-11). Everything
 * that refers to Dailies or to the add-ons is in the client package and is only touched when Dailies and at least one of
 * them is loaded: that is the whole of the soft dependencies. Without Dailies the mod does nothing; without one of the
 * add-ons it leaves out that add-on's part.
 */
@Mod(value = CreateExtrasCompat.ID, dist = Dist.CLIENT)
public final class CreateExtrasCompat {

    public static final String ID = "dailies_createextras";
    /** The add-ons, by mod id: Create Deep Seas (with its Abyss and High Seas parts), Create Connected, Create Encased, Create: Tracks, Steam 'n' Rails, Create Jetpack, Create: Hypertubes */
    public static final String DEEP_SEAS = "create_submarine";
    public static final String CONNECTED = "create_connected";
    public static final String ENCASED = "createcasing";
    public static final String TRACKS = "tracks";
    public static final String RAILS = "railways";
    public static final String JETPACK = "create_jetpack";
    public static final String HYPERTUBE = "create_hypertube";
    private static final Logger LOGGER = LogUtils.getLogger();

    public CreateExtrasCompat() {
        ModList mods = ModList.get();
        boolean any = mods.isLoaded(DEEP_SEAS) || mods.isLoaded(CONNECTED) || mods.isLoaded(ENCASED) || mods.isLoaded(TRACKS) || mods.isLoaded(RAILS)
            || mods.isLoaded(JETPACK) || mods.isLoaded(HYPERTUBE);
        if (!mods.isLoaded("dailies") || !any) {
            LOGGER.info("Dailies Create add-ons compat: Dailies, or Create's add-ons, are not installed, so there is nothing to do");
            return;
        }
        try {
            CreateExtrasDailies.register(new CreateExtrasDailies.Mods(mods.isLoaded(DEEP_SEAS), mods.isLoaded(CONNECTED), mods.isLoaded(ENCASED),
                mods.isLoaded(TRACKS), mods.isLoaded(RAILS), mods.isLoaded(JETPACK), mods.isLoaded(HYPERTUBE)));
        } catch (LinkageError tooOld) {
            // A Dailies from before the compat API, or a version of the add-ons whose classes have changed shape
            LOGGER.warn("Dailies Create add-ons compat: this Dailies or these add-ons do not have what the compat needs, so their screens are not recorded", tooOld);
        }
    }

}
