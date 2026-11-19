package comedymonkey.dailiescompat.create.client;

import com.simibubi.create.content.equipment.clipboard.ClipboardScreen;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlockEntity;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftScreen;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsScreen;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
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
 * it and a sequenced gearshift beside the player. While recording, the motor's value settings board is opened and its
 * speed scrolled, the motor's value box is aimed at, the goggles are put on and the shaft looked at, the gearshift's
 * screen is opened, and a clipboard is written in. Dailies then checks each is drawn riding along in first person, and
 * nothing in third person or flying free.
 */
final class CreateDisplayTest implements DisplayTest {

    private static final BlockPos MOTOR = new BlockPos(2, -60, 0);
    private static final BlockPos GEARSHIFT = new BlockPos(-2, -60, 0);
    private static final String TYPED = "Speed: 64";

    @Override
    public void stage(Stage stage) throws Exception {
        // A motor facing north drives the shaft north of it; its value box is on its top, which faces no shaft
        stage.command("setblock 2 -60 0 create:creative_motor[facing=north]");
        stage.command("setblock 2 -60 -1 create:shaft[axis=z]");
        stage.command("setblock -2 -60 0 create:sequenced_gearshift");
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
            new Display("the goggles on the shaft", CreateDailies.ID + "/" + Goggles.NAME, stage -> {
                stage.command("item replace entity @s armor.head with create:goggles");
                stage.command("tp @s 2.5 -60 -3.5 facing 2.5 -59.5 -0.5");
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
            }));
    }

}
