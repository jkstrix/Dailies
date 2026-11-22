package comedymonkey.dailies.mixin.capture;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import comedymonkey.dailies.capture.Capture;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.component.SwingAnimation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A server never tells a player about their own arm swings, so recording sees them where they start. Since 26.1 a local
 * player has no swing of its own: it swings through LivingEntity's, and only a swing that really starts is one a server
 * would have shown everyone else.
 */
@Mixin(LivingEntity.class)
public abstract class OwnSwingMixin {

    @ModifyExpressionValue(method = "swing(Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/component/SwingAnimation;Z)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity$SwingState;startIfAble(Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/component/SwingAnimation;I)Z"))
    private boolean dailies$swing(boolean started, InteractionHand hand, SwingAnimation animation, boolean sendToSwingingEntity) {
        if (started && (Object) this instanceof LocalPlayer player) {
            Capture.ownSwing(player, hand, animation);
        }
        return started;
    }

}
