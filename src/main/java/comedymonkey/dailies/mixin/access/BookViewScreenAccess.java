package comedymonkey.dailies.mixin.access;

import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The pages a book screen shows and the page open, which it keeps to itself: recorded for a written book read from the hand or on a lectern */
@Mixin(BookViewScreen.class)
public interface BookViewScreenAccess {

    @Accessor("bookAccess")
    BookViewScreen.BookAccess dailies$bookAccess();

    @Accessor("currentPage")
    int dailies$currentPage();

}
