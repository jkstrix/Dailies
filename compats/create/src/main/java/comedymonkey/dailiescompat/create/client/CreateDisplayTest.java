package comedymonkey.dailiescompat.create.client;

import com.simibubi.create.content.contraptions.wrench.RadialWrenchMenu;
import com.simibubi.create.content.equipment.clipboard.ClipboardScreen;
import com.simibubi.create.content.equipment.symmetryWand.SymmetryWandScreen;
import com.simibubi.create.content.equipment.toolbox.RadialToolboxMenu;
import com.simibubi.create.content.equipment.toolbox.ToolboxBlockEntity;
import com.simibubi.create.content.equipment.toolbox.ToolboxHandler;
import com.simibubi.create.content.equipment.toolbox.ToolboxInventory;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlockEntity;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftScreen;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlockEntity;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkScreen;
import com.simibubi.create.content.redstone.link.controller.LinkedControllerClientHandler;
import com.simibubi.create.content.schematics.client.SchematicPromptScreen;
import com.simibubi.create.content.trains.station.AssemblyScreen;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.simibubi.create.content.trains.station.StationBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsScreen;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import com.simibubi.create.foundation.utility.CreateLang;
import com.simibubi.create.foundation.utility.CreatePaths;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsBlock;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsHandler;
import com.simibubi.create.content.equipment.blueprint.BlueprintEntity;
import com.simibubi.create.content.equipment.zapper.terrainzapper.WorldshaperScreen;
import com.simibubi.create.content.logistics.stockTicker.StockKeeperRequestScreen;
import com.simibubi.create.content.schematics.client.SchematicEditScreen;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.schedule.IScheduleInput;
import com.simibubi.create.content.trains.schedule.ScheduleScreen;
import com.simibubi.create.content.trains.schedule.destination.DestinationInstruction;
import com.simibubi.create.foundation.gui.widget.CompositeWidget;
import comedymonkey.dailies.api.compat.DisplayTest;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.minecraft.world.phys.AABB;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The Create compat's display test, run by Dailies' scripted run (its create group): a creative motor with a shaft on
 * it, a sequenced gearshift, a fluid tank, a display link, a train station, a schematic table, a schematicannon and a
 * glued area beside the player, with a small build saved as a schematic file, a toolbox holding four kinds of item, an
 * encased fan, a crafting blueprint on the ground, a stock ticker kept by a blaze burner, a piece of track, and a train
 * of one carriage assembled on a line of its own. While recording, the motor's value settings board is opened and its
 * speed scrolled, the motor's value box is aimed at, the goggles are put on and a fluid tank looked at, the gearshift's
 * screen is opened, a clipboard is written in, the display link's and the station's assembly screens and the wand of
 * symmetry's are opened, super glue is held by the glued area, a status line is put up, a linked controller starts
 * binding, a schematic is held and one placed and its edit screen opened, the schematic table and the schematicannon
 * are used, a schematic is named, the worldshaper's screen is opened, the toolbox's marks are put on the hotbar, the
 * toolbox's and the wrench's selection wheels are opened, a shaft is held at a shaft for its placement helper, the wand
 * of symmetry puts its mirror down, the blueprint is looked at, a curve of track is begun, the stock keeper's window is
 * searched, a schedule's stop is edited, and the train's controls are taken and let go. Dailies then checks each is
 * drawn riding along in first person, and nothing in third person or flying free.
 */
final class CreateDisplayTest implements DisplayTest {

