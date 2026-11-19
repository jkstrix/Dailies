package comedymonkey.dailiescompat.createextras.client;

import com.hlysine.create_connected.content.sequencedpulsegenerator.SequencedPulseGeneratorBlockEntity;
import com.hlysine.create_connected.content.sequencedpulsegenerator.SequencedPulseGeneratorScreen;
import com.maxenonyme.AbyssDimension.client.PDAManager;
import com.maxenonyme.createsubmarine.submarine.block.entity.CommandSubBlockEntity;
import com.maxenonyme.createsubmarine.submarine.client.CommandSubClientHandler;
import com.maxenonyme.createsubmarine.submarine.client.SonarScreen;
import com.mojang.logging.LogUtils;
import com.pedrorok.hypertube.core.connection.SimpleConnection;
import com.pedrorok.hypertube.registry.ModDataComponent;
import com.railwayteam.railways.content.bogey_menu.BogeyMenuScreen;
import com.railwayteam.railways.content.bogey_menu.handler.BogeyMenuEventsHandler;
import com.railwayteam.railways.content.cycle_menu.RadialTagCycleMenu;
import com.railwayteam.railways.content.cycle_menu.TagCycleHandlerClient;
import com.railwayteam.railways.registry.CRKeys;
import comedymonkey.dailies.api.compat.DisplayTest;
import fr.iglee42.createcasing.blockEntities.AutoClutchBlockEntity;
import fr.iglee42.createcasing.client.AutoClutchClient;
import fr.iglee42.createcasing.items.recaser.RadialRecaserMenu;
import fr.iglee42.createcasing.screen.AutoClutchScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * The display test of the compat for Create's other add-ons, run by Dailies' scripted run (its createextras group). The
 * stage, around the player at the middle of a flat world: Create Deep Seas' electrolyzer, boat engine and command sub
 * console, Create Connected's sequenced pulse generator, Create Encased's andesite automatic clutch and a Create:
 * Hypertubes tube. While recording, the player, in survival, opens the electrolyzer's window and the boat engine's (as
 * using them opens them, the engine's while sneaking), the sonar's screen on the console, and has the PDA's message slide
 * in and Create Deep Seas' warning put above the hotbar; opens the pulse generator's and the clutch's screens; holds a
 * recaser with casings in the inventory and opens its wheel; holds railway casing and opens the bogey menu, and holds
 * slashed locometal and opens the tag wheel, each by Steam 'n' Rails' own key handler; wears a jetpack; and holds a tube
 * being joined on from the placed one. Dailies then checks each is drawn riding along in first person, and nothing in
 * third person or flying free.
 * <p>
 * The mods' own conditions for drawing, met here: the windows are opened by the server, with nothing in the hand; the
 * sonar closes itself beyond eight blocks of its console, so the player stands beside it; the recaser's wheel opens only
 * with casings to offer, and the bogey menu and the tag wheel only while their items are held; the jetpack's controls show
 * while a jetpack is worn on the chest; the tube's outline shows while a tube that glints, joining on from a placed tube,
 * is held. Turned by yaw and pitch, never by {@code tp ... facing}; block entities are waited for after {@code setblock}.
 * Not in the test, as no simple stage shows them: the implosion (a sub imploding), a boat engine's throttle (held from its
 * helm seat), a console's widget under the pointer (aimed at its screen), the switch on the train's bar (a train driven
 * towards a switch), and Hypertubes' arrow and escape bar (riding a tube). Create: Tracks has nothing of its own to show
 * beyond its lines above the hotbar.
 */
