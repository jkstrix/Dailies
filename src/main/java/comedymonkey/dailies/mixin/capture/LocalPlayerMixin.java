package comedymonkey.dailies.mixin.capture;

import comedymonkey.dailies.capture.Capture;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A server never tells a player about their own arm swings, so recording has to see them where they start.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {

    @Inject(method = "swing", at = @At("HEAD"))
    private void dailies$swing(InteractionHand hand, CallbackInfo ci) {
        Capture.ownSwing((LocalPlayer) (Object) this, hand);
    }

}