    private static final BlockPos MOTOR = new BlockPos(2, -60, 0);
    private static final BlockPos GEARSHIFT = new BlockPos(-2, -60, 0);
    private static final BlockPos DISPLAY_LINK = new BlockPos(4, -60, 3);
    private static final BlockPos STATION = new BlockPos(-4, -60, 3);
    private static final BlockPos TABLE = new BlockPos(6, -60, -3);
    private static final BlockPos CANNON = new BlockPos(-6, -60, -3);
    private static final BlockPos TOOLBOX = new BlockPos(-3, -60, -6);
    private static final BlockPos FAN = new BlockPos(3, -60, -6);
    private static final BlockPos BLUEPRINT = new BlockPos(0, -60, -9);
    /** The blaze burner beside the stock ticker, which keeps its stock */
    private static final BlockPos KEEPER = new BlockPos(-7, -60, -9);
    /** The first point of a curve of track, and the train's station on a line of its own */
    private static final BlockPos TRACK_START = new BlockPos(0, -60, 22);
    private static final BlockPos TRAIN_STATION = new BlockPos(0, -60, 15);
    /** A schematic of three by two by three blocks the stage saves in the player's schematics folder, for one placed */
    private static final String PLACED_FILE = "dailies_placed.nbt";
    private static final String TYPED = "Speed: 64";
    private static final String NAME = "Dailies";

