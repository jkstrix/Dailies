package comedymonkey.dailiescompat.supplementaries.client;

import com.mojang.logging.LogUtils;
import comedymonkey.dailies.api.compat.DisplayTest;
import net.mehvahdjukaar.supplementaries.client.cannon.CannonController;
import net.mehvahdjukaar.supplementaries.client.hud.SelectableContainerItemHud;
import net.mehvahdjukaar.supplementaries.client.screens.BlackBoardScreen;
import net.mehvahdjukaar.supplementaries.client.screens.DoormatScreen;
import net.mehvahdjukaar.supplementaries.client.screens.SignPostScreen;
import net.mehvahdjukaar.supplementaries.client.screens.SpeakerBlockScreen;
import net.mehvahdjukaar.supplementaries.common.block.tiles.BlackboardBlockTile;
import net.mehvahdjukaar.supplementaries.common.block.tiles.CannonBlockTile;
import net.mehvahdjukaar.supplementaries.common.block.tiles.DoormatBlockTile;
import net.mehvahdjukaar.supplementaries.common.block.tiles.SignPostBlockTile;
import net.mehvahdjukaar.supplementaries.common.block.tiles.SpeakerBlockTile;
import net.mehvahdjukaar.supplementaries.common.entities.data.SlimedData;
import net.mehvahdjukaar.supplementaries.common.utils.SlotReference;
import net.mehvahdjukaar.supplementaries.reg.ModData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * The Supplementaries compat's display test, run by Dailies' scripted run (its supplementaries group). The stage, around
 * the player at the middle of a flat world: a doormat, an oak fence for a sign post, a blackboard and a speaker block; a
 * present, a trapped present, a notice board, a pulley, a sack and a cannon; a red merchant standing still; a stone
 * target eleven blocks to the south for a slingshot; and, with Amendments, a lectern holding a book and quill. While
 * recording, the player, in survival, writes on the doormat, puts a sign post on the fence and writes on it, opens the
 * blackboard and draws on it, sets the speaker's message; opens the present's window and types who it is for, the
 * trapped present's, the notice board's, the pulley's, the sack's and the cannon's (typing a pitch), and trades with the red
 * merchant; is hit by slime, holds a quiver and picks from it, aims the cannon (its controls, and its keys above the
 * hotbar) and holds a slingshot with Stasis looking at the far target; and writes in the lectern's book. Dailies then
 * checks each is drawn riding along in first person, and nothing in third person or flying free.
 * <p>
 * The mods' own conditions for drawing, met here: the server windows are opened as the player opens them, by using the
 * block (or the merchant) from beside it with an empty hand, facing the notice board's front; the screens only the
 * player's game knows are opened as their blocks open them on the player's game; the slime is put on the player by the
 * server, as a slimeball does, and shows while its ticks last; the quiver's row shows while it is being picked from and
 * the quiver is in the hand; the cannon's controls show while it is aimed, which the player starts beside it; the far
 * outline needs a slingshot with Stasis in the hand and a block past the player's reach; the lectern opens Amendments'
 * window for a book and quill. Turned by yaw and pitch, never by {@code tp ... facing}; block entities are waited for
 * after {@code setblock}. Not in the test: a cannon's trajectory, which needs the server to have worked out a loaded
 * cannon's shot and the aim to meet the ground, and the barrel's view and a cannon boat's charge, which need the player
 * loaded in a cannon or riding a boat.
 */
