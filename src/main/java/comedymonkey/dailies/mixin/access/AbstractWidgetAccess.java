package comedymonkey.dailies.mixin.access;

import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * A control shown as having the keys without being given them: a box on a screen playback draws shows its cursor, but
 * must not turn the game's text input on, as setting the focus the usual way does
 */
@Mixin(AbstractWidget.class)
public interface AbstractWidgetAccess {

    @Accessor("focused")
    void dailies$setFocusedQuietly(boolean focused);

}
