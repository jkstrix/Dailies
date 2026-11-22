package comedymonkey.dailies.windows.vanilla;

import comedymonkey.dailies.mixin.access.AbstractWidgetAccess;
import comedymonkey.dailies.mixin.access.BookEditScreenAccess;
import comedymonkey.dailies.mixin.access.BookSignScreenAccess;
import comedymonkey.dailies.mixin.access.MultiLineEditBoxAccess;
import comedymonkey.dailies.mixin.access.SignEditScreenAccess;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.gui.components.Whence;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.gui.screens.inventory.BookSignScreen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.inventory.HangingSignEditScreen;
import net.minecraft.client.gui.screens.inventory.LecternScreen;
import net.minecraft.client.gui.screens.inventory.SignEditScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.LecternMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The game's own book, lectern and sign screens as playback builds them from a state: each the game's screen, so it is
 * drawn exactly as the recorder's game drew it, with what it does with input taken away. Playback gives them none, but
 * each live screen sends something as it is used or closed (a page turn to the lectern, the book's pages, a sign's lines,
 * a command clicked in a book), and a screen built for playback must never send anything or change the world, so every
 * way in to that is shut here too.
 */
final class PlaybackScreens {

    private PlaybackScreens() {
    }

    /** A written book as it was read from the hand, open at the recorded page */
    static final class Book extends BookViewScreen {
        private final int page;

        Book(BookAccess pages, int page) {
            super(pages);
            this.page = page;
        }

        @Override
        protected void init() {
            super.init();
            // Only once laid out: setting the page before then reaches for page buttons that are not made yet
            this.setPage(this.page);
        }

        @Override
        protected boolean handleClickEvent(@Nullable ClickEvent event) {
            // A page can run a command or open a link when clicked
            return false;
        }

        @Override
        protected void closeContainerOnServer() {
        }

        @Override
        public void onClose() {
        }
    }

    /**
     * A book on a lectern, open at the recorded page, with the buttons the reader had: Done and Take Book for a reader who
     * could take it, Done alone otherwise. The live screen asks the game's own player, who in playback is the viewer.
     */
    static final class Lectern extends LecternScreen {
        private final BookAccess pages;
        private final int page;
        private final boolean mayBuild;

        Lectern(BookAccess pages, int page, boolean mayBuild, Inventory inventory) {
            // A menu of its own that no server knows: the lectern's screen listens to it, and it never changes
            super(new LecternMenu(0), inventory, Component.empty());
            this.pages = pages;
            this.page = page;
            this.mayBuild = mayBuild;
        }

        @Override
        protected void init() {
            super.init();
            this.setBookAccess(this.pages);
            this.setPage(this.page);
        }

