package comedymonkey.dailies.mixin.access;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * A window's own tick, without the game's wrapper around it, which closes the window through the connection when the
 * player is not alive: playback calls it only for the few of the game's windows that move by themselves
 */
@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenAccess {

    @Invoker("containerTick")
    void dailies$containerTick();

}
