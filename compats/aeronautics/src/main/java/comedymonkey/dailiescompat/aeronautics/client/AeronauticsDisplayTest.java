package comedymonkey.dailiescompat.aeronautics.client;

import com.mojang.logging.LogUtils;
import comedymonkey.dailies.api.compat.DisplayTest;
import dev.simulated_team.simulated.content.blocks.altitude_sensor.AltitudeSensorBlockEntity;
import dev.simulated_team.simulated.content.blocks.altitude_sensor.AltitudeSensorScreen;
import dev.simulated_team.simulated.content.blocks.nameplate.NameplateBlockEntity;
import dev.simulated_team.simulated.content.blocks.nameplate.NameplateScreen;
import dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter.LinkedTypewriterBlock;
import dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter.LinkedTypewriterBlockEntity;
import dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter.LinkedTypewriterInteractionHandler;
import dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter.LinkedTypewriterItemBindHandler;
import dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter.screen.LinkedTypewriterScreen;
import dev.simulated_team.simulated.content.blocks.redstone.modulating_receiver.ModulatingLinkedReceiverBlockEntity;
import dev.simulated_team.simulated.content.blocks.redstone.modulating_receiver.ModulatingLinkedReceiverScreen;
import dev.simulated_team.simulated.index.SimClickInteractions;
import dev.simulated_team.simulated.util.hold_interaction.HoldInteractionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * The Create Aeronautics compat's display test, run by Dailies' scripted run (its aeronautics group). The stage, around
 * the player at the middle of a flat world: an altitude sensor, a modulating linked receiver, a nameplate, a linked
 * typewriter and a redstone link beside it, a physics assembler, a steering wheel and a throttle lever, and two coaster
 * anchorpoints in a line to the south. While recording, the player, in survival, opens the altitude sensor's, the
 * receiver's and the nameplate's screens (typing a name into the plate), opens the typewriter's window, its list of keys
 * and a key's entry, starts binding a typewriter held in the hand to the redstone link, holds the assembler's lever, the
 * steering wheel (wearing goggles, through which alone its readout shows) and the throttle lever, puts a tyre where it
 * cannot go for Offroad's line above the hotbar, holds a rivet for Simulated Coasters' row of hints, and holds coaster
 * track with the first anchorpoint picked while looking at the second, for its row of track. Dailies then checks each is
 * drawn riding along in first person, and nothing in third person or flying free.
 * <p>
 * The mods' own conditions for drawing, met here: each screen is opened as its block opens it (the typewriter's window by
 * the server, as using it while sneaking does); the levers' readouts show while their hold lasts, which ends out of the
 * player's reach, so the player stands beside each; the steering wheel's shows only through goggles; the binding box
 * shows while the typewriter is in the hand and the link it was used on is there; the rivet's hints show while one is
 * held and no screen is open; the row of track shows in survival only, while track with a first anchorpoint picked is
 * held and another anchorpoint looked at. Turned by yaw and pitch, never by {@code tp ... facing}; block entities are
 * waited for after {@code setblock}. Not in the test, as no simple stage shows them: the contraption diagram and the
 * physics staff's locks, which need a craft of Sable's assembled, and a coaster track's handles, which need a track built
 * between two anchorpoints and shaped with the wrench.
 */
