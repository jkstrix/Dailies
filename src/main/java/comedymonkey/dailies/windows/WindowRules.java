package comedymonkey.dailies.windows;

import java.util.Locale;
import java.util.Set;

/**
 * Which of the recorder's screens a recording keeps (specification REC-25). It works from an allow list: a screen is
 * kept only when it is a play screen, one opened by using something in the world or by the inventory key. For now
 * that is a window the server opened with a menu type (a chest, a furnace, a villager's trades, a mod's machine), a
 * horse's inventory, and the player's own inventory. Everything else is left out without being looked at: settings,
 * statistics, advancements, the pause menu and every other menu, the creative inventory, books, signs, chat, title
 * and loading screens, and anything a mod draws for itself. A screen a compat claims whole (REC-26) is the compat's,
 * and left out here. No game in here, so it is unit tested.
 */
public final class WindowRules {

    /**
     * Server-driven windows that are play screens but draw wrongly when built again in playback, by menu type id. Each
     * says why. A compat can take one of these over later.
     */
    static final Set<String> DENIED = Set.of(
        // Its list of recipes is worked out on the player's side from the recipes the server sent, which a recording
        // leaves out (REC-11): played back it shows the input and nothing to choose from
        "minecraft:stonecutter"
    );

    /**
     * What is known of the screen on the recorder's game at the end of a tick.
     *
     * @param menuScreen      whether it is a screen built on a menu (any container screen)
     * @param inventoryScreen whether it is the game's own inventory screen, showing the player's own inventory menu
     * @param horseScreen     whether it is the game's horse inventory screen
     * @param playersMenu     whether its menu is the one the server opened for the player (the player's open container)
     * @param containerId     its menu's container id; 0 is the player's own inventory
     * @param menuType        its menu's type id, or null when the menu has none (the inventory, a horse, the creative inventory)
     * @param horseOpened     whether the server's packet opening a horse's inventory with this container id was seen
     * @param claimed         whether a compat claims the screen whole, by its class or its menu type (REC-26)
     */
    record Facts(boolean menuScreen, boolean inventoryScreen, boolean horseScreen, boolean playersMenu, int containerId, String menuType, boolean horseOpened,
                 boolean claimed) {

        Facts(boolean menuScreen, boolean inventoryScreen, boolean horseScreen, boolean playersMenu, int containerId, String menuType, boolean horseOpened) {
            this(menuScreen, inventoryScreen, horseScreen, playersMenu, containerId, menuType, horseOpened, false);
        }

        /** A screen that is not built on a menu at all: the pause menu, settings, statistics, a book, chat */
        static Facts notAWindow() {
            return new Facts(false, false, false, false, -1, null, false);
        }
    }

    private WindowRules() {
    }

    /** @return how the window is to be recorded, or null when the screen is not a play screen and is left out */
    static WindowState.Source decide(Facts facts) {
        if (facts.claimed()) {
            // A compat records it, and only the compat: the two would draw it twice
            return null;
        }
        if (facts.inventoryScreen()) {
            return WindowState.Source.INVENTORY;
        }
        if (!facts.menuScreen() || !facts.playersMenu()) {
            return null;
        }
        if (facts.horseScreen()) {
            return facts.horseOpened() ? WindowState.Source.HORSE : null;
        }
        if (facts.containerId() <= 0 || facts.menuType() == null || facts.menuType().isEmpty() || denied(facts.menuType())) {
            return null;
        }
        return WindowState.Source.SERVER;
    }

    static boolean denied(String menuType) {
        return menuType != null && DENIED.contains(menuType.toLowerCase(Locale.ROOT));
    }

}
