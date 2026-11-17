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

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * The test world: a fresh flat creative world, made the same way every run.
 */
final class WorldSteps {

    static final String WORLD = "Dailies Test";

    private WorldSteps() {
    }

    static void createWorld(SelfTest test) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        Path saved = minecraft.gameDirectory.toPath().resolve("saves").resolve(WORLD);
        deleteTree(saved);
        // Every run starts with no recordings, so nothing from an earlier run can pass or fail a check
        deleteTree(comedymonkey.dailies.capture.Recorder.folder());

        test.onClientRun(() -> {
            GameRules rules = new GameRules();
            rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
            rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
            rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
            LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.NORMAL, true, rules, WorldDataConfiguration.DEFAULT);
            minecraft.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(20261117L, false, false),
                registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), new TitleScreen());
        });
        test.waitFor(() -> minecraft.level != null && minecraft.player != null && minecraft.getSingleplayerServer() != null && minecraft.screen == null,
            120, "the test world to open");
        // Let the first chunks arrive and the player settle on the ground
        test.sleepTicks(60);
        test.command("tp @s 0.5 -60 0.5 0 0");
        test.sleepTicks(20);
        test.log("test world ready");
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

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

}
