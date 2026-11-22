package comedymonkey.dailies.mixin.access;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.gui.screens.inventory.BookSignScreen;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * What the screen a book and quill is signed on holds, which it keeps to itself: the book screen it came from, the
 * writer and the title being typed. Read while recording; the title is put on the read-only copy playback draws, before
 * it is laid out
 */
@Mixin(BookSignScreen.class)
public interface BookSignScreenAccess {

    @Accessor("bookEditScreen")
    BookEditScreen dailies$bookEditScreen();

    @Accessor("owner")
    Player dailies$owner();

    @Accessor("titleBox")
    EditBox dailies$titleBox();

    @Accessor("titleValue")
    void dailies$setTitleValue(String title);

}
