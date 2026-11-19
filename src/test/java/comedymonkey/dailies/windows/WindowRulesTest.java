package comedymonkey.dailies.windows;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which of the recorder's screens are play screens, recorded, and which are left out */
class WindowRulesTest {

    /** A window the server opened for the player, of this menu type */
    private static WindowRules.Facts server(int containerId, String menuType) {
        return new WindowRules.Facts(true, false, false, true, containerId, menuType, false);
    }

    @Test
    void windowsTheServerOpensAreRecorded() {
        for (String type : new String[]{"minecraft:generic_9x3", "minecraft:generic_9x6", "minecraft:furnace", "minecraft:merchant", "minecraft:crafting",
            "minecraft:anvil", "minecraft:enchantment", "minecraft:brewing_stand", "minecraft:beacon", "minecraft:shulker_box", "postrider:mailbox",
            "sophisticatedbackpacks:backpack"}) {
            assertEquals(WindowState.Source.SERVER, WindowRules.decide(server(4, type)), type);
        }
    }

    @Test
    void thePlayersOwnInventoryAndAHorsesAreRecorded() {
        assertEquals(WindowState.Source.INVENTORY, WindowRules.decide(new WindowRules.Facts(true, true, false, true, 0, null, false)));
        assertEquals(WindowState.Source.HORSE, WindowRules.decide(new WindowRules.Facts(true, false, true, true, 3, null, true)));
        // A horse screen nobody saw the server open is not known to be a horse's at all
        assertNull(WindowRules.decide(new WindowRules.Facts(true, false, true, true, 3, null, false)));
    }

    @Test
    void screensThatAreNotPlayAreLeftOut() {
        // The pause menu, settings, statistics, advancements, chat, a book, a sign, the title screen: no menu at all
        assertNull(WindowRules.decide(WindowRules.Facts.notAWindow()));
        // The creative inventory: built on a menu with no type and no id, and not the inventory screen
        assertNull(WindowRules.decide(new WindowRules.Facts(true, false, false, false, 0, null, false)));
        // A menu of the player's own inventory shown by some other screen (a mod's panel) is not the inventory key's screen
        assertNull(WindowRules.decide(new WindowRules.Facts(true, false, false, true, 0, null, false)));
        // A screen with a typed menu the server did not open for the player: something a mod built on its side alone
        assertNull(WindowRules.decide(new WindowRules.Facts(true, false, false, false, 5, "somemod:client_panel", false)));
        // A menu with an id but no type
        assertNull(WindowRules.decide(new WindowRules.Facts(true, false, false, true, 5, null, false)));
        assertNull(WindowRules.decide(new WindowRules.Facts(true, false, false, true, 5, "", false)));
        // Container id 0 belongs to the player's inventory, never to a window the server opened
        assertNull(WindowRules.decide(server(0, "minecraft:generic_9x3")));
    }

    @Test
    void windowsOnTheDenyListAreLeftOut() {
        assertTrue(WindowRules.denied("minecraft:stonecutter"));
        assertTrue(WindowRules.denied("Minecraft:Stonecutter"));
        assertFalse(WindowRules.denied("minecraft:furnace"));
        assertFalse(WindowRules.denied(null));
        assertNull(WindowRules.decide(server(6, "minecraft:stonecutter")));
        for (String denied : WindowRules.DENIED) {
            assertNull(WindowRules.decide(server(6, denied)), denied);
        }
    }

}