final class AeronauticsDisplayTest implements DisplayTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final BlockPos ALTITUDE = new BlockPos(2, -60, 0);
    private static final BlockPos RECEIVER = new BlockPos(-2, -60, 0);
    private static final BlockPos NAMEPLATE = new BlockPos(0, -60, 2);
    private static final BlockPos TYPEWRITER = new BlockPos(0, -60, -2);
    private static final BlockPos LINK = new BlockPos(1, -60, -3);
    private static final BlockPos ASSEMBLER = new BlockPos(-4, -60, 4);
    private static final BlockPos WHEEL = new BlockPos(4, -60, -4);
    private static final BlockPos THROTTLE = new BlockPos(-4, -60, -4);
    /** Two coaster anchorpoints in a line to the south: the first picked, the second looked at */
    private static final BlockPos ANCHOR_FIRST = new BlockPos(0, -60, 7);
    private static final BlockPos ANCHOR_SECOND = new BlockPos(0, -60, 14);
    private static final String NAME = "Dailies";
    /** The key the typewriter's entry is opened for: A, on its keyboard's third row */
    private static final int KEY_A = 65;

    private final boolean simulated;
    private final boolean coasters;

    AeronauticsDisplayTest(boolean simulated, boolean coasters) {
        this.simulated = simulated;
        this.coasters = coasters;
    }

    @Override
    public void stage(Stage stage) throws Exception {
        if (this.simulated) {
            stage.command("setblock 2 -60 0 simulated:altitude_sensor");
            stage.command("setblock -2 -60 0 simulated:modulating_linked_receiver");
            stage.command("setblock 0 -60 2 simulated:white_nameplate");
            stage.command("setblock 0 -60 -2 simulated:linked_typewriter");
            stage.command("setblock 1 -60 -3 create:redstone_link");
            stage.command("setblock -4 -60 4 simulated:physics_assembler");
            stage.command("setblock 4 -60 -4 simulated:steering_wheel");
            stage.command("setblock -4 -60 -4 simulated:throttle_lever");
        }
        if (this.coasters) {
            stage.command("setblock 0 -60 7 simulatedcoasters:coaster_anchorpoint[facing=up]");
            stage.command("setblock 0 -60 14 simulatedcoasters:coaster_anchorpoint[facing=up]");
        }
        // Their block entities are made with the blocks, and set up on their first ticks
        stage.waitTicks(10);
    }

    /**
     * Puts the player back as each display starts: nothing held or worn, no lever or wheel held, no typewriter binding.
     * The test closes a screen left open, but a hold and a binding are not screens
     */
    private static void reset(Stage stage) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        stage.onClient(() -> {
            if (ModList.get().isLoaded("simulated")) {
                SimulatedReset.run();
            }
        });
        stage.command("item replace entity @s weapon.mainhand with minecraft:air");
        stage.command("item replace entity @s armor.head with minecraft:air");
        stage.waitTicks(2);
        if (minecraft.player == null) {
            LOGGER.warn("Dailies Create Aeronautics compat's display test: no player");
        }
    }

    /** Simulated's part of {@link #reset}, in a class of its own so a game without Simulated never loads it */
    private static final class SimulatedReset {
        static void run() {
            HoldInteractionManager.stop();
            if (LinkedTypewriterInteractionHandler.getMode() == LinkedTypewriterInteractionHandler.Mode.BINDING_FROM_ITEM) {
                LinkedTypewriterItemBindHandler.reset();
            }
        }
    }

    /** Stands the player at a place, turned by yaw and pitch, and lets the game catch up */
    private static void standAt(Stage stage, double x, double z, float yaw, float pitch) throws Exception {
        stage.command("tp @s " + x + " -60 " + z + " " + yaw + " " + pitch);
        stage.waitTicks(3);
    }

    /** The typewriter's window, as using it while sneaking opens it: the player becomes its user, and the server opens it */
    private static void openTypewriter(Stage stage) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        standAt(stage, 0.5, -0.5, 180.0F, 30.0F);
        stage.onClient(() -> LinkedTypewriterInteractionHandler.setMode(LinkedTypewriterInteractionHandler.Mode.SCREEN_BINDING));
        stage.onServer(player -> {
            if (player.serverLevel().getBlockEntity(TYPEWRITER) instanceof LinkedTypewriterBlockEntity typewriter
                && player.serverLevel().getBlockState(TYPEWRITER).getBlock() instanceof LinkedTypewriterBlock block) {
                typewriter.checkAndStartUsing(player.getUUID());
                Reflect.call(block, "displayScreen", new Class<?>[]{LinkedTypewriterBlockEntity.class, Player.class}, typewriter, player);
            }
        });
        stage.waitTicks(5);
        if (!(minecraft.screen instanceof LinkedTypewriterScreen)) {
            LOGGER.warn("Dailies Create Aeronautics compat's display test: the linked typewriter's window did not open");
        }
    }

    /** Holds a lever or wheel, as using it starts the hold, from beside it */
    private static void hold(Stage stage, BlockPos pos, Runnable start) throws Exception {
        standAt(stage, pos.getX() + 0.5, pos.getZ() + (pos.getZ() < 0 ? 1.5 : -0.5), pos.getZ() < 0 ? 180.0F : 0.0F, 45.0F);
        stage.onClient(start);
        stage.waitTicks(3);
    }

    @Override
    public List<Display> displays() {
        Minecraft minecraft = Minecraft.getInstance();
        String prefix = AeronauticsDailies.ID + "/";
        List<Display> displays = new ArrayList<>();
        if (this.simulated) {
            displays.add(new Display("the altitude sensor's screen", prefix + SimulatedScreens.ALTITUDE, stage -> {
                reset(stage);
                standAt(stage, 0.5, 0.5, -90.0F, 30.0F);
                stage.onClient(() -> {
                    if (minecraft.level.getBlockEntity(ALTITUDE) instanceof AltitudeSensorBlockEntity sensor) {
                        AltitudeSensorScreen.open(sensor);
                    }
                });
            }));
            displays.add(new Display("the modulating linked receiver's screen", prefix + SimulatedScreens.RECEIVER, stage -> {
                reset(stage);
                standAt(stage, 0.5, 0.5, 90.0F, 30.0F);
                stage.onClient(() -> {
                    if (minecraft.level.getBlockEntity(RECEIVER) instanceof ModulatingLinkedReceiverBlockEntity receiver) {
                        ModulatingLinkedReceiverScreen.open(receiver);
                    }
                });
            }));
            displays.add(new Display("a nameplate being named", prefix + SimulatedScreens.NAMEPLATE, stage -> {
                reset(stage);
                standAt(stage, 0.5, 0.5, 0.0F, 30.0F);
                // As the nameplate opens it when used: on the plate leading its row, while the player can reach it
                stage.onClient(() -> {
                    if (minecraft.level.getBlockEntity(NAMEPLATE) instanceof NameplateBlockEntity plate) {
                        NameplateScreen.setScreen(plate);
                    }
                });
                stage.waitTicks(3);
                for (char typed : NAME.toCharArray()) {
                    stage.onClient(() -> {
                        if (minecraft.screen instanceof NameplateScreen plate) {
                            plate.charTyped(typed, 0);
                        }
                    });
                    stage.waitTicks(1);
                }
            }));
            displays.add(new Display("the linked typewriter's window", TypewriterExtras.MENU, stage -> {
                reset(stage);
                openTypewriter(stage);
            }));
            displays.add(new Display("the linked typewriter's list of keys", TypewriterExtras.MENU, stage -> {
                reset(stage);
                openTypewriter(stage);
                // As its list button opens it, in place of the keyboard
                stage.onClient(() -> {
                    if (minecraft.screen instanceof LinkedTypewriterScreen typewriter) {
                        typewriter.switchScreen(true);
                    }
                });
                stage.waitTicks(2);
            }));
            displays.add(new Display("a linked typewriter key's entry", TypewriterExtras.MENU, stage -> {
                reset(stage);
                openTypewriter(stage);
                // As clicking the keyboard's A opens its entry: the keyboard greyed out, the entry over it
                stage.onClient(() -> {
                    if (minecraft.screen instanceof LinkedTypewriterScreen typewriter) {
                        Reflect.call(typewriter, "switchStates", new Class<?>[]{boolean.class}, false);
                        typewriter.modifier.startModifying(typewriter.getNewEntries().getEntry(KEY_A), entry -> {
                            Reflect.call(typewriter, "switchStates", new Class<?>[]{boolean.class}, true);
                            if (entry != null) {
                                typewriter.getNewEntries().setKey(entry.glfwKeyCode, entry);
                            }
                        }).keyCode(KEY_A);
                    }
                });
                stage.waitTicks(2);
            }));
            displays.add(new Display("a linked typewriter binding to a link", prefix + SimulatedHud.BINDING, stage -> {
                reset(stage);
                // As using a typewriter held in the hand on a redstone link starts it: it waits for a key while the
                // typewriter stays in the hand and the link is there
                stage.command("item replace entity @s weapon.mainhand with simulated:linked_typewriter");
                standAt(stage, 0.5, -0.5, 180.0F, 30.0F);
                stage.onClient(() -> {
                    LinkedTypewriterItemBindHandler.setClickedPos(LINK);
                    LinkedTypewriterInteractionHandler.setMode(LinkedTypewriterInteractionHandler.Mode.BINDING_FROM_ITEM);
                });
                stage.waitTicks(3);
            }));
            displays.add(new Display("the physics assembler's lever held", prefix + SimulatedHud.LEVER, stage -> {
                reset(stage);
                hold(stage, ASSEMBLER, () -> SimClickInteractions.PHYSICS_ASSEMBLER_MANAGER.startHold(minecraft.level, minecraft.player, ASSEMBLER));
            }));
            displays.add(new Display("the steering wheel held, through goggles", prefix + SimulatedHud.LEVER, stage -> {
                reset(stage);
                stage.command("item replace entity @s armor.head with create:goggles");
                hold(stage, WHEEL, () -> SimClickInteractions.STEERING_WHEEL_MANAGER.startHold(minecraft.level, minecraft.player, WHEEL));
            }));
            displays.add(new Display("the throttle lever held", prefix + SimulatedHud.LEVER, stage -> {
                reset(stage);
                hold(stage, THROTTLE, () -> SimClickInteractions.THROTTLE_LEVER_MANAGER.startHold(minecraft.level, minecraft.player, THROTTLE));
            }));
        }
        if (ModList.get().isLoaded("offroad")) {
            displays.add(new Display("Offroad's line above the hotbar", prefix + StatusLine.NAME, stage -> {
                reset(stage);
                standAt(stage, 0.5, 0.5, 0.0F, 60.0F);
                // A tyre used on the ground, as the player does: Offroad says on the player's own game where it must go
                stage.command("item replace entity @s weapon.mainhand with offroad:tire");
                stage.waitTicks(2);
                stage.onClient(() -> {
                    BlockPos ground = new BlockPos(0, -61, 1);
                    minecraft.player.getMainHandItem().getItem().useOn(new UseOnContext(minecraft.player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(ground).add(0, 0.5, 0), Direction.UP, ground, false)));
                });
                stage.waitTicks(2);
            }));
        }
        if (this.coasters) {
            displays.add(new Display("a rivet's hints above the hotbar", prefix + CoastersHud.HINTS, stage -> {
                reset(stage);
                stage.command("item replace entity @s weapon.mainhand with simulatedcoasters:rivet");
                stage.waitTicks(3);
            }));
            displays.add(new Display("coaster track's row above the hotbar", prefix + CoastersHud.TRACK_ROW, stage -> {
                reset(stage);
                // Track with the first anchorpoint picked, as using it on one picks it, and the second looked at: three
                // blocks north of it and twenty degrees down meets its top
                stage.command("item replace entity @s weapon.mainhand with simulatedcoasters:coaster_track[minecraft:custom_data={SimulatedCoastersAnchorFirst:"
                    + ANCHOR_FIRST.asLong() + "L}] 64");
                standAt(stage, 0.5, ANCHOR_SECOND.getZ() - 2.5, 0.0F, 20.0F);
                stage.waitTicks(5);
            }));
        }
        return displays;
    }

}
