package comedymonkey.dailies.selftest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.gamerules.GameRules;
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

    /**
     * The rules the next world made here starts with, or null for the game's defaults. A new world no longer takes its
     * rules with its settings, so they are set as its server is about to start, before any of it has ticked
     */
    private static volatile java.util.function.Consumer<GameRules> startingRules;
    private static boolean listening;

    private WorldSteps() {
    }

    private static void startWith(java.util.function.Consumer<GameRules> rules) {
        startingRules = rules;
        if (!listening) {
            listening = true;
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerAboutToStartEvent event) -> {
                java.util.function.Consumer<GameRules> set = startingRules;
                startingRules = null;
                if (set != null) {
                    set.accept(event.getServer().getGameRules());
                }
            });
        }
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
            startWith(rules -> {
                rules.set(GameRules.SPAWN_MOBS, false, null);
                rules.set(GameRules.ADVANCE_TIME, false, null);
                rules.set(GameRules.ADVANCE_WEATHER, false, null);
            });
            LevelSettings settings = new LevelSettings(name, GameType.CREATIVE, new LevelSettings.DifficultySettings(Difficulty.NORMAL, false, false), true, WorldDataConfiguration.DEFAULT);
            minecraft.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(20261117L, false, false),
                registries -> registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), new TitleScreen());
        });
        test.waitFor(() -> minecraft.level != null && minecraft.player != null && minecraft.getSingleplayerServer() != null && minecraft.gui.screen() == null,
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
            startWith(null);
            LevelSettings settings = new LevelSettings(name, GameType.CREATIVE, new LevelSettings.DifficultySettings(Difficulty.NORMAL, false, false), true, WorldDataConfiguration.DEFAULT);
            minecraft.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(seed, true, false),
                registries -> registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value().createWorldDimensions(), new TitleScreen());
        });
        test.waitFor(() -> minecraft.level != null && minecraft.player != null && minecraft.getSingleplayerServer() != null && minecraft.gui.screen() == null,
            300, "the generated test world to open");
        test.waitTicks(60);
        test.log("generated test world ready");
    }

    static void leaveWorld(SelfTest test) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        test.onClientRun(() -> {
            if (minecraft.level != null) {
                minecraft.level.disconnect(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE);
                minecraft.disconnectWithProgressScreen();
                minecraft.gui.setScreen(new TitleScreen());
            }
        });
        test.waitFor(() -> minecraft.level == null && !minecraft.isLocalServer(), 60, "the world to close");
    }

}
