package comedymonkey.dailies.mixin.access;

import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The text a box of several lines holds, with its cursor and selection: for a book and quill's page */
@Mixin(MultiLineEditBox.class)
public interface MultiLineEditBoxAccess {

    @Accessor("textField")
    MultilineTextField dailies$textField();

}
