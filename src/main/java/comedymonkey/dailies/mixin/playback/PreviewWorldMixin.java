package comedymonkey.dailies.mixin.playback;

import comedymonkey.dailies.ui.PreviewProgress;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * While the path preview plays a Smart Camera stretch at up to twenty times its speed (specification EDIT-8), the world
 * is not drawn at all: only the screen is, which is the preview's progress plate. Drawing the world is most of what a
 * frame costs, and every millisecond not spent on it goes to the ticks that are played between frames. The game's
 * own flag for this is the one it uses while a world is loading, so nothing else of the frame changes.
 */
@Mixin(Minecraft.class)
public abstract class PreviewWorldMixin {

    // Not required: should another mod have taken this call, the preview still works, only drawing the world as it goes
    @ModifyArg(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V"),
        index = 1, require = 0)
    private boolean dailies$noWorldWhilePreviewing(boolean renderLevel) {
        return PreviewProgress.drawWorld(renderLevel);
    }

}