final class CreateExtrasDisplayTest implements DisplayTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final BlockPos ELECTROLYZER = new BlockPos(3, -60, 0);
    private static final BlockPos ENGINE = new BlockPos(-3, -60, 0);
    private static final BlockPos CONSOLE = new BlockPos(0, -60, 3);
    private static final BlockPos PULSE = new BlockPos(0, -60, -3);
    private static final BlockPos CLUTCH = new BlockPos(3, -60, 3);
    private static final BlockPos TUBE = new BlockPos(-3, -60, -3);

    private final CreateExtrasDailies.Mods mods;

    CreateExtrasDisplayTest(CreateExtrasDailies.Mods mods) {
        this.mods = mods;
    }

    @Override
    public void stage(Stage stage) throws Exception {
        if (this.mods.deepSeas()) {
            stage.command("setblock 3 -60 0 create_submarine:electrolyzer");
            stage.command("setblock -3 -60 0 create_high_seas:boat_engine");
            stage.command("setblock 0 -60 3 create_submarine:command_sub");
        }
        if (this.mods.connected()) {
            stage.command("setblock 0 -60 -3 create_connected:sequenced_pulse_generator");
        }
        if (this.mods.encased()) {
            stage.command("setblock 3 -60 3 createcasing:andesite_automatic_clutch");
        }
        if (this.mods.hypertube()) {
            stage.command("setblock -3 -60 -3 create_hypertube:hypertube");
        }
        // Their block entities are made with the blocks, and set up on their first ticks
        stage.waitTicks(10);
    }

    /** Puts the player back as each display starts: nothing held or worn. The test closes a screen left open */
    private static void reset(Stage stage) throws Exception {
        stage.command("item replace entity @s weapon.mainhand with minecraft:air");
        stage.command("item replace entity @s armor.chest with minecraft:air");
        stage.waitTicks(2);
    }

    /** Stands the player at a place, turned by yaw and pitch, and lets the game catch up */
    private static void standAt(Stage stage, double x, double z, float yaw, float pitch) throws Exception {
        stage.command("tp @s " + x + " -60 " + z + " " + yaw + " " + pitch);
        stage.waitTicks(3);
    }

    /** Says so when the screen asked for did not open, so a display that shows nothing is plain in the log */
    private static void expectScreen(Class<?> type, String what) {
        if (!type.isInstance(Minecraft.getInstance().screen)) {
            LOGGER.warn("Dailies Create add-ons compat's display test: {} did not open", what);
        }
    }

    /** Uses a block with an empty hand on the server, as the player's click does, sneaking or not */
    private static void use(Stage stage, BlockPos pos, boolean sneaking) throws Exception {
        stage.onServer(player -> {
            BlockState block = player.serverLevel().getBlockState(pos);
            player.setShiftKeyDown(sneaking);
            try {
                block.useWithoutItem(player.serverLevel(), player, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
            } finally {
                player.setShiftKeyDown(false);
            }
        });
        stage.waitTicks(5);
    }

    @Override
    public List<Display> displays() {
        List<Display> displays = new ArrayList<>();
        String prefix = CreateExtrasDailies.ID + "/";
        // Each mod's displays in a class of its own, so a game without the mod never loads its classes
        if (this.mods.deepSeas()) {
            DeepSeas.add(displays, prefix);
        }
        if (this.mods.connected()) {
            Connected.add(displays, prefix);
        }
        if (this.mods.encased()) {
            Encased.add(displays, prefix);
        }
        if (this.mods.rails()) {
            Rails.add(displays, prefix);
        }
        if (this.mods.jetpack()) {
            displays.add(new Display("Create Jetpack's controls, a jetpack worn", prefix + JetpackHud.NAME, stage -> {
                reset(stage);
                stage.command("item replace entity @s armor.chest with create_jetpack:jetpack");
                stage.waitTicks(5);
            }));
        }
        if (this.mods.hypertube()) {
            Hypertube.add(displays, prefix);
        }
        return displays;
    }

    private static final class DeepSeas {
        static void add(List<Display> displays, String prefix) {
            Minecraft minecraft = Minecraft.getInstance();
            displays.add(new Display("Create Deep Seas' electrolyzer's window", "create_submarine:electrolyzer", stage -> {
                reset(stage);
                standAt(stage, 1.5, 0.5, -90.0F, 30.0F);
                use(stage, ELECTROLYZER, false);
            }));
            displays.add(new Display("High Seas' boat engine's window", "create_high_seas:boat_engine", stage -> {
                reset(stage);
                standAt(stage, -0.5, 0.5, 90.0F, 30.0F);
                // Used while sneaking it opens its window; otherwise it seats the player at its helm
                use(stage, ENGINE, true);
            }));
            displays.add(new Display("the sonar's screen on a command sub console", prefix + DeepSeasParts.SONAR, stage -> {
                reset(stage);
                standAt(stage, 0.5, 1.5, 0.0F, 30.0F);
                // As the console opens it from its sonar tab: closed again by the sonar beyond eight blocks of the console
                stage.onClient(() -> {
                    if (minecraft.level.getBlockEntity(CONSOLE) instanceof CommandSubBlockEntity console) {
                        minecraft.setScreen(new SonarScreen(console));
                    }
                });
                stage.waitTicks(3);
                stage.onClient(() -> expectScreen(SonarScreen.class, "the sonar's screen"));
            }));
            displays.add(new Display("the PDA's message sliding in", prefix + DeepSeasParts.PDA, stage -> {
                reset(stage);
                // As the Abyss's own scene starts it, with its own sound: typed out over four seconds, then held
                stage.onClient(() -> PDAManager.startDialogue("Dailies is listening on this channel.",
                    ResourceLocation.fromNamespaceAndPath("create_abyss", "leviathan_class_detected"), 4000, 200));
                stage.waitTicks(15);
            }));
            displays.add(new Display("Create Deep Seas' warning above the hotbar", prefix + StatusLine.NAME, stage -> {
                reset(stage);
                // As its key handler warns a player hitting a console without sneaking
                stage.onClient(() -> Reflect.callStatic(CommandSubClientHandler.class, "warnSneakToBreak", new Class<?>[]{Minecraft.class}, minecraft));
                stage.waitTicks(2);
            }));
        }
    }

    private static final class Connected {
        static void add(List<Display> displays, String prefix) {
            Minecraft minecraft = Minecraft.getInstance();
            displays.add(new Display("a sequenced pulse generator's screen", prefix + ConnectedScreens.PULSE, stage -> {
                reset(stage);
                standAt(stage, 0.5, -0.5, 180.0F, 30.0F);
                // As using the generator opens it
                stage.onClient(() -> {
                    if (minecraft.level.getBlockEntity(PULSE) instanceof SequencedPulseGeneratorBlockEntity generator) {
                        minecraft.setScreen(new SequencedPulseGeneratorScreen(generator));
                    }
                });
                stage.waitTicks(3);
                stage.onClient(() -> expectScreen(SequencedPulseGeneratorScreen.class, "the sequenced pulse generator's screen"));
            }));
        }
    }

    private static final class Encased {
        static void add(List<Display> displays, String prefix) {
            Minecraft minecraft = Minecraft.getInstance();
            displays.add(new Display("an automatic clutch's screen", prefix + EncasedScreens.CLUTCH, stage -> {
                reset(stage);
                standAt(stage, 1.5, 1.5, -45.0F, 30.0F);
                // As using the clutch opens it
                stage.onClient(() -> {
                    if (minecraft.level.getBlockEntity(CLUTCH) instanceof AutoClutchBlockEntity clutch) {
                        AutoClutchClient.openScreen(clutch, minecraft.player);
                    }
                });
                stage.waitTicks(3);
                stage.onClient(() -> expectScreen(AutoClutchScreen.class, "the automatic clutch's screen"));
            }));
            displays.add(new Display("the recaser's wheel", prefix + EncasedScreens.RECASER, stage -> {
                reset(stage);
                // The wheel offers the casings in the inventory, and opens only when there are some
                stage.command("item replace entity @s hotbar.1 with create:andesite_casing 16");
                stage.command("item replace entity @s hotbar.2 with create:brass_casing 16");
                stage.command("item replace entity @s weapon.mainhand with createcasing:recaser");
                stage.waitTicks(3);
                // As its key opens it for the recaser held
                stage.onClient(() -> RadialRecaserMenu.tryCreateFor(minecraft.player.getMainHandItem()).ifPresent(minecraft::setScreen));
                stage.waitTicks(3);
                stage.onClient(() -> expectScreen(RadialRecaserMenu.class, "the recaser's wheel"));
            }));
        }
    }

    private static final class Rails {
        static void add(List<Display> displays, String prefix) {
            displays.add(new Display("Steam 'n' Rails' bogey menu", prefix + RailsParts.BOGEYS, stage -> {
                reset(stage);
                stage.command("item replace entity @s weapon.mainhand with create:railway_casing");
                stage.waitTicks(3);
                // As its key handler opens it while railway casing is held
                stage.onClient(() -> BogeyMenuEventsHandler.onKeyInput(CRKeys.BOGEY_MENU.getBoundCode(), true));
                stage.waitTicks(3);
                stage.onClient(() -> expectScreen(BogeyMenuScreen.class, "the bogey menu"));
            }));
            displays.add(new Display("Steam 'n' Rails' tag wheel", prefix + RailsParts.TAG_WHEEL, stage -> {
                reset(stage);
                stage.command("item replace entity @s weapon.mainhand with railways:slashed_locometal");
                stage.waitTicks(3);
                // As its key handler opens it while an item of a cycled tag is held
                stage.onClient(() -> TagCycleHandlerClient.onKeyInput(CRKeys.CYCLE_MENU.getBoundCode(), true));
                stage.waitTicks(3);
                stage.onClient(() -> expectScreen(RadialTagCycleMenu.class, "the tag wheel"));
            }));
        }
    }

    private static final class Hypertube {
        static void add(List<Display> displays, String prefix) {
            displays.add(new Display("a tube's placement outline", prefix + HypertubeParts.OUTLINE, stage -> {
                reset(stage);
                standAt(stage, -1.5, -1.5, 135.0F, 30.0F);
                // A tube held as using it on the placed tube leaves it: joining on from it, glinting
                stage.onServer((ServerPlayer player) -> {
                    ItemStack tube = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("create_hypertube", "hypertube")), 16);
                    tube.set(ModDataComponent.TUBE_CONNECTING_FROM, new SimpleConnection(TUBE, Direction.NORTH, 0.0F));
                    tube.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
                    player.setItemInHand(InteractionHand.MAIN_HAND, tube);
                });
                stage.waitTicks(5);
            }));
        }
    }

}
