package comedymonkey.dailiescompat.extras.client;

import com.mojang.logging.LogUtils;
import comedymonkey.dailies.api.compat.Claims;
import comedymonkey.dailies.api.compat.DailiesCompat;
import comedymonkey.dailies.api.compat.DailiesCompats;
import comedymonkey.dailies.api.compat.DisplayTest;
import comedymonkey.dailiescompat.extras.ExtrasCompat;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The compat for the rest of the pack itself (specification CPT-12): the class that registers with Dailies, loaded only
 * when Dailies is, and at least one of the mods it covers (see {@link ExtrasCompat}). What the mods draw during play,
 * from a sweep of every screen, HUD layer, drawing into the world and line above the hotbar in their jars:
 * <ul>
 * <li>Explorer's Compass and Nature's Compass: the lines each shows while it is held, searching, found or not found
 * ({@link CompassLines}). Their search lists are menus and stay out;</li>
 * <li>Sophisticated Backpacks: a backpack's window, recorded whole with its upgrade tabs, since Dailies' stand-in player
 * for a window holds no backpack ({@link Backpacks}); its settings window is settings, and left out;</li>
 * <li>extras for windows Dailies draws itself: Curios' page and cosmetic slots ({@link CuriosExtras}), a corpse's page of
 * further items ({@link CorpseExtras});</li>
 * <li>the lines the mods put above the hotbar on the player's own game alone ({@link StatusLine}).</li>
 * </ul>
 * Left out, as not play or not interface: configuration screens; the compasses' search lists; Corpse's death history (a list opened by a key, like the statistics); Sophisticated Backpacks' settings window; the
 * Locator Bar mod, at the owner's word; and how the world looks (a backpack's mob catcher effect, Sophisticated's
 * highlighted blocks), which playback draws from its own world. VanillaBackport's nautilus inventory, recorded on
 * Minecraft 1.21.1, is left out on 26.3, where VanillaBackport has no release.
 * <p>
 * Each state is the least that draws its part again, and the whole of it; Dailies writes it when it changes and keeps it
 * for snapshots, jumps and trims.
 */
public final class ExtrasDailies implements DailiesCompat {

    public static final String ID = ExtrasCompat.ID + ":extras";
    /** Raise when a state in ExtrasStates changes shape; the parts must still read every version before it */
    static final int VERSION = 1;
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Predicate<String> loaded;

    private ExtrasDailies(Predicate<String> loaded) {
        this.loaded = loaded;
    }

    /** Registers the compat and its listeners, with the parts of the mods that are loaded. Only call it when Dailies is loaded */
    public static void register(Predicate<String> loaded) {
        DailiesCompats.register(new ExtrasDailies(loaded));
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, RenderGuiLayerEvent.Pre.class, CompassLines::layerStarts);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, RenderGuiLayerEvent.Post.class, CompassLines::layerEnds);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public void claim(Claims claims) {
        // The line above the hotbar, which two of the mods (Sophisticated Backpacks and Corpse) put up on the player's own game
        claims.overlay(StatusLine.NAME, Parts.overlay(StatusLine::capture, StatusLine::draw, 1));
        boolean explorers = this.loaded.test(ExtrasCompat.EXPLORERS_COMPASS);
        boolean natures = this.loaded.test(ExtrasCompat.NATURES_COMPASS);
        if (explorers) {
            claims.overlay(CompassLines.EXPLORERS, Parts.overlay(CompassLines::captureExplorers, CompassLines::draw, 1));
        }
        if (natures) {
            claims.overlay(CompassLines.NATURES, Parts.overlay(CompassLines::captureNatures, CompassLines::draw, 1));
        }
        claimFor(ExtrasCompat.BACKPACKS, claims, Backpacks::claim);
        claimFor(ExtrasCompat.CURIOS, claims, CuriosExtras::claim);
        claimFor(ExtrasCompat.CORPSE, claims, CorpseExtras::claim);
    }

    /** One mod's claims, made only when it is loaded; a mod whose classes have changed shape leaves out its own part alone */
    private void claimFor(String mod, Claims claims, Consumer<Claims> claim) {
        if (!this.loaded.test(mod)) {
            return;
        }
        try {
            claim.accept(claims);
        } catch (LinkageError | RuntimeException changed) {
            LOGGER.warn("Dailies extras compat: this version of {} does not have what the compat needs, so its screens are not recorded", mod, changed);
        }
    }

    @Override
    public DisplayTest displayTest() {
        return new ExtrasDisplayTest(this.loaded);
    }

}
