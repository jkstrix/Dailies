package comedymonkey.dailies.selftest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.nio.file.Path;

import static comedymonkey.dailies.selftest.SelfTestSupport.deleteTree;

/**
 * The test world: a fresh flat creative world, made the same way every run.
 */
final class WorldSteps {

    static final String WORLD = "Dailies Test";

    private WorldSteps() {
    }

    static void createWorld(SelfTest test) throws Exception {
        // Every run starts with no recordings, so nothing from an earlier run can pass or fail a check
        deleteTree(comedymonkey.dailies.capture.Recorder.folder());
        createWorld(test, WORLD);
    }

    /** Another fresh flat world, for the steps that need a recording from somewhere else. The recordings are left alone */
    static void createWorld(SelfTest test, String name) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        Path saved = minecraft.gameDirectory.toPath().resolve("saves").resolve(name);
        deleteTree(saved);

        test.onClientRun(() -> {
            GameRules rules = new GameRules();
            rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
            rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
            rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
            LevelSettings settings = new LevelSettings(name, GameType.CREATIVE, false, Difficulty.NORMAL, true, rules, WorldDataConfiguration.DEFAULT);
            minecraft.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(20261117L, false, false),
                registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), new TitleScreen());
        });
        test.waitFor(() -> minecraft.level != null && minecraft.player != null && minecraft.getSingleplayerServer() != null && minecraft.screen == null,
            120, "the test world to open");
        // Let the first chunks arrive and the player settle on the ground
        test.waitTicks(60);
        test.command("tp @s 0.5 -60 0.5 0 0");
        test.waitTicks(20);
        test.log("test world ready");
    }

    /**
     * A fresh world of generated terrain: the game's own default kind, with structures, mobs spawning, and the day and
     * the weather running, as a person plays it. Creative, so the script can fly and build; the steps that need survival
     * switch to it. The seed is fixed so that a run can be repeated, but nothing may rely on what is where: the steps
     * look for what they need. The recordings are left alone
     */
    static void createRealWorld(SelfTest test, String name, long seed) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        deleteTree(minecraft.gameDirectory.toPath().resolve("saves").resolve(name));
        test.onClientRun(() -> {
            LevelSettings settings = new LevelSettings(name, GameType.CREATIVE, false, Difficulty.NORMAL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
            minecraft.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(seed, true, false),
                registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).createWorldDimensions(), new TitleScreen());
        });
        test.waitFor(() -> minecraft.level != null && minecraft.player != null && minecraft.getSingleplayerServer() != null && minecraft.screen == null,
            300, "the generated test world to open");
        test.waitTicks(60);
        test.log("generated test world ready");
    }

    static void leaveWorld(SelfTest test) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        test.onClientRun(() -> {
            if (minecraft.level != null) {
                minecraft.level.disconnect();
                minecraft.disconnect();
                minecraft.setScreen(new TitleScreen());
            }
        });
        test.waitFor(() -> minecraft.level == null && !minecraft.isLocalServer(), 60, "the world to close");
    }

}
