package comedymonkey.dailies.mixin.access;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** A window's data slots (a furnace's flame and arrow, a brewing stand's bubbles), which the game keeps to itself, for recording them */
@Mixin(AbstractContainerMenu.class)
public interface ContainerMenuAccess {

    @Accessor("dataSlots")
    List<DataSlot> dailies$dataSlots();

}
