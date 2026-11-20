package comedymonkey.dailiescompat.supplementaries.client;

import com.mojang.authlib.GameProfile;
import comedymonkey.dailiescompat.supplementaries.state.SuppStates;
import net.mehvahdjukaar.amendments.client.gui.LecternBookEditScreen;
import net.mehvahdjukaar.amendments.common.LecternEditMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.TextFieldHelper;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Amendments' book and quill written in on a lectern: a window the server opens (its menu, {@code amendments:lectern_edit},
 * holds the lectern's book and its page), shown on Amendments' own screen, which is the game's book and quill screen
 * with an ink and a quill to colour and style the writing. Dailies' own windows cannot draw it (it is not a window with
 * slots), and the pages as typed reach the server only as it is closed, so the compat claims the window whole: its pages
 * as typed, the page open, the cursor, the signing page and its title, and the ink and quill picked. Drawn in playback on
 * Amendments' screen over a lectern of the compat's own, in no world, holding a book of those pages; it takes no input
 * and is never closed, which is where it sends the pages.
 */
final class LecternBook {

    static final String NAME = "lectern_book";
    static final String MENU = "amendments:lectern_edit";

    private LecternBook() {
    }

    static byte @Nullable [] capture(Screen screen) {
        if (!(screen instanceof LecternBookEditScreen book)) {
            return null;
        }
        List<String> pages = Reflect.get(book, "pages");
        TextFieldHelper field = Reflect.get(book, "pageEdit");
        Player owner = Reflect.get(book, "owner");
        Object ink = Reflect.get(book, "ink");
        Object quill = Reflect.get(book, "quill");
        return SuppStates.lecternBook(new SuppStates.LecternBook(new ArrayList<>(pages), Reflect.get(book, "currentPage"), field == null ? 0 : field.getCursorPos(),
            field == null ? 0 : field.getSelectionPos(), Reflect.get(book, "isSigning"), Reflect.get(book, "title"),
            owner == null ? "" : owner.getGameProfile().getName(), ink == null ? 0 : Reflect.get(ink, "type"), quill == null ? 0 : Reflect.get(quill, "type")));
    }

    static @Nullable Screen build(byte[] state) {
        SuppStates.LecternBook recorded = SuppStates.readLecternBook(state);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return null;
        }
        List<String> pages = recorded.pages().isEmpty() ? List.of("") : recorded.pages();
        ItemStack held = new ItemStack(Items.WRITABLE_BOOK);
        held.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(pages.stream().map(Filterable::passThrough).toList()));
        LecternBlockEntity lectern = new LecternBlockEntity(BlockPos.ZERO, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.HAS_BOOK, true));
        lectern.setBook(held);
        LecternEditMenu menu = new LecternEditMenu(0, lectern, new SimpleContainerData(1));
        // The writer, by name only, for the signing page's "by" line: a stand-in never in the world
        String name = recorded.owner().isEmpty() ? "Player" : recorded.owner();
        RemotePlayer owner = new RemotePlayer(minecraft.level, new GameProfile(UUID.nameUUIDFromBytes(("Dailies writer " + name).getBytes(StandardCharsets.UTF_8)), name));
        LecternBookEditScreen screen = new LecternBookEditScreen(menu, owner.getInventory(), Component.empty());
        return new Shown<>(screen, laidOut -> {
            // Laying it out fills its pages from the lectern's book; then the rest as the recorder's screen held it
            List<String> shown = Reflect.get(laidOut, "pages");
            shown.clear();
            shown.addAll(pages);
            Reflect.set(laidOut, "currentPage", Math.clamp(recorded.page(), 0, pages.size() - 1));
            Reflect.set(laidOut, "isSigning", recorded.signing());
            Reflect.set(laidOut, "title", recorded.title());
            setType(Reflect.get(laidOut, "ink"), recorded.ink());
            setType(Reflect.get(laidOut, "quill"), recorded.quill());
            Reflect.call(laidOut, "clearDisplayCache", new Class<?>[0]);
            Reflect.call(laidOut, "updateButtonVisibility", new Class<?>[0]);
            TextFieldHelper field = Reflect.get(laidOut, "pageEdit");
            if (field != null) {
                int length = pages.get(Math.clamp(recorded.page(), 0, pages.size() - 1)).length();
                field.setSelectionRange(Math.clamp(recorded.cursor(), 0, length), Math.clamp(recorded.selection(), 0, length));
            }
        });
    }

    /** The ink or quill picked, with the tooltip it shows for it */
    private static void setType(@Nullable Object button, int type) {
        if (button != null) {
            Reflect.set(button, "type", type);
            Reflect.call(button, "refreshTooltip", new Class<?>[0]);
        }
    }

}
