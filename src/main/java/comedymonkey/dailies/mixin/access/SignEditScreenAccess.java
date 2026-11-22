package comedymonkey.dailies.mixin.access;

import net.minecraft.client.gui.font.TextFieldHelper;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * What a sign's editing screen holds as it is typed on, which it keeps to itself: read while recording, and the line
 * and cursor put on the read-only copy playback draws
 */
@Mixin(AbstractSignEditScreen.class)
public interface SignEditScreenAccess {

    @Accessor("sign")
    SignBlockEntity dailies$sign();

    @Accessor("text")
    SignText dailies$text();

    @Accessor("messages")
    String[] dailies$messages();

    @Accessor("isFrontText")
    boolean dailies$front();

    @Accessor("line")
    int dailies$line();

    @Accessor("line")
    void dailies$setLine(int line);

    @Accessor("signField")
    TextFieldHelper dailies$signField();

}
