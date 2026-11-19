package comedymonkey.dailies.mixin.access;

import net.minecraft.client.gui.font.TextFieldHelper;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * What a book and quill's screen holds as it is written in, which it keeps to itself: read while recording, and put on
 * the read-only copy playback draws, before it is laid out
 */
@Mixin(BookEditScreen.class)
public interface BookEditScreenAccess {

    @Accessor("owner")
    Player dailies$owner();

    @Accessor("pages")
    List<String> dailies$pages();

    @Accessor("currentPage")
    int dailies$currentPage();

    @Accessor("currentPage")
    void dailies$setCurrentPage(int page);

    @Accessor("isSigning")
    boolean dailies$signing();

    @Accessor("isSigning")
    void dailies$setSigning(boolean signing);

    @Accessor("title")
    String dailies$title();

    @Accessor("title")
    void dailies$setTitle(String title);

    @Accessor("pageEdit")
    TextFieldHelper dailies$pageEdit();

}
