package comedymonkey.dailies.mixin.access;

import net.minecraft.client.gui.components.MultilineTextField;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The other end of a selection in a box of several lines, the cursor being one end: for a book and quill's page */
@Mixin(MultilineTextField.class)
public interface MultilineTextFieldAccess {

    @Accessor("selectCursor")
    int dailies$selectCursor();

}