final class SuppDisplayTest implements DisplayTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final BlockPos DOORMAT = new BlockPos(3, -60, 0);
    private static final BlockPos FENCE = new BlockPos(3, -60, 3);
    private static final BlockPos BLACKBOARD = new BlockPos(-3, -60, 0);
    private static final BlockPos SPEAKER = new BlockPos(-3, -60, 3);
    private static final BlockPos PRESENT = new BlockPos(3, -60, -3);
    private static final BlockPos TRAPPED_PRESENT = new BlockPos(-3, -60, -3);
    private static final BlockPos NOTICE_BOARD = new BlockPos(6, -60, 0);
    private static final BlockPos PULLEY = new BlockPos(-6, -60, 0);
    private static final BlockPos SACK = new BlockPos(-6, -60, -3);
    private static final BlockPos CANNON = new BlockPos(6, -60, -3);
    private static final BlockPos LECTERN = new BlockPos(0, -60, -6);
    /** The merchant's place, and the slingshot's far target: past the player's reach, within the slingshot's forty blocks */
    private static final Vec3 MERCHANT = new Vec3(-6.5, -60, 6.5);
    private static final BlockPos TARGET = new BlockPos(0, -59, 12);
    private static final String WORDS = "Dailies";

    private final boolean supplementaries;
    private final boolean amendments;

    SuppDisplayTest(boolean supplementaries, boolean amendments) {
        this.supplementaries = supplementaries;
        this.amendments = amendments;
    }

    @Override
    public void stage(Stage stage) throws Exception {
        if (this.supplementaries) {
            stage.command("setblock 3 -60 0 supplementaries:doormat");
            stage.command("setblock 3 -60 3 minecraft:oak_fence");
            stage.command("setblock -3 -60 0 supplementaries:blackboard[facing=east]");
            stage.command("setblock -3 -60 3 supplementaries:speaker_block");
            stage.command("setblock 3 -60 -3 supplementaries:present");
            stage.command("setblock -3 -60 -3 supplementaries:trapped_present");
            stage.command("setblock 6 -60 0 supplementaries:notice_board[facing=west]");
            stage.command("setblock -6 -60 0 supplementaries:pulley_block");
            stage.command("setblock -6 -60 -3 supplementaries:sack");
            stage.command("setblock 6 -60 -3 supplementaries:cannon");
            stage.command("setblock 0 -60 12 minecraft:stone");
            stage.command("setblock 0 -59 12 minecraft:stone");
            stage.command("summon supplementaries:red_merchant " + MERCHANT.x + " " + MERCHANT.y + " " + MERCHANT.z + " {NoAI:1b,Silent:1b,PersistenceRequired:1b}");
        }
        if (this.amendments) {
            stage.command("setblock 0 -60 -6 minecraft:lectern[facing=south,has_book=true]{Book:{id:\"minecraft:writable_book\",count:1}}");
        }
        // Their block entities are made with the blocks, and set up on their first ticks
        stage.waitTicks(10);
    }

    /** Puts the player back as each display starts: nothing held, no cannon aimed, no row open, no slime */
    private void reset(Stage stage) throws Exception {
        if (this.supplementaries) {
            stage.onClient(Supp::reset);
            stage.onServer(player -> Supp.slime(player, 0));
        }
        stage.command("item replace entity @s weapon.mainhand with minecraft:air");
        stage.waitTicks(2);
    }

    /** Stands the player at a place, turned by yaw and pitch, and lets the game catch up */
    private static void standAt(Stage stage, double x, double z, float yaw, float pitch) throws Exception {
        stage.command("tp @s " + x + " -60 " + z + " " + yaw + " " + pitch);
        stage.waitTicks(3);
    }

    /** Uses a block with what is in the hand, on one of its faces, as the player's right click does */
    private static void use(Stage stage, BlockPos pos, Direction face) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        stage.onClient(() -> minecraft.gameMode.useItemOn(minecraft.player, InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5), face, pos, false)));
        stage.waitTicks(5);
    }

    /** A server window opened by using its block from beside it, logged when it did not open */
    private static void openWindow(Stage stage, BlockPos pos, Direction face, String what) throws Exception {
        standAt(stage, pos.getX() + 0.5 + face.getStepX() * 1.5, pos.getZ() + 0.5 + face.getStepZ() * 1.5, yawTowards(face.getOpposite()), 30.0F);
        use(stage, pos, face);
        if (Minecraft.getInstance().screen == null) {
            LOGGER.warn("Dailies Supplementaries compat's display test: {} did not open", what);
        }
    }

    /** The yaw that faces this way */
    private static float yawTowards(Direction facing) {
        return switch (facing) {
            case SOUTH -> 0.0F;
            case WEST -> 90.0F;
            case NORTH -> 180.0F;
            default -> -90.0F;
        };
    }

    /** Types into the screen open, a letter a tick, as the player types */
    private static void type(Stage stage, String words) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        for (char typed : words.toCharArray()) {
            stage.onClient(() -> {
                Screen screen = minecraft.screen;
                if (screen != null) {
                    screen.charTyped(typed, 0);
                }
            });
            stage.waitTicks(1);
        }
    }

    @Override
    public List<Display> displays() {
        String prefix = SuppDailies.ID + "/";
        List<Display> displays = new ArrayList<>();
        if (this.supplementaries) {
            displays.add(new Display("a doormat being written on", prefix + SuppScreens.DOORMAT, stage -> {
                reset(stage);
                standAt(stage, 1.5, 0.5, -90.0F, 45.0F);
                // As the doormat opens it on the player's game when it is used
                stage.onClient(() -> Supp.openDoormat(DOORMAT));
                stage.waitTicks(3);
                type(stage, WORDS);
            }));
            displays.add(new Display("a sign post being written on", prefix + SuppScreens.SIGN_POST, stage -> {
                reset(stage);
                // A sign post put on the fence as the player puts one, on its upper half
                stage.command("item replace entity @s weapon.mainhand with supplementaries:way_sign_oak");
                standAt(stage, 1.5, 3.5, -90.0F, 20.0F);
                Minecraft minecraft = Minecraft.getInstance();
                stage.onClient(() -> minecraft.gameMode.useItemOn(minecraft.player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(new Vec3(FENCE.getX(), FENCE.getY() + 0.75, FENCE.getZ() + 0.5), Direction.WEST, FENCE, false)));
                stage.waitTicks(5);
                stage.onClient(() -> Supp.openSignPost(FENCE));
                stage.waitTicks(3);
                type(stage, WORDS);
            }));
            displays.add(new Display("a blackboard being drawn on", prefix + SuppScreens.BLACKBOARD, stage -> {
                reset(stage);
                standAt(stage, -1.5, 0.5, 90.0F, 30.0F);
                stage.onClient(() -> Supp.openBlackboard(BLACKBOARD));
                stage.waitTicks(3);
                // A short stroke in white, as dragging over the board's pixels draws it
                stage.onClient(Supp::draw);
            }));
            displays.add(new Display("a speaker block being set", prefix + SuppScreens.SPEAKER, stage -> {
                reset(stage);
                standAt(stage, -1.5, 3.5, 90.0F, 30.0F);
                stage.onClient(() -> Supp.openSpeaker(SPEAKER));
                stage.waitTicks(3);
                type(stage, WORDS);
            }));
            displays.add(new Display("a present's window, its recipient typed", SuppWindows.PRESENT_MENU, stage -> {
                reset(stage);
                openWindow(stage, PRESENT, Direction.WEST, "the present's window");
                type(stage, WORDS);
            }));
            displays.add(new Display("a trapped present's window", SuppWindows.TRAPPED_PRESENT_MENU, stage -> {
                reset(stage);
                openWindow(stage, TRAPPED_PRESENT, Direction.EAST, "the trapped present's window");
            }));
            displays.add(new Display("the notice board's window", "supplementaries:notice_board", stage -> {
                reset(stage);
                openWindow(stage, NOTICE_BOARD, Direction.WEST, "the notice board's window");
            }));
            displays.add(new Display("the pulley's window", "supplementaries:pulley_block", stage -> {
                reset(stage);
                openWindow(stage, PULLEY, Direction.EAST, "the pulley's window");
            }));
            displays.add(new Display("the sack's window", "supplementaries:sack", stage -> {
                reset(stage);
                openWindow(stage, SACK, Direction.EAST, "the sack's window");
            }));
            displays.add(new Display("the cannon's window, a pitch typed", SuppWindows.CANNON_MENU, stage -> {
                reset(stage);
                openWindow(stage, CANNON, Direction.WEST, "the cannon's window");
                stage.onClient(Supp::typePitch);
                stage.waitTicks(2);
            }));
            displays.add(new Display("the red merchant's trades", SuppWindows.MERCHANT_MENU, stage -> {
                reset(stage);
                standAt(stage, MERCHANT.x + 1.5, MERCHANT.z, 90.0F, 10.0F);
                Minecraft minecraft = Minecraft.getInstance();
                stage.onClient(() -> {
                    Entity merchant = minecraft.level.getEntities((Entity) null, new AABB(MERCHANT, MERCHANT).inflate(2.0),
                        entity -> entity.getType().builtInRegistryHolder().key().location().toString().equals("supplementaries:red_merchant")).stream().findFirst().orElse(null);
                    if (merchant != null) {
                        minecraft.gameMode.interact(minecraft.player, merchant, InteractionHand.MAIN_HAND);
                    }
                });
                stage.waitTicks(5);
                if (minecraft.screen == null) {
                    LOGGER.warn("Dailies Supplementaries compat's display test: the red merchant's trades did not open");
                }
            }));
            displays.add(new Display("slime over the screen", prefix + SuppHud.SLIMED, stage -> {
                reset(stage);
                // As a slimeball's hit puts it on the player, from the server
                stage.onServer(player -> Supp.slime(player, 200));
                stage.waitTicks(3);
            }));
            displays.add(new Display("a quiver's row, picked from", prefix + SuppHud.SELECTABLE, stage -> {
                reset(stage);
                stage.command("item replace entity @s weapon.mainhand with supplementaries:quiver");
                // Arrows in it, where this game takes the quiver's contents written so: the row shows either way
                stage.command("item replace entity @s weapon.mainhand with supplementaries:quiver[supplementaries:quiver_content={items:[{id:\"minecraft:arrow\",count:16},"
                    + "{id:\"minecraft:spectral_arrow\",count:8}],selected_slot:1}]");
                stage.waitTicks(2);
                // As using the quiver opens its row on the player's game
                stage.onClient(Supp::openQuiver);
                stage.waitTicks(3);
            }));
            displays.add(new Display("the cannon aimed", prefix + SuppHud.CANNON, stage -> {
                reset(stage);
                standAt(stage, CANNON.getX() - 1.0, CANNON.getZ() + 0.5, -90.0F, 20.0F);
                // As using the cannon while sneaking starts aiming it on the player's game
                stage.onClient(() -> Supp.aim(CANNON));
                stage.waitTicks(3);
            }));
            displays.add(new Display("the cannon's keys above the hotbar", prefix + StatusLine.NAME, stage -> {
                reset(stage);
                standAt(stage, CANNON.getX() - 1.0, CANNON.getZ() + 0.5, -90.0F, 20.0F);
                stage.onClient(() -> Supp.aim(CANNON));
                stage.waitTicks(3);
            }));
            displays.add(new Display("a slingshot with Stasis pointed at a far block", prefix + InWorld.OUTLINE, stage -> {
                reset(stage);
                stage.command("item replace entity @s weapon.mainhand with supplementaries:slingshot[minecraft:enchantments={levels:{\"supplementaries:stasis\":1}}]");
                // Straight along the ground to the south, where the target's upper block meets the player's eyes
                standAt(stage, TARGET.getX() + 0.5, 0.5, 0.0F, 0.0F);
                stage.waitTicks(3);
            }));
        }
        if (this.amendments) {
            displays.add(new Display("a book and quill written in on a lectern", prefix + LecternBook.NAME, stage -> {
                reset(stage);
                openWindow(stage, LECTERN, Direction.SOUTH, "the lectern's book and quill");
                type(stage, WORDS);
            }));
        }
        return displays;
    }

    /** Supplementaries' part, in a class of its own so a game with Amendments alone never loads it */
    private static final class Supp {

        static void reset() {
            if (CannonController.isActive()) {
                CannonController.stopControlling();
            }
            SelectableContainerItemHud hud = SelectableContainerItemHud.getInstance();
            if (hud.isActive()) {
                Reflect.call(hud, "closeHud", new Class<?>[0]);
            }
        }

        static void slime(net.minecraft.server.level.ServerPlayer player, int ticks) {
            ((SlimedData) ModData.SLIMED_DATA.getOrCreate(player)).setSlimedTicks(player, ticks);
        }

        static void openDoormat(BlockPos pos) {
            if (Minecraft.getInstance().level.getBlockEntity(pos) instanceof DoormatBlockTile doormat) {
                DoormatScreen.open(doormat);
            } else {
                LOGGER.warn("Dailies Supplementaries compat's display test: no doormat at {}", pos);
            }
        }

        static void openSignPost(BlockPos pos) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.screen instanceof SignPostScreen) {
                return;
            }
            if (minecraft.level.getBlockEntity(pos) instanceof SignPostBlockTile post) {
                SignPostScreen.open(post, 0);
            } else {
                LOGGER.warn("Dailies Supplementaries compat's display test: no sign post was put on the fence at {}", pos);
            }
        }

        static void openBlackboard(BlockPos pos) {
            if (Minecraft.getInstance().level.getBlockEntity(pos) instanceof BlackboardBlockTile board) {
                BlackBoardScreen.open(board);
            } else {
                LOGGER.warn("Dailies Supplementaries compat's display test: no blackboard at {}", pos);
            }
        }

        static void draw() {
            if (Minecraft.getInstance().screen instanceof BlackBoardScreen board) {
                for (int u = 4; u < 12; u++) {
                    board.updateBlackboard(u, 8, (byte) 1);
                }
            }
        }

        static void openSpeaker(BlockPos pos) {
            if (Minecraft.getInstance().level.getBlockEntity(pos) instanceof SpeakerBlockTile speaker) {
                SpeakerBlockScreen.open(speaker);
            } else {
                LOGGER.warn("Dailies Supplementaries compat's display test: no speaker block at {}", pos);
            }
        }

        static void typePitch() {
            Screen screen = Minecraft.getInstance().screen;
            if (screen != null && Reflect.get(screen, "pitchSelector") instanceof net.minecraft.client.gui.components.EditBox pitch) {
                pitch.setValue("30");
            }
        }

        static void openQuiver() {
            Minecraft minecraft = Minecraft.getInstance();
            SelectableContainerItemHud.getInstance().setUsingItem(SlotReference.hand(InteractionHand.MAIN_HAND), minecraft.player);
        }

        static void aim(BlockPos pos) {
            if (Minecraft.getInstance().level.getBlockEntity(pos) instanceof CannonBlockTile cannon) {
                CannonController.startControlling(cannon);
            } else {
                LOGGER.warn("Dailies Supplementaries compat's display test: no cannon at {}", pos);
            }
        }
    }

}