        @Override
        protected void createMenuControls() {
            // As LecternScreen and BookViewScreen lay them out, doing nothing when pressed
            int buttonY = this.menuControlsTop();
            if (this.mayBuild) {
                int middle = this.width / 2;
                this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> {
                }).pos(middle - 98 - 2, buttonY).width(98).build());
                this.addRenderableWidget(Button.builder(Component.translatable("lectern.take_book"), button -> {
                }).pos(middle + 2, buttonY).width(98).build());
            } else {
                this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> {
                }).pos((this.width - 200) / 2, buttonY).width(200).build());
            }
        }

        // The live screen sends each of these to the server's lectern, and closing it closes the game's player's window
        @Override
        protected void pageBack() {
        }

        @Override
        protected void pageForward() {
        }

        @Override
        protected boolean forcePage(int page) {
            return false;
        }

        @Override
        protected boolean handleClickEvent(@Nullable ClickEvent event) {
            return false;
        }

        @Override
        protected void closeContainerOnServer() {
        }

        @Override
        public void onClose() {
        }
    }

    /**
     * A book and quill as it was being written in or signed. Its pages, page, cursor and title are put on it before it is
     * laid out ({@link VanillaCompat}); its Done and Sign buttons and its keys would send the book's pages to the server,
     * so it takes no input at all.
     */
    static final class BookEdit extends BookEditScreen {
        private int cursor = -1;
        private int selection = -1;

        BookEdit(Player owner, ItemStack book) {
            super(owner, book, InteractionHand.MAIN_HAND, book.getOrDefault(DataComponents.WRITABLE_BOOK_CONTENT, WritableBookContent.EMPTY));
        }

        /** Where the cursor and the selection's other end go once the page's box is made */
        void cursorAfterLayout(int cursor, int selection) {
            this.cursor = cursor;
            this.selection = selection;
        }

        @Override
        protected void init() {
            super.init();
            MultiLineEditBox page = ((BookEditScreenAccess) (Object) this).dailies$page();
            if (page == null || this.cursor < 0) {
                return;
            }
            MultilineTextField field = ((MultiLineEditBoxAccess) page).dailies$textField();
            field.seekCursor(Whence.ABSOLUTE, this.selection);
            field.setSelecting(true);
            field.seekCursor(Whence.ABSOLUTE, this.cursor);
            field.setSelecting(false);
            // Shown with the keys, so its cursor is drawn, without turning the game's text input on
            ((AbstractWidgetAccess) page).dailies$setFocusedQuietly(true);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            return false;
        }

        @Override
        public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
            return false;
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            return false;
        }

        @Override
        public boolean charTyped(CharacterEvent event) {
            return false;
        }

        @Override
        public void onClose() {
        }
    }

    /**
     * A book and quill being signed: the title typed so far, put on before it is laid out. Its buttons would sign the book
     * or go back to writing, so it takes no input at all.
     */
    static final class BookSign extends BookSignScreen {

        BookSign(BookEditScreen book, Player owner, List<String> pages) {
            super(book, owner, InteractionHand.MAIN_HAND, pages);
        }

        @Override
        protected void init() {
            super.init();
            EditBox title = ((BookSignScreenAccess) (Object) this).dailies$titleBox();
            if (title != null) {
                // Shown with the keys, so its cursor is drawn, without turning the game's text input on
                ((AbstractWidgetAccess) title).dailies$setFocusedQuietly(true);
            }
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            return false;
        }

        @Override
        public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
            return false;
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            return false;
        }

        @Override
        public boolean charTyped(CharacterEvent event) {
            return false;
        }

        @Override
        public void onClose() {
        }
    }

    /**
     * The Done button a sign's screen has, laid out as the game's does and doing nothing. The game's own layout also turns
     * the game's text input on, which a screen playback draws must not do
     */
    private static void signDone(AbstractSignEditScreen screen, java.util.function.Consumer<Button> add) {
        add.accept(Button.builder(CommonComponents.GUI_DONE, button -> {
        }).bounds(screen.width / 2 - 100, screen.height / 4 + 144, 200, 20).build());
    }

    /**
     * Puts the recorded cursor and selection on a sign's screen once it is laid out, which is when its text field is made.
     * The field reads the line being typed on, which is put on before then.
     */
    private static void cursor(Object screen, int cursor, int selection) {
        ((SignEditScreenAccess) screen).dailies$signField().setSelectionRange(cursor, selection);
    }

    /**
     * A sign as it was being edited, on a copy of the sign made for drawing (the playback world's sign is never touched).
     * The live screen sends the sign's lines as it is closed, and closes itself when the sign is out of reach; this one
     * does neither.
     */
    static final class Sign extends SignEditScreen {
        private final int cursor;
        private final int selection;

        Sign(SignBlockEntity sign, boolean front, int cursor, int selection) {
            super(sign, front ? SignTextSlot.FRONT : SignTextSlot.BACK, false);
            this.cursor = cursor;
            this.selection = selection;
        }

        @Override
        protected void init() {
            signDone(this, this::addRenderableWidget);
            cursor(this, this.cursor, this.selection);
        }

        @Override
        public void tick() {
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            return false;
        }

        @Override
        public boolean charTyped(CharacterEvent event) {
            return false;
        }

        @Override
        public void onClose() {
        }

        @Override
        public void removed() {
        }
    }

    /** A hanging sign as it was being edited: as {@link Sign} */
    static final class HangingSign extends HangingSignEditScreen {
        private final int cursor;
        private final int selection;

        HangingSign(SignBlockEntity sign, boolean front, int cursor, int selection) {
            super(sign, front ? SignTextSlot.FRONT : SignTextSlot.BACK, false);
            this.cursor = cursor;
            this.selection = selection;
        }

        @Override
        protected void init() {
            signDone(this, this::addRenderableWidget);
            cursor(this, this.cursor, this.selection);
        }

        @Override
        public void tick() {
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            return false;
        }

        @Override
        public boolean charTyped(CharacterEvent event) {
            return false;
        }

        @Override
        public void onClose() {
        }

        @Override
        public void removed() {
        }
    }

}
