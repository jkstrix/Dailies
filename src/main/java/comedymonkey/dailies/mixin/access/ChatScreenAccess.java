package comedymonkey.dailies.mixin.access;

import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** What a chat screen was opened with: nothing for the chat key, "/" for the command key, which playback answers differently */
@Mixin(ChatScreen.class)
public interface ChatScreenAccess {

    @Accessor("initial")
    String dailies$initial();

}
