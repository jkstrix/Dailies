package comedymonkey.dailies.mixin.access;

import net.minecraft.client.player.FirstPersonHandsAndItems;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** How far up each of the player's own hands is (1 fully up), as the game keeps it, for the recording */
@Mixin(FirstPersonHandsAndItems.class)
public interface FirstPersonHandsAccess {

    @Accessor("mainHandHeight")
    float dailies$mainHandHeight();

    @Accessor("offHandHeight")
    float dailies$offHandHeight();
}
