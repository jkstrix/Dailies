package comedymonkey.dailies.windows.vanilla;

import com.mojang.blaze3d.platform.InputConstants;
import comedymonkey.dailies.api.compat.DisplayTest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.inventory.LecternScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.LecternMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * The display test of Dailies' own compat for the game's books, lectern and signs, run by the windows group of the
 * scripted run (TEST-8) after the windows: a lectern holding a written book, a sign and a hanging sign beside the
 * player, then while recording a written book is read from the hand with two pages turned, the lectern's book is read
 * with a page turned, a book and quill is written in, and then written in and signed, and the sign and the hanging sign
 * are each edited. Every block is put down by the game's own code rather than by commands, so no block state is named.
 */
final class VanillaDisplayTest implements DisplayTest {

    private static final BlockPos LECTERN = new BlockPos(2, -60, 1);
    private static final BlockPos SIGN = new BlockPos(-2, -60, 2);
    private static final BlockPos HANGING_SIGN = new BlockPos(2, -59, -1);

    @Override
    public void stage(Stage stage) throws Exception {
        stage.onServer(player -> {
            ServerLevel level = player.level();
            level.setBlock(LECTERN, Blocks.LECTERN.defaultBlockState(), Block.UPDATE_ALL);
            // Facing the player, who stands to the south of it at z 0.5
            level.setBlock(SIGN, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 8), Block.UPDATE_ALL);
            // A hanging sign hangs from the block above it
            level.setBlock(HANGING_SIGN.above(), Blocks.SPRUCE_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(HANGING_SIGN, Blocks.SPRUCE_HANGING_SIGN.defaultBlockState(), Block.UPDATE_ALL);
        });
        // The block entities, on the server and then on the player's game, before the lectern is given its book
        stage.waitTicks(10);
        stage.onServer(player -> {
            ServerLevel level = player.level();
            LecternBlock.tryPlaceBook(null, level, LECTERN, level.getBlockState(LECTERN), book("A lectern's book", 3));
        });
        stage.waitTicks(10);
    }

    /** A written book of a few pages, its text resolved already as a book signed in the game is */
    private static ItemStack book(String title, int pages) {
        List<Filterable<Component>> text = new ArrayList<>();
        for (int i = 1; i <= pages; i++) {
            text.add(Filterable.passThrough(Component.literal(title + ", page " + i + ".\nRecorded by Dailies, and drawn again in playback.")));
        }
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough(title), "Dailies", 0, text, true));
        return book;
    }

    /** Opens a book and quill from the main hand, as using it does */
    private static void openBookAndQuill(Stage stage, Minecraft minecraft) throws Exception {
        stage.onServer(player -> player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WRITABLE_BOOK)));
        stage.waitTicks(5);
        stage.onClient(() -> minecraft.gameMode.useItem(minecraft.player, InteractionHand.MAIN_HAND));
        stage.waitTicks(2);
    }

    /** Types into the screen open, a character a tick, as a player types */
    private static void type(Stage stage, Minecraft minecraft, String text) throws Exception {
        for (char typed : text.toCharArray()) {
            stage.onClient(() -> {
                if (minecraft.gui.screen() != null) {
                    minecraft.gui.screen().charTyped(typed, 0);
                }
            });
            stage.waitTicks(1);
        }
    }

    /** Presses a key on the screen open */
    private static void press(Stage stage, Minecraft minecraft, int key) throws Exception {
        stage.onClient(() -> {
            if (minecraft.gui.screen() != null) {
                minecraft.gui.screen().keyPressed(key, 0, 0);
            }
        });
        stage.waitTicks(1);
    }

    /** Opens a sign's editing screen as placing a sign does */
    private static void editSign(Stage stage, BlockPos pos) throws Exception {
        stage.onServer(player -> {
            if (player.level().getBlockEntity(pos) instanceof SignBlockEntity sign && sign.getBlockState().getBlock() instanceof SignBlock block) {
                block.openTextEdit(player, sign, true);
            }
        });
        stage.waitTicks(5);
    }

    @Override
    public List<Display> displays() {
        Minecraft minecraft = Minecraft.getInstance();
        return List.of(
            new Display("a written book read, two pages turned", VanillaCompat.ID + "/book_read", stage -> {
                stage.onServer(player -> player.setItemInHand(InteractionHand.MAIN_HAND, book("A book in the hand", 4)));
                stage.waitTicks(5);
                stage.onClient(() -> minecraft.gameMode.useItem(minecraft.player, InteractionHand.MAIN_HAND));
                stage.waitTicks(10);
                for (int turn = 0; turn < 2; turn++) {
                    stage.onClient(() -> {
                        if (minecraft.gui.screen() instanceof BookViewScreen) {
                            minecraft.gui.screen().keyPressed(InputConstants.KEY_PAGEDOWN, 0, 0);
                        }
                    });
                    stage.waitTicks(10);
                }
            }),
            new Display("a book on a lectern, a page turned", VanillaCompat.ID + "/lectern", stage -> {
                stage.onServer(player -> {
                    if (player.level().getBlockEntity(LECTERN) instanceof MenuProvider lectern) {
                        player.openMenu(lectern);
                    }
                });
                stage.waitTicks(10);
                // As the page's arrow does: the lectern's page is the server's, and comes back to the screen
                stage.onClient(() -> {
                    if (minecraft.gui.screen() instanceof LecternScreen lectern) {
                        minecraft.gameMode.handleInventoryButtonClick(lectern.getMenu().containerId, LecternMenu.BUTTON_NEXT_PAGE);
                    }
                });
                stage.waitTicks(5);
            }),
            new Display("a book and quill written in", VanillaCompat.ID + "/book_write", stage -> {
                openBookAndQuill(stage, minecraft);
                type(stage, minecraft, "Dear diary,");
                press(stage, minecraft, InputConstants.KEY_RETURN);
                type(stage, minecraft, "today I recorded a book.");
                // The cursor back into the line, so it is drawn inside the text rather than at its end
                for (int i = 0; i < 6; i++) {
                    press(stage, minecraft, InputConstants.KEY_LEFT);
                }
            }),
            new Display("a book and quill being signed", VanillaCompat.ID + "/book_write", stage -> {
                openBookAndQuill(stage, minecraft);
                type(stage, minecraft, "To be signed.");
                // The Sign button, as clicking it does
                stage.onClient(() -> {
                    if (minecraft.gui.screen() instanceof BookEditScreen writing) {
                        for (GuiEventListener child : writing.children()) {
                            if (child instanceof Button button && button.getMessage().equals(Component.translatable("book.signButton"))) {
                                button.onPress();
                                break;
                            }
                        }
                    }
                });
                stage.waitTicks(2);
                type(stage, minecraft, "My Dailies");
            }),
            new Display("a sign edited", VanillaCompat.ID + "/sign_edit", stage -> {
                editSign(stage, SIGN);
                type(stage, minecraft, "Dailies");
                press(stage, minecraft, InputConstants.KEY_RETURN);
                type(stage, minecraft, "was here");
            }),
            new Display("a hanging sign edited", VanillaCompat.ID + "/hanging_sign_edit", stage -> {
                editSign(stage, HANGING_SIGN);
                type(stage, minecraft, "Shop");
                press(stage, minecraft, InputConstants.KEY_DOWN);
                type(stage, minecraft, "Open");
            }));
    }

}
