package comedymonkey.dailies.windows.vanilla;

import com.mojang.authlib.GameProfile;
import comedymonkey.dailies.api.compat.Claims;
import comedymonkey.dailies.api.compat.DailiesCompat;
import comedymonkey.dailies.api.compat.DailiesCompats;
import comedymonkey.dailies.api.compat.DisplayTest;
import comedymonkey.dailies.api.compat.ScreenPart;
import comedymonkey.dailies.mixin.access.BookEditScreenAccess;
import comedymonkey.dailies.mixin.access.BookViewScreenAccess;
import comedymonkey.dailies.mixin.access.SignEditScreenAccess;
import comedymonkey.dailies.windows.Compats;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.TextFieldHelper;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.inventory.HangingSignEditScreen;
import net.minecraft.client.gui.screens.inventory.LecternScreen;
import net.minecraft.client.gui.screens.inventory.SignEditScreen;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HangingSignBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * The game's own books, lectern and signs in recordings (specification REC-29, REC-33 to REC-35, PLAY-24, EXP-18): a
 * compat of Dailies' own, through the same API the mods use, so its states are written on change and in every
 * snapshot, and jumps, trims and combines carry them, as for every compat. Each is a play screen, opened by using
 * something in the world, and none of them is a window stage 1 can draw: a book read from the hand and a book and quill
 * have no menu at all, a sign is edited on a screen of the player's own, and the lectern's window is the server's but
 * its screen is a book, which keeps its pages and page to itself and is laid out as a book, not a window.
 * <ul>
 * <li>A written book read from the hand: which hand, the page open, and its pages, stored once in the recording.</li>
 * <li>A book on a lectern: claimed by its window's type, so stage 1 leaves it alone: the pages, stored once, the page
 * open, and whether the reader could take the book (the screen's buttons).</li>
 * <li>A book and quill written in or signed: the page being typed on, the cursor and selection, the signing screen with
 * its title, and the other pages, stored once; a few times a second while it changes.</li>
 * <li>A sign or hanging sign edited: which sign (its place, block, wood and side), the lines as typed, the line typed on
 * with its cursor, and its colour; a few times a second while it changes. Drawn on a copy of the sign, so the playback
 * world's own sign is never touched.</li>
 * </ul>
 * Each is drawn with the game's own screen, taking no input and sending nothing ({@link PlaybackScreens}). Only the
 * game's own screens of these classes are recorded: a mod's screen built on one of them is left out, as it was before.
 * The creative inventory stays out (the owner's choice).
 */
public final class VanillaCompat implements DailiesCompat {

    public static final String ID = "dailies:vanilla";
    /** Raise when a state in VanillaStates changes shape; the parts below must still read every version before it */
    static final int VERSION = 1;
    /** Typing is written at most five times a second */
    private static final int TYPING_TICKS = 4;
    /** The book screens' width, from which their window's corner is found */
    private static final int BOOK_WIDTH = 192;

    private VanillaCompat() {
    }

    /** Registers the compat with Dailies' own compats, on the player's game, as Dailies starts */
    public static void register() {
        Compats.registerOwn(new VanillaCompat());
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public void claim(Claims claims) {
        claims.screen("book_read", BookViewScreen.class, new BookRead());
        claims.menu("lectern", "minecraft:lectern", new Lectern());
        claims.screen("book_write", BookEditScreen.class, new BookWrite());
        claims.screen("sign_edit", SignEditScreen.class, new SignEdit(SignEditScreen.class));
        claims.screen("hanging_sign_edit", HangingSignEditScreen.class, new SignEdit(HangingSignEditScreen.class));
    }

    @Override
    public DisplayTest displayTest() {
        return new VanillaDisplayTest();
    }

    // --- Pages, stored once ---

    private static @Nullable HolderLookup.Provider registries() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level == null ? null : minecraft.level.registryAccess();
    }

    /**
     * Keeps the bytes of the pages last stored, so a book held open is not encoded again every tick, only when its pages
     * differ from those last stored. The bytes are still handed over every tick: a recording started while the book is
     * open must get them too, and the same array handed over again is not hashed again.
     */
    private static final class Stored {
        private @Nullable List<?> source = null;
        private byte @Nullable [] bytes = null;

        <T> String handle(List<T> pages, Function<List<T>, List<String>> asText) {
            if (this.bytes == null || !pages.equals(this.source)) {
                this.source = List.copyOf(pages);
                this.bytes = VanillaStates.pages(asText.apply(pages));
            }
            return DailiesCompats.storeOnce(this.bytes);
        }
    }

    /** A book's pages as the game's JSON for each, to store once */
    private static List<String> json(List<Component> pages, HolderLookup.Provider registries) {
        List<String> json = new ArrayList<>(pages.size());
        for (Component page : pages) {
            json.add(Component.Serializer.toJson(page, registries));
        }
        return json;
    }

    /** A book's pages read back from what was stored once by their handle; null when the recording has them not */
    private static BookViewScreen.@Nullable BookAccess book(String handle) {
        byte[] stored = DailiesCompats.stored(handle);
        HolderLookup.Provider registries = registries();
        if (stored == null || registries == null) {
            return null;
        }
        List<Component> pages = new ArrayList<>();
        for (String page : VanillaStates.readPages(stored)) {
            pages.add(Component.Serializer.fromJson(page, registries));
        }
        return new BookViewScreen.BookAccess(pages);
    }

    private static int[] bookCorner(Screen screen) {
        return new int[]{(screen.width - BOOK_WIDTH) / 2, 2};
    }

    // --- The screens ---

    /** A written book read from the hand */
    private static final class BookRead implements ScreenPart {
        private final Stored stored = new Stored();

        @Override
        public byte @Nullable [] capture(Screen screen) {
            HolderLookup.Provider registries = registries();
            Minecraft minecraft = Minecraft.getInstance();
            if (screen.getClass() != BookViewScreen.class || registries == null || minecraft.player == null) {
                return null;
            }
            BookViewScreenAccess book = (BookViewScreenAccess) screen;
            List<Component> pages = book.dailies$bookAccess().pages();
            int hand = -1;
            for (InteractionHand each : InteractionHand.values()) {
                BookViewScreen.BookAccess held = BookViewScreen.BookAccess.fromItem(minecraft.player.getItemInHand(each));
                if (held != null && held.pages().equals(pages)) {
                    hand = each.ordinal();
                    break;
                }
            }
            return VanillaStates.bookRead(new VanillaStates.BookRead(hand, this.stored.handle(pages, each -> json(each, registries)), book.dailies$currentPage()));
        }

        @Override
        public @Nullable Screen build(byte[] state, int version) {
            VanillaStates.BookRead recorded = VanillaStates.readBookRead(state);
            BookViewScreen.BookAccess pages = book(recorded.pages());
            return pages == null ? null : new PlaybackScreens.Book(pages, recorded.page());
        }

        @Override
        public int[] corner(Screen screen) {
            return bookCorner(screen);
        }
    }

    /** A book on a lectern */
    private static final class Lectern implements ScreenPart {
        private final Stored stored = new Stored();

        @Override
        public byte @Nullable [] capture(Screen screen) {
            HolderLookup.Provider registries = registries();
            Minecraft minecraft = Minecraft.getInstance();
            if (screen.getClass() != LecternScreen.class || registries == null || minecraft.player == null) {
                return null;
            }
            BookViewScreenAccess book = (BookViewScreenAccess) screen;
            return VanillaStates.lectern(new VanillaStates.Lectern(this.stored.handle(book.dailies$bookAccess().pages(), each -> json(each, registries)), book.dailies$currentPage(),
                minecraft.player.mayBuild()));
        }

        @Override
        public @Nullable Screen build(byte[] state, int version) {
            VanillaStates.Lectern recorded = VanillaStates.readLectern(state);
            BookViewScreen.BookAccess pages = book(recorded.pages());
            Minecraft minecraft = Minecraft.getInstance();
            if (pages == null || minecraft.player == null) {
                return null;
            }
            // The inventory is only handed on by the lectern's screen, which never uses it
            return new PlaybackScreens.Lectern(pages, recorded.page(), recorded.mayBuild(), minecraft.player.getInventory());
        }

        @Override
        public int[] corner(Screen screen) {
            return bookCorner(screen);
        }
    }

    /** A book and quill written in, or signed */
    private static final class BookWrite implements ScreenPart {
        private final Stored stored = new Stored();

        @Override
        public byte @Nullable [] capture(Screen screen) {
            if (screen.getClass() != BookEditScreen.class) {
                return null;
            }
            BookEditScreenAccess book = (BookEditScreenAccess) screen;
            List<String> pages = book.dailies$pages();
            int page = book.dailies$currentPage();
            String pageText = page >= 0 && page < pages.size() ? pages.get(page) : "";
            // The other pages change only as pages are turned or added, so the page typed on is all a write carries
            String others = pages.size() > 1 ? this.stored.handle(VanillaStates.others(pages, page), each -> each) : null;
            TextFieldHelper field = book.dailies$pageEdit();
            return VanillaStates.bookWrite(new VanillaStates.BookWrite(others, page, pageText, field.getCursorPos(), field.getSelectionPos(), book.dailies$signing(),
                book.dailies$title(), book.dailies$owner().getGameProfile().getName()));
        }

        @Override
        public @Nullable Screen build(byte[] state, int version) {
            VanillaStates.BookWrite recorded = VanillaStates.readBookWrite(state);
            List<String> others = null;
            if (recorded.otherPages() != null) {
                byte[] stored = DailiesCompats.stored(recorded.otherPages());
                if (stored == null) {
                    return null;
                }
                others = VanillaStates.readPages(stored);
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level == null) {
                return null;
            }
            // The writer, by name only, for the signing screen's "by" line: a stand-in never in the world
            String name = recorded.owner().isEmpty() ? "Player" : recorded.owner();
            RemotePlayer owner = new RemotePlayer(minecraft.level, new GameProfile(UUID.nameUUIDFromBytes(("Dailies writer " + name).getBytes(StandardCharsets.UTF_8)), name));
            PlaybackScreens.BookEdit screen = new PlaybackScreens.BookEdit(owner, new ItemStack(Items.WRITABLE_BOOK));
            BookEditScreenAccess book = (BookEditScreenAccess) (Object) screen;
            List<String> pages = recorded.pagesWith(others);
            book.dailies$pages().clear();
            book.dailies$pages().addAll(pages);
            book.dailies$setCurrentPage(Math.max(0, Math.min(recorded.page(), pages.size() - 1)));
            book.dailies$setSigning(recorded.signing());
            book.dailies$setTitle(recorded.title());
            // The field reads the open page, put on above; laying the screen out leaves the cursor where it is
            book.dailies$pageEdit().setSelectionRange(recorded.cursor(), recorded.selection());
            return screen;
        }

        @Override
        public int[] corner(Screen screen) {
            return bookCorner(screen);
        }

        @Override
        public int ticksBetweenWrites() {
            return TYPING_TICKS;
        }
    }

    /** A sign or a hanging sign edited, each its own claim */
    private static final class SignEdit implements ScreenPart {
        private final Class<? extends Screen> screenClass;

        SignEdit(Class<? extends Screen> screenClass) {
            this.screenClass = screenClass;
        }

        @Override
        public byte @Nullable [] capture(Screen screen) {
            if (screen.getClass() != this.screenClass) {
                return null;
            }
            SignEditScreenAccess editing = (SignEditScreenAccess) screen;
            SignBlockEntity sign = editing.dailies$sign();
            Block block = sign.getBlockState().getBlock();
            ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
            TextFieldHelper field = editing.dailies$signField();
            SignText text = editing.dailies$text();
            return VanillaStates.signEdit(new VanillaStates.SignEdit(sign.getBlockPos().asLong(), key.toString(), SignBlock.getWoodType(block).name(),
                sign instanceof HangingSignBlockEntity, editing.dailies$front(), Arrays.asList(editing.dailies$messages().clone()), editing.dailies$line(),
                field == null ? 0 : field.getCursorPos(), field == null ? 0 : field.getSelectionPos(), text.getColor().getName(), text.hasGlowingText()));
        }

        @Override
        public @Nullable Screen build(byte[] state, int version) {
            VanillaStates.SignEdit recorded = VanillaStates.readSignEdit(state);
            ResourceLocation key = ResourceLocation.tryParse(recorded.block());
            Block block = key == null ? null : BuiltInRegistries.BLOCK.getOptional(key).orElse(null);
            if (!(block instanceof EntityBlock maker)) {
                return null;
            }
            // A copy of the sign for the screen to draw and type into, made by the sign's own block so its kind is right;
            // in no world, so the playback world's sign is never touched
            BlockState blockState = block.defaultBlockState();
            BlockEntity made = maker.newBlockEntity(BlockPos.of(recorded.pos()), blockState);
            if (!(made instanceof SignBlockEntity sign)) {
                return null;
            }
            Component[] lines = new Component[4];
            for (int i = 0; i < lines.length; i++) {
                lines[i] = Component.literal(recorded.line(i));
            }
            sign.setText(new SignText(lines, lines.clone(), DyeColor.byName(recorded.colour(), DyeColor.BLACK), recorded.glowing()), recorded.front());
            Screen screen = sign instanceof HangingSignBlockEntity
                ? new PlaybackScreens.HangingSign(sign, recorded.front(), recorded.cursor(), recorded.selection())
                : new PlaybackScreens.Sign(sign, recorded.front(), recorded.cursor(), recorded.selection());
            ((SignEditScreenAccess) screen).dailies$setLine(Math.max(0, Math.min(3, recorded.line())));
            return screen;
        }

        @Override
        public int[] corner(Screen screen) {
            // The sign is drawn about the screen's middle, at a fixed height
            return new int[]{screen.width / 2, 0};
        }

        @Override
        public int ticksBetweenWrites() {
            return TYPING_TICKS;
        }
    }

}
