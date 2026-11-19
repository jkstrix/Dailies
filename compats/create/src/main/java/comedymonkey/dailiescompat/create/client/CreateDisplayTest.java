package comedymonkey.dailiescompat.create.client;

import com.simibubi.create.content.equipment.clipboard.ClipboardScreen;
import com.simibubi.create.content.equipment.symmetryWand.SymmetryWandScreen;
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
import comedymonkey.dailies.api.compat.DisplayTest;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * The Create compat's display test, run by Dailies' scripted run (its create group): a creative motor with a shaft on
 * it, a sequenced gearshift, a fluid tank, a display link, a train station, a schematic table, a schematicannon and a
 * glued area beside the player. While recording, the motor's value settings board is opened and its speed scrolled,
 * the motor's value box is aimed at, the goggles are put on and a fluid tank looked at, the gearshift's screen is
 * opened, a clipboard is written in, the display link's and the station's assembly screens and the wand of symmetry's
 * are opened, super glue is held by the glued area, a status line is put up, a linked controller starts binding, a
 * schematic is held, the schematic table and the schematicannon are used, and a schematic is named. Dailies then checks
 * each is drawn riding along in first person, and nothing in third person or flying free.
 */
final class CreateDisplayTest implements DisplayTest {

    private static final BlockPos MOTOR = new BlockPos(2, -60, 0);
    private static final BlockPos GEARSHIFT = new BlockPos(-2, -60, 0);
    private static final BlockPos DISPLAY_LINK = new BlockPos(4, -60, 3);
    private static final BlockPos STATION = new BlockPos(-4, -60, 3);
    private static final BlockPos TABLE = new BlockPos(6, -60, -3);
    private static final BlockPos CANNON = new BlockPos(-6, -60, -3);
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
            }));
    }

    /** Uses a block with an empty hand, as the player does, which has the server open its window */
    private static void useBlock(Stage stage, BlockPos pos) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        stage.onClient(() -> minecraft.gameMode.useItemOn(minecraft.player, InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0.5, 0), Direction.UP, pos, false)));
        stage.waitTicks(3);
    }

}
