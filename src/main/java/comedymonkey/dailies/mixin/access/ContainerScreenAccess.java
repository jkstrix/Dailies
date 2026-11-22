package comedymonkey.dailies.mixin.access;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * A window's own tick, without the game's wrapper around it, which closes the window through the connection when the
 * player is not alive: playback calls it only for the few of the game's windows that move by themselves
 */
@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenAccess {

    @Invoker("containerTick")
    void dailies$containerTick();

    /** The slot under the mouse as the window last worked it out, or null */
    @Accessor("hoveredSlot")
    @Nullable Slot dailies$hoveredSlot();

}
