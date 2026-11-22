package comedymonkey.dailies.mixin.playback;

import comedymonkey.dailies.playback.FrameHold;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The end of drawing the world, after every renderer and shader pack has finished with it and before the HUD and the
 * screens are drawn: where {@link FrameHold} keeps the picture, or puts the kept one back during a jump. The world is
 * still drawn during a jump, so everything the game and other mods do each frame goes on as ever. Allowed to be missing:
 * jumps are then shown as they run.
 */
@Mixin(GameRenderer.class)
public abstract class HoldFrameMixin {

    @Inject(method = "renderLevel", at = @At("RETURN"), require = 0)
    private void dailies$holdFrame(CallbackInfo ci) {
        FrameHold.worldDrawn();
    }

}
