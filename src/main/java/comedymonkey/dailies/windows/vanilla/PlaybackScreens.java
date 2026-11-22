package comedymonkey.dailies.windows.vanilla;

import comedymonkey.dailies.mixin.access.SignEditScreenAccess;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.inventory.HangingSignEditScreen;
import net.minecraft.client.gui.screens.inventory.LecternScreen;
import net.minecraft.client.gui.screens.inventory.SignEditScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.LecternMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.SignBlockEntity;

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
        public boolean handleComponentClicked(Style style) {
            // A page can run a command or open a link when clicked
            return false;
        }

        @Override
        protected void closeScreen() {
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
            if (this.mayBuild) {
                this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> {
                }).bounds(this.width / 2 - 100, 196, 98, 20).build());
                this.addRenderableWidget(Button.builder(Component.translatable("lectern.take_book"), button -> {
                }).bounds(this.width / 2 + 2, 196, 98, 20).build());
            } else {
                this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> {
                }).bounds(this.width / 2 - 100, 196, 200, 20).build());
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
        public boolean handleComponentClicked(Style style) {
            return false;
        }

        @Override
        protected void closeScreen() {
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

        BookEdit(Player owner, ItemStack book) {
            super(owner, book, InteractionHand.MAIN_HAND);
        }

        @Override
        public boolean mouseClicked(double x, double y, int button) {
            return false;
        }

        @Override
        public boolean mouseDragged(double x, double y, int button, double dragX, double dragY) {
            return false;
        }

        @Override
        public boolean keyPressed(int key, int scan, int modifiers) {
            return false;
        }

        @Override
        public boolean charTyped(char typed, int modifiers) {
            return false;
        }

        @Override
        public void onClose() {
        }
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
            super(sign, front, false);
            this.cursor = cursor;
            this.selection = selection;
        }

        @Override
        protected void init() {
            super.init();
            cursor(this, this.cursor, this.selection);
        }

        @Override
        public void tick() {
        }

        @Override
        public boolean keyPressed(int key, int scan, int modifiers) {
            return false;
        }

        @Override
        public boolean charTyped(char typed, int modifiers) {
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
            super(sign, front, false);
            this.cursor = cursor;
            this.selection = selection;
        }

        @Override
        protected void init() {
            super.init();
            cursor(this, this.cursor, this.selection);
        }

        @Override
        public void tick() {
        }

        @Override
        public boolean keyPressed(int key, int scan, int modifiers) {
            return false;
        }

        @Override
        public boolean charTyped(char typed, int modifiers) {
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
