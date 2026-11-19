package comedymonkey.dailiescompat.extras.client;

import com.mojang.logging.LogUtils;
import comedymonkey.dailies.api.compat.DisplayTest;
import comedymonkey.dailiescompat.extras.ExtrasCompat;
import de.maxhenkel.corpse.corelib.death.Death;
import de.maxhenkel.corpse.entities.CorpseEntity;
import de.maxhenkel.pipez.Filter;
import de.maxhenkel.pipez.blocks.PipeBlock;
import de.maxhenkel.pipez.blocks.tileentity.PipeLogicTileEntity;
import de.maxhenkel.pipez.gui.ExtractContainer;
import de.maxhenkel.pipez.gui.ExtractScreen;
import de.maxhenkel.pipez.gui.FilterContainer;
import de.maxhenkel.pipez.gui.FilterScreen;
import de.maxhenkel.pipez.gui.containerfactory.FilterContainerProvider;
import de.maxhenkel.pipez.gui.containerfactory.PipeContainerProvider;
import com.blackgear.vanillabackport.common.level.entities.mob.animal.nautilus.AbstractNautilus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper;
import org.slf4j.Logger;
import top.theillusivec4.curios.common.inventory.container.CuriosContainerProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The display test of the compat for the rest of the pack, run by Dailies' scripted run (its extras group). The stage,
 * around the player at the middle of a flat world: a Pipez universal pipe two blocks south and two west, pulling from a
 * chest beyond it. While recording, the player, in survival, holds Explorer's Compass and Nature's Compass each with
 * something found, opens a backpack held in the hand and one with a pickup upgrade's tab open, opens the Curios window, opens a corpse
 * laid two blocks off, opens the pipe's settings on its first and its second tab and a filter with an item typed, and
 * opens a tamed nautilus' inventory. Dailies then checks each is drawn riding along in first person, and nothing in third
 * person or flying free.
 * <p>
 * The mods' own conditions for drawing, met here: a compass shows its lines while held in either hand with no screen
 * open, from its state on the item (found, with a structure or biome and where), set as the item is given; the backpack is opened as using it in the hand opens it, its upgrade and open tab
 * set on the backpack first; the Curios window as Curios' key opens it, by the server; the corpse is opened by the server
 * as using it does, once the player's game has it, within reach, and holding the items the player carried; the pipe's
 * windows as using its pulling side and its filter button open them, by the server; and the nautilus, once tamed and
 * known to the player's game, as using it while sneaking opens its inventory, two blocks off (within its reach of four).
 * Turned by yaw and pitch, never by {@code tp ... facing}; block entities are waited for after {@code setblock}. Not in
 * the test: Curios' second page and cosmetic slots (no pack of curio slots big enough in a test world), a corpse's page of
 * further items (they come only from mods' slots), and a line Sophisticated Backpacks or Corpse puts above the hotbar
 * (the tool swap key and the death history key with no deaths), which a key opens rather than a step.
 */
final class ExtrasDisplayTest implements DisplayTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final BlockPos PIPE = new BlockPos(-2, -60, 2);
    static final BlockPos PIPE_CHEST = new BlockPos(-2, -60, 3);

    private final Predicate<String> loaded;

    ExtrasDisplayTest(Predicate<String> loaded) {
        this.loaded = loaded;
    }

    @Override
    public void stage(Stage stage) throws Exception {
        if (this.loaded.test(ExtrasCompat.PIPEZ)) {
            stage.command("setblock -2 -60 3 minecraft:chest[facing=north]");
            stage.command("setblock -2 -60 2 pipez:universal_pipe");
        }
        // Their block entities are made with the blocks, and set up on their first ticks
        stage.waitTicks(10);
        if (this.loaded.test(ExtrasCompat.PIPEZ)) {
            stage.onServer(PipezSteps::pullFromChest);
            stage.waitTicks(5);
        }
    }

    /** Puts the player back as each display starts: nothing held, at the middle. The test closes a screen left open */
    private void reset(Stage stage) throws Exception {
        stage.command("item replace entity @s weapon.mainhand with minecraft:air");
        stage.command("tp @s 0.5 -60 0.5 0 0");
        stage.waitTicks(3);
    }

    /** Stands the player at the middle, turned by yaw and pitch, and lets the game catch up */
    private static void face(Stage stage, float yaw, float pitch) throws Exception {
        stage.command("tp @s 0.5 -60 0.5 " + yaw + " " + pitch);
        stage.waitTicks(3);
    }

    @Override
    public List<Display> displays() {
        String prefix = ExtrasDailies.ID + "/";
        List<Display> displays = new ArrayList<>();
        if (this.loaded.test(ExtrasCompat.EXPLORERS_COMPASS)) {
            displays.add(new Display("Explorer's Compass held, a village found", prefix + CompassLines.EXPLORERS, stage -> {
                reset(stage);
                stage.command("item replace entity @s weapon.mainhand with explorerscompass:explorerscompass[explorerscompass:compass_state=2,"
                    + "explorerscompass:structure_id=\"minecraft:village_plains\",explorerscompass:found_x=120,explorerscompass:found_z=-340]");
                stage.waitTicks(3);
            }));
        }
        if (this.loaded.test(ExtrasCompat.NATURES_COMPASS)) {
            displays.add(new Display("Nature's Compass held, a biome found", prefix + CompassLines.NATURES, stage -> {
                reset(stage);
                stage.command("item replace entity @s weapon.mainhand with naturescompass:naturescompass[naturescompass:compass_state=2,"
                    + "naturescompass:biome_id=\"minecraft:cherry_grove\",naturescompass:found_x=-480,naturescompass:found_z=256]");
                stage.waitTicks(3);
            }));
        }
        if (this.loaded.test(ExtrasCompat.BACKPACKS)) {
            displays.add(new Display("a backpack held and opened", prefix + Backpacks.BACKPACK, stage -> {
                reset(stage);
                stage.command("item replace entity @s weapon.mainhand with sophisticatedbackpacks:backpack");
                stage.waitTicks(3);
                stage.onServer(player -> BackpackSteps.open(player, false));
                stage.waitTicks(5);
            }));
            displays.add(new Display("a backpack with its pickup upgrade's tab open", prefix + Backpacks.BACKPACK, stage -> {
                reset(stage);
                stage.command("item replace entity @s weapon.mainhand with sophisticatedbackpacks:backpack");
                stage.waitTicks(3);
                // Opened and closed once first: a backpack fresh from a command has no upgrade slots until it has been opened
                stage.onServer(player -> BackpackSteps.open(player, false));
                stage.waitTicks(5);
                stage.onServer(player -> player.closeContainer());
                stage.waitTicks(5);
                stage.onServer(player -> BackpackSteps.open(player, true));
                stage.waitTicks(5);
            }));
        }
        if (this.loaded.test(ExtrasCompat.CURIOS)) {
            displays.add(new Display("the Curios window", "curios:curios_container", stage -> {
                reset(stage);
                stage.onServer(CuriosSteps::open);
                stage.waitTicks(5);
            }));
        }
        if (this.loaded.test(ExtrasCompat.CORPSE)) {
            displays.add(new Display("a corpse opened", "corpse:corpse_inventory", stage -> {
                reset(stage);
                // Something in the player's pockets for the corpse to hold
                stage.command("item replace entity @s hotbar.1 with minecraft:diamond 5");
                stage.command("item replace entity @s hotbar.2 with minecraft:iron_pickaxe");
                stage.waitTicks(2);
                stage.onServer(CorpseSteps::layCorpse);
                stage.waitTicks(10);
                stage.onServer(CorpseSteps::open);
                stage.waitTicks(5);
            }));
        }
        if (this.loaded.test(ExtrasCompat.PIPEZ)) {
            displays.add(new Display("a universal pipe's settings", "pipez:extract", stage -> {
                reset(stage);
                face(stage, 135.0F, 30.0F);
                stage.onServer(PipezSteps::openSettings);
                stage.waitTicks(5);
            }));
            displays.add(new Display("a universal pipe's settings on its second tab", "pipez:extract", stage -> {
                reset(stage);
                face(stage, 135.0F, 30.0F);
                stage.onServer(PipezSteps::openSettings);
                stage.waitTicks(5);
                stage.onClient(PipezSteps::secondTab);
                stage.waitTicks(2);
            }));
            displays.add(new Display("a pipe's filter, an item typed", "pipez:filter", stage -> {
                reset(stage);
                face(stage, 135.0F, 30.0F);
                stage.onServer(PipezSteps::openFilter);
                stage.waitTicks(5);
                stage.onClient(PipezSteps::typeItem);
                stage.waitTicks(2);
            }));
        }
        if (this.loaded.test(ExtrasCompat.VANILLA_BACKPORT)) {
            displays.add(new Display("a tamed nautilus' inventory", prefix + NautilusPart.NAME, stage -> {
                reset(stage);
                face(stage, 135.0F, 20.0F);
                stage.onServer(NautilusSteps::tame);
                stage.waitTicks(10);
                stage.onServer(NautilusSteps::open);
                stage.waitTicks(5);
            }));
        }
        return displays;
    }

    // Each mod's steps in a class of its own, so a game without the mod never loads them

    /** Sophisticated Backpacks' steps */
    private static final class BackpackSteps {
        /** As using the backpack in the hand opens it; with a pickup upgrade put in first and its tab marked open on the backpack */
        static void open(ServerPlayer player, boolean withUpgrade) {
            ItemStack backpack = player.getMainHandItem();
            if (backpack.isEmpty()) {
                LOGGER.warn("Dailies extras compat's display test: no backpack in the hand");
                return;
            }
            if (withUpgrade) {
                IBackpackWrapper wrapper = BackpackWrapper.fromStack(backpack);
                if (wrapper.getUpgradeHandler().getSlots() == 0) {
                    LOGGER.warn("Dailies extras compat's display test: the backpack has no upgrade slots");
                } else {
                    wrapper.getUpgradeHandler().setStackInSlot(0, new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("sophisticatedbackpacks",
                        "pickup_upgrade"))));
                    wrapper.setOpenTabId(0);
                }
            }
            backpack.getItem().use(player.serverLevel(), player, InteractionHand.MAIN_HAND);
        }
    }

    /** Curios' steps */
    private static final class CuriosSteps {
        /** As Curios' key opens its window, by the server */
        static void open(ServerPlayer player) {
            player.openMenu(new CuriosContainerProvider());
        }
    }

    /** Corpse's steps */
    private static final class CorpseSteps {
        private static UUID laid = null;

        /** A corpse two blocks off, holding what the player carries, as dying leaves one */
        static void layCorpse(ServerPlayer player) {
            CorpseEntity corpse = CorpseEntity.createFromDeath(player, Death.fromPlayer(player));
            corpse.setPos(2.5, -60.0, 2.5);
            player.serverLevel().addFreshEntity(corpse);
            laid = corpse.getUUID();
        }

        /** As using the corpse opens it, once the player's game has it */
        static void open(ServerPlayer player) {
            Entity corpse = laid == null ? null : player.serverLevel().getEntity(laid);
            if (corpse instanceof CorpseEntity) {
                corpse.interact(player, InteractionHand.MAIN_HAND);
            } else {
                LOGGER.warn("Dailies extras compat's display test: the corpse was not laid");
            }
        }
    }

    /** Pipez's steps */
    private static final class PipezSteps {
        /** The pipe pulls from the chest beyond it, as using its side with a wrench sets it */
        static void pullFromChest(ServerPlayer player) {
            ServerLevel level = player.serverLevel();
            if (level.getBlockState(PIPE).getBlock() instanceof PipeBlock pipe) {
                pipe.setExtracting(level, PIPE, Direction.SOUTH, true);
            } else {
                LOGGER.warn("Dailies extras compat's display test: no pipe at {}", PIPE);
            }
        }

        /** As using the pipe's pulling side opens its settings */
        static void openSettings(ServerPlayer player) {
            if (player.serverLevel().getBlockEntity(PIPE) instanceof PipeLogicTileEntity pipe) {
                PipeContainerProvider.openGui(player, pipe, Direction.SOUTH, -1, (id, inventory, opener) -> new ExtractContainer(id, inventory, pipe, Direction.SOUTH, -1));
            } else {
                LOGGER.warn("Dailies extras compat's display test: the pipe at {} has no settings", PIPE);
            }
        }

        /** As clicking the second tab turns to it */
        static void secondTab() {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.screen instanceof ExtractScreen screen && screen.hasTabs()) {
                Reflect.set(screen, "currentindex", 1);
                Reflect.call(screen, "init", new Class<?>[0]);
            } else {
                LOGGER.warn("Dailies extras compat's display test: the pipe's settings have no tabs");
            }
        }

        /** As the settings' add button opens a new filter for the first kind of pipe */
        static void openFilter(ServerPlayer player) {
            if (player.serverLevel().getBlockEntity(PIPE) instanceof PipeLogicTileEntity pipe) {
                Filter<?, ?> filter = pipe.getPipeTypes()[0].createFilter();
                FilterContainerProvider.openGui(player, pipe, Direction.SOUTH, filter, 0, (id, inventory, opener) -> new FilterContainer(id, inventory, pipe,
                    Direction.SOUTH, 0, filter));
            }
        }

        static void typeItem() {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.screen instanceof FilterScreen screen) {
                EditBox item = Reflect.get(screen, "item");
                item.setValue("minecraft:cobblestone");
            } else {
                LOGGER.warn("Dailies extras compat's display test: the pipe's filter did not open");
            }
        }
    }

    /** VanillaBackport's steps */
    private static final class NautilusSteps {
        private static UUID tamed = null;

        /** A nautilus two blocks off, standing still, tamed by the player */
        static void tame(ServerPlayer player) {
            Entity made = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.fromNamespaceAndPath("vanillabackport", "nautilus")).create(player.serverLevel());
            if (!(made instanceof AbstractNautilus nautilus)) {
                LOGGER.warn("Dailies extras compat's display test: this game has no nautilus");
                return;
            }
            nautilus.moveTo(-1.5, -60.0, -1.5, 0.0F, 0.0F);
            nautilus.setNoAi(true);
            nautilus.tame(player);
            player.serverLevel().addFreshEntity(nautilus);
            tamed = nautilus.getUUID();
        }

        /** As using it while sneaking opens its inventory */
        static void open(ServerPlayer player) {
            Entity nautilus = tamed == null ? null : player.serverLevel().getEntity(tamed);
            if (nautilus instanceof AbstractNautilus tame) {
                tame.openCustomInventoryScreen(player);
            } else {
                LOGGER.warn("Dailies extras compat's display test: the nautilus was not tamed");
            }
        }
    }

}
