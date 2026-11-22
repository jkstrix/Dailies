package comedymonkey.dailies.mixin.playback;

import comedymonkey.dailies.windows.WindowLayer;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The inventory draws the game's own player beside the armour slots, and so do mods' windows that borrow its drawing
 * (the curios inventory). In playback the game's own player is the person watching, so while a recorded window is
 * being drawn the recorder is drawn there instead, as the recorder's own game drew them
 */
@Mixin(InventoryScreen.class)
public abstract class WindowEntityMixin {

    @ModifyVariable(method = "renderEntityInInventoryFollowsAngle", at = @At("HEAD"), argsOnly = true)
    private static LivingEntity dailies$recorderInWindow(LivingEntity entity) {
        return WindowLayer.entityShown(entity);
    }
}