    @Override
    public void stage(Stage stage) throws Exception {
        // A motor facing north drives the shaft north of it; its value box is on its top, which faces no shaft
        stage.command("setblock 2 -60 0 create:creative_motor[facing=north]");
        stage.command("setblock 2 -60 -1 create:shaft[axis=z]");
        stage.command("setblock -2 -60 0 create:sequenced_gearshift");
        stage.command("setblock 0 -60 -3 create:fluid_tank");
        stage.command("setblock 4 -60 3 create:display_link[facing=up]");
        stage.command("setblock -4 -60 3 create:track_station");
        stage.command("setblock 6 -60 -3 create:schematic_table");
        stage.command("setblock -6 -60 -3 create:schematicannon");
        // A glued area two blocks long on the ground, south of where the player stands
        stage.command("summon create:super_glue 0 -60 6 {From:[0.0d,0.0d,0.0d],To:[2.0d,1.0d,1.0d]}");
        // A toolbox for its wheel, and an encased fan, which the wrench's wheel turns to face any of six ways
        stage.command("setblock -3 -60 -6 create:brown_toolbox");
        stage.command("setblock 3 -60 -6 create:encased_fan[facing=north]");
        stage.waitTicks(5);
        // A small build saved as a schematic file, as the schematic and quill saves one, for a placed schematic's blocks
        stage.command("fill 8 -60 8 10 -60 10 create:andesite_casing");
        stage.command("setblock 9 -59 9 create:cogwheel");
        stage.command("setblock 8 -59 8 minecraft:glass");
        stage.waitTicks(3);
        stage.onServer(player -> {
            StructureTemplate template = new StructureTemplate();
            template.fillFromWorld(player.serverLevel(), new BlockPos(8, -60, 8), new Vec3i(3, 2, 3), false, null);
            try {
                Path file = CreatePaths.SCHEMATICS_DIR.resolve(PLACED_FILE);
                Files.createDirectories(file.getParent());
                NbtIo.writeCompressed(template.save(new CompoundTag()), file);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        // A stock ticker with a blaze burner beside it, which keeps its stock as a seated keeper would, and a crafting
        // blueprint laid on the ground holding a recipe in each of its sections
        stage.command("setblock -8 -60 -9 create:stock_ticker");
        // With a blaze in it (the property is "blaze"): a burner without one never opens the keeper
        stage.command("setblock -7 -60 -9 create:blaze_burner[blaze=smouldering]");
        stage.command("setblock 0 -60 22 create:track[shape=xo]");
        stage.onServer(player -> {
            BlueprintEntity blueprint = new BlueprintEntity(player.level(), BLUEPRINT, Direction.UP, Direction.NORTH);
            for (int section = 0; section < 9; section++) {
                ItemStackHandler recipe = new ItemStackHandler(11);
                recipe.setStackInSlot(0, new ItemStack(Items.OAK_PLANKS));
                recipe.setStackInSlot(3, new ItemStack(Items.OAK_PLANKS));
                recipe.setStackInSlot(9, new ItemStack(Items.STICK, 4));
                blueprint.getOrCreateRecipeCompound().put(String.valueOf(section), recipe.serializeNBT(player.level().registryAccess()));
            }
            player.level().addFreshEntity(blueprint);
        });
        stageTrain(stage);
        // Four of the toolbox's slots kept for an item each, the last of them empty again (its slot is still kept for it)
        stage.onServer(player -> {
            if (player.level().getBlockEntity(TOOLBOX) instanceof ToolboxBlockEntity toolbox) {
                ToolboxInventory inventory = Reflect.get(toolbox, "inventory");
                inventory.insertItem(0, new ItemStack(Items.IRON_PICKAXE), false);
                inventory.insertItem(ToolboxInventory.STACKS_PER_COMPARTMENT, Texts.stack("create:wrench"), false);
                inventory.insertItem(2 * ToolboxInventory.STACKS_PER_COMPARTMENT, new ItemStack(Items.TORCH, 32), false);
                inventory.insertItem(3 * ToolboxInventory.STACKS_PER_COMPARTMENT, Texts.stack("create:andesite_alloy").copyWithCount(8), false);
                inventory.extractItem(3 * ToolboxInventory.STACKS_PER_COMPARTMENT, 8, false);
            }
        });
        stage.waitTicks(5);
    }

    /** Stands on the motor looking straight down at the middle of its top, where its value box is */
    private static void aimAtMotor(Stage stage) throws Exception {
        stage.command("tp @s 2.5 -59 0.5625 0 90");
        stage.waitTicks(5);
    }

    @Override
    public List<Display> displays() {
        Minecraft minecraft = Minecraft.getInstance();
        return List.of(
            new Display("the motor's value settings board", CreateDailies.ID + "/" + ValueBoard.NAME, stage -> {
                aimAtMotor(stage);
                stage.onClient(() -> {
                    // As Create's ValueSettingsClient opens it once use has been held on the value box
                    ScrollValueBehaviour speed = BlockEntityBehaviour.get(minecraft.level, MOTOR, ScrollValueBehaviour.TYPE);
                    if (speed == null) {
                        return;
                    }
                    HitResult aimed = minecraft.hitResult;
                    BlockHitResult hit = aimed instanceof BlockHitResult block && block.getBlockPos().equals(MOTOR) ? block
                        : new BlockHitResult(Vec3.atCenterOf(MOTOR).add(0, 0.5, 0), Direction.UP, MOTOR, false);
                    minecraft.setScreen(new ValueSettingsScreen(MOTOR, speed.createBoard(minecraft.player, hit), speed.getValueSettings(),
                        speed::newSettingHovered, speed.netId()));
                });
                stage.waitTicks(5);
                // Scrolling moves the board's cursor along, as the mouse wheel does; the board picks what is under it
                for (int i = 0; i < 3; i++) {
                    stage.onClient(() -> {
                        if (minecraft.screen instanceof ValueSettingsScreen board) {
                            double x = minecraft.mouseHandler.xpos() * minecraft.getWindow().getGuiScaledWidth() / minecraft.getWindow().getScreenWidth();
                            double y = minecraft.mouseHandler.ypos() * minecraft.getWindow().getGuiScaledHeight() / minecraft.getWindow().getScreenHeight();
                            board.mouseScrolled(x, y, 0, 1);
                        }
                    });
                    stage.waitTicks(2);
                }
            }),
            new Display("the motor's value box", CreateDailies.ID + "/" + ValueBoxes.NAME, CreateDisplayTest::aimAtMotor),
            new Display("the goggles on a fluid tank", CreateDailies.ID + "/" + Goggles.NAME, stage -> {
                // A fluid tank: the goggles always show its capacity, and it has no value box. Create hides the goggles'
                // readout while a value box shows (the motor has one on every side looked at), and shows nothing for a
                // bare shaft, which has no stress figures
                stage.command("item replace entity @s armor.head with create:goggles");
                // Due north and 30 degrees down, at the middle of the tank (the facing form of tp did not turn the player)
                stage.command("tp @s 0.5 -60 -0.5 180 30");
                stage.waitTicks(5);
            }),
            new Display("a sequenced gearshift's screen", CreateDailies.ID + "/" + MachineScreens.GEARSHIFT, stage -> {
                stage.command("tp @s -2.5 -60 2.5 facing -1.5 -59.5 0.5");
                stage.waitTicks(3);
                stage.onClient(() -> {
                    if (minecraft.level.getBlockEntity(GEARSHIFT) instanceof SequencedGearshiftBlockEntity gearshift) {
                        minecraft.setScreen(new SequencedGearshiftScreen(gearshift));
                    }
                });
            }),
            new Display("a clipboard being written", CreateDailies.ID + "/" + ClipboardPart.NAME, stage -> {
                stage.command("item replace entity @s armor.head with minecraft:air");
                stage.command("item replace entity @s weapon.mainhand with create:clipboard");
                stage.command("tp @s 0.5 -60 3.5 0 -30");
                stage.waitTicks(5);
                stage.onClient(() -> minecraft.gameMode.useItem(minecraft.player, InteractionHand.MAIN_HAND));
                stage.waitTicks(3);
                for (char typed : TYPED.toCharArray()) {
                    stage.onClient(() -> {
                        if (minecraft.screen instanceof ClipboardScreen clipboard) {
                            clipboard.charTyped(typed, 0);
                        }
                    });
                    stage.waitTicks(1);
                }
            }),
            new Display("a display link's screen", CreateDailies.ID + "/" + PanelScreens.DISPLAY_LINK, stage -> {
                stage.command("item replace entity @s weapon.mainhand with minecraft:air");
                stage.command("tp @s 4.5 -60 1.5 0 30");
                stage.waitTicks(3);
                // As DisplayLinkBlock opens it when used; with nothing linked it says so
                stage.onClient(() -> {
                    if (minecraft.level.getBlockEntity(DISPLAY_LINK) instanceof DisplayLinkBlockEntity link) {
                        minecraft.setScreen(new DisplayLinkScreen(link));
                    }
                });
            }),
            new Display("a train station's assembly screen", CreateDailies.ID + "/" + StationScreens.ASSEMBLY, stage -> {
                stage.command("tp @s -3.5 -60 1.5 0 30");
                stage.waitTicks(3);
                // The assembly screen, as StationBlock opens it on a station assembling: it sends nothing unless a train
                // is assembled, so a station on no track is safe to show
                stage.onClient(() -> {
                    if (minecraft.level.getBlockEntity(STATION) instanceof StationBlockEntity station) {
                        minecraft.setScreen(new AssemblyScreen(station, new GlobalStation()));
                    }
                });
            }),
            new Display("the wand of symmetry's screen", CreateDailies.ID + "/" + SymmetryPart.NAME, stage -> {
                // In hand, as the wand opens it when used while sneaking; closing it sets the wand held
                stage.command("item replace entity @s weapon.mainhand with create:wand_of_symmetry");
                stage.waitTicks(3);
                stage.onClient(() -> minecraft.setScreen(new SymmetryWandScreen(minecraft.player.getMainHandItem(), InteractionHand.MAIN_HAND)));
            }),
            new Display("glued areas, with super glue held", CreateDailies.ID + "/" + Outlines.NAME, stage -> {
                // Create outlines the glued areas near a player holding glue
                stage.command("item replace entity @s weapon.mainhand with create:super_glue");
                stage.command("tp @s 1 -60 3.5 0 20");
                stage.waitTicks(5);
            }),
            new Display("a line Create puts above the hotbar", CreateDailies.ID + "/" + CreateHud.STATUS, stage -> {
                // As the glue handler says it when the second corner is too far; the line shows for three seconds
                stage.onClient(() -> CreateLang.translate("super_glue.too_far").sendStatus(minecraft.player));
                stage.waitTicks(2);
            }),
            new Display("a linked controller being bound", CreateDailies.ID + "/" + CreateHud.BIND, stage -> {
                // As using the controller on a redstone link while sneaking starts it; it waits for a key until the
                // controller leaves the hand, which the next display does
                stage.command("item replace entity @s weapon.mainhand with create:linked_controller");
                stage.waitTicks(3);
                stage.onClient(() -> LinkedControllerClientHandler.toggleBindMode(MOTOR));
                stage.waitTicks(3);
            }),
            new Display("a schematic held", CreateDailies.ID + "/" + SchematicParts.HELD, stage -> {
                // A schematic not yet placed: its tool bar offers only deploy, and the deploy preview follows the block
                // looked at. Its file is never read until it is placed
                stage.command("item replace entity @s weapon.mainhand with create:schematic[create:schematic_file=\"dailies_test.nbt\","
                    + "create:schematic_owner=\"Dev\",create:schematic_bounds=[3,2,3]]");
                stage.command("tp @s 0.5 -60 3.5 0 45");
                stage.waitTicks(5);
            }),
            new Display("a placed schematic's blocks", CreateDailies.ID + "/" + SchematicParts.HELD, stage -> {
                // Placed where it stands, south of the station: Create draws its blocks inside its box from the file saved
                // in the stage, which the recording stores once
                stage.command("item replace entity @s weapon.mainhand with create:schematic[create:schematic_file=\"" + PLACED_FILE + "\","
                    + "create:schematic_owner=\"Dev\",create:schematic_bounds=[3,2,3],create:schematic_deployed=true,create:schematic_anchor=[-9,-60,6]]");
                stage.command("tp @s -7.5 -60 2.5 0 30");
                stage.waitTicks(5);
            }),
            new Display("a placed schematic's edit screen", CreateDailies.ID + "/" + EditScreens.SCHEMATIC_EDIT, stage -> {
                // As the schematic's tool bar opens it on the placed schematic held; closing it places the schematic where
                // it already stands. A coordinate typed into, as the player does
                stage.onClient(() -> minecraft.setScreen(new SchematicEditScreen()));
                stage.waitTicks(3);
                stage.onClient(() -> {
                    if (minecraft.screen instanceof SchematicEditScreen edit) {
                        EditBox x = Reflect.get(edit, "xInput");
                        edit.setFocused(x);
                        x.setFocused(true);
                        x.moveCursorToEnd(false);
                    }
                });
                stage.waitTicks(2);
            }),
            new Display("the schematic table", "create:schematic_table", stage -> {
                stage.command("item replace entity @s weapon.mainhand with minecraft:air");
                stage.command("tp @s 6.5 -60 -0.5 180 20");
                stage.waitTicks(5);
                useBlock(stage, TABLE);
            }),
            new Display("the schematicannon", "create:schematicannon", stage -> {
                stage.command("tp @s -5.5 -60 -0.5 180 20");
                stage.waitTicks(5);
                useBlock(stage, CANNON);
            }),
            new Display("a schematic being named", CreateDailies.ID + "/" + SchematicParts.NAMING, stage -> {
                // As the schematic and quill opens it once a selection is made; it saves only when confirmed
                stage.onClient(() -> minecraft.setScreen(new SchematicPromptScreen()));
                stage.waitTicks(3);
                for (char typed : NAME.toCharArray()) {
                    stage.onClient(() -> {
                        if (minecraft.screen instanceof SchematicPromptScreen prompt) {
                            prompt.charTyped(typed, 0);
                        }
                    });
                    stage.waitTicks(1);
                }
            }),
            new Display("the worldshaper's screen", CreateDailies.ID + "/" + EditScreens.WORLDSHAPER, stage -> {
                // In hand, as the worldshaper opens it when used while sneaking; closing it sends the settings it opened with
                stage.command("item replace entity @s weapon.mainhand with create:handheld_worldshaper");
                stage.waitTicks(3);
                stage.onClient(() -> minecraft.setScreen(new WorldshaperScreen(minecraft.player.getMainHandItem(), InteractionHand.MAIN_HAND)));
            }),
            new Display("the toolbox's marks on the hotbar", CreateDailies.ID + "/" + PlayHud.TOOLBOX_MARKS, stage -> {
                // The selected hotbar slot as Create notes one holding an item taken from the toolbox, noted on the
                // player's game alone, so the toolbox on the server neither fills nor empties the slot
                stage.command("item replace entity @s weapon.mainhand with minecraft:air");
                stage.command("tp @s -2.5 -60 -3.5 180 30");
                stage.onClient(() -> {
                    CompoundTag taken = new CompoundTag();
                    taken.put("Pos", NbtUtils.writeBlockPos(TOOLBOX));
                    taken.putInt("Slot", 1);
                    CompoundTag marks = new CompoundTag();
                    marks.put(String.valueOf(minecraft.player.getInventory().selected), taken);
                    minecraft.player.getPersistentData().put("CreateToolboxData", marks);
                });
                stage.waitTicks(3);
            }),
            new Display("the toolbox's selection wheel", CreateDailies.ID + "/" + Wheels.TOOLBOX, stage -> {
                // Within the toolbox's reach, with nothing taken from it yet, so the wheel offers its slots
                stage.onClient(() -> tidy(minecraft));
                stage.command("item replace entity @s weapon.mainhand with minecraft:air");
                stage.command("tp @s -2.5 -60 -3.5 180 30");
                stage.waitTicks(5);
                stage.onClient(() -> {
                    List<ToolboxBlockEntity> toolboxes = ToolboxHandler.getNearest(minecraft.level, minecraft.player, 8);
                    toolboxes.sort(Comparator.comparing(ToolboxBlockEntity::getUniqueId));
                    if (!toolboxes.isEmpty()) {
                        minecraft.setScreen(new ToolboxWheelOnTop(toolboxes));
                    }
                });
                stage.waitTicks(3);
            }),
            new Display("the wrench's selection wheel", CreateDailies.ID + "/" + Wheels.WRENCH, stage -> {
                // As Create's key handler opens it with a wrench in hand on a block it can turn; it sends only when a key
                // or button is let go, and closing it does nothing more
                stage.command("item replace entity @s weapon.mainhand with create:wrench");
                stage.command("tp @s 3.5 -60 -3.5 180 30");
                stage.waitTicks(5);
                stage.onClient(() -> RadialWrenchMenu.tryCreateFor(minecraft.level.getBlockState(FAN), FAN, minecraft.level).ifPresent(minecraft::setScreen));
                stage.waitTicks(3);
            }),
            new Display("a placement helper", CreateDailies.ID + "/" + PlayHud.PLACEMENT, stage -> {
                // A shaft held at the shaft beside the motor, from the east, at the middle of its side: Create's shaft helper
                // shows a ghost shaft where the next one goes, and Catnip's arrow round the crosshair points at it
                stage.command("item replace entity @s weapon.mainhand with create:shaft");
                stage.command("tp @s 4.5 -60 -0.5 90 29");
                stage.waitTicks(15);
            }),
            new Display("the wand of symmetry's mirror", CreateDailies.ID + "/" + WorldMarks.MIRROR, stage -> {
                // Used on the ground, not sneaking, the wand puts its mirror there and turns it on
                stage.command("item replace entity @s weapon.mainhand with create:wand_of_symmetry");
                stage.command("tp @s 0.5 -60 -5.5 180 40");
                stage.waitTicks(3);
                stage.onClient(() -> minecraft.gameMode.useItemOn(minecraft.player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(new Vec3(0.5, -60.0, -7.5), Direction.UP, new BlockPos(0, -61, -8), false)));
                stage.waitTicks(5);
            }),
            new Display("a crafting blueprint looked at", CreateDailies.ID + "/" + PlayHud.BLUEPRINT, stage -> {
                // The recipe looked at, with nothing in the player's inventory: its ingredients shown missing, its sticks
                stage.command("item replace entity @s weapon.mainhand with minecraft:air");
                stage.command("tp @s 0.5 -60 -6.5 180 45");
                stage.waitTicks(10);
            }),
            new Display("the track placement hint", CreateDailies.ID + "/" + PlayHud.TRACK_HINT, stage -> {
                // The first point picked on a piece of track heading east, then the ground about 22 blocks east and 3
                // south looked at, still facing east: a long S-bend. Create shows the hint only while the curve has a
                // straight run at both ends (TrackPlacement resets its warm-up otherwise), which a plain turn or a short
                // S-bend never has. Held long enough for the item's name to go and the hint to warm up
                // A stack: the player is in survival, and a curve that needs more track than is held is not offered
                stage.command("item replace entity @s weapon.mainhand with create:track 64");
                stage.command("tp @s -1.5 -60 22.5 -90 50");
                stage.waitTicks(3);
                stage.onClient(() -> minecraft.gameMode.useItemOn(minecraft.player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(TRACK_START).add(0, -0.375, 0), Direction.UP, TRACK_START, false)));
                stage.waitTicks(3);
                stage.command("tp @s 19.5 -60 25.5 -90 28");
                stage.waitTicks(50);
            }),
            new Display("the stock keeper's search", "create:stock_keeper_request", stage -> {
                // The blaze burner beside the stock ticker used with an empty hand, as a player opens the keeper; a search
                // typed, which the extras keep
                stage.command("item replace entity @s weapon.mainhand with minecraft:air");
                stage.command("tp @s -6.5 -60 -6.5 180 20");
                stage.waitTicks(3);
                stage.onClient(() -> minecraft.gameMode.useItemOn(minecraft.player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(KEEPER).add(0, 0, 0.5), Direction.SOUTH, KEEPER, false)));
                stage.waitTicks(5);
                stage.onClient(() -> {
                    if (minecraft.screen instanceof StockKeeperRequestScreen keeper) {
                        keeper.searchBox.setFocused(true);
                    }
                });
                for (char typed : "iron".toCharArray()) {
                    stage.onClient(() -> {
                        if (minecraft.screen instanceof StockKeeperRequestScreen keeper) {
                            keeper.charTyped(typed, 0);
                        }
                    });
                    stage.waitTicks(1);
                }
            }),
            new Display("a schedule entry being edited", "create:schedule", stage -> {
                // A schedule opened from the hand, with a new stop's editor open over its list and a station typed in, as
                // the window's add button opens it. Closing the window saves the schedule without the stop, never confirmed
                stage.command("item replace entity @s weapon.mainhand with create:schedule");
                stage.waitTicks(3);
                stage.onClient(() -> minecraft.gameMode.useItem(minecraft.player, InteractionHand.MAIN_HAND));
                stage.waitTicks(5);
                stage.onClient(() -> {
                    if (minecraft.screen instanceof ScheduleScreen schedule) {
                        try {
                            Method start = ScheduleScreen.class.getDeclaredMethod("startEditing", IScheduleInput.class, Consumer.class, boolean.class);
                            start.setAccessible(true);
                            start.invoke(schedule, new DestinationInstruction(), (Consumer<Boolean>) confirmed -> {
                            }, false);
                        } catch (ReflectiveOperationException e) {
                            throw new IllegalStateException(e);
                        }
                        for (GuiEventListener field : ((CompositeWidget) Reflect.get(schedule, "editorSubWidgets")).children()) {
                            if (field instanceof EditBox station) {
                                station.setFocused(true);
                                station.setValue("Dailies West");
                            }
                        }
                    }
                });
                stage.waitTicks(3);
            }),
            new Display("a train being driven", CreateDailies.ID + "/" + CreateHud.TRAIN, stage -> {
                // Beside the train assembled in the stage, its controls taken as using them does on the player's game; the
                // next display lets them go
                stage.command("tp @s 3.5 -60 16.5 180 20");
                stage.waitTicks(10);
                stage.onClient(() -> {
                    for (CarriageContraptionEntity carriage : minecraft.level.getEntitiesOfClass(CarriageContraptionEntity.class,
                        new AABB(TRAIN_STATION).inflate(16))) {
                        for (Map.Entry<BlockPos, StructureTemplate.StructureBlockInfo> block : carriage.getContraption().getBlocks().entrySet()) {
                            if (block.getValue().state().getBlock() instanceof ControlsBlock) {
                                ControlsHandler.startControlling(carriage, block.getKey());
                                return;
                            }
                        }
                    }
                });
                stage.waitTicks(10);
            }),
            new Display("the train's controls let go", CreateDailies.ID + "/" + CreateHud.STATUS, stage -> {
                // Letting go puts a line above the hotbar; and nothing of the earlier displays is left on the player's game
                stage.onClient(() -> tidy(minecraft));
                stage.waitTicks(2);
            }));
    }

    /**
     * The toolbox's wheel as Create's key handler opens it with one toolbox in reach, with the pointer held on its top
     * slot (the wrench) and nothing chosen when it closes: the test closes it with its own close, where Create's wheel
     * would take the item out of the toolbox
     */
    static final class ToolboxWheelOnTop extends RadialToolboxMenu {
        ToolboxWheelOnTop(List<ToolboxBlockEntity> toolboxes) {
            super(toolboxes, State.SELECT_ITEM, toolboxes.get(0));
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            // Slot 1, straight up from the middle, where Create draws its first slot turned not at all
            super.render(graphics, this.getMinecraft().getWindow().getGuiScaledWidth() / 2, this.getMinecraft().getWindow().getGuiScaledHeight() / 2 - 40,
                partialTicks);
        }

        @Override
        public void removed() {
        }
    }

    /**
     * A train of one carriage on a line of track of its own, assembled at its station before recording as a player
     * assembles one (two bogeys under railway casing, glued, with controls on top), for the bar shown while it is driven
     */
    private static void stageTrain(Stage stage) throws Exception {
        stage.command("fill -6 -60 14 16 -60 14 create:track[shape=xo]");
        stage.waitTicks(20);
        // The station looks at the track beside it, the train's body lying east of it
        stage.command("setblock 0 -60 15 create:track_station{TargetTrack:[I;0,0,-1],TargetDirection:1b}");
        stage.waitTicks(40);
        stage.command("setblock 1 -59 14 create:small_bogey[axis=x]");
        stage.command("setblock 5 -59 14 create:small_bogey[axis=x]");
        stage.command("fill 1 -58 14 5 -58 14 create:railway_casing");
        stage.command("setblock 1 -57 14 create:controls[facing=east]");
        stage.command("create glue 1 -59 14 5 -57 14");
        stage.waitTicks(5);
        stage.onServer(player -> {
            if (player.level().getBlockEntity(TRAIN_STATION) instanceof StationBlockEntity station) {
                station.enterAssemblyMode(null);
            }
        });
        stage.waitTicks(10);
        stage.onServer(player -> {
            if (player.level().getBlockEntity(TRAIN_STATION) instanceof StationBlockEntity station) {
                station.assemble(player.getUUID());
            }
        });
        stage.waitTicks(20);
        stage.onServer(player -> {
            if (player.level().getBlockEntity(TRAIN_STATION) instanceof StationBlockEntity station) {
                station.exitAssemblyMode();
            }
        });
        stage.waitTicks(20);
    }

    /** Puts away what an earlier display left on the player's game alone: a train's controls taken, a toolbox's marks */
    private static void tidy(Minecraft minecraft) {
        if (ControlsHandler.getContraption() != null) {
            ControlsHandler.stopControlling();
        }
        minecraft.player.getPersistentData().remove("CreateToolboxData");
    }

    /** Uses a block with an empty hand, as the player does, which has the server open its window */
    private static void useBlock(Stage stage, BlockPos pos) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        stage.onClient(() -> minecraft.gameMode.useItemOn(minecraft.player, InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0.5, 0), Direction.UP, pos, false)));
        stage.waitTicks(3);
    }

}
