package comedymonkey.dailiescompat.jade.client;

import com.mojang.logging.LogUtils;
import comedymonkey.dailies.api.compat.DisplayTest;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import snownee.jade.api.JadeIds;
import snownee.jade.api.config.IWailaConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * The Jade compat's display test, run by Dailies' scripted run (its jade group): a chest holding three kinds of item two
 * blocks south of the player, a furnace smelting raw iron two blocks east, a pig two blocks west and, with Create, a
 * creative motor two blocks north. While recording the player, in survival, turns to look at each in turn, and Dailies
 * then checks Jade's box is drawn riding along in first person, and nothing in third person or flying free.
 * <p>
 * Jade's own conditions for its box, all met here: its box is on (its default) and shown without a key held (its
 * default, toggled by a key, which the stage puts back if a test game was left with it off or held); the thing looked at
 * is within the player's reach, 4.5 blocks for a block and 3 for a mob in survival, Jade adding nothing by default; no
 * screen is open; and a chest's contents and a furnace's progress come from the server, which in singleplayer is the
 * game's own, with Jade on it. Jade shows no box until the server has answered, so each is looked at for half a second
 * before the test's two seconds start. Details shown only while a key is held (Jade's "lite" mode) are not needed: the
 * default mode shows the contents, the progress and the hearts at once.
 */
final class JadeDisplayTest implements DisplayTest {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Thirty degrees down, from the player's eyes at the middle of a block, meets the side of a block two away */
    private static final int DOWN = 30;

    @Override
    public void stage(Stage stage) throws Exception {
        stage.onClient(() -> {
            IWailaConfig.General general = IWailaConfig.get().general();
            if (!general.shouldDisplayTooltip()) {
                general.setDisplayTooltip(true);
            }
            if (general.getDisplayMode() != IWailaConfig.DisplayMode.TOGGLE) {
                // Held for a key, or details only with a key: the test holds no key
                general.setDisplayMode(IWailaConfig.DisplayMode.TOGGLE);
            }
            general.setDisplayBlocks(true);
            general.setDisplayEntities(true);
            for (var id : List.of(JadeIds.UNIVERSAL_ITEM_STORAGE, JadeIds.MC_FURNACE, JadeIds.MC_ENTITY_HEALTH)) {
                if (!IWailaConfig.get().plugin().get(id)) {
                    LOGGER.warn("Dailies Jade compat's display test: Jade's {} is turned off in this game's settings, so its box shows less", id);
                }
            }
        });
        // A chest facing the player, with what Jade lists from the server's answer
        stage.command("setblock 0 -60 2 minecraft:chest[facing=north]");
        // A furnace facing the player, burning coal on raw iron
        stage.command("setblock 2 -60 0 minecraft:furnace[facing=west]");
        if (ModList.get().isLoaded("create")) {
            stage.command("setblock 0 -60 -2 create:creative_motor[facing=north]");
        }
        // Their block entities are made with the block, but filled only once the server has them
        stage.waitTicks(5);
        stage.command("item replace block 0 -60 2 container.0 with minecraft:diamond 12");
        stage.command("item replace block 0 -60 2 container.1 with minecraft:iron_ingot 32");
        stage.command("item replace block 0 -60 2 container.4 with minecraft:oak_log 16");
        stage.command("item replace block 2 -60 0 container.0 with minecraft:raw_iron 32");
        stage.command("item replace block 2 -60 0 container.1 with minecraft:coal 16");
        // A pig that stands still, its east side two blocks west of the player
        stage.command("summon minecraft:pig -1.5 -60 0.5 {NoAI:1b,Rotation:[0.0f,0.0f]}");
        stage.waitTicks(5);
    }

    /** Turns the player at their place by yaw and pitch, then waits for Jade to have the server's answer */
    private static Opener lookAt(float yaw) {
        return stage -> {
            stage.command("tp @s 0.5 -60 0.5 " + yaw + " " + DOWN);
            stage.waitTicks(10);
        };
    }

    @Override
    public List<Display> displays() {
        String box = JadeDailies.ID + "/" + TooltipBox.NAME;
        List<Display> displays = new ArrayList<>();
        displays.add(new Display("Jade's box on a chest with items in it", box, lookAt(0.0F)));
        displays.add(new Display("Jade's box on a furnace smelting", box, lookAt(-90.0F)));
        displays.add(new Display("Jade's box on a pig, with its hearts", box, lookAt(90.0F)));
        if (ModList.get().isLoaded("create")) {
            displays.add(new Display("Jade's box on a creative motor", box, lookAt(180.0F)));
        }
        return displays;
    }

}
